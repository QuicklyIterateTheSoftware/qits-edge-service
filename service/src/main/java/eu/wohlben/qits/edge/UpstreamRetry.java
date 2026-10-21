package eu.wohlben.qits.edge;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpClosedException;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.httpproxy.OriginRequestProvider;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import io.vertx.httpproxy.ProxyResponse;
import java.io.IOException;
import org.jboss.logging.Logger;

/**
 * One second attempt, on a fresh connection, for a bodyless GET or HEAD whose upstream never
 * answered — and a 502 after that, exactly as before (qits-1170).
 *
 * <p><b>Why it exists.</b> An upstream task swarm is replacing closes its idle keep-alive
 * connections as it drains, and the FIN can lose the race against the pool: the proxy client hands
 * a connection the peer has already closed to the next request, the write meets a reset, and the
 * caller got a 502 for a request that never reached anything. CI downloads through the edge — a 201
 * MB jar, a docker layer — died that way on every redeploy of the mirror. The new task is already
 * up behind the same name, so a second attempt on a new socket reaches it.
 *
 * <p><b>What is retried is narrow on purpose.</b>
 *
 * <ul>
 *   <li><b>GET and HEAD with no body</b> — {@link #replayable}. Nothing else is idempotent enough
 *       to send twice without asking the upstream, and a body has been streamed into the first
 *       attempt already: the inbound request cannot be read a second time.
 *   <li><b>A failure before any response header</b>. This interceptor sees the future the proxy
 *       resolves on the upstream's response HEAD; once that has succeeded nothing here runs again,
 *       so a response whose bytes have started flowing to the client is never retried — a failure
 *       mid-body is a reset toward the client, as it always was.
 *   <li><b>A broken connection</b> — {@link #retryable}: a connection closed or reset under the
 *       request, a refused connect, a TCP connect timeout. A full pool and an acquisition that
 *       timed out are NOT: a second wait behind the same queue is thirty more seconds of the same
 *       answer.
 * </ul>
 *
 * <p><b>Fresh means fresh.</b> The second attempt is not asked of the same pool, which could hand
 * over the next idle connection to the same drained task: {@code fresh} dials through the pool's
 * non-keep-alive client, so it is always a new socket, resolved anew.
 *
 * <p><b>It is the last interceptor</b>, so what the others wrote onto the proxy request — the
 * identity, the forwarded headers, the hop-by-hop strip — is on it when it is sent the second time;
 * the proxy copies those headers onto each outbound request it sends.
 */
final class UpstreamRetry implements ProxyInterceptor {

  private static final Logger LOG = Logger.getLogger(UpstreamRetry.class);

  private final Upstream upstream;

  private final OriginRequestProvider fresh;

  /**
   * @param upstream the origin, for the log line
   * @param fresh where the second attempt's connection comes from — a new socket every time
   */
  UpstreamRetry(Upstream upstream, OriginRequestProvider fresh) {
    this.upstream = upstream;
    this.fresh = fresh;
  }

  @Override
  public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
    HttpServerRequest inbound = context.request().proxiedRequest();
    if (!replayable(inbound.method(), inbound.headers())) {
      return context.sendRequest();
    }
    return context
        .sendRequest()
        .recover(
            failure -> {
              if (!retryable(failure) || inbound.response().closed()) {
                return Future.failedFuture(failure);
              }
              LOG.warnf(
                  "no answer from %s for %s %s (%s); retrying once on a fresh connection",
                  upstream, inbound.method(), inbound.path(), failure);
              // The first attempt piped the inbound request as its body — empty, but read. A
              // request is read once, so the second attempt sends none, which is what a bodyless
              // request is on the wire anyway. Resumed so the inbound's read state is released.
              context.request().setBody(null);
              inbound.resume();
              return fresh
                  .create(context)
                  .compose(request -> context.request().send(request))
                  .onFailure(
                      again ->
                          LOG.warnf(
                              "the retry to %s for %s %s failed too, answering 502: %s",
                              upstream, inbound.method(), inbound.path(), again));
            });
  }

  /**
   * Whether a request may be sent twice: GET or HEAD, with neither a non-zero {@code
   * Content-Length} nor a {@code Transfer-Encoding}. Pure, so the rule is asserted without a
   * request.
   */
  static boolean replayable(HttpMethod method, MultiMap headers) {
    if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
      return false;
    }
    if (headers.contains(HttpHeaders.TRANSFER_ENCODING)) {
      return false;
    }
    String length = headers.get(HttpHeaders.CONTENT_LENGTH);
    return length == null || length.trim().equals("0");
  }

  /**
   * Whether a failure says the connection broke rather than that the pool is full: closed under the
   * request ({@link HttpClosedException}), reset or otherwise failed on the socket ({@link
   * IOException}, which includes a refused connect and Netty's connect timeout, both {@code
   * ConnectException}s). A full pool and an acquisition timeout are Vert.x exceptions of their own,
   * neither of these.
   */
  static boolean retryable(Throwable failure) {
    return failure instanceof HttpClosedException || failure instanceof IOException;
  }
}
