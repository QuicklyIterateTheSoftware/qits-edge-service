package eu.wohlben.qits.edge;

import io.vertx.core.Future;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * {@code GET /upstream-pools}: how full each of the edge's two proxy pools is to each upstream
 * origin, and what is holding the connections, on every vhost, answered by the edge itself and
 * never proxied — the same shape of route as {@link NavigationRoute}.
 *
 * <p>The document is an array with one entry per pool and origin holding at least one open
 * connection, fullest first:
 *
 * <pre>{@code
 * [{"name":"qits-projects","environment":"dev","origin":"dev-qits-projects:8080","pool":"stream",
 *   "open":12,"max":256,
 *   "held":[{"method":"GET","path":"/projects/mcp","ageMs":3605211,"client":"10.0.0.7",
 *            "userAgent":"claude-code/2.1","workspaceId":"ws-1","traceId":"4bf92f35..."}]}]
 * }</pre>
 *
 * {@code name} and {@code environment} are whose the origin is — see {@link EdgeRouter#owner},
 * which names an origin no deployment claims any more by its wire alias, and by its bare host with
 * a null environment when even that does not read. {@code pool} is {@code ordinary} or {@code
 * stream} — see {@link UpstreamPools} for why there are two — and {@code max} is THAT pool's
 * configured size, read from its options rather than written here, so the two cannot disagree.
 *
 * <p>{@code held} is one entry per leased connection, oldest first: the exchange it is carrying now
 * — see {@link UpstreamLease} — and {@code ageMs}, how long it has held it. {@code open} minus the
 * length of {@code held} is the origin's idle connections. Every field of a held entry but {@code
 * method}, {@code path} and {@code ageMs} may be null; they are written as null rather than left
 * out, so a reader iterates one shape. A path is listed without its query string.
 *
 * <p><b>Only a caller the edge would let through to a service is answered.</b> The document names
 * every upstream on the estate and how loaded it is, which is not a thing to hand an anonymous
 * caller. A valid machine credential — checked by {@link EdgeAuth#checkCredential} exactly as on a
 * service vhost, demanding the platform audience because the answer is estate-wide rather than one
 * service's — or, with the browser gate on, a live {@code qits-session} cookie introspected by
 * {@link EdgeSessions}. Anything else is a 401; an idp that cannot vouch for an opaque token is the
 * same 503 the service gate answers, because a check that cannot be made is not a verdict.
 */
@ApplicationScoped
public class UpstreamPoolsRoute {

  static final String PATH = "/upstream-pools";

  private static final Logger LOG = Logger.getLogger(UpstreamPoolsRoute.class);

  @Inject EdgeRouter edgeRouter;

  @Inject EdgeAuth auth;

  @Inject EdgeSessions sessions;

  void register(@Observes Router router) {
    router
        .route(PATH)
        .method(HttpMethod.GET)
        .method(HttpMethod.HEAD)
        // Ahead of EdgeRouter.ROUTE_ORDER, like /main-navigation: answered here, never proxied.
        .order(100)
        .handler(this::handle);
  }

  private void handle(RoutingContext context) {
    HttpServerRequest request = context.request();
    // No pause while the caller is checked: a GET or a HEAD has no body to hold back.
    admitted(request)
        .onSuccess(
            yes -> {
              if (yes) {
                answer(request);
              } else {
                refuse(request);
              }
            })
        .onFailure(
            failure -> {
              if (failure instanceof EdgeAuth.IdpUnreachable) {
                LOG.warnf("%s; %s answered 503", failure.getMessage(), PATH);
                auth.unavailable(request);
                return;
              }
              LOG.errorf(failure, "could not check the caller of %s", PATH);
              refuse(request);
            });
  }

  /**
   * Whether this caller would be let through to a service: a machine credential first, as on a
   * service vhost, then — only while the browser gate is on — the session cookie.
   */
  private Future<Boolean> admitted(HttpServerRequest request) {
    if (EdgeAuth.carriesCredential(request)) {
      // The apex reading demands the platform audience alone; see the class comment.
      return auth.checkCredential(
              HostEnvironments.Route.apex(edgeRouter.defaultEnvironment()), request)
          .map(rejection -> rejection == null);
    }
    String cookie = sessions.enabled() ? sessions.cookie(request) : null;
    if (cookie == null) {
      return Future.succeededFuture(false);
    }
    return sessions.introspect(cookie).map(session -> session != null);
  }

  private static void refuse(HttpServerRequest request) {
    request
        .response()
        .setStatusCode(401)
        // Bearer, not Basic: a Basic challenge would make a browser's background fetch open a
        // credential dialog, and the landing page reads this document on every menu open.
        .putHeader("WWW-Authenticate", "Bearer")
        .putHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        .putHeader(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
        .end("The upstream pools are answered to a caller with a session or a bearer token.\n");
  }

  private void answer(HttpServerRequest request) {
    List<UpstreamPools.Snapshot> snapshots = new ArrayList<>();
    for (UpstreamPools pools : edgeRouter.pools()) {
      snapshots.addAll(pools.snapshot());
    }
    request
        .response()
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
        .putHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        .end(document(snapshots, edgeRouter::owner).encode());
  }

  /**
   * The document, from both pools' snapshots and an owner lookup. Static so its shape and order are
   * asserted without a boot.
   *
   * <p>Sorted by {@code open}, fullest first, then by origin and then by pool, so two equal entries
   * keep one order between two reads. The leases keep the order the snapshot gave them, oldest
   * first.
   */
  static JsonArray document(
      List<UpstreamPools.Snapshot> snapshots,
      java.util.function.Function<Upstream, UpstreamPools.Owner> owner) {
    List<UpstreamPools.Snapshot> entries = new ArrayList<>(snapshots);
    entries.sort(
        Comparator.comparingInt(UpstreamPools.Snapshot::open)
            .reversed()
            .thenComparing(entry -> entry.origin().toString())
            .thenComparing(UpstreamPools.Snapshot::pool));
    JsonArray document = new JsonArray();
    for (UpstreamPools.Snapshot entry : entries) {
      UpstreamPools.Owner whose = owner.apply(entry.origin());
      JsonArray held = new JsonArray();
      for (UpstreamPools.Holder holder : entry.held()) {
        UpstreamLease lease = holder.lease();
        held.add(
            new JsonObject()
                .put("method", lease.method())
                .put("path", lease.path())
                .put("ageMs", holder.ageMs())
                .put("client", lease.client())
                .put("userAgent", lease.userAgent())
                .put("workspaceId", lease.workspaceId())
                .put("traceId", lease.traceId()));
      }
      document.add(
          new JsonObject()
              .put("name", whose.name())
              .put("environment", whose.environment())
              .put("origin", entry.origin().toString())
              .put("pool", entry.pool())
              .put("open", entry.open())
              .put("max", entry.max())
              .put("held", held));
    }
    return document;
  }
}
