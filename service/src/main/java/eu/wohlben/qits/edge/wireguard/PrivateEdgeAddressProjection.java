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
import java.util.Optional;
import java.util.UUID;

/**
 * {@code edge_private_edge_address}: the newest tunnel address per private-edge application — see
 * {@code V14__private_edge_address.sql}. {@link PrivateEdgeAddressSubscriber} writes it, {@code
 * HostRecords} reads {@link #newest()}.
 */
@ApplicationScoped
public class PrivateEdgeAddressProjection {

  @Inject
  @DataSource("edge")
  AgroalDataSource dataSource;

  /**
   * Record one announcement, keeping the newest per application by {@code occurredAt} then {@code
   * eventId} — the tie-safe order {@link WireguardPeerProjection#record} uses.
   *
   * @return true only when this frame is now the row AND its address differs from the one it
   *     replaced (or there was none), so the caller asks for a reconcile only when the answer moved
   */
  // Its own transaction for the reason WireguardPeerProjection's is: the claim ledger lives in a
  // different datasource, so this row commits wholly or not at all, and a replayed frame is a
  // no-op.
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public boolean record(
      String application, String tunnelAddress, UUID eventId, Instant occurredAt) {
    if (application == null || tunnelAddress == null || eventId == null || occurredAt == null) {
      throw new IllegalArgumentException(
          "An announcement needs an application, an address, an event id and a time.");
    }
    try (Connection connection = dataSource.getConnection()) {
      String previous;
      try (PreparedStatement read =
          connection.prepareStatement(
              "select occurred_at, event_id, tunnel_address from edge_private_edge_address"
                  + " where application = ? for update")) {
        read.setString(1, application);
        try (ResultSet result = read.executeQuery()) {
          if (result.next()) {
            Instant recordedAt = result.getTimestamp(1).toInstant();
            int order = occurredAt.compareTo(recordedAt);
            boolean newer =
                order > 0
                    || (order == 0
                        && eventId.toString().compareTo(result.getObject(2, UUID.class).toString())
                            > 0);
            if (!newer) {
              return false;
            }
            previous = result.getString(3);
          } else {
            previous = null;
          }
        }
      }
      try (PreparedStatement upsert =
          connection.prepareStatement(
              """
              insert into edge_private_edge_address
                (application, tunnel_address, event_id, occurred_at)
              values (?, ?, ?, ?)
              on conflict (application) do update
                set tunnel_address = excluded.tunnel_address,
                    event_id = excluded.event_id,
                    occurred_at = excluded.occurred_at
              """)) {
        upsert.setString(1, application);
        upsert.setString(2, tunnelAddress);
        upsert.setObject(3, eventId);
        upsert.setTimestamp(4, java.sql.Timestamp.from(occurredAt));
        upsert.executeUpdate();
      }
      return !tunnelAddress.equals(previous);
    } catch (SQLException failure) {
      throw new IllegalStateException("could not record the private edge address", failure);
    }
  }

  /**
   * The address of the newest announcement overall, or empty before any private edge announced.
   *
   * <p><b>Newest overall, not per application</b>, because the public zone has one answer per name:
   * today there is one private edge ({@code qits-edge-private}) and this is its address. Were there
   * ever two, the one that announced last wins for every private host until a per-edge mapping of
   * hosts exists — nothing in a {@code DeploymentActive} frame says which private edge serves it.
   */
  public Optional<String> newest() {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement query =
            connection.prepareStatement(
                "select tunnel_address from edge_private_edge_address"
                    + " order by occurred_at desc, event_id desc limit 1");
        ResultSet result = query.executeQuery()) {
      return result.next() ? Optional.of(result.getString(1)) : Optional.empty();
    } catch (SQLException failure) {
      throw new IllegalStateException("could not read the private edge address", failure);
    }
  }
}
