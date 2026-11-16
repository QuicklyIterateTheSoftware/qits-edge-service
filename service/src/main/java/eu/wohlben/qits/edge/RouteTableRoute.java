package eu.wohlben.qits.edge;

import io.vertx.core.Future;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Locale;
import org.jboss.logging.Logger;

/**
 * {@code PUT /edge-route-table} and {@code GET /edge-route-table}: the route table qits-deployments
 * pushes (epic qits-2174), on every vhost, answered by the edge itself and never proxied — the same
 * shape of route as {@link NavigationRoute}. What is stored and how is {@link RouteTables}; D2a
 * stores and reports a table and serves nothing from it.
 *
 * <p><b>Only the deployer may push.</b> A {@code PUT} needs a credential {@link
 * EdgeAuth#checkCredential} accepts, presented as a service bearer ({@link
 * EdgeAuth#isServiceBearer}: a JWT carrying {@code qits:system}, never an opaque token or a Basic
 * pair), whose {@code sub} is {@code qits.edge.routes.publisher} — the deployer's own idp client.
 * qits-idp writes a client token's client id into {@code sub} and sets no {@code azp} (its
 * TokenService.signFor); a granted claim is copied into the token verbatim, so an {@code azp} would
 * be the client's to choose and is never read (qits-2174, the first live push answered 403).
 * Anything else is a {@code 403}. It answers {@code 204} when the table is stored, {@code 200
 * {"held":<v>}} when its version is not above the held one, and {@code 422 {"error":"<reason>"}}
 * when it is invalid.
 *
 * <p>A {@code GET} is answered to the same caller or to any {@code qits:system} service: {@code
 * {"edge":"public|private","version":<v>|null,"receivedAt":"…"|null}} — the deployer reads it on
 * each observation pass and pushes again when the edge reports an older version.
 *
 * <p>An idp that cannot vouch for the credential is the same {@code 503} the service gate answers.
 */
@ApplicationScoped
public class RouteTableRoute {

  static final String PATH = "/edge-route-table";

  /** Far above any table the estate can build, and small enough to hold in memory. */
  static final long BODY_LIMIT = 4L * 1024 * 1024;

  private static final Logger LOG = Logger.getLogger(RouteTableRoute.class);

  private static final String BEARER = "bearer ";

  @Inject EdgeAuth auth;

  @Inject RouteTables tables;

  @Inject EdgeConfig config;

  void register(@Observes Router router) {
    router
        .route(PATH)
        .method(HttpMethod.PUT)
        // Ahead of EdgeRouter.ROUTE_ORDER, like /main-navigation: answered here, never proxied.
        .order(100)
        .handler(BodyHandler.create(false).setBodyLimit(BODY_LIMIT))
        .handler(this::put);
    router.route(PATH).method(HttpMethod.GET).method(HttpMethod.HEAD).order(100).handler(this::get);
  }

  private void put(RoutingContext context) {
    HttpServerRequest request = context.request();
    // Read BEFORE the check, which rewrites an accepted Basic pair or opaque token into the JWT it
    // stood for: only what the caller presented says whether it is a service speaking for itself.
    String presented = request.getHeader(HttpHeaders.AUTHORIZATION);
    String publisher = config.routes().publisher();
    admit(request, presented, () -> publisher.equals(clientId(presented)))
        .onSuccess(
            yes -> {
              if (!yes) {
                refuse(request);
                return;
              }
              String body = context.body().asString();
              context
                  .vertx()
                  .<RouteTables.Outcome>executeBlocking(() -> tables.offer(body), false)
                  .onSuccess(outcome -> answer(request, outcome))
                  .onFailure(failure -> failed(request, failure));
            })
        .onFailure(failure -> unchecked(request, failure));
  }

  private void get(RoutingContext context) {
    HttpServerRequest request = context.request();
    String presented = request.getHeader(HttpHeaders.AUTHORIZATION);
    // Any qits:system service — the deployer, whose sub is a special case of that, included.
    admit(request, presented, () -> true)
        .onSuccess(
            yes -> {
              if (!yes) {
                refuse(request);
                return;
              }
              context
                  .vertx()
                  .executeBlocking(tables::held, false)
                  .onSuccess(held -> json(request, 200, held.document()))
                  .onFailure(failure -> failed(request, failure));
            })
        .onFailure(failure -> unchecked(request, failure));
  }

  /**
   * True when the edge accepts the presented credential, it is a service bearer, and {@code
   * further} holds of it. A failed future is a check that could not be made.
   */
  private Future<Boolean> admit(
      HttpServerRequest request, String presented, java.util.function.BooleanSupplier further) {
    if (!EdgeAuth.isServiceBearer(presented)) {
      // Checked first and without idp: a value that is no service bearer is refused whatever it is.
      return Future.succeededFuture(false);
    }
    return auth.checkCredential(request).map(problem -> problem == null && further.getAsBoolean());
  }

  /**
   * The {@code sub} claim — the idp client id — of a presented bearer JWT; null for anything else.
   */
  static String clientId(String authorization) {
    if (authorization == null || !authorization.toLowerCase(Locale.ROOT).startsWith(BEARER)) {
      return null;
    }
    try {
      return SignedJwt.parse(authorization.substring(BEARER.length()).trim())
          .claims()
          .getString("sub");
    } catch (RuntimeException unreadable) {
      return null;
    }
  }

  private static void answer(HttpServerRequest request, RouteTables.Outcome outcome) {
    switch (outcome) {
      case RouteTables.Stored stored ->
          request
              .response()
              .setStatusCode(204)
              .putHeader(HttpHeaders.CACHE_CONTROL, "no-store")
              .end();
      case RouteTables.AlreadyHeld held ->
          json(request, 200, new JsonObject().put("held", held.held()));
      case RouteTables.Invalid invalid ->
          json(request, 422, new JsonObject().put("error", invalid.reason()));
    }
  }

  private static void refuse(HttpServerRequest request) {
    json(
        request,
        403,
        new JsonObject()
            .put("error", "the route table is pushed and read by qits-deployments' own bearer"));
  }

  private void unchecked(HttpServerRequest request, Throwable failure) {
    if (failure instanceof EdgeAuth.IdpUnreachable) {
      LOG.warnf("%s; %s answered 503", failure.getMessage(), PATH);
      auth.unavailable(request);
      return;
    }
    LOG.errorf(failure, "could not check the caller of %s", PATH);
    refuse(request);
  }

  private static void failed(HttpServerRequest request, Throwable failure) {
    LOG.errorf(failure, "%s %s failed", request.method(), PATH);
    request.response().setStatusCode(500).end();
  }

  private static void json(HttpServerRequest request, int status, JsonObject document) {
    request
        .response()
        .setStatusCode(status)
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
        .putHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        .end(document.encode());
  }
}
