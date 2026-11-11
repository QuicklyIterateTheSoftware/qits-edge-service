package eu.wohlben.qits.edge;

import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Whether a request on one of the edge's own surfaces carries a credential the service gate would
 * accept — the question {@code /upstream-pools} answers before it serves anything and {@code
 * /main-navigation} answers before it names a private application (qits-2061).
 *
 * <p>The same two checks the gate runs, in its order, and nothing re-implemented: a machine
 * credential first ({@link EdgeAuth#checkCredential}, exactly as on a service vhost), then — only
 * while the browser gate is on and only when no machine credential was presented — the {@code
 * qits-session} cookie, introspected by {@link EdgeSessions}.
 */
@ApplicationScoped
public class AdmittedCaller {

  @Inject EdgeAuth auth;

  @Inject EdgeSessions sessions;

  /**
   * True when the gate would admit this caller, false when it would not. A FAILED future is a check
   * that could not be made — {@link EdgeAuth.IdpUnreachable} for an opaque token idp could not be
   * asked about, or an idp that could not introspect a session — and is the caller's to decide.
   *
   * <p>No pause: both readers are a GET or a HEAD, with no body to hold back.
   */
  public Future<Boolean> admitted(HttpServerRequest request) {
    if (EdgeAuth.carriesCredential(request)) {
      return auth.checkCredential(request).map(rejection -> rejection == null);
    }
    String cookie = sessions.enabled() ? sessions.cookie(request) : null;
    if (cookie == null) {
      return Future.succeededFuture(false);
    }
    return sessions.introspect(cookie).map(session -> session != null);
  }
}
