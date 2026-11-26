package eu.wohlben.qits.edge;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClosedException;
import io.vertx.core.http.HttpServerRequest;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.jboss.logging.Logger;

/**
 * What the edge says when a connection closes under an upstream exchange — so that Vert.x does not
 * say it at ERROR instead (qits-2717).
 *
 * <p><b>Why it exists.</b> {@code HttpClientRequestImpl.handleException} logs {@code ERROR
 * <message>} — no stack trace, no trace id, its own logger — whenever the connection under a client
 * request fails before that request was ended and nobody gave the request an exception handler.
 * Neither of the edge's two upstream paths did:
 *
 * <ul>
 *   <li>{@link EdgeWebSocketUpgrade} hands the handshake over with {@code connect()}, which never
 *       ends the request. Every spliced socket therefore ended in {@code ERROR Connection was
 *       closed} when it closed, however it closed: 1,800 in 13 hours on the public edge, one per
 *       qits-egress forward socket, and one per daemon socket on the private and overlay edges. It
 *       was the whole of error issues {@code 4fc94e96…}, {@code c9db9ec1…} and {@code 5f8b0e20…}.
 *   <li>{@code vertx-http-proxy} pipes the inbound body into the outbound request and sets no
 *       handler on it either, so an upload whose connection dies mid-body did the same.
 * </ul>
 *
 * <p><b>The classification.</b> A WebSocket that ends is a stream ending: DEBUG. One that ends in a
 * reset — the upstream or the client gone without closing — is worth reading: WARN, naming the
 * upstream and the path, once per socket. A failure before the upstream accepted the upgrade is
 * DEBUG, because {@link EdgeWebSocketUpgrade} already logs it and answers 502/503 or relays the
 * upstream's own refusal. For a proxied request, a client that went away is DEBUG (there is nobody
 * left to answer); an upstream that closed or reset under an unfinished request is WARN, and the
 * proxy answers the caller 502 as it always did. A genuine outage keeps its own WARN and status
 * where the connection is acquired.
 *
 * <p>Nothing is silenced by configuration: with a handler on both paths Vert.x logs nothing for
 * them, so an ERROR from {@code HttpClientRequestImpl} still means a request nobody handles.
 */
final class UpstreamCloses {

  private static final Logger LOG = Logger.getLogger(UpstreamCloses.class);

  private UpstreamCloses() {}

  /**
   * Give a proxied request its exception handler. Called where the origin provider is granted the
   * request, so the first attempt and {@link UpstreamRetry}'s fresh one both carry it.
   */
  static void watch(HttpClientRequest outbound, HttpServerRequest inbound, Upstream upstream) {
    String method = inbound.method().name();
    String path = inbound.path();
    outbound.exceptionHandler(
        failure -> {
          if (inbound.response().closed()) {
            LOG.debugf(
                "the client went away during %s %s to %s: %s", method, path, upstream, failure);
          } else {
            LOG.warnf("%s closed the connection during %s %s: %s", upstream, method, path, failure);
          }
        });
    // The connection is told of a reset too, and without a handler of its own Vert.x logs that at
    // ERROR from ConnectionBase. The request's handler above already said what happened, so this
    // one only keeps the record. A pooled connection carries the last exchange's handler, which is
    // why it is set on every grant rather than once per connection.
    if (outbound.connection() != null) {
      outbound
          .connection()
          .exceptionHandler(
              failure -> LOG.debugf("the connection to %s failed: %s", upstream, failure));
    }
  }

  /**
   * The exception handler of one WebSocket's upstream side, for its handshake request and its
   * connection alike: both can be told of the same reset, and it is reported once.
   *
   * @param accepted whether the upstream has answered the handshake with 101
   */
  static Handler<Throwable> socket(Upstream upstream, String path, BooleanSupplier accepted) {
    AtomicBoolean warned = new AtomicBoolean();
    return failure -> {
      if (!accepted.getAsBoolean()) {
        LOG.debugf("the WebSocket %s to %s failed before the upgrade: %s", path, upstream, failure);
      } else if (failure instanceof HttpClosedException) {
        LOG.debugf("the WebSocket %s to %s closed", path, upstream);
      } else if (warned.compareAndSet(false, true)) {
        LOG.warnf("the WebSocket %s to %s ended abruptly: %s", path, upstream, failure);
      }
    };
  }
}
