package eu.wohlben.qits.edge.wireguard;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code edge_wireguard_peer}: the newest registration frame per peer, active or a tombstone — see
 * {@code V9__wireguard_peers.sql}. {@link PeerEventsSubscriber} writes it, {@link LivePeers} loads
 * the active rows at start.
 */
@ApplicationScoped
public class WireguardPeerProjection {

  /** One row as {@link LivePeers} reads it. */
  public record Row(UUID peerId, String name, String publicKey, String tunnelIp, boolean active) {}

  @Inject
  @DataSource("edge")
  AgroalDataSource dataSource;

  /**
   * Record one frame for {@code peerId}, keeping the newest by {@code occurredAt} then {@code
   * eventId} — the tie-safe order {@code EdgeRoutes.replace} and {@code EdgeProjects.apply} use.
   *
   * @return true only when this frame is now the row, so the caller hands it on; an older or a
   *     repeated frame changes nothing and answers false
   */
  // qits-eventstream owns its claim ledger in a different datasource. Suspending that transaction
  // avoids pretending two ordinary PostgreSQL pools are one XA transaction: this row commits wholly
  // or not at all, and an outer claim failure merely replays the same idempotent frame.
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public boolean record(
      UUID peerId,
      String name,
      String publicKey,
      String tunnelIp,
      boolean active,
      UUID eventId,
      Instant occurredAt) {
    if (peerId == null || eventId == null || occurredAt == null) {
      throw new IllegalArgumentException("A peer frame needs a peer id, an event id and a time.");
    }
    try (Connection connection = dataSource.getConnection()) {
      if (!newer(connection, peerId, eventId, occurredAt)) {
        return false;
      }
      try (PreparedStatement upsert =
          connection.prepareStatement(
              """
              insert into edge_wireguard_peer
                (peer_id, name, public_key, tunnel_ip, active, event_id, occurred_at)
              values (?, ?, ?, ?, ?, ?, ?)
              on conflict (peer_id) do update
                set name = excluded.name,
                    public_key = excluded.public_key,
                    tunnel_ip = excluded.tunnel_ip,
                    active = excluded.active,
                    event_id = excluded.event_id,
                    occurred_at = excluded.occurred_at
              """)) {
        upsert.setObject(1, peerId);
        upsert.setString(2, name == null ? "" : name);
        upsert.setString(3, publicKey == null ? "" : publicKey);
        upsert.setString(4, tunnelIp == null ? "" : tunnelIp);
        upsert.setBoolean(5, active);
        upsert.setObject(6, eventId);
        upsert.setTimestamp(7, java.sql.Timestamp.from(occurredAt));
        upsert.executeUpdate();
      }
      return true;
    } catch (SQLException failure) {
      throw new IllegalStateException(
          "could not record the edge wireguard peer projection", failure);
    }
  }

  private static boolean newer(Connection connection, UUID peerId, UUID eventId, Instant occurredAt)
      throws SQLException {
    try (PreparedStatement read =
        connection.prepareStatement(
            "select occurred_at, event_id from edge_wireguard_peer where peer_id = ? for update")) {
      read.setObject(1, peerId);
      try (ResultSet result = read.executeQuery()) {
        if (!result.next()) {
          return true;
        }
        Instant recordedAt = result.getTimestamp(1).toInstant();
        int order = occurredAt.compareTo(recordedAt);
        return order > 0
            || (order == 0
                && eventId.toString().compareTo(result.getObject(2, UUID.class).toString()) > 0);
      }
    }
  }

  /** Every row, active ones and tombstones, in peer id order. */
  public List<Row> rows() {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement query =
            connection.prepareStatement(
                "select peer_id, name, public_key, tunnel_ip, active from edge_wireguard_peer"
                    + " order by peer_id");
        ResultSet result = query.executeQuery()) {
      List<Row> rows = new ArrayList<>();
      while (result.next()) {
        rows.add(
            new Row(
                result.getObject(1, UUID.class),
                result.getString(2),
                result.getString(3),
                result.getString(4),
                result.getBoolean(5)));
      }
      return rows;
    } catch (SQLException failure) {
      throw new IllegalStateException("could not load the edge wireguard peer projection", failure);
    }
  }
}
