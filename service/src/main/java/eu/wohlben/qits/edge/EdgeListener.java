package eu.wohlben.qits.edge;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.logging.Logger;

/**
 * What every connection meets before anything else in this process: the listener allow-list, and on
 * a private edge the peer log (epic qits-1976).
 *
 * <p><b>Why a route, and why the first one.</b> Quarkus offers no connection handler on the server
 * it builds — {@code HttpServerOptionsCustomizer} sees options, not connections — so the earliest
 * hook an application has is a route on the {@code @Observes Router}. This one is ordered {@link
 * #ROUTE_ORDER}, {@code Integer.MIN_VALUE}, the slot Quarkus' own bookkeeping handlers share, so it
 * runs before the body handler, before {@code /q}, before {@link EdgeRouter}'s catch-all and so
 * before every WebSocket upgrade that route splices. On HTTPS the TLS handshake and the request
 * head have been read by Vert.x by then — nothing short of a socket filter could avoid that — but
 * no byte of the body, no Host reading, no gate and no upstream: a refused connection is closed
 * here, on its first request, with nothing written back.
 *
 * <p><b>Nothing is installed when there is nothing to do</b>: a public edge with no allow-list,
 * which is every edge before this epic, routes exactly as it did.
 */
@ApplicationScoped
public class EdgeListener {

  /** Ahead of every route this application or Quarkus registers that reads a request. */
  public static final int ROUTE_ORDER = Integer.MIN_VALUE;

  private static final Logger LOG = Logger.getLogger(EdgeListener.class);

  private static final Logger PEERS = Logger.getLogger(PeerLog.CATEGORY);

  @Inject EdgeConfig config;

  @Inject WireguardConfig wireguard;

  void init(@Observes Router router) {
    ListenerAllowList allowed =
        ListenerAllowList.of(config.listen().allowedLocalCidrs().orElse(List.of()));
    PeerLog peerLog =
        config.exposure() == ExposureClass.PRIVATE
            ? new PeerLog(
                WireguardPeers.parse(wireguard.peers().orElse(null), LOG::warn),
                Cidr.parse(config.peerLog().overlayCidr()))
            : null;
    LOG.infof(
        "a %s edge: local addresses %s, peer log %s",
        config.exposure().name().toLowerCase(java.util.Locale.ROOT),
        allowed,
        peerLog == null ? "off" : "on");
    if (allowed.any() && peerLog == null) {
      return;
    }
    router.route().order(ROUTE_ORDER).handler(handler(allowed, peerLog, false));
  }

  /**
   * The route's handler, with its inputs passed in so a plain Vert.x server can be given the very
   * same one in a test.
   *
   * @param peerLog null on a public edge, which writes no peer line
   * @param logLoopback whether a loopback caller gets a peer line: never in production, where the
   *     loopback is the deployer's health check every few seconds and never a peer
   */
  static Handler<RoutingContext> handler(
      ListenerAllowList allowed, PeerLog peerLog, boolean logLoopback) {
    return rc -> {
      HttpServerRequest request = rc.request();
      HttpConnection connection = request.connection();
      if (!allowed.any()) {
        String local = hostAddress(connection.localAddress());
        if (!allowed.admits(local)) {
          LOG.debugf(
              "closed a connection on local address %s from %s: outside"
                  + " qits.edge.listen.allowed-local-cidrs %s",
              local, hostAddress(connection.remoteAddress()), allowed);
          connection.close();
          return;
        }
      }
      String remote = hostAddress(connection.remoteAddress());
      if (peerLog != null && (logLoopback || !ListenerAllowList.loopback(remote))) {
        logOnce(rc, peerLog, remote);
      }
      rc.next();
    };
  }

  /**
   * One line per request, written when the status is known: as the response head goes out — which a
   * spliced WebSocket's {@code 101} does too, while its end handler never runs (see {@code
   * EdgeWebSocketUpgrade}) — or, for an exchange that ended without one, when it ends.
   */
  private static void logOnce(RoutingContext rc, PeerLog peerLog, String remote) {
    AtomicBoolean written = new AtomicBoolean();
    Runnable write =
        () -> {
          if (written.compareAndSet(false, true)) {
            HttpServerRequest request = rc.request();
            PEERS.info(
                PeerLog.line(
                    peerLog.peer(remote),
                    remote,
                    request.method().name(),
                    authority(request),
                    request.path(),
                    rc.response().getStatusCode()));
          }
        };
    rc.addHeadersEndHandler(ignored -> write.run());
    rc.addEndHandler(ignored -> write.run());
  }

  private static String hostAddress(SocketAddress address) {
    return address == null ? null : address.hostAddress();
  }

  private static String authority(HttpServerRequest request) {
    HostAndPort authority = request.authority();
    return authority != null ? authority.host() : request.getHeader("Host");
  }
}
