package eu.wohlben.qits.edge.wireguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.edge.StubGateways;
import eu.wohlben.qits.edge.WireguardPeers;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The peer projection, driven through hand-built frames as qits-network-access publishes them, into
 * {@code edge_wireguard_peer} and a {@link LivePeers} of a public edge — which holds the set and
 * dials nothing, so no UAPI socket is needed. {@link StubGateways} for the one shared
 * configuration, as {@code ProjectSansTest} explains.
 */
@QuarkusTest
@WithTestResource(StubGateways.class)
class PeerEventsSubscriberTest {

  private static final String ENVIRONMENT = "dev";

  private static final String KEY_A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

  private static final String KEY_B = "95pJhHSoEe/esnOiCdHvLmFIMzADJiaoQSZ6WcMJvwM=";

  @Inject WireguardPeerProjection rows;

  @Inject
  @DataSource("edge")
  AgroalDataSource edgeDataSource;

  private LivePeers live;

  private PeerEventsSubscriber subscriber;

  @BeforeEach
  void freshProjection() throws SQLException {
    try (Connection connection = edgeDataSource.getConnection();
        PreparedStatement delete = connection.prepareStatement("delete from edge_wireguard_peer")) {
      delete.executeUpdate();
    }
    live = new LivePeers();
    subscriber = new PeerEventsSubscriber(rows, live, ENVIRONMENT);
  }

  @Test
  void theConsumerReplaysFromTheEpochUnderItsOwnName() {
    assertEquals("edge-wireguard-peers", subscriber.consumerId());
    assertTrue(subscriber.replayFromEpoch());
    assertEquals(Set.of("PeerRegistered", "PeerRevoked"), subscriber.signatures());
  }

  @Test
  void framesAreAppliedInOrderAndTheNewestWins() {
    UUID laptop = UUID.randomUUID();
    UUID node = UUID.randomUUID();
    deliver(registered(laptop, "laptop", KEY_A, "100.96.0.10", at(1)));
    deliver(registered(node, "node-1", KEY_B, "100.96.0.11", at(2)));
    assertEquals(List.of("laptop", "node-1"), names());

    deliver(revoked(laptop, "laptop", KEY_A, "100.96.0.10", "REVOKED", at(3)));
    assertEquals(List.of("node-1"), names());

    // A replay of the register, as a rebuild from the epoch delivers it: older, so nothing moves.
    deliver(registered(laptop, "laptop", KEY_A, "100.96.0.10", at(1)));
    assertEquals(List.of("node-1"), names());
    assertEquals(
        List.of(false, true),
        rows.rows().stream()
            .sorted(java.util.Comparator.comparing(row -> row.name()))
            .map(WireguardPeerProjection.Row::active)
            .toList());
  }

  @Test
  void aRevokeBeforeItsRegisterLeavesNoPeer() {
    UUID peer = UUID.randomUUID();
    deliver(revoked(peer, "laptop", KEY_A, "100.96.0.10", "USER_DELETED", at(5)));
    deliver(registered(peer, "laptop", KEY_A, "100.96.0.10", at(4)));

    assertEquals(List.of(), live.peers());
    assertEquals(1, rows.rows().size());
    assertFalse(rows.rows().get(0).active(), "the tombstone is kept");
  }

  @Test
  void anotherEnvironmentsFramesAreNotSelected() {
    EventFrame elsewhere =
        frame(
            PeerEventsSubscriber.REGISTERED,
            at(1),
            registeredPayload(UUID.randomUUID(), "laptop", KEY_A, "100.96.0.10"),
            "prod");
    assertFalse(subscriber.selects(elsewhere));
    assertFalse(
        subscriber.selects(frame(PeerEventsSubscriber.REGISTERED, at(1), new JsonObject(), null)));
    assertTrue(
        subscriber.selects(registered(UUID.randomUUID(), "laptop", KEY_A, "100.96.0.10", at(1))));
  }

  @Test
  void aRegisterWithoutAUsableKeyIsSettledWithoutAPeer() {
    deliver(registered(UUID.randomUUID(), "laptop", "AAAA", "100.96.0.10", at(1)));
    deliver(registered(UUID.randomUUID(), "laptop", KEY_A, "nowhere", at(1)));
    assertEquals(List.of(), live.peers());
    assertEquals(List.of(), rows.rows());
  }

  /** What the eventstream reader does: a frame it selects is handed over, any other is skipped. */
  private void deliver(EventFrame frame) {
    if (subscriber.selects(frame)) {
      subscriber.onFrame(frame);
    }
  }

  private List<String> names() {
    return live.peers().stream().map(WireguardPeers.Peer::name).toList();
  }

  private static Instant at(int minute) {
    return Instant.parse("2026-11-10T10:00:00Z").plusSeconds(60L * minute);
  }

  private static JsonObject registeredPayload(UUID peer, String name, String key, String ip) {
    return new JsonObject()
        .put("peerId", peer.toString())
        .put("kind", "DEVICE")
        .put("name", name)
        .put("publicKey", key)
        .put("tunnelIp", ip)
        .put("ownerUserId", UUID.randomUUID().toString())
        .putNull("targetId");
  }

  private static EventFrame registered(UUID peer, String name, String key, String ip, Instant at) {
    return frame(
        PeerEventsSubscriber.REGISTERED, at, registeredPayload(peer, name, key, ip), ENVIRONMENT);
  }

  private static EventFrame revoked(
      UUID peer, String name, String key, String ip, String reason, Instant at) {
    return frame(
        PeerEventsSubscriber.REVOKED,
        at,
        new JsonObject()
            .put("peerId", peer.toString())
            .put("kind", "DEVICE")
            .put("name", name)
            .put("publicKey", key)
            .put("tunnelIp", ip)
            .put("reason", reason),
        ENVIRONMENT);
  }

  private static EventFrame frame(String name, Instant at, JsonObject payload, String environment) {
    return new EventFrame(
        UUID.randomUUID().toString(), name, at, payload.encode(), null, null, environment);
  }
}
