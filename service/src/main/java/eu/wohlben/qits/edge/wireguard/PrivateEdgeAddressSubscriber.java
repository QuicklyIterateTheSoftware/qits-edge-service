package eu.wohlben.qits.edge.wireguard;

import eu.wohlben.qits.edge.PrivateHostRecords;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Durable projection of {@link PrivateEdgeAnnounced}'s tunnel address (qits-2273) into {@code
 * edge_private_edge_address} ({@link PrivateEdgeAddressProjection}): the value the public edge
 * writes into every private host's A record in the public zone ({@link PrivateHostRecords}).
 *
 * <p>It runs on every edge, which is harmless — the rows are a few bytes and only the public edge
 * reads them. Every environment's frames are read: the address is wg0's, and which environment a
 * private edge was deployed in says nothing about which names it serves.
 *
 * <p>A frame whose address is not an IPv4 — with or without a {@code /prefix}, which is stripped —
 * is settled with a WARN, as is one without an application, id or time: it would be refused the
 * same way on every later catch-up, and a throw would pin this consumer behind it forever.
 */
@ApplicationScoped
public class PrivateEdgeAddressSubscriber implements QitsDurableEventListener {

  static final String SIGNATURE = "PrivateEdgeAnnounced";
  static final String CONSUMER_ID = "edge-private-edge-address";

  private static final Logger LOG = Logger.getLogger(PrivateEdgeAddressSubscriber.class);

  /**
   * The two fields this projection reads, as a private wire DTO like every other consumer DTO here:
   * the published {@link PrivateEdgeAnnounced} carries its identity on the envelope, not in the
   * payload, and unknown fields are ignored.
   */
  public record PrivateEdgeAddressPayload(String application, String tunnelAddress) {}

  @Inject PrivateEdgeAddressProjection rows;
  @Inject PrivateHostRecords records;

  public PrivateEdgeAddressSubscriber() {}

  PrivateEdgeAddressSubscriber(PrivateEdgeAddressProjection rows, PrivateHostRecords records) {
    this.rows = rows;
    this.records = records;
  }

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SIGNATURE);
  }

  /** The projection has no other seed: a fresh edge must learn the address announced before it. */
  @Override
  public boolean replayFromEpoch() {
    return true;
  }

  @Override
  public void onFrame(EventFrame frame) {
    PrivateEdgeAddressPayload payload;
    try {
      payload = CanonicalJson.payloadTo(frame.payload(), PrivateEdgeAddressPayload.class);
    } catch (RuntimeException unreadable) {
      LOG.warnf(
          "%s %s carried an unreadable payload: %s; it is settled unhandled",
          frame.name(), frame.id(), unreadable.getMessage());
      return;
    }
    UUID event = uuid(frame.id());
    String application =
        payload.application() == null || payload.application().isBlank()
            ? null
            : payload.application().strip();
    if (application == null || event == null || frame.occurredAt() == null) {
      LOG.warnf(
          "%s %s carries no application, usable event id or time; it is settled unhandled",
          frame.name(), frame.id());
      return;
    }
    String address = ipv4(payload.tunnelAddress());
    if (address == null) {
      LOG.warnf(
          "%s %s announces %s with the tunnel address `%s`, which is not an IPv4; it is settled"
              + " unhandled",
          frame.name(), frame.id(), application, payload.tunnelAddress());
      return;
    }
    if (!rows.record(application, address, event, frame.occurredAt())) {
      LOG.debugf(
          "%s %s changes nothing about %s's recorded address",
          frame.name(), frame.id(), application);
      return;
    }
    LOG.infof(
        "private edge %s announced tunnel address %s (%s %s)",
        application, address, frame.name(), frame.id());
    if (records != null) {
      // Debounced and off this thread: the reconcile runs after this frame's row has committed.
      records.requestReconcile();
    }
  }

  /**
   * The dotted-quad IPv4 in {@code literal}, any {@code /prefix} stripped, or null. Parsed by hand
   * rather than through {@code InetAddress}, which would resolve a name.
   */
  static String ipv4(String literal) {
    if (literal == null) {
      return null;
    }
    String text = literal.strip();
    int slash = text.indexOf('/');
    if (slash >= 0) {
      text = text.substring(0, slash);
    }
    String[] octets = text.split("\\.", -1);
    if (octets.length != 4) {
      return null;
    }
    StringBuilder normalised = new StringBuilder();
    for (String octet : octets) {
      if (octet.isEmpty()
          || octet.length() > 3
          || !octet.chars().allMatch(c -> c >= '0' && c <= '9')) {
        return null;
      }
      int value = Integer.parseInt(octet);
      if (value > 255) {
        return null;
      }
      if (!normalised.isEmpty()) {
        normalised.append('.');
      }
      normalised.append(value);
    }
    return normalised.toString();
  }

  private static UUID uuid(String value) {
    if (value == null) {
      return null;
    }
    try {
      return UUID.fromString(value.strip());
    } catch (IllegalArgumentException notOne) {
      return null;
    }
  }
}
