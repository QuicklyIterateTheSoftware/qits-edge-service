package eu.wohlben.qits.edge.dns;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsQueryEncoder;
import io.netty.handler.codec.dns.DatagramDnsResponseDecoder;
import io.netty.handler.codec.dns.DefaultDnsQuery;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.handler.codec.dns.DnsOpCode;
import io.netty.handler.codec.dns.DnsQuery;
import io.netty.handler.codec.dns.DnsRawRecord;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.handler.codec.dns.TcpDnsQueryEncoder;
import io.netty.handler.codec.dns.TcpDnsResponseDecoder;
import io.netty.util.ReferenceCountUtil;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * {@link TunnelDns}: the answer logic against constructed queries, then one real round trip over
 * each transport on the loopback.
 */
class TunnelDnsTest {

  private static final Inet4Address LOCAL = TunnelDns.ipv4("100.96.0.1");

  private static final Inet4Address PUBLIC = TunnelDns.ipv4("203.0.113.7");

  private final AtomicBoolean ready = new AtomicBoolean(true);

  private final AtomicReference<Inet4Address> publicAddress = new AtomicReference<>(PUBLIC);

  private final TunnelDns.Answers answers =
      new TunnelDns.Answers(
          "wohlben.eu",
          publicAddress::get,
          30,
          ready::get,
          name ->
              switch (name) {
                case "ci.dev.acme.wohlben.eu" -> TunnelDns.Reach.HERE;
                case "late.wohlben.eu" -> TunnelDns.Reach.CATCHING_UP;
                default -> TunnelDns.Reach.ELSEWHERE;
              });

  private static DnsQuery query(String name, DnsRecordType type) {
    DnsQuery query = new DefaultDnsQuery(4711, DnsOpCode.QUERY);
    query.setRecursionDesired(true);
    query.setRecord(DnsSection.QUESTION, new DefaultDnsQuestion(name, type));
    return query;
  }

  private DnsResponse ask(String name, DnsRecordType type) {
    DnsQuery query = query(name, type);
    return answers.respond(query, LOCAL, new DefaultDnsResponse(query.id(), query.opCode()));
  }

  private static byte[] address(DnsResponse response) {
    assertEquals(1, response.count(DnsSection.ANSWER));
    DnsRawRecord record = response.recordAt(DnsSection.ANSWER);
    assertEquals(DnsRecordType.A, record.type());
    assertEquals(30, record.timeToLive());
    return ByteBufUtil.getBytes(record.content());
  }

  @Test
  void aRoutedNameIsTheLocalAddress() {
    DnsResponse response = ask("ci.dev.acme.wohlben.eu.", DnsRecordType.A);
    assertEquals(DnsResponseCode.NOERROR, response.code());
    assertTrue(response.isAuthoritativeAnswer());
    assertTrue(response.isRecursionDesired(), "RD is copied");
    assertEquals(4711, response.id());
    DnsRecord question = response.recordAt(DnsSection.QUESTION);
    assertEquals("ci.dev.acme.wohlben.eu.", question.name(), "the question is echoed");
    assertArrayEquals(LOCAL.getAddress(), address(response));
  }

  @Test
  void caseAndTheRootDotDoNotMatter() {
    assertArrayEquals(LOCAL.getAddress(), address(ask("CI.Dev.ACME.Wohlben.EU", DnsRecordType.A)));
    assertArrayEquals(LOCAL.getAddress(), address(ask("Ci.Dev.Acme.Wohlben.Eu.", DnsRecordType.A)));
  }

  @Test
  void anUnknownInZoneNameIsThePublicAddress() {
    DnsResponse response = ask("nope.dev.acme.wohlben.eu", DnsRecordType.A);
    assertEquals(DnsResponseCode.NOERROR, response.code());
    assertTrue(response.isAuthoritativeAnswer());
    assertArrayEquals(PUBLIC.getAddress(), address(response));
  }

  @Test
  void theApexIsThePublicAddress() {
    assertArrayEquals(PUBLIC.getAddress(), address(ask("WOHLBEN.eu.", DnsRecordType.A)));
  }

  @Test
  void anyOtherTypeIsAnEmptyNoError() {
    for (DnsRecordType type :
        new DnsRecordType[] {DnsRecordType.AAAA, DnsRecordType.MX, DnsRecordType.TXT}) {
      DnsResponse response = ask("ci.dev.acme.wohlben.eu", type);
      assertEquals(DnsResponseCode.NOERROR, response.code(), type.name());
      assertTrue(response.isAuthoritativeAnswer());
      assertEquals(0, response.count(DnsSection.ANSWER), type.name());
    }
  }

  @Test
  void outsideTheZoneIsRefused() {
    for (String name : new String[] {"example.com", "notwohlben.eu", "eu"}) {
      DnsResponse response = ask(name, DnsRecordType.A);
      assertEquals(DnsResponseCode.REFUSED, response.code(), name);
      assertFalse(response.isAuthoritativeAnswer());
      assertEquals(0, response.count(DnsSection.ANSWER));
    }
  }

  @Test
  void beforeReadinessEveryQueryIsServfail() {
    ready.set(false);
    for (String name : new String[] {"ci.dev.acme.wohlben.eu", "wohlben.eu", "example.com"}) {
      DnsResponse response = ask(name, DnsRecordType.A);
      assertEquals(DnsResponseCode.SERVFAIL, response.code(), name);
      assertEquals(0, response.count(DnsSection.ANSWER));
    }
  }

  @Test
  void aNameTheRouterCannotSayYetIsServfail() {
    assertEquals(DnsResponseCode.SERVFAIL, ask("late.wohlben.eu", DnsRecordType.A).code());
  }

  @Test
  void withNoPublicAddressKnownAnUnroutedNameIsServfail() {
    publicAddress.set(null);
    for (String name : new String[] {"nope.wohlben.eu", "wohlben.eu"}) {
      DnsResponse response = ask(name, DnsRecordType.A);
      assertEquals(DnsResponseCode.SERVFAIL, response.code(), name);
      assertEquals(0, response.count(DnsSection.ANSWER));
    }
    assertArrayEquals(
        LOCAL.getAddress(),
        address(ask("ci.dev.acme.wohlben.eu", DnsRecordType.A)),
        "a routed name needs no public address");
    assertEquals(
        DnsResponseCode.NOERROR, ask("nope.wohlben.eu", DnsRecordType.AAAA).code(), "nor AAAA");
  }

  @Test
  void thePublicAddressIsTheApexLookedUpAndKeptAcrossAFailedLookup() {
    List<String> asked = new ArrayList<>();
    AtomicReference<Inet4Address> resolves = new AtomicReference<>();
    TunnelDns.PublicAddress apex =
        new TunnelDns.PublicAddress(
            "wohlben.eu",
            null,
            name -> {
              asked.add(name);
              return resolves.get();
            });
    assertNull(apex.get(), "nothing known before the first lookup");
    apex.refresh();
    assertNull(apex.get(), "nothing resolved, nothing known");
    resolves.set(PUBLIC);
    apex.refresh();
    assertEquals(PUBLIC, apex.get());
    resolves.set(null);
    apex.refresh();
    assertEquals(PUBLIC, apex.get(), "a failed lookup keeps the last address");
    Inet4Address moved = TunnelDns.ipv4("203.0.113.8");
    resolves.set(moved);
    apex.refresh();
    assertEquals(moved, apex.get());
    assertEquals(List.of("wohlben.eu", "wohlben.eu", "wohlben.eu", "wohlben.eu"), asked);
  }

  @Test
  void aStatedPublicAddressIsNeverLookedUp() {
    TunnelDns.PublicAddress stated =
        new TunnelDns.PublicAddress(
            "wohlben.eu",
            PUBLIC,
            name -> {
              throw new AssertionError("looked up " + name);
            });
    stated.refresh();
    assertEquals(PUBLIC, stated.get());
  }

  @Test
  void aThrowingResolverIsAFailedLookup() {
    TunnelDns.PublicAddress apex =
        new TunnelDns.PublicAddress(
            "wohlben.eu",
            null,
            name -> {
              throw new IllegalStateException("resolver down");
            });
    apex.refresh();
    assertNull(apex.get());
  }

  @Test
  void otherThanOneQuestionIsFormerr() {
    DnsQuery none = new DefaultDnsQuery(1, DnsOpCode.QUERY);
    assertEquals(
        DnsResponseCode.FORMERR,
        answers.respond(none, LOCAL, new DefaultDnsResponse(1, DnsOpCode.QUERY)).code());
    DnsQuery two = query("wohlben.eu", DnsRecordType.A);
    two.addRecord(DnsSection.QUESTION, new DefaultDnsQuestion("ci.wohlben.eu", DnsRecordType.A));
    assertEquals(
        DnsResponseCode.FORMERR,
        answers.respond(two, LOCAL, new DefaultDnsResponse(1, DnsOpCode.QUERY)).code());
  }

  @Test
  void theLocalAddressFallsBackToTheBindOnAWildcard() {
    Inet4Address bind = TunnelDns.ipv4("100.96.0.1");
    assertEquals(bind, TunnelDns.local(new InetSocketAddress("0.0.0.0", 53), bind));
    assertEquals(bind, TunnelDns.local(null, bind));
    assertEquals(
        TunnelDns.ipv4("10.0.0.5"), TunnelDns.local(new InetSocketAddress("10.0.0.5", 53), bind));
  }

  @Test
  void thePublicAddressIsAnIpv4LiteralOnly() {
    assertNull(TunnelDns.ipv4(""));
    assertNull(TunnelDns.ipv4(null));
    assertNull(TunnelDns.ipv4("edge.example.com"));
    assertNull(TunnelDns.ipv4("::1"));
    assertEquals(PUBLIC, TunnelDns.ipv4(" 203.0.113.7 "));
  }

  // --- over the wire
  // ------------------------------------------------------------------------------

  @Test
  void aRealUdpAndTcpRoundTrip() throws Exception {
    Inet4Address loopback = TunnelDns.ipv4("127.0.0.1");
    EventLoopGroup client = new NioEventLoopGroup(1);
    try (TunnelDns.Server server = TunnelDns.Server.listen(loopback, 0, answers)) {
      // UDP
      CompletableFuture<DnsResponse> udpAnswer = new CompletableFuture<>();
      Channel udp =
          new Bootstrap()
              .group(client)
              .channelFactory(NioDatagramChannel::new)
              .handler(
                  new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel channel) {
                      channel
                          .pipeline()
                          .addLast(
                              new DatagramDnsQueryEncoder(),
                              new DatagramDnsResponseDecoder(),
                              capture(udpAnswer));
                    }
                  })
              .bind(loopback, 0)
              .sync()
              .channel();
      InetSocketAddress udpServer = new InetSocketAddress(loopback, server.udpPort());
      DatagramDnsQuery udpQuery = new DatagramDnsQuery(null, udpServer, 7, DnsOpCode.QUERY);
      udpQuery.setRecord(
          DnsSection.QUESTION, new DefaultDnsQuestion("ci.dev.acme.wohlben.eu", DnsRecordType.A));
      udp.writeAndFlush(udpQuery).sync();
      DnsResponse viaUdp = udpAnswer.get(10, TimeUnit.SECONDS);
      try {
        assertEquals(7, viaUdp.id());
        assertEquals(DnsResponseCode.NOERROR, viaUdp.code());
        assertTrue(viaUdp.isAuthoritativeAnswer());
        assertArrayEquals(
            loopback.getAddress(), wireAddress(viaUdp), "the address the query arrived on");
      } finally {
        ReferenceCountUtil.release(viaUdp);
        udp.close().sync();
      }

      // TCP
      CompletableFuture<DnsResponse> tcpAnswer = new CompletableFuture<>();
      Channel tcp =
          new Bootstrap()
              .group(client)
              .channelFactory(NioSocketChannel::new)
              .handler(
                  new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                      channel
                          .pipeline()
                          .addLast(
                              new TcpDnsQueryEncoder(),
                              new TcpDnsResponseDecoder(),
                              capture(tcpAnswer));
                    }
                  })
              .connect(loopback, server.tcpPort())
              .sync()
              .channel();
      DnsQuery tcpQuery = new DefaultDnsQuery(8, DnsOpCode.QUERY);
      tcpQuery.setRecord(
          DnsSection.QUESTION, new DefaultDnsQuestion("nope.wohlben.eu", DnsRecordType.A));
      tcp.writeAndFlush(tcpQuery).sync();
      DnsResponse viaTcp = tcpAnswer.get(10, TimeUnit.SECONDS);
      try {
        assertEquals(8, viaTcp.id());
        assertEquals(DnsResponseCode.NOERROR, viaTcp.code());
        assertArrayEquals(PUBLIC.getAddress(), wireAddress(viaTcp));
      } finally {
        ReferenceCountUtil.release(viaTcp);
        tcp.close().sync();
      }
    } finally {
      client.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }
  }

  // --- the overlay, one socket per address (qits-1991)
  // ---------------------------------------------------------------------------------------------
  //
  // The second loopback address stands in for the edge's task address on qits-private: Linux
  // routes all of 127/8 to lo, so 127.0.0.2 is a concrete local address a socket can be bound to
  // and
  // asked on, and a socket bound to it must answer it — not the tunnel's — for a routed name.

  private static final Inet4Address TUNNEL = TunnelDns.ipv4("127.0.0.1");

  private static final Inet4Address OVERLAY = TunnelDns.ipv4("127.0.0.2");

  /** TEST-NET-1: never an address of this host, so binding it fails. */
  private static final Inet4Address NOT_HERE = TunnelDns.ipv4("192.0.2.1");

  @Test
  void anOverlayAddressAnswersARoutedNameWithItsOwnAddressOverUdpAndTcp() throws Exception {
    EventLoopGroup client = new NioEventLoopGroup(1);
    try (TunnelDns.Server tunnel = TunnelDns.Server.listen(TUNNEL, 0, answers)) {
      List<TunnelDns.Server> overlay =
          TunnelDns.listenOverlay(
              Optional.of("127.0.0.2/32"),
              TUNNEL,
              0,
              answers,
              () -> List.of(TUNNEL, OVERLAY, TunnelDns.ipv4("10.231.0.5")));
      try {
        assertEquals(1, overlay.size(), "only the address inside the block is bound");
        TunnelDns.Server served = overlay.get(0);
        assertEquals(OVERLAY, served.address());

        String routed = "ci.dev.acme.wohlben.eu";
        assertArrayEquals(
            OVERLAY.getAddress(), wireAddress(udp(client, OVERLAY, served.udpPort(), routed)));
        assertArrayEquals(
            OVERLAY.getAddress(), wireAddress(tcp(client, OVERLAY, served.tcpPort(), routed)));
        assertArrayEquals(
            TUNNEL.getAddress(),
            wireAddress(udp(client, TUNNEL, tunnel.udpPort(), routed)),
            "the tunnel's socket still answers its own address");
        assertArrayEquals(
            TUNNEL.getAddress(), wireAddress(tcp(client, TUNNEL, tunnel.tcpPort(), routed)));
        assertArrayEquals(
            PUBLIC.getAddress(),
            wireAddress(udp(client, OVERLAY, served.udpPort(), "nope.wohlben.eu")),
            "an unrouted in-zone name is the public address on the overlay too");
        DnsResponse outside = udp(client, OVERLAY, served.udpPort(), "example.com");
        assertEquals(
            DnsResponseCode.REFUSED,
            outside.code(),
            "outside the zone, so docker's resolver asks the next --dns");
      } finally {
        overlay.forEach(TunnelDns.Server::close);
      }
    } finally {
      client.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }
  }

  @Test
  void anUnsetOrBlankOverlayBindsNothingAndReadsNoAddress() {
    AtomicInteger read = new AtomicInteger();
    for (Optional<String> stated : List.of(Optional.<String>empty(), Optional.of(" "))) {
      assertTrue(
          TunnelDns.listenOverlay(
                  stated,
                  TUNNEL,
                  0,
                  answers,
                  () -> {
                    read.incrementAndGet();
                    return List.of(OVERLAY);
                  })
              .isEmpty());
    }
    assertEquals(0, read.get());
  }

  @Test
  void noAddressInsideTheOverlayBindsNothingBeyondTheTunnel() {
    assertTrue(
        TunnelDns.listenOverlay(
                Optional.of("10.231.0.0/20"),
                TUNNEL,
                0,
                answers,
                () -> List.of(TUNNEL, TunnelDns.ipv4("172.18.0.4")))
            .isEmpty());
    assertTrue(
        TunnelDns.listenOverlay(
                Optional.of("127.0.0.0/8"), TUNNEL, 0, answers, () -> List.of(TUNNEL))
            .isEmpty(),
        "the tunnel's own address is bound already, never twice");
    assertTrue(
        TunnelDns.listenOverlay(
                Optional.of("not-a-block"), TUNNEL, 0, answers, () -> List.of(OVERLAY))
            .isEmpty(),
        "an unreadable block serves the tunnel alone");
    assertTrue(
        TunnelDns.listenOverlay(
                Optional.of("127.0.0.2/32"),
                TUNNEL,
                0,
                answers,
                () -> {
                  throw new IllegalStateException("no interfaces");
                })
            .isEmpty(),
        "unreadable interfaces serve the tunnel alone");
  }

  @Test
  void anOverlayAddressThatCannotBeBoundLeavesTheTunnelAndTheOthersServing() throws Exception {
    EventLoopGroup client = new NioEventLoopGroup(1);
    try (TunnelDns.Server tunnel = TunnelDns.Server.listen(TUNNEL, 0, answers)) {
      List<TunnelDns.Server> overlay =
          TunnelDns.listenOverlay(
              Optional.of("0.0.0.0/0"), TUNNEL, 0, answers, () -> List.of(NOT_HERE, OVERLAY));
      try {
        assertEquals(1, overlay.size(), "the unbindable address is skipped, the next is bound");
        assertEquals(OVERLAY, overlay.get(0).address());
        String routed = "ci.dev.acme.wohlben.eu";
        assertArrayEquals(
            TUNNEL.getAddress(), wireAddress(udp(client, TUNNEL, tunnel.udpPort(), routed)));
        assertArrayEquals(
            TUNNEL.getAddress(), wireAddress(tcp(client, TUNNEL, tunnel.tcpPort(), routed)));
        assertArrayEquals(
            OVERLAY.getAddress(),
            wireAddress(udp(client, OVERLAY, overlay.get(0).udpPort(), routed)));
      } finally {
        overlay.forEach(TunnelDns.Server::close);
      }
    } finally {
      client.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }
  }

  @Test
  void theInterfaceAddressesIncludeTheLoopback() {
    assertTrue(TunnelDns.interfaceAddresses().contains(TUNNEL));
  }

  /** One A query over UDP to {@code server}:{@code port}; the response is read, then released. */
  private static DnsResponse udp(EventLoopGroup group, Inet4Address server, int port, String name)
      throws Exception {
    CompletableFuture<DnsResponse> answer = new CompletableFuture<>();
    Channel channel =
        new Bootstrap()
            .group(group)
            .channelFactory(NioDatagramChannel::new)
            .handler(
                new ChannelInitializer<DatagramChannel>() {
                  @Override
                  protected void initChannel(DatagramChannel channel) {
                    channel
                        .pipeline()
                        .addLast(
                            new DatagramDnsQueryEncoder(),
                            new DatagramDnsResponseDecoder(),
                            capture(answer));
                  }
                })
            .bind(TUNNEL, 0)
            .sync()
            .channel();
    try {
      DatagramDnsQuery query =
          new DatagramDnsQuery(null, new InetSocketAddress(server, port), 9, DnsOpCode.QUERY);
      query.setRecord(DnsSection.QUESTION, new DefaultDnsQuestion(name, DnsRecordType.A));
      channel.writeAndFlush(query).sync();
      return detach(answer.get(10, TimeUnit.SECONDS));
    } finally {
      channel.close().sync();
    }
  }

  /** One A query over TCP to {@code server}:{@code port}; the response is read, then released. */
  private static DnsResponse tcp(EventLoopGroup group, Inet4Address server, int port, String name)
      throws Exception {
    CompletableFuture<DnsResponse> answer = new CompletableFuture<>();
    Channel channel =
        new Bootstrap()
            .group(group)
            .channelFactory(NioSocketChannel::new)
            .handler(
                new ChannelInitializer<SocketChannel>() {
                  @Override
                  protected void initChannel(SocketChannel channel) {
                    channel
                        .pipeline()
                        .addLast(
                            new TcpDnsQueryEncoder(), new TcpDnsResponseDecoder(), capture(answer));
                  }
                })
            .connect(server, port)
            .sync()
            .channel();
    try {
      DnsQuery query = new DefaultDnsQuery(10, DnsOpCode.QUERY);
      query.setRecord(DnsSection.QUESTION, new DefaultDnsQuestion(name, DnsRecordType.A));
      channel.writeAndFlush(query).sync();
      return detach(answer.get(10, TimeUnit.SECONDS));
    } finally {
      channel.close().sync();
    }
  }

  /** A heap copy of the code and the answer, so the pooled original can be released at once. */
  private static DnsResponse detach(DnsResponse wire) {
    try {
      DefaultDnsResponse copy = new DefaultDnsResponse(wire.id(), wire.opCode(), wire.code());
      DnsRecord answer = wire.recordAt(DnsSection.ANSWER);
      if (answer instanceof DnsRawRecord raw) {
        copy.addRecord(
            DnsSection.ANSWER,
            new DefaultDnsRawRecord(
                raw.name(),
                raw.type(),
                raw.timeToLive(),
                Unpooled.wrappedBuffer(ByteBufUtil.getBytes(raw.content()))));
      }
      return copy;
    } finally {
      ReferenceCountUtil.release(wire);
    }
  }

  private static byte[] wireAddress(DnsResponse response) {
    assertEquals(1, response.count(DnsSection.ANSWER));
    DnsRawRecord record = response.recordAt(DnsSection.ANSWER);
    return ByteBufUtil.getBytes(record.content());
  }

  private static SimpleChannelInboundHandler<DnsResponse> capture(
      CompletableFuture<DnsResponse> into) {
    return new SimpleChannelInboundHandler<>(false) {
      @Override
      protected void channelRead0(ChannelHandlerContext ctx, DnsResponse response) {
        into.complete(response);
      }

      @Override
      public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        into.completeExceptionally(cause);
      }
    };
  }
}
