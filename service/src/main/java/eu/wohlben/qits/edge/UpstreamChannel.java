package eu.wohlben.qits.edge;

import io.netty.channel.Channel;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.net.impl.ConnectionBase;

/**
 * The one place the edge reaches under Vert.x's public API: from an upstream {@link HttpConnection}
 * to the Netty {@link Channel} it rides on.
 *
 * <p><b>Why this needs internal API at all.</b> The keepalive timers {@link UpstreamKeepAlive} sets
 * are per socket, and Vert.x 4.5 has no public way to set them on a CLIENT connection: {@code
 * TCPSSLOptions} carries {@code tcpKeepAliveIdleSeconds} and its two siblings, but no transport
 * applies them to an outbound socket — {@code Transport.configure(ClientOptionsBase)} sets {@code
 * SO_KEEPALIVE} alone, the JDK transport overrides nothing, and epoll applies them server side
 * only. The connect handler hands over an {@link HttpConnection}, and every connection the HTTP
 * client makes is a {@link ConnectionBase}, whose {@code channel()} is public on a class in an
 * {@code impl} package. That cast is the whole of the dependency, and it lives here so a Vert.x
 * upgrade that moves it breaks one small class rather than the router.
 *
 * <p>A cast that no longer holds is not an outage: the caller logs it and the connection is used as
 * it is, with the transport's default keepalive timers.
 */
final class UpstreamChannel {

  private UpstreamChannel() {}

  /**
   * The Netty channel under one upstream connection.
   *
   * @throws ClassCastException when a Vert.x release stopped building client connections on {@link
   *     ConnectionBase}
   */
  static Channel of(HttpConnection connection) {
    return ((ConnectionBase) connection).channel();
  }
}
