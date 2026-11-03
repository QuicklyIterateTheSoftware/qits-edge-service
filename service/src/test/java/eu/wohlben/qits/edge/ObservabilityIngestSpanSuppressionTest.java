package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.semconv.UrlAttributes;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The proof for qits-1432: a request proxied through the edge to the OTLP ingest it fronts for
 * qits-observability — {@code /observability/api/otel/} — opens no SERVER span and no CLIENT span
 * of its own, while an ordinary request through the identical path still opens both, and the
 * suppressed request still reaches its upstream exactly as before.
 *
 * <p><b>Its own {@code @TestProfile}, on purpose.</b> {@code quarkus.otel.sdk.disabled} is read at
 * boot, and the shipped default turns it off under {@code %test} so the rest of this module's suite
 * never retries against a receiver that is not there — see {@code OtelLogConfigTest}. This class
 * turns it back on for itself alone, the way qits-events' {@code OtelLogBridgeTest} does, and reads
 * the spans off an in-memory {@code SpanExporter} CDI bean ({@link InMemorySpanExporterProducer})
 * rather than a real collector — Quarkus' {@code quarkus.otel.traces.exporter} default, {@code
 * cdi}, wires it into the SDK's pipeline with no exporter configuration of this test's own.
 *
 * <p><b>The auth gate is turned off rather than worked around.</b> {@code
 * qits.edge.auth.enforce-on-apps=false} is the one switch that opens an app vhost's every method to
 * a caller with no credential at all — see {@link EdgeAuth#open} — so this class needs no stub idp,
 * no signed token and no session cookie to prove a request actually reaches its upstream; what is
 * under test is the span, not the gate.
 *
 * <p>The upstream is a bare {@code Vert.x} {@code HttpServer} on its own loopback port, outside CDI
 * and outside this process' own Vert.x instance — it must NOT be instrumented itself, since the
 * claim under test is about the edge's own spans, not the far side's.
 */
@QuarkusTest
@TestProfile(ObservabilityIngestSpanSuppressionTest.TracingOn.class)
class ObservabilityIngestSpanSuppressionTest {

  /**
   * A flat project (no environment label), so the Host is just {@code <app>.<project>.<domain>}.
   */
  private static final String PROJECT = "probeproj";

  private static final String APPLICATION = "probe-telemetry-app";

  private static final String BROWSER_HOST = "probeapp";

  private static final String DOMAIN = "example.com";

  private static final String ENVIRONMENT = "prod";

  private static final String HOST = BROWSER_HOST + "." + PROJECT + "." + DOMAIN;

  private static final String SUPPRESSED_PATH = "/observability/api/otel/v1/traces";

  private static final String ORDINARY_PATH = "/hello";

  private static final Duration POSITIVE_WAIT = Duration.ofSeconds(20);

  public static class TracingOn implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "quarkus.otel.sdk.disabled",
          "false",
          // Batched export's own schedule delay, shortened so the positive assertion below does
          // not need to wait out the SDK's 5s default on every run; see awaitSpansFor.
          "quarkus.otel.bsp.schedule.delay",
          "100ms",
          "qits.edge.auth.enforce-on-apps",
          "false",
          "qits.edge.domain",
          DOMAIN,
          "qits.edge.environments",
          ENVIRONMENT,
          "qits.edge.default-environment",
          ENVIRONMENT);
    }
  }

  @Inject EdgeRoutes routes;

  @Inject EdgeProjects projects;

  private static Vertx upstreamVertx;

  private static HttpServer upstream;

  private static int upstreamPort;

  private static final ConcurrentLinkedQueue<String> upstreamRequests =
      new ConcurrentLinkedQueue<>();

  private static EdgeClient client;

  @BeforeAll
  static void startUpstream() throws Exception {
    upstreamVertx = Vertx.vertx();
    upstream =
        upstreamVertx
            .createHttpServer()
            .requestHandler(
                request -> {
                  upstreamRequests.add(request.path());
                  request.response().setStatusCode(200).end("upstream-ok");
                })
            .listen(0, "127.0.0.1")
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    upstreamPort = upstream.actualPort();
  }

  @AfterAll
  static void stopUpstream() throws Exception {
    if (client != null) {
      client.close();
      client = null;
    }
    if (upstream != null) {
      upstream.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
    if (upstreamVertx != null) {
      upstreamVertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
  }

  private static EdgeClient client() {
    if (client == null) {
      client = new EdgeClient(RestAssured.port);
    }
    return client;
  }

  @BeforeEach
  void publishTheProbeApplication() {
    InMemorySpanExporterProducer.EXPORTER.reset();
    upstreamRequests.clear();
    projects.apply(
        PROJECT, "p-" + PROJECT, true, false, UUID.randomUUID().toString(), Instant.now());
    routes.replace(
        ENVIRONMENT,
        APPLICATION,
        UUID.randomUUID().toString(),
        Instant.now(),
        new EdgeRoutes.Snapshot(
            List.of(
                new EdgeEndpoint(
                    ENVIRONMENT, APPLICATION, "/", new Upstream("127.0.0.1", upstreamPort))),
            BROWSER_HOST,
            null,
            List.of()));
  }

  @Test
  void theOtlpIngestGetsNoEdgeSpanWhileAnOrdinaryPathStillDoes() {
    // The suppressed request first: by the time the positive wait below succeeds, several of the
    // 100ms batch export cycles have already run, so a suppressed span — if one existed — would
    // already be sitting in the exporter.
    EdgeClient.Answer suppressed = client().get(HOST, SUPPRESSED_PATH);
    assertEquals(200, suppressed.status(), "the OTLP ingest path must still be proxied normally");
    assertTrue(
        upstreamRequests.contains(SUPPRESSED_PATH),
        "the upstream must still see the request; nothing about the span suppression may touch"
            + " the proxying itself");

    EdgeClient.Answer ordinary = client().get(HOST, ORDINARY_PATH);
    assertEquals(200, ordinary.status());
    assertTrue(upstreamRequests.contains(ORDINARY_PATH));

    List<SpanData> ordinaryServerSpans =
        awaitSpansFor(ORDINARY_PATH, SpanKind.SERVER, POSITIVE_WAIT);
    assertEquals(
        1,
        ordinaryServerSpans.size(),
        "an ordinary request must still open its own SERVER span; recorded spans: " + dump());
    List<SpanData> ordinaryClientSpans =
        awaitSpansFor(ORDINARY_PATH, SpanKind.CLIENT, POSITIVE_WAIT);
    assertEquals(
        1,
        ordinaryClientSpans.size(),
        "the edge's own upstream hop must still open its CLIENT span for an ordinary path;"
            + " recorded spans: "
            + dump());

    // By now the exporter has flushed at least once since both requests were made (the ordinary
    // request's spans just proved that), so a suppressed span would already be visible.
    assertEquals(
        0,
        spansFor(SUPPRESSED_PATH, SpanKind.SERVER).size(),
        "the OTLP ingest path must open no SERVER span: " + dump());
    assertEquals(
        0,
        spansFor(SUPPRESSED_PATH, SpanKind.CLIENT).size(),
        "the OTLP ingest path must open no CLIENT span for the edge's upstream hop either: "
            + dump());
  }

  private static List<SpanData> awaitSpansFor(String path, SpanKind kind, Duration timeout) {
    Instant deadline = Instant.now().plus(timeout);
    List<SpanData> found = spansFor(path, kind);
    while (found.isEmpty() && Instant.now().isBefore(deadline)) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        break;
      }
      found = spansFor(path, kind);
    }
    return found;
  }

  private static List<SpanData> spansFor(String path, SpanKind kind) {
    return InMemorySpanExporterProducer.EXPORTER.getFinishedSpanItems().stream()
        .filter(span -> span.getKind() == kind)
        .filter(span -> matches(span, kind, path))
        .toList();
  }

  private static boolean matches(SpanData span, SpanKind kind, String path) {
    if (kind == SpanKind.SERVER) {
      return path.equals(span.getAttributes().get(UrlAttributes.URL_PATH));
    }
    String urlFull = span.getAttributes().get(UrlAttributes.URL_FULL);
    return urlFull != null && urlFull.endsWith(path);
  }

  private static String dump() {
    return InMemorySpanExporterProducer.EXPORTER.getFinishedSpanItems().stream()
        .map(
            span ->
                span.getKind()
                    + " "
                    + span.getName()
                    + " url.path="
                    + span.getAttributes().get(UrlAttributes.URL_PATH)
                    + " url.full="
                    + span.getAttributes().get(UrlAttributes.URL_FULL))
        .toList()
        .toString();
  }
}
