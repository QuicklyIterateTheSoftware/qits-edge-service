package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ConnectionPoolTooBusyException;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpClosedException;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import io.vertx.httpproxy.HttpProxy;
import java.net.ConnectException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link UpstreamRetry} against a real socket: an upstream that hangs up on its first connection
 * without a byte of answer — the drained task whose pooled connection the edge reused — and answers
 * every later one.
 *
 * <p>Framework-free, like {@code UpstreamKeepAliveTest}: a Vert.x of its own, the upstream a raw
 * {@link NetServer} so the first connection can be closed exactly where the bug closes it, and in
 * front of it a proxy built the way {@code EdgeRouter.reverseProxy} builds one — the edge's client
 * options, its origin request options, and the retry as the last interceptor dialling a
 * non-keep-alive client.
 */
class UpstreamRetryTest {

  private Vertx vertx;

  private NetServer upstream;

  private HttpServer edge;

  private HttpClient caller;

  /** Every connection the upstream accepted. */
  private final AtomicInteger connections = new AtomicInteger();

  /** What the upstream does with connection number N (1-based). */
  private volatile java.util.function.IntFunction<Behaviour> behaviour = n -> Behaviour.ANSWER;

  private enum Behaviour {
    /** Read the request, then close without a byte of response. */
    HANG_UP,
    /** Send the response head and part of the body, then close. */
    HANG_UP_MID_BODY,
    /** Answer 200 {@code ok}. */
    ANSWER
  }

  @BeforeEach
  void listen() throws Exception {
    vertx = Vertx.vertx();
    upstream =
        vertx
            .createNetServer()
            .connectHandler(
                socket -> {
                  Behaviour does = behaviour.apply(connections.incrementAndGet());
                  socket.handler(bytes -> respond(socket, does, bytes));
                })
            .listen(0, "localhost")
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    Upstream origin = new Upstream("localhost", upstream.actualPort());
    HttpClient pooled =
        EdgeRouter.proxyClient(vertx, EdgeRouter.proxyClientOptions(5000), connection -> {});
    HttpClient fresh =
        EdgeRouter.proxyClient(
            vertx, EdgeRouter.proxyClientOptions(5000).setKeepAlive(false), connection -> {});
    HttpProxy proxy =
        HttpProxy.reverseProxy(pooled)
            .origin(context -> pooled.request(EdgeRouter.originRequestOptions(origin)))
            .addInterceptor(new EdgeHopByHop())
            .addInterceptor(new EdgeRequestFraming())
            .addInterceptor(
                new UpstreamRetry(
                    origin, context -> fresh.request(EdgeRouter.originRequestOptions(origin))));
    edge =
        vertx
            .createHttpServer()
            .requestHandler(proxy)
            .listen(0, "localhost")
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    caller = vertx.createHttpClient();
  }

  @AfterEach
  void close() throws Exception {
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  private static void respond(NetSocket socket, Behaviour does, Buffer request) {
    // One read is the whole of these small requests' heads; the POST's body follows its head and
    // is ignored either way.
    if (!request.toString().contains("\r\n\r\n")) {
      return;
    }
    switch (does) {
      case HANG_UP -> socket.close();
      case HANG_UP_MID_BODY -> {
        socket.write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\npartial");
        socket.close();
      }
      case ANSWER -> {
        boolean head = request.toString().startsWith("HEAD ");
        socket.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n" + (head ? "" : "ok"));
      }
    }
  }

  @Test
  void aGetWhoseFirstConnectionHangsUpUnansweredIsSentOnceMoreAndAnswered() throws Exception {
    behaviour = n -> n == 1 ? Behaviour.HANG_UP : Behaviour.ANSWER;

    Reply reply = send(HttpMethod.GET, null);

    assertEquals(200, reply.status(), "the second attempt reached the upstream");
    assertEquals("ok", reply.body());
    assertEquals(2, connections.get(), "exactly one more connection, a new one");
  }

  @Test
  void aHeadIsRetriedTheSameWay() throws Exception {
    behaviour = n -> n == 1 ? Behaviour.HANG_UP : Behaviour.ANSWER;

    assertEquals(200, send(HttpMethod.HEAD, null).status());
    assertEquals(2, connections.get());
  }

  @Test
  void aSecondFailureIsTheSame502AsBeforeAndThereIsNoThirdAttempt() throws Exception {
    behaviour = n -> Behaviour.HANG_UP;

    assertEquals(502, send(HttpMethod.GET, null).status());
    assertEquals(2, connections.get(), "once, not until it works");
  }

  @Test
  void aPostIsNeverSentTwice() throws Exception {
    behaviour = n -> n == 1 ? Behaviour.HANG_UP : Behaviour.ANSWER;

    assertEquals(502, send(HttpMethod.POST, "payload").status());
    assertEquals(1, connections.get(), "a POST may have done its work before the hang-up");
  }

  @Test
  void aResponseThatHadStartedIsNotRetried() throws Exception {
    // The head arrived and the proxy began relaying it: what the client already holds cannot be
    // taken back, so the failure is the client's, as it always was.
    behaviour = n -> n == 1 ? Behaviour.HANG_UP_MID_BODY : Behaviour.ANSWER;

    ExecutionException cut =
        assertThrows(ExecutionException.class, () -> send(HttpMethod.GET, null));
    assertTrue(
        cut.getCause() instanceof HttpClosedException
            || cut.getCause() instanceof java.io.IOException,
        "the client sees its response cut: " + cut.getCause());
    assertEquals(1, connections.get(), "and the upstream never sees a second request");
  }

  @Test
  void onlyABodylessGetOrHeadIsReplayable() {
    MultiMap none = MultiMap.caseInsensitiveMultiMap();
    assertTrue(UpstreamRetry.replayable(HttpMethod.GET, none));
    assertTrue(UpstreamRetry.replayable(HttpMethod.HEAD, none));
    assertTrue(
        UpstreamRetry.replayable(
            HttpMethod.GET,
            MultiMap.caseInsensitiveMultiMap().add(HttpHeaders.CONTENT_LENGTH, "0")));
    assertFalse(
        UpstreamRetry.replayable(
            HttpMethod.GET,
            MultiMap.caseInsensitiveMultiMap().add(HttpHeaders.CONTENT_LENGTH, "3")));
    assertFalse(
        UpstreamRetry.replayable(
            HttpMethod.GET,
            MultiMap.caseInsensitiveMultiMap().add(HttpHeaders.TRANSFER_ENCODING, "chunked")));
    for (HttpMethod method :
        new HttpMethod[] {
          HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.OPTIONS
        }) {
      assertFalse(UpstreamRetry.replayable(method, none), method.name());
    }
  }

  @Test
  void aBrokenConnectionIsRetryableAndAFullPoolIsNot() {
    assertTrue(UpstreamRetry.retryable(new HttpClosedException("Connection was closed")));
    assertTrue(UpstreamRetry.retryable(new ConnectException("Connection refused")));
    assertTrue(UpstreamRetry.retryable(new java.io.IOException("Connection reset by peer")));
    assertTrue(
        UpstreamRetry.retryable(new io.netty.channel.ConnectTimeoutException("connect timed out")));
    assertFalse(UpstreamRetry.retryable(new ConnectionPoolTooBusyException("full")));
    assertFalse(UpstreamRetry.retryable(new TimeoutException("waited for a connection")));
  }

  private record Reply(int status, String body) {}

  private Reply send(HttpMethod method, String body) throws Exception {
    return caller
        .request(
            new RequestOptions()
                .setMethod(method)
                .setHost("localhost")
                .setPort(edge.actualPort())
                .setURI("/artifact.jar"))
        .compose(request -> body == null ? request.send() : request.send(body))
        .compose(
            (HttpClientResponse response) ->
                response.body().map(bytes -> new Reply(response.statusCode(), bytes.toString())))
        .toCompletionStage()
        .toCompletableFuture()
        .get(20, TimeUnit.SECONDS);
  }
}
