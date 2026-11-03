package eu.wohlben.qits.edge;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import io.opentelemetry.semconv.UrlAttributes;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * Drops the edge's own CLIENT span for the one upstream hop {@code
 * quarkus.otel.traces.suppress-application-uris} cannot reach (qits-1432).
 *
 * <p><b>The other half of the fix, not a replacement for it.</b> That config key stops this
 * process' SERVER span for a request under {@code /observability/api/otel/} — the OTLP ingest of
 * qits-observability, which every service's telemetry now leaves through this edge on its way to.
 * Without it, each export batch is itself a proxied request that gets its own edge span, which is
 * exported in the next batch, forever. But the edge's own reverse-proxy hop to the upstream — the
 * {@code HttpClient} {@link EdgeRouter#proxyClient} builds on the one CDI {@link
 * io.vertx.core.Vertx} this whole process shares — is instrumented by the identical Quarkus Vert.x
 * tracer that instruments the inbound side, so it opens a CLIENT span for that same request.
 * Quarkus' {@code DropTargetsSampler} (the class the config key feeds) checks {@code
 * SpanKind.SERVER} before it ever reads that list, so a CLIENT span falls straight through to
 * whatever sampler is otherwise in force — the suppression never reaches it. Nor would the same
 * list reach it if it did: a CLIENT span carries {@code url.full}, never {@code url.path}/{@code
 * url.query} (SERVER-only semantics in the shared HTTP attribute extractors), so the config key's
 * match on path is a different attribute than this hop has.
 *
 * <p><b>Why a CDI {@code Sampler} bean, and why it still delegates.</b> Quarkus discovers the first
 * CDI bean of this type and uses it in place of whatever {@code quarkus.otel.traces.sampler} would
 * otherwise configure — then wraps THAT in {@code DropTargetsSampler} for {@code
 * suppress-application-uris}, so registering this bean does not opt out of the config key; both
 * apply together, each to the span kind it can see. This service sets no {@code
 * quarkus.otel.traces.sampler}, so the SDK's own default, {@link Sampler#parentBased} over {@link
 * Sampler#alwaysOn}, is what every other span — CLIENT or SERVER, any path but this one — must
 * still get, which is why it is the fallback here rather than something invented for this class.
 *
 * <p><b>Dropping is not refusing.</b> A dropped span still lets its trace id propagate to whatever
 * is downstream of it (see {@link UpstreamLease#traceId}); it is simply never recorded or exported.
 * The request itself is untouched — this never runs anywhere near the proxy's own routing or header
 * handling, only inside the OTel SDK's sampling decision.
 */
@ApplicationScoped
final class ObservabilityIngestSampler implements Sampler {

  /** Must read as the config key's own wildcard entry, stripped of its trailing {@code *}. */
  static final String SUPPRESSED_PREFIX = "/observability/api/otel/";

  private final Sampler delegate = Sampler.parentBased(Sampler.alwaysOn());

  @Override
  public SamplingResult shouldSample(
      Context parentContext,
      String traceId,
      String name,
      SpanKind spanKind,
      Attributes attributes,
      List<LinkData> parentLinks) {
    if (spanKind == SpanKind.CLIENT && suppressed(attributes)) {
      return SamplingResult.drop();
    }
    return delegate.shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
  }

  @Override
  public String getDescription() {
    return "ObservabilityIngestSampler{" + delegate.getDescription() + "}";
  }

  private static boolean suppressed(Attributes attributes) {
    String path = path(attributes.get(UrlAttributes.URL_FULL));
    return path != null && path.startsWith(SUPPRESSED_PREFIX);
  }

  /** {@code null} for anything this hop's own URL never is: absent, or not a URI at all. */
  private static String path(String urlFull) {
    if (urlFull == null) {
      return null;
    }
    try {
      return new URI(urlFull).getRawPath();
    } catch (URISyntaxException malformed) {
      return null;
    }
  }
}
