package eu.wohlben.qits.edge;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/**
 * {@code qits.edge.wireguard.peers}, read: {@code name:base64PublicKey:tunnelIp}, comma separated —
 * {@code laptop:<44-char key>:100.96.0.3}. Base64 holds no {@code :} and no {@code ,}, so the two
 * separators are unambiguous.
 *
 * <p><b>An operator override, and nothing more</b> (epic qits-1977). The peers a private edge
 * serves come from qits-network-access, live, as {@code PeerRegistered} and {@code PeerRevoked}
 * ({@code eu.wohlben.qits.edge.wireguard.LivePeers}); this property's peers are merged with those,
 * so a peer can still be stated by hand without a registration. Unset is none, on every edge — the
 * classpath file of static peers epic qits-1976 shipped is gone.
 *
 * <p><b>Lenient, and loud about it.</b> A malformed entry is dropped with a WARN naming its
 * position and what is wrong with it, never its key, and the rest of the list still counts: one
 * typo must not unname every other peer.
 */
@ApplicationScoped
public class WireguardPeers {

  private static final Logger LOG = Logger.getLogger(WireguardPeers.class);

  /** A WireGuard public key is 32 raw bytes. */
  private static final int KEY_BYTES = 32;

  /** One peer: who it is, the key it handshakes with, and the one tunnel address it may use. */
  public record Peer(String name, String publicKey, InetAddress tunnelIp) {}

  @Inject WireguardConfig wireguard;

  private volatile List<Peer> peers;

  /** The configured peers, read once. */
  public List<Peer> peers() {
    List<Peer> read = peers;
    if (read == null) {
      read = List.copyOf(configured(wireguard.peers(), LOG::warn));
      peers = read;
    }
    return read;
  }

  /** {@link #peers()}' reading, with its input passed in: the property, or none. */
  static List<Peer> configured(Optional<String> property, Consumer<String> warn) {
    if (property.isPresent() && !property.get().isBlank()) {
      return parse(property.get(), "qits.edge.wireguard.peers", warn);
    }
    return List.of();
  }

  static List<Peer> parse(String raw, Consumer<String> warn) {
    return parse(raw, "qits.edge.wireguard.peers", warn);
  }

  /**
   * Every well-formed entry of {@code raw}, in order; null or blank is no peers.
   *
   * @param source where the list was read, named in each warning
   * @param warn told one sentence per entry that was dropped
   */
  static List<Peer> parse(String raw, String source, Consumer<String> warn) {
    List<Peer> peers = new ArrayList<>();
    if (raw == null || raw.isBlank()) {
      return peers;
    }
    String[] entries = raw.split(",", -1);
    for (int i = 0; i < entries.length; i++) {
      String entry = entries[i].strip();
      if (entry.isEmpty()) {
        continue;
      }
      String problem = null;
      String[] parts = entry.split(":", -1);
      Peer peer = null;
      if (parts.length != 3) {
        problem = "it has " + parts.length + " fields where name:publicKey:tunnelIp has three";
      } else {
        String name = parts[0].strip();
        String key = parts[1].strip();
        InetAddress ip = Cidr.literal(parts[2]);
        if (!HostEnvironments.isLabel(name.toLowerCase(java.util.Locale.ROOT))) {
          problem = "its name is not a DNS-label-shaped word";
        } else if (!isKey(key)) {
          problem = "its public key is not 32 bytes of base64";
        } else if (ip == null) {
          problem = "its tunnel address is not an IP address";
        } else {
          peer = new Peer(name, key, ip);
        }
      }
      if (peer == null) {
        warn.accept(
            source
                + " entry "
                + (i + 1)
                + " is ignored: "
                + problem
                + " (name:base64PublicKey:tunnelIp)");
      } else {
        peers.add(peer);
      }
    }
    return peers;
  }

  /** Whether {@code key} is base64 of 32 bytes, the shape of a WireGuard public key. */
  public static boolean isKey(String key) {
    try {
      return Base64.getDecoder().decode(key).length == KEY_BYTES;
    } catch (IllegalArgumentException notBase64) {
      return false;
    }
  }
}
