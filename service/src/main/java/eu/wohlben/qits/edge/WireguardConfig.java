package eu.wohlben.qits.edge;

import io.smallrye.config.ConfigMapping;
import java.util.Optional;

/**
 * The private edge's WireGuard settings, {@code qits.edge.wireguard.*}. This slice reads only the
 * peer list, for {@link PeerLog}; the device itself — its key, its UAPI socket, its listen port —
 * joins this mapping with the task that brings {@code wg0} up.
 */
@ConfigMapping(prefix = "qits.edge.wireguard")
public interface WireguardConfig {

  /**
   * The static peers, {@code name:base64PublicKey:tunnelIp} comma separated — see {@link
   * WireguardPeers}. Absent is no peers, and every tunnel caller is then {@code peer=unknown}.
   */
  Optional<String> peers();
}
