package eu.wohlben.qits.edge.acme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.http.RawHttpClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * DNS-01 mutations through Hetzner Cloud's RRSet API, at the base URL and with the bearer the
 * service hands in (qits-2617): the qits-egress gateway with the edge's own qits token, or — the
 * seed edge only — api.hetzner.cloud with the Hetzner token. A refused call throws {@link
 * HetznerApi#refusal}, which the certificate reconcile logs and retries.
 */
@RawHttpClient(
    reason =
        "the request is bound for api.hetzner.cloud, the DNS provider API — directly or relayed by"
            + " qits-egress, which reads only the bearer: no trace or caller header belongs on it")
public final class HetznerDnsChallengeProvider implements DnsChallengeProvider {

  private final HetznerApi hetzner;
  private final ObjectMapper json;
  private final String zone;

  /**
   * @param api the RRSet API's base, ending in {@code /v1/}
   * @param bearer what that base accepts — see {@link HetznerBearer}
   */
  public HetznerDnsChallengeProvider(URI api, HetznerBearer bearer, String zone) {
    this(HetznerApi.defaultClient(), new ObjectMapper(), api, bearer, zone);
  }

  HetznerDnsChallengeProvider(
      HttpClient http, ObjectMapper json, URI api, HetznerBearer bearer, String zone) {
    this.hetzner = new HetznerApi(http, json, api, bearer, zone);
    this.json = json;
    this.zone = hetzner.zone();
  }

  @Override
  public void present(String fqdn, String value) throws Exception {
    String name = relativeName(fqdn);
    String quoted = quote(value);
    JsonNode rrset = find(name);
    if (!rrset.isMissingNode()) {
      for (JsonNode record : rrset.path("records")) {
        if (quoted.equals(record.path("value").asText())) {
          return;
        }
      }
      post(
          rrsetUri(rrset.path("name").asText()) + "/actions/add_records",
          Map.of("records", List.of(Map.of("value", quoted, "comment", "QITS ACME validation"))));
      return;
    }
    post(
        "zones/" + encode(zone) + "/rrsets",
        Map.of(
            "name",
            name,
            "type",
            "TXT",
            "ttl",
            300,
            "records",
            List.of(Map.of("value", quoted, "comment", "QITS ACME validation"))));
  }

  @Override
  public void cleanup(String fqdn, String value) throws Exception {
    String name = relativeName(fqdn);
    String quoted = quote(value);
    JsonNode rrset = find(name);
    if (rrset.isMissingNode()) {
      return;
    }
    long matching =
        java.util.stream.StreamSupport.stream(rrset.path("records").spliterator(), false)
            .filter(record -> quoted.equals(record.path("value").asText()))
            .count();
    if (matching == 0) {
      return;
    }
    String path = rrsetUri(rrset.path("name").asText());
    if (rrset.path("records").size() == 1) {
      request("DELETE", path, null, true);
    } else {
      post(path + "/actions/remove_records", Map.of("records", List.of(Map.of("value", quoted))));
    }
  }

  private JsonNode find(String name) throws Exception {
    String path = "zones/" + encode(zone) + "/rrsets?type=TXT&name=" + encode(name);
    HttpResponse<String> response = request("GET", path, null, false);
    JsonNode rrsets = json.readTree(response.body()).path("rrsets");
    return rrsets.isArray() && !rrsets.isEmpty()
        ? rrsets.get(0)
        : com.fasterxml.jackson.databind.node.MissingNode.getInstance();
  }

  private String rrsetUri(String name) {
    return "zones/" + encode(zone) + "/rrsets/" + encode(name) + "/TXT";
  }

  private void post(String path, Object body) throws Exception {
    request("POST", path, json.writeValueAsBytes(body), false);
  }

  private HttpResponse<String> request(
      String method, String path, byte[] body, boolean acceptMissing) throws Exception {
    HttpResponse<String> response = hetzner.send(method, path, body);
    if (response.statusCode() / 100 == 2 || (acceptMissing && response.statusCode() == 404)) {
      return response;
    }
    throw hetzner.refusal(method, path, response);
  }

  private String relativeName(String fqdn) {
    String name = fqdn.endsWith(".") ? fqdn.substring(0, fqdn.length() - 1) : fqdn;
    String suffix = "." + zone;
    if (name.equals(zone)) {
      return "@";
    }
    if (!name.endsWith(suffix)) {
      throw new IllegalArgumentException("challenge name is outside the configured zone");
    }
    return name.substring(0, name.length() - suffix.length());
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static String encode(String value) {
    return HetznerApi.encode(value);
  }
}
