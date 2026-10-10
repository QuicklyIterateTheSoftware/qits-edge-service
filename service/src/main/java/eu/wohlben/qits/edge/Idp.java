package eu.wohlben.qits.edge;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Where the idp is, and what it calls itself — two facts, and only one of them is configured.
 *
 * <p>{@link #issuers()} are the {@code iss} values an accepted token may carry, exactly: a consumer
 * that validated a token whose issuer differed by one character would be validating somebody
 * else's. An issuer is a string that is COMPARED, and it does not have to resolve. It is DERIVED
 * here from the platform's one stated domain ({@link EdgeConfig#domain()}, {@code QITS_DOMAIN}) —
 * {@code https://idp.qits.<domain>}, no path, no trailing slash — and it is NEVER a configuration
 * key or a properties default (qits-730): idp derives the same string from the same domain, so the
 * two cannot be configured apart.
 *
 * <p>{@code qits.edge.idp.dial-url} is the address this process actually connects to for discovery,
 * tokens and introspection; every path under it is derived here rather than configured, because the
 * paths belong to idp rather than to a deployment. The key set is the exception: its address is the
 * {@code jwks_uri} of the discovery document.
 *
 * <p><b>WHY THEY ARE TWO, and it is not tidiness.</b> The issuer is stamped into every token in
 * flight and compared for equality by every consumer, so it cannot move with a deployment; the
 * address must. Reading an address as the issuer is exactly what refused every machine token on the
 * estate on 2026-10-02 (qits-162). DO NOT RE-MERGE THEM — and never let the dial address fall back
 * to an issuer, or an issuer be read from configuration.
 */
@ApplicationScoped
public class Idp {

  @Inject EdgeConfig edge;

  @Inject IdpConfig config;

  /** {@link IdpConfig#dialUrl()}, read once; a plain field so a test can state it outright. */
  String configuredDial;

  @PostConstruct
  void read() {
    configuredDial = config.dialUrl();
  }

  /** Every {@code iss} an accepted token may carry: the one derived issuer. */
  public List<String> issuers() {
    return issuers(edge.domain());
  }

  /** The accepted issuers for a stated domain. */
  static List<String> issuers(String domain) {
    return List.of(issuer(domain));
  }

  /** {@code https://idp.qits.<domain>}, the domain normalised the way every other name here is. */
  static String issuer(String domain) {
    return "https://idp.qits." + EdgeRouter.domain(domain);
  }

  /**
   * The base this process CONNECTS to: {@code qits.edge.idp.dial-url}, trimmed of trailing slashes.
   */
  public String dialBase() {
    return trimmed(configuredDial);
  }

  /**
   * {@code <dial>/.well-known/openid-configuration} — idp's discovery document. The edge reads the
   * signing keys from the {@code jwks_uri} it names ({@link IdpKeys}), as every quarkus-oidc tenant
   * on the estate does, rather than composing a key path of its own.
   */
  public String discoveryUri() {
    return dialBase() + "/.well-known/openid-configuration";
  }

  /** {@code <dial>/token} — RFC 6749 {@code client_credentials}, form encoded. */
  public String tokenEndpoint() {
    return dialBase() + "/token";
  }

  /**
   * {@code <dial>/api/sessions/introspect} — a browser session's cookie in, the user it belongs to
   * out. Derived like the two above, from the address rather than from the claim.
   *
   * <p>Under {@code /api} rather than beside {@code /token}: the protocol endpoints are the OIDC
   * ones and this is not an OIDC endpoint. It is idp's own API, guarded by the caller's HTTP Basic
   * client credentials, and it lives where the rest of idp's API does.
   */
  public String introspectionEndpoint() {
    return dialBase() + "/api/sessions/introspect";
  }

  /**
   * {@code <dial>/api/tokens/introspect} — an opaque {@code qits_tok_} token in, the ordinary idp
   * JWT it currently stands for out. The sibling of {@link #introspectionEndpoint()}: the same API,
   * the same caller credential, the same dial address, and a different question.
   */
  public String tokenIntrospectionEndpoint() {
    return dialBase() + "/api/tokens/introspect";
  }

  private String trimmed(String value) {
    String url = value.trim();
    while (url.endsWith("/")) {
      url = url.substring(0, url.length() - 1);
    }
    return url;
  }
}
