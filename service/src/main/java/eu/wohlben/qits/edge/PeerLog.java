package eu.wohlben.qits.edge;

import java.net.InetAddress;
import java.util.List;

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
 * edge — and {@code unknown} for anything else.
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

  /** Each peer's tunnel address as a block of one, so it matches in either socket spelling. */
  private final List<Named> peers;

  private record Named(String name, Cidr address) {}

  private final Cidr overlay;

  PeerLog(List<WireguardPeers.Peer> peers, Cidr overlay) {
    this.peers =
        peers.stream()
            .map(peer -> new Named(peer.name(), Cidr.parse(peer.tunnelIp().getHostAddress())))
            .toList();
    this.overlay = overlay;
  }

  /** Who {@code remoteIp} is: a configured peer's name, {@link #OVERLAY}, or {@link #UNKNOWN}. */
  String peer(String remoteIp) {
    InetAddress remote = Cidr.literal(remoteIp);
    if (remote == null) {
      return UNKNOWN;
    }
    for (Named peer : peers) {
      // A peer at 100.96.0.2 is named whether the socket reports it so or as ::ffff:100.96.0.2.
      if (peer.address().contains(remote)) {
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
