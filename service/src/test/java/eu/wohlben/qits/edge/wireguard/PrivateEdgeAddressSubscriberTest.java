package eu.wohlben.qits.edge.wireguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.edge.StubGateways;
import eu.wohlben.qits.eventstream.control.EventFrame;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The tunnel address projection (qits-2273), driven through hand-built {@code PrivateEdgeAnnounced}
 * frames into {@code edge_private_edge_address}. {@link StubGateways} for the one shared
 * configuration, as {@code PeerEventsSubscriberTest} does.
 */
@QuarkusTest
@WithTestResource(StubGateways.class)
class PrivateEdgeAddressSubscriberTest {

  @Inject PrivateEdgeAddressProjection rows;

  @Inject
  @DataSource("edge")
  AgroalDataSource edgeDataSource;

  private PrivateEdgeAddressSubscriber subscriber;

  @BeforeEach
  void freshProjection() throws SQLException {
    try (Connection connection = edgeDataSource.getConnection();
        PreparedStatement delete =
            connection.prepareStatement("delete from edge_private_edge_address")) {
      delete.executeUpdate();
    }
    subscriber = new PrivateEdgeAddressSubscriber(rows, null);
  }

  @Test
  void theConsumerReplaysFromTheEpochUnderItsOwnName() {
    assertEquals("edge-private-edge-address", subscriber.consumerId());
    assertTrue(subscriber.replayFromEpoch());
    assertEquals(Set.of("PrivateEdgeAnnounced"), subscriber.signatures());
  }

  @Test
  void theNewestAnnouncementWinsAndAPrefixIsStripped() {
    subscriber.onFrame(announced("qits-edge-private", "100.96.0.1/24", at(1)));
    assertEquals(Optional.of("100.96.0.1"), rows.newest());

    subscriber.onFrame(announced("qits-edge-private", "100.96.0.7", at(3)));
    // A replayed older frame, as a rebuild from the epoch delivers it: nothing moves.
    subscriber.onFrame(announced("qits-edge-private", "100.96.0.1/24", at(2)));
    assertEquals(Optional.of("100.96.0.7"), rows.newest());
  }

  @Test
  void theNewestOverallIsTheAnswerAcrossPrivateEdges() {
    subscriber.onFrame(announced("qits-edge-private", "100.96.0.1", at(5)));
    subscriber.onFrame(announced("qits-edge-private-b", "100.97.0.1", at(4)));
    assertEquals(Optional.of("100.96.0.1"), rows.newest());
  }

  @Test
  void anAddressThatIsNotAnIpv4IsSettledWithoutARow() {
    subscriber.onFrame(announced("qits-edge-private", "fd00::1/64", at(1)));
    subscriber.onFrame(announced("qits-edge-private", "edge.example", at(1)));
    subscriber.onFrame(announced("qits-edge-private", null, at(1)));
    subscriber.onFrame(announced(" ", "100.96.0.1", at(1)));
    assertEquals(Optional.empty(), rows.newest());
  }

  @Test
  void theIpv4ReadingIsStrict() {
    assertEquals("100.96.0.1", PrivateEdgeAddressSubscriber.ipv4(" 100.96.0.1/24 "));
    assertEquals("10.0.0.1", PrivateEdgeAddressSubscriber.ipv4("010.0.0.1"));
    assertNull(PrivateEdgeAddressSubscriber.ipv4("256.0.0.1"));
    assertNull(PrivateEdgeAddressSubscriber.ipv4("100.96.0"));
    assertNull(PrivateEdgeAddressSubscriber.ipv4("100.96.0.1.5"));
    assertNull(PrivateEdgeAddressSubscriber.ipv4("100.96..1"));
  }

  private static Instant at(int minute) {
    return Instant.parse("2026-11-16T10:00:00Z").plusSeconds(60L * minute);
  }

  private static EventFrame announced(String application, String tunnelAddress, Instant at) {
    JsonObject payload =
        new JsonObject()
            .put("application", application)
            .put("publicKey", "95pJhHSoEe/esnOiCdHvLmFIMzADJiaoQSZ6WcMJvwM=")
            .put("publicAddress", "46.224.171.33")
            .put("listenPort", 47812)
            .put("tunnelAddress", tunnelAddress)
            .put("tunnelCidr", "100.96.0.0/24")
            .put("dnsAddress", "100.96.0.1")
            .put("mtu", 1380)
            .put("domain", "wohlben.eu");
    return new EventFrame(
        UUID.randomUUID().toString(),
        PrivateEdgeAddressSubscriber.SIGNATURE,
        at,
        payload.encode(),
        null,
        null,
        "dev");
  }
}
