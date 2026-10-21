package eu.wohlben.qits.edge;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.vertx.http.runtime.filters.QuarkusRequestWrapper;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.logging.Logger;

/**
 * <b>Tell Quarkus' HTTP drain that an exchange is over when Vert.x never will</b> (qits-1170).
 *
 * <p>{@code GracefulShutdownFilter} counts every inbound request up as it arrives and down when its
 * {@code QuarkusRequestWrapper} runs its done handlers — which it does only from the response's
 * end, close or exception handler. On SIGTERM the drain waits for that count to reach zero. Two
 * things the edge does every day end an exchange with none of those handlers running, so each
 * leaves the count one higher <b>for the rest of the process's life</b>, and a drain after a day of
 * uptime waits out its whole {@code quarkus.shutdown.timeout} however few transfers are moving. The
 * live redeploy of 2026-10-21 did exactly that: 13 streams closed at 20:57:36, then 9m30s of
 * "Graceful Shutdown DOWN" and "Timed out waiting for graceful shutdown".
 *
 * <ol>
 *   <li><b>A response the proxy resets.</b> When an upstream breaks mid-body — a stream connection
 *       {@link UpstreamPools#closeAll} closed, an SSE channel whose upstream was redeployed, a
 *       download whose upstream died — {@code vertx-http-proxy} calls {@code reset()} on the
 *       inbound response. On HTTP/1.x that is {@code Http1xServerResponse.close()}, which marks the
 *       response closed BEFORE the connection goes; when it does, {@code handleClosed} sees the
 *       mark and returns without running a single handler. {@link #handleProxyResponse} sees the
 *       failed send and settles it.
 *   <li><b>A spliced WebSocket.</b> {@code toNetSocket()} writes the {@code 101} and hands the
 *       channel to a plain socket; the HTTP connection that owned the response is gone from the
 *       pipeline and never hears the close. {@link EdgeWebSocketUpgrade} settles it when the
 *       inbound socket closes.
 * </ol>
 *
 * <p><b>Why reflection.</b> The only way to run the done handlers is {@code
 * QuarkusRequestWrapper.done()}, which is package-private, and every public handler that would call
 * it is unreachable once Vert.x has decided the response is closed or written. {@code done()} is
 * idempotent — a compare-and-set on its own flag — so settling an exchange whose handlers did run
 * changes nothing. The method is registered for native reflection here; a release that renames it
 * costs one WARN and the old timeout, never a failed request.
 */
@RegisterForReflection(targets = QuarkusRequestWrapper.class, methods = true, fields = false)
final class DrainCount implements ProxyInterceptor {

  private static final Logger LOG = Logger.getLogger(DrainCount.class);

  /** Before {@link #DONE}, whose initialiser may already warn. */
  private static final AtomicBoolean WARNED = new AtomicBoolean();

  private static final Method DONE = done();

  /**
   * The proxy's send of the response failed, which means it has reset the inbound response; the
   * drain is told the exchange is over. A successful send ended the response, whose own handler
   * already did.
   */
  @Override
  public Future<Void> handleProxyResponse(ProxyContext context) {
    HttpServerRequest inbound = context.request().proxiedRequest();
    return context.sendResponse().onFailure(failure -> settle(inbound));
  }

  /**
   * Counts this inbound exchange as finished in Quarkus' HTTP drain. Safe to call more than once,
   * on an exchange that is already settled, and on a request Quarkus never wrapped (a test's plain
   * Vert.x server), where it does nothing.
   */
  static void settle(HttpServerRequest request) {
    if (DONE == null) {
      return;
    }
    QuarkusRequestWrapper wrapper;
    try {
      wrapper = QuarkusRequestWrapper.get(request);
    } catch (RuntimeException notWrapped) {
      // get() reads a marker cookie only Quarkus' wrapper answers; a bare request answers null.
      return;
    }
    if (wrapper == null) {
      return;
    }
    try {
      DONE.invoke(wrapper);
    } catch (ReflectiveOperationException | RuntimeException failure) {
      warnOnce(failure);
    }
  }

  private static Method done() {
    try {
      Method done = QuarkusRequestWrapper.class.getDeclaredMethod("done");
      done.setAccessible(true);
      return done;
    } catch (ReflectiveOperationException | RuntimeException failure) {
      warnOnce(failure);
      return null;
    }
  }

  private static void warnOnce(Throwable failure) {
    if (WARNED.compareAndSet(false, true)) {
      LOG.warnf(
          "cannot settle a reset or upgraded exchange in Quarkus' HTTP drain (%s): a redeploy will"
              + " wait out quarkus.shutdown.timeout",
          failure);
    }
  }
}
