package eu.wohlben.qits.edge;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import java.lang.reflect.Proxy;
import java.security.interfaces.RSAPublicKey;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The edge's real idp clients — {@link IdpKeys}, {@link IdpGrants}, {@link IdpIntrospection} —
 * built without CDI and pointed at one dial address, for the consumer pact against qits-idp (ticket
 * qits-1149). Their wiring is package-private, so this lives in the edge's package.
 *
 * <p>{@link #vhostStatusForToken} and {@link #realmStatusForToken} go one step further, for the
 * refusal rows (ticket qits-950): a real {@link EdgeAuth}, wired the same way, driven with a
 * minimal fake {@link HttpServerRequest}/{@link HttpServerResponse} pair so the HTTP status it
 * actually answers a caller — not merely idp's own status — is what the consumer test asserts.
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

  /**
   * The status the edge's real credential check answers a caller presenting {@code token} as a
   * {@code Bearer} credential on a gated vhost: {@link EdgeAuth#checkCredential} against this mock
   * server, then — exactly as {@code EdgeRouter}'s dispatch does on a refusal — {@link
   * EdgeAuth#challenge}. 200 when the check accepts it; nothing here then builds the proxied
   * response, so acceptance is read off the verdict alone.
   */
  public int vhostStatusForToken(String token) throws Exception {
    EdgeAuth real = edgeAuth();
    int[] status = {0};
    HttpServerRequest request =
        fakeRequest("Bearer " + token, status, new java.util.concurrent.CountDownLatch(1));
    String rejection =
        real.checkCredential(new HostEnvironments.Route("dev", "registry", null), request)
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    if (rejection == null) {
      return 200;
    }
    real.challenge(request, rejection);
    return status[0];
  }

  /**
   * The status the edge's real docker realm answers {@code docker login -p <token>} — {@code token}
   * as the Basic password — with: {@link EdgeAuth#token}, which ends the response itself on every
   * path, refusal included.
   *
   * <p>{@link EdgeAuth#token} is {@code void}: its introspection runs on Vert.x's event loop and
   * ends the fake response from a callback, not from this call stack, so this waits on the latch
   * {@link #fakeRequest} counts down from {@code end} rather than reading {@code status} the
   * instant the call returns.
   */
  public int realmStatusForToken(String token) throws Exception {
    EdgeAuth real = edgeAuth();
    int[] status = {0};
    java.util.concurrent.CountDownLatch ended = new java.util.concurrent.CountDownLatch(1);
    String basic =
        "Basic "
            + java.util.Base64.getEncoder()
                .encodeToString(
                    ("token:" + token).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    HttpServerRequest request = fakeRequest(basic, status, ended);
    real.token(request);
    if (!ended.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("the edge's realm token endpoint never ended the response");
    }
    return status[0];
  }

  /**
   * The edge's real {@link EdgeAuth}, wired exactly as {@link #introspection()}'s client is — the
   * same dial, the same patience, the same introspection credential this probe already holds. Built
   * without CDI, like every other client here; its fields are package-private for exactly this.
   */
  private EdgeAuth edgeAuth() {
    EdgeAuth real = new EdgeAuth();
    real.config = auth;
    real.idp = idp;
    real.keys = keys();
    real.introspection = introspection();
    real.open();
    return real;
  }

  /**
   * A minimal {@link HttpServerRequest}/{@link HttpServerResponse} pair, answering only what {@link
   * EdgeAuth#checkCredential}, {@link EdgeAuth#challenge} and {@link EdgeAuth#token} read off them:
   * the one header that carries the credential, the Host the challenge echoes, and the status the
   * response is given. Built the same way {@link #authConfig()} and {@link #edgeConfig} are — a
   * dynamic proxy, because neither Vert.x interface is small and nothing here needs a real socket.
   */
  private static HttpServerRequest fakeRequest(
      String authorization, int[] status, java.util.concurrent.CountDownLatch ended) {
    MultiMap requestHeaders = MultiMap.caseInsensitiveMultiMap();
    if (authorization != null) {
      requestHeaders.set("Authorization", authorization);
    }
    requestHeaders.set("Host", "registry.dev.localhost");
    Object[] response = new Object[1];
    response[0] =
        Proxy.newProxyInstance(
            HttpServerResponse.class.getClassLoader(),
            new Class<?>[] {HttpServerResponse.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "setStatusCode":
                  status[0] = (int) args[0];
                  return response[0];
                case "putHeader":
                  return response[0];
                case "headers":
                  return MultiMap.caseInsensitiveMultiMap();
                case "end":
                  ended.countDown();
                  return Future.succeededFuture();
                default:
                  throw new UnsupportedOperationException(
                      "the fake response states no " + method.getName());
              }
            });
    return (HttpServerRequest)
        Proxy.newProxyInstance(
            HttpServerRequest.class.getClassLoader(),
            new Class<?>[] {HttpServerRequest.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "getHeader":
                  return requestHeaders.get(args[0].toString());
                case "response":
                  return response[0];
                case "path":
                  return "/v2/";
                case "scheme":
                  return "http";
                default:
                  throw new UnsupportedOperationException(
                      "the fake request states no " + method.getName());
              }
            });
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
                  // EdgeAuth.open()'s caches, and checkToken's refusal TTL — read only by
                  // vhostStatusForToken/realmStatusForToken's real EdgeAuth, never by the bare
                  // introspection client the other probe methods use.
                  case "anonymousReadApps" -> Optional.empty();
                  case "basicCacheSize" -> 16;
                  case "tokenCacheSize" -> 16;
                  case "tokenCacheTtlMs" -> 15_000L;
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
