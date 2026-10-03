package eu.wohlben.qits.edge;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * CORS on every service host, owned by the edge and by nothing behind it.
 *
 * <p><b>Why it exists.</b> A hostname alone picks the application (epic qits-528): a service host
 * routes only its own application's paths, so an SPA on {@code ci.<env>.<project>.<domain>} reads
 * another application's API on that application's own name, {@code
 * projects.<env>.<project>.<domain>} — cross-origin, with the session cookie — and the browser
 * needs CORS to let it.
 *
 * <p><b>The rule is the domain anchor and nothing narrower.</b> An {@code Origin} under the stated
 * domain, at any depth, or the apex itself — {@link EdgeSessions#admitsOrigin}, the same anchor the
 * login return is checked against — is echoed with credentials allowed. The owner's ruling is that
 * it is not narrowed to the hosts the projection publishes: every name under the domain is this
 * platform's own, and a list kept in step with the projection would be one more thing to drift. A
 * foreign origin gets no {@code Access-Control-Allow-Origin} at all, so the browser refuses it as
 * it would with no edge in front.
 *
 * <p><b>The edge owns every {@code Access-Control-*} header</b>, so whatever an upstream sends
 * under that prefix is removed before the answer leaves, on every response — proxied, the edge's
 * own 401s and challenges, an SSE stream (a plain proxied GET whose head is written before its
 * first chunk) and a WebSocket handshake's 101. One headers-end hook on the routing context covers
 * them all, which is why this is not a proxy interceptor: an interceptor never sees the edge's own
 * answers or the upgrade path. A WebSocket needs no CORS — the browser does not enforce it on one —
 * so the hook there only keeps an upstream from speaking for the edge.
 *
 * <p><b>A preflight is answered here, before the gate.</b> A browser never sends credentials on an
 * {@code OPTIONS} preflight, so a gated host would answer it 401 and the real request would never
 * be made. It is answered 204 from this process with the requested method and headers echoed, and
 * it touches no session: nothing is introspected and nothing is set. A preflight from a foreign
 * origin is not answered here and meets the gate like any other request.
 *
 * <p>{@code Vary: Origin} goes on every response of a service host, admitted or not: the answer
 * differs by {@code Origin}, so a cache that keyed a response without it would hand one origin's
 * answer to another.
 */
@ApplicationScoped
public class EdgeCors {

  static final String PREFIX = "Access-Control-";

  static final String ALLOW_ORIGIN = "Access-Control-Allow-Origin";
  static final String ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials";
  static final String ALLOW_METHODS = "Access-Control-Allow-Methods";
  static final String ALLOW_HEADERS = "Access-Control-Allow-Headers";
  static final String EXPOSE_HEADERS = "Access-Control-Expose-Headers";
  static final String MAX_AGE = "Access-Control-Max-Age";
  static final String REQUEST_METHOD = "Access-Control-Request-Method";
  static final String REQUEST_HEADERS = "Access-Control-Request-Headers";

  /**
   * What a cross-origin script may read beyond the CORS-safelisted response headers: where a create
   * answered, the cache validator, a retry hint, a download's name, and paging links.
   */
  static final String EXPOSED = "Location, ETag, Retry-After, Content-Disposition, Link";

  /**
   * Ten minutes: long enough to spare an SPA a preflight per call, short enough to follow a fix.
   */
  static final String PREFLIGHT_MAX_AGE = "600";

  @Inject EdgeSessions sessions;

  /**
   * Install the rule on one service host's exchange, and answer it when it is an admitted
   * preflight.
   *
   * @return true when this answered the request, which is then over
   */
  boolean handle(RoutingContext rc) {
    HttpServerRequest request = rc.request();
    String origin = request.getHeader(HttpHeaders.ORIGIN);
    if (!sessions.admitsOrigin(origin)) {
      rc.addHeadersEndHandler(ignored -> apply(rc.response().headers(), null, null));
      return false;
    }
    Preflight preflight =
        isPreflight(request)
            ? new Preflight(request.getHeader(REQUEST_METHOD), request.getHeader(REQUEST_HEADERS))
            : null;
    rc.addHeadersEndHandler(ignored -> apply(rc.response().headers(), origin, preflight));
    if (preflight == null) {
      return false;
    }
    // Everything CORS on this answer is written by apply() at headers-end, the same place as on
    // every other answer — so a preflight and the real request it clears cannot disagree.
    rc.response().setStatusCode(204).end();
    return true;
  }

  /** What a preflight asked for, which its answer echoes. */
  record Preflight(String method, String headers) {}

  /** {@code OPTIONS} carrying an {@code Origin} and the method it asks for. */
  static boolean isPreflight(HttpServerRequest request) {
    return request.method() == HttpMethod.OPTIONS
        && request.getHeader(HttpHeaders.ORIGIN) != null
        && request.getHeader(REQUEST_METHOD) != null;
  }

  /**
   * The rule, on one response's headers: every {@code Access-Control-*} header an upstream sent is
   * dropped, then the edge's own are written. Package-private and static so it can be asserted
   * without a socket.
   *
   * @param origin the admitted origin to echo, or null for a foreign or absent one
   * @param preflight the preflight this process is answering, or null for every other response
   */
  static void apply(MultiMap headers, String origin, Preflight preflight) {
    for (String name : List.copyOf(headers.names())) {
      if (name.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
        headers.remove(name);
      }
    }
    vary(headers, "Origin");
    if (origin == null) {
      return;
    }
    headers.set(ALLOW_ORIGIN, origin);
    headers.set(ALLOW_CREDENTIALS, "true");
    if (preflight == null) {
      headers.set(EXPOSE_HEADERS, EXPOSED);
      return;
    }
    headers.set(ALLOW_METHODS, preflight.method());
    if (preflight.headers() != null && !preflight.headers().isBlank()) {
      headers.set(ALLOW_HEADERS, preflight.headers());
    }
    headers.set(MAX_AGE, PREFLIGHT_MAX_AGE);
    vary(headers, REQUEST_METHOD);
    vary(headers, REQUEST_HEADERS);
  }

  /** One name added to whatever {@code Vary} the response already carries, once. */
  private static void vary(MultiMap headers, String wanted) {
    for (String value : headers.getAll(HttpHeaders.VARY)) {
      for (String token : value.split(",")) {
        String name = token.strip();
        if (name.equals("*") || name.equalsIgnoreCase(wanted)) {
          return;
        }
      }
    }
    headers.add(HttpHeaders.VARY, wanted);
  }
}
