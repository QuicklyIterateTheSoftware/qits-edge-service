package eu.wohlben.qits.edge.wireguard;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * What a private edge is, for whoever builds a peer's configuration (epic qits-1977):
 * qits-network-access reads it to write the {@code [Peer]} a device or node dials. Published by
 * {@link PrivateEdgeAnnouncer}. {@code eventId} and {@code occurredAt} travel on the envelope.
 *
 * @param application the deployed identity, {@code QITS_APPLICATION}
 * @param publicKey the edge's WireGuard public key, base64
 * @param publicAddress the endpoint host: the zone apex's A record as the tunnel DNS knows it
 * @param listenPort the UDP port wg0 listens on
 * @param tunnelAddress wg0's own address, which a peer allows as its one {@code AllowedIPs}
 * @param tunnelCidr the block peers are addressed in
 * @param dnsAddress the tunnel DNS responder a peer asks for {@code domain}
 * @param mtu wg0's MTU
 * @param domain the split-DNS zone, {@code qits.edge.domain}
 */
public record PrivateEdgeAnnounced(
    UUID eventId,
    String application,
    String publicKey,
    String publicAddress,
    int listenPort,
    String tunnelAddress,
    String tunnelCidr,
    String dnsAddress,
    int mtu,
    String domain,
    Instant occurredAt)
    implements QitsEvent {}
