package eu.wohlben.qits.edge;

import eu.wohlben.qits.edge.acme.HetznerBearer;
import io.quarkus.oidc.client.OidcClients;
import io.quarkus.oidc.client.Tokens;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Where the edge's Hetzner DNS calls go and what they carry, for both of their users: the
 * certificate's DNS-01 orders ({@link EdgeCertificateManager}) and the zone's address records
 * ({@link HostRecords}).
 *
 * <p><b>The base URL</b> is {@link AcmeConfig#hetznerApiUrl()}: by default the qits-egress
 * gateway's {@code hetzner} route, {@code http://<env>-qits-egress:8081/hetzner/v1/} on qits-net
 * (qits-2523, qits-2617).
 *
 * <p><b>The bearer</b> is decided by whether a Hetzner token is configured ({@link HetznerToken}):
 *
 * <ul>
 *   <li><b>Direct</b> — a token is configured, the file or the value: it is sent as it is. This is
 *       the SEED edge a bootstrap starts from its own stack file, before qits-egress exists; the
 *       stack gives it the swarm secret ({@code QITS_EDGE_ACME_HETZNER_TOKEN_FILE}) and {@code
 *       QITS_EDGE_ACME_HETZNER_API_URL=https://api.hetzner.cloud/v1/}.
 *   <li><b>Gateway</b> — no token: the edge's own {@code qits} oidc-client access token (sub {@code
 *       <env>-qits-edge}), which qits-egress authenticates, strips, and replaces with the Hetzner
 *       token it alone holds ({@code QITS_EGRESS_SECRET_HETZNER_DNS}). This is the deployed edge,
 *       which holds no Hetzner token at all.
 * </ul>
 *
 * <p>No token is therefore not a refusal. An EMPTY token file still is: a mounted secret that came
 * up empty is a broken deployment, and quietly falling to the gateway would hide it.
 *
 * <p>{@code @Singleton}, not {@code @ApplicationScoped}: a client proxy would create this bean
 * lazily on the first DNS call, on a pool thread, and ArC then fails to produce the {@link
 * AcmeConfig} mapping for its constructor ("A synthetic injection point was not declared for
 * required type InjectionPoint", measured in {@code EdgeCertificateDebounceTest}). A singleton is
 * created where it is injected, at boot.
 */
@Singleton
public class HetznerAccess {

  /** How long one token request to idp may take before the DNS call fails and is retried. */
  static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(10);

  private final AcmeConfig acme;
  private final OidcClients oidcClients;
  private volatile Tokens tokens;

  @Inject
  HetznerAccess(AcmeConfig acme, OidcClients oidcClients) {
    this.acme = acme;
    this.oidcClients = oidcClients;
  }

  /** The RRSet API's base, ending in {@code /v1/}. */
  public URI api() {
    String url = acme.hetznerApiUrl().strip();
    return URI.create(url.endsWith("/") ? url : url + "/");
  }

  /**
   * The bearer for this pass, decided now: the token is read on every call, so a rotated file is
   * picked up without a restart.
   *
   * @throws IllegalStateException when the configured token file is empty
   * @throws java.io.IOException when the configured token file cannot be read
   */
  public HetznerBearer bearer() throws Exception {
    Optional<String> direct = HetznerToken.of(acme);
    if (direct.isPresent()) {
      String token = direct.orElseThrow();
      return () -> token;
    }
    return this::qitsToken;
  }

  /** Whether {@link #bearer()} would send a Hetzner token rather than the edge's own. */
  public boolean direct() throws Exception {
    return HetznerToken.of(acme).isPresent();
  }

  /**
   * The edge's own {@code qits} access token, minted by client credentials and kept until it is
   * {@link #MARGIN} from expiring — one token request per lifetime, not one per DNS call (a DNS-01
   * order or a host-record pass makes dozens). A token that states no expiry is not kept.
   */
  private String qitsToken() {
    Tokens held = tokens;
    if (!usable(held, Instant.now())) {
      held = oidcClients.getClient("qits").getTokens().await().atMost(TOKEN_TIMEOUT);
      tokens = held;
    }
    return held.getAccessToken();
  }

  /** How long before its expiry a held token is replaced, so no request leaves with a dying one. */
  static final Duration MARGIN = Duration.ofSeconds(60);

  static boolean usable(Tokens held, Instant now) {
    if (held == null || held.getAccessToken() == null || held.getAccessTokenExpiresAt() == null) {
      return false;
    }
    return now.plus(MARGIN).getEpochSecond() < held.getAccessTokenExpiresAt();
  }
}
