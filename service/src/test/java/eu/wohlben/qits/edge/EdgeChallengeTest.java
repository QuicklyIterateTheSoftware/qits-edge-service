package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.WithDefault;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The decisions {@code EdgeAuth} and {@code EdgeSessions} make without touching a socket: the
 * {@code WWW-Authenticate} value character by character, the audience it demands, which requests
 * skip the gate entirely, and — for the browser half — what a cookie header says, what may be
 * redirected, and where a login may return to.
 *
 * <p>The challenge is a wire contract with a client nobody here controls — docker parses it to find
 * the token endpoint, and one it cannot parse fails a pull with no message in any log on either
 * side. The browser half's contracts are the same kind of thing one hop out: a cookie name idp
 * sets, a login path its SPA serves, and a redirect target that must never leave this host.
 */
class EdgeChallengeTest {

  // --- the platform's app vhosts: code, not configuration (qits-528)
  // -------------------------------

  @Test
  void thePlatformAppVhostsAreExactlyTheFourPlatformApplications() {
    // idp joined the three machine vhosts (qits-1403): its name must answer before the deployment
    // catch-up ends, so it cannot wait for the projection to learn it.
    assertEquals(Set.of("registry", "mirror", "githost", "idp"), PlatformApps.labels());
    assertTrue(PlatformApps.contains("IDP"), "a Host name arrives in any case");
    assertEquals("qits-idp", PlatformApps.application("idp"));
  }

  @Test
  void idpIsAddressedByThePlatformsOwnRuleAndOwnsItsOwnRoute() {
    // dev-qits-idp:8080, the alias qits-deployments gives it — the address its own deployment
    // publishes, so a published `idp` host and the platform label agree
    // (DeploymentActiveSubscriber).
    // The path is never rewritten: idp.qits.<domain>/idp/jwks reaches dev-qits-idp:8080/idp/jwks.
    assertEquals(
        new Upstream("dev-qits-idp", 8080), EdgeRouter.appUpstream("idp", "dev", Map.of()));
    assertTrue(PlatformApps.owns("idp", "/idp"));
    assertTrue(PlatformApps.owns("idp", "/idp/jwks"));
    assertTrue(PlatformApps.owns("idp", "/idp/login"));
    assertFalse(PlatformApps.owns("idp", "/idpx/login"), "a route matches whole segments");
    assertFalse(PlatformApps.owns("idp", "/ci/api/runs"));
    assertFalse(PlatformApps.owns("registry", "/idp/login"), "only idp owns the login prefix");
    assertFalse(PlatformApps.owns("brochure", "/idp/login"));
  }

  @Test
  void aPlatformAppIsAddressedByThePlatformsOwnRule() {
    // <env>-qits-<application>:8080, the alias qits-deployments gives every service on qits-net.
    // These were six live configuration entries, one of them naming a tier that no longer exists.
    assertEquals(
        new Upstream("dev-qits-artifacts", 8080),
        EdgeRouter.appUpstream("registry", "dev", Map.of()));
    assertEquals(
        new Upstream("dev-qits-mirror", 8080), EdgeRouter.appUpstream("mirror", "dev", Map.of()));
    assertEquals(
        new Upstream("dev-qits-githost", 8080), EdgeRouter.appUpstream("githost", "dev", Map.of()));
    assertEquals(
        new Upstream("prod-qits-artifacts", 8080),
        EdgeRouter.appUpstream("registry", "prod", Map.of()),
        "one label serves every tier, each at its own alias");
  }

  @Test
  void aLocalOverrideReAddressesOneLabelInOneEnvironmentOnly() {
    Map<String, EdgeConfig.App> overrides =
        Map.of("mirror", hosts(Map.of("dev", "127.0.0.1:4711")));

    assertEquals(
        new Upstream("127.0.0.1", 4711), EdgeRouter.appUpstream("mirror", "dev", overrides));
    assertEquals(
        new Upstream("prod-qits-mirror", 8080),
        EdgeRouter.appUpstream("mirror", "prod", overrides));
    assertEquals(
        new Upstream("dev-qits-artifacts", 8080),
        EdgeRouter.appUpstream("registry", "dev", overrides));
  }

  @Test
  void anOverrideCannotAddAVhost() {
    // Which names the edge serves on its own is code. A key for any other label is a typo or a
    // second route table, and it fails the startup rather than routing anything.
    EdgeRouter.requirePlatformLabels(Map.of("githost", hosts(Map.of())));
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () -> EdgeRouter.requirePlatformLabels(Map.of("brochure", hosts(Map.of()))));
    assertTrue(refused.getMessage().contains("qits.edge.apps.brochure"), refused.getMessage());
  }

  @Test
  void theRetiredPatternEntriesStillInTheStoreBindNothing() {
    // The six QITS_EDGE_APPS_*_PATTERN entries stay in every container's environment until the
    // configuration GC collects them, two deploys after the release that stops declaring them. In
    // that window they must be inert: no override, and above all no startup failure.
    EdgeConfig config =
        new SmallRyeConfigBuilder()
            .withMapping(EdgeConfig.class)
            .withSources(
                new EnvConfigSource(
                    Map.of(
                        "QITS_EDGE_APPS_EDITOR_AUDIENCE_PATTERN", "{env}-qits-workspaces",
                        "QITS_EDGE_APPS_EDITOR_HOST_PATTERN", "{env}-qits-workspaces",
                        "QITS_EDGE_APPS_GITHOST_AUDIENCE_PATTERN", "{env}-qits-githost",
                        "QITS_EDGE_APPS_GITHOST_HOST_PATTERN", "{env}-qits-githost",
                        "QITS_EDGE_APPS_MIRROR_HOST_PATTERN", "{env}-qits-platform-mirror",
                        "QITS_EDGE_APPS_REGISTRY_HOST_PATTERN", "{env}-qits-artifacts"),
                    300))
            .build()
            .getConfigMapping(EdgeConfig.class);

    EdgeRouter.requirePlatformLabels(config.apps());
    assertEquals(
        new Upstream("dev-qits-mirror", 8080),
        EdgeRouter.appUpstream("mirror", "dev", config.apps()));
  }

  // --- the platform audience ---------------------------------------------------------------------

  @Test
  void thePlatformAudienceHasNoEnvironment() {
    // Roles are the permission. A placeholder here would make the audience per environment again.
    assertEquals("qits-platform", PlatformApps.PLATFORM_AUDIENCE);
  }

  @Test
  void aBasicCredentialIsJudgedByThePlatformAudienceAlone() {
    // The Basic and token paths judge the minted token's audiences, cached or fresh, by one rule.
    assertNull(EdgeAuth.refusalFor(new JsonArray(List.of("qits-platform"))));
    assertNull(EdgeAuth.refusalFor(new JsonArray(List.of("somebody-else", "qits-platform"))));
    assertEquals(
        "the credential is not for qits-platform",
        EdgeAuth.refusalFor(new JsonArray(List.of("somebody-else"))));
    assertEquals(
        "the credential is not for qits-platform",
        EdgeAuth.refusalFor(new JsonArray(List.of("dev-qits-artifacts"))),
        "an application's own wire alias is not an audience any vhost accepts");
  }

  @Test
  void theChallengePointsBackAtThisSameVhostsTokenEndpoint() {
    assertEquals(
        "Bearer realm=\"http://registry.dev.localhost:8080/token\",service=\"registry.dev.localhost:8080\"",
        EdgeAuth.bearerChallenge("http", "registry.dev.localhost:8080", "no bearer token"));
  }

  @Test
  void theBasicChallengeNamesTheSameAuthorityTheBearerOneServes() {
    // One door, described twice. A realm that disagreed with the Bearer challenge's `service` would
    // read as two credentials to a client that shows the user which one it is asking for.
    assertEquals(
        "Basic realm=\"registry.dev.localhost:8080\"",
        EdgeAuth.basicChallenge("registry.dev.localhost:8080"));
  }

  @Test
  void aRejectedTokenSaysSoSoTheClientRefetchesRatherThanGivingUp() {
    assertTrue(
        EdgeAuth.bearerChallenge("https", "registry.dev.localhost", "the token expired")
            .endsWith(",error=\"invalid_token\""));
    // Absent on a first anonymous request: clients read an error there as "these credentials are
    // wrong" and stop, rather than as "you have not tried yet".
    assertFalse(
        EdgeAuth.bearerChallenge("https", "registry.dev.localhost", "no bearer token")
            .contains("error="));
  }

  @Test
  void aHostHeaderCannotWriteItsOwnRealm() {
    // The Host header is echoed into a quoted header value. Without the filter, a caller could
    // close
    // the quote and point a docker client at somebody else's token endpoint.
    assertEquals(
        "evil.example.comrealmhttp:attacker",
        EdgeAuth.safeAuthority("evil.example.com\",realm=\"http://attacker"));
    assertEquals("", EdgeAuth.safeAuthority(null));
    assertEquals(
        "registry.dev.localhost:8080", EdgeAuth.safeAuthority("registry.dev.localhost:8080"));
  }

  @Test
  void theSchemeIsCarriedIntoTheRealm() {
    assertTrue(
        EdgeAuth.bearerChallenge("https", "registry.dev.localhost", "no bearer token")
            .contains("realm=\"https://registry.dev.localhost/token\""));
  }

  // --- the anonymous-read exemption --------------------------------------------------------------

  private static final String Q = "/q";

  @Test
  void anExemptedAppServesTheTwoReadingMethodsAndNoOthers() {
    // The whole shape of the decision: reads are the bootstrap steps that happen before there is a
    // token to hold, writes change what the platform will run and keep the check.
    Set<String> open = Set.of("mirror");
    assertTrue(EdgeAuth.anonymousRead(app("mirror", "dev"), HttpMethod.GET, "/v2/", open, Q));
    assertTrue(EdgeAuth.anonymousRead(app("mirror", "dev"), HttpMethod.HEAD, "/v2/", open, Q));
    for (HttpMethod method :
        new HttpMethod[] {
          HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.OPTIONS
        }) {
      assertFalse(
          EdgeAuth.anonymousRead(app("mirror", "dev"), method, "/v2/", open, Q),
          method + " is not a read and must still need a token");
    }
  }

  @Test
  void theExemptionIsPerAppLabel() {
    // An app that was not named is gated on every method, which is the pre-exemption edge exactly.
    assertFalse(
        EdgeAuth.anonymousRead(
            app("registry", "dev"), HttpMethod.GET, "/v2/", Set.of("mirror"), Q));
    // Every environment of a named app, from one entry — the label is the whole key.
    assertTrue(
        EdgeAuth.anonymousRead(app("mirror", "prod"), HttpMethod.GET, "/v2/", Set.of("mirror"), Q));
  }

  @Test
  void theExemptionNeverReachesTheEnvironmentVhost() {
    // The environment vhost has its own switch, and it routes the platform's whole existing
    // traffic. No value in this list may widen it — not even one that spells an environment name.
    assertFalse(
        EdgeAuth.anonymousRead(
            HostEnvironments.Route.apex("dev"),
            HttpMethod.GET,
            "/v2/",
            Set.of("mirror", "dev"),
            Q));
  }

  @Test
  void anEmptyListGatesEverything() {
    // Today's behaviour, and the shipped default: with nothing named, every method on every app
    // vhost needs a token.
    assertFalse(EdgeAuth.anonymousRead(app("mirror", "dev"), HttpMethod.GET, "/v2/", Set.of(), Q));
    assertFalse(
        EdgeAuth.anonymousRead(app("registry", "dev"), HttpMethod.HEAD, "/v2/", Set.of(), Q));
  }

  @Test
  void theManagementSurfaceIsNeverPartOfTheOpening() {
    // qits-2222: /q on an app vhost now reaches that app's own Quarkus management routes
    // (EdgeRouter's early non-application route), so the exemption must never cover it, however
    // the app's reads are otherwise opened.
    Set<String> open = Set.of("mirror");
    assertFalse(
        EdgeAuth.anonymousRead(app("mirror", "dev"), HttpMethod.GET, "/q/metrics", open, Q));
    assertFalse(
        EdgeAuth.anonymousRead(app("mirror", "dev"), HttpMethod.GET, "/q/openapi", open, Q));
    assertFalse(
        EdgeAuth.anonymousRead(app("mirror", "dev"), HttpMethod.GET, "/q/health/ready", open, Q));
    assertFalse(EdgeAuth.anonymousRead(app("mirror", "dev"), HttpMethod.GET, "/q", open, Q));
    // Ordinary paths on the same app, and a path that merely starts with "/q" without being the
    // management root, still read the exemption normally.
    assertTrue(EdgeAuth.anonymousRead(app("mirror", "dev"), HttpMethod.GET, "/queue/x", open, Q));
    assertTrue(EdgeAuth.anonymousRead(app("mirror", "dev"), HttpMethod.GET, "/v2/", open, Q));
    // A custom non-application root is matched, not the default: "/q" is then an ordinary path.
    assertTrue(
        EdgeAuth.anonymousRead(app("mirror", "dev"), HttpMethod.GET, "/q", open, "/management"));
    assertFalse(
        EdgeAuth.anonymousRead(
            app("mirror", "dev"), HttpMethod.GET, "/management/metrics", open, "/management"));
  }

  @Test
  void aSpellingTheRouterWouldNormaliseIsNeverPartOfTheOpening() {
    // qits-2229: every one of these is /q/metrics to Vert.x — the edge's router and the app's,
    // which
    // receives the raw path verbatim — while the raw-path /q test above reads it as something else.
    // Not canonical, so not exempt: each meets the credential check like any other gated path.
    Set<String> open = Set.of("mirror");
    for (String path :
        List.of(
            "//q/metrics",
            "/%71/metrics",
            "/q%2Fmetrics",
            "/x/../q/metrics",
            "/%2Fq/metrics",
            "/q/./metrics")) {
      assertFalse(
          EdgeAuth.anonymousRead(app("mirror", "dev"), HttpMethod.GET, path, open, Q),
          path + " is not canonical and must not be read anonymously");
    }
    // The opening itself is untouched on the paths it was made for.
    assertTrue(EdgeAuth.anonymousRead(app("mirror", "dev"), HttpMethod.GET, "/v2/", open, Q));
    assertTrue(EdgeAuth.anonymousRead(app("mirror", "dev"), HttpMethod.GET, "/queue/x", open, Q));
  }

  @Test
  void aCanonicalPathIsTheOneSpellingEveryNormaliserAgreesOn() {
    // One row per rule in EdgePaths.canonical, each side of it.
    // Starts with a slash.
    assertTrue(EdgePaths.canonical("/"));
    assertTrue(EdgePaths.canonical("/v2/library/alpine/manifests/3.20"));
    assertFalse(EdgePaths.canonical("v2/"));
    assertFalse(EdgePaths.canonical(""));
    assertFalse(EdgePaths.canonical(null));
    // No empty segment: Vert.x collapses //.
    assertFalse(EdgePaths.canonical("//q/health"));
    assertFalse(EdgePaths.canonical("/v2//x"));
    assertFalse(EdgePaths.canonical("/v2/x//"));
    // No backslash.
    assertFalse(EdgePaths.canonical("/q\\health"));
    assertFalse(EdgePaths.canonical("/\\q/health"));
    // No dot segment, literal or escaped in any case or mix.
    assertFalse(EdgePaths.canonical("/x/../q/health"));
    assertFalse(EdgePaths.canonical("/q/./health"));
    assertFalse(EdgePaths.canonical("/q/health/.."));
    assertFalse(EdgePaths.canonical("/x/%2e%2e/q/health"));
    assertFalse(EdgePaths.canonical("/x/%2E%2E/q/health"));
    assertFalse(EdgePaths.canonical("/x/.%2e/q/health"));
    assertFalse(EdgePaths.canonical("/x/%2E./q/health"));
    assertFalse(EdgePaths.canonical("/q/%2e/health"));
    // ...while a dot inside a name is just a character.
    assertTrue(EdgePaths.canonical("/assets/main-ab12cd.js"));
    assertTrue(EdgePaths.canonical("/x/.well-known/y"));
    assertTrue(EdgePaths.canonical("/x/.../y"));
    // No escape a normaliser decodes in place: unreserved characters, a slash, a backslash.
    assertFalse(EdgePaths.canonical("/%71/health"));
    assertFalse(EdgePaths.canonical("/%51/health"));
    assertFalse(EdgePaths.canonical("/v%32/"));
    assertFalse(EdgePaths.canonical("/a%2Db"));
    assertFalse(EdgePaths.canonical("/a%5fb"));
    assertFalse(EdgePaths.canonical("/a%7Eb"));
    assertFalse(EdgePaths.canonical("/q%2Fhealth"));
    assertFalse(EdgePaths.canonical("/q%2fhealth"));
    assertFalse(EdgePaths.canonical("/q%5Chealth"));
    // Any other escape means the same thing to every parser, so it stays canonical.
    assertTrue(EdgePaths.canonical("/a%20b"));
    assertTrue(EdgePaths.canonical("/sha256%3Aabc"));
    assertTrue(EdgePaths.canonical("/caf%C3%A9"));
    assertTrue(EdgePaths.canonical("/a%25b"));
    // A malformed escape is read differently by every parser that meets one.
    assertFalse(EdgePaths.canonical("/a%"));
    assertFalse(EdgePaths.canonical("/a%2"));
    assertFalse(EdgePaths.canonical("/a%zzb"));
    assertFalse(EdgePaths.canonical("/a%2gb"));
  }

  @Test
  void theSessionGatesAnonymousPrefixCannotBeSteppedOutOf() {
    // qits-2229: /idp/ is the session gate's anonymous prefix, compared with the raw path, and
    // idp's
    // own Vert.x normalises what it receives. Each of these starts with /idp/ and is /q/health (or
    // could be) to idp, so EdgeRouter.noSession requires the canonical spelling before the prefix.
    List<String> idp = List.of("/idp/");
    for (String path : List.of("/idp/../q/health", "/idp/%2e%2e/q/health", "/idp/..%2Fq/health")) {
      assertTrue(EdgeSessions.anonymous(path, idp), path + " does match the bare prefix");
      assertFalse(EdgePaths.canonical(path), path + " must lose the carve-out");
    }
    assertTrue(EdgePaths.canonical("/idp/login"));
    assertTrue(EdgePaths.canonical("/idp/assets/main-ab12cd.js"));
    assertTrue(EdgePaths.canonical("/idp/api/auth/login"));
  }

  @Test
  void theShippedDefaultNamesNoApp() throws Exception {
    // A default here would open reads on a deployment that never asked, and the names it would open
    // are exactly the ones worth closing. Pinned rather than assumed: absent means empty.
    assertNull(
        AuthConfig.class.getMethod("anonymousReadApps").getAnnotation(WithDefault.class),
        "qits.edge.auth.anonymous-read-apps must have no default");
  }

  @Test
  void aConfiguredLabelIsReadInTheSpellingAHostNameArrivesIn() {
    // Host names arrive in any case at all and HostEnvironments lower-cases what it resolves, so
    // without this normalisation `Mirror` in configuration would open nothing.
    assertEquals(Set.of("mirror", "registry"), EdgeAuth.readApps(List.of(" Mirror ", "REGISTRY")));
    assertEquals(Set.of(), EdgeAuth.readApps(List.of("", "  ")));
  }

  // --- HTTP Basic ------------------------------------------------------------------------------

  @Test
  void aCredentialIsBase64OfAClientIdAndASecret() {
    // The shape, and nothing about whether idp knows it. What this rejects never reaches idp, so a
    // client with an empty credential store is answered here rather than made to wait for one.
    assertTrue(EdgeAuth.isClientCredentials(encode("a-client:a-secret")));
    assertTrue(
        EdgeAuth.isClientCredentials(encode("a-client:a:secret")), "a secret may hold a colon");
    assertFalse(EdgeAuth.isClientCredentials(null));
    assertFalse(EdgeAuth.isClientCredentials(""));
    assertFalse(EdgeAuth.isClientCredentials("!!not-base64"));
    assertFalse(EdgeAuth.isClientCredentials(encode("no-colon-at-all")));
    assertFalse(EdgeAuth.isClientCredentials(encode(":")), "no id and no secret is neither");
    assertFalse(EdgeAuth.isClientCredentials(encode("an-id:")), "a client id alone is not one");
    assertFalse(EdgeAuth.isClientCredentials(encode(":a-secret")));
  }

  @Test
  void gitOauthBasicCarriesAnAccessTokenAsItsPassword() {
    assertEquals(
        "header.payload.signature",
        EdgeAuth.oauth2Token(encode("oauth2:header.payload.signature")));
    assertNull(EdgeAuth.oauth2Token(encode("client:secret")));
    assertNull(EdgeAuth.oauth2Token(encode("oauth2:")));
    assertNull(EdgeAuth.oauth2Token("not-base64"));
  }

  @Test
  void aCachedCredentialIsHeldAsAHashAndNeverAsItself() {
    // The cache key is a caller's SECRET. A hash is what keeps it out of a map, a log line and a
    // heap dump, and the same credential has to keep finding its own entry.
    String credential = encode("a-client:a-secret");
    String fingerprint = EdgeAuth.fingerprint(credential);
    assertEquals(fingerprint, EdgeAuth.fingerprint(credential));
    assertNotEquals(fingerprint, EdgeAuth.fingerprint(encode("a-client:another-secret")));
    assertFalse(fingerprint.contains("a-secret"));
    assertFalse(fingerprint.contains(credential));
  }

  @Test
  void aBeliefOutlivesNeitherTheCeilingNorTheTokenItHolds() {
    // What is cached beside the verdict is the token the edge will FORWARD, so the entry has to
    // die while that token is still worth forwarding — it is validated one hop further in, a
    // moment later, against another process' clock.
    long now = 1_000_000L;
    assertEquals(
        now + 60_000,
        EdgeAuth.believeUntil(now, 60_000, Instant.ofEpochMilli(now + 300_000), 60_000),
        "the configured ceiling binds while the token has life to spare");
    assertEquals(
        now + 240_000,
        EdgeAuth.believeUntil(now, 300_000, Instant.ofEpochMilli(now + 300_000), 60_000),
        "and the token's own life binds otherwise — less the margin, which is the whole point");
    assertEquals(
        now,
        EdgeAuth.believeUntil(now, 300_000, Instant.ofEpochMilli(now + 30_000), 60_000),
        "a token already inside the margin is a belief that expired before it was written, so the"
            + " credential is spent again rather than a dying token being handed on");
    assertEquals(
        now,
        EdgeAuth.believeUntil(now, 300_000, Instant.ofEpochMilli(now - 1), 60_000),
        "and never a time in the past, which a hit would read as live");
    assertEquals(
        now,
        EdgeAuth.believeUntil(now, 300_000, null, 60_000),
        "a token that names no expiry is refused before this, and is cached for no time at all");
  }

  @Test
  void aTokenIsTheFixedPrefixAndNothingAJwtCouldEverBe() {
    // The prefix is the whole recogniser, and it has to be: it is what keeps a token out of the JWT
    // parser and a JWT out of idp's token door.
    assertTrue(TokenValue.isToken("qits_tok_abc"));
    assertFalse(TokenValue.isToken("qits_tok_"), "the prefix alone is no token");
    assertFalse(TokenValue.isToken(null));
    assertFalse(TokenValue.isToken("eyJhbGciOiJSUzI1NiJ9.e30.c2ln"), "a JWT always begins eyJ");
    assertFalse(TokenValue.isToken("QITS_TOK_abc"), "and the prefix is exact");
  }

  @Test
  void aBasicPairCarriesATokenAsItsPasswordWhateverTheUser() {
    assertEquals("qits_tok_abc", TokenValue.fromBasic(encode("oauth2:qits_tok_abc")));
    assertEquals("qits_tok_abc", TokenValue.fromBasic(encode("token:qits_tok_abc")));
    assertEquals("qits_tok_abc", TokenValue.fromBasic(encode(":qits_tok_abc")));
    assertEquals(
        "qits_tok_a:b", TokenValue.fromBasic(encode("u:qits_tok_a:b")), "split at the FIRST colon");
    assertNull(TokenValue.fromBasic(encode("a-client:a-secret")), "a client secret is not one");
    assertNull(TokenValue.fromBasic(encode("oauth2:header.payload.signature")), "nor a JWT");
    assertNull(TokenValue.fromBasic(encode("qits_tok_abc")), "no colon, no password");
    assertNull(TokenValue.fromBasic(encode("qits_tok_abc:secret")), "the USER is not looked at");
    assertNull(TokenValue.fromBasic("!!not-base64"));
    assertNull(TokenValue.fromBasic(""));
    assertNull(TokenValue.fromBasic(null));
  }

  @Test
  void aTokensAnswerOutlivesNeitherTheCeilingNorTheJwtItHolds() {
    // The same rule as a Basic credential's, fed from introspection's relative expiresIn.
    long now = 1_000_000L;
    assertEquals(
        now + 15_000,
        EdgeAuth.tokenBelieveUntil(now, 15_000, 300, 60_000),
        "the ceiling binds while the JWT has life to spare — the revocation bound");
    assertEquals(
        now + 40_000,
        EdgeAuth.tokenBelieveUntil(now, 300_000, 100, 60_000),
        "and the JWT's own life, less the margin, otherwise");
    assertEquals(
        now,
        EdgeAuth.tokenBelieveUntil(now, 15_000, 30, 60_000),
        "a JWT already inside the margin is introspected again rather than handed on dying");
    assertEquals(
        now,
        EdgeAuth.tokenBelieveUntil(now, 15_000, -5, 60_000),
        "and never a time in the past, which a hit would read as live");
  }

  @Test
  void theIntrospectionCredentialIsBothHalvesOrNone() {
    assertEquals(
        "Basic " + encode("an-edge:an-edge-secret"),
        IdpIntrospection.basicAuthorization("an-edge", "an-edge-secret"));
    assertNull(IdpIntrospection.basicAuthorization("an-edge", null));
    assertNull(IdpIntrospection.basicAuthorization(null, "an-edge-secret"));
    assertNull(IdpIntrospection.basicAuthorization("an-edge", " "));
  }

  @Test
  void theRemintMarginIsSixtySeconds() {
    // Sixty seconds before its expiry a cached token is treated as spent, so what is forwarded
    // still has a working life upstream.
    assertEquals(60_000, EdgeAuth.TOKEN_MARGIN_MS);
  }

  // --- the patience the identity provider is given ----------------------------------------------

  @Test
  void onlyTheConnectionIsWaitedOut() {
    // An ANSWER from idp is idp deciding, and it arrives as a Grant rather than a failure — so the
    // only thing this classifies is the network, and every shape of it is safe to repeat.
    assertTrue(IdpGrants.connectionClassed(new java.net.ConnectException("Connection refused")));
    assertTrue(IdpGrants.connectionClassed(new java.net.UnknownHostException("qits-platform-idp")));
    assertTrue(IdpGrants.connectionClassed(new java.util.concurrent.TimeoutException()));
    assertTrue(
        IdpGrants.connectionClassed(
            new RuntimeException(new java.io.IOException("Connection reset by peer"))));
    // Vert.x reports these two as a plain exception with no cause, so the message is the only
    // evidence there is.
    assertTrue(IdpGrants.connectionClassed(new RuntimeException("Connection was closed")));
    assertTrue(IdpGrants.connectionClassed(new RuntimeException("The timeout period elapsed")));
    assertFalse(IdpGrants.connectionClassed(new IllegalStateException("not a network problem")));
  }

  @Test
  void theWaitBetweenTriesDoublesAndIsCapped() {
    assertEquals(IdpGrants.FIRST_BACKOFF_MS, IdpGrants.backoffMs(0));
    assertEquals(IdpGrants.FIRST_BACKOFF_MS * 2, IdpGrants.backoffMs(1));
    assertEquals(IdpGrants.FIRST_BACKOFF_MS * 4, IdpGrants.backoffMs(2));
    assertEquals(IdpGrants.BACKOFF_CAP_MS, IdpGrants.backoffMs(30), "a long window is still tries");
  }

  @Test
  void theShippedTimeBoundsAreTheDeploymentsAndNotTheSuites() throws Exception {
    // The suite shrinks all three so its own tests are quick. What a deployment gets is here, and
    // the call timeout is the one that matters most: without it there is no answer at all.
    assertEquals("5000", shippedDefault("idpCallTimeoutMs"));
    assertEquals("45000", shippedDefault("idpRetryWindowMs"));
    assertEquals("300000", shippedDefault("basicCacheTtlMs"));
    assertEquals("1024", shippedDefault("basicCacheSize"));
    // A revoked token's afterlife at the edge, and the bound on how many are held.
    assertEquals("15000", shippedDefault("tokenCacheTtlMs"));
    assertEquals("1024", shippedDefault("tokenCacheSize"));
  }

  // --- the browser gate's own decisions ----------------------------------------------------------

  @Test
  void theBrowserGateShipsOffAndWithTheContractedNames() throws Exception {
    // OFF is the rollout plan: the gate lands before idp can issue a session and before the
    // gateway can read the headers, so it ships inert and is flipped as a step of its own.
    assertEquals("false", sessionDefault("enabled"));
    // The cookie and the path are a contract with qits-platform-idp and its SPA, so they are pinned
    // here rather than left to a deployment to keep in step.
    assertEquals("qits-session", sessionDefault("cookieName"));
    // Neither the return hosts nor the canonical origin is configuration any more: both are derived
    // from the one stated domain. A clone therefore admits `localhost:8080` and everything the
    // grammar can build under it, with no key to keep in step with the project set.
    assertThrows(
        NoSuchMethodException.class,
        () -> SessionsConfig.class.getMethod("browserHosts"),
        "browser-hosts is derived now and must not come back as a key");
    assertThrows(
        NoSuchMethodException.class,
        () -> SessionsConfig.class.getMethod("canonicalOrigin"),
        "the canonical origin is derived from the stated domain and must not come back as a key");
    // The derivation a clone gets, from the shipped default domain alone.
    String canonical = EdgeSessions.canonicalOrigin(EdgeRouter.domain("localhost"), 8080);
    assertEquals("http://qits.localhost:8080", canonical);
    String apex =
        EdgeRouter.domain("localhost")
            + EnvironmentAuthority.port(URI.create(canonical).getAuthority());
    assertEquals("localhost:8080", apex);
    assertTrue(
        EdgeSessions.browserHost("projects.qits.localhost:8080", Set.of(apex), List.of(apex)),
        "and it admits an application of the platform's own project without naming the project");
    assertEquals("/idp/login", sessionDefault("loginPath"));
    assertEquals("/idp/", sessionDefault("anonymousPrefixes"));
    assertEquals("30000", sessionDefault("cacheTtlMs"));
    assertEquals("1024", sessionDefault("cacheSize"));
    assertEquals("60000", sessionDefault("staleGraceMs"));
  }

  @Test
  void theEdgesOwnIdpCredentialHasNoDefault() throws Exception {
    // A credential is a deployment fact. The deployer injects QITS_RESOURCE_IDP_CLIENT_ID/_SECRET,
    // and a value here would be a client id every installation shared.
    assertNull(IdpConfig.class.getMethod("clientId").getAnnotation(WithDefault.class));
    assertNull(IdpConfig.class.getMethod("clientSecret").getAnnotation(WithDefault.class));
  }

  // --- the session client is the idp:client resource alone (epic qits-540 dossier, 'Plan (as of
  // 2026-09-13)', C4/D6/D7) ----------------------------------------------------------------------

  @Test
  void theSessionClientReadsOnlyTheResourceVars() throws Exception {
    // Read off the SHIPPED application.properties expression, not a copy of it, so a rewritten
    // expression fails this test rather than only production.
    assertEquals(Optional.empty(), sessionClientId(Map.of()), "unset is absent");
    assertEquals(
        Optional.of("dev-qits-edge"),
        sessionClientId(Map.of("QITS_RESOURCE_IDP_CLIENT_ID", "dev-qits-edge")));
    assertEquals(
        Optional.empty(),
        sessionClientId(Map.of("QITS_EDGE_SESSIONS_CLIENT_ID", "dev-qits-edge")),
        "the bootstrap-era name is no fallback any more (qits-540)");
  }

  @Test
  void theSessionSecretReadsOnlyTheResourceVar() throws Exception {
    assertEquals(Optional.empty(), sessionClientSecret(Map.of()));
    assertEquals(
        Optional.of("new-secret"),
        sessionClientSecret(Map.of("QITS_RESOURCE_IDP_CLIENT_SECRET", "new-secret")));
    assertEquals(
        Optional.empty(),
        sessionClientSecret(Map.of("QITS_EDGE_SESSIONS_CLIENT_SECRET", "old-secret")),
        "the bootstrap-era name is no fallback any more (qits-540)");
  }

  /**
   * THE DEPLOYER'S idp CLIENT WINS FOR REAL, with every variable dev-qits-edge carried on
   * 2026-10-03 injected (qits-163, qits-540). The older names are the environment spelling of
   * {@code qits.edge.sessions.client-*}, which is why the client is not keyed there: the
   * environment source would answer those keys with the OLD secret before any expression was read,
   * idp would refuse it, and the edge's introspection would break. Resolved through a real
   * environment source, the way a container resolves it — and the gate's own variable, {@code
   * QITS_EDGE_SESSIONS_ENABLED}, still binds through that same source.
   */
  @Test
  void theLiveEnvironmentResolvesTheDeployersIdpClientAndKeepsTheGate() throws Exception {
    Map<String, String> live =
        Map.of(
            "QITS_RESOURCE_IDP_CLIENT_ID", "dev-qits-edge",
            "QITS_RESOURCE_IDP_CLIENT_SECRET", "database-secret",
            "QITS_RESOURCE_IDP_URL", "http://dev-qits-idp:8080/idp",
            "QITS_EDGE_SESSIONS_CLIENT_ID", "stale-edge",
            "QITS_EDGE_SESSIONS_CLIENT_SECRET", "environment-secret",
            "QITS_EDGE_SESSIONS_ENABLED", "true");
    assertEquals(Optional.of("database-secret"), sessionClientSecret(live));
    assertEquals(Optional.of("dev-qits-edge"), sessionClientId(live));
    assertEquals("http://dev-qits-idp:8080/idp", dialUrl(live), "the alias, never the resource");
    assertTrue(
        applicationProperties(live)
            .withMapping(SessionsConfig.class)
            .build()
            .getConfigMapping(SessionsConfig.class)
            .enabled(),
        "QITS_EDGE_SESSIONS_ENABLED is the env spelling of qits.edge.sessions.enabled and still"
            + " turns the gate on");
  }

  /** A stated QITS_IDP_DIAL_URL moves nothing (qits-540). */
  @Test
  void anOldEnvironmentDialUrlIsNotRead() throws Exception {
    assertEquals(
        "http://dev-qits-idp:8080/idp",
        dialUrl(Map.of("QITS_IDP_DIAL_URL", "http://stated:8080/idp")),
        "alone it is not a fallback either");
  }

  /**
   * THE EDGE'S OWN DIAL STAYS INTERNAL (qits-1403, exception E1). The deployer makes {@code
   * QITS_RESOURCE_IDP_URL} idp's PUBLIC name for every service — a name this process serves — so
   * reading it would have the edge dial its own gate for every key fetch and introspection.
   */
  @Test
  void thePublicIdpResourceDoesNotMoveTheEdgesDial() throws Exception {
    assertEquals(
        "http://dev-qits-idp:8080/idp",
        dialUrl(
            Map.of(
                "QITS_ENVIRONMENT", "dev",
                "QITS_RESOURCE_IDP_URL", "https://idp.qits.wohlben.eu/idp")));
    assertEquals(
        "http://stated:8080/idp",
        dialUrl(
            Map.of(
                "QITS_RESOURCE_IDP_URL", "https://idp.qits.wohlben.eu/idp",
                "QITS_EDGE_IDP_DIAL_URL", "http://stated:8080/idp")),
        "the key's own environment spelling is the one override");
  }

  /**
   * QITS-EVENTS AND QITS-OBSERVABILITY ARE DIALLED ON THEIR INTERNAL ALIASES (qits-1403, E1). The
   * eventstream jar's default for {@code qits.events.url} will move to the public name — through
   * this process, whose own catch-up waits for qits-events — so the shipped file pins the alias,
   * above the jar's ordinal 100.
   */
  @Test
  void theShippedFilePinsTheInternalEventsAndObservabilityAddresses() throws Exception {
    var live = applicationProperties(Map.of("QITS_ENVIRONMENT", "dev")).build();
    assertEquals("http://dev-qits-events:8080", live.getValue("qits.events.url", String.class));
    assertEquals(
        "http://dev-qits-observability:8080",
        live.getValue("qits.observability.url", String.class));

    var outranked =
        applicationProperties(Map.of("QITS_ENVIRONMENT", "dev"))
            .withSources(
                new PropertiesConfigSource(
                    Map.of("qits.events.url", "https://events.qits.wohlben.eu"), "jar", 100))
            .build();
    assertEquals(
        "http://dev-qits-events:8080",
        outranked.getValue("qits.events.url", String.class),
        "a library default that moves to the public name does not move the edge");
  }

  /**
   * THE ISSUER IS NEVER A CONFIGURATION KEY OR A PROPERTIES DEFAULT (qits-730). It is derived in
   * {@link Idp} from the domain; a shipped value spelling the accepted issuer would be the second
   * place it is stated, and the first step back to reading it from configuration.
   */
  @Test
  void theShippedFileStatesNoIssuer() throws Exception {
    PropertiesConfigSource shipped = new PropertiesConfigSource(shippedUrl(), 250);
    for (String name : shipped.getPropertyNames()) {
      String value = shipped.getValue(name);
      assertFalse(
          value.contains("idp.qits."),
          name + " states an issuer; it is derived from the domain, never configured: " + value);
    }
  }

  /**
   * THE idp:client ADDRESS IS DIALLED, NEVER DEMANDED AS {@code iss} — the qits-162 regression.
   *
   * <p>The deployer injects {@code QITS_RESOURCE_IDP_URL=http://dev-qits-idp:8080/idp} once a
   * deployment declares {@code idp:client}. Reading it as the issuer made the edge demand that
   * string while idp stamps another, and every machine JWT on the estate was refused. This resolves
   * the shipped file under exactly that environment and feeds the result to a real {@link Idp}.
   */
  @Test
  void theStatedIdpAddressMovesTheDialBaseAndNeverBecomesAnIssuer() throws Exception {
    Map<String, String> env = Map.of("QITS_EDGE_IDP_DIAL_URL", "http://dev-qits-idp:8080/idp");
    Idp idp = new Idp();
    idp.configuredDial = dialUrl(env);

    assertEquals("http://dev-qits-idp:8080/idp", idp.dialBase());
    assertEquals(
        "http://dev-qits-idp:8080/idp/.well-known/openid-configuration", idp.discoveryUri());
    assertFalse(
        Idp.issuers("localhost").contains(idp.dialBase()),
        "the iss claim idp stamps does not move because the deployer injected an address");
  }

  /**
   * THE SHIPPED DIAL ADDRESS RESOLVES, AND IT IS NOT AN ISSUER.
   *
   * <p>{@code qits.edge.idp.dial-url}'s default is a nested expression — an env name whose fallback
   * is itself an expression — so a malformed one would not be a wrong value but a config expansion
   * that throws at startup, which on this service is the platform's only published listener failing
   * to boot and the deploy rolling back. {@code IdpTest} cannot catch that: it constructs {@link
   * Idp} with values it supplies. This reads the shipped file with expansion on, which is the only
   * place the default's own text is exercised.
   */
  @Test
  void theShippedDialAddressDerivesTheEnvironmentAndIsNotAnIssuer() throws Exception {
    assertEquals("http://dev-qits-idp:8080/idp", dialUrl(Map.of()));
    assertFalse(
        Idp.issuers("localhost").contains(dialUrl(Map.of())),
        "the issuer is the iss claim and the dial-url is an address; shipping them equal is the"
            + " re-merge the split exists to prevent");

    assertEquals(
        "http://prod-qits-idp:8080/idp",
        dialUrl(Map.of("QITS_ENVIRONMENT", "prod")),
        "the environment is derived from what qits-deployments injects, not written down");
    assertEquals(
        "http://stated:8080/idp",
        dialUrl(Map.of("QITS_EDGE_IDP_DIAL_URL", "http://stated:8080/idp")),
        "and the key's own environment spelling states it outright");
  }

  /**
   * THE PACKAGED EDGE NEEDS NO EDGE VARIABLES TO SERVE ITS DEPLOYMENT (qits-1391). A deployment
   * used to hand it {@code QITS_EDGE_ENVIRONMENTS}, {@code QITS_EDGE_DEFAULT_ENVIRONMENT} and
   * {@code QITS_EDGE_SESSIONS_ENABLED}; with only the deployer's own {@code QITS_ENVIRONMENT} the
   * shipped file must land on the same values — the environment it was deployed into, and the
   * browser gate on. Read without a profile, which is the packaged process's view of the file.
   */
  @Test
  void theShippedFileServesTheDeployedEnvironmentWithTheGateOn() throws Exception {
    var live = applicationProperties(Map.of("QITS_ENVIRONMENT", "dev")).build();
    assertEquals("dev", live.getValue("qits.edge.environments", String.class));
    assertEquals("dev", live.getValue("qits.edge.default-environment", String.class));
    assertTrue(live.getValue("qits.edge.sessions.enabled", Boolean.class));

    var local = applicationProperties(Map.of()).build();
    assertEquals(
        "prod",
        local.getValue("qits.edge.default-environment", String.class),
        "a process with no QITS_ENVIRONMENT keeps the old default");
  }

  private static String dialUrl(Map<String, String> env) throws Exception {
    return applicationProperties(env)
        .build()
        .getOptionalValue("qits.edge.idp.dial-url", String.class)
        .orElseThrow();
  }

  private static Optional<String> sessionClientId(Map<String, String> env) throws Exception {
    return applicationProperties(env)
        .withMapping(IdpConfig.class)
        .build()
        .getConfigMapping(IdpConfig.class)
        .clientId();
  }

  private static Optional<String> sessionClientSecret(Map<String, String> env) throws Exception {
    return applicationProperties(env)
        .withMapping(IdpConfig.class)
        .build()
        .getConfigMapping(IdpConfig.class)
        .clientSecret();
  }

  /**
   * The shipped {@code application.properties}, with expression expansion on (off by default on a
   * bare builder) and a real {@link EnvConfigSource} at its production ordinal standing in for the
   * environment variables a deployment would set.
   *
   * <p><b>A real one, not a properties map at ordinal 300</b> (qits-163). An environment source
   * does more than hold values: it answers a dotted key from the variable that spells it ({@code
   * qits.edge.sessions.client-secret} from {@code QITS_EDGE_SESSIONS_CLIENT_SECRET}), outranking
   * this file. A plain map does not, which is exactly how the shadowing that broke the edge's
   * introspection passed every precedence test here.
   *
   * <p>The test JVM's classpath carries a SECOND {@code application.properties} — this repository's
   * own, under {@code src/test/resources} — so {@code getResource} alone cannot be trusted to pick
   * the shipped one: the two shadow each other in classpath order rather than merging. This picks
   * the copy that actually defines {@code qits.edge.idp.dial-url}, which only the shipped one does.
   */
  private static SmallRyeConfigBuilder applicationProperties(Map<String, String> env)
      throws Exception {
    return new SmallRyeConfigBuilder()
        .addDefaultInterceptors()
        .withSources(new PropertiesConfigSource(shippedUrl(), 250))
        .withSources(new EnvConfigSource(env, 300));
  }

  private static java.net.URL shippedUrl() throws Exception {
    var urls = EdgeChallengeTest.class.getClassLoader().getResources("application.properties");
    java.net.URL shipped = null;
    while (urls.hasMoreElements()) {
      java.net.URL candidate = urls.nextElement();
      if (new PropertiesConfigSource(candidate, 250).getValue("qits.edge.idp.dial-url") != null) {
        shipped = candidate;
        break;
      }
    }
    if (shipped == null) {
      throw new IllegalStateException(
          "no application.properties on the test classpath defines qits.edge.idp.dial-url");
    }
    return shipped;
  }

  @Test
  void theSessionCookieIsReadOutOfEverythingABrowserSends() {
    // A browser sends every cookie it holds for the host, in whatever order, with spaces after the
    // semicolons — and a server is allowed to quote a value.
    assertEquals("abc", EdgeSessions.cookieValue("qits-session=abc", "qits-session"));
    assertEquals(
        "abc", EdgeSessions.cookieValue("theme=dark; qits-session=abc; tz=CET", "qits-session"));
    assertEquals("abc", EdgeSessions.cookieValue("qits-session=\"abc\"", "qits-session"));
    assertNull(EdgeSessions.cookieValue("theme=dark", "qits-session"));
    assertNull(EdgeSessions.cookieValue("qits-session=", "qits-session"));
    assertNull(EdgeSessions.cookieValue(null, "qits-session"));
    // Cookie names are case-sensitive, so this is a different cookie and not this one.
    assertNull(EdgeSessions.cookieValue("QITS-SESSION=abc", "qits-session"));
  }

  @Test
  void browserReturnHostsAreAuthoritiesAndNothingElse() {
    // Whatever a caller's Host header carries, it is read as an authority or as nothing: the
    // matcher never sees a URL, a path, user-info or a query.
    assertEquals("wohlben.eu", EdgeSessions.authority("WOHLBEN.eu"));
    assertEquals(
        "ci.dev.acme.wohlben.eu:8443", EdgeSessions.authority("ci.dev.acme.wohlben.eu:8443"));
    assertNull(EdgeSessions.authority("https://evil.example"));
    assertNull(EdgeSessions.authority("evil.example/path"));
    assertNull(EdgeSessions.authority("user@evil.example"));
    assertNull(EdgeSessions.authority("evil.example?not-an-authority"));
  }

  @Test
  void oneWildcardOnTheStatedDomainCoversTheWholeGrammar() {
    // The whole matcher, and the two derived entries it is given. The names are read right to left
    // — `<app>[.<env>].<project>.<domain>` — so the deepest legal name is THREE labels in front of
    // the stated domain, and one wildcard anchored there covers every project and every
    // environment with no knowledge of either. What keeps a foreign origin out is the anchor, not
    // the label count; the bound is the grammar's own depth.
    String apex = EdgeRouter.domain("wohlben.eu");
    assertEquals("wohlben.eu", apex);
    Set<String> exact = Set.of(apex);
    List<String> wildcards = List.of(apex);

    assertTrue(EdgeSessions.browserHost("wohlben.eu", exact, wildcards), "the domain itself");
    assertTrue(
        EdgeSessions.browserHost("qits.wohlben.eu", exact, wildcards),
        "ONE label: a project's own door");
    assertTrue(
        EdgeSessions.browserHost("projects.qits.wohlben.eu", exact, wildcards),
        "TWO: an env-less project's application — the live sign-in this derivation fixes");
    assertTrue(
        EdgeSessions.browserHost("dev.qits.wohlben.eu", exact, wildcards),
        "TWO the other way: an environment's own door");
    assertTrue(
        EdgeSessions.browserHost("projects.dev.qits.wohlben.eu", exact, wildcards),
        "THREE: an env-ful project's application, the deepest name the grammar makes");
    assertTrue(
        EdgeSessions.browserHost("githost.dev.gizmo.wohlben.eu", exact, wildcards),
        "any project, without this process being told the project set");
    // FOUR labels is a name the grammar could not have produced, so it is refused even though it
    // is under the domain — the bound is the grammar's depth.
    assertFalse(
        EdgeSessions.browserHost("a.b.c.d.wohlben.eu", exact, wildcards),
        "four labels, not a name");
    // A different domain is the case the anchor exists for.
    assertFalse(
        EdgeSessions.browserHost("projects.qits.evil.example", exact, wildcards),
        "a foreign origin, which is the whole reason this check exists");
    assertFalse(
        EdgeSessions.browserHost("wohlben.eu.evil.example", exact, wildcards),
        "the domain is a suffix of this name and it is still a different site to a browser");
    // The port is part of the authority on both sides, so a name on another port matches nothing.
    assertFalse(EdgeSessions.browserHost("qits.wohlben.eu:8443", exact, wildcards));
    assertFalse(EdgeSessions.browserHost(null, exact, wildcards));
  }

  @Test
  void corsSharesTheAnchorAtAnyDepth() {
    // The same matcher behind EdgeCors, unbounded: the owner's ruling admits every origin under
    // the domain, not only the grammar's names. The anchor is unchanged, so a foreign site is
    // refused exactly as a foreign login return is.
    String apex = EdgeRouter.domain("wohlben.eu");
    Set<String> exact = Set.of(apex);
    List<String> wildcards = List.of(apex);
    int any = Integer.MAX_VALUE;
    assertTrue(EdgeSessions.underDomain("a.b.c.d.wohlben.eu", exact, wildcards, any));
    assertTrue(EdgeSessions.underDomain("wohlben.eu", exact, wildcards, any));
    assertFalse(EdgeSessions.underDomain("wohlben.eu.evil.example", exact, wildcards, any));
    assertFalse(EdgeSessions.underDomain("evilwohlben.eu", exact, wildcards, any));
    assertFalse(EdgeSessions.underDomain(".wohlben.eu", exact, wildcards, any));
    assertFalse(EdgeSessions.underDomain("qits.wohlben.eu:8443", exact, wildcards, any));
  }

  @Test
  void aLoopbackOriginIsExactlyLocalhostOrTheLoopbackAddressOverHttp() {
    for (String admitted :
        List.of(
            "http://localhost:4200",
            "http://localhost",
            "http://127.0.0.1:4200",
            "http://localhost:65535")) {
      assertTrue(EdgeCors.isLoopback(admitted), admitted);
    }
    for (String refused :
        List.of(
            "https://localhost:4200",
            "http://localhost.evil.example",
            "http://localhost:4200.evil.example",
            "http://localhost:4200/",
            "http://localhost:",
            "http://localhost:65536",
            "http://localhost:0x10",
            "http://127.0.0.1.evil.example",
            "http://127.0.0.10:4200",
            "http://evil.localhost:4200",
            "http://user@localhost:4200",
            "null")) {
      assertFalse(EdgeCors.isLoopback(refused), refused);
    }
    assertFalse(EdgeCors.isLoopback(null));
  }

  @Test
  void theCanonicalOriginIsThePlatformProjectsDoorAndNotTheApex() {
    // The whole of the derivation, and the bug it fixes. The apex composes no application name —
    // every address carries a project label and the apex carries none — so a refused login used to
    // be sent to `https://wohlben.eu`, which this edge serves as a door and answers 404. The
    // platform's own project is a constant of the platform, not a configured name.
    assertEquals("qits", EdgeSessions.PLATFORM_PROJECT);
    assertEquals(
        "https://qits.wohlben.eu",
        EdgeSessions.canonicalOrigin(EdgeRouter.domain("wohlben.eu"), 8080));
    assertEquals(
        "https://qits.example.co.uk",
        EdgeSessions.canonicalOrigin(EdgeRouter.domain("example.co.uk"), 8080),
        "a two-label domain is still one stated value and gets one label in front of it");

    // And the local shape, where there is no real domain: one listener is the whole platform, so
    // the port is part of the origin and an origin without it names nothing.
    assertEquals(
        "http://qits.localhost:8080",
        EdgeSessions.canonicalOrigin(EdgeRouter.domain("localhost"), 8080));
    assertEquals(
        "http://qits.localhost:9000",
        EdgeSessions.canonicalOrigin(EdgeRouter.domain("  LOCALHOST  "), 9000),
        "whatever port this process actually listens on");
  }

  @Test
  void theDerivationCarriesTheCanonicalOriginsPort() {
    // A local clone serves on :8080, and the port is part of the authority on both sides — a
    // derivation that dropped it would match nothing at all, silently.
    String canonical =
        URI.create(EdgeSessions.canonicalOrigin(EdgeRouter.domain("localhost"), 8080))
            .getAuthority();
    assertEquals("qits.localhost:8080", canonical);
    String apex = EdgeRouter.domain("localhost") + EnvironmentAuthority.port(canonical);
    assertEquals("localhost:8080", apex);
    assertTrue(EdgeSessions.browserHost(canonical, Set.of(apex), List.of(apex)), "the door itself");
    assertTrue(
        EdgeSessions.browserHost("projects.dev.qits.localhost:8080", Set.of(apex), List.of(apex)),
        "and the deepest name a clone can serve");
    assertFalse(
        EdgeSessions.browserHost("projects.qits.localhost", Set.of(apex), List.of(apex)),
        "portless is another authority");
  }

  @Test
  void onlyARequestThatCouldRenderALoginPageIsRedirected() {
    // Sec-Fetch-Mode answers it whenever it is there: `navigate` is the one value that means a
    // document is being loaded, and everything else is a request made by a page that already
    // exists.
    assertTrue(EdgeSessions.isNavigation(HttpMethod.GET, "navigate", "text/html"));
    assertTrue(EdgeSessions.isNavigation(HttpMethod.POST, "navigate", null), "a form post is one");
    assertFalse(EdgeSessions.isNavigation(HttpMethod.GET, "cors", "text/html"));
    assertFalse(EdgeSessions.isNavigation(HttpMethod.GET, "websocket", "text/html"));
    assertFalse(EdgeSessions.isNavigation(HttpMethod.GET, "no-cors", null));
    // With no header at all — curl, an old client — the method and Accept are what a navigation
    // looked like before the header existed.
    assertTrue(
        EdgeSessions.isNavigation(
            HttpMethod.GET, null, "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8"));
    assertFalse(EdgeSessions.isNavigation(HttpMethod.GET, null, "application/json"));
    assertFalse(EdgeSessions.isNavigation(HttpMethod.GET, null, "text/event-stream"));
    assertFalse(EdgeSessions.isNavigation(HttpMethod.POST, null, "text/html"));
    assertFalse(EdgeSessions.isNavigation(HttpMethod.GET, null, null));
  }

  @Test
  void theRedirectTargetCanOnlyEverBeAPathOnThisHost() {
    // An open redirect through the platform's own login page: the value is where a browser is sent
    // AFTER authenticating, so anything that can name another origin turns login into a
    // redirector.
    assertEquals("/projects/7?tab=runs", EdgeSessions.redirectTarget("/projects/7?tab=runs"));
    assertEquals("/", EdgeSessions.redirectTarget("//evil.example.com/steal"));
    assertEquals("/", EdgeSessions.redirectTarget("https://evil.example.com/steal"));
    // A backslash is a slash to every browser's URL parser, whatever the RFC says.
    assertEquals("/", EdgeSessions.redirectTarget("/\\evil.example.com"));
    assertEquals("/", EdgeSessions.redirectTarget("\\\\evil.example.com"));
    // A carriage return in a header value is a second header.
    assertEquals("/", EdgeSessions.redirectTarget("/ok\r\nLocation: http://evil.example.com"));
    assertEquals("/", EdgeSessions.redirectTarget(null));
    assertEquals("/", EdgeSessions.redirectTarget(""));
  }

  @Test
  void theAnonymousCarveOutIsAPrefixAndNothingElse() {
    List<String> idp = List.of("/idp/");
    assertTrue(EdgeSessions.anonymous("/idp/login", idp));
    assertTrue(EdgeSessions.anonymous("/idp/assets/main-ab12cd.js", idp));
    assertTrue(EdgeSessions.anonymous("/idp/api/auth/login", idp));
    assertFalse(EdgeSessions.anonymous("/idpsomething", idp), "the slash is part of the prefix");
    assertFalse(EdgeSessions.anonymous("/projects", idp));
    assertFalse(EdgeSessions.anonymous(null, idp));
    assertEquals(List.of("/idp/"), EdgeSessions.prefixes(List.of(" /idp/ ", "", "  ")));
  }

  @Test
  void aRoleSetBecomesOneHeaderValueThatCannotBeSplit() {
    // Comma-separated is safe because a role is $app:$resource:$role and holds no comma. One that
    // somehow did would arrive downstream as TWO roles, so it is dropped rather than carried.
    assertEquals("qits:admin", EdgeSessions.rolesHeader(new JsonArray(List.of("qits:admin"))));
    assertEquals("", EdgeSessions.rolesHeader(new JsonArray()));
    assertEquals("", EdgeSessions.rolesHeader(null));
    assertEquals(
        "qits:admin",
        EdgeSessions.rolesHeader(new JsonArray(List.of("qits:admin", "smuggled,qits:root"))));
    assertEquals(
        "qits:admin",
        EdgeSessions.rolesHeader(new JsonArray(List.of("qits:admin", "a\r\nX-Qits-User: root"))));
  }

  @Test
  void anIdentityWithNoUsableNameIsRefusedRatherThanSanitised() {
    // Both fields go into headers an upstream believes unconditionally. A strange answer must never
    // become a strange identity, and nothing about the platform's own idp sends one.
    assertNotNull(EdgeSessions.read("{\"userId\":\"an-id\",\"username\":\"operator\"}"));
    assertNull(EdgeSessions.read("{\"userId\":\"an-id\"}"));
    assertNull(EdgeSessions.read("{\"username\":\"operator\"}"));
    assertNull(EdgeSessions.read("{\"userId\":\"an-id\",\"username\":\"a\\r\\nb\"}"));
    assertNull(EdgeSessions.read("not json at all"));
  }

  @Test
  void aSessionWithNoReadableExpiryIsStillBelievedForTheCachesOwnWindow() {
    // idp has just said the session is good; a date this process could not parse is not a reason to
    // log somebody out, and the cache TTL bounds the belief either way.
    assertEquals(
        Long.MAX_VALUE,
        EdgeSessions.read("{\"userId\":\"an-id\",\"username\":\"operator\"}").expiresAtMillis());
    assertEquals(
        java.time.Instant.parse("2030-01-01T00:00:00Z").toEpochMilli(),
        EdgeSessions.read(
                "{\"userId\":\"an-id\",\"username\":\"operator\","
                    + "\"expiresAt\":\"2030-01-01T00:00:00Z\"}")
            .expiresAtMillis());
  }

  private static String shippedDefault(String key) throws Exception {
    return AuthConfig.class.getMethod(key).getAnnotation(WithDefault.class).value();
  }

  private static String sessionDefault(String key) throws Exception {
    return SessionsConfig.class.getMethod(key).getAnnotation(WithDefault.class).value();
  }

  private static String encode(String plain) {
    return java.util.Base64.getEncoder()
        .encodeToString(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static EdgeConfig.App hosts(Map<String, String> hosts) {
    return () -> hosts;
  }

  private static HostEnvironments.Route app(String app, String environment) {
    return new HostEnvironments.Route(environment, app, null);
  }
}
