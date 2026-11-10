package eu.wohlben.qits.edge;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

/**
 * The private edge's WireGuard settings, {@code qits.edge.wireguard.*}: the hand-stated peers
 * ({@link WireguardPeers}), and the device itself ({@code
 * eu.wohlben.qits.edge.wireguard.WireguardDevice}). Every default is the private edge's; a private
 * edge gets {@code enabled} and its listen port from {@link PrivateVariantConfigSource}.
 */
@ConfigMapping(prefix = "qits.edge.wireguard")
public interface WireguardConfig {

  /**
   * Peers stated by hand, {@code name:base64PublicKey:tunnelIp} comma separated — see {@link
   * WireguardPeers}. Absent is none; whatever is stated is merged with the peers
   * qits-network-access registers.
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

  /**
   * wg0's own address and prefix, {@code QITS_EDGE_WIREGUARD_ADDRESS}: the init helper gives the
   * device this address, and {@code PrivateEdgeAnnounced} tells peers the tunnel address and block
   * it implies. Its default in application.properties equals {@code docker/edge-init/main.go}'s.
   */
  String address();

  /** wg0's MTU, {@code QITS_EDGE_WIREGUARD_MTU}; its default equals the init helper's too. */
  int mtu();

  /**
   * The deployed identity {@code PrivateEdgeAnnounced} names: the deployer's {@code
   * QITS_APPLICATION}.
   */
  String application();

  /** The private key, base64 of its 32 raw bytes; generated on first start when absent. */
  @WithDefault("/work/.wireguard/private.key")
  String keyFile();

  /** wireguard-go's UAPI socket, chowned to this process' uid by the init helper. */
  @WithDefault("/var/run/wireguard/wg0.sock")
  String uapiSocket();
}
