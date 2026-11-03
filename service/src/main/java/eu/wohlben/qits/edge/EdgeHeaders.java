package eu.wohlben.qits.edge;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.SocketAddress;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import io.vertx.httpproxy.ProxyRequest;
import io.vertx.httpproxy.ProxyResponse;
import java.util.List;

/**
 * Everything the edge does to a request's headers, on every path out of this process.
 *
 * <p><b>1. It keeps the client's Host.</b> {@code vertx-http-proxy} leaves a proxied request's
 * authority unset, and the Vert.x client then fills the {@code Host} header in from the socket it
 * opened — so without this an upstream would see its own socket address ({@code qits-ci:8080}) for
 * every request and never learn the name the browser typed. That name is what any redirect, cookie
 * domain or absolute URL it generates has to be built from, so losing it breaks all three at once
 * and leaves nothing in a log to say why. Measured on Vert.x 4.5.26 against a real upstream, not
 * assumed: {@code EdgeRoutingTest.theOriginalHostReachesTheUpstream} is the regression.
 *
 * <p><b>2. It describes the original client</b> with three headers, each set <b>only when
 * absent</b>: {@code X-Forwarded-For}, {@code X-Forwarded-Host} and {@code X-Forwarded-Proto}.
 *
 * <p>Set-if-absent, not set. The edge is not always the outermost hop: a TLS terminator may sit in
 * front of it, and that terminator is the only hop that can tell the truth about {@code https}.
 * Overwriting there would replace a true value with a false one. What it costs is that a direct
 * client can supply its own — which is why nothing may make a trust decision on these three, and
 * nothing does: they are diagnostics and link generation. What authentication the edge does make a
 * decision on is the bearer token, which is signed — see {@code EdgeAuth}.
 *
 * <p><b>3. It keeps the {@code X-Qits-*} namespace honest</b> on <b>every</b> request that leaves
 * this process — see {@link #applyIdentity}, called unconditionally from {@code EdgeRouter.proxy}.
 * Every inbound header under the prefix is dropped, session or none, credential or none. <b>The
 * edge asserts no identity</b> (qits-1373): a service authenticates the bearer it is sent and reads
 * nobody from a header, and a browser session is the edge's gate on navigations, not an identity it
 * forwards. The strip stays anyway, because it is cheap and because it keeps the namespace meaning
 * nothing a client wrote: a future app that trusted it would otherwise inherit forgeries. What is
 * written back after the strip is attribution only — the calling application, {@link #CALLER},
 * whenever the page the request came from is one of the platform's own; and, for a request carrying
 * an accepted {@code qits:system} JWT, the attribution its service stamped on it, {@link
 * #SERVICE_ATTRIBUTION}.
 *
 * <p><b>Nothing else is touched.</b> No header outside the reserved prefix is stripped, no path is
 * rewritten, no body is read. {@code Authorization} and every custom header pass through as they
 * arrived. The one addition is the named browser-session cookie on a session-less path; {@link
 * #stripCookie} removes only that pair and preserves the rest.
 *
 * <p>{@code ProxyInterceptor} has no single abstract method, so this is a class rather than a
 * lambda. Nothing about it is environment-specific, so one instance is shared by every proxy.
 */
final class EdgeHeaders implements ProxyInterceptor {

  static final String FOR = "X-Forwarded-For";
  static final String HOST = "X-Forwarded-Host";
  static final String PROTO = "X-Forwarded-Proto";

  /**
   * The platform's reserved header namespace: what a hop STATES about a request, never what a
   * client wrote. The strip rule is the same prefix, which is the whole point — an enumerated
   * list's failure mode is adding a header somebody believes and forgetting to extend it, a silent,
   * additive mistake no test naturally catches.
   *
   * <p>It used to carry an identity, {@code X-Qits-User}, {@code X-Qits-User-Id} and {@code
   * X-Qits-Roles}, which this process wrote from a browser session for forward-auth services to
   * believe. It writes none of them any more (qits-1373): every service authenticates a bearer
   * itself. A client that still sends them has them stripped like any other reserved name.
   */
  static final String RESERVED_PREFIX = "X-Qits-";

  /**
   * The APPLICATION a request comes from — its deployed identity, the same value as its OTel {@code
   * service.name}: {@code qits-landing}, {@code qits-ci}. A receiving service records it as the
   * span attribute {@code qits.caller}, so a trace says who asked as well as who answered.
   *
   * <p>A service stamps it itself on its own outbound calls. A BROWSER cannot, and must not be able
   * to: the strip removes whatever a client sent under this name like every other reserved one, and
   * what the edge writes back is derived from the page the request came from — its {@code Origin},
   * or the origin of its {@code Referer} where it sent none — and only when that page is on one of
   * this platform's own names and one application serves it. See {@code EdgeRouter.caller}. It is
   * not a credential: an {@code Origin} is a browser's statement, and a non-browser client can
   * write one, so nothing may authorise on it. It is attribution, and asserting it here is what
   * keeps a client from attributing its traffic to somebody else through the header itself.
   */
  static final String CALLER = RESERVED_PREFIX + "Caller";

  /** The event or request a service's call was caused by — set by the calling service itself. */
  static final String CAUSATION_ID = RESERVED_PREFIX + "Causation-Id";

  /** The environment a service's call is made in — set by the calling service itself. */
  static final String ENVIRONMENT = RESERVED_PREFIX + "Environment";

  /**
   * The attribution a SERVICE states about its own call, kept as sent when — and only when — the
   * request carries an accepted {@code qits:system} JWT of its own (qits-1403). Every service
   * stamps these on its outbound calls, and once those calls reach a peer through this process
   * rather than on qits-net the strip would erase them: a trace would lose who asked and why. A
   * service bearer is a credential idp minted for a platform service, checked here in full, so its
   * statement about itself is as good as the one it made on qits-net. No identity header is ever in
   * this list: a machine's identity is its token, and the receiving service reads it there.
   */
  static final List<String> SERVICE_ATTRIBUTION = List.of(CAUSATION_ID, CALLER, ENVIRONMENT);

  /**
   * Whether a header name belongs to the reserved namespace. Case-insensitive: header names are,
   * and a client sending {@code x-qits-caller} must be treated exactly like one sending {@code
   * X-Qits-Caller}.
   */
  static boolean isReserved(String name) {
    return name != null
        && name.length() > RESERVED_PREFIX.length()
        && name.regionMatches(true, 0, RESERVED_PREFIX, 0, RESERVED_PREFIX.length());
  }

  /**
   * Drop every inbound {@code X-Qits-*} header, then write back the attribution this request is
   * owed — and never an identity.
   *
   * <p><b>Both halves stay in this one method, and that is the rule rather than a tidiness.</b> The
   * forged header and the stated one have the same name, so the code that writes the stated value
   * has to be downstream of the code that removes the forged one; splitting them puts the property
   * in the hands of a call order somewhere else.
   *
   * <p><b>Called on the INBOUND request's map</b>, by {@link EdgeRouter} at the single point where
   * a request leaves this process for an upstream. That is what covers the two paths at once: an
   * ordinary proxied request copies these headers, and a WebSocket upgrade — the edge's own path,
   * {@code EdgeWebSocketUpgrade}, which never installs the interceptor chain, so {@link
   * #handleProxyRequest} never runs for it — is forwarded from this same map.
   *
   * <p><b>A browser session asserts nothing here</b> (qits-1373). It decides whether a navigation
   * reaches a service at all — see {@code EdgeSessions} — and that is all it decides: who the
   * person is, a service learns from the bearer the page sends it.
   *
   * @param caller the application the request's page belongs to, asserted as {@link #CALLER}, or
   *     null to assert none — a machine's request, a foreign page, a name no application serves
   */
  static void applyIdentity(MultiMap headers, String caller) {
    applyIdentity(headers, caller, false);
  }

  /**
   * {@link #applyIdentity(MultiMap, String)}, for a request that may carry a service bearer.
   *
   * <p><b>A service bearer keeps its own attribution</b>: with {@code serviceBearer} true, the
   * {@link #SERVICE_ATTRIBUTION} headers it sent are written back after the strip exactly as they
   * arrived, and the page's caller is asserted only when it sent no {@link #CALLER} of its own.
   * Everything else in the namespace goes. With it false: strip all of it, assert the page's
   * caller.
   *
   * @param serviceBearer whether the request carried a JWT this process accepted and whose roles
   *     include {@code qits:system} — {@link EdgeAuth#isServiceBearer}. Never true for a browser
   *     session, an anonymous request, a client secret or an opaque {@code qits_tok_} token
   */
  static void applyIdentity(MultiMap headers, String caller, boolean serviceBearer) {
    // The service's own statement, read before the strip and only for a service bearer.
    java.util.Map<String, List<String>> kept = new java.util.LinkedHashMap<>();
    if (serviceBearer) {
      for (String name : SERVICE_ATTRIBUTION) {
        List<String> values = headers.getAll(name);
        if (!values.isEmpty()) {
          kept.put(name, List.copyOf(values));
        }
      }
    }
    // Snapshot the names first: removing from the map while iterating its own name view would
    // otherwise skip entries.
    for (String name : List.copyOf(headers.names())) {
      if (isReserved(name)) {
        headers.remove(name);
      }
    }
    // Only now, with the namespace provably empty, is it safe to write into it.
    kept.forEach(headers::set);
    if (caller != null && !headers.contains(CALLER)) {
      headers.set(CALLER, caller);
    }
  }

  /**
   * The origin of the page a request comes from: its {@code Origin} header as sent, or — only when
   * there is none — the {@code scheme://authority} of its {@code Referer}.
   *
   * <p><b>The fallback exists because a browser does not always send {@code Origin}</b>: a
   * same-origin GET or HEAD carries none, and that is most of what a page fetches from its own
   * service. Its {@code Referer} names the page, and the page's origin is all that is read off it —
   * path, query and fragment are dropped, and a URL carrying user-info is not one a browser sends
   * and is refused. A present {@code Origin} is final, {@code null} included: that is a browser
   * deliberately hiding the page, and reading the {@code Referer} instead would be second-guessing
   * it.
   *
   * <p>Nothing here decides whether the origin is a trusted one — that is {@code
   * EdgeSessions.admitsOrigin}, and then whether an application serves its name.
   *
   * @return the origin to attribute the request to, or null when the request names no page
   */
  static String callerOrigin(String origin, String referer) {
    if (origin != null && !origin.isBlank()) {
      return origin.strip();
    }
    if (referer == null || referer.isBlank()) {
      return null;
    }
    try {
      java.net.URI page = new java.net.URI(referer.strip());
      if (page.getScheme() == null
          || page.getRawAuthority() == null
          || page.getRawUserInfo() != null) {
        return null;
      }
      return page.getScheme().toLowerCase(java.util.Locale.ROOT) + "://" + page.getRawAuthority();
    } catch (java.net.URISyntaxException unreadable) {
      return null;
    }
  }

  /**
   * Remove one named cookie while preserving every other pair as the browser sent it.
   *
   * <p>A parent-domain cookie is necessarily offered to sibling hosts. Registry, mirror, and git
   * host do not consume a person session, so forwarding it would turn a browser credential into a
   * service-visible bearer. Rebuilding only the Cookie header preserves unrelated application
   * cookies and keeps the edge's header policy narrow.
   */
  static void stripCookie(MultiMap headers, String name) {
    String raw = headers.get("Cookie");
    if (raw == null || name == null || name.isBlank()) {
      return;
    }
    java.util.ArrayList<String> kept = new java.util.ArrayList<>();
    for (String pair : raw.split(";")) {
      int equals = pair.indexOf('=');
      if (equals > 0 && pair.substring(0, equals).strip().equals(name)) {
        continue;
      }
      if (!pair.isBlank()) {
        kept.add(pair.strip());
      }
    }
    if (kept.isEmpty()) {
      headers.remove("Cookie");
    } else {
      headers.set("Cookie", String.join("; ", kept));
    }
  }

  @Override
  public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
    ProxyRequest request = context.request();
    HttpServerRequest inbound = request.proxiedRequest();
    // Job 1. The socket still goes to the configured origin — this is only what the request SAYS.
    if (inbound.authority() != null) {
      request.setAuthority(inbound.authority());
    }
    applyForwarded(request.headers(), inbound);
    return context.sendRequest();
  }

  /**
   * Job 2 on a request's own header map.
   *
   * <p>Called directly by {@link EdgeRouter} for a WebSocket handshake, which never reaches the
   * interceptor chain: an upgrade goes through {@code EdgeWebSocketUpgrade}, the edge's own path,
   * which forwards the inbound request's headers as this method leaves them. Without this call an
   * upgraded connection — every interactive terminal on the platform — would arrive at the upstream
   * service with no record of who opened it.
   *
   * <p><b>Job 1 has no equivalent on that path</b>, and the gap is deliberate: the upgrade path
   * rebuilds the handshake with the client's own {@code Host} dropped, exactly as {@code
   * vertx-http-proxy}'s did before it. So an upstream reads a socket's original host name from
   * {@code X-Forwarded-Host}, not from {@code Host}. It costs nothing today because a handshake's
   * Host is a protocol formality rather than something an upstream service routes on.
   */
  static void applyForwarded(MultiMap headers, HttpServerRequest inbound) {
    SocketAddress remote = inbound.remoteAddress();
    if (remote != null && remote.hostAddress() != null && !headers.contains(FOR)) {
      headers.set(FOR, remote.hostAddress());
    }
    HostAndPort authority = inbound.authority();
    if (authority != null && !headers.contains(HOST)) {
      // The authority as the client wrote it, port included when it named one — a link built from a
      // name whose port was dropped points at a port nothing listens on.
      headers.set(
          HOST,
          authority.port() > 0 ? authority.host() + ":" + authority.port() : authority.host());
    }
    if (!headers.contains(PROTO)) {
      headers.set(PROTO, inbound.scheme() == null ? "http" : inbound.scheme());
    }
  }
}
