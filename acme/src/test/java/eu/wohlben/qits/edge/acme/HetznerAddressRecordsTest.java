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
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The A side of the Hetzner client against a fake API, the way the TXT side's test fakes it. */
class HetznerAddressRecordsTest {

  private static final String TOKEN = "not-a-real-token";

  private HttpServer server;
  private final List<Request> requests = new ArrayList<>();

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
  }

  @Test
  void listsTheOwnedARrsetsAcrossEveryPage() throws Exception {
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
              return new Response(
                  200,
                  """
                  {"rrsets":[{"name":"mcp.qits","type":"A","ttl":300,
                    "labels":{"qits-exposure":"private"},"records":[{"value":"100.96.0.2"}]}],
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
                "mcp.qits", "A", 300, Map.of("qits-exposure", "private"), List.of("100.96.0.2")));
    assertThat(owned).allMatch(AddressRecords.RrSet::owned);
    assertThat(requests).hasSize(2);
    assertThat(requests.get(0).path())
        .isEqualTo(
            "/v1/zones/wohlben.eu/rrsets?type=A&label_selector=qits-exposure%3Dprivate"
                + "&per_page=100&page=1");
    assertThat(requests.get(1).path()).endsWith("&page=2");
  }

  @Test
  void createsALabelledARrsetWithTheShortTtl() throws Exception {
    var records = serve(request -> new Response(201, "{\"rrset\":{},\"action\":{}}"));

    records.create("projects.qits", "100.96.0.1");

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
  void setsRecordsChangesTheTtlAndDeletesByNameAndType() throws Exception {
    var records =
        serve(
            request ->
                request.method().equals("DELETE")
                    ? new Response(404, "{\"error\":{\"code\":\"not_found\"}}")
                    : new Response(201, "{\"action\":{}}"));

    records.setRecords("projects.qits", "100.96.0.9");
    records.changeTtl("projects.qits");
    records.delete("projects.qits");

    assertThat(requests)
        .extracting(Request::method, Request::path)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "POST", "/v1/zones/wohlben.eu/rrsets/projects.qits/A/actions/set_records"),
            org.assertj.core.groups.Tuple.tuple(
                "POST", "/v1/zones/wohlben.eu/rrsets/projects.qits/A/actions/change_ttl"),
            org.assertj.core.groups.Tuple.tuple(
                "DELETE", "/v1/zones/wohlben.eu/rrsets/projects.qits/A"));
    assertThat(requests.get(0).body()).isEqualTo("{\"records\":[{\"value\":\"100.96.0.9\"}]}");
    assertThat(requests.get(1).body()).isEqualTo("{\"ttl\":60}");
  }

  @Test
  void looksUpOneNameOfAnyType() throws Exception {
    var records =
        serve(
            request ->
                new Response(
                    200,
                    """
                    {"rrsets":[{"name":"projects.qits","type":"CNAME","ttl":300,"labels":{},
                      "records":[{"value":"elsewhere.example."}]}]}
                    """));

    List<AddressRecords.RrSet> found = records.named("projects.qits");

    assertThat(requests.get(0).path()).isEqualTo("/v1/zones/wohlben.eu/rrsets?name=projects.qits");
    assertThat(found).hasSize(1);
    assertThat(found.get(0).type()).isEqualTo("CNAME");
    assertThat(found.get(0).owned()).isFalse();
  }

  @Test
  void aRefusalNamesTheStatusAndTheBodyAndNeverTheToken() throws Exception {
    String body =
        "{\"error\":{\"code\":\"invalid_input\",\"message\":\"" + "x".repeat(500) + "\"}}";
    var records = serve(request -> new Response(422, body));

    assertThatThrownBy(() -> records.create("projects.qits", "100.96.0.1"))
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
  void aMissingRrsetOnAnyOtherCallIsARefusal() throws Exception {
    var records = serve(request -> new Response(404, ""));

    assertThatThrownBy(() -> records.setRecords("projects.qits", "100.96.0.1"))
        .hasMessageContaining("HTTP 404: (empty body)");
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
        HttpClient.newHttpClient(), new ObjectMapper(), api, TOKEN, "wohlben.eu");
  }

  private record Request(String method, String path, String body) {}

  private record Response(int status, String body) {}
}
