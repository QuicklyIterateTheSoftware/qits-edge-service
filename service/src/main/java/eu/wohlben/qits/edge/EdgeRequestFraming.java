package eu.wohlben.qits.edge;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import io.vertx.httpproxy.ProxyResponse;

/**
 * A request body that arrives over HTTP/2 without a {@code Content-Length} leaves the edge chunked.
 *
 * <p><b>Why it exists.</b> {@code vertx-http-proxy} 4.5.26 frames the upstream request from the
 * inbound one: a {@code Content-Length} if it had one, chunked if it said {@code Transfer-Encoding:
 * chunked}, and otherwise neither. HTTP/2 has no {@code Transfer-Encoding} — its DATA frames carry
 * the body — so a streamed h2 body takes the "neither" branch. Vert.x then refuses every write to
 * the HTTP/1.1 upstream request ({@code IllegalStateException: You must set the Content-Length
 * header ...}), the pipe still ends it, and the upstream receives a request with no body. git sends
 * every push larger than its {@code http.postBuffer} (1 MB) this way, so qits-githost read a null
 * body and answered 500 (measured 2026-10-03; HTTP/1.1 chunked and fixed-length pushes worked).
 *
 * <p><b>How.</b> The proxy reads the inbound request's own headers when it sends, so this marks
 * that request chunked. It is not a header that leaves the edge: the proxy drops {@code
 * Transfer-Encoding} when it copies headers and sets the outbound request chunked itself.
 *
 * <p>GET and HEAD are left alone: they carry no body, and an empty chunked body on every browser
 * read would be a change to no purpose.
 */
final class EdgeRequestFraming implements ProxyInterceptor {

  @Override
  public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
    if (context.request().getBody() != null) {
      frame(context.request().proxiedRequest());
    }
    return context.sendRequest();
  }

  /** Mark an h2 request with a body of unknown length chunked. Package-private for the tests. */
  static void frame(HttpServerRequest request) {
    if (needsChunked(request.version(), request.method(), request.headers())) {
      request.headers().set(HttpHeaders.TRANSFER_ENCODING, HttpHeaders.CHUNKED);
    }
  }

  static boolean needsChunked(HttpVersion version, HttpMethod method, MultiMap headers) {
    return version == HttpVersion.HTTP_2
        && method != HttpMethod.GET
        && method != HttpMethod.HEAD
        && !headers.contains(HttpHeaders.CONTENT_LENGTH)
        && !headers.contains(HttpHeaders.TRANSFER_ENCODING);
  }
}
