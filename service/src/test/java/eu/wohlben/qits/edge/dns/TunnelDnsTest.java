package eu.wohlben.qits.edge.dns;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.edge.Cidr;
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
import java.time.Duration;
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

  // --- the overlay edge: no tunnel, the service VIP (qits-2304)
  // -----------------------------------

  private static final Cidr OVERLAY_BLOCK = Cidr.parse("10.231.0.0/20");

  @Test
  void aRoutedNameIsTheVipOnlyWhenBothSitInsideTheOverlay() {
    Inet4Address task = TunnelDns.ipv4("10.231.0.5");
    Inet4Address vip = TunnelDns.ipv4("10.231.0.2");
    assertEquals(vip, TunnelDns.hereAddress(task, vip, OVERLAY_BLOCK));
    assertEquals(task, TunnelDns.hereAddress(task, null, OVERLAY_BLOCK), "until one is known");
    assertEquals(
        task,
        TunnelDns.hereAddress(task, TunnelDns.ipv4("172.18.0.2"), OVERLAY_BLOCK),
        "a VIP outside the overlay is never answered");
    assertEquals(
        LOCAL,
        TunnelDns.hereAddress(LOCAL, vip, OVERLAY_BLOCK),
        "a query that arrived on the tunnel keeps its own address");
    assertEquals(task, TunnelDns.hereAddress(task, vip, null), "no overlay, no VIP");
  }

  @Test
  void theAnswersHandARoutedNameThroughHereAndNothingElse() {
    Inet4Address vip = TunnelDns.ipv4("10.231.0.2");
    TunnelDns.Answers viaVip =
        new TunnelDns.Answers(
            answers.zone(),
            answers.publicAddress(),
            answers.ttlSeconds(),
            answers.ready(),
            answers.reach(),
            local -> vip);
    DnsQuery routed = query("ci.dev.acme.wohlben.eu", DnsRecordType.A);
    assertArrayEquals(
        vip.getAddress(),
        address(
            viaVip.respond(routed, LOCAL, new DefaultDnsResponse(routed.id(), routed.opCode()))));
    DnsQuery unrouted = query("nope.wohlben.eu", DnsRecordType.A);
    assertArrayEquals(
        PUBLIC.getAddress(),
        address(
            viaVip.respond(
                unrouted, LOCAL, new DefaultDnsResponse(unrouted.id(), unrouted.opCode()))));
    assertArrayEquals(
        LOCAL.getAddress(),
        address(ask("ci.dev.acme.wohlben.eu", DnsRecordType.A)),
        "the five-argument answers — the private edge's — keep the local address");
  }

  @Test
  void theServiceAddressTakesOnlyAnIpv4InsideTheOverlayAndKeepsItAcrossAMiss() {
    AtomicReference<Inet4Address> resolved = new AtomicReference<>();
    List<String> asked = new ArrayList<>();
    TunnelDns.ServiceAddress vip =
        new TunnelDns.ServiceAddress(
            "dev-qits-edge-overlay",
            OVERLAY_BLOCK,
            name -> {
              asked.add(name);
              return resolved.get();
            });
    vip.refresh();
    assertNull(vip.get(), "nothing resolved yet");
    resolved.set(TunnelDns.ipv4("172.18.0.2"));
    vip.refresh();
    assertNull(vip.get(), "outside the overlay is never taken");
    resolved.set(TunnelDns.ipv4("10.231.0.2"));
    vip.refresh();
    assertEquals(TunnelDns.ipv4("10.231.0.2"), vip.get());
    resolved.set(null);
    vip.refresh();
    assertEquals(TunnelDns.ipv4("10.231.0.2"), vip.get(), "a failed lookup keeps the VIP");
    assertEquals(List.of("dev-qits-edge-overlay"), asked.stream().distinct().toList());
  }

  /** A stand-in for the mapping, as the overlay variant states it, on ephemeral ports. */
  private static TunnelDnsConfig overlayConfig(String block, String alias) {
    return new TunnelDnsConfig() {
      @Override
      public boolean enabled() {
        return true;
      }

      @Override
      public String bind() {
        return "100.96.0.1";
      }

      @Override
      public Optional<String> overlayCidr() {
        return Optional.ofNullable(block);
      }

      @Override
      public Optional<String> serviceAlias() {
        return Optional.ofNullable(alias);
      }

      @Override
      public int port() {
        return 0;
      }

      @Override
      public Optional<String> publicAddress() {
        return Optional.empty();
      }

      @Override
      public Optional<String> upstream() {
        return Optional.empty();
      }

      @Override
      public Duration upstreamTimeout() {
        return Duration.ofSeconds(2);
      }

      @Override
      public Duration ttl() {
        return Duration.ofSeconds(30);
      }
    };
  }

  @Test
  void withoutATunnelTheOverlayAloneIsServedAndARoutedNameBecomesTheVip() throws Exception {
    // The overlay edge's start: wireguard off, so no tunnel socket — the overlay addresses decide.
    // The announcer is left unset: telling it anything would throw, and nothing may.
    Inet4Address vip = TunnelDns.ipv4("127.0.0.3");
    TunnelDns dns = new TunnelDns();
    dns.config = overlayConfig("127.0.0.0/29", "dev-qits-edge-overlay");
    dns.interfaces = () -> List.of(TunnelDns.ipv4("127.0.0.1"), OVERLAY, NOT_HERE);
    dns.resolver =
        name ->
            switch (name) {
              case "dev-qits-edge-overlay" -> vip;
              case "wohlben.eu" -> PUBLIC;
              default -> null;
            };
    EventLoopGroup client = new NioEventLoopGroup(1);
    try {
      dns.start(
          "wohlben.eu",
          false,
          () -> true,
          name ->
              name.equals("ci.dev.acme.wohlben.eu")
                  ? TunnelDns.Reach.HERE
                  : TunnelDns.Reach.ELSEWHERE);

      assertFalse(dns.tunnelBound(), "no tunnel socket without a tunnel");
      List<TunnelDns.Server> served = dns.overlayServers();
      assertEquals(
          List.of(TunnelDns.ipv4("127.0.0.1"), OVERLAY),
          served.stream().map(TunnelDns.Server::address).toList(),
          "every address inside the block, none outside it");
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (dns.serviceAddress().get() == null && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertEquals(vip, dns.serviceAddress().get(), "looked up at start, off the event loop");

      TunnelDns.Server overlay = served.get(1);
      String routed = "ci.dev.acme.wohlben.eu";
      assertArrayEquals(
          vip.getAddress(), wireAddress(udp(client, OVERLAY, overlay.udpPort(), routed)));
      assertArrayEquals(
          vip.getAddress(), wireAddress(tcp(client, OVERLAY, overlay.tcpPort(), routed)));
      long publicDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      DnsResponse unrouted;
      do {
        unrouted = udp(client, OVERLAY, overlay.udpPort(), "nope.wohlben.eu");
      } while (unrouted.code() != DnsResponseCode.NOERROR && System.nanoTime() < publicDeadline);
      assertArrayEquals(PUBLIC.getAddress(), wireAddress(unrouted), "the public address");
      assertEquals(
          DnsResponseCode.REFUSED,
          udp(client, OVERLAY, overlay.udpPort(), "example.com").code(),
          "outside the zone stays REFUSED: no forwarder without a tunnel");
    } finally {
      dns.stop(null);
      client.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }
    assertTrue(dns.overlayServers().isEmpty(), "closed at shutdown");
  }

  @Test
  void withoutATunnelAndWithoutAnOverlayNothingRuns() {
    for (String block : new String[] {null, " ", "not-a-block", "192.0.2.0/24"}) {
      TunnelDns dns = new TunnelDns();
      dns.config = overlayConfig(block, null);
      dns.interfaces = () -> List.of(OVERLAY);
      dns.resolver = name -> PUBLIC;
      dns.start("wohlben.eu", false, () -> true, name -> TunnelDns.Reach.HERE);
      assertFalse(dns.tunnelBound(), String.valueOf(block));
      assertTrue(dns.overlayServers().isEmpty(), String.valueOf(block));
      assertNull(dns.serviceAddress(), String.valueOf(block));
      dns.stop(null);
    }
  }

  @Test
  void theInterfaceAddressesIncludeTheLoopback() {
    assertTrue(TunnelDns.interfaceAddresses().contains(TUNNEL));
  }

  // --- forwarding outside the zone, the tunnel only (qits-2262)
  // ---------------------------------------------------------------------------------------------

  private static final byte[] FORWARDED = {(byte) 198, 51, 100, 4};

  /**
   * A fake upstream resolver on the loopback, UDP and TCP on ports of their own: it answers every
   * query with a message it writes by hand — one A record {@link #FORWARDED}, or NXDOMAIN with an
   * SOA whose names are compression pointers into its own message — or not at all.
   */
  private static final class FakeUpstream implements AutoCloseable {

    enum Mode {
      ANSWER,
      NXDOMAIN,
      SILENT
    }

    volatile Mode mode = Mode.ANSWER;
    final List<byte[]> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
    final java.net.DatagramSocket udp;
    final java.net.ServerSocket tcp;

    FakeUpstream() throws Exception {
      udp = new java.net.DatagramSocket(new InetSocketAddress(TUNNEL, 0));
      tcp = new java.net.ServerSocket(0, 8, TUNNEL);
      Thread.ofVirtual().start(this::serveUdp);
      Thread.ofVirtual().start(this::serveTcp);
    }

    InetSocketAddress udpAddress() {
      return new InetSocketAddress(TUNNEL, udp.getLocalPort());
    }

    InetSocketAddress tcpAddress() {
      return new InetSocketAddress(TUNNEL, tcp.getLocalPort());
    }

    private void serveUdp() {
      byte[] buffer = new byte[4096];
      while (!udp.isClosed()) {
        try {
          java.net.DatagramPacket packet = new java.net.DatagramPacket(buffer, buffer.length);
          udp.receive(packet);
          byte[] query = java.util.Arrays.copyOf(packet.getData(), packet.getLength());
          asked.add(query);
          byte[] reply = reply(query);
          if (reply != null) {
            udp.send(new java.net.DatagramPacket(reply, reply.length, packet.getSocketAddress()));
          }
        } catch (java.io.IOException closed) {
          return;
        }
      }
    }

    private void serveTcp() {
      while (!tcp.isClosed()) {
        try (java.net.Socket connection = tcp.accept()) {
          java.io.DataInputStream in = new java.io.DataInputStream(connection.getInputStream());
          byte[] query = new byte[in.readUnsignedShort()];
          in.readFully(query);
          asked.add(query);
          byte[] reply = reply(query);
          if (reply == null) {
            Thread.sleep(5_000);
            continue;
          }
          java.io.DataOutputStream out = new java.io.DataOutputStream(connection.getOutputStream());
          out.writeShort(reply.length);
          out.write(reply);
          out.flush();
        } catch (java.io.IOException | InterruptedException closed) {
          if (tcp.isClosed()) {
            return;
          }
        }
      }
    }

    /** The answer to {@code query}, its id and question copied; null when silent. */
    private byte[] reply(byte[] query) {
      Mode now = mode;
      if (now == Mode.SILENT) {
        return null;
      }
      int end = 12;
      while (query[end] != 0) {
        end += (query[end] & 0xFF) + 1;
      }
      end += 1 + 4; // the root label, then QTYPE and QCLASS
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      java.nio.ByteBuffer header = java.nio.ByteBuffer.allocate(12);
      header.put(query, 0, 2); // the id
      header.putShort((short) (now == Mode.NXDOMAIN ? 0x8183 : 0x8180)); // QR RD RA, rcode
      header.putShort((short) 1); // QDCOUNT
      header.putShort((short) (now == Mode.ANSWER ? 1 : 0)); // ANCOUNT
      header.putShort((short) (now == Mode.NXDOMAIN ? 1 : 0)); // NSCOUNT
      header.putShort((short) 0); // ARCOUNT
      out.writeBytes(header.array());
      out.write(query, 12, end - 12);
      java.nio.ByteBuffer record = java.nio.ByteBuffer.allocate(64);
      record.putShort((short) 0xC00C); // the question's name, by pointer
      if (now == Mode.ANSWER) {
        record.putShort((short) 1).putShort((short) 1).putInt(60).putShort((short) 4);
        record.put(FORWARDED);
      } else {
        record.putShort((short) 6).putShort((short) 1).putInt(60).putShort((short) 24);
        record.putShort((short) 0xC00C).putShort((short) 0xC00C); // MNAME, RNAME by pointer
        record.putInt(1).putInt(2).putInt(3).putInt(4).putInt(5);
      }
      out.write(record.array(), 0, record.position());
      return out.toByteArray();
    }

    @Override
    public void close() throws Exception {
      udp.close();
      tcp.close();
    }
  }

  private static Forwarder forwarder(InetSocketAddress upstream, long timeoutMillis) {
    return new Forwarder(
        "wohlben.eu", List.of(upstream), java.time.Duration.ofMillis(timeoutMillis));
  }

  @Test
  void outsideTheZoneOnTheTunnelIsForwardedOverUdpAndTcp() throws Exception {
    EventLoopGroup client = new NioEventLoopGroup(1);
    try (FakeUpstream upstream = new FakeUpstream();
        TunnelDns.Server udpTunnel =
            TunnelDns.Server.listen(TUNNEL, 0, answers, forwarder(upstream.udpAddress(), 2_000));
        TunnelDns.Server tcpTunnel =
            TunnelDns.Server.listen(TUNNEL, 0, answers, forwarder(upstream.tcpAddress(), 2_000))) {
      DnsResponse viaUdp = udp(client, TUNNEL, udpTunnel.udpPort(), "Example.COM");
      assertEquals(9, viaUdp.id(), "the client's id, not the upstream exchange's");
      assertEquals(DnsResponseCode.NOERROR, viaUdp.code());
      assertFalse(viaUdp.isAuthoritativeAnswer(), "the upstream's flags, not this zone's");
      assertArrayEquals(FORWARDED, wireAddress(viaUdp));

      DnsResponse viaTcp = tcp(client, TUNNEL, tcpTunnel.tcpPort(), "example.com");
      assertEquals(10, viaTcp.id());
      assertEquals(DnsResponseCode.NOERROR, viaTcp.code());
      assertArrayEquals(FORWARDED, wireAddress(viaTcp));

      assertEquals(2, upstream.asked.size(), "one exchange per query, each its own transport");
      assertArrayEquals(
          TUNNEL.getAddress(),
          wireAddress(udp(client, TUNNEL, udpTunnel.udpPort(), "ci.dev.acme.wohlben.eu")),
          "the zone is still answered here, never forwarded");
      assertEquals(2, upstream.asked.size());
    } finally {
      client.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }
  }

  @Test
  void anUpstreamNxdomainIsRelayedWithItsAuthority() throws Exception {
    EventLoopGroup client = new NioEventLoopGroup(1);
    try (FakeUpstream upstream = new FakeUpstream();
        TunnelDns.Server udpTunnel =
            TunnelDns.Server.listen(TUNNEL, 0, answers, forwarder(upstream.udpAddress(), 2_000));
        TunnelDns.Server tcpTunnel =
            TunnelDns.Server.listen(TUNNEL, 0, answers, forwarder(upstream.tcpAddress(), 2_000))) {
      upstream.mode = FakeUpstream.Mode.NXDOMAIN;
      for (DnsResponse response :
          List.of(
              udp(client, TUNNEL, udpTunnel.udpPort(), "nothing.example"),
              tcp(client, TUNNEL, tcpTunnel.tcpPort(), "nothing.example"))) {
        assertEquals(DnsResponseCode.NXDOMAIN, response.code());
        assertEquals(0, response.count(DnsSection.ANSWER));
        assertEquals(
            1,
            response.count(DnsSection.AUTHORITY),
            "the SOA, its compressed names still pointing into the relayed message");
      }
    } finally {
      client.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }
  }

  @Test
  void forwardingDoesNotWaitForTheRoutesView() throws Exception {
    ready.set(false);
    EventLoopGroup client = new NioEventLoopGroup(1);
    try (FakeUpstream upstream = new FakeUpstream();
        TunnelDns.Server tunnel =
            TunnelDns.Server.listen(TUNNEL, 0, answers, forwarder(upstream.udpAddress(), 2_000))) {
      assertArrayEquals(
          FORWARDED, wireAddress(udp(client, TUNNEL, tunnel.udpPort(), "example.com")));
      assertEquals(
          DnsResponseCode.SERVFAIL,
          udp(client, TUNNEL, tunnel.udpPort(), "ci.dev.acme.wohlben.eu").code(),
          "the zone still waits");
    } finally {
      client.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }
  }

  @Test
  void anUpstreamThatIsDownOrSilentIsServfail() throws Exception {
    EventLoopGroup client = new NioEventLoopGroup(1);
    InetSocketAddress nobody;
    try (java.net.DatagramSocket taken = new java.net.DatagramSocket(0, TUNNEL)) {
      nobody = new InetSocketAddress(TUNNEL, taken.getLocalPort()); // closed again: nothing there
    }
    try (FakeUpstream upstream = new FakeUpstream();
        TunnelDns.Server down =
            TunnelDns.Server.listen(TUNNEL, 0, answers, forwarder(nobody, 5_000));
        TunnelDns.Server silentUdp =
            TunnelDns.Server.listen(TUNNEL, 0, answers, forwarder(upstream.udpAddress(), 300));
        TunnelDns.Server silentTcp =
            TunnelDns.Server.listen(TUNNEL, 0, answers, forwarder(upstream.tcpAddress(), 300))) {
      long before = System.nanoTime();
      DnsResponse refused = udp(client, TUNNEL, down.udpPort(), "example.com");
      assertEquals(DnsResponseCode.SERVFAIL, refused.code());
      assertEquals(9, refused.id());
      assertEquals(
          DnsResponseCode.SERVFAIL, tcp(client, TUNNEL, down.tcpPort(), "example.com").code());
      assertTrue(
          System.nanoTime() - before < TimeUnit.SECONDS.toNanos(4),
          "an upstream that refuses outright is not waited for");

      upstream.mode = FakeUpstream.Mode.SILENT;
      assertEquals(
          DnsResponseCode.SERVFAIL, udp(client, TUNNEL, silentUdp.udpPort(), "example.com").code());
      assertEquals(
          DnsResponseCode.SERVFAIL, tcp(client, TUNNEL, silentTcp.tcpPort(), "example.com").code());
    } finally {
      client.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }
  }

  @Test
  void theOverlayStillRefusesOutsideTheZoneWhileTheTunnelForwards() throws Exception {
    EventLoopGroup client = new NioEventLoopGroup(1);
    try (FakeUpstream upstream = new FakeUpstream();
        TunnelDns.Server tunnel =
            TunnelDns.Server.listen(TUNNEL, 0, answers, forwarder(upstream.udpAddress(), 2_000))) {
      List<TunnelDns.Server> overlay =
          TunnelDns.listenOverlay(
              Optional.of("127.0.0.2/32"), TUNNEL, 0, answers, () -> List.of(TUNNEL, OVERLAY));
      try {
        TunnelDns.Server served = overlay.get(0);
        assertEquals(
            DnsResponseCode.REFUSED, udp(client, OVERLAY, served.udpPort(), "example.com").code());
        assertEquals(
            DnsResponseCode.REFUSED, tcp(client, OVERLAY, served.tcpPort(), "example.com").code());
        assertArrayEquals(
            FORWARDED, wireAddress(udp(client, TUNNEL, tunnel.udpPort(), "example.com")));
        assertEquals(1, upstream.asked.size(), "only the tunnel's query went upstream");
      } finally {
        overlay.forEach(TunnelDns.Server::close);
      }
    } finally {
      client.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }
  }

  @Test
  void theUpstreamsAreResolvConfsNameserversUnlessStated() {
    String resolvConf =
        """
        # generated
        search example.internal
        nameserver 127.0.0.11
        nameserver  2001:db8::53
        nameserver fe80::1%eth0
        options ndots:0
        """;
    assertEquals(
        List.of(new InetSocketAddress("127.0.0.11", 53), new InetSocketAddress("2001:db8::53", 53)),
        Forwarder.upstreams(Optional.empty(), resolvConf, List.of()),
        "the nameserver lines on port 53; a scoped address is no literal and is skipped");
    assertEquals(
        List.of(
            new InetSocketAddress("192.0.2.53", 5353),
            new InetSocketAddress("192.0.2.54", 53),
            new InetSocketAddress("2001:db8::1", 53),
            new InetSocketAddress("2001:db8::2", 5300)),
        Forwarder.upstreams(
            Optional.of(" 192.0.2.53:5353, 192.0.2.54,2001:db8::1 ,[2001:db8::2]:5300, dns.google"),
            resolvConf,
            List.of()),
        "a stated list wins, and a name is no literal");
    assertTrue(Forwarder.upstreams(Optional.of(" "), null, List.of()).isEmpty());
    assertEquals(
        List.of(new InetSocketAddress("127.0.0.11", 53)),
        Forwarder.upstreams(Optional.of(" "), "nameserver 127.0.0.11", List.of()),
        "a blank list is unset");
  }

  @Test
  void anUpstreamThatIsThisRespondersOwnSocketIsDropped() {
    List<InetSocketAddress> own =
        List.of(new InetSocketAddress("100.96.0.1", 53), new InetSocketAddress("10.231.0.5", 53));
    assertEquals(
        List.of(new InetSocketAddress("100.96.0.1", 5353), new InetSocketAddress("127.0.0.11", 53)),
        Forwarder.upstreams(
            Optional.of("100.96.0.1, 10.231.0.5:53, 0.0.0.0, 100.96.0.1:5353, 127.0.0.11"),
            null,
            own));
  }

  @Test
  void onlyAStandardQueryOutsideTheZoneIsForwarded() {
    Forwarder forwarder = forwarder(new InetSocketAddress("192.0.2.53", 53), 1_000);
    assertTrue(forwarder.forwards(query("example.com", DnsRecordType.A)));
    assertTrue(forwarder.forwards(query("notwohlben.eu.", DnsRecordType.AAAA)));
    assertFalse(forwarder.forwards(query("WOHLBEN.eu.", DnsRecordType.A)));
    assertFalse(forwarder.forwards(query("ci.wohlben.eu", DnsRecordType.A)));
    assertFalse(forwarder.forwards(new DefaultDnsQuery(1, DnsOpCode.QUERY)), "FORMERR is ours");
    DnsQuery status = new DefaultDnsQuery(1, DnsOpCode.STATUS);
    status.setRecord(DnsSection.QUESTION, new DefaultDnsQuestion("example.com", DnsRecordType.A));
    assertFalse(forwarder.forwards(status), "NOTIMP is ours");
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
      copy.setAuthoritativeAnswer(wire.isAuthoritativeAnswer());
      for (DnsSection section : new DnsSection[] {DnsSection.ANSWER, DnsSection.AUTHORITY}) {
        for (int i = 0; i < wire.count(section); i++) {
          if (wire.recordAt(section, i) instanceof DnsRawRecord raw) {
            copy.addRecord(
                section,
                new DefaultDnsRawRecord(
                    raw.name(),
                    raw.type(),
                    raw.timeToLive(),
                    Unpooled.wrappedBuffer(ByteBufUtil.getBytes(raw.content()))));
          }
        }
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
