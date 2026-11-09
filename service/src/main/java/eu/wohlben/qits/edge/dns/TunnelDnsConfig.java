package eu.wohlben.qits.edge.dns;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Optional;

/**
 * The private edge's tunnel DNS responder, {@code qits.edge.dns.*} — see {@link TunnelDns}. Its
 * zone is not here: it is {@code qits.edge.domain}, the one domain the platform states.
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

  /** The port both sockets bind, UDP and TCP alike. */
  @WithDefault("53")
  int port();

  /**
   * The public edge's IPv4 address — the answer for every in-zone name this edge does not route
   * itself — as an OVERRIDE. Unset, which is the normal case, the responder looks it up: the zone
   * apex's A record, at start and every five minutes ({@link TunnelDns.PublicAddress}). Set, it
   * must be an IPv4 literal and is used as is.
   */
  Optional<String> publicAddress();

  /** The TTL of every answer, short so a deployment changing exposure is followed quickly. */
  @WithDefault("30s")
  Duration ttl();
}
