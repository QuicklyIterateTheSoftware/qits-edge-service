package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.UpgradeRejectedException;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.httpproxy.HttpProxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A connection that closes under an upstream exchange is not an ERROR (qits-2717).
 *
 * <p>Vert.x logs {@code ERROR Connection was closed} from {@code HttpClientRequestImpl} itself
 * whenever the connection under an unfinished client request goes away and nobody gave that request
 * an exception handler. Every WebSocket the edge splices is such a request — a {@code connect()} is
 * never ended — so every socket that ended, for any reason, filed one ERROR. These exchanges run
 * through the edge's own {@link EdgeWebSocketUpgrade} and the proxy's origin provider with {@link
 * UpstreamCloses#watch}, as {@code EdgeRouter} wires them, and assert what reaches the log.
 *
 * <p>Framework-free, like {@link StreamDrainTest}: a Vert.x of its own, two upstreams — a polite
 * one, and one whose every close is a reset ({@code SO_LINGER 0}), which is how an upstream that
 * dies mid-exchange looks from the edge — and a front server in place of the catch-all route.
 */
class UpstreamClosesTest {

  private Vertx vertx;

  private HttpServer upstream;

  private HttpServer resetting;

  private HttpServer edge;

  private HttpServer resettingEdge;

  private final List<String> records = new CopyOnWriteArrayList<>();

  private final Handler capture =
      new Handler() {
        private final Formatter formatter = new SimpleFormatter();

        @Override
        public void publish(LogRecord record) {
          records.add(
              record.getLevel()
                  + " "
                  + record.getLoggerName()
                  + " "
                  + formatter.formatMessage(record));
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
      };

  @BeforeEach
  void listen() throws Exception {
    vertx = Vertx.vertx();
    upstream = await(upstream(new HttpServerOptions()).listen(0, "localhost"));
    resetting = await(upstream(new HttpServerOptions().setSoLinger(0)).listen(0, "localhost"));
    edge = await(edgeFor(new Upstream("localhost", upstream.actualPort())));
    resettingEdge = await(edgeFor(new Upstream("localhost", resetting.actualPort())));
    java.util.logging.Logger.getLogger("").addHandler(capture);
  }

  @AfterEach
  void close() throws Exception {
    java.util.logging.Logger.getLogger("").removeHandler(capture);
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  /**
   * The upstream: {@code /refuse} turns an upgrade down with a 404, any other WebSocket echoes and
   * hangs up on {@code bye} (a close frame) or drops its connection on {@code die}; {@code /cut}
   * drops its connection on the first chunk of a body, anything else is read to its end and
   * answered 200.
   */
  private io.vertx.core.http.HttpServer upstream(HttpServerOptions options) {
    return vertx
        .createHttpServer(options)
        .requestHandler(
            request -> {
              if (request.path().equals("/refuse")) {
                request.response().setStatusCode(404).end();

              } else if (request.headers().contains(HttpHeaders.UPGRADE)) {
                request
                    .toWebSocket()
                    .onSuccess(
                        socket ->
                            socket.textMessageHandler(
                                message -> {
                                  switch (message) {
                                    case "bye" -> socket.close();
                                    case "die" -> request.connection().close();
                                    default -> socket.writeTextMessage(message);
                                  }
                                }));
              } else if (request.path().equals("/cut")) {
                request.handler(chunk -> request.connection().close());
              } else {
                request.body().onSuccess(body -> request.response().end("read " + body.length()));
              }
            });
  }

  /** The edge in front of one upstream: the WebSocket path, else the proxy with the watch. */
  private io.vertx.core.Future<HttpServer> edgeFor(Upstream origin) {
    UpstreamPools pools = new UpstreamPools(UpstreamPools.STREAM, 256, Upstream::toString);
    HttpClient client =
        EdgeRouter.proxyClient(vertx, EdgeRouter.proxyClientOptions(5000), pools::connected);
    HttpProxy proxy =
        HttpProxy.reverseProxy(client)
            .origin(
                context ->
                    client
                        .request(EdgeRouter.originRequestOptions(origin))
                        .onSuccess(
                            request ->
                                UpstreamCloses.watch(
                                    request, context.request().proxiedRequest(), origin)));
    EdgeWebSocketUpgrade upgrade = new EdgeWebSocketUpgrade(client, pools);
    return vertx
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
        .listen(0, "localhost");
  }

  @Test
  void aWebSocketTheClientClosesLogsNoError() throws Exception {
    WebSocket socket = open(edge, "/terminal");
    CompletableFuture<Void> closed = new CompletableFuture<>();
    socket.closeHandler(v -> closed.complete(null));
    await(socket.close());
    closed.get(10, TimeUnit.SECONDS);

    settle();
    assertNo("ERROR");
    assertNo("WARN");
  }

  /**
   * A client gone without a close frame — the TCP connection simply ends, as a killed CLI's does.
   */
  @Test
  void aWebSocketWhoseClientDropsTheConnectionLogsNoError() throws Exception {
    dropAfterUpgrade(edge);

    settle();
    assertNo("ERROR");
    assertNo("WARN");
  }

  @Test
  void aWebSocketTheUpstreamClosesLogsNoError() throws Exception {
    WebSocket socket = open(edge, "/terminal");
    CompletableFuture<Void> closed = new CompletableFuture<>();
    socket.closeHandler(v -> closed.complete(null));
    socket.writeTextMessage("bye");
    closed.get(10, TimeUnit.SECONDS);

    settle();
    assertNo("ERROR");
    assertNo("WARN");
  }

  /**
   * The upstream ends the TCP connection without a close frame — a FIN, not a reset: an upstream
   * task stopping, as qits-ci's did under the private edge's daemon sockets.
   */
  @Test
  void aWebSocketWhoseUpstreamDropsTheConnectionLogsNoError() throws Exception {
    WebSocket socket = open(edge, "/terminal");
    CompletableFuture<Void> closed = new CompletableFuture<>();
    socket.closeHandler(v -> closed.complete(null));
    socket.exceptionHandler(failure -> closed.complete(null));
    socket.writeTextMessage("die");
    closed.get(10, TimeUnit.SECONDS);

    settle();
    assertNo("ERROR");
    assertNo("WARN");
  }

  /**
   * The upstream's 101 and its FIN arrive together, so the connection closes while the handshake
   * still owns it — before the edge has spliced.
   */
  @Test
  void aWebSocketWhoseUpstreamClosesWithItsUpgradeLogsNoError() throws Exception {
    io.vertx.core.net.NetServer raw =
        await(
            vertx
                .createNetServer()
                .connectHandler(
                    socket ->
                        socket.handler(
                            head ->
                                socket.end(
                                    Buffer.buffer(
                                        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                                            + "Connection: Upgrade\r\n\r\n"))))
                .listen(0, "localhost"));
    HttpServer through = await(edgeFor(new Upstream("localhost", raw.actualPort())));
    dropAfterUpgrade(through);

    settle();
    assertNo("ERROR");
    assertNo("WARN");
  }

  @Test
  void anUpstreamDyingMidWebSocketLogsOneWarningNamingItAndThePath() throws Exception {
    WebSocket socket = open(resettingEdge, "/terminal");
    CompletableFuture<Void> closed = new CompletableFuture<>();
    socket.closeHandler(v -> closed.complete(null));
    socket.exceptionHandler(failure -> closed.complete(null));
    socket.writeTextMessage("die");
    closed.get(10, TimeUnit.SECONDS);

    settle();
    assertNo("ERROR");
    List<String> warnings = matching("WARN");
    assertEquals(1, warnings.size(), "one WARN for the socket: " + records);
    assertTrue(warnings.get(0).contains("/terminal"), warnings.get(0));
    assertTrue(warnings.get(0).contains("localhost:" + resetting.actualPort()), warnings.get(0));
  }

  @Test
  void anUpgradeTheUpstreamRefusesIsRelayedWithNoError() throws Exception {
    ExecutionException refused =
        org.junit.jupiter.api.Assertions.assertThrows(
            ExecutionException.class, () -> open(edge, "/refuse"));
    assertEquals(404, ((UpgradeRejectedException) refused.getCause()).getStatus());

    settle();
    assertNo("ERROR");
  }

  @Test
  void anUploadTheUpstreamCutsAnswers502AndLogsOneWarning() throws Exception {
    CompletableFuture<Integer> status = new CompletableFuture<>();
    HttpClientRequest upload = upload(resettingEdge, "/cut");
    upload.response().onSuccess(response -> status.complete(response.statusCode()));
    upload.response().onFailure(status::completeExceptionally);
    upload.write(Buffer.buffer("x".repeat(1024)));
    vertx.setPeriodic(
        50,
        id -> {
          if (status.isDone()) {
            vertx.cancelTimer(id);
          } else {
            upload.write(Buffer.buffer("x".repeat(1024)));
          }
        });

    assertEquals(502, status.get(10, TimeUnit.SECONDS));

    settle();
    assertNo("ERROR");
    List<String> warnings = matching("WARN");
    assertEquals(1, warnings.size(), "one WARN for the upload: " + records);
    assertTrue(warnings.get(0).contains("POST /cut"), warnings.get(0));
    assertTrue(warnings.get(0).contains("localhost:" + resetting.actualPort()), warnings.get(0));
  }

  @Test
  void anUploadTheClientAbortsLogsNeitherErrorNorWarning() throws Exception {
    HttpClientRequest upload = upload(edge, "/upload");
    await(upload.write(Buffer.buffer("x".repeat(1024))));
    Thread.sleep(200);
    await(upload.connection().close());

    settle();
    assertNo("ERROR");
    assertNo("WARN");
  }

  /** A completed upload still reads as it did: the watch changes nothing about a good exchange. */
  @Test
  void aCompleteUploadIsAnsweredAsBefore() throws Exception {
    HttpClientRequest upload = upload(edge, "/upload");
    CompletableFuture<String> body = new CompletableFuture<>();
    upload
        .response()
        .compose(response -> response.body())
        .onSuccess(read -> body.complete(read.toString()))
        .onFailure(body::completeExceptionally);
    upload.end(Buffer.buffer("x".repeat(1024)));
    assertEquals("read 1024", body.get(10, TimeUnit.SECONDS));

    settle();
    assertNo("ERROR");
    assertNo("WARN");
  }

  /** A raw client: the handshake, the 101 (or the connection ending), then a dropped connection. */
  private void dropAfterUpgrade(HttpServer through) throws Exception {
    io.vertx.core.net.NetSocket raw =
        await(vertx.createNetClient().connect(through.actualPort(), "localhost"));
    raw.exceptionHandler(failure -> {});
    CompletableFuture<Void> upgraded = new CompletableFuture<>();
    raw.handler(
        bytes -> {
          if (bytes.toString().startsWith("HTTP/1.1 101")) {
            upgraded.complete(null);
          }
        });
    raw.closeHandler(v -> upgraded.complete(null));
    raw.write(
        "GET /terminal HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n"
            + "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
            + "Sec-WebSocket-Version: 13\r\n\r\n");
    upgraded.get(10, TimeUnit.SECONDS);
    Thread.sleep(200);
    raw.close();
  }

  private WebSocket open(HttpServer through, String path) throws Exception {
    WebSocket socket =
        await(
            vertx
                .createHttpClient()
                .webSocket(
                    new WebSocketConnectOptions()
                        .setHost("localhost")
                        .setPort(through.actualPort())
                        .setURI(path)));
    CompletableFuture<String> echoed = new CompletableFuture<>();
    socket.textMessageHandler(echoed::complete);
    socket.writeTextMessage("ls");
    assertEquals("ls", echoed.get(10, TimeUnit.SECONDS), "the splice is live");
    return socket;
  }

  private HttpClientRequest upload(HttpServer through, String path) throws Exception {
    HttpClientRequest request =
        await(
            vertx
                .createHttpClient()
                .request(
                    new RequestOptions()
                        .setMethod(HttpMethod.POST)
                        .setHost("localhost")
                        .setPort(through.actualPort())
                        .setURI(path)));
    request.setChunked(true);
    // The test's own client request, aborted or cut on purpose: its exceptions are expected, and
    // left unhandled Vert.x would log them at ERROR here and blame the edge.
    request.exceptionHandler(failure -> {});
    return request;
  }

  /** Long enough for both ends of a closed connection to have run their handlers. */
  private static void settle() throws InterruptedException {
    Thread.sleep(500);
  }

  private List<String> matching(String level) {
    return records.stream().filter(line -> line.startsWith(level + " ")).toList();
  }

  private void assertNo(String level) {
    assertEquals(List.of(), matching(level), "no " + level + " record");
  }

  private static <T> T await(io.vertx.core.Future<T> future) throws Exception {
    return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }
}
