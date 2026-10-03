package eu.wohlben.qits.edge;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpHeaders;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import io.vertx.httpproxy.ProxyResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * No hop-by-hop header crosses the edge in either direction: what describes the connection between
 * an upstream and this process stays on that connection (RFC 9110 §7.6.1).
 *
 * <p><b>Why it exists.</b> {@code vertx-http-proxy} 4.5.26 copies an upstream's response headers
 * nearly verbatim — it drops only {@code Transfer-Encoding} (and rewrites {@code Date} and {@code
 * Warning}). So an Express upstream's {@code Connection: keep-alive} and {@code Keep-Alive:
 * timeout=5} reached the client. On HTTP/1.1 that is merely wrong; on HTTP/2 it is fatal, because
 * connection-specific fields are malformed there (RFC 9113 §8.2.2) and Netty answers them by
 * resetting the stream with {@code PROTOCOL_ERROR}. Every browser speaks h2 to the edge, so the
 * apex — {@code qits-landing-app}, which is Express — could not be loaded at all by anyone signed
 * in, while {@code curl --http1.1} showed a perfectly good 302 (measured live 2026-10-03).
 *
 * <p><b>Why an interceptor, and not {@link EdgeCors}' headers-end hook.</b> The interceptor chain
 * runs on exactly the responses this process copies from an upstream — every proxied answer, an SSE
 * stream's head included — and on nothing else. The headers-end hook would also run on the {@code
 * 101} of a WebSocket handshake, which {@code EdgeWebSocketUpgrade} relays with its own {@code
 * Connection: Upgrade} and {@code Upgrade} — the two headers that handshake cannot do without. That
 * path never installs this chain, and the {@code 101} guard below keeps it true even if an upgrade
 * ever fell through to the proxy. The hook would also see the headers Vert.x adds to frame its own
 * HTTP/1.1 answer, which are this process' to write and not an upstream's to leak.
 *
 * <p><b>Framing is the server's own.</b> Dropping the upstream's {@code Transfer-Encoding} changes
 * nothing about how a streamed answer travels: the proxy sets the inbound response chunked itself
 * whenever the upstream gave no length on HTTP/1.1, and HTTP/2 frames its DATA with no header at
 * all. {@code EdgeRoutingTest.aChunkedResponseIsNotBuffered} is the regression for that.
 *
 * <p><b>The request direction</b> already loses the fixed list inside {@code vertx-http-proxy}
 * ({@code Connection}, {@code Keep-Alive}, {@code Proxy-Authenticate}, {@code Proxy-Authorization},
 * {@code TE}, {@code Trailer}, {@code Transfer-Encoding}, {@code Upgrade}); what it misses is a
 * header a client's own {@code Connection} names, and {@code Proxy-Connection}. Both are removed
 * here, on the proxy's copy of the request rather than on the inbound map, so a WebSocket handshake
 * — forwarded from the inbound map by its own path — is untouched.
 */
final class EdgeHopByHop implements ProxyInterceptor {

  /**
   * The connection-specific fields: RFC 9110 §7.6.1's list, the ones RFC 9113 §8.2.2 names as
   * malformed on HTTP/2, and {@code Proxy-Connection}, the non-standard spelling some clients and
   * servers still send.
   */
  static final List<String> HOP_BY_HOP =
      List.of(
          "Connection",
          "Keep-Alive",
          "Proxy-Connection",
          "Transfer-Encoding",
          "Upgrade",
          "TE",
          "Trailer");

  @Override
  public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
    strip(context.request().headers());
    return context.sendRequest();
  }

  @Override
  public Future<Void> handleProxyResponse(ProxyContext context) {
    ProxyResponse response = context.response();
    // A switched protocol is the one answer whose Connection and Upgrade ARE the message.
    if (response.getStatusCode() != 101) {
      strip(response.headers());
    }
    return context.sendResponse();
  }

  /**
   * Remove every hop-by-hop header from one header map: the fixed list, and every name the map's
   * own {@code Connection} header nominates. Package-private and static so it can be asserted
   * without a socket.
   */
  static void strip(MultiMap headers) {
    List<String> named = new ArrayList<>();
    for (String value : headers.getAll(HttpHeaders.CONNECTION)) {
      for (String token : value.split(",")) {
        String name = token.strip();
        if (!name.isEmpty()) {
          named.add(name);
        }
      }
    }
    // The nominated names first, then the fixed list — which includes Connection itself, so the
    // list of nominations is read before the header carrying it goes.
    named.forEach(headers::remove);
    HOP_BY_HOP.forEach(headers::remove);
  }
}
