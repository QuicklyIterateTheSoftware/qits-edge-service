package eu.wohlben.qits.edge.dns;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * {@link TunnelDns}: the answer logic against constructed queries, then one real round trip over
 * each transport on the loopback.
 */
class TunnelDnsTest {

  private static final Inet4Address LOCAL = TunnelDns.ipv4("100.96.0.1");

  private static final Inet4Address PUBLIC = TunnelDns.ipv4("203.0.113.7");

  private final AtomicBoolean ready = new AtomicBoolean(true);

  private final TunnelDns.Answers answers =
      new TunnelDns.Answers(
          "wohlben.eu",
          PUBLIC,
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
