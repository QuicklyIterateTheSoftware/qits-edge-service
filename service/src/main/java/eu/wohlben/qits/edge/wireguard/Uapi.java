package eu.wohlben.qits.edge.wireguard;

import eu.wohlben.qits.edge.WireguardPeers;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * WireGuard's cross-platform UAPI, the text protocol wireguard-go serves on {@code
 * /var/run/wireguard/<iface>.sock}: {@code key=value} lines, an operation ({@code set=1}, {@code
 * get=1}) first and a blank line last, answered by {@code errno=<n>} and a blank line. Keys go over
 * it in hex. One connection per operation; nothing here is reflective.
 */
final class Uapi {

  /** How long one operation may take before the socket is given up on. */
  static final Duration TIMEOUT = Duration.ofSeconds(3);

  static final String GET = "get=1\n\n";

  private static final HexFormat HEX = HexFormat.of();

  private Uapi() {}

  /**
   * The whole configuration, replacing whatever the device held: key, port and exactly these peers,
   * each allowed its one tunnel address.
   */
  static String setRequest(byte[] privateKey, int listenPort, List<WireguardPeers.Peer> peers) {
    StringBuilder request = new StringBuilder("set=1\n");
    request.append("private_key=").append(HEX.formatHex(privateKey)).append('\n');
    request.append("listen_port=").append(listenPort).append('\n');
    request.append("replace_peers=true\n");
    for (WireguardPeers.Peer peer : peers) {
      request
          .append("public_key=")
          .append(HEX.formatHex(Base64.getDecoder().decode(peer.publicKey())))
          .append('\n');
      request.append("replace_allowed_ips=true\n");
      request
          .append("allowed_ip=")
          .append(peer.tunnelIp().getHostAddress())
          .append(peer.tunnelIp() instanceof Inet4Address ? "/32" : "/128")
          .append('\n');
    }
    return request.append('\n').toString();
  }

  /** Send {@code request} and return the answer, through its terminating blank line. */
  static String exchange(Path socket, String request) throws IOException {
    try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(socket));
        Selector selector = Selector.open()) {
      ByteBuffer out = ByteBuffer.wrap(request.getBytes(StandardCharsets.US_ASCII));
      while (out.hasRemaining()) {
        channel.write(out);
      }
      channel.configureBlocking(false);
      channel.register(selector, SelectionKey.OP_READ);
      ByteArrayOutputStream answer = new ByteArrayOutputStream();
      ByteBuffer in = ByteBuffer.allocate(4096);
      long deadline = System.nanoTime() + TIMEOUT.toNanos();
      while (true) {
        long left = (deadline - System.nanoTime()) / 1_000_000;
        if (left <= 0) {
          throw new IOException(socket + " did not answer within " + TIMEOUT.toSeconds() + "s");
        }
        selector.select(left);
        selector.selectedKeys().clear();
        int read = channel.read(in);
        if (read < 0) {
          break;
        }
        if (read > 0) {
          answer.write(in.array(), 0, in.position());
          in.clear();
          if (answer.toString(StandardCharsets.US_ASCII).endsWith("\n\n")) {
            break;
          }
        }
      }
      return answer.toString(StandardCharsets.US_ASCII);
    }
  }

  /** The value of the last {@code key=} line of {@code answer}, or null. */
  static String value(String answer, String key) {
    String found = null;
    for (String line : answer.split("\n")) {
      if (line.startsWith(key + "=")) {
        found = line.substring(key.length() + 1);
      }
    }
    return found;
  }

  /** The answer's {@code errno}; an answer without one reads as -1, a failure. */
  static int errno(String answer) {
    String errno = value(answer, "errno");
    try {
      return errno == null ? -1 : Integer.parseInt(errno.strip());
    } catch (NumberFormatException unreadable) {
      return -1;
    }
  }
}
