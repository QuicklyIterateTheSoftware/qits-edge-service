package eu.wohlben.qits.edge.dns;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Optional;

/**
 * The private edge's tunnel DNS responder, and the overlay edge's overlay-only one, {@code
 * qits.edge.dns.*} — see {@link TunnelDns}. Its zone is not here: it is {@code qits.edge.domain},
 * the one domain the platform states.
 */
@ConfigMapping(prefix = "qits.edge.dns")
public interface TunnelDnsConfig {

  /**
   * Whether the responder runs at all, {@code QITS_EDGE_DNS_ENABLED}. Only a private edge sets it.
   */
  @WithDefault("false")
  boolean enabled();

  /** The local address both sockets bind: the edge's own end of the tunnel. */
  @WithDefault("100.96.0.1")
  String bind();

  /**
   * The overlay whose callers this responder also answers, {@code 10.231.0.0/20} on the private
   * edge ({@code qits-private}); unset, the default, it answers the tunnel alone. Set, every IPv4
   * address of this container inside the block, as found at start, gets a UDP and a TCP socket of
   * its own beside the tunnel's — one per address, never the wildcard, because a UDP socket bound
   * to {@code 0.0.0.0} cannot tell which address was asked and a private name would be answered
   * with the tunnel's, which the overlay cannot route (qits-1991).
   */
  Optional<String> overlayCidr();

  /**
   * This edge's own wire alias on the overlay, {@code <env>-qits-edge-overlay} on the overlay edge
   * (qits-2304) and unset everywhere else. Set, and only on an edge without a tunnel, a routed name
   * asked on an overlay socket is answered with what the alias resolves to through this container's
   * resolver — the swarm service's VIP, which outlives the task that answered — rather than the
   * task's own address. Looked up at start and every five minutes ({@link
   * TunnelDns.ServiceAddress}); an answer outside {@link #overlayCidr()} is never used, and until
   * one is known the task address is answered as before.
   */
  Optional<String> serviceAlias();

  /** The port every socket binds, UDP and TCP alike, the tunnel's and the overlay's. */
  @WithDefault("53")
  int port();

  /**
   * The public edge's IPv4 address — the answer for every in-zone name this edge does not route
   * itself — as an OVERRIDE. Unset, which is the normal case, the responder looks it up: the zone
   * apex's A record, at start and every five minutes ({@link TunnelDns.PublicAddress}). Set, it
   * must be an IPv4 literal and is used as is.
   */
  Optional<String> publicAddress();

  /**
   * The resolvers a name outside the zone, asked on the tunnel, is forwarded to (qits-2262): a
   * comma list of {@code address[:port]}, IPv6 as {@code [address]:port}, port 53 when none is
   * given. Unset, the normal case, they are the {@code nameserver} lines of {@code
   * /etc/resolv.conf}, the container's own resolver. Address literals only, and never one of this
   * responder's own sockets. The overlay sockets never forward.
   */
  Optional<String> upstream();

  /** How long a forwarded query waits for an upstream before it is answered SERVFAIL. */
  @WithDefault("2s")
  Duration upstreamTimeout();

  /** The TTL of every answer, short so a deployment changing exposure is followed quickly. */
  @WithDefault("30s")
  Duration ttl();
}
