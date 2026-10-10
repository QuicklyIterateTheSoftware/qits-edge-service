package eu.wohlben.qits.edge;

import io.vertx.core.Vertx;
import java.lang.reflect.Proxy;
import java.security.interfaces.RSAPublicKey;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The edge's real idp clients — {@link IdpKeys}, {@link IdpGrants}, {@link IdpIntrospection} —
 * built without CDI and pointed at one dial address, for the consumer pact against qits-idp (ticket
 * qits-1149). Their wiring is package-private, so this lives in the edge's package.
 */
public final class IdpProbe implements AutoCloseable {

  /** The edge's own idp client, as the deployer would inject it. Not a secret: a test value. */
  public static final String CLIENT_ID = "dev-qits-edge";

  public static final String CLIENT_SECRET = "edge-secret";

  private final Vertx vertx = Vertx.vertx();
  private final Idp idp = new Idp();
  private final AuthConfig auth = authConfig();

  public IdpProbe(String dialUrl) {
    idp.configuredDial = dialUrl;
  }

  /** {@code GET <dial>/jwks}: the key the edge would verify with. */
  public RSAPublicKey key(String kid) throws Exception {
    IdpKeys keys = new IdpKeys();
    keys.vertx = vertx;
    keys.config = auth;
    keys.idp = idp;
    keys.open();
    return keys.find(kid).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  /** {@code POST <dial>/token}, relaying a caller's Basic header: status and body as read. */
  public IdpGrants.Grant grant(String authorization) throws Exception {
    IdpGrants grants = new IdpGrants();
    grants.vertx = vertx;
    grants.config = auth;
    grants.idp = idp;
    grants.open();
    return grants
        .grant(authorization)
        .toCompletionStage()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);
  }

  /** {@code POST <dial>/api/sessions/introspect} with the edge's own client. */
  public IdpIntrospection.Answer introspectSession(String cookie) throws Exception {
    return introspection()
        .introspect(idp.introspectionEndpoint(), cookie)
        .toCompletionStage()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);
  }

  /** {@code POST <dial>/api/tokens/introspect} with the edge's own client. */
  public IdpIntrospection.Answer introspectToken(String token) throws Exception {
    return introspection()
        .introspect(idp.tokenIntrospectionEndpoint(), token)
        .toCompletionStage()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);
  }

  /** What {@link EdgeSessions} makes of an introspection answer; null when it refuses it. */
  public static EdgeSessions.Session session(String body) {
    return EdgeSessions.read(body);
  }

  private IdpIntrospection introspection() {
    IdpIntrospection introspection = new IdpIntrospection();
    introspection.vertx = vertx;
    introspection.authConfig = auth;
    introspection.idpConfig = idpConfig();
    introspection.open();
    return introspection;
  }

  @Override
  public void close() {
    vertx.close();
  }

  private static AuthConfig authConfig() {
    return (AuthConfig)
        Proxy.newProxyInstance(
            AuthConfig.class.getClassLoader(),
            new Class<?>[] {AuthConfig.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "idpCallTimeoutMs" -> 5000L;
                  // No retries: the mock server answers, and a refusal is never retried anyway.
                  case "idpRetryWindowMs" -> 0L;
                  case "jwksRefreshCooldownMs" -> 0L;
                  default ->
                      throw new UnsupportedOperationException(
                          "IdpProbe states no " + method.getName());
                });
  }

  private static IdpConfig idpConfig() {
    return new IdpConfig() {
      @Override
      public String dialUrl() {
        throw new UnsupportedOperationException();
      }

      @Override
      public Optional<String> clientId() {
        return Optional.of(CLIENT_ID);
      }

      @Override
      public Optional<String> clientSecret() {
        return Optional.of(CLIENT_SECRET);
      }
    };
  }
}
