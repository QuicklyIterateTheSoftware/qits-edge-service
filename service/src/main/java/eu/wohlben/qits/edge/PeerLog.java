package eu.wohlben.qits.edge;

import java.net.InetAddress;
import java.util.List;
import java.util.function.Supplier;

/**
 * The private edge's one line per request, on {@link #CATEGORY} at INFO:
 *
 * <pre>
 * peer=&lt;name|overlay|unknown&gt; ip=&lt;remote ip&gt; method=&lt;m&gt; host=&lt;authority&gt; path=&lt;path&gt; status=&lt;code&gt;
 * </pre>
 *
 * <p>It is how an operator reads who used the tunnel. {@code peer} is the name a tunnel address is
 * configured under ({@link WireguardPeers}), {@code overlay} for a remote inside {@code
 * qits.edge.peer-log.overlay-cidr} — a private service on {@code qits-private} calling through its
 * edge — and {@code unknown} for anything else. The peers are read at each request ({@code
 * LivePeers}, epic qits-1977), so a peer registered a second ago is named on its first request and
 * a revoked one is not named any more.
 *
 * <p><b>The remote address is the socket's</b>, never {@code X-Forwarded-For}: the edge sets that
 * header only when it is absent and documents it as untrusted ({@code EdgeHeaders.applyForwarded}),
 * and a peer name read from it would be a name any caller could claim.
 *
 * <p>Public edges do not write it; their volume is not the question it answers.
 */
final class PeerLog {

  /** The logger the line is written on, so it can be filtered or routed on its own. */
  static final String CATEGORY = "eu.wohlben.qits.edge.peer";

  static final String OVERLAY = "overlay";

  static final String UNKNOWN = "unknown";

  /** The peers as they are now, read at each request. */
  private final Supplier<List<WireguardPeers.Peer>> peers;

  private final Cidr overlay;

  PeerLog(Supplier<List<WireguardPeers.Peer>> peers, Cidr overlay) {
    this.peers = peers;
    this.overlay = overlay;
  }

  /** A fixed list of peers, for a test. */
  PeerLog(List<WireguardPeers.Peer> peers, Cidr overlay) {
    this(() -> peers, overlay);
  }

  /** Who {@code remoteIp} is: a configured peer's name, {@link #OVERLAY}, or {@link #UNKNOWN}. */
  String peer(String remoteIp) {
    InetAddress remote = Cidr.literal(remoteIp);
    if (remote == null) {
      return UNKNOWN;
    }
    for (WireguardPeers.Peer peer : peers.get()) {
      // A peer at 100.96.0.2 is named whether the socket reports it so or as ::ffff:100.96.0.2:
      // its address as a block of one matches either spelling.
      if (Cidr.parse(peer.tunnelIp().getHostAddress()).contains(remote)) {
        return peer.name();
      }
    }
    return overlay != null && overlay.contains(remote) ? OVERLAY : UNKNOWN;
  }

  /** The line itself. Every value came off the wire except {@code peer}, so each is made inert. */
  static String line(String peer, String ip, String method, String host, String path, int status) {
    return "peer="
        + inert(peer)
        + " ip="
        + inert(ip)
        + " method="
        + inert(method)
        + " host="
        + inert(host)
        + " path="
        + inert(path)
        + " status="
        + status;
  }

  /**
   * A value that cannot end the line or forge a field: absent is {@code -}, and a space, a control
   * character or a {@code %} is percent-encoded, so {@code path=/a b host=x} is read as one path.
   */
  static String inert(String value) {
    if (value == null || value.isEmpty()) {
      return "-";
    }
    StringBuilder out = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c <= 0x20 || c == 0x7f || c == '%') {
        out.append('%').append(String.format("%02X", (int) c));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
