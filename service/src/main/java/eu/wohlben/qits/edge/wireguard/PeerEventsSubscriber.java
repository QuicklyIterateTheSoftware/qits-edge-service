package eu.wohlben.qits.edge.wireguard;

import eu.wohlben.qits.edge.EdgeConfig;
import eu.wohlben.qits.edge.WireguardPeers;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Durable projection of qits-network-access' peer registrations (epic qits-1977): {@code
 * PeerRegistered} writes a peer active, {@code PeerRevoked} writes it inactive, into {@code
 * edge_wireguard_peer} ({@link WireguardPeerProjection}), and a frame that became the row is handed
 * to {@link LivePeers}, which applies it to {@code wg0} on a private edge and only holds it on a
 * public one.
 *
 * <p>Only frames of this edge's own environment ({@code qits.edge.default-environment}, the
 * deployer's {@code QITS_ENVIRONMENT}) are selected: another environment's peers are not this
 * tunnel's.
 *
 * <p>Order is the frame's {@code occurredAt}, then its id, so a revoke replayed before the register
 * it ends leaves a tombstone the register cannot overwrite, and no peer.
 */
@ApplicationScoped
public class PeerEventsSubscriber implements QitsDurableEventListener {

  static final String REGISTERED = "PeerRegistered";
  static final String REVOKED = "PeerRevoked";
  static final String CONSUMER_ID = "edge-wireguard-peers";

  private static final Logger LOG = Logger.getLogger(PeerEventsSubscriber.class);

  /**
   * Private wire DTO rather than a dependency on qits-network-access' event jar, like every other
   * consumer DTO here; unknown fields are ignored. {@code peerName} is read beside {@code name}
   * because a publisher's record component called {@code name} collides with the QitsEvent
   * envelope's ignored {@code name()} and is silently dropped (see {@code
   * ProjectLifecycleSubscriber}); whichever spelling arrives is the name.
   */
  public record PeerRegisteredPayload(
      String peerId,
      String kind,
      String name,
      String peerName,
      String publicKey,
      String tunnelIp,
      String ownerUserId,
      String targetId) {}

  /** {@code PeerRevoked}'s payload; see {@link PeerRegisteredPayload}. */
  public record PeerRevokedPayload(
      String peerId,
      String kind,
      String name,
      String peerName,
      String publicKey,
      String tunnelIp,
      String reason) {}

  @Inject WireguardPeerProjection rows;
  @Inject LivePeers live;
  @Inject EdgeConfig config;

  private String environment;

  public PeerEventsSubscriber() {}

  PeerEventsSubscriber(WireguardPeerProjection rows, LivePeers live, String environment) {
    this.rows = rows;
    this.live = live;
    this.environment = environment;
  }

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(REGISTERED, REVOKED);
  }

  /** The projection has no other seed: a new edge must learn every peer ever registered. */
  @Override
  public boolean replayFromEpoch() {
    return true;
  }

  @Override
  public boolean selects(EventFrame frame) {
    String own = environment();
    return own != null && own.equals(frame.environment());
  }

  private String environment() {
    if (environment == null && config != null) {
      environment = config.defaultEnvironment();
    }
    return environment;
  }

  @Override
  public void onFrame(EventFrame frame) {
    boolean registered = REGISTERED.equals(frame.name());
    if (!registered && !REVOKED.equals(frame.name())) {
      LOG.warnf(
          "%s %s is neither %s nor %s; it is settled unhandled",
          frame.name(), frame.id(), REGISTERED, REVOKED);
      return;
    }
    String peerId;
    String name;
    String publicKey;
    String tunnelIp;
    String reason;
    try {
      if (registered) {
        PeerRegisteredPayload payload =
            CanonicalJson.payloadTo(frame.payload(), PeerRegisteredPayload.class);
        peerId = payload.peerId();
        name = payload.name() != null ? payload.name() : payload.peerName();
        publicKey = payload.publicKey();
        tunnelIp = payload.tunnelIp();
        reason = null;
      } else {
        PeerRevokedPayload payload =
            CanonicalJson.payloadTo(frame.payload(), PeerRevokedPayload.class);
        peerId = payload.peerId();
        name = payload.name() != null ? payload.name() : payload.peerName();
        publicKey = payload.publicKey();
        tunnelIp = payload.tunnelIp();
        reason = payload.reason();
      }
    } catch (RuntimeException unreadable) {
      // Settled rather than thrown: a frame refused for its own content is refused identically on
      // every later catch-up, and a throw would pin this consumer's watermark before every peer
      // registered after it.
      LOG.warnf(
          "%s %s carried an unreadable peer payload: %s; it is settled unhandled",
          frame.name(), frame.id(), unreadable.getMessage());
      return;
    }
    UUID peer = uuid(peerId);
    UUID event = uuid(frame.id());
    if (peer == null || event == null || frame.occurredAt() == null) {
      LOG.warnf(
          "%s %s carries no usable peer id, event id or time; it is settled unhandled",
          frame.name(), frame.id());
      return;
    }
    WireguardPeers.Peer active = registered ? LivePeers.peer(name, publicKey, tunnelIp) : null;
    if (registered && active == null) {
      LOG.warnf(
          "%s %s registers peer %s without a 32-byte base64 key or an IP tunnel address; it is"
              + " settled unhandled",
          frame.name(), frame.id(), peer);
      return;
    }
    boolean recorded =
        rows.record(peer, name, publicKey, tunnelIp, registered, event, frame.occurredAt());
    if (!recorded) {
      LOG.debugf(
          "%s %s is not newer than peer %s's recorded frame", frame.name(), frame.id(), peer);
      return;
    }
    live.apply(peer, active, reason);
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
