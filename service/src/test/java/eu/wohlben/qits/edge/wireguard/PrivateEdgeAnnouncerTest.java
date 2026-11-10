package eu.wohlben.qits.edge.wireguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** When {@link PrivateEdgeAnnouncer} publishes, and what. */
class PrivateEdgeAnnouncerTest {

  private static final String PUBLIC_KEY = "95pJhHSoEe/esnOiCdHvLmFIMzADJiaoQSZ6WcMJvwM=";

  private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

  private final List<PrivateEdgeAnnounced> published = new ArrayList<>();

  private PrivateEdgeAnnouncer announcer() {
    return new PrivateEdgeAnnouncer(
        PrivateEdgeAnnouncer.Settings.of(
            "qits-edge-private", 47812, "100.96.0.1/24", "100.96.0.1", 1380, "wohlben.eu"),
        published::add,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static Inet4Address ip(String literal) throws Exception {
    return (Inet4Address) InetAddress.getByName(literal);
  }

  @Test
  void itPublishesOnceBothHalvesAreKnownWithEveryField() throws Exception {
    PrivateEdgeAnnouncer announcer = announcer();

    announcer.publicAddress(ip("46.224.171.33"));
    assertEquals(List.of(), published, "no key yet: wg0 is not configured");
    announcer.deviceConfigured(PUBLIC_KEY);

    assertEquals(1, published.size());
    PrivateEdgeAnnounced event = published.get(0);
    assertNotNull(event.eventId());
    assertEquals("PrivateEdgeAnnounced", event.signature());
    assertEquals("qits-edge-private", event.application());
    assertEquals(PUBLIC_KEY, event.publicKey());
    assertEquals("46.224.171.33", event.publicAddress());
    assertEquals(47812, event.listenPort());
    assertEquals("100.96.0.1", event.tunnelAddress());
    assertEquals("100.96.0.0/24", event.tunnelCidr());
    assertEquals("100.96.0.1", event.dnsAddress());
    assertEquals(1380, event.mtu());
    assertEquals("wohlben.eu", event.domain());
    assertEquals(NOW, event.occurredAt());
  }

  @Test
  void itPublishesAgainOnlyWhenTheAddressChanges() throws Exception {
    PrivateEdgeAnnouncer announcer = announcer();
    announcer.deviceConfigured(PUBLIC_KEY);
    announcer.publicAddress(ip("46.224.171.33"));
    announcer.publicAddress(ip("46.224.171.33"));
    assertEquals(1, published.size(), "an unchanged address is no reason");

    announcer.publicAddress(ip("46.224.171.34"));

    assertEquals(2, published.size());
    assertEquals("46.224.171.34", published.get(1).publicAddress());
    assertNotEquals(published.get(0).eventId(), published.get(1).eventId());
  }

  @Test
  void theTunnelBlockIsDerivedFromTheAddress() {
    assertEquals(
        "10.8.0.0/16",
        PrivateEdgeAnnouncer.Settings.of("a", 1, "10.8.3.1/16", "10.8.3.1", 1380, "x")
            .tunnelCidr());
    assertThrows(
        IllegalArgumentException.class,
        () -> PrivateEdgeAnnouncer.Settings.of("a", 1, "100.96.0.1", "d", 1380, "x"));
  }
}
