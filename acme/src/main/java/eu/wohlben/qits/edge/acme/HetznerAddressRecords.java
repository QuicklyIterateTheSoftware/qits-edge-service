package eu.wohlben.qits.edge.acme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.http.RawHttpClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link AddressRecords} through Hetzner Cloud's RRSet API, the same API and bearer the DNS-01
 * challenge uses ({@link HetznerDnsChallengeProvider}).
 *
 * <p>A refused call throws with the method, the path, the status and the response body cut to
 * {@link #BODY_LIMIT} characters — what Hetzner said is the diagnosis. The bearer is a header and
 * never part of either.
 */
@RawHttpClient(
    reason =
        "api.hetzner.cloud is the DNS provider API, not a qits service: no trace or caller header belongs on it")
public final class HetznerAddressRecords implements AddressRecords {

  /** {@code label_selector} for the ownership label, derived from the one definition. */
  static final String SELECTOR = OWNER_LABEL + "=" + OWNER_VALUE;

  static final int BODY_LIMIT = 300;

  private static final int PAGE_SIZE = 100;

  private final HetznerApi hetzner;
  private final ObjectMapper json;

  public HetznerAddressRecords(String token, String zone) {
    this(HetznerApi.defaultClient(), new ObjectMapper(), HetznerApi.DEFAULT_API, token, zone);
  }

  HetznerAddressRecords(HttpClient http, ObjectMapper json, URI api, String token, String zone) {
    this.hetzner = new HetznerApi(http, json, api, token, zone);
    this.json = json;
  }

  @Override
  public List<RrSet> owned() throws Exception {
    List<RrSet> found = new ArrayList<>();
    int page = 1;
    while (true) {
      JsonNode body =
          get(
              hetzner.rrsets()
                  + "?type=A&label_selector="
                  + HetznerApi.encode(SELECTOR)
                  + "&per_page="
                  + PAGE_SIZE
                  + "&page="
                  + page);
      found.addAll(rrsets(body));
      JsonNode next = body.path("meta").path("pagination").path("next_page");
      if (!next.isInt() || next.asInt() <= page) {
        return found;
      }
      page = next.asInt();
    }
  }

  @Override
  public List<RrSet> named(String name) throws Exception {
    return rrsets(get(hetzner.rrsets() + "?name=" + HetznerApi.encode(name)));
  }

  @Override
  public void create(String name, String value) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("type", "A");
    body.put("ttl", TTL);
    body.put("labels", Map.of(OWNER_LABEL, OWNER_VALUE));
    body.put("records", List.of(Map.of("value", value)));
    call("POST", hetzner.rrsets(), body, false);
  }

  @Override
  public void setRecords(String name, String value) throws Exception {
    call(
        "POST",
        rrset(name) + "/actions/set_records",
        Map.of("records", List.of(Map.of("value", value))),
        false);
  }

  @Override
  public void changeTtl(String name) throws Exception {
    call("POST", rrset(name) + "/actions/change_ttl", Map.of("ttl", TTL), false);
  }

  @Override
  public void delete(String name) throws Exception {
    call("DELETE", rrset(name), null, true);
  }

  private String rrset(String name) {
    return hetzner.rrsets() + "/" + HetznerApi.encode(name) + "/A";
  }

  private JsonNode get(String path) throws Exception {
    return json.readTree(call("GET", path, null, false).body());
  }

  private HttpResponse<String> call(String method, String path, Object body, boolean acceptMissing)
      throws Exception {
    HttpResponse<String> response =
        hetzner.send(method, path, body == null ? null : json.writeValueAsBytes(body));
    int status = response.statusCode();
    if (status / 100 == 2 || (acceptMissing && status == 404)) {
      return response;
    }
    throw new IllegalStateException(
        "Hetzner DNS "
            + method
            + " "
            + path
            + " failed with HTTP "
            + status
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

  private static List<RrSet> rrsets(JsonNode body) {
    List<RrSet> found = new ArrayList<>();
    for (JsonNode rrset : body.path("rrsets")) {
      Map<String, String> labels = new LinkedHashMap<>();
      rrset
          .path("labels")
          .properties()
          .forEach(label -> labels.put(label.getKey(), label.getValue().asText()));
      List<String> values = new ArrayList<>();
      for (JsonNode record : rrset.path("records")) {
        values.add(record.path("value").asText());
      }
      found.add(
          new RrSet(
              rrset.path("name").asText(),
              rrset.path("type").asText(),
              rrset.path("ttl").asInt(0),
              labels,
              values));
    }
    return found;
  }
}
