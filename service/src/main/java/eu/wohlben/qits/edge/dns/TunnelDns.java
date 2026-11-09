package eu.wohlben.qits.edge.dns;

import eu.wohlben.qits.edge.EdgeConfig;
import eu.wohlben.qits.edge.EdgeRouter;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsQueryDecoder;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DatagramDnsResponseEncoder;
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
import io.netty.handler.codec.dns.TcpDnsQueryDecoder;
import io.netty.handler.codec.dns.TcpDnsResponseEncoder;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.jboss.logging.Logger;

/**
 * The private edge's tunnel DNS responder (epic qits-1976): what a WireGuard peer's resolver asks
 * for every name under {@code qits.edge.domain}.
 *
 * <p><b>One question decides the answer</b>: would this edge route a request with that Host to a
 * snapshot it holds? That is asked of {@link EdgeRouter#reach}, the router's own reading and
 * projection join, so the two cannot disagree. Such a name is answered with the local address the
 * query arrived on — over the tunnel, {@code 100.96.0.1}, this very process — and every other name
 * in the zone, the apex and unknown labels included, with the public edge's address. A peer
 * therefore reaches a private service through the tunnel and everything else the way the rest of
 * the internet does.
 *
 * <ul>
 *   <li>A record type other than A for an in-zone name: NOERROR with no answer — this edge has no
 *       IPv6 and no other records, and saying NXDOMAIN would deny the A record too.
 *   <li>A name outside the zone: REFUSED. This is not a recursive resolver.
 *   <li>A query with other than one question: FORMERR. A packet the codec cannot read at all is
 *       dropped: there is no header to answer it under.
 *   <li>Until the routes view is ready — the same head-reached condition {@code /q/health/ready}
 *       reports — every query is SERVFAIL, so a private name that has not been read yet is never
 *       sent to the public address.
 * </ul>
 *
 * <p>It runs on a small event loop group of its own, closed at shutdown, with netty's DNS codec
 * over both UDP and TCP on {@code qits.edge.dns.bind}:{@code qits.edge.dns.port}. Nothing here is
 * looked up reflectively: channels are built by constructor reference and the codec ships its own
 * native-image configuration.
 */
@ApplicationScoped
public class TunnelDns {

  private static final Logger LOG = Logger.getLogger(TunnelDns.class);

  @Inject TunnelDnsConfig config;

  @Inject EdgeConfig edge;

  @Inject EdgeRouter router;

  private volatile Server server;

  void start(@Observes StartupEvent ignored) {
    if (!config.enabled()) {
      return;
    }
    String zone = EdgeRouter.domain(edge.domain());
    Inet4Address publicAddress = ipv4(config.publicAddress().orElse(null));
    if (publicAddress == null) {
      LOG.errorf(
          "the tunnel DNS responder is NOT started: qits.edge.dns.public-address (QITS_PUBLIC_IP)"
              + " must be the public edge's IPv4 address, and it is `%s`. Every in-zone name this"
              + " edge does not route is answered with it, so there is nothing to answer without"
              + " it.",
          config.publicAddress().orElse(""));
      return;
    }
    Inet4Address bind = ipv4(config.bind());
    if (bind == null) {
      LOG.errorf(
          "the tunnel DNS responder is NOT started: qits.edge.dns.bind must be an IPv4 address,"
              + " and it is `%s`",
          config.bind());
      return;
    }
    Answers answers =
        new Answers(
            zone,
            publicAddress,
            config.ttl().toSeconds(),
            router::ready,
            name ->
                switch (router.reach(name)) {
                  case CATCHING_UP -> Reach.CATCHING_UP;
                  case SNAPSHOT -> Reach.HERE;
                  case ELSEWHERE -> Reach.ELSEWHERE;
                });
    try {
      server = Server.listen(bind, config.port(), answers);
    } catch (Exception failed) {
      LOG.errorf(
          failed,
          "the tunnel DNS responder could not bind %s:%d (UDP and TCP)",
          bind.getHostAddress(),
          config.port());
      return;
    }
    LOG.infof(
        "tunnel DNS responder on %s:%d (UDP and TCP): zone %s, public address %s, ttl %ds",
        bind.getHostAddress(),
        config.port(),
        zone,
        publicAddress.getHostAddress(),
        config.ttl().toSeconds());
  }

  void stop(@Observes ShutdownEvent ignored) {
    Server running = server;
    if (running != null) {
      server = null;
      running.close();
    }
  }

  /** An IPv4 literal, or null for anything else — never a name, which would mean a lookup. */
  static Inet4Address ipv4(String literal) {
    if (literal == null || !NetUtil.isValidIpV4Address(literal.strip())) {
      return null;
    }
    try {
      return (Inet4Address)
          InetAddress.getByAddress(NetUtil.createByteArrayFromIpAddressString(literal.strip()));
    } catch (UnknownHostException | ClassCastException impossible) {
      return null;
    }
  }

  /** What the router says about one in-zone name, in this responder's terms. */
  enum Reach {
    /** Nothing may be said yet: SERVFAIL. */
    CATCHING_UP,
    /** Routed to a snapshot this edge holds: the local address. */
    HERE,
    /** Anything else in the zone: the public address. */
    ELSEWHERE
  }

  /**
   * The whole answer logic, free of sockets so it is asserted with constructed queries.
   *
   * @param zone the stated domain, normalised — {@link EdgeRouter#domain}
   * @param ready whether the routes view is ready; false answers every query SERVFAIL
   * @param reach a normalised in-zone name to what the router says about it
   */
  record Answers(
      String zone,
      Inet4Address publicAddress,
      long ttlSeconds,
      BooleanSupplier ready,
      Function<String, Reach> reach) {

    /**
     * Fill {@code response}, already carrying the query's id and opcode, with the answer to {@code
     * query}.
     *
     * @param local the address the query arrived on, already resolved off a wildcard — see {@link
     *     #local}
     */
    <R extends DnsResponse> R respond(DnsQuery query, Inet4Address local, R response) {
      response.setRecursionDesired(query.isRecursionDesired());
      DnsRecord asked =
          query.count(DnsSection.QUESTION) == 1 ? query.recordAt(DnsSection.QUESTION) : null;
      if (!(asked instanceof DnsQuestion question)) {
        return code(response, DnsResponseCode.FORMERR);
      }
      response.addRecord(
          DnsSection.QUESTION,
          new DefaultDnsQuestion(question.name(), question.type(), question.dnsClass()));
      if (!query.opCode().equals(DnsOpCode.QUERY)) {
        return code(response, DnsResponseCode.NOTIMP);
      }
      if (!ready.getAsBoolean()) {
        return code(response, DnsResponseCode.SERVFAIL);
      }
      String name = normalise(question.name());
      if (!name.equals(zone) && !name.endsWith("." + zone)) {
        return code(response, DnsResponseCode.REFUSED);
      }
      Inet4Address answer;
      if (!question.type().equals(DnsRecordType.A)) {
        answer = null;
      } else {
        Reach said = reach.apply(name);
        if (said == Reach.CATCHING_UP) {
          return code(response, DnsResponseCode.SERVFAIL);
        }
        answer = said == Reach.HERE ? local : publicAddress;
      }
      response.setAuthoritativeAnswer(true).setCode(DnsResponseCode.NOERROR);
      if (answer != null) {
        response.addRecord(
            DnsSection.ANSWER,
            new DefaultDnsRawRecord(
                question.name(),
                DnsRecordType.A,
                ttlSeconds,
                Unpooled.wrappedBuffer(answer.getAddress())));
      }
      return response;
    }
  }

  private static <R extends DnsResponse> R code(R response, DnsResponseCode code) {
    response.setCode(code);
    return response;
  }

  /** Lower case, no trailing root dot: the spelling the zone and the router compare. */
  static String normalise(String name) {
    String normal = name.strip().toLowerCase(Locale.ROOT);
    while (normal.endsWith(".")) {
      normal = normal.substring(0, normal.length() - 1);
    }
    return normal;
  }

  /**
   * The address a query arrived on: the socket's own local address, or the bind address where that
   * is a wildcard (a socket bound to {@code 0.0.0.0} cannot say which of its addresses was asked).
   */
  static Inet4Address local(SocketAddress arrived, Inet4Address bind) {
    if (arrived instanceof InetSocketAddress socket
        && socket.getAddress() instanceof Inet4Address address
        && !address.isAnyLocalAddress()) {
      return address;
    }
    return bind;
  }

  private static void log(DnsResponse response, SocketAddress from) {
    if (LOG.isDebugEnabled()) {
      DnsRecord question = response.recordAt(DnsSection.QUESTION);
      DnsRecord answer = response.recordAt(DnsSection.ANSWER);
      LOG.debugf(
          "%s %s from %s: %s%s",
          question == null ? "?" : question.type().name(),
          question == null ? "?" : question.name(),
          from,
          response.code(),
          answer instanceof DefaultDnsRawRecord raw
              ? " " + NetUtil.bytesToIpAddress(ByteBufUtil.getBytes(raw.content()))
              : "");
    }
  }

  /** The two bound sockets and the group they run on. */
  record Server(EventLoopGroup group, Channel udp, Channel tcp) implements AutoCloseable {

    /**
     * Bind UDP and TCP on {@code bind}:{@code port}. Port 0 binds each to an ephemeral port of its
     * own — {@link #udpPort()} and {@link #tcpPort()} say which.
     */
    static Server listen(Inet4Address bind, int port, Answers answers) throws InterruptedException {
      EventLoopGroup group =
          new NioEventLoopGroup(1, new DefaultThreadFactory("edge-tunnel-dns", true));
      try {
        Channel udp =
            new Bootstrap()
                .group(group)
                .channelFactory(NioDatagramChannel::new)
                .handler(new UdpInitializer(new UdpHandler(answers, bind)))
                .bind(bind, port)
                .sync()
                .channel();
        Channel tcp;
        try {
          tcp =
              new ServerBootstrap()
                  .group(group)
                  .channelFactory(NioServerSocketChannel::new)
                  .childHandler(new TcpInitializer(new TcpHandler(answers, bind)))
                  .bind(bind, port)
                  .sync()
                  .channel();
        } catch (RuntimeException | InterruptedException failed) {
          udp.close();
          throw failed;
        }
        return new Server(group, udp, tcp);
      } catch (RuntimeException | InterruptedException failed) {
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        throw failed;
      }
    }

    int udpPort() {
      return ((InetSocketAddress) udp.localAddress()).getPort();
    }

    int tcpPort() {
      return ((InetSocketAddress) tcp.localAddress()).getPort();
    }

    @Override
    public void close() {
      udp.close().syncUninterruptibly();
      tcp.close().syncUninterruptibly();
      group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }
  }

  @RegisterForReflection(methods = true, fields = false)
  static final class UdpInitializer extends ChannelInitializer<DatagramChannel> {

    private final UdpHandler handler;

    UdpInitializer(UdpHandler handler) {
      this.handler = handler;
    }

    @Override
    protected void initChannel(DatagramChannel channel) {
      channel
          .pipeline()
          .addLast(new DatagramDnsQueryDecoder(), new DatagramDnsResponseEncoder(), handler);
    }
  }

  @RegisterForReflection(methods = true, fields = false)
  static final class TcpInitializer extends ChannelInitializer<SocketChannel> {

    private final TcpHandler handler;

    TcpInitializer(TcpHandler handler) {
      this.handler = handler;
    }

    @Override
    protected void initChannel(SocketChannel channel) {
      channel.pipeline().addLast(new TcpDnsQueryDecoder(), new TcpDnsResponseEncoder(), handler);
    }
  }

  @ChannelHandler.Sharable
  @RegisterForReflection(methods = true, fields = false)
  static final class UdpHandler extends SimpleChannelInboundHandler<DatagramDnsQuery> {

    private final Answers answers;
    private final Inet4Address bind;

    UdpHandler(Answers answers, Inet4Address bind) {
      this.answers = answers;
      this.bind = bind;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramDnsQuery query) {
      DatagramDnsResponse response =
          answers.respond(
              query,
              local(query.recipient(), bind),
              new DatagramDnsResponse(
                  query.recipient(), query.sender(), query.id(), query.opCode()));
      log(response, query.sender());
      ctx.writeAndFlush(response);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      // An unreadable datagram: dropped, and the socket stays open for the next one.
      LOG.debugf("dropped an unreadable DNS datagram: %s", cause.toString());
    }
  }

  @ChannelHandler.Sharable
  @RegisterForReflection(methods = true, fields = false)
  static final class TcpHandler extends SimpleChannelInboundHandler<DnsQuery> {

    private final Answers answers;
    private final Inet4Address bind;

    TcpHandler(Answers answers, Inet4Address bind) {
      this.answers = answers;
      this.bind = bind;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DnsQuery query) {
      DefaultDnsResponse response =
          answers.respond(
              query,
              local(ctx.channel().localAddress(), bind),
              new DefaultDnsResponse(query.id(), query.opCode()));
      log(response, ctx.channel().remoteAddress());
      ctx.writeAndFlush(response);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      LOG.debugf(
          "closed a DNS connection from %s after an unreadable query: %s",
          ctx.channel().remoteAddress(), cause.toString());
      ctx.close();
    }
  }
}
