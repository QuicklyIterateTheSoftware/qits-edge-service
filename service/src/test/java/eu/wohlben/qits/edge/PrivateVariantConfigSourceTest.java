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
    assertEquals("https://idp.qits.${QITS_DOMAIN}/idp", source.getValue("qits.edge.idp.dial-url"));
    assertEquals("https://events.qits.${QITS_DOMAIN}", source.getValue("qits.events.url"));
    assertEquals(
        "https://observability.qits.${QITS_DOMAIN}", source.getValue("qits.observability.url"));
    assertEquals("80", source.getValue("quarkus.http.port"));
    assertEquals("443", source.getValue("quarkus.http.ssl-port"));
    assertEquals(
        "/work/.letsencrypt/current/lets-encrypt.crt",
        source.getValue("quarkus.tls.key-store.pem.acme.cert"));
    assertEquals(
        "/work/.letsencrypt/current/lets-encrypt.key",
        source.getValue("quarkus.tls.key-store.pem.acme.key"));
    assertEquals("1m", source.getValue("quarkus.tls.reload-period"));
    assertEquals(15, source.getPropertyNames().size());
    assertEquals(source.getPropertyNames(), source.getProperties().keySet());
    assertNull(source.getValue("qits.edge.domain"), "anything else keeps its default");
    assertNull(
        source.getValue("quarkus.otel.service.name"),
        "the deployer's QUARKUS_OTEL_SERVICE_NAME names it now (qits-2054); this source must not"
            + " compete with it");
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

  @Test
  void thePublicNamesExpandQitsDomainOnlyForThePrivateVariant() {
    SmallRyeConfig privateConfig =
        new SmallRyeConfigBuilder()
            .addDefaultInterceptors()
            .withSources(
                new PropertiesConfigSource(
                    Map.of(
                        "qits.edge.idp.dial-url", "http://dev-qits-idp:8080/idp",
                        "qits.events.url", "http://dev-qits-events:8080",
                        "qits.observability.url", "http://dev-qits-observability:8080"),
                    "application.properties",
                    250))
            .withSources(new EnvConfigSource(Map.of("QITS_DOMAIN", "example.test"), 300))
            .withSources(new PrivateVariantConfigSource(() -> "private"))
            .build();

    assertEquals(
        "https://idp.qits.example.test/idp",
        privateConfig.getValue("qits.edge.idp.dial-url", String.class));
    assertEquals(
        "https://events.qits.example.test",
        privateConfig.getValue("qits.events.url", String.class));
    assertEquals(
        "https://observability.qits.example.test",
        privateConfig.getValue("qits.observability.url", String.class));

    SmallRyeConfig publicConfig =
        new SmallRyeConfigBuilder()
            .addDefaultInterceptors()
            .withSources(
                new PropertiesConfigSource(
                    Map.of(
                        "qits.edge.idp.dial-url", "http://dev-qits-idp:8080/idp",
                        "qits.events.url", "http://dev-qits-events:8080",
                        "qits.observability.url", "http://dev-qits-observability:8080"),
                    "application.properties",
                    250))
            .withSources(new EnvConfigSource(Map.of("QITS_DOMAIN", "example.test"), 300))
            .withSources(new PrivateVariantConfigSource(() -> null))
            .build();

    assertEquals(
        "http://dev-qits-idp:8080/idp",
        publicConfig.getValue("qits.edge.idp.dial-url", String.class));
    assertEquals(
        "http://dev-qits-events:8080", publicConfig.getValue("qits.events.url", String.class));
    assertEquals(
        "http://dev-qits-observability:8080",
        publicConfig.getValue("qits.observability.url", String.class));
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
