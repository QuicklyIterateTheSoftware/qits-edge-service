package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.nio.NioChannelOption;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import jdk.net.ExtendedSocketOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The keepalive timers, read back from a live socket the edge's own proxy client opened.
 *
 * <p>An options object cannot carry this proof — Vert.x 4.5 has {@code tcpKeepAlive*} options that
 * no transport applies to a client socket, which is how a value can be "configured" and absent at
 * once. So this test builds the client exactly as {@code EdgeRouter.init} does, {@link
 * EdgeRouter#proxyClient} with {@link EdgeRouter#proxyClientOptions} and {@link
 * UpstreamPools#connected} as the connect handler, opens a real connection to a local server, and
 * asks the kernel through the channel what it was told.
 *
 * <p>Framework-free: a Vert.x of its own and a server on an ephemeral loopback port, no Quarkus.
 */
class UpstreamKeepAliveTest {

  private Vertx vertx;

  private HttpServer server;

  /** Requests the server has not answered yet — the pool-width test holds them open. */
  private final List<HttpServerRequest> held = new CopyOnWriteArrayList<>();

  @BeforeEach
  void listen() throws Exception {
    vertx = Vertx.vertx();
    server =
        vertx
            .createHttpServer()
            .requestHandler(
                request -> {
                  if (request.method() == HttpMethod.CONNECT) {
                    // A tunnel: the connection stops being HTTP, the shape of every 101 the edge
                    // splices. Echo, so the client side has a live socket to close.
                    request.toNetSocket().onSuccess(socket -> socket.handler(socket::write));
                    return;
                  }
                  if (request.path().equals("/hold")) {
                    held.add(request);
                    return;
                  }
                  request.response().end("ok");
                })
            .listen(0, "localhost")
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
  }

  @AfterEach
  void close() throws Exception {
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  @Test
  void everyUpstreamSocketProbesAfterSixtySecondsEveryTenAndGivesUpAfterThree() throws Exception {
    UpstreamPools pools = new UpstreamPools(64, Upstream::toString);
    CompletableFuture<Map<String, Object>> readBack = new CompletableFuture<>();
    HttpClient client =
        EdgeRouter.proxyClient(
            vertx,
            EdgeRouter.proxyClientOptions(5000),
            connection -> {
              // The edge's own handler first, then the reading — so what is read is what the edge
              // left on the socket, not what the platform defaults to.
              pools.connected(connection);
              Channel channel = UpstreamChannel.of(connection);
              Map<String, Object> values = new ConcurrentHashMap<>();
              values.put("SO_KEEPALIVE", channel.config().getOption(ChannelOption.SO_KEEPALIVE));
              values.put(
                  "TCP_KEEPIDLE",
                  channel
                      .config()
                      .getOption(NioChannelOption.of(ExtendedSocketOptions.TCP_KEEPIDLE)));
              values.put(
                  "TCP_KEEPINTERVAL",
                  channel
                      .config()
                      .getOption(NioChannelOption.of(ExtendedSocketOptions.TCP_KEEPINTERVAL)));
              values.put(
                  "TCP_KEEPCOUNT",
                  channel
                      .config()
                      .getOption(NioChannelOption.of(ExtendedSocketOptions.TCP_KEEPCOUNT)));
              readBack.complete(values);
            });

    assertEquals(200, get(client, "/").statusCode());
    Map<String, Object> values = readBack.get(10, TimeUnit.SECONDS);

    assertEquals(true, values.get("SO_KEEPALIVE"), "the kernel must probe a silent socket at all");
    assertEquals(UpstreamKeepAlive.IDLE_SECONDS, values.get("TCP_KEEPIDLE"));
    assertEquals(UpstreamKeepAlive.INTERVAL_SECONDS, values.get("TCP_KEEPINTERVAL"));
    assertEquals(UpstreamKeepAlive.COUNT, values.get("TCP_KEEPCOUNT"));
    // Spelled out as well as named: these three numbers ARE the acceptance criterion, and a
    // constant changed in one place would otherwise pass here unseen.
    assertEquals(
        List.of(60, 10, 3),
        List.of(
            values.get("TCP_KEEPIDLE"),
            values.get("TCP_KEEPINTERVAL"),
            values.get("TCP_KEEPCOUNT")));
  }

  @Test
  void aConnectionIsCountedAgainstTheOriginItWasDialledAsAndUncountedWhenItCloses()
      throws Exception {
    UpstreamPools pools = new UpstreamPools(64, Upstream::toString);
    CompletableFuture<HttpConnection> connected = new CompletableFuture<>();
    HttpClient client =
        EdgeRouter.proxyClient(
            vertx,
            EdgeRouter.proxyClientOptions(5000),
            connection -> {
              pools.connected(connection);
              connected.complete(connection);
            });

    // Dialled by NAME, which is how every real upstream is dialled — `dev-qits-projects:8080`. The
    // key must be that name and not the address it resolved to, or a swarm task moving would
    // read as a second origin.
    Upstream origin = new Upstream("localhost", server.actualPort());
    assertEquals(200, get(client, origin, "/").statusCode());
    HttpConnection connection = connected.get(10, TimeUnit.SECONDS);
    assertEquals(Map.of(origin, 1), pools.open(), "a pooled idle connection still holds a slot");

    connection.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    awaitTrue(() -> pools.open().isEmpty(), "a closed connection gives its slot back");
  }

  @Test
  void aConnectionTurnedIntoARawSocketIsStillUncountedWhenItCloses() throws Exception {
    // A 101 — a WebSocket through EdgeWebSocketUpgrade, any other upgrade through the proxy — and a
    // CONNECT end the same way: the HttpConnection becomes a raw socket and its close handler is
    // never called again. Counted down there, every tunnel would be a slot counted forever; the
    // channel's close future is what fires.
    UpstreamPools pools = new UpstreamPools(64, Upstream::toString);
    HttpClient client =
        EdgeRouter.proxyClient(vertx, EdgeRouter.proxyClientOptions(5000), pools::connected);
    Upstream origin = new Upstream("localhost", server.actualPort());

    io.vertx.core.net.NetSocket tunnel =
        client
            .request(
                EdgeRouter.originRequestOptions(origin)
                    .setMethod(HttpMethod.CONNECT)
                    .setURI("upstream:1"))
            .compose(request -> request.connect())
            .map(HttpClientResponse::netSocket)
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    assertEquals(
        Map.of(origin, 1), pools.open(), "a tunnel is a socket to the origin, and is counted");

    tunnel.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    awaitTrue(() -> pools.open().isEmpty(), "a closed tunnel is uncounted like any connection");
  }

  @Test
  void aTunnelKeepsItsPoolSlotUntilItCloses() throws Exception {
    // Why a WebSocket belongs in the count: Vert.x does not evict a spliced connection from the
    // pool. With a pool of ONE, an open tunnel leaves nothing for the next request, which waits
    // out its acquisition bound; once the tunnel closes, the slot is back.
    HttpClient client =
        EdgeRouter.proxyClient(
            vertx,
            EdgeRouter.proxyClientOptions(5000).setMaxPoolSize(1),
            new UpstreamPools(1, Upstream::toString)::connected);
    Upstream origin = new Upstream("localhost", server.actualPort());
    io.vertx.core.net.NetSocket tunnel =
        client
            .request(
                EdgeRouter.originRequestOptions(origin)
                    .setMethod(HttpMethod.CONNECT)
                    .setURI("upstream:1"))
            .compose(request -> request.connect())
            .map(HttpClientResponse::netSocket)
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);

    CompletableFuture<HttpClientRequest> queued =
        client
            .request(
                EdgeRouter.originRequestOptions(origin)
                    .setConnectTimeout(500)
                    .setMethod(HttpMethod.GET)
                    .setURI("/"))
            .toCompletionStage()
            .toCompletableFuture();
    java.util.concurrent.ExecutionException starved =
        org.junit.jupiter.api.Assertions.assertThrows(
            java.util.concurrent.ExecutionException.class, () -> queued.get(10, TimeUnit.SECONDS));
    assertTrue(
        starved.getCause() instanceof java.util.concurrent.TimeoutException
            || starved.getCause() instanceof io.vertx.core.http.ConnectionPoolTooBusyException,
        "the tunnel holds the only slot: " + starved.getCause());

    tunnel.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    assertEquals(200, get(client, origin, "/").statusCode(), "and gives it back when it closes");
  }

  @Test
  void theEdgesClientReallyPoolsWiderThanVertxsDefaultFive() throws Exception {
    // The builder pools with `new PoolOptions()` unless it is handed the client options' own — so
    // a client built from proxyClientOptions alone would hold FIVE connections per origin, and
    // the sixth request would queue. Six concurrent requests held open by the server must
    // therefore open six connections.
    UpstreamPools pools = new UpstreamPools(64, Upstream::toString);
    HttpClient client =
        EdgeRouter.proxyClient(vertx, EdgeRouter.proxyClientOptions(5000), pools::connected);
    Upstream origin = new Upstream("localhost", server.actualPort());
    List<CompletableFuture<HttpClientResponse>> responses = new CopyOnWriteArrayList<>();
    for (int i = 0; i < 6; i++) {
      responses.add(send(client, origin, "/hold"));
    }
    awaitTrue(() -> held.size() == 6, "six requests in flight at once need six connections");
    assertEquals(Map.of(origin, 6), pools.open());
    held.forEach(request -> request.response().end("released"));
    for (CompletableFuture<HttpClientResponse> response : responses) {
      assertEquals(200, response.get(10, TimeUnit.SECONDS).statusCode());
    }
  }

  @Test
  void anOptionTheChannelCannotTakeIsReportedAndNeverThrown() {
    // An embedded channel is not a NIO socket, so Netty answers false for every JDK socket option:
    // the shape of an epoll transport, or of a native image without libextnet. The connection must
    // still be usable — the answer is the names of what was not set, and one ERROR per name.
    Set<String> refused = assertDoesNotThrow(() -> UpstreamKeepAlive.apply(new EmbeddedChannel()));
    assertEquals(Set.of("TCP_KEEPIDLE", "TCP_KEEPINTERVAL", "TCP_KEEPCOUNT"), refused);
    assertTrue(UpstreamKeepAlive.reported("TCP_KEEPIDLE"));
    // A second channel is refused the same way and reported no further — the set above is per
    // process, which is what keeps a thousand connections from being a thousand ERROR lines.
    assertEquals(refused, UpstreamKeepAlive.apply(new EmbeddedChannel()));
  }

  private HttpClientResponse get(HttpClient client, String path) throws Exception {
    return get(client, new Upstream("localhost", server.actualPort()), path);
  }

  private static HttpClientResponse get(HttpClient client, Upstream origin, String path)
      throws Exception {
    return send(client, origin, path).get(10, TimeUnit.SECONDS);
  }

  /** One request through the edge's own origin options, its body drained so the slot is reused. */
  private static CompletableFuture<HttpClientResponse> send(
      HttpClient client, Upstream origin, String path) {
    Promise<HttpClientResponse> answered = Promise.promise();
    client
        .request(EdgeRouter.originRequestOptions(origin).setMethod(HttpMethod.GET).setURI(path))
        .compose(request -> request.send())
        .compose(response -> response.body().map(body -> response))
        .onComplete(answered);
    return answered.future().toCompletionStage().toCompletableFuture();
  }

  private static void awaitTrue(java.util.function.BooleanSupplier condition, String message)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError(message);
      }
      Thread.sleep(20);
    }
  }
}
