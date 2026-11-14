package eu.wohlben.qits.edge.dns;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsQueryEncoder;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DefaultDnsQuery;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.handler.codec.dns.DnsOpCode;
import io.netty.handler.codec.dns.DnsQuery;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.handler.codec.dns.TcpDnsQueryEncoder;
import io.netty.util.NetUtil;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/**
 * The tunnel socket's forwarder (qits-2262): a name outside the zone, asked on the tunnel, is
 * passed to an upstream resolver and its answer relayed back. A WireGuard phone app sets one {@code
 * DNS =} for the whole device, so a REFUSED there would take the phone off the internet.
 *
 * <p><b>The answer is relayed as bytes, not re-encoded.</b> netty's record decoder keeps most RDATA
 * as it came off the wire, compression pointers included — an NXDOMAIN's SOA, an MX — and those
 * pointers are offsets into the upstream's message, meaningless in a newly encoded one. So the
 * upstream's message is relayed whole, with only its 16-bit id put back to the client's: question,
 * every section, the rcode (NXDOMAIN and the rest as-is) and the flags (RA, TC) are the upstream's
 * own. A UDP query is forwarded over UDP and a TCP query over TCP, so a truncated UDP answer sends
 * the client to TCP, where it gets the whole one.
 *
 * <p>The upstream query is built afresh: the one question, RD as the client set it, a fresh random
 * id, and the client's EDNS OPT record (payload size and flags, no options) when it sent one. Each
 * exchange is one new socket from an ephemeral port, connected to the upstream so nothing but the
 * upstream is read; a response is taken only with the id sent and the QR bit set.
 *
 * <p>Upstreams are tried in order, the next only when one fails outright (refused connection, ICMP
 * port unreachable); the whole exchange has one deadline, {@code qits.edge.dns.upstream-timeout},
 * and missing it, or running out of upstreams, is SERVFAIL. At most {@link #MAX_IN_FLIGHT}
 * exchanges run at once; beyond that a query is SERVFAIL rather than another socket.
 */
final class Forwarder {

  private static final Logger LOG = Logger.getLogger(Forwarder.class);

  /** The DNS port, where a stated upstream names none. */
  static final int DNS_PORT = 53;

  /** Exchanges at once, each one socket; beyond, SERVFAIL. */
  static final int MAX_IN_FLIGHT = 256;

  private static final int HEADER = 12;

  private final String zone;
  private final List<InetSocketAddress> upstreams;
  private final long timeoutMillis;
  private final AtomicInteger inFlight = new AtomicInteger();

  /**
   * @param zone the stated domain, normalised — names under it are never forwarded
   * @param upstreams the resolvers, in order; not empty
   */
  Forwarder(String zone, List<InetSocketAddress> upstreams, Duration timeout) {
    if (upstreams.isEmpty()) {
      throw new IllegalArgumentException("a forwarder needs an upstream");
    }
    this.zone = zone;
    this.upstreams = List.copyOf(upstreams);
    this.timeoutMillis = Math.max(1, timeout.toMillis());
  }

  List<InetSocketAddress> upstreams() {
    return upstreams;
  }

  /**
   * Whether {@code query} is this forwarder's: a standard query with one question for a name
   * outside the zone. Anything else — the zone, a malformed query, another opcode — is {@link
   * TunnelDns.Answers}' to answer.
   */
  boolean forwards(DnsQuery query) {
    if (!query.opCode().equals(DnsOpCode.QUERY) || query.count(DnsSection.QUESTION) != 1) {
      return false;
    }
    return query.recordAt(DnsSection.QUESTION) instanceof DnsQuestion question
        && !TunnelDns.inZone(TunnelDns.normalise(question.name()), zone);
  }

  /** Forward a UDP query and write the upstream's answer, or SERVFAIL, back to its sender. */
  void udp(ChannelHandlerContext ctx, DatagramDnsQuery query) {
    InetSocketAddress client = query.sender();
    InetSocketAddress local = query.recipient();
    int id = query.id();
    DnsQuestion question = question(query);
    boolean recursionDesired = query.isRecursionDesired();
    GenericFutureListener<Future<byte[]>> relay =
        done -> {
          if (done.isSuccess()) {
            ctx.writeAndFlush(new DatagramPacket(withId(done.getNow(), id), client, local));
          } else {
            failed(question, client, done.cause());
            ctx.writeAndFlush(
                servfail(
                    new DatagramDnsResponse(local, client, id, DnsOpCode.QUERY),
                    question,
                    recursionDesired));
          }
        };
    exchange(ctx.channel().eventLoop(), query, false).addListener(relay);
  }

  /** Forward a TCP query and write the upstream's answer, or SERVFAIL, back on its connection. */
  void tcp(ChannelHandlerContext ctx, DnsQuery query) {
    int id = query.id();
    DnsQuestion question = question(query);
    boolean recursionDesired = query.isRecursionDesired();
    GenericFutureListener<Future<byte[]>> relay =
        done -> {
          if (done.isSuccess()) {
            ByteBuf message = withId(done.getNow(), id);
            ByteBuf framed = ctx.alloc().buffer(2 + message.readableBytes());
            framed.writeShort(message.readableBytes()).writeBytes(message);
            message.release();
            ctx.writeAndFlush(framed);
          } else {
            failed(question, ctx.channel().remoteAddress(), done.cause());
            ctx.writeAndFlush(
                servfail(new DefaultDnsResponse(id, DnsOpCode.QUERY), question, recursionDesired));
          }
        };
    exchange(ctx.channel().eventLoop(), query, true).addListener(relay);
  }

  /**
   * Ask the upstreams {@code query}'s one question; the promise holds the upstream's whole message
   * under the id this exchange sent. Everything taken from {@code query} is copied before this
   * returns, so the caller may release it at once.
   */
  Future<byte[]> exchange(EventLoop loop, DnsQuery query, boolean overTcp) {
    Promise<byte[]> answer = loop.newPromise();
    if (inFlight.incrementAndGet() > MAX_IN_FLIGHT) {
      inFlight.decrementAndGet();
      return answer.setFailure(
          new IllegalStateException(MAX_IN_FLIGHT + " upstream exchanges are running already"));
    }
    Ask ask =
        new Ask(
            ThreadLocalRandom.current().nextInt(1 << 16),
            question(query),
            query.isRecursionDesired(),
            opt(query));
    ScheduledFuture<?> deadline =
        loop.schedule(
            () ->
                answer.tryFailure(
                    new TimeoutException("no upstream answered within " + timeoutMillis + " ms")),
            timeoutMillis,
            TimeUnit.MILLISECONDS);
    answer.addListener(
        ignored -> {
          deadline.cancel(false);
          inFlight.decrementAndGet();
        });
    attempt(loop, 0, ask, overTcp, answer, null);
    return answer;
  }

  /** What is sent upstream, copied off the client's query. */
  private record Ask(int id, DnsQuestion question, boolean recursionDesired, DnsRecord opt) {

    <Q extends DnsQuery> Q fill(Q query) {
      query.setRecursionDesired(recursionDesired);
      query.addRecord(DnsSection.QUESTION, question);
      if (opt != null) {
        query.addRecord(DnsSection.ADDITIONAL, opt);
      }
      return query;
    }
  }

  private void attempt(
      EventLoop loop,
      int index,
      Ask ask,
      boolean overTcp,
      Promise<byte[]> answer,
      Throwable previous) {
    if (answer.isDone()) {
      return;
    }
    if (index >= upstreams.size()) {
      answer.tryFailure(
          previous != null ? previous : new IllegalStateException("no upstream answered"));
      return;
    }
    InetSocketAddress upstream = upstreams.get(index);
    AtomicBoolean movedOn = new AtomicBoolean();
    Consumer<Throwable> next =
        cause -> {
          if (movedOn.compareAndSet(false, true)) {
            attempt(loop, index + 1, ask, overTcp, answer, cause);
          }
        };
    UpstreamHandler handler = new UpstreamHandler(ask.id(), answer, next);
    Bootstrap bootstrap = new Bootstrap().group(loop);
    ChannelFuture connected =
        overTcp
            ? bootstrap
                .channelFactory(NioSocketChannel::new)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) Math.min(timeoutMillis, 30_000))
                .handler(new UpstreamTcpInitializer(handler))
                .connect(upstream)
            : bootstrap
                .channelFactory(NioDatagramChannel::new)
                .handler(new UpstreamUdpInitializer(handler))
                .connect(upstream);
    Channel channel = connected.channel();
    answer.addListener(ignored -> channel.close());
    connected.addListener(
        (ChannelFutureListener)
            done -> {
              if (!done.isSuccess()) {
                next.accept(done.cause());
                return;
              }
              Object sent =
                  overTcp
                      ? ask.fill(new DefaultDnsQuery(ask.id(), DnsOpCode.QUERY))
                      : ask.fill(new DatagramDnsQuery(null, upstream, ask.id(), DnsOpCode.QUERY));
              channel
                  .writeAndFlush(sent)
                  .addListener(
                      (ChannelFutureListener)
                          written -> {
                            if (!written.isSuccess()) {
                              channel.close();
                              next.accept(written.cause());
                            }
                          });
            });
  }

  /** A copy of {@code query}'s one question, free of the query's buffers. */
  private static DnsQuestion question(DnsQuery query) {
    DnsQuestion asked = query.recordAt(DnsSection.QUESTION);
    return new DefaultDnsQuestion(asked.name(), asked.type(), asked.dnsClass());
  }

  /**
   * The client's EDNS OPT record as payload size, extended rcode, version and flags — its class and
   * TTL — with no options; null when it sent none. Copied, so nothing holds the client's buffer.
   */
  private static DnsRecord opt(DnsQuery query) {
    for (int i = 0; i < query.count(DnsSection.ADDITIONAL); i++) {
      DnsRecord record = query.recordAt(DnsSection.ADDITIONAL, i);
      if (record.type().equals(DnsRecordType.OPT)) {
        return new DefaultDnsRawRecord(
            record.name(),
            DnsRecordType.OPT,
            record.dnsClass(),
            record.timeToLive(),
            Unpooled.EMPTY_BUFFER);
      }
    }
    return null;
  }

  /** The upstream's message, as a buffer, with the client's id where the upstream's was. */
  private static ByteBuf withId(byte[] message, int id) {
    ByteBuf buffer = Unpooled.wrappedBuffer(message.clone());
    buffer.setShort(0, id);
    return buffer;
  }

  private static <R extends DnsResponse> R servfail(
      R response, DnsQuestion question, boolean recursionDesired) {
    response.setRecursionDesired(recursionDesired);
    response.addRecord(DnsSection.QUESTION, question);
    response.setCode(DnsResponseCode.SERVFAIL);
    return response;
  }

  private static void failed(DnsQuestion question, Object from, Throwable cause) {
    LOG.debugf(
        "%s %s from %s: SERVFAIL, the upstream did not answer: %s",
        question.type().name(), question.name(), from, String.valueOf(cause));
  }

  // --- the upstream list
  // ------------------------------------------------------------------------------------------

  /**
   * The upstreams: {@code stated}, a comma list of {@code address[:port]} ({@code [v6]:port} for an
   * IPv6 address with a port), when set; otherwise the {@code nameserver} lines of {@code
   * resolvConf}, the container's own resolver, on port 53. Only address literals: an upstream that
   * needs a lookup would be looked up through itself. Anything in {@code own} — this responder's
   * own sockets, which a forward would loop back into — is dropped, and so is an unreadable entry;
   * both are logged.
   */
  static List<InetSocketAddress> upstreams(
      Optional<String> stated, String resolvConf, Collection<InetSocketAddress> own) {
    String list = stated.map(String::strip).filter(value -> !value.isEmpty()).orElse(null);
    List<String> entries = new ArrayList<>();
    if (list != null) {
      for (String entry : list.split(",")) {
        if (!entry.isBlank()) {
          entries.add(entry.strip());
        }
      }
    } else if (resolvConf != null) {
      for (String line : resolvConf.split("\n")) {
        String[] words = line.strip().split("\\s+");
        if (words.length >= 2 && words[0].equals("nameserver")) {
          entries.add(words[1]);
        }
      }
    }
    List<InetSocketAddress> found = new ArrayList<>();
    for (String entry : entries) {
      InetSocketAddress upstream = address(entry);
      if (upstream == null) {
        LOG.warnf("the tunnel DNS upstream `%s` is not an address[:port] literal; skipped", entry);
      } else if (loops(upstream, own)) {
        LOG.warnf(
            "the tunnel DNS upstream %s is this responder's own socket, a forwarding loop; skipped",
            entry);
      } else if (!found.contains(upstream)) {
        found.add(upstream);
      }
    }
    return List.copyOf(found);
  }

  /** {@code /etc/resolv.conf}, or null when it cannot be read. */
  static String resolvConf() {
    try {
      return Files.readString(Path.of("/etc/resolv.conf"), StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException unreadable) {
      return null;
    }
  }

  private static boolean loops(InetSocketAddress upstream, Collection<InetSocketAddress> own) {
    for (InetSocketAddress socket : own) {
      if (socket.getPort() == upstream.getPort()
          && (socket.getAddress().equals(upstream.getAddress())
              || upstream.getAddress().isAnyLocalAddress())) {
        return true;
      }
    }
    return false;
  }

  /** {@code a.b.c.d}, {@code a.b.c.d:port}, a bare IPv6 literal or {@code [v6]:port}; else null. */
  static InetSocketAddress address(String entry) {
    String host = entry;
    int port = DNS_PORT;
    if (entry.startsWith("[")) {
      int close = entry.indexOf(']');
      if (close < 0) {
        return null;
      }
      host = entry.substring(1, close);
      String rest = entry.substring(close + 1);
      if (!rest.isEmpty()) {
        if (!rest.startsWith(":")) {
          return null;
        }
        port = port(rest.substring(1));
      }
    } else if (entry.indexOf(':') == entry.lastIndexOf(':') && entry.indexOf(':') > 0) {
      host = entry.substring(0, entry.indexOf(':'));
      port = port(entry.substring(entry.indexOf(':') + 1));
    }
    if (port < 1) {
      return null;
    }
    InetAddress address = NetUtil.createInetAddressFromIpAddressString(host);
    return address == null ? null : new InetSocketAddress(address, port);
  }

  private static int port(String digits) {
    try {
      int port = Integer.parseInt(digits);
      return port <= 65535 ? port : -1;
    } catch (NumberFormatException unreadable) {
      return -1;
    }
  }

  // --- the upstream sockets
  // ---------------------------------------------------------------------------------------

  @RegisterForReflection(methods = true, fields = false)
  static final class UpstreamUdpInitializer extends ChannelInitializer<DatagramChannel> {

    private final UpstreamHandler handler;

    UpstreamUdpInitializer(UpstreamHandler handler) {
      this.handler = handler;
    }

    @Override
    protected void initChannel(DatagramChannel channel) {
      channel.pipeline().addLast(new DatagramDnsQueryEncoder(), handler);
    }
  }

  @RegisterForReflection(methods = true, fields = false)
  static final class UpstreamTcpInitializer extends ChannelInitializer<SocketChannel> {

    private final UpstreamHandler handler;

    UpstreamTcpInitializer(UpstreamHandler handler) {
      this.handler = handler;
    }

    @Override
    protected void initChannel(SocketChannel channel) {
      channel
          .pipeline()
          .addLast(
              new TcpDnsQueryEncoder(),
              new LengthFieldBasedFrameDecoder(0xFFFF, 0, 2, 0, 2),
              handler);
    }
  }

  /**
   * Reads one exchange's socket: a datagram over UDP, one length-delimited frame over TCP. The
   * first message under the id sent, with QR set, is the answer; anything else is ignored. An
   * error, or a TCP connection closed before the answer, moves on to the next upstream.
   */
  @RegisterForReflection(methods = true, fields = false)
  static final class UpstreamHandler extends ChannelInboundHandlerAdapter {

    private final int id;
    private final Promise<byte[]> answer;
    private final Consumer<Throwable> next;

    UpstreamHandler(int id, Promise<byte[]> answer, Consumer<Throwable> next) {
      this.id = id;
      this.answer = answer;
      this.next = next;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
      try {
        ByteBuf content =
            msg instanceof DatagramPacket packet
                ? packet.content()
                : msg instanceof ByteBuf buffer ? buffer : null;
        if (content != null
            && content.readableBytes() >= HEADER
            && content.getUnsignedShort(content.readerIndex()) == id
            && (content.getByte(content.readerIndex() + 2) & 0x80) != 0) {
          answer.trySuccess(ByteBufUtil.getBytes(content));
        }
      } finally {
        ReferenceCountUtil.release(msg);
      }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
      if (!answer.isDone()) {
        next.accept(new ClosedChannelException());
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      next.accept(cause);
      ctx.close();
    }
  }
}
