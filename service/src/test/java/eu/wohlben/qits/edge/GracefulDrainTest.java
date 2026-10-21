package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkus.vertx.http.runtime.filters.GracefulShutdownFilter;
import io.quarkus.vertx.http.runtime.filters.QuarkusRequestWrapper;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.WebSocket;
import io.vertx.httpproxy.HttpProxy;
import io.vertx.httpproxy.OriginRequestProvider;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Quarkus' HTTP drain itself — its {@link GracefulShutdownFilter} in front of the edge's proxy
 * chain and its WebSocket splice — and whether it reaches zero once the streams are closed
 * (qits-1170).
 *
 * <p>{@link StreamDrainTest} shows that closing the stream pool ends both kinds of stream toward
 * their clients. That was not enough: the live redeploy of 2026-10-21 closed 13 streams and then
 * waited out the full 9m30s, because the filter's count never came down for a response the proxy
 * reset or a socket the edge spliced — see {@link DrainCount}. Each test here starts the drain the
 * way Quarkus does — {@code preShutdown}, then {@code shutdown} with a notification — and asserts
 * the notification arrives; the last one shows the drain still waits for a transfer in flight.
 *
 * <p>Framework-free like {@link StreamDrainTest}, but every inbound request is wrapped and counted
 * exactly as Quarkus' root handler does, and proxied through {@link EdgeRouter#reverseProxy}'s
 * chain.
 */
class GracefulDrainTest {

  private static final long DRAIN_SECONDS = 5;

  private Vertx vertx;

  private HttpServer upstream;

  private HttpServer edge;

  private UpstreamPools pools;

  private GracefulShutdownFilter filter;

  @BeforeEach
  void listen() throws Exception {
    vertx = Vertx.vertx();
    upstream =
        vertx
            .createHttpServer()
            .webSocketHandler(socket -> socket.textMessageHandler(socket::writeTextMessage))
            .requestHandler(
                request -> {
                  if (request.path().equals("/plain")) {
                    request.response().end("ok");
                    return;
                  }
                  // An SSE channel, or a transfer: one chunk, then held open.
                  request
                      .response()
                      .setChunked(true)
                      .putHeader(HttpHeaders.CONTENT_TYPE, "text/event-stream")
                      .write("data: hello\n\n");
                  if (request.path().equals("/dies")) {
                    // The upstream task goes away mid-body, as a redeployed one does.
                    vertx.setTimer(100, t -> request.connection().close());
                  }
                })
            .listen(0, "localhost")
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    Upstream origin = new Upstream("localhost", upstream.actualPort());
    pools = new UpstreamPools(UpstreamPools.STREAM, 256, Upstream::toString);
    HttpClient client =
        EdgeRouter.proxyClient(vertx, EdgeRouter.proxyClientOptions(5000), pools::connected);
    OriginRequestProvider provider =
        context -> client.request(EdgeRouter.originRequestOptions(origin));
    HttpProxy proxy = EdgeRouter.reverseProxy(client, origin, provider, provider);
    EdgeWebSocketUpgrade upgrade = new EdgeWebSocketUpgrade(client, pools);
    filter = new GracefulShutdownFilter();
    filter.next(
        request -> {
          if (request.headers().contains(HttpHeaders.UPGRADE)) {
            request.pause();
            upgrade.handle(request, origin, EdgeRouter.originRequestOptions(origin));
          } else {
            proxy.handle(request);
          }
        });
    edge =
        vertx
            .createHttpServer()
            // What Quarkus' root handler does with every request before its router sees it.
            .requestHandler(
                request -> filter.handle(new QuarkusRequestWrapper(request, (cookie, r) -> {})))
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
  void aProxiedSseChannelClosedAtShutdownLetsTheDrainEnd() throws Exception {
    openSse(vertx.createHttpClient(), "/events");

    assertEquals(1, pools.closeAll());

    drain().get(DRAIN_SECONDS, TimeUnit.SECONDS);
  }

  @Test
  void anHttp2SseChannelClosedAtShutdownLetsTheDrainEnd() throws Exception {
    openSse(
        vertx.createHttpClient(
            new HttpClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_2)
                .setHttp2ClearTextUpgrade(false)),
        "/events");

    assertEquals(1, pools.closeAll());

    drain().get(DRAIN_SECONDS, TimeUnit.SECONDS);
  }

  @Test
  void aSplicedWebSocketClosedAtShutdownLetsTheDrainEnd() throws Exception {
    WebSocket socket = openWebSocket();
    CompletableFuture<Void> closed = new CompletableFuture<>();
    socket.closeHandler(v -> closed.complete(null));

    assertEquals(1, pools.closeAll());
    closed.get(10, TimeUnit.SECONDS);

    drain().get(DRAIN_SECONDS, TimeUnit.SECONDS);
  }

  @Test
  void aWebSocketItsClientClosedLongBeforeLeavesNothingCounted() throws Exception {
    WebSocket socket = openWebSocket();
    socket.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    awaitTrue(() -> pools.open().isEmpty(), "the tunnel is gone");

    drain().get(DRAIN_SECONDS, TimeUnit.SECONDS);
  }

  @Test
  void aTransferWhoseUpstreamDiedLongBeforeLeavesNothingCounted() throws Exception {
    CompletableFuture<Void> ended = openSse(vertx.createHttpClient(), "/dies");
    ended.get(10, TimeUnit.SECONDS);

    drain().get(DRAIN_SECONDS, TimeUnit.SECONDS);
  }

  @Test
  void anIdleKeepAliveConnectionIsNotCounted() throws Exception {
    String body =
        vertx
            .createHttpClient()
            .request(HttpMethod.GET, edge.actualPort(), "localhost", "/plain")
            .compose(request -> request.send().compose(response -> response.body()))
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS)
            .toString();
    assertEquals("ok", body);

    drain().get(DRAIN_SECONDS, TimeUnit.SECONDS);
  }

  @Test
  void aTransferStillMovingHoldsTheDrain() throws Exception {
    openSse(vertx.createHttpClient(), "/download");

    CompletableFuture<Void> drained = drain();
    Thread.sleep(500);
    assertFalse(drained.isDone(), "the drain waits for what is in flight");

    pools.closeAll();
    drained.get(DRAIN_SECONDS, TimeUnit.SECONDS);
  }

  /** Quarkus' two shutdown passes over the filter; completes when the filter reports done. */
  private CompletableFuture<Void> drain() {
    CompletableFuture<Void> drained = new CompletableFuture<>();
    filter.preShutdown(() -> {});
    filter.shutdown(() -> drained.complete(null));
    return drained;
  }

  /** Opens a stream through the edge, waits for its first chunk; completes when it ends. */
  private CompletableFuture<Void> openSse(HttpClient client, String path) throws Exception {
    CompletableFuture<String> first = new CompletableFuture<>();
    CompletableFuture<Void> ended = new CompletableFuture<>();
    client
        .request(
            new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setHost("localhost")
                .setPort(edge.actualPort())
                .setURI(path)
                .putHeader(HttpHeaders.ACCEPT, "text/event-stream"))
        .compose(request -> request.send())
        .onSuccess(
            response -> {
              response.handler(chunk -> first.complete(chunk.toString()));
              response.endHandler(v -> ended.complete(null));
              response.exceptionHandler(failure -> ended.complete(null));
            })
        .onFailure(first::completeExceptionally);
    assertEquals("data: hello\n\n", first.get(10, TimeUnit.SECONDS));
    return ended;
  }

  private WebSocket openWebSocket() throws Exception {
    WebSocket socket =
        vertx
            .createHttpClient()
            .webSocket(edge.actualPort(), "localhost", "/terminal")
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    CompletableFuture<String> echoed = new CompletableFuture<>();
    socket.textMessageHandler(echoed::complete);
    socket.writeTextMessage("ls");
    assertEquals("ls", echoed.get(10, TimeUnit.SECONDS), "the splice is live");
    return socket;
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
