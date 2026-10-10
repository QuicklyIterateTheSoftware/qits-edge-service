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

  private final Vertx vertx = Vertx.vertx();
  private final Idp idp = new Idp();
  private final AuthConfig auth = authConfig();
  private final String clientId;
  private final String clientSecret;

  /**
   * @param dialUrl the idp dial address, {@code .../idp}
   * @param domain the stated domain the accepted issuer is derived from
   * @param edgeAuthorization the edge's own client as a {@code Basic} header, as the deployer would
   *     inject it; null where the call does not use it
   */
  public IdpProbe(String dialUrl, String domain, String edgeAuthorization) {
    idp.configuredDial = dialUrl;
    idp.edge = edgeConfig(domain);
    String[] client = client(edgeAuthorization);
    clientId = client[0];
    clientSecret = client[1];
  }

  /** The dial address, trimmed as the edge trims it. */
  public String dial() {
    return idp.dialBase();
  }

  /** {@code GET <dial>/.well-known/openid-configuration}: the {@code jwks_uri} the edge follows. */
  public String discoverJwksUri() throws Exception {
    return keys()
        .discoverJwksUri()
        .toCompletionStage()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);
  }

  /**
   * {@code GET <jwksUri>}, as discovery named it: the key the edge would verify with. A mock server
   * answers discovery with the recorded address, so the key set's address is stated here.
   */
  public RSAPublicKey key(String jwksUri, String kid) throws Exception {
    IdpKeys keys = keys();
    keys.jwksUri = jwksUri;
    return keys.find(kid).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  private IdpKeys keys() {
    IdpKeys keys = new IdpKeys();
    keys.vertx = vertx;
    keys.config = auth;
    keys.idp = idp;
    keys.open();
    return keys;
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

  private static String[] client(String basic) {
    if (basic == null) {
      return new String[] {null, null};
    }
    String decoded =
        new String(
            java.util.Base64.getDecoder().decode(basic.substring("Basic ".length())),
            java.nio.charset.StandardCharsets.UTF_8);
    int colon = decoded.indexOf(':');
    return new String[] {decoded.substring(0, colon), decoded.substring(colon + 1)};
  }

  private static EdgeConfig edgeConfig(String domain) {
    return (EdgeConfig)
        Proxy.newProxyInstance(
            EdgeConfig.class.getClassLoader(),
            new Class<?>[] {EdgeConfig.class},
            (proxy, method, args) -> {
              if (method.getName().equals("domain")) {
                return domain;
              }
              throw new UnsupportedOperationException("IdpProbe states no " + method.getName());
            });
  }

  private IdpConfig idpConfig() {
    return new IdpConfig() {
      @Override
      public String dialUrl() {
        throw new UnsupportedOperationException();
      }

      @Override
      public Optional<String> clientId() {
        return Optional.ofNullable(clientId);
      }

      @Override
      public Optional<String> clientSecret() {
        return Optional.ofNullable(clientSecret);
      }
    };
  }
}
