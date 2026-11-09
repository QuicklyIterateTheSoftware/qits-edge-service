package eu.wohlben.qits.edge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Everything the private edge is, derived from one fact: {@code QITS_VARIANT=private}, which
 * qits-deployments injects into a variant's container (epic qits-1976). A main deployment has no
 * {@code QITS_VARIANT}, and then this source is empty and the edge is exactly the public one.
 *
 * <p><b>Why a source and not deployment settings.</b> The private edge's values are code, not
 * per-install configuration: its class, its tunnel, its listener, its ports and the certificate it
 * serves follow from being the private variant, and settings would be a second place to get them
 * wrong (owner ruling, 2026-11-09).
 *
 * <p><b>Ordinal 275</b>: above {@code application.properties} (250), so it beats a {@code %prod}
 * line there too — SmallRye prefers an unprofiled value from a higher-ordinal source over a
 * profiled one from a lower — and below environment variables (300), so an operator can still
 * override any single value. {@code qits.edge.acme.enabled=false} is stated explicitly for that
 * reason: the private edge orders nothing, it serves the public edge's certificate read-only.
 *
 * <p><b>Read at runtime, every time.</b> Registered through {@code
 * META-INF/services/org.eclipse.microprofile.config.spi.ConfigSource}, which Quarkus discovers at
 * build time and instantiates in the native image too; the variable is read in each call and never
 * cached, so whatever the image build saw (no {@code QITS_VARIANT}) cannot leak into the running
 * process. It is deliberately not {@code @StaticInitSafe}.
 */
public class PrivateVariantConfigSource implements ConfigSource {

  /** The variable qits-deployments sets on a variant's container. */
  static final String VARIANT = "QITS_VARIANT";

  /** The variant this source answers for. */
  static final String PRIVATE = "private";

  static final int ORDINAL = 275;

  /** The private edge's values, in the order they are documented in README.md. */
  static final Map<String, String> VALUES;

  static {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("qits.edge.exposure", "private");
    values.put("qits.edge.wireguard.enabled", "true");
    values.put("qits.edge.wireguard.listen-port", "47812");
    values.put("qits.edge.dns.enabled", "true");
    values.put("qits.edge.listen.allowed-local-cidrs", "100.96.0.0/24,10.231.0.0/20");
    values.put("qits.edge.acme.enabled", "false");
    values.put("quarkus.http.port", "80");
    values.put("quarkus.http.ssl-port", "443");
    // The public edge's own TLS settings, QUARKUS_TLS_KEY_STORE_PEM_ACME_CERT/_KEY and
    // QUARKUS_TLS_RELOAD_PERIOD: the default TLS configuration's PEM pair named `acme`, which the
    // HTTP server uses because it names no tls-configuration-name. The files are the public edge's,
    // on its letsencrypt volume mounted read-only; the reload follows its renewals.
    values.put(
        "quarkus.tls.key-store.pem.acme.cert", "/work/.letsencrypt/current/lets-encrypt.crt");
    values.put("quarkus.tls.key-store.pem.acme.key", "/work/.letsencrypt/current/lets-encrypt.key");
    values.put("quarkus.tls.reload-period", "1m");
    VALUES = Collections.unmodifiableMap(values);
  }

  private final Supplier<String> variant;

  /** The source Quarkus instantiates: it reads the process environment. */
  public PrivateVariantConfigSource() {
    this(() -> System.getenv(VARIANT));
  }

  PrivateVariantConfigSource(Supplier<String> variant) {
    this.variant = variant;
  }

  private boolean isPrivate() {
    String value = variant.get();
    return value != null && PRIVATE.equals(value.strip());
  }

  @Override
  public Map<String, String> getProperties() {
    return isPrivate() ? VALUES : Map.of();
  }

  @Override
  public Set<String> getPropertyNames() {
    return isPrivate() ? VALUES.keySet() : Set.of();
  }

  @Override
  public String getValue(String propertyName) {
    return isPrivate() ? VALUES.get(propertyName) : null;
  }

  @Override
  public String getName() {
    return "qits-edge private variant (QITS_VARIANT=private)";
  }

  @Override
  public int getOrdinal() {
    return ORDINAL;
  }
}
