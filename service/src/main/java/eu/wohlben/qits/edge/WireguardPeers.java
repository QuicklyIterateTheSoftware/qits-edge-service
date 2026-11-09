package eu.wohlben.qits.edge;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * {@code qits.edge.wireguard.peers}, read: {@code name:base64PublicKey:tunnelIp}, comma separated —
 * {@code proof-workspace:<44-char key>:100.96.0.2}. Base64 holds no {@code :} and no {@code ,}, so
 * the two separators are unambiguous.
 *
 * <p>One list, two readers. The peer log names a caller by its tunnel address here; the WireGuard
 * device configures the same peers from it. Static peers by hand are the whole of this slice — live
 * registration is qits-1977's.
 *
 * <p><b>Where the list comes from</b> ({@link #peers()}, the one reading both use): {@code
 * qits.edge.wireguard.peers} when it is set; else, on a {@link ExposureClass#PRIVATE} edge, the
 * classpath file {@value #STATIC_PEERS_RESOURCE} — the proof peer, shipped with the image until
 * qits-1977 replaces static peers, and removed by deleting its line or the file; else none. A
 * public edge never reads the file, so the proof peer can never name a caller there.
 *
 * <p><b>Lenient, and loud about it.</b> A malformed entry is dropped with a WARN naming its
 * position and what is wrong with it, never its key, and the rest of the list still counts: one
 * typo must not unname every other peer.
 */
@ApplicationScoped
public class WireguardPeers {

  /**
   * The static peers shipped with the image, one {@code name:key:ip} per line, {@code #} comments.
   */
  static final String STATIC_PEERS_RESOURCE = "wireguard/static-peers";

  private static final Logger LOG = Logger.getLogger(WireguardPeers.class);

  /** A WireGuard public key is 32 raw bytes. */
  private static final int KEY_BYTES = 32;

  /** One peer: who it is, the key it handshakes with, and the one tunnel address it may use. */
  public record Peer(String name, String publicKey, InetAddress tunnelIp) {}

  @Inject WireguardConfig wireguard;

  @Inject EdgeConfig edge;

  private volatile List<Peer> peers;

  /** The configured peers, read once: see the class comment for where they come from. */
  public List<Peer> peers() {
    List<Peer> read = peers;
    if (read == null) {
      read = List.copyOf(configured(wireguard.peers(), edge.exposure(), LOG::warn));
      peers = read;
    }
    return read;
  }

  /** {@link #peers()}' reading, with its inputs passed in. */
  static List<Peer> configured(
      Optional<String> property, ExposureClass exposure, Consumer<String> warn) {
    if (property.isPresent() && !property.get().isBlank()) {
      return parse(property.get(), "qits.edge.wireguard.peers", warn);
    }
    if (exposure != ExposureClass.PRIVATE) {
      return List.of();
    }
    return parse(staticPeers(), STATIC_PEERS_RESOURCE, warn);
  }

  /** The classpath file's entries, comma-joined; absent is none. */
  static String staticPeers() {
    try (InputStream in =
        WireguardPeers.class.getClassLoader().getResourceAsStream(STATIC_PEERS_RESOURCE)) {
      if (in == null) {
        return null;
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .map(String::strip)
          .filter(line -> !line.isEmpty() && !line.startsWith("#"))
          .collect(Collectors.joining(","));
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
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

  private static boolean isKey(String key) {
    try {
      return Base64.getDecoder().decode(key).length == KEY_BYTES;
    } catch (IllegalArgumentException notBase64) {
      return false;
    }
  }
}
