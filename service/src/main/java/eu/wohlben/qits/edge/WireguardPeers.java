package eu.wohlben.qits.edge;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@code qits.edge.wireguard.peers}, read: {@code name:base64PublicKey:tunnelIp}, comma separated —
 * {@code proof-workspace:<44-char key>:100.96.0.2}. Base64 holds no {@code :} and no {@code ,}, so
 * the two separators are unambiguous.
 *
 * <p>One list, two readers. The peer log names a caller by its tunnel address here; the WireGuard
 * device configures the same peers from it. Static peers by hand are the whole of this slice — live
 * registration is qits-1977's.
 *
 * <p><b>Lenient, and loud about it.</b> A malformed entry is dropped with a WARN naming its
 * position and what is wrong with it, never its key, and the rest of the list still counts: one
 * typo must not unname every other peer.
 */
final class WireguardPeers {

  /** A WireGuard public key is 32 raw bytes. */
  private static final int KEY_BYTES = 32;

  /** One peer: who it is, the key it handshakes with, and the one tunnel address it may use. */
  record Peer(String name, String publicKey, InetAddress tunnelIp) {}

  private WireguardPeers() {}

  /**
   * Every well-formed entry of {@code raw}, in order; null or blank is no peers.
   *
   * @param warn told one sentence per entry that was dropped
   */
  static List<Peer> parse(String raw, Consumer<String> warn) {
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
            "qits.edge.wireguard.peers entry "
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

  private static boolean isKey(String key) {
    try {
      return Base64.getDecoder().decode(key).length == KEY_BYTES;
    } catch (IllegalArgumentException notBase64) {
      return false;
    }
  }
}
