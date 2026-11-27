package eu.wohlben.qits.edge.acme;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.http.RawHttpClient;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * The HTTP half of Hetzner Cloud's RRSet API that {@link HetznerDnsChallengeProvider} (TXT) and
 * {@link HetznerAddressRecords} (A, AAAA, CNAME) share: one base URL, one bearer, one zone, one
 * request shape. It answers the raw response and judges nothing, so each caller keeps its own
 * refusal.
 *
 * <p><b>The base URL is the caller's</b> ({@code qits.edge.acme.hetzner-api-url}, qits-2617): the
 * qits-egress gateway's {@code http://<env>-qits-egress:8081/hetzner/v1/} on the deployed edge,
 * {@code https://api.hetzner.cloud/v1/} on the seed edge. Every path here is relative to it, so
 * both end in {@code .../v1/zones/<zone>/rrsets...}. The {@link HetznerBearer} is asked once per
 * request.
 */
@RawHttpClient(
    reason =
        "the request is bound for api.hetzner.cloud, the DNS provider API — directly or relayed by"
            + " qits-egress, which reads only the bearer: no trace or caller header belongs on it")
final class HetznerApi {

  /** How much of a refusal's body reaches the message. */
  static final int BODY_LIMIT = 300;

  private final ObjectMapper json;
  private final HetznerRoute route;
  private final String zone;

  HetznerApi(HttpClient http, ObjectMapper json, URI api, HetznerBearer bearer, String zone) {
    this(json, HetznerRoute.fixed(http, api, bearer), zone);
  }

  /** Every request asks {@code route} where it goes (qits-2760). */
  HetznerApi(ObjectMapper json, HetznerRoute route, String zone) {
    this.json = json;
    this.route = route;
    this.zone = zone.toLowerCase(Locale.ROOT);
  }

  /** A base without its trailing slash would resolve {@code zones/...} against its parent. */
  private static URI withTrailingSlash(URI api) {
    String base = api.toString();
    return base.endsWith("/") ? api : URI.create(base + "/");
  }

  /** The client of the 8081 gateway and of api.hetzner.cloud: the JVM's trust, no key. */
  static HttpClient defaultClient() {
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
  }

  /**
   * The client of qits-egress' TLS proxy listener (qits-2760): {@code context} presents the edge's
   * workload certificate and trusts the platform CA. Built at run time, never statically.
   */
  static HttpClient client(javax.net.ssl.SSLContext context) {
    return HttpClient.newBuilder()
        .sslContext(context)
        .connectTimeout(Duration.ofSeconds(15))
        .build();
  }

  ObjectMapper json() {
    return json;
  }

  String zone() {
    return zone;
  }

  /** {@code zones/<zone>/rrsets}, the collection every call here is under. */
  String rrsets() {
    return "zones/" + encode(zone) + "/rrsets";
  }

  /**
   * One request, answered whatever its status; the bearer is the only header that is secret. A
   * {@link HetznerRoute.Hop} with no bearer sends no {@code Authorization}: its client certificate
   * is the credential.
   */
  HttpResponse<String> send(String method, String path, byte[] body) throws Exception {
    HetznerRoute.Hop hop = route.hop();
    URI api = withTrailingSlash(hop.api());
    var builder =
        HttpRequest.newBuilder(api.resolve(path))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json");
    if (hop.bearer() != null) {
      String token = hop.bearer().token();
      if (token == null || token.isBlank()) {
        throw new IllegalStateException("no bearer for the Hetzner DNS API");
      }
      builder.header("Authorization", "Bearer " + token);
    }
    if (body == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
    }
    return hop.http().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  /**
   * The refusal both callers throw: the method, the path, who answered (the gateway's or Hetzner's
   * authority — a 403, 407 or 5xx from qits-egress is the gateway's own verdict, a 4xx through it
   * is usually Hetzner's), the status and the response body cut to {@link #BODY_LIMIT} characters.
   * Neither body carries a secret; the bearer is a header and never part of the message. The caller
   * logs it at WARN and retries on its next trigger.
   */
  IllegalStateException refusal(String method, String path, HttpResponse<String> response) {
    return new IllegalStateException(
        "Hetzner DNS "
            + method
            + " "
            + path
            + " via "
            + response.uri().getAuthority()
            + " failed with HTTP "
            + response.statusCode()
            + ": "
            + truncated(response.body()));
  }

  static String truncated(String body) {
    if (body == null || body.isBlank()) {
      return "(empty body)";
    }
    String flat = body.strip().replaceAll("\\s+", " ");
    return flat.length() <= BODY_LIMIT ? flat : flat.substring(0, BODY_LIMIT) + "...";
  }

  static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  /**
   * Percent-encodes a path segment the way {@link #encode} does, except it leaves {@code @}
   * literal. Hetzner's RRSet API matches the raw request path rather than decoding it, so the zone
   * apex's name, {@code @}, has to reach the path as-is: {@code @} is a legal {@code pchar} under
   * RFC 3986, but {@link #encode} is a query encoder and turns it into {@code %40} anyway, which
   * 404s where {@code @} would have matched (seen live 2026-11-18, qits-2292). The wildcard name,
   * {@code *}, never had this problem — {@link URLEncoder} already leaves it alone — which is why
   * only the apex ever tripped it. Use this for the name and type segments of an RRSet path; keep
   * {@link #encode} for the {@code label_selector} query value.
   */
  static String encodePathSegment(String value) {
    return encode(value).replace("%40", "@");
  }
}
