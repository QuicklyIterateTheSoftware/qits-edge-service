package eu.wohlben.qits.edge;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

/**
 * The private edge's WireGuard settings, {@code qits.edge.wireguard.*}: the peer list, for {@link
 * PeerLog} and the device alike ({@link WireguardPeers}), and the device itself ({@code
 * eu.wohlben.qits.edge.wireguard.WireguardDevice}). Every default is the private edge's; a private
 * edge gets {@code enabled} and its listen port from {@link PrivateVariantConfigSource}.
 */
@ConfigMapping(prefix = "qits.edge.wireguard")
public interface WireguardConfig {

  /**
   * The static peers, {@code name:base64PublicKey:tunnelIp} comma separated — see {@link
   * WireguardPeers}. Absent is the image's own {@code wireguard/static-peers} on a private edge and
   * no peers on a public one.
   */
  Optional<String> peers();

  /**
   * Whether this process configures {@code wg0} over its UAPI socket, {@code
   * QITS_EDGE_WIREGUARD_ENABLED}. The device itself is brought up by the image's init helper.
   */
  @WithDefault("false")
  boolean enabled();

  /** The UDP port wireguard-go listens on, which the deployment publishes in host mode. */
  @WithDefault("47812")
  int listenPort();

  /** The private key, base64 of its 32 raw bytes; generated on first start when absent. */
  @WithDefault("/work/.wireguard/private.key")
  String keyFile();

  /** wireguard-go's UAPI socket, chowned to this process' uid by the init helper. */
  @WithDefault("/var/run/wireguard/wg0.sock")
  String uapiSocket();
}
