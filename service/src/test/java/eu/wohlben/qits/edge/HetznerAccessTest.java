package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.OidcClients;
import io.quarkus.oidc.client.Tokens;
import io.quarkus.runtime.configuration.DurationConverter;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.mutiny.Uni;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which base URL and which bearer the edge's Hetzner DNS calls get (qits-2617): the seed edge's
 * direct token, or — the deployed edge — the qits-egress gateway with the edge's own qits token.
 */
class HetznerAccessTest {

  @TempDir Path dir;

  /** Every token the fake idp minted, in order, and which client it was asked by. */
  private final List<String> minted = new ArrayList<>();

  private long expiresIn = 300;

  private AcmeConfig acme(Map<String, String> environment) {
    SmallRyeConfig config =
        new SmallRyeConfigBuilder()
            .addDefaultInterceptors()
            .withMapping(AcmeConfig.class)
            .withSources(new EnvConfigSource(environment, 300))
            .withConverter(Duration.class, 200, new DurationConverter())
            .build();
    return config.getConfigMapping(AcmeConfig.class);
  }

  private HetznerAccess access(Map<String, String> environment) {
    return new HetznerAccess(acme(environment), idp());
  }

  /** {@code OidcClients} answering for {@code qits} only, minting a numbered token per request. */
  private OidcClients idp() {
    OidcClient client =
        (OidcClient)
            Proxy.newProxyInstance(
                OidcClient.class.getClassLoader(),
                new Class<?>[] {OidcClient.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("getTokens")) {
                    String token = "qits-jwt-" + (minted.size() + 1);
                    minted.add(token);
                    return Uni.createFrom()
                        .item(
                            new Tokens(
                                token,
                                Instant.now().getEpochSecond() + expiresIn,
                                null,
                                null,
                                null,
                                null,
                                "dev-qits-edge"));
                  }
                  throw new UnsupportedOperationException(method.getName());
                });
    return (OidcClients)
        Proxy.newProxyInstance(
            OidcClients.class.getClassLoader(),
            new Class<?>[] {OidcClients.class},
            (proxy, method, args) -> {
              if (method.getName().equals("getClient")
                  && args != null
                  && args.length == 1
                  && "qits".equals(args[0])) {
                return client;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  @Test
  void theDefaultBaseIsTheEgressGatewaysHetznerRouteInThisEnvironment() {
    assertEquals(URI.create("http://dev-qits-egress:8081/hetzner/v1/"), access(Map.of()).api());
    assertEquals(
        URI.create("http://prod-qits-egress:8081/hetzner/v1/"),
        access(Map.of("QITS_ENVIRONMENT", "prod")).api());
  }

  @Test
  void theSeedEdgesDirectBaseArrivesUnderTheKeyTheBootstrapRenders() {
    // qits-bootstrap-cli's seed stack sets exactly this key (qits-2618).
    assertEquals(
        URI.create("https://api.hetzner.cloud/v1/"),
        access(Map.of("QITS_EDGE_ACME_HETZNER_API_URL", "https://api.hetzner.cloud/v1")).api());
  }

  @Test
  void gatewayModeSendsTheEdgesOwnQitsTokenAndKeepsItUntilItNearsExpiry() throws Exception {
    HetznerAccess access = access(Map.of());

    assertFalse(access.direct());
    var bearer = access.bearer();
    assertEquals("qits-jwt-1", bearer.token());
    assertEquals("qits-jwt-1", bearer.token(), "one token per lifetime, not one per call");
    assertEquals("qits-jwt-1", access.bearer().token(), "and one across passes");
    assertEquals(List.of("qits-jwt-1"), minted);
  }

  @Test
  void aTokenAboutToExpireIsReplacedBeforeItIsSent() throws Exception {
    expiresIn = 30;
    var bearer = access(Map.of()).bearer();

    assertEquals("qits-jwt-1", bearer.token());
    assertEquals("qits-jwt-2", bearer.token());
  }

  @Test
  void noHetznerTokenIsNotARefusal() throws Exception {
    // Neither key: the ordinary deployed edge. Blank reads as absent, as it always did.
    assertFalse(access(Map.of()).direct());
    assertFalse(access(Map.of("QITS_EDGE_ACME_HETZNER_TOKEN", "  ")).direct());
    assertEquals("qits-jwt-1", access(Map.of()).bearer().token());
  }

  @Test
  void directModeSendsTheConfiguredTokenAndAsksIdpForNothing() throws Exception {
    HetznerAccess byValue = access(Map.of("QITS_EDGE_ACME_HETZNER_TOKEN", " not-a-real-token "));
    assertTrue(byValue.direct());
    assertEquals("not-a-real-token", byValue.bearer().token());

    Path file = Files.writeString(dir.resolve("qits-dns-hetzner-token"), "from-the-file\n");
    Map<String, String> both = new HashMap<>();
    both.put("QITS_EDGE_ACME_HETZNER_TOKEN", "by-value");
    both.put("QITS_EDGE_ACME_HETZNER_TOKEN_FILE", file.toString());
    assertEquals("from-the-file", access(both).bearer().token(), "the file wins");

    assertEquals(List.of(), minted);
  }

  @Test
  void anEmptyTokenFileIsStillRefused() throws Exception {
    Path file = Files.writeString(dir.resolve("qits-dns-hetzner-token"), " \n");
    HetznerAccess access = access(Map.of("QITS_EDGE_ACME_HETZNER_TOKEN_FILE", file.toString()));

    IllegalStateException refused = assertThrows(IllegalStateException.class, access::bearer);
    assertTrue(refused.getMessage().contains("empty"), refused.getMessage());
    assertEquals(List.of(), minted, "a broken secret never falls back to the gateway");
  }
}
