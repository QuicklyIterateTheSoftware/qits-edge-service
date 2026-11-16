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
 * {@link HetznerAddressRecords} (A) share: one bearer, one zone, one request shape. It answers the
 * raw response and judges nothing, so each caller keeps its own refusal — the TXT side's message is
 * the one it always had.
 */
@RawHttpClient(
    reason =
        "api.hetzner.cloud is the DNS provider API, not a qits service: no trace or caller header belongs on it")
final class HetznerApi {

  static final URI DEFAULT_API = URI.create("https://api.hetzner.cloud/v1/");

  private final HttpClient http;
  private final ObjectMapper json;
  private final URI api;
  private final String token;
  private final String zone;

  HetznerApi(HttpClient http, ObjectMapper json, URI api, String token, String zone) {
    this.http = http;
    this.json = json;
    this.api = api;
    this.token = token;
    this.zone = zone.toLowerCase(Locale.ROOT);
    if (token.isBlank()) {
      throw new IllegalArgumentException("Hetzner DNS token is blank");
    }
  }

  static HttpClient defaultClient() {
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
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

  /** One request, answered whatever its status; the bearer is the only header that is secret. */
  HttpResponse<String> send(String method, String path, byte[] body) throws Exception {
    var builder =
        HttpRequest.newBuilder(api.resolve(path))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json");
    if (body == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
