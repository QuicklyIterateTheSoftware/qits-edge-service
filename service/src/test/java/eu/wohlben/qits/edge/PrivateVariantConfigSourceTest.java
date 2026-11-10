package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link PrivateVariantConfigSource}: the private edge from QITS_VARIANT alone. */
class PrivateVariantConfigSourceTest {

  @Test
  void thePrivateVariantSuppliesEveryValue() {
    PrivateVariantConfigSource source = new PrivateVariantConfigSource(() -> "private");

    assertEquals("private", source.getValue("qits.edge.exposure"));
    assertEquals("true", source.getValue("qits.edge.wireguard.enabled"));
    assertEquals("47812", source.getValue("qits.edge.wireguard.listen-port"));
    assertEquals("true", source.getValue("qits.edge.dns.enabled"));
    assertEquals("10.231.0.0/20", source.getValue("qits.edge.dns.overlay-cidr"));
    assertEquals(
        "100.96.0.0/24,10.231.0.0/20", source.getValue("qits.edge.listen.allowed-local-cidrs"));
    assertEquals("false", source.getValue("qits.edge.acme.enabled"));
    assertEquals("80", source.getValue("quarkus.http.port"));
    assertEquals("443", source.getValue("quarkus.http.ssl-port"));
    assertEquals(
        "/work/.letsencrypt/current/lets-encrypt.crt",
        source.getValue("quarkus.tls.key-store.pem.acme.cert"));
    assertEquals(
        "/work/.letsencrypt/current/lets-encrypt.key",
        source.getValue("quarkus.tls.key-store.pem.acme.key"));
    assertEquals("1m", source.getValue("quarkus.tls.reload-period"));
    assertEquals(
        "${QITS_APPLICATION:qits-edge-private}", source.getValue("quarkus.otel.service.name"));
    assertEquals(13, source.getPropertyNames().size());
    assertEquals(source.getPropertyNames(), source.getProperties().keySet());
    assertNull(source.getValue("qits.edge.domain"), "anything else keeps its default");
    assertEquals(275, source.getOrdinal());
  }

  @Test
  void anAbsentOrOtherVariantIsEmpty() {
    for (String variant : new String[] {null, "", "PRIVATE", "public", "privat"}) {
      PrivateVariantConfigSource source = new PrivateVariantConfigSource(() -> variant);
      assertTrue(source.getPropertyNames().isEmpty(), String.valueOf(variant));
      assertTrue(source.getProperties().isEmpty(), String.valueOf(variant));
      assertNull(source.getValue("qits.edge.exposure"), String.valueOf(variant));
    }
  }

  @Test
  void theVariantIsReadAtEveryCallNeverCached() {
    String[] variant = {null};
    PrivateVariantConfigSource source = new PrivateVariantConfigSource(() -> variant[0]);
    assertNull(source.getValue("quarkus.http.port"));
    variant[0] = "private";
    assertEquals("80", source.getValue("quarkus.http.port"));
  }

  @Test
  void itBeatsAProdProfileLineInApplicationPropertiesAndLosesToTheEnvironment() {
    SmallRyeConfig config =
        new SmallRyeConfigBuilder()
            .withProfile("prod")
            .withSources(
                new PropertiesConfigSource(
                    Map.of(
                        "%prod.qits.edge.acme.enabled", "true",
                        "quarkus.http.port", "8080",
                        "qits.edge.exposure", "public"),
                    "application.properties",
                    250))
            .withSources(new EnvConfigSource(Map.of("QUARKUS_HTTP_PORT", "8081"), 300))
            .withSources(new PrivateVariantConfigSource(() -> "private"))
            .build();

    assertEquals("false", config.getRawValue("qits.edge.acme.enabled"));
    assertEquals("private", config.getRawValue("qits.edge.exposure"));
    assertEquals("8081", config.getRawValue("quarkus.http.port"), "an env var still wins");
  }

  /**
   * Telemetry under its own name; quarkus.application.name, which more than OTel reads, unmoved.
   */
  @Test
  void thePrivateEdgeExportsTelemetryAsItsOwnApplication() {
    Map<String, String> properties = Map.of("quarkus.application.name", "qits-edge");

    SmallRyeConfig deployed =
        config(properties, Map.of("QITS_APPLICATION", "qits-edge-private"), () -> "private");
    assertEquals("qits-edge-private", deployed.getRawValue("quarkus.otel.service.name"));
    assertEquals("qits-edge", deployed.getRawValue("quarkus.application.name"));

    SmallRyeConfig renamed =
        config(properties, Map.of("QITS_APPLICATION", "qits-edge-other"), () -> "private");
    assertEquals("qits-edge-other", renamed.getRawValue("quarkus.otel.service.name"));

    SmallRyeConfig bare = config(properties, Map.of(), () -> "private");
    assertEquals("qits-edge-private", bare.getRawValue("quarkus.otel.service.name"));

    SmallRyeConfig publicEdge =
        config(properties, Map.of("QITS_APPLICATION", "qits-edge"), () -> null);
    assertNull(
        publicEdge.getRawValue("quarkus.otel.service.name"),
        "the public edge sets none: Quarkus falls back to quarkus.application.name, qits-edge");
    assertEquals("qits-edge", publicEdge.getRawValue("quarkus.application.name"));
  }

  private static SmallRyeConfig config(
      Map<String, String> properties,
      Map<String, String> environment,
      java.util.function.Supplier<String> variant) {
    return new SmallRyeConfigBuilder()
        .addDefaultInterceptors()
        .withSources(new PropertiesConfigSource(properties, "application.properties", 250))
        .withSources(new EnvConfigSource(environment, 300))
        .withSources(new PrivateVariantConfigSource(variant))
        .build();
  }

  @Test
  void theServiceFileRegistersIt() throws Exception {
    // Main and test resources each carry a service file: read them all.
    boolean listed = false;
    var files =
        getClass()
            .getClassLoader()
            .getResources("META-INF/services/org.eclipse.microprofile.config.spi.ConfigSource");
    while (files.hasMoreElements()) {
      try (var in = files.nextElement().openStream()) {
        listed |=
            new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .lines()
                .anyMatch(line -> line.strip().equals(PrivateVariantConfigSource.class.getName()));
      }
    }
    assertTrue(listed);
  }
}
