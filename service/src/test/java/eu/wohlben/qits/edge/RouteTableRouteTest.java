package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code PUT/GET /edge-route-table} on a running edge (qits-2174, D2a): who may push and read, the
 * version rule, a refused table keeping the held one, and the GET's shape. The edge under test is
 * the public one, so it also proves the public edge never fetches.
 */
@QuarkusTest
@WithTestResource(StubGateways.class)
class RouteTableRouteTest {

  @TestHTTPResource("/")
  URL base;

  @Inject EdgeConfig config;

  @Inject RouteTableFetch fetch;

  @Inject EdgeRoutes routes;

  @Inject
  @DataSource("edge")
  AgroalDataSource edge;

  @BeforeEach
  void forgetTheTable() throws Exception {
    try (Connection connection = edge.getConnection();
        PreparedStatement delete = connection.prepareStatement("delete from edge_route_table")) {
      delete.executeUpdate();
    }
  }

  /** A valid public table, one application in dev on `ci`. */
  static JsonObject table(long version) {
    return new JsonObject()
        .put("edge", "public")
        .put("version", version)
        .put("builtAt", "2026-11-13T09:20:00Z")
        .put(
            "applications",
            new JsonArray()
                .add(
                    new JsonObject()
                        .put("environment", "dev")
                        .put("application", "qits-ci")
                        .put("exposure", "public")
                        .put("browserHost", "ci")
                        .put("apiDocsPath", "/ci/q/openapi")
                        .put(
                            "endpoints",
                            new JsonArray()
                                .add(
                                    new JsonObject()
                                        .put("path", "/ci")
                                        .put("upstreamHost", "dev-qits-ci")
                                        .put("upstreamPort", 8080)))
                        .put(
                            "navigation",
                            new JsonArray()
                                .add(
                                    new JsonObject()
                                        .put("slot", "platform")
                                        .put("label", "CI")
                                        .put("position", 30)
                                        .putNull("subpath")))))
        .put(
            "withdrawn",
            new JsonArray()
                .add(
                    new JsonObject()
                        .put("environment", "dev")
                        .put("application", "qits-stt")
                        .put("host", "stt")
                        .put("exposure", "private")));
  }

  private String publisher() {
    return StubGateways.serviceBearer(config.routes().publisher());
  }

  @Test
  void thePublisherStoresANewTable() throws Exception {
    HttpResponse<String> put = put(table(100).encode(), publisher());
    assertEquals(204, put.statusCode(), put.body());

    HttpResponse<String> get = get(publisher());
    assertEquals(200, get.statusCode(), get.body());
    JsonObject held = new JsonObject(get.body());
    assertEquals(List.of("edge", "version", "receivedAt"), List.copyOf(held.fieldNames()));
    assertEquals("public", held.getString("edge"));
    assertEquals(100L, held.getLong("version"));
    Instant receivedAt = Instant.parse(held.getString("receivedAt"));
    assertTrue(receivedAt.isAfter(Instant.now().minusSeconds(60)), receivedAt.toString());
    assertEquals("no-store", get.headers().firstValue("Cache-Control").orElse(null));

    try (Connection connection = edge.getConnection();
        PreparedStatement select =
            connection.prepareStatement("select edge, version, body::text from edge_route_table");
        ResultSet row = select.executeQuery()) {
      assertTrue(row.next());
      assertEquals("public", row.getString(1));
      assertEquals(100L, row.getLong(2));
      assertEquals(table(100), new JsonObject(row.getString(3)), "the body is kept whole");
    }
  }

  @Test
  void anEqualOrLowerVersionIsAnsweredWithTheHeldOne() throws Exception {
    assertEquals(204, put(table(100).encode(), publisher()).statusCode());
    for (long version : new long[] {100, 99, 1}) {
      HttpResponse<String> again = put(table(version).encode(), publisher());
      assertEquals(200, again.statusCode(), again.body());
      assertEquals(new JsonObject().put("held", 100L), new JsonObject(again.body()));
    }
    assertEquals(204, put(table(101).encode(), publisher()).statusCode());
    assertEquals(101L, new JsonObject(get(publisher()).body()).getLong("version"));
  }

  @Test
  void anInvalidTableIsRefusedAndTheHeldOneKept() throws Exception {
    assertEquals(204, put(table(100).encode(), publisher()).statusCode());
    JsonObject invalid = table(200);
    invalid
        .getJsonArray("applications")
        .getJsonObject(0)
        .put("browserHost", "dev"); // an environment name
    HttpResponse<String> refused = put(invalid.encode(), publisher());
    assertEquals(422, refused.statusCode(), refused.body());
    String error = new JsonObject(refused.body()).getString("error");
    assertTrue(error.contains("is an environment name"), error);

    HttpResponse<String> unreadable = put("not json", publisher());
    assertEquals(422, unreadable.statusCode(), unreadable.body());

    assertEquals(100L, new JsonObject(get(publisher()).body()).getLong("version"));
  }

  @Test
  void onlyThePublishersOwnBearerMayPush() throws Exception {
    for (String refused :
        new String[] {
          null,
          // a valid platform bearer, no qits:system
          StubGateways.platformBearer(),
          // a service, but not the deployer
          StubGateways.serviceBearer("dev-qits-ci"),
          // the deployer's azp on a token the edge's idp never signed
          "Bearer "
              + TestTokens.mint(
                  TestTokens.IMPOSTOR,
                  TestTokens.KID,
                  "RS256",
                  TestTokens.claims(
                          StubGateways.ISSUER,
                          List.of(StubGateways.PLATFORM_AUDIENCE),
                          Instant.now().plusSeconds(300))
                      .put("groups", new JsonArray(List.of("qits:system")))
                      .put("azp", config.routes().publisher())),
          StubGateways.basic(StubGateways.EDGE_ID, StubGateways.EDGE_SECRET),
        }) {
      HttpResponse<String> put = put(table(100).encode(), refused);
      assertEquals(403, put.statusCode(), refused + ": " + put.body());
    }
    assertNull(new JsonObject(get(publisher()).body()).getValue("version"));
  }

  @Test
  void anyServiceMayReadAndNobodyElse() throws Exception {
    HttpResponse<String> empty = get(StubGateways.serviceBearer("dev-qits-ci"));
    assertEquals(200, empty.statusCode(), empty.body());
    JsonObject document = new JsonObject(empty.body());
    assertEquals("public", document.getString("edge"));
    assertTrue(document.containsKey("version"));
    assertNull(document.getValue("version"));
    assertNull(document.getValue("receivedAt"));

    assertEquals(403, get(null).statusCode());
    assertEquals(403, get(StubGateways.platformBearer()).statusCode());
  }

  @Test
  void aStoredTableIsNotServedYet() throws Exception {
    // D2a: routing stays the projection's, so a table naming a new host changes nothing.
    JsonObject table = table(100);
    table.getJsonArray("applications").getJsonObject(0).put("application", "qits-unrouted");
    table.getJsonArray("applications").getJsonObject(0).put("browserHost", "unrouted");
    assertEquals(204, put(table.encode(), publisher()).statusCode());
    assertFalse(routes.applicationHosts("dev").containsKey("qits-unrouted"));
  }

  @Test
  void thePublicEdgeNeverFetches() {
    assertFalse(fetch.fetching());
  }

  private HttpResponse<String> put(String body, String authorization) throws Exception {
    HttpRequest.Builder request =
        request()
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body));
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    return send(request);
  }

  private HttpResponse<String> get(String authorization) throws Exception {
    HttpRequest.Builder request = request().GET();
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    return send(request);
  }

  private HttpRequest.Builder request() {
    return HttpRequest.newBuilder(URI.create(base.toString()).resolve(RouteTableRoute.PATH))
        .header("Host", "qits.example.com")
        .header("X-Forwarded-Proto", "https");
  }

  private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
    try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
      HttpResponse<String> response =
          client.send(request.build(), HttpResponse.BodyHandlers.ofString());
      assertNotNull(response);
      return response;
    }
  }
}
