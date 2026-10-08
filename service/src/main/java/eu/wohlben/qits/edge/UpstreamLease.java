package eu.wohlben.qits.edge;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.quarkus.opentelemetry.runtime.QuarkusContextStorage;
import io.vertx.core.Context;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.impl.HttpServerRequestInternal;
import io.vertx.core.net.SocketAddress;
import java.util.Locale;

/**
 * Who is holding an upstream connection: the inbound exchange a pooled connection is carrying right
 * now, read off the request before the connection is asked for.
 *
 * <p><b>Why it exists.</b> A full pool used to be a number. {@code 64/64} said the pool to
 * qits-projects was exhausted and nothing said by WHAT — and what it was turned out to be dozens of
 * agents' MCP event streams, each one a GET that holds its connection for as long as the agent
 * lives, which no count could have told apart from a burst of slow pushes. A lease names the
 * holder: the method and path, how long it has held the slot, who asked, with what, for which
 * workspace, and the trace to look the whole exchange up by. {@link UpstreamPools} keeps one per
 * leased connection and drops it when the exchange completes; {@code /upstream-pools} lists them
 * and the pool WARN quotes the oldest.
 *
 * <p><b>Everything here is diagnostics, and nothing here is a credential.</b> {@code client} is the
 * first {@code X-Forwarded-For} entry when the request carried one — which a direct client can
 * supply itself, exactly as {@link EdgeHeaders} says of the header — and the socket's own peer
 * otherwise. The path is read WITHOUT its query string, because a query is where a token in a URL
 * would be; {@code workspaceId} is the one query parameter lifted out, because it is how an agent's
 * stream says whose it is. No header beyond {@code User-Agent} is kept.
 *
 * @param method the inbound method, e.g. {@code GET}
 * @param path the inbound path, without its query string
 * @param client the first {@code X-Forwarded-For} entry, else the peer's host; null when neither
 * @param userAgent the inbound {@code User-Agent}, or null
 * @param workspaceId the inbound {@code workspaceId} query parameter, or null
 * @param traceId the trace this exchange belongs to — see {@link #traceId} — or null
 */
record UpstreamLease(
    String method,
    String path,
    String client,
    String userAgent,
    String workspaceId,
    String traceId) {

  /** The one query parameter a lease keeps: whose workspace an agent's stream is for. */
  static final String WORKSPACE_PARAMETER = "workspaceId";

  /**
   * The lease for one inbound request. Never throws: it runs inside the origin provider, and a
   * request must not fail over bookkeeping — a part that cannot be read is null instead.
   */
  static UpstreamLease of(HttpServerRequest request) {
    try {
      return new UpstreamLease(
          request.method() == null ? null : request.method().name(),
          request.path(),
          client(request),
          request.getHeader(HttpHeaders.USER_AGENT),
          workspaceId(request),
          traceId(request));
    } catch (RuntimeException unreadable) {
      return new UpstreamLease(null, null, null, null, null, null);
    }
  }

  /** The first {@code X-Forwarded-For} hop, else the socket's peer. Diagnostics only. */
  static String client(HttpServerRequest request) {
    String forwarded = request.getHeader(EdgeHeaders.FOR);
    if (forwarded != null) {
      int comma = forwarded.indexOf(',');
      String first = (comma < 0 ? forwarded : forwarded.substring(0, comma)).strip();
      if (!first.isEmpty()) {
        return first;
      }
    }
    SocketAddress peer = request.remoteAddress();
    return peer == null ? null : peer.host();
  }

  private static String workspaceId(HttpServerRequest request) {
    try {
      String value = request.getParam(WORKSPACE_PARAMETER);
      return value == null || value.isBlank() ? null : value;
    } catch (RuntimeException malformedQuery) {
      // A query Netty cannot decode is the upstream's to refuse, not the lease's.
      return null;
    }
  }

  /**
   * The trace id of the exchange this request is, from the first of three sources that has one.
   *
   * <p><b>1. The OTel context Quarkus attached to the request's OWN Vert.x context.</b> Quarkus'
   * tracer starts the SERVER span in {@code receiveRequest} and attaches it to the duplicated
   * context Vert.x created for this request — and that context, not whichever one happens to be
   * current, is the only place it is certainly found. {@code Span.current()} reads the CURRENT
   * context, and the origin provider does not always run on the request's own: it runs after the
   * gate, and a session introspection or a credential check that completed a future created
   * elsewhere — a cache entry shared between requests — resumes on that future's context. There
   * {@code Span.current()} answers some other request's span, and a wrong trace id is worse than
   * none: it sends whoever reads it to an unrelated exchange. The request's own context cannot be
   * wrong that way. Reaching it takes two pieces of internal API, Vert.x's {@code
   * HttpServerRequestInternal.context()} and Quarkus' {@code QuarkusContextStorage.getOtelContext},
   * both kept to this method.
   *
   * <p><b>2. {@code Span.current()}</b>, for a request that is not a Vert.x-internal one or a
   * Quarkus whose storage moved — valid only when its span context is.
   *
   * <p><b>3. The inbound {@code traceparent}</b>, when no span exists at all — the SDK switched
   * off, as it is in tests. With the SDK on, the server span CONTINUES that trace, so its id is the
   * same one; this is not a second reading that can disagree with the first, only a later one.
   *
   * @return 32 lower-case hex digits, or null when none of the three has a valid one
   */
  static String traceId(HttpServerRequest request) {
    String attached = attachedTraceId(request);
    if (attached != null) {
      return attached;
    }
    try {
      SpanContext current = Span.current().getSpanContext();
      if (current.isValid()) {
        return current.getTraceId();
      }
    } catch (RuntimeException | LinkageError noTracing) {
      // Diagnostics only; fall through to the header.
    }
    return traceparentTraceId(request.getHeader("traceparent"));
  }

  private static String attachedTraceId(HttpServerRequest request) {
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
      SpanContext span = Span.fromContext(otel).getSpanContext();
      return span.isValid() ? span.getTraceId() : null;
    } catch (RuntimeException | LinkageError moved) {
      // Internal API that a Vert.x or Quarkus upgrade may move; Span.current() is the fallback.
      return null;
    }
  }

  /**
   * The trace id of a W3C {@code traceparent}, {@code 00-<32 hex>-<16 hex>-<2 hex>}, or null for
   * anything that is not one — including the all-zero id the spec calls invalid.
   */
  static String traceparentTraceId(String traceparent) {
    if (traceparent == null) {
      return null;
    }
    String[] parts = traceparent.strip().toLowerCase(Locale.ROOT).split("-");
    if (parts.length < 4 || parts[0].length() != 2 || parts[1].length() != 32) {
      return null;
    }
    String traceId = parts[1];
    for (int i = 0; i < traceId.length(); i++) {
      char c = traceId.charAt(i);
      if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) {
        return null;
      }
    }
    return traceId.equals("0".repeat(32)) ? null : traceId;
  }
}
