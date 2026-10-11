package eu.wohlben.qits.edge;

import io.opentelemetry.api.trace.Span;
import io.quarkus.opentelemetry.runtime.QuarkusContextStorage;
import io.vertx.core.Context;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.impl.HttpServerRequestInternal;

/**
 * The edge's own SERVER span for one inbound request, read off the request's OWN Vert.x context
 * rather than off whichever context happens to be current.
 *
 * <p><b>Why not {@code Span.current()}.</b> Quarkus' tracer starts the server span in {@code
 * receiveRequest} and attaches it to the duplicated context Vert.x created for this request — and
 * that context is the only place it is certainly found. The code that runs after the gate does not
 * always run on it: a session introspection or a credential check that completed a future created
 * elsewhere — a cache entry shared between requests — resumes on that future's context, and there
 * {@code Span.current()} answers some OTHER request's span. A trace id read from it sends a reader
 * to an unrelated exchange; an attribute written onto it describes this request on somebody else's.
 * Both are worse than nothing, which is what this answers instead when the request's own span
 * cannot be reached.
 *
 * <p>Reaching it takes two pieces of internal API, Vert.x's {@code
 * HttpServerRequestInternal.context()} and Quarkus' {@code QuarkusContextStorage.getOtelContext},
 * and this class is the one place either is touched — {@link UpstreamLease#traceId} reads the trace
 * id through it, {@code EdgeRouter} records {@code qits.caller} through it.
 */
final class RequestSpans {

  private RequestSpans() {}

  /**
   * The span Quarkus attached to this request's own context, or null when there is none to be
   * certain of: not a Vert.x-internal request, no OTel context on it (the SDK switched off, as it
   * is in tests), an invalid span, or an internal API a Vert.x or Quarkus upgrade moved. Never
   * throws: every caller is bookkeeping, and a request must not fail over it.
   */
  static Span of(HttpServerRequest request) {
    try {
      if (!(request instanceof HttpServerRequestInternal internal)) {
        return null;
      }
      Context context = internal.context();
      io.opentelemetry.context.Context otel =
          context == null ? null : QuarkusContextStorage.getOtelContext(context);
      if (otel == null) {
        return null;
      }
      Span span = Span.fromContext(otel);
      return span.getSpanContext().isValid() ? span : null;
    } catch (RuntimeException | LinkageError moved) {
      return null;
    }
  }
}
