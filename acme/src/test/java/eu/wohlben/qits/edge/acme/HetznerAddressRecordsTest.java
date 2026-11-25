package eu.wohlben.qits.edge.acme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The address side of the Hetzner client — A, AAAA and CNAME (qits-2273, qits-2292) — against a
 * fake API, the way the TXT side's test fakes it.
 */
class HetznerAddressRecordsTest {

  private static final String TOKEN = "not-a-real-token";

  private HttpServer server;
  private final List<Request> requests = new ArrayList<>();

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
  }

  @Test
  void listsTheOwnedRrsetsOfEitherLabelAndEveryTypeAcrossEveryPage() throws Exception {
    var records =
        serve(
            request -> {
              if (request.path().endsWith("&page=1")) {
                return new Response(
                    200,
                    """
                    {"rrsets":[{"name":"projects.qits","type":"A","ttl":60,
                      "labels":{"qits-exposure":"private"},"records":[{"value":"100.96.0.1"}]}],
                     "meta":{"pagination":{"page":1,"next_page":2}}}
                    """);
              }
              // A public CNAME, and two the zone should not have answered: an unlabelled one and a
              // TXT. The client hands on only what is ours.
              return new Response(
                  200,
                  """
                  {"rrsets":[{"name":"ci.dev.qits","type":"CNAME","ttl":300,
                    "labels":{"qits-exposure":"public"},"records":[{"value":"node.wohlben.eu."}]},
                   {"name":"stray","type":"A","ttl":300,"labels":{},"records":[{"value":"1.2.3.4"}]},
                   {"name":"txt","type":"TXT","ttl":300,"labels":{"qits-exposure":"public"},
                    "records":[{"value":"\\"x\\""}]}],
                   "meta":{"pagination":{"page":2,"next_page":null}}}
                  """);
            });

    List<AddressRecords.RrSet> owned = records.owned();

    assertThat(owned)
        .containsExactly(
            new AddressRecords.RrSet(
                "projects.qits",
                "A",
                60,
                Map.of("qits-exposure", "private"),
                List.of("100.96.0.1")),
            new AddressRecords.RrSet(
                "ci.dev.qits",
                "CNAME",
                300,
                Map.of("qits-exposure", "public"),
                List.of("node.wohlben.eu.")));
    assertThat(owned).allMatch(AddressRecords.RrSet::owned);
    assertThat(owned)
        .extracting(AddressRecords.RrSet::exposure)
        .containsExactly("private", "public");
    assertThat(requests).hasSize(2);
    assertThat(requests.get(0).path())
        .isEqualTo(
            "/v1/zones/wohlben.eu/rrsets?type=A&type=AAAA&type=CNAME"
                + "&label_selector=qits-exposure%20in%20%28private%2Cpublic%29"
                + "&per_page=100&page=1");
    assertThat(requests.get(1).path()).endsWith("&page=2");
  }

  @Test
  void createsALabelledARrsetWithTheShortTtl() throws Exception {
    var records = serve(request -> new Response(201, "{\"rrset\":{},\"action\":{}}"));

    records.create("projects.qits", "A", List.of("100.96.0.1"), "private");

    assertThat(requests).hasSize(1);
    assertThat(requests.get(0).method()).isEqualTo("POST");
    assertThat(requests.get(0).path()).isEqualTo("/v1/zones/wohlben.eu/rrsets");
    assertThat(new ObjectMapper().readTree(requests.get(0).body()))
        .isEqualTo(
            new ObjectMapper()
                .readTree(
                    """
                    {"name":"projects.qits","type":"A","ttl":60,
                     "labels":{"qits-exposure":"private"},"records":[{"value":"100.96.0.1"}]}
                    """));
  }

  @Test
  void createsAPublicCnameWithItsTrailingDotAndAPublicAaaa() throws Exception {
    var records = serve(request -> new Response(201, "{\"rrset\":{},\"action\":{}}"));

    records.create("ci.dev.qits", "CNAME", List.of("node.wohlben.eu"), "public");
    records.create("node", "AAAA", List.of("2a01:4f8:c17:1::1"), "public");

    ObjectMapper mapper = new ObjectMapper();
    assertThat(mapper.readTree(requests.get(0).body()))
        .isEqualTo(
            mapper.readTree(
                """
                {"name":"ci.dev.qits","type":"CNAME","ttl":60,
                 "labels":{"qits-exposure":"public"},"records":[{"value":"node.wohlben.eu."}]}
                """));
    assertThat(mapper.readTree(requests.get(1).body()))
        .isEqualTo(
            mapper.readTree(
                """
                {"name":"node","type":"AAAA","ttl":60,
                 "labels":{"qits-exposure":"public"},"records":[{"value":"2a01:4f8:c17:1::1"}]}
                """));
  }

  @Test
  void anAbsoluteCnameValueKeepsItsOneDot() throws Exception {
    var records = serve(request -> new Response(201, "{\"action\":{}}"));

    records.setRecords("qits", "CNAME", List.of("node.wohlben.eu."));

    assertThat(requests.get(0).path())
        .isEqualTo("/v1/zones/wohlben.eu/rrsets/qits/CNAME/actions/set_records");
    assertThat(requests.get(0).body())
        .isEqualTo("{\"records\":[{\"value\":\"node.wohlben.eu.\"}]}");
  }

  @Test
  void labelsTheApexByAPutOnItsOwnPath() throws Exception {
    var records = serve(request -> new Response(200, "{\"rrset\":{}}"));

    records.setLabels("@", "A", Map.of("qits-exposure", "public"));

    assertThat(requests.get(0).method()).isEqualTo("PUT");
    // The apex's name is "@", and Hetzner matches the raw path: URLEncoder's %40 404s where the
    // literal "@" matches (qits-2292). Never regress this back to "%40".
    assertThat(requests.get(0).path()).isEqualTo("/v1/zones/wohlben.eu/rrsets/@/A");
    assertThat(requests.get(0).body()).isEqualTo("{\"labels\":{\"qits-exposure\":\"public\"}}");
  }

  @Test
  void deletesTheApexByItsLiteralAtSignPath() throws Exception {
    var records = serve(request -> new Response(404, "{\"error\":{\"code\":\"not_found\"}}"));

    records.delete("@", "A");

    assertThat(requests.get(0).method()).isEqualTo("DELETE");
    assertThat(requests.get(0).path()).isEqualTo("/v1/zones/wohlben.eu/rrsets/@/A");
  }

  @Test
  void setsRecordsChangesTheTtlAndDeletesByNameAndType() throws Exception {
    var records =
        serve(
            request ->
                request.method().equals("DELETE")
                    ? new Response(404, "{\"error\":{\"code\":\"not_found\"}}")
                    : new Response(201, "{\"action\":{}}"));

    records.setRecords("projects.qits", "A", List.of("100.96.0.9", "100.96.0.10"));
    records.changeTtl("projects.qits", "A");
    records.delete("projects.qits", "A");
    records.delete("qits", "CNAME");

    assertThat(requests)
        .extracting(Request::method, Request::path)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "POST", "/v1/zones/wohlben.eu/rrsets/projects.qits/A/actions/set_records"),
            org.assertj.core.groups.Tuple.tuple(
                "POST", "/v1/zones/wohlben.eu/rrsets/projects.qits/A/actions/change_ttl"),
            org.assertj.core.groups.Tuple.tuple(
                "DELETE", "/v1/zones/wohlben.eu/rrsets/projects.qits/A"),
            org.assertj.core.groups.Tuple.tuple(
                "DELETE", "/v1/zones/wohlben.eu/rrsets/qits/CNAME"));
    assertThat(requests.get(0).body())
        .isEqualTo("{\"records\":[{\"value\":\"100.96.0.9\"},{\"value\":\"100.96.0.10\"}]}");
    assertThat(requests.get(1).body()).isEqualTo("{\"ttl\":60}");
  }

  @Test
  void getsOneRrsetByNameAndTypeOnItsOwnPath() throws Exception {
    var records =
        serve(
            request ->
                request.path().endsWith("/*/A")
                    ? new Response(
                        200,
                        """
                        {"rrset":{"name":"*","type":"A","ttl":300,"labels":{},
                          "records":[{"value":"46.224.171.33"}]}}
                        """)
                    : new Response(404, "{\"error\":{\"code\":\"not_found\"}}"));

    Optional<AddressRecords.RrSet> wildcard = records.get("*", "A");
    Optional<AddressRecords.RrSet> none = records.get("qits", "CNAME");

    assertThat(requests.get(0).method()).isEqualTo("GET");
    assertThat(requests.get(0).path()).isEqualTo("/v1/zones/wohlben.eu/rrsets/*/A");
    assertThat(requests.get(1).path()).isEqualTo("/v1/zones/wohlben.eu/rrsets/qits/CNAME");
    assertThat(wildcard)
        .contains(new AddressRecords.RrSet("*", "A", 300, Map.of(), List.of("46.224.171.33")));
    assertThat(wildcard.orElseThrow().owned()).isFalse();
    assertThat(none).isEmpty();
  }

  @Test
  void getsTheApexByItsLiteralAtSignPathNeverTheEncodedOne() throws Exception {
    var records =
        serve(
            request ->
                request.path().equals("/v1/zones/wohlben.eu/rrsets/@/A")
                    ? new Response(
                        200,
                        """
                        {"rrset":{"name":"@","type":"A","ttl":300,"labels":{},
                          "records":[{"value":"46.224.171.33"}]}}
                        """)
                    : new Response(404, "{\"error\":{\"code\":\"not_found\"}}"));

    Optional<AddressRecords.RrSet> apex = records.get("@", "A");

    assertThat(requests.get(0).method()).isEqualTo("GET");
    assertThat(requests.get(0).path()).isEqualTo("/v1/zones/wohlben.eu/rrsets/@/A");
    assertThat(apex)
        .contains(new AddressRecords.RrSet("@", "A", 300, Map.of(), List.of("46.224.171.33")));
  }

  @Test
  void getAnyOtherRefusalThrows() throws Exception {
    var records = serve(request -> new Response(503, "busy"));

    assertThatThrownBy(() -> records.get("*", "A")).hasMessageContaining("HTTP 503: busy");
  }

  @Test
  void aRefusalNamesTheStatusAndTheBodyAndNeverTheToken() throws Exception {
    String body =
        "{\"error\":{\"code\":\"invalid_input\",\"message\":\"" + "x".repeat(500) + "\"}}";
    var records = serve(request -> new Response(422, body));

    assertThatThrownBy(() -> records.create("projects.qits", "A", List.of("100.96.0.1"), "private"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HTTP 422")
        .hasMessageContaining("invalid_input")
        .hasMessageNotContaining(TOKEN)
        .satisfies(
            failure ->
                assertThat(failure.getMessage().length())
                    .isLessThan(HetznerAddressRecords.BODY_LIMIT + 120));
  }

  @Test
  void anEgressRefusalNamesTheGatewayItsStatusAndItsBody() throws Exception {
    // qits-egress answers a call it will not relay itself — 403 without a grant, 502 without the
    // secret — and its body says which; that is what the WARN must carry (qits-2617).
    var records = serve(request -> new Response(502, "{\"error\":\"secret not configured\"}"));

    assertThatThrownBy(() -> records.owned())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("via 127.0.0.1:" + server.getAddress().getPort())
        .hasMessageContaining("HTTP 502: {\"error\":\"secret not configured\"}")
        .hasMessageNotContaining(TOKEN);
  }

  @Test
  void throughTheGatewayEveryCallCarriesTheQitsBearerUnderTheHetznerRoute() throws Exception {
    // The deployed edge's base is http://<env>-qits-egress:8081/hetzner/v1/ and its bearer the
    // edge's own qits token; the gateway maps /hetzner/v1/zones/... onto api.hetzner.cloud/v1/...
    List<String> authorizations = new ArrayList<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          synchronized (requests) {
            requests.add(
                new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(), ""));
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
          }
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(201, -1);
          exchange.close();
        });
    server.start();
    // No trailing slash, as a hand-set value might read: the base still keeps its last segment.
    URI gateway = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hetzner/v1");
    int[] minted = {0};
    var records =
        new HetznerAddressRecords(
            HttpClient.newHttpClient(),
            new ObjectMapper(),
            gateway,
            () -> "qits-jwt-" + ++minted[0],
            "wohlben.eu");

    records.create("projects.qits", "A", List.of("100.96.0.1"), "private");
    records.delete("projects.qits", "A");

    assertThat(requests)
        .extracting(Request::method, Request::path)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("POST", "/hetzner/v1/zones/wohlben.eu/rrsets"),
            org.assertj.core.groups.Tuple.tuple(
                "DELETE", "/hetzner/v1/zones/wohlben.eu/rrsets/projects.qits/A"));
    // Asked per request, so a renewed token is used the moment it exists.
    assertThat(authorizations).containsExactly("Bearer qits-jwt-1", "Bearer qits-jwt-2");
  }

  @Test
  void aCallWithoutABearerIsRefusedBeforeItIsSent() throws Exception {
    serve(request -> new Response(200, "{}"));
    URI api = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
    var records =
        new HetznerAddressRecords(
            HttpClient.newHttpClient(), new ObjectMapper(), api, () -> " ", "wohlben.eu");

    assertThatThrownBy(() -> records.delete("projects.qits", "A"))
        .hasMessageContaining("no bearer");
    assertThat(requests).isEmpty();
  }

  @Test
  void aMissingRrsetOnAnyOtherCallIsARefusal() throws Exception {
    var records = serve(request -> new Response(404, ""));

    assertThatThrownBy(() -> records.setRecords("projects.qits", "A", List.of("100.96.0.1")))
        .hasMessageContaining("HTTP 404: (empty body)");
  }

  @Test
  void pathSegmentEncodingLeavesTheApexAndTheWildcardLiteralButEncodesEverythingElseLikeEncode() {
    assertThat(HetznerApi.encodePathSegment("@")).isEqualTo("@");
    assertThat(HetznerApi.encodePathSegment("*")).isEqualTo(HetznerApi.encode("*")).isEqualTo("*");
    assertThat(HetznerApi.encodePathSegment("projects.qits"))
        .isEqualTo(HetznerApi.encode("projects.qits"));
    assertThat(HetznerApi.encodePathSegment("a b"))
        .isEqualTo(HetznerApi.encode("a b"))
        .isEqualTo("a%20b");
  }

  private HetznerAddressRecords serve(Function<Request, Response> answer) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          Request request =
              new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(), body);
          synchronized (requests) {
            requests.add(request);
          }
          Response response = answer.apply(request);
          byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
          if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
          }
          exchange.close();
        });
    server.start();
    URI api = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
    return new HetznerAddressRecords(
        HttpClient.newHttpClient(), new ObjectMapper(), api, () -> TOKEN, "wohlben.eu");
  }

  private record Request(String method, String path, String body) {}

  private record Response(int status, String body) {}
}
