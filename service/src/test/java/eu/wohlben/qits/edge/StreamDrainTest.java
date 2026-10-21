package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.WebSocket;
import io.vertx.httpproxy.HttpProxy;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What {@code EdgeRouter.draining} does when graceful shutdown begins — {@link
 * UpstreamPools#closeAll} on the stream pool — seen from the client of a live SSE channel and of a
 * live WebSocket, both proxied the way the edge proxies them (qits-1170).
 *
 * <p>The event itself is Quarkus' to fire and is not raised here: what has to hold is that closing
 * the pool's connections ENDS both kinds of stream toward their clients, which is what lets the
 * HTTP drain finish instead of waiting out its timeout.
 *
 * <p>Framework-free: a Vert.x of its own, an upstream that never ends what it opens, and a front
 * server in place of the edge's catch-all route.
 */
class StreamDrainTest {

  private Vertx vertx;

  private HttpServer upstream;

  private HttpServer edge;

  private UpstreamPools pools;

  @BeforeEach
  void listen() throws Exception {
    vertx = Vertx.vertx();
    upstream =
        vertx
            .createHttpServer()
            .webSocketHandler(socket -> socket.textMessageHandler(socket::writeTextMessage))
            .requestHandler(
                request -> {
                  // An SSE channel: one event, and then held open for as long as anybody lets it.
                  request
                      .response()
                      .setChunked(true)
                      .putHeader(HttpHeaders.CONTENT_TYPE, "text/event-stream")
                      .write("data: hello\n\n");
                })
            .listen(0, "localhost")
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    Upstream origin = new Upstream("localhost", upstream.actualPort());
    pools = new UpstreamPools(UpstreamPools.STREAM, 256, Upstream::toString);
    HttpClient client =
        EdgeRouter.proxyClient(vertx, EdgeRouter.proxyClientOptions(5000), pools::connected);
    HttpProxy proxy =
        HttpProxy.reverseProxy(client)
            .origin(context -> client.request(EdgeRouter.originRequestOptions(origin)));
    EdgeWebSocketUpgrade upgrade = new EdgeWebSocketUpgrade(client, pools);
    edge =
        vertx
            .createHttpServer()
            .requestHandler(
                request -> {
                  if (request.headers().contains(HttpHeaders.UPGRADE)) {
                    request.pause();
                    upgrade.handle(request, origin, EdgeRouter.originRequestOptions(origin));
                  } else {
                    proxy.handle(request);
                  }
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
  void closingTheStreamPoolEndsAProxiedSseChannelTowardItsClient() throws Exception {
    CompletableFuture<String> first = new CompletableFuture<>();
    CompletableFuture<Void> ended = new CompletableFuture<>();
    vertx
        .createHttpClient()
        .request(
            new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setHost("localhost")
                .setPort(edge.actualPort())
                .setURI("/events")
                .putHeader(HttpHeaders.ACCEPT, "text/event-stream"))
        .compose(request -> request.send())
        .onSuccess(
            response -> {
              response.handler(chunk -> first.complete(chunk.toString()));
              // Either is the stream ending: a clean end, or the reset the proxy answers an
              // upstream that went away mid-body with. Both make an EventSource reconnect.
              response.endHandler(v -> ended.complete(null));
              response.exceptionHandler(failure -> ended.complete(null));
            })
        .onFailure(ended::completeExceptionally);
    assertEquals("data: hello\n\n", first.get(10, TimeUnit.SECONDS));
    Upstream origin = new Upstream("localhost", upstream.actualPort());
    assertEquals(Map.of(origin, 1), pools.open(), "the channel holds one stream connection");

    assertEquals(1, pools.closeAll());

    ended.get(10, TimeUnit.SECONDS);
    awaitTrue(() -> pools.open().isEmpty(), "and the connection is gone from the pool");
  }

  @Test
  void closingTheStreamPoolClosesASplicedWebSocket() throws Exception {
    WebSocket socket =
        vertx
            .createHttpClient()
            .webSocket(edge.actualPort(), "localhost", "/terminal")
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    CompletableFuture<String> echoed = new CompletableFuture<>();
    CompletableFuture<Void> closed = new CompletableFuture<>();
    socket.textMessageHandler(echoed::complete);
    socket.closeHandler(v -> closed.complete(null));
    socket.writeTextMessage("ls");
    assertEquals("ls", echoed.get(10, TimeUnit.SECONDS), "the splice is live");

    assertEquals(1, pools.closeAll());

    closed.get(10, TimeUnit.SECONDS);
    awaitTrue(() -> pools.open().isEmpty(), "and the tunnel is gone from the pool");
  }

  @Test
  void anIdlePoolHasNothingToClose() {
    assertEquals(0, pools.closeAll());
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
    assertTrue(condition.getAsBoolean(), message);
  }
}
