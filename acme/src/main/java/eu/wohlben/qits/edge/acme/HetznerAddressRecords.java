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
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * {@link AddressRecords} — A, AAAA and CNAME — through Hetzner Cloud's RRSet API, the same API and
 * bearer the DNS-01 challenge uses ({@link HetznerDnsChallengeProvider}).
 *
 * <p>A refused call throws with the method, the path, the status and the response body cut to
 * {@link #BODY_LIMIT} characters — what Hetzner said is the diagnosis. The bearer is a header and
 * never part of either.
 */
@RawHttpClient(
    reason =
        "api.hetzner.cloud is the DNS provider API, not a qits service: no trace or caller header belongs on it")
public final class HetznerAddressRecords implements AddressRecords {

  /**
   * {@code label_selector} for the ownership label with either value, derived from the one
   * definition: {@code qits-exposure in (private,public)}, Hetzner's set-based selector.
   */
  static final String SELECTOR =
      OWNER_LABEL + " in (" + String.join(",", new TreeSet<>(OWNER_VALUES)) + ")";

  /** The {@code type} filter, repeated once per type the edge writes, in a stable order. */
  static final String TYPE_FILTER =
      new TreeSet<>(TYPES).stream().map(type -> "type=" + type).collect(Collectors.joining("&"));

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
                  + "?"
                  + TYPE_FILTER
                  + "&label_selector="
                  + HetznerApi.encode(SELECTOR)
                  + "&per_page="
                  + PAGE_SIZE
                  + "&page="
                  + page);
      // The selector and the type filter are the zone's; this is ours. An RRSet that came back
      // without the label, or of a type this side never writes, is not one to hand on.
      rrsets(body).stream()
          .filter(rrset -> rrset.owned() && TYPES.contains(rrset.type()))
          .forEach(found::add);
      JsonNode next = body.path("meta").path("pagination").path("next_page");
      if (!next.isInt() || next.asInt() <= page) {
        return found;
      }
      page = next.asInt();
    }
  }

  @Override
  public Optional<RrSet> get(String name, String type) throws Exception {
    HttpResponse<String> response =
        call(
            "GET",
            hetzner.rrsets()
                + "/"
                + HetznerApi.encodePathSegment(name)
                + "/"
                + HetznerApi.encodePathSegment(type),
            null,
            true);
    if (response.statusCode() == 404) {
      return Optional.empty();
    }
    JsonNode rrset = json.readTree(response.body()).path("rrset");
    return rrset.isObject() ? Optional.of(rrset(rrset)) : Optional.empty();
  }

  @Override
  public void create(String name, String type, List<String> values, String exposure)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("type", type);
    body.put("ttl", TTL);
    body.put("labels", Map.of(OWNER_LABEL, exposure));
    body.put("records", records(type, values));
    call("POST", hetzner.rrsets(), body, false);
  }

  @Override
  public void setRecords(String name, String type, List<String> values) throws Exception {
    call(
        "POST",
        rrset(name, type) + "/actions/set_records",
        Map.of("records", records(type, values)),
        false);
  }

  /**
   * The records body; a CNAME's value is absolute, so a name without its trailing dot gets one —
   * Hetzner would otherwise read it relative to the zone ({@code node.wohlben.eu.wohlben.eu}).
   */
  private static List<Map<String, String>> records(String type, List<String> values) {
    return values.stream()
        .map(value -> "CNAME".equals(type) && !value.endsWith(".") ? value + "." : value)
        .map(value -> Map.of("value", value))
        .toList();
  }

  @Override
  public void changeTtl(String name, String type) throws Exception {
    call("POST", rrset(name, type) + "/actions/change_ttl", Map.of("ttl", TTL), false);
  }

  @Override
  public void setLabels(String name, String type, Map<String, String> labels) throws Exception {
    call("PUT", rrset(name, type), Map.of("labels", labels), false);
  }

  @Override
  public void delete(String name, String type) throws Exception {
    call("DELETE", rrset(name, type), null, true);
  }

  private String rrset(String name, String type) {
    return hetzner.rrsets()
        + "/"
        + HetznerApi.encodePathSegment(name)
        + "/"
        + HetznerApi.encodePathSegment(type);
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
      found.add(rrset(rrset));
    }
    return found;
  }

  private static RrSet rrset(JsonNode rrset) {
    Map<String, String> labels = new LinkedHashMap<>();
    rrset
        .path("labels")
        .properties()
        .forEach(label -> labels.put(label.getKey(), label.getValue().asText()));
    List<String> values = new ArrayList<>();
    for (JsonNode record : rrset.path("records")) {
      values.add(record.path("value").asText());
    }
    return new RrSet(
        rrset.path("name").asText(),
        rrset.path("type").asText(),
        rrset.path("ttl").asInt(0),
        labels,
        values);
  }
}
