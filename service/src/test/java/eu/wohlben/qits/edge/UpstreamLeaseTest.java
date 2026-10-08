package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import io.quarkus.opentelemetry.runtime.QuarkusContextStorage;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.impl.HttpServerRequestInternal;
import io.vertx.core.impl.ContextInternal;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a lease reads off an inbound request, against a real Vert.x server request — above all WHICH
 * trace id, because a wrong one sends whoever reads it to an unrelated exchange.
 *
 * <p>The suite runs with the OTel SDK off, so no server span exists in any {@code @QuarkusTest}
 * here. This test puts one where Quarkus' tracer puts it — {@code
 * QuarkusContextStorage.attach(requestContext, ...)}, which is what {@code
 * InstrumenterVertxTracer.receiveRequest} calls with the request's own duplicated context — and
 * then reads the lease from ANOTHER context with another span current, the shape of an origin
 * provider resumed by a future some other request created.
 */
class UpstreamLeaseTest {

  private static final String REQUEST_TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

  private static final String OTHER_TRACE = "0af7651916cd43dd8448eb211c80319c";

  private static final String HEADER_TRACE = "11111111111111111111111111111111";

  private Vertx vertx;

  private HttpServer server;

  /** What the server computes per request, chosen by each test. */
  private volatile Function<HttpServerRequest, Object> probe;

  @BeforeEach
  void listen() throws Exception {
    vertx = Vertx.vertx();
    server =
        vertx
            .createHttpServer()
            .requestHandler(
                request -> {
                  Object answer = probe.apply(request);
                  if (answer instanceof io.vertx.core.Future<?> future) {
                    future.onComplete(
                        done ->
                            request
                                .response()
                                .end(String.valueOf(done.succeeded() ? done.result() : done)));
                  } else {
                    request.response().end(String.valueOf(answer));
                  }
                })
            .listen(0, "127.0.0.1")
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
  }

  @AfterEach
  void close() throws Exception {
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  private static io.opentelemetry.context.Context withSpan(String traceId) {
    return io.opentelemetry.context.Context.root()
        .with(
            Span.wrap(
                SpanContext.create(
                    traceId,
                    "00f067aa0ba902b7",
                    TraceFlags.getSampled(),
                    TraceState.getDefault())));
  }

  private String ask(String uri, Map<String, String> headers) throws Exception {
    HttpClient client = vertx.createHttpClient();
    RequestOptions options =
        new RequestOptions()
            .setHost("127.0.0.1")
            .setPort(server.actualPort())
            .setMethod(HttpMethod.GET)
            .setURI(uri);
    headers.forEach(options::putHeader);
    CompletableFuture<String> body = new CompletableFuture<>();
    client
        .request(options)
        .compose(request -> request.send())
        .compose(response -> response.body())
        .onComplete(
            done -> {
              if (done.succeeded()) {
                body.complete(done.result().toString());
              } else {
                body.completeExceptionally(done.cause());
              }
            });
    return body.get(10, TimeUnit.SECONDS);
  }

  @Test
  void theTraceIsTheOneAttachedToTheRequestsOwnContextWhereverTheLeaseIsRead() throws Exception {
    probe =
        request -> {
          ContextInternal own = (ContextInternal) ((HttpServerRequestInternal) request).context();
          QuarkusContextStorage.INSTANCE.attach(own, withSpan(REQUEST_TRACE));
          // Another request's context, with ITS span current: what Span.current() would read if
          // the provider were resumed there.
          ContextInternal elsewhere = own.unwrap().duplicate();
          QuarkusContextStorage.INSTANCE.attach(elsewhere, withSpan(OTHER_TRACE));
          Promise<String> read = Promise.promise();
          elsewhere.runOnContext(
              ignored -> {
                String current = Span.current().getSpanContext().getTraceId();
                read.complete(current + " " + UpstreamLease.traceId(request));
              });
          return read.future();
        };
    assertEquals(
        OTHER_TRACE + " " + REQUEST_TRACE,
        ask("/", Map.of("traceparent", "00-" + HEADER_TRACE + "-00f067aa0ba902b7-01")),
        "Span.current() there is another request's span; the lease still reads this request's");
  }

  @Test
  void withNoSpanAttachedTheCurrentSpanAndThenTheTraceparentAnswer() throws Exception {
    probe =
        request -> {
          String headerOnly = UpstreamLease.traceId(request);
          String current;
          try (Scope ignored = withSpan(OTHER_TRACE).makeCurrent()) {
            current = UpstreamLease.traceId(request);
          }
          return headerOnly + " " + current;
        };
    assertEquals(
        HEADER_TRACE + " " + OTHER_TRACE,
        ask("/", Map.of("traceparent", "00-" + HEADER_TRACE + "-00f067aa0ba902b7-01")));
    probe = request -> String.valueOf(UpstreamLease.traceId(request));
    assertEquals(
        "null", ask("/", Map.of()), "no span and no header: no trace, never a made-up one");
  }

  @Test
  void theLeaseNamesTheExchangeWithoutItsQueryString() throws Exception {
    probe =
        request -> {
          UpstreamLease lease = UpstreamLease.of(request);
          return lease.method()
              + "|"
              + lease.path()
              + "|"
              + lease.client()
              + "|"
              + lease.userAgent()
              + "|"
              + lease.workspaceId();
        };
    assertEquals(
        "GET|/projects/mcp|203.0.113.7|agent/1|ws-42",
        ask(
            "/projects/mcp?workspaceId=ws-42&token=secret",
            Map.of("X-Forwarded-For", " 203.0.113.7 , 10.0.0.1", "User-Agent", "agent/1")),
        "the first forwarded hop, and the query reduced to the one parameter a lease keeps");
    assertEquals(
        "GET|/plain|127.0.0.1|agent/2|null",
        ask("/plain", Map.of("User-Agent", "agent/2")),
        "no forwarded header: the socket's own peer");
  }

  @Test
  void aTraceparentIsReadOnlyWhenItIsOne() {
    assertEquals(
        REQUEST_TRACE,
        UpstreamLease.traceparentTraceId(
            "00-" + REQUEST_TRACE.toUpperCase() + "-00f067aa0ba902b7-01"));
    assertNull(UpstreamLease.traceparentTraceId(null));
    assertNull(UpstreamLease.traceparentTraceId("garbage"));
    assertNull(UpstreamLease.traceparentTraceId("00-" + "0".repeat(32) + "-00f067aa0ba902b7-01"));
    assertNull(UpstreamLease.traceparentTraceId("00-" + "z".repeat(32) + "-00f067aa0ba902b7-01"));
  }
}
