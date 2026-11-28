package eu.wohlben.qits.edge;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The edge end to end, against real stub upstreams on ephemeral loopback ports: two environment
 * gateways, two environments' {@code registry} and {@code mirror} applications, and a stand-in idp.
 * Only {@code mirror} is named in {@code qits.edge.auth.anonymous-read-apps}, so the gated and the
 * read-open answer are both observable from one boot.
 *
 * <p><b>Why one class rather than four.</b> A WebSocket upgrade through {@code vertx-http-proxy}
 * only survives the FIRST Quarkus start in a JVM — after a restart it silently degrades to a plain
 * proxied GET, so the handshake fails with nothing logged anywhere. It is a property of the test
 * harness, not of this code, and qits-gateway paid for finding it. A restart happens when a test
 * class needs a different configuration from the one before it, so the cheapest immunity is for
 * every {@code @QuarkusTest} here to share one: one class, one resource, one start. Splitting this
 * file is how the socket test starts failing for no visible reason.
 */
@QuarkusTest
@WithTestResource(StubGateways.class)
class EdgeRoutingTest {

  /** The route table this suite seeds, as qits-deployments would push it. */
  @Inject TableSeed seed;

  @Inject RouteTables tables;

  @Inject EdgeRoutes routes;

  @Inject EdgeProjects projects;

  /**
   * The project projection's barrier, which this suite lowers to see what a behind edge answers.
   */
  @Inject ProjectSansBootstrap projectSans;

  @Inject
  @DataSource("edge")
  AgroalDataSource edgeDataSource;

  /** The project every name in the project tiers is spelled with here. It has environments. */
  private static final String PROJECT = "acme";

  /**
   * A project that has NO environments, whose applications therefore carry no environment label —
   * {@code <app>.gizmo.example.com}, served in the default environment. It is the shape the
   * platform's own project takes once its {@code supportsEnvironments} flag is off.
   */
  private static final String FLAT_PROJECT = "gizmo";

  private static EdgeClient client;

  /**
   * Built on first use, not in {@code @BeforeAll}. Quarkus fills {@code RestAssured.port} in from
   * the port the server actually bound, and with {@code quarkus.http.test-port=0} that is not known
   * until it has; a client constructed in {@code @BeforeAll} reads the unset {@code -1}.
   */
  private static EdgeClient client() {
    if (client == null) {
      client = new EdgeClient(RestAssured.port);
    }
    return client;
  }

  private static EdgeClient http2;

  /** The same, speaking cleartext HTTP/2 with prior knowledge — see {@link EdgeClient}. */
  private static EdgeClient http2() {
    if (http2 == null) {
      http2 = new EdgeClient(RestAssured.port, HttpVersion.HTTP_2);
    }
    return http2;
  }

  @AfterAll
  static void close() {
    if (client != null) {
      client.close();
      client = null;
    }
    if (http2 != null) {
      http2.close();
      http2 = null;
    }
  }

  @BeforeEach
  void publishEnvironmentFixture() throws Exception {
    clearProjection();
    publishProject();
    for (String environment : List.of("dev", "prod")) {
      seed.deploy(
          new JsonObject()
              .put("applicationName", "test-environment")
              .put("environmentName", environment)
              .put(
                  "endpoints",
                  new io.vertx.core.json.JsonArray()
                      .add(
                          endpoint(
                              "/", upstream("qits.test.environment-upstreams." + environment)))));
      publishMirror(environment);
    }
  }

  /**
   * The mirror's host, published as qits-deployments publishes it (qits-2744): `mirror` is no
   * longer a platform label in code, so the edge reaches it only through the route table.
   */
  private void publishMirror(String environment) {
    seed.deploy(
        new JsonObject()
            .put("applicationName", "qits-mirror")
            .put("environmentName", environment)
            .put("browserHost", "mirror")
            .put(
                "endpoints",
                new io.vertx.core.json.JsonArray()
                    .add(
                        endpoint(
                            "/mirror",
                            upstream("qits.test.app-upstreams.mirror." + environment)))));
  }

  // --- the door, which serves nothing -----------------------------------------------------------

  @Test
  void theDoorServesNoPathAtAll() {
    // The ruling: every service is on its own name, so the environment's own name routes nothing.
    // These are the paths that worked here before — a wire protocol, a clone URL, an SPA's own XHR,
    // a segment, the login page — and each of them is now a 404 that reaches no upstream.
    activateArtifacts();
    activateCi();
    activateProjects();
    for (String path :
        List.of("/v2/", "/git/x", "/ci/api/runs", "/ci/", "/idp/login", "/anything")) {
      EdgeClient.Answer answer = client().get("dev.acme.example.com", path);
      assertEquals(404, answer.status(), path);
      assertNull(answer.line("upstream"), path + " must reach no upstream");
      assertTrue(answer.body().contains("serves nothing"), answer.body());
    }
  }

  @Test
  void neitherACookieNorAMachineBearerOpensTheDoor() {
    // A credential is not a key to a name that routes nothing. Both are what a caller that has not
    // moved to the service's own name would hold.
    activateCi();
    for (Map<String, String> credential :
        List.of(Map.of("Cookie", "qits-session=" + StubGateways.SESSION), token())) {
      EdgeClient.Answer answer = client().get("dev.acme.example.com", "/ci/api/runs", credential);
      assertEquals(404, answer.status(), credential.toString());
      assertNull(answer.line("upstream"));
    }
  }

  @Test
  void aWebSocketUpgradeToTheDoorIsRefusedRatherThanProxied() {
    // The terminals moved with everything else. An upgrade here must be answered, not forwarded.
    activateCi();
    EdgeClient.Answer answer =
        client()
            .send(
                HttpMethod.GET,
                "dev.acme.example.com",
                "/terminal",
                null,
                Map.of("Upgrade", "websocket", "Connection", "Upgrade"));
    assertEquals(404, answer.status());
    assertNull(answer.line("upstream"));
  }

  // --- the routing decision ------------------------------------------------------------------

  @Test
  void anApplicationSubdomainReachesThatEnvironmentsApplication() {
    // The WP1 decision in one line: the app label picks the upstream, the env label picks whose.
    assertEquals(
        "registry-dev",
        client().get("registry.dev.acme.example.com", "/v2/", token()).line("upstream"));
    assertEquals(
        "registry-prod",
        client().get("registry.prod.acme.example.com", "/v2/", token()).line("upstream"));
  }

  @Test
  void anUnconfiguredApplicationLabelIsRefusedRatherThanSentToTheGateway() {
    // NOT a fall-through. The name was aimed at a service, and the gateway is the hop that does not
    // authenticate these — a mistyped registry vhost reaching it would be an open door with a typo
    // for a key.
    EdgeClient.Answer answer = client().get("registy.dev.acme.example.com", "/v2/");
    assertEquals(404, answer.status());
    assertTrue(answer.body().contains("registy"), answer.body());
    assertNull(answer.line("upstream"), "it must not have reached any upstream");
  }

  @Test
  void aDeploymentActiveEndpointIsProxiedDirectlyAndItsPrefixHasABoundary() throws Exception {
    clearProjection();
    // ci's second route has an upstream of its own, so reaching it proves the route's endpoint is
    // proxied, not the host's primary upstream.
    seed.deploy(
        new JsonObject()
            .put("applicationName", "qits-ci")
            .put("environmentName", "dev")
            .put("browserHost", "ci")
            .put(
                "endpoints",
                new io.vertx.core.json.JsonArray()
                    .add(endpoint("/ci", upstream("qits.test.app-upstreams.mirror.dev")))
                    .add(endpoint("/hooks", upstream("qits.edge.apps.registry.hosts.dev")))));

    assertEquals(
        "registry-dev",
        client().get("ci.dev.acme.example.com", "/hooks/push", token()).line("upstream"));
    // The route's prefix boundary matters: /hooks catches a child, never this merely similar word
    // — which nobody declared, so it falls to the service whose name this is.
    assertEquals(
        "mirror-dev",
        client().get("ci.dev.acme.example.com", "/hooks-old", token()).line("upstream"));
  }

  /**
   * The deployer's WHOLE table, as it is built today: the field names of {@code GET
   * /deployments/api/edge-routes/{edge}} spelt as literals, plus fields this edge does not read
   * ({@code builtAt}, and an entry field the deployer may grow next). Spelling them out is the
   * point — a rename on the far side has to fail HERE, loudly, instead of quietly unrouting an
   * application; and an addition must cost nothing.
   */
  @Test
  void aFullyPopulatedDeployerTableRoutesAndItsUnreadFieldsAreIgnored() throws Exception {
    clearProjection();
    Upstream upstream = upstream("qits.edge.apps.registry.hosts.dev");
    JsonObject table =
        new JsonObject()
            .put("edge", "public")
            .put("version", TableSeed.nextVersion())
            .put("builtAt", "2026-11-13T09:20:00Z")
            .put(
                "applications",
                new io.vertx.core.json.JsonArray()
                    .add(
                        new JsonObject()
                            .put("environment", "dev")
                            .put("application", "qits-artifacts")
                            .put("exposure", "public")
                            .put("browserHost", "registry")
                            .put("apiDocsPath", "/artifacts/q/swagger-ui")
                            .put("commitSha", "8a9b8fa6c2d14e0b7f35a9c8d1e2b4f60a3c7d95")
                            .put(
                                "endpoints",
                                new io.vertx.core.json.JsonArray()
                                    .add(endpoint("/artifacts", upstream))
                                    .add(endpoint("/v2", upstream)))
                            .put(
                                "navigation",
                                new io.vertx.core.json.JsonArray()
                                    .add(
                                        placement("services.details", "Artifacts", 3)
                                            .putNull("subpath")))))
            .put("withdrawn", new io.vertx.core.json.JsonArray());

    assertTrue(tables.offer(table) instanceof RouteTables.Stored);

    // Every part landed: the routes, the public name and the placement.
    assertEquals("qits-artifacts", routes.resolve("dev", "/artifacts/api/files").application());
    assertEquals("registry", routes.applicationHost("dev", "qits-artifacts").host());
    assertEquals(
        "registry-dev",
        client()
            .get("registry.dev.acme.example.com", "/artifacts/api/files", token())
            .line("upstream"));
    assertNotNull(routes.resolve("dev", "/v2/"));
    assertEquals(
        List.of(
            new EdgeRoutes.NavigationPlacement(
                "qits-artifacts",
                "services.details",
                "Artifacts",
                3,
                "registry",
                "/artifacts",
                null)),
        routes.navigation("dev").stream()
            .filter(placement -> "qits-artifacts".equals(placement.application()))
            .toList());

    // The entry's environment is the routing key: it is served there alone.
    assertNull(routes.resolve("prod", "/artifacts/api/files"));
  }

  @Test
  void mainNavigationCarriesEverySlotAndOneOriginPerService() {
    activateArtifacts();
    activateCi();

    EdgeClient.Answer navigation = client().get("dev.acme.example.com", "/main-navigation");
    assertEquals(200, navigation.status());
    assertEquals("no-store", navigation.headers().get("cache-control"));
    JsonObject document = new JsonObject(navigation.body());
    assertEquals("dev", document.getString("environment"));
    assertEquals("http://dev.acme.example.com", document.getString("origin"));

    JsonObject slots = document.getJsonObject("slots");
    // Every key, empty ones included: a shell iterates the document rather than a second copy of
    // the vocabulary.
    assertEquals(
        List.of(
            "system",
            "platform",
            "project.detail",
            "services.details",
            "daemons.details",
            "libs.details",
            "apps.details",
            "frontends.details",
            "cli.details",
            "images.details"),
        List.copyOf(slots.fieldNames()));
    assertTrue(slots.getJsonArray("platform").isEmpty(), slots.encode());

    // Position, then label. CI is 2 and Artifacts is 3, so the deployment's order is the one shown.
    assertEquals(
        List.of("CI", "Artifacts"),
        slots.getJsonArray("services.details").stream()
            .map(value -> ((JsonObject) value).getString("label"))
            .toList());
    assertEquals(
        List.of("http://ci.dev.acme.example.com", "http://registry.dev.acme.example.com"),
        slots.getJsonArray("services.details").stream()
            .map(value -> ((JsonObject) value).getString("origin"))
            .toList());
    assertEquals(
        List.of("qits-ci", "qits-artifacts"),
        slots.getJsonArray("services.details").stream()
            .map(value -> ((JsonObject) value).getString("app"))
            .toList());
    // The primary route is served with a hosted entry too: it is what a shell renders an
    // application
    // under until that application is flipped, so nothing leaves the sidebar mid-rollout.
    assertEquals(
        List.of("/ci", "/artifacts"),
        slots.getJsonArray("services.details").stream()
            .map(value -> ((JsonObject) value).getString("path"))
            .toList());
  }

  @Test
  void anAppsDetailsPlacementIsAdmittedAndRendersBetweenLibsAndFrontends() {
    // The seventh archetype slot. It is published exactly like the six before it; what makes it the
    // seventh rather than the last is where it sits in EdgeRoutes.SLOTS, which is both the order
    // this document renders and the order qits-deployments' spec parser reads the same words in.
    seed.deploy(
        new JsonObject()
            .put("applicationName", "qits-ci")
            .put("environmentName", "dev")
            .put("browserHost", "ci")
            .put(
                "endpoints",
                new io.vertx.core.json.JsonArray()
                    .add(endpoint("/ci", upstream("qits.test.app-upstreams.mirror.dev"))))
            .put(
                "navigation",
                // Declared out of order on purpose: the vocabulary decides the render order,
                // not the order the deployment happened to write the placements in.
                new io.vertx.core.json.JsonArray()
                    .add(placement("frontends.details", "Shell", 1))
                    .add(placement("apps.details", "Docs", 1))
                    .add(placement("libs.details", "Eventstream", 1))));

    assertEquals(
        List.of("libs.details", "apps.details", "frontends.details"),
        routes.navigation("dev").stream().map(EdgeRoutes.NavigationPlacement::slot).toList());

    JsonObject slots =
        new JsonObject(client().get("dev.acme.example.com", "/main-navigation").body())
            .getJsonObject("slots");
    assertEquals(
        List.of("Docs"),
        slots.getJsonArray("apps.details").stream()
            .map(value -> ((JsonObject) value).getString("label"))
            .toList());
    assertEquals(
        "http://ci.dev.acme.example.com",
        slots.getJsonArray("apps.details").getJsonObject(0).getString("origin"));
    // The key order of the document is the render order, so the new slot is drawn after the
    // libraries and before the microfrontends — the order qits-deployments publishes in too.
    List<String> keys = List.copyOf(slots.fieldNames());
    assertEquals(keys.indexOf("libs.details") + 1, keys.indexOf("apps.details"), keys.toString());
    assertEquals(
        keys.indexOf("apps.details") + 1, keys.indexOf("frontends.details"), keys.toString());
  }

  @Test
  void aWordOutsideTheVocabularyIsStillRefusedWholeAfterTheSeventhSlotWasAdded() {
    // Admitting a word does not open the vocabulary. The near miss is the singular of the new one,
    // which is exactly the typo a hand-written spec makes, and the refusal names both the word and
    // the list so it is readable where it is logged.
    String message =
        assertThrows(
                IllegalArgumentException.class,
                () -> new EdgeRoutes.NavigationEntry("app.details", "Docs", 1))
            .getMessage();
    assertTrue(message.contains("`app.details` is not a navigation slot"), message);
    assertTrue(message.contains("apps.details"), message);

    // And, like every refused table, the one carrying it changes no routes at all.
    seed.deploy(
        new JsonObject()
            .put("applicationName", "qits-ci")
            .put("environmentName", "dev")
            .put("browserHost", "ci")
            .put(
                "endpoints",
                new io.vertx.core.json.JsonArray()
                    .add(endpoint("/ci", upstream("qits.test.app-upstreams.mirror.dev"))))
            .put(
                "navigation",
                new io.vertx.core.json.JsonArray().add(placement("app.details", "Docs", 1))));

    assertTrue(routes.navigation("dev").isEmpty());
    assertNull(routes.serviceHost("dev", "ci"));
  }

  @Test
  void mainNavigationIsSlotsApplicationsAndNothingElse() {
    // No flat list and no synthesized Home. Every shell reads the tree, and the environment's own
    // door is qits-projects' `system` entry — a deployment fact like every other entry here.
    // `projectOrigin` is the one addition: the authority a client puts `<app>.<slug>.` in front of.
    activateArtifacts();
    activateCi();

    JsonObject document =
        new JsonObject(client().get("dev.acme.example.com", "/main-navigation").body());
    assertEquals(
        List.of("environment", "origin", "projectOrigin", "slots", "applications"),
        List.copyOf(document.fieldNames()));
  }

  @Test
  void mainNavigationCarriesApiDocsPerApplicationAndSubpathsPerEntry() {
    // Two additions, two shapes. `applications` is per-application metadata — one object per
    // application that published an api-docs path, keyed by name, for the shell page that knows
    // which repository it shows. `subpath` rides each entry and names the view it opens, relative
    // to the scope the shell composes; entries declared before it existed read null.
    activateCi();
    seed.deploy(
        new JsonObject()
            .put("applicationName", "qits-projects")
            .put("environmentName", "dev")
            .put("browserHost", "projects")
            .put(
                "endpoints",
                new io.vertx.core.json.JsonArray()
                    .add(endpoint("/projects", upstream("qits.edge.apps.registry.hosts.dev"))))
            .put(
                "navigation",
                new io.vertx.core.json.JsonArray()
                    .add(placement("system", "Overview", 1))
                    .add(placement("services.details", "Api Docs", 6).put("subpath", "api-docs"))));

    JsonObject document =
        new JsonObject(client().get("dev.acme.example.com", "/main-navigation").body());
    JsonObject applications = document.getJsonObject("applications");
    assertEquals("/ci/q/swagger-ui", applications.getJsonObject("qits-ci").getString("apiDocs"));
    assertNull(
        applications.getJsonObject("qits-projects").getString("apiDocs"),
        "no api-docs, no apiDocs — the entry is there for its origin alone");

    JsonObject services =
        document.getJsonObject("slots").getJsonArray("services.details").stream()
            .map(JsonObject.class::cast)
            .filter(entry -> "qits-projects".equals(entry.getString("app")))
            .findFirst()
            .orElseThrow();
    assertEquals("api-docs", services.getString("subpath"));
    JsonObject ci =
        document.getJsonObject("slots").getJsonArray("services.details").stream()
            .map(JsonObject.class::cast)
            .filter(entry -> "qits-ci".equals(entry.getString("app")))
            .findFirst()
            .orElseThrow();
    assertNull(ci.getString("subpath"), "an entry without one opens the application's root");
  }

  @Test
  void oneApplicationHangsSeveralRowsUnderOneHeading() {
    // qits-workspaces is one application in one container publishing TWO rows under the project
    // node: the workspace list and the runner list. The claim used to be the slot alone, which
    // refused
    // this whole frame — and refused the spec a hop earlier, so the deployment failed as
    // "deployment spec unreadable" while its build stayed green.
    activateWorkspaces();

    List<JsonObject> project =
        new JsonObject(client().get("dev.acme.example.com", "/main-navigation").body())
            .getJsonObject("slots").getJsonArray("project.detail").stream()
                .map(JsonObject.class::cast)
                .toList();
    assertEquals(
        List.of("Workspaces", "Runners"),
        project.stream().map(entry -> entry.getString("label")).toList(),
        "two rows, in the order the positions asked for");
    assertEquals(
        List.of("qits-workspaces", "qits-workspaces"),
        project.stream().map(entry -> entry.getString("app")).toList(),
        "both of them are the same application, and nothing collapses them");
    // The two rows differ where a shell needs them to: one opens the application's root under the
    // scope, the other the view its subpath names. Everything else — origin, path — is shared,
    // because it is one application.
    assertEquals(
        List.of("http://workspaces.dev.acme.example.com", "http://workspaces.dev.acme.example.com"),
        project.stream().map(entry -> entry.getString("origin")).toList());
    assertNull(project.get(0).getString("subpath"));
    assertEquals("runners", project.get(1).getString("subpath"));
  }

  @Test
  void twoRowsOfOneApplicationAtOnePositionAreATieAndNotARefusal() {
    // A position is the repository's own number, and the document has always broken a tie by label
    // and then by application — two applications naming 1 in one slot is ordinary. So the same tie
    // inside one application is ordinary too: refusing it would be a rule this document does not
    // have, and both rows would still be renderable if it did.
    seed.deploy(
        new JsonObject()
            .put("applicationName", "qits-workspaces")
            .put("environmentName", "dev")
            .put("browserHost", "workspaces")
            .put(
                "endpoints",
                new io.vertx.core.json.JsonArray()
                    .add(endpoint("/workspaces", upstream("qits.test.app-upstreams.mirror.dev"))))
            .put(
                "navigation",
                new io.vertx.core.json.JsonArray()
                    .add(placement("project.detail", "Workspaces", 1))
                    .add(placement("project.detail", "Runners", 1).put("subpath", "runners"))));

    assertEquals(
        List.of("Runners", "Workspaces"),
        new JsonObject(client().get("dev.acme.example.com", "/main-navigation").body())
            .getJsonObject("slots").getJsonArray("project.detail").stream()
                .map(value -> ((JsonObject) value).getString("label"))
                .toList(),
        "the tie is broken by label, as it is between applications");
  }

  @Test
  void theSamePlacementTwiceIsRefusedWholeAndNamesThePair() throws Exception {
    // One row asked for twice: the table is refused whole, with a reason that names the pair — a
    // table with four project.detail entries says which one is twice — and changes no routes.
    clearProjection();
    RouteTables.Outcome outcome =
        seed.deploy(
            new JsonObject()
                .put("applicationName", "qits-workspaces")
                .put("environmentName", "dev")
                .put("browserHost", "workspaces")
                .put(
                    "endpoints",
                    new io.vertx.core.json.JsonArray()
                        .add(
                            endpoint(
                                "/workspaces", upstream("qits.test.app-upstreams.mirror.dev"))))
                .put(
                    "navigation",
                    new io.vertx.core.json.JsonArray()
                        .add(placement("project.detail", "Runners", 1))
                        .add(placement("project.detail", "Runners", 2).put("subpath", "runners"))));

    assertTrue(routes.navigation("dev").isEmpty());
    assertNull(routes.serviceHost("dev", "workspaces"));
    assertNull(routes.resolve("dev", "/workspaces/42"), "the refused table published no routes");
    String message = ((RouteTables.Invalid) outcome).reason();
    assertTrue(message.contains("`Runners` in project.detail twice"), message);
  }

  @Test
  void anApiDocsPathUnderNoneOfItsOwnRoutesIsSettledWithoutChangingRoutes() throws Exception {
    // The spec parser refuses this shape at the source; the table store restates the rule so a
    // hand-crafted table is refused rather than publishing a document nothing serves.
    clearProjection();
    seed.deploy(
        new JsonObject()
            .put("applicationName", "qits-ci")
            .put("environmentName", "dev")
            .put("apiDocsPath", "/docs/q/swagger-ui")
            .put(
                "endpoints",
                new io.vertx.core.json.JsonArray()
                    .add(endpoint("/ci", upstream("qits.test.app-upstreams.mirror.dev")))));

    JsonObject document =
        new JsonObject(client().get("dev.acme.example.com", "/main-navigation").body());
    assertTrue(document.getJsonObject("applications").isEmpty(), document.encode());
    assertNull(routes.resolve("dev", "/ci/api"), "the refused table published no routes either");
  }

  @Test
  void everyApplicationWithAPublishedHostHasItsOriginInTheNavigation() {
    // Additive: the origin an SPA reads another application's API on once a hostname alone picks
    // the application. brochure places no navigation entry at all and still gets one; ci keeps its
    // apiDocs beside it.
    activateCi();
    activateBrochure();
    JsonObject applications =
        new JsonObject(client().get("dev.acme.example.com", "/main-navigation").body())
            .getJsonObject("applications");
    assertEquals(
        "http://brochure.dev.acme.example.com",
        applications.getJsonObject("qits-brochure").getString("origin"));
    assertNull(applications.getJsonObject("qits-brochure").getString("apiDocs"));
    assertEquals(
        "http://ci.dev.acme.example.com",
        applications.getJsonObject("qits-ci").getString("origin"));
    assertEquals("/ci/q/swagger-ui", applications.getJsonObject("qits-ci").getString("apiDocs"));
    // Only applications that publish a host: the environment's own catch-all publishes none.
    assertNull(applications.getJsonObject("test-environment"));
  }

  @Test
  void navigationIsServedOnAServiceHostToo() {
    // Every shell renders the same tree, so the document is on every vhost — and it names the
    // environment's origins even when the request itself carried an application's name.
    activateCi();
    JsonObject document =
        new JsonObject(client().get("ci.dev.acme.example.com", "/main-navigation").body());
    assertEquals("dev", document.getString("environment"));
    assertEquals("http://dev.acme.example.com", document.getString("origin"));
    assertEquals(
        "http://ci.dev.acme.example.com",
        document
            .getJsonObject("slots")
            .getJsonArray("services.details")
            .getJsonObject(0)
            .getString("origin"));
  }

  // --- /upstream-pools
  // ----------------------------------------------------------------------------

  @Test
  void upstreamPoolsAnswersNobodyWithoutACredential() {
    // The document names every upstream on the estate and how loaded it is. A cookie is no
    // credential here either: the browser gate is off in this suite, so the edge reads none.
    for (Map<String, String> credential :
        List.of(
            Map.<String, String>of(),
            Map.of("Cookie", "qits-session=" + StubGateways.SESSION),
            bearer("not-a-jwt"),
            aliasToken("dev"))) {
      EdgeClient.Answer answer =
          client().get("dev.acme.example.com", UpstreamPoolsRoute.PATH, credential);
      assertEquals(401, answer.status(), credential.toString());
      assertEquals("no-store", answer.headers().get("cache-control"));
      assertNull(answer.line("upstream"), "answered by the edge, never proxied");
    }
  }

  @Test
  void upstreamPoolsListsEachOpenPoolWithItsApplicationFullestFirst() {
    // One request to dev's registry leaves one pooled connection to its stub — keep-alive is the
    // point of a pool — and the platform app grid says whose that address is.
    assertEquals(
        "registry-dev",
        client().get("registry.dev.acme.example.com", "/v2/", token()).line("upstream"));

    for (String host : List.of("dev.acme.example.com", "ci.dev.acme.example.com", "localhost")) {
      EdgeClient.Answer answer = client().get(host, UpstreamPoolsRoute.PATH, token());
      assertEquals(200, answer.status(), host);
      assertEquals("no-store", answer.headers().get("cache-control"));
      assertNull(answer.line("upstream"), "answered by the edge on every name, never proxied");

      io.vertx.core.json.JsonArray pools = new io.vertx.core.json.JsonArray(answer.body());
      JsonObject registry = null;
      int previous = Integer.MAX_VALUE;
      for (int i = 0; i < pools.size(); i++) {
        JsonObject pool = pools.getJsonObject(i);
        assertEquals(
            java.util.Set.of("name", "environment", "origin", "pool", "open", "max", "held"),
            pool.fieldNames(),
            pool.encode());
        assertTrue(
            java.util.Set.of(UpstreamPools.ORDINARY, UpstreamPools.STREAM)
                .contains(pool.getString("pool")),
            pool.encode());
        assertEquals(256, pool.getInteger("max"), "the configured pool size");
        assertTrue(pool.getInteger("open") >= 1, "only origins holding a connection are listed");
        assertTrue(pool.getInteger("open") <= previous, "fullest first: " + pools.encode());
        assertTrue(
            pool.getJsonArray("held").size() <= pool.getInteger("open"),
            "a lease is on an open connection: " + pool.encode());
        previous = pool.getInteger("open");
        if (pool.getString("origin")
                .equals(upstream("qits.edge.apps.registry.hosts.dev").toString())
            && pool.getString("pool").equals(UpstreamPools.ORDINARY)) {
          registry = pool;
        }
      }
      assertNotNull(registry, "dev's registry holds an ordinary connection: " + pools.encode());
      assertEquals("qits-artifacts", registry.getString("name"));
      assertEquals("dev", registry.getString("environment"));
    }
  }

  @Test
  void anEventStreamGetIsCarriedByTheStreamPoolAndNamedByItsLeaseUntilItCloses() throws Exception {
    // The fix and its evidence in one exchange. An SSE GET holds its upstream connection for as
    // long as it is open, so it must be counted in the STREAM pool and leave the ordinary pool's
    // count alone — and while it is open, /upstream-pools names it: the path without its query,
    // the workspace, the caller, the trace. The edge's OTel SDK is off in this suite, so the trace
    // id is the caller's traceparent's — the id the server span would have continued.
    Upstream registry = upstream("qits.edge.apps.registry.hosts.dev");
    String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
    int ordinaryBefore = openTo(UpstreamPools.ORDINARY, registry);
    io.vertx.core.Vertx vertx = io.vertx.core.Vertx.vertx();
    try {
      io.vertx.core.http.RequestOptions options =
          new io.vertx.core.http.RequestOptions()
              .setServer(
                  io.vertx.core.net.SocketAddress.inetSocketAddress(RestAssured.port, "127.0.0.1"))
              .setHost("registry.dev.acme.example.com")
              .setPort(80)
              .setMethod(HttpMethod.GET)
              .setURI(StubGateways.SSE_HOLD_PATH + "?workspaceId=ws-42&token=not-in-the-lease");
      token().forEach(options::putHeader);
      options.putHeader("Accept", "text/event-stream");
      options.putHeader("User-Agent", "lease-test/1");
      options.putHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
      options.putHeader("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01");
      java.util.concurrent.CompletableFuture<io.vertx.core.http.HttpClientResponse> opened =
          new java.util.concurrent.CompletableFuture<>();
      java.util.concurrent.CompletableFuture<String> firstChunk =
          new java.util.concurrent.CompletableFuture<>();
      vertx
          .createHttpClient()
          .request(options)
          .compose(request -> request.send())
          .onSuccess(
              response -> {
                response.handler(chunk -> firstChunk.complete(chunk.toString()));
                opened.complete(response);
              })
          .onFailure(opened::completeExceptionally);
      io.vertx.core.http.HttpClientResponse response =
          opened.get(30, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(200, response.statusCode());
      assertEquals(": open\n\n", firstChunk.get(30, java.util.concurrent.TimeUnit.SECONDS));

      JsonObject held = held(UpstreamPools.STREAM, registry, StubGateways.SSE_HOLD_PATH);
      assertNotNull(held, "the open stream is leased in the stream pool: " + poolsDocument());
      assertEquals("GET", held.getString("method"));
      assertEquals(StubGateways.SSE_HOLD_PATH, held.getString("path"), "no query string");
      assertEquals("ws-42", held.getString("workspaceId"));
      assertEquals("203.0.113.7", held.getString("client"), "the first forwarded hop");
      assertEquals("lease-test/1", held.getString("userAgent"));
      assertEquals(traceId, held.getString("traceId"));
      assertTrue(held.getLong("ageMs") >= 0, held.encode());
      assertNull(
          held(UpstreamPools.ORDINARY, registry, StubGateways.SSE_HOLD_PATH),
          "and not in the ordinary pool: " + poolsDocument());
      assertTrue(
          openTo(UpstreamPools.ORDINARY, registry) <= ordinaryBefore,
          "the stream took no ordinary connection: " + poolsDocument());

      // The caller goes away. The edge resets the origin request, which closes its connection,
      // and the lease goes with it — nothing about a closed stream is left holding a name.
      response.request().connection().close();
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
      while (held(UpstreamPools.STREAM, registry, StubGateways.SSE_HOLD_PATH) != null) {
        assertTrue(
            System.nanoTime() < deadline,
            "a closed stream's lease is released: " + poolsDocument());
        Thread.sleep(50);
      }
    } finally {
      StubGateways.releaseStreams();
      vertx.close();
    }
  }

  /** The {@code /upstream-pools} document as an admitted caller reads it. */
  private static io.vertx.core.json.JsonArray poolsDocument() {
    EdgeClient.Answer answer =
        client().get("dev.acme.example.com", UpstreamPoolsRoute.PATH, token());
    assertEquals(200, answer.status(), answer.body());
    return new io.vertx.core.json.JsonArray(answer.body());
  }

  /** One pool's entry for one origin, or null when that pool holds nothing open to it. */
  private static JsonObject entry(String pool, Upstream origin) {
    io.vertx.core.json.JsonArray document = poolsDocument();
    for (int i = 0; i < document.size(); i++) {
      JsonObject entry = document.getJsonObject(i);
      if (entry.getString("pool").equals(pool)
          && entry.getString("origin").equals(origin.toString())) {
        return entry;
      }
    }
    return null;
  }

  private static int openTo(String pool, Upstream origin) {
    JsonObject entry = entry(pool, origin);
    return entry == null ? 0 : entry.getInteger("open");
  }

  /** The lease on one path in one pool's entry for one origin, or null. */
  private static JsonObject held(String pool, Upstream origin, String path) {
    JsonObject entry = entry(pool, origin);
    if (entry == null) {
      return null;
    }
    io.vertx.core.json.JsonArray held = entry.getJsonArray("held");
    for (int i = 0; i < held.size(); i++) {
      if (path.equals(held.getJsonObject(i).getString("path"))) {
        return held.getJsonObject(i);
      }
    }
    return null;
  }

  // --- a service's own name ---------------------------------------------------------------------

  @Test
  void aPublishedHostServesItsOwnServiceAtTheRoot() {
    activateCi();
    // `/` belongs to test-environment in this environment, and on a service's own name it is still
    // that service's: the hostname alone picks the application, the catch-all included.
    assertEquals(
        "mirror-dev", client().get("ci.dev.acme.example.com", "/", token()).line("upstream"));
    assertEquals(
        "/deep/link", client().get("ci.dev.acme.example.com", "/deep/link", token()).line("uri"));
  }

  @Test
  void anotherApplicationsRouteOnAServiceHostIsAnsweredByTheHostsOwnService() {
    // The hostname alone picks the application. Another application's route — its PRIMARY one,
    // /artifacts, the segment it is known by, or a secondary wire route like /v2 — means nothing on
    // ci's name: ci's own service answers, exactly as for a path nobody declared. An SPA reads
    // another application on that application's own name, cross-origin (EdgeCors).
    activateCi();
    activateArtifacts();
    for (String path : List.of("/artifacts/api/files", "/v2/")) {
      EdgeClient.Answer answer = client().get("ci.dev.acme.example.com", path, token());
      assertEquals("mirror-dev", answer.line("upstream"), path);
      assertEquals(path, answer.line("uri"), path);
      // On its owner's own name it is the owner's.
      assertEquals(
          "registry-dev",
          client().get("registry.dev.acme.example.com", path, token()).line("upstream"),
          path);
    }
    // The candidates are the host's own routes, longest prefix first among them.
    assertNull(routes.resolve("dev", "qits-ci", "/artifacts/api/files"));
    assertEquals("/v2", routes.resolve("dev", "qits-artifacts", "/v2/library/x").path());
  }

  // --- CORS, which the edge owns on every service host ------------------------------------------

  @Test
  void anOriginUnderTheDomainIsEchoedWithCredentials() {
    activateCi();
    for (String origin :
        List.of(
            "https://projects.dev.acme.example.com",
            "https://example.com",
            "https://acme.example.com",
            "https://a.b.c.d.e.example.com")) {
      EdgeClient.Answer answer =
          client()
              .get("ci.dev.acme.example.com", "/ci/api/runs", withToken(Map.of("Origin", origin)));
      assertEquals(200, answer.status(), origin);
      assertEquals(List.of(origin), answer.headerValues("Access-Control-Allow-Origin"), origin);
      assertEquals(List.of("true"), answer.headerValues("Access-Control-Allow-Credentials"));
      assertEquals(List.of(EdgeCors.EXPOSED), answer.headerValues("Access-Control-Expose-Headers"));
      assertTrue(answer.headerValues("Vary").contains("Origin"), answer.raw().toString());
    }
    // A streamed answer — the shape an SSE channel has — carries it on its head like any other.
    EdgeClient.Answer streamed =
        client()
            .get(
                "ci.dev.acme.example.com",
                "/stream",
                withToken(Map.of("Origin", "https://projects.dev.acme.example.com")));
    assertTrue(streamed.body().contains("chunk-2"), streamed.body());
    assertEquals(
        List.of("https://projects.dev.acme.example.com"),
        streamed.headerValues("Access-Control-Allow-Origin"));
  }

  @Test
  void aForeignOriginGetsNoAllowOrigin() {
    activateCi();
    for (String origin :
        List.of(
            "https://evil.example",
            "https://example.com.evil.example",
            "https://evilexample.com",
            // The scheme is the one the canonical origin was derived with: https under a real
            // domain.
            "http://projects.dev.acme.example.com",
            "https://projects.dev.acme.example.com:8443",
            "null")) {
      EdgeClient.Answer answer =
          client()
              .get("ci.dev.acme.example.com", "/ci/api/runs", withToken(Map.of("Origin", origin)));
      assertEquals(200, answer.status(), origin);
      assertEquals(List.of(), answer.headerValues("Access-Control-Allow-Origin"), origin);
      assertEquals(List.of(), answer.headerValues("Access-Control-Allow-Credentials"), origin);
      assertTrue(answer.headerValues("Vary").contains("Origin"), origin);
    }
  }

  // --- X-Qits-Caller, which the edge asserts for a page on the platform's own names
  // ---------------

  @Test
  void aPlatformPagesRequestReachesTheServiceWithItsCallerAssertedOverAForgedOne() {
    // A qits-projects page calling qits-ci. The client's own X-Qits-Caller is stripped with the
    // rest of the reserved prefix, and the edge's reading of the Origin is what arrives.
    activateCi();
    activateProjects();
    EdgeClient.Answer answer =
        client()
            .get(
                "ci.dev.acme.example.com",
                "/ci/api/runs",
                withToken(
                    Map.of(
                        "Origin",
                        "https://projects.dev.acme.example.com",
                        EdgeHeaders.CALLER,
                        "qits-idp")));
    assertEquals(200, answer.status());
    assertEquals("qits-projects", answer.upstreamHeader(EdgeHeaders.CALLER));
  }

  @Test
  void withoutAnOriginTheReferersPageIsTheCallerAndAServiceCallingItselfIsStillOne() {
    activateCi();
    EdgeClient.Answer answer =
        client()
            .get(
                "ci.dev.acme.example.com",
                "/ci/api/runs",
                withToken(Map.of("Referer", "https://ci.dev.acme.example.com/ci/runs/42?tab=log")));
    assertEquals("qits-ci", answer.upstreamHeader(EdgeHeaders.CALLER));
  }

  @Test
  void aForeignOrUnservedPageAssertsNoCallerAndAForgedOneStaysStripped() {
    activateCi();
    for (String origin :
        List.of(
            "https://evil.example",
            "https://nosuchapp.dev.acme.example.com",
            "https://acme.example.com",
            "null")) {
      EdgeClient.Answer answer =
          client()
              .get(
                  "ci.dev.acme.example.com",
                  "/ci/api/runs",
                  withToken(Map.of("Origin", origin, EdgeHeaders.CALLER, "qits-idp")));
      assertEquals(200, answer.status(), origin);
      assertNull(answer.upstreamHeader(EdgeHeaders.CALLER), origin);
    }
    // And no page at all: a machine's request names none.
    assertNull(
        client()
            .get(
                "ci.dev.acme.example.com",
                "/ci/api/runs",
                withToken(Map.of(EdgeHeaders.CALLER, "qits-idp")))
            .upstreamHeader(EdgeHeaders.CALLER));
  }

  // --- a service bearer's own attribution (qits-1403) --------------------------------------------

  /** What a platform service stamps on its own call, and an identity it must never assert. */
  private static Map<String, String> attribution(Map<String, String> credential) {
    Map<String, String> headers = new java.util.HashMap<>(credential);
    headers.put(EdgeHeaders.CAUSATION_ID, "evt-42");
    headers.put(EdgeHeaders.CALLER, "qits-ci");
    headers.put(EdgeHeaders.ENVIRONMENT, "dev");
    headers.put("X-Qits-User", "admin");
    headers.put("X-Qits-Roles", "qits:root");
    return headers;
  }

  @Test
  void aSystemBearerKeepsItsAttributionThroughTheEdge() {
    // A platform service dialling a peer's public name: its causation, caller and environment are
    // what a trace is stitched from, and the strip used to erase them.
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev." + PROJECT + ".example.com",
                "/v2/",
                attribution(
                    bearer(
                        TestTokens.withRoles(
                            issuer(),
                            List.of(StubGateways.PLATFORM_AUDIENCE),
                            List.of("qits:system")))));
    assertEquals("registry-dev", answer.line("upstream"), answer.body());
    assertEquals("evt-42", answer.upstreamHeader(EdgeHeaders.CAUSATION_ID));
    assertEquals("qits-ci", answer.upstreamHeader(EdgeHeaders.CALLER));
    assertEquals("dev", answer.upstreamHeader(EdgeHeaders.ENVIRONMENT));
    assertNull(answer.upstreamHeader("X-Qits-User"), "identity is always stripped");
    assertNull(answer.upstreamHeader("X-Qits-Roles"));
  }

  @Test
  void anyOtherCredentialHasTheAttributionStripped() throws Exception {
    // A JWT without qits:system, and an agent's opaque token, keep today's rule: the strip.
    Thread.sleep(tokenCacheTtlMs() + 400);
    for (Map<String, String> credential : List.of(token(), bearer(StubGateways.TOKEN))) {
      EdgeClient.Answer answer =
          client().get("registry.dev." + PROJECT + ".example.com", "/v2/", attribution(credential));
      assertEquals("registry-dev", answer.line("upstream"), answer.body());
      assertNull(answer.upstreamHeader(EdgeHeaders.CAUSATION_ID));
      assertNull(answer.upstreamHeader(EdgeHeaders.CALLER));
      assertNull(answer.upstreamHeader(EdgeHeaders.ENVIRONMENT));
      assertNull(answer.upstreamHeader("X-Qits-User"));
    }
  }

  // --- the platform labels with no route table (qits-1403) --------------------------------------

  @Test
  void aPlatformLabelOnTheUnreadPlatformProjectIsServed() throws Exception {
    // A fresh edge database: `qits` is not a project here until qits-projects' log is read, and
    // that read waits on idp's name. The platform labels on it are served in the default
    // environment anyway; any other name on it is still the unknown project.
    projectSans.authoritative(false);
    try {
      EdgeClient.Answer registry = client().get("registry.qits.example.com", "/v2/", token());
      assertEquals("registry-prod", registry.line("upstream"), registry.body());

      EdgeClient.Answer other = client().get("ci.qits.example.com", "/", token());
      assertEquals(503, other.status(), "a projected name on an unread project still waits");
      assertNull(other.line("upstream"));
    } finally {
      projectSans.authoritative(true);
    }
    assertEquals(
        "registry-prod",
        client().get("registry.qits.example.com", "/v2/", token()).line("upstream"),
        "and with the barrier down, still served rather than a 404 about the project");
  }

  @Test
  void aBasicGrantOnIdpsOwnRouteReachesIdpExactlyAsSent() {
    // A service's client_credentials grant on idp's public name. The edge must not spend the pair
    // at idp and forward a minted JWT in its place: idp's /token would then see no client at all.
    int grants = StubGateways.grants();
    Map<String, String> headers = new java.util.HashMap<>(basic("dev-qits-ci", "secret"));
    headers.put("Content-Type", "application/x-www-form-urlencoded");
    headers.put("X-Qits-User", "admin");
    headers.put(EdgeHeaders.CAUSATION_ID, "evt-42");
    String sent = headers.get("Authorization");
    EdgeClient.Answer answer =
        client()
            .send(
                HttpMethod.POST,
                "idp.dev." + PROJECT + ".example.com",
                "/idp/token",
                "grant_type=client_credentials",
                headers);
    assertEquals("idp-dev", answer.line("upstream"), answer.body());
    assertEquals(sent, answer.upstreamHeader("Authorization"), "the pair, untouched");
    assertEquals(grants, StubGateways.grants(), "the edge made no grant of its own");
    assertNull(answer.upstreamHeader("X-Qits-User"), "identity is still stripped");
    assertNull(
        answer.upstreamHeader(EdgeHeaders.CAUSATION_ID),
        "and attribution too: deciding a service bearer here would need the credential checked");
  }

  @Test
  void anyCredentialOnIdpsOwnRoutePassesUncheckedWithAndWithoutARouteTable() {
    // A bearer the edge would refuse is idp's to refuse, not the edge's — and the rule is the
    // label's, so it holds on an edge that has received no route table too.
    Map<String, String> junk = bearer("not-a-jwt");
    assertEquals(
        "idp-dev",
        client()
            .get("idp.dev." + PROJECT + ".example.com", "/idp/userinfo", junk)
            .line("upstream"));
    assertEquals(
        "Bearer not-a-jwt",
        client()
            .get("idp.dev." + PROJECT + ".example.com", "/idp/api/tokens", junk)
            .upstreamHeader("Authorization"));
    seed.coldStart();
    assertEquals(
        "idp-dev",
        client()
            .get("idp.dev." + PROJECT + ".example.com", "/idp/userinfo", junk)
            .line("upstream"));
    // Off idp's route the name is gated as any other service's.
    EdgeClient.Answer offRoute = client().get("idp.dev." + PROJECT + ".example.com", "/v2/", junk);
    assertEquals(401, offRoute.status(), offRoute.body());
    assertNull(offRoute.line("upstream"));
  }

  @Test
  void aColdStartServesOnlyThePlatformAppsAndIsReady() {
    // An empty database on the public edge: no table was ever received, and none will be until the
    // deployer sees this edge healthy — so it is ready, and serves PlatformApps alone. qits-events
    // checks the edge's catch-up tokens against keys it fetches from idp's public name, through
    // this edge, so idp has to answer with no table at all.
    seed.coldStart();
    assertNull(routes.version());
    assertTrue(routes.ready(), "the public edge never waits for a push");

    EdgeClient.Answer idp =
        client().get("idp.dev." + PROJECT + ".example.com", "/idp/jwks", token());
    assertEquals("idp-dev", idp.line("upstream"), idp.body());
    EdgeClient.Answer registry =
        client().get("registry.dev." + PROJECT + ".example.com", "/v2/", token());
    assertEquals("registry-dev", registry.line("upstream"), "every platform label is served");

    // Every other name is simply not published: a 404, not a 503 waiting for a push.
    EdgeClient.Answer ci = client().get("ci.dev." + PROJECT + ".example.com", "/", token());
    assertEquals(404, ci.status(), ci.body());
    assertNull(ci.line("upstream"));
    EdgeClient.Answer navigation = client().get("dev.acme.example.com", "/main-navigation");
    assertEquals(200, navigation.status(), navigation.body());
    assertTrue(
        new JsonObject(navigation.body()).getJsonObject("applications").isEmpty(),
        navigation.body());
    RestAssured.given()
        .header("Host", "localhost")
        .when()
        .get("/q/health/ready")
        .then()
        .statusCode(200)
        .body("checks.find { it.name == 'edge upstreams' }.status", is("UP"))
        .body("checks.find { it.name == 'edge upstreams' }.data.'route-table'", is("none"));

    activateCi();
    assertEquals(
        "mirror-dev",
        client().get("ci.dev." + PROJECT + ".example.com", "/", token()).line("upstream"),
        "and a published name is served the moment its table is stored");
  }

  @Test
  void aCachedTableIsServedAtBootBeforeAnyPush() {
    // A restarted edge: the table it stored is in edge_route_table, and the boot serves it before
    // anything is pushed or fetched. Forgetting the served copy and loading again is that boot.
    activateCi();
    Long stored = routes.version();
    assertNotNull(stored);
    routes.serveNothing();
    assertNull(routes.serviceHost("dev", "ci"), "the in-memory copy is gone");

    tables.load();

    assertEquals(stored, routes.version());
    assertEquals(
        "mirror-dev",
        client().get("ci.dev." + PROJECT + ".example.com", "/", token()).line("upstream"));
    RestAssured.given()
        .header("Host", "localhost")
        .when()
        .get("/q/health/ready")
        .then()
        .statusCode(200)
        .body(
            "checks.find { it.name == 'edge upstreams' }.data.'route-table'",
            is(stored.toString()));
  }

  @Test
  void aTableTheOtherTaskStoredIsServedOnTheNextAsk() {
    // Start-first: two tasks of this edge share one database, and a push lands on either. The one
    // that did not take it catches up when the deployer next asks GET /edge-route-table, or pushes
    // the same version again.
    activateCi();
    Long stored = routes.version();
    routes.serveNothing();

    assertEquals(stored, tables.held().version());
    assertEquals(stored, routes.version(), "the GET brought the served copy level with the row");
    assertNotNull(routes.serviceHost("dev", "ci"));

    routes.serveNothing();
    assertEquals(new RouteTables.AlreadyHeld(stored), tables.offer(seed.table(stored)));
    assertEquals(stored, routes.version(), "and so does the same push again");
  }

  @Test
  void aLowerOrEqualVersionIsIgnored() {
    activateCi();
    long held = routes.version();
    JsonObject stale = seed.table(held);
    stale.put("applications", new io.vertx.core.json.JsonArray());

    assertEquals(new RouteTables.AlreadyHeld(held), tables.offer(stale.copy()));
    assertEquals(new RouteTables.AlreadyHeld(held), tables.offer(stale.put("version", held - 1)));

    assertEquals(held, routes.version());
    assertNotNull(routes.serviceHost("dev", "ci"), "the held table is still the one served");
  }

  @Test
  void anInvalidTableIsRefusedAndTheOldOneKept() {
    activateCi();
    long held = routes.version();
    JsonObject invalid = seed.table(TableSeed.nextVersion());
    invalid
        .getJsonArray("applications")
        .add(
            new JsonObject()
                .put("environment", "dev")
                .put("application", "qits-impostor")
                .put("browserHost", "ci")
                .put(
                    "endpoints",
                    new io.vertx.core.json.JsonArray()
                        .add(
                            endpoint(
                                "/impostor", upstream("qits.test.environment-upstreams.dev")))));

    RouteTables.Outcome outcome = tables.offer(invalid);

    assertTrue(outcome instanceof RouteTables.Invalid, outcome.toString());
    assertTrue(((RouteTables.Invalid) outcome).reason().contains("`ci`"), outcome.toString());
    assertEquals(held, routes.version());
    assertEquals("qits-ci", routes.serviceHost("dev", "ci").application());
    assertEquals(
        "mirror-dev", client().get("ci.dev.acme.example.com", "/", token()).line("upstream"));
  }

  @Test
  void anAdmittedPreflightIsAnsweredBeforeTheGate() {
    // No credential at all — a browser sends none on a preflight — on a host that refuses every
    // uncredentialed request. The edge answers it, and nothing reaches the upstream.
    activateCi();
    EdgeClient.Answer preflight =
        client()
            .send(
                HttpMethod.OPTIONS,
                "ci.dev.acme.example.com",
                "/ci/api/runs",
                null,
                Map.of(
                    "Origin", "https://projects.dev.acme.example.com",
                    "Access-Control-Request-Method", "PUT",
                    "Access-Control-Request-Headers", "content-type,x-requested-with"));
    assertEquals(204, preflight.status(), preflight.body());
    assertNull(preflight.line("upstream"));
    assertEquals(
        List.of("https://projects.dev.acme.example.com"),
        preflight.headerValues("Access-Control-Allow-Origin"));
    assertEquals(List.of("true"), preflight.headerValues("Access-Control-Allow-Credentials"));
    assertEquals(List.of("PUT"), preflight.headerValues("Access-Control-Allow-Methods"));
    assertEquals(
        List.of("content-type,x-requested-with"),
        preflight.headerValues("Access-Control-Allow-Headers"));
    assertEquals(
        List.of(EdgeCors.PREFLIGHT_MAX_AGE), preflight.headerValues("Access-Control-Max-Age"));
    assertEquals(List.of(), preflight.headerValues("Set-Cookie"));

    // A foreign origin's preflight is not the edge's to answer: it meets the gate as it did.
    EdgeClient.Answer foreign =
        client()
            .send(
                HttpMethod.OPTIONS,
                "ci.dev.acme.example.com",
                "/ci/api/runs",
                null,
                Map.of("Origin", "https://evil.example", "Access-Control-Request-Method", "PUT"));
    assertEquals(401, foreign.status());
    assertEquals(List.of(), foreign.headerValues("Access-Control-Allow-Origin"));

    // The real request after it is still gated: the preflight opened nothing.
    assertEquals(
        401,
        client()
            .get(
                "ci.dev.acme.example.com",
                "/ci/api/runs",
                Map.of("Origin", "https://projects.dev.acme.example.com"))
            .status());
  }

  @Test
  void anUpstreamsOwnCorsHeadersAreReplaced() {
    activateCi();
    EdgeClient.Answer admitted =
        client()
            .get(
                "ci.dev.acme.example.com",
                "/ci/cors-upstream",
                withToken(Map.of("Origin", "https://projects.dev.acme.example.com")));
    assertEquals("mirror-dev", admitted.line("upstream"));
    assertEquals(
        List.of("https://projects.dev.acme.example.com"),
        admitted.headerValues("Access-Control-Allow-Origin"));
    assertEquals(List.of(), admitted.headerValues("Access-Control-Allow-Methods"));
    assertEquals(List.of(), admitted.headerValues("Access-Control-Max-Age"));
    assertTrue(admitted.headerValues("Vary").contains("Accept-Encoding"));
    assertTrue(admitted.headerValues("Vary").contains("Origin"));

    // A foreign origin does not inherit the upstream's wildcard either.
    EdgeClient.Answer foreign =
        client()
            .get(
                "ci.dev.acme.example.com",
                "/ci/cors-upstream",
                withToken(Map.of("Origin", "https://evil.example")));
    assertEquals("mirror-dev", foreign.line("upstream"));
    assertEquals(List.of(), foreign.headerValues("Access-Control-Allow-Origin"));
    assertEquals(List.of(), foreign.headerValues("Access-Control-Allow-Methods"));
  }

  /** The dev token plus some headers of the test's own. */
  private static Map<String, String> withToken(Map<String, String> headers) {
    Map<String, String> merged = new java.util.HashMap<>(token());
    merged.putAll(headers);
    return merged;
  }

  @Test
  void aHostASecondApplicationClaimsIsRefusedAndTheFirstKeepsIt() {
    activateCi();
    seed.deploy(
        new JsonObject()
            .put("applicationName", "qits-impostor")
            .put("environmentName", "dev")
            .put("browserHost", "ci")
            .put(
                "endpoints",
                new io.vertx.core.json.JsonArray()
                    .add(endpoint("/impostor", upstream("qits.test.environment-upstreams.dev")))));

    assertEquals("qits-ci", routes.serviceHost("dev", "ci").application());
    assertEquals(
        "mirror-dev", client().get("ci.dev.acme.example.com", "/", token()).line("upstream"));
  }

  @Test
  void aHostThatIsAnEnvironmentNameIsRefused() {
    // HostEnvironments reads the first label as an application, so `dev.dev.acme.example.com` would
    // be
    // routable and `dev.acme.example.com` would not.
    seed.deploy(
        new JsonObject()
            .put("applicationName", "qits-confused")
            .put("environmentName", "dev")
            .put("browserHost", "prod")
            .put(
                "endpoints",
                new io.vertx.core.json.JsonArray()
                    .add(endpoint("/confused", upstream("qits.test.environment-upstreams.dev")))));

    assertNull(routes.serviceHost("dev", "prod"));
    // The whole table is refused, so its routes are not activated either: `/confused` still falls
    // to
    // whoever owns the environment's catch-all.
    assertEquals("test-environment", routes.resolve("dev", "/confused").application());
  }

  @Test
  void aPublishedHostThatContradictsAConfiguredVhostIsRefused() {
    // `registry` is a configured application vhost with its own gate and anonymous reads. A
    // deployment publishing that name for a DIFFERENT upstream would silently take those over.
    seed.deploy(
        new JsonObject()
            .put("applicationName", "qits-impostor")
            .put("environmentName", "dev")
            .put("browserHost", "registry")
            .put(
                "endpoints",
                new io.vertx.core.json.JsonArray()
                    .add(endpoint("/impostor", upstream("qits.test.environment-upstreams.dev")))));

    assertNull(routes.serviceHost("dev", "registry"));
    assertEquals(
        "registry-dev",
        client().get("registry.dev.acme.example.com", "/v2/", token()).line("upstream"));
  }

  // --- the project tiers -------------------------------------------------------------------------

  @Test
  void aPublishedHostIsReachedUnderAProjectToo() {
    // `workspaces` is a name a DEPLOYMENT publishes rather than a configured vhost, and it reaches
    // the app position like any other: the label is offered as unknown here and the projection
    // claims it.
    activateWorkspaces();
    assertEquals(
        "mirror-dev",
        client()
            .get("workspaces.dev." + PROJECT + ".example.com", "/workspaces/42", token())
            .line("upstream"));
  }

  @Test
  void anUnknownApplicationUnderAProjectIs404AndTheAnswerNamesBoth() {
    // The security property, unchanged by the new grammar: an app-shaped label nobody serves is
    // answered HERE, after the deployment projection has been asked, rather than falling through to
    // a hop that would not authenticate it.
    EdgeClient.Answer answer =
        client().get("nosuchapp.dev." + PROJECT + ".example.com", "/anything");
    assertEquals(404, answer.status());
    assertNull(answer.line("upstream"));
    assertTrue(answer.body().contains("`nosuchapp` is not an application"), answer.body());
    assertTrue(answer.body().contains("the project `" + PROJECT + "`"), answer.body());
  }

  @Test
  void aProjectsOwnNameIsADoorAndSoIsAnEnvironmentInsideIt() {
    // Two doors now, one inside the other: `<project>.<domain>` and `<env>.<project>.<domain>`.
    // Neither serves a path, and each says which door it is.
    activateProjects();
    activateProjects("prod");

    EdgeClient.Answer project = client().get(PROJECT + ".example.com", "/ci/api/runs", token());
    assertEquals(404, project.status());
    assertNull(project.line("upstream"), "a door reaches no upstream");
    assertTrue(project.body().contains("`" + PROJECT + "` project's door"), project.body());
    assertTrue(project.body().contains("<app>." + PROJECT + ".example.com"), project.body());

    EdgeClient.Answer environment =
        client().get("dev." + PROJECT + ".example.com", "/ci/api/runs", token());
    assertEquals(404, environment.status());
    assertNull(environment.line("upstream"));
    assertTrue(
        environment.body().contains("`dev` environment of the `" + PROJECT + "` project"),
        environment.body());
    assertTrue(
        environment.body().contains("<app>.dev." + PROJECT + ".example.com"), environment.body());
  }

  @Test
  void anEnvironmentDoorStillSendsAVisitorToTheProjectsHost() {
    activateProjects();
    EdgeClient.Answer landing = client().get("dev." + PROJECT + ".example.com", "/");
    assertEquals(302, landing.status());
    assertEquals("http://projects.dev.acme.example.com/", landing.headers().get("location"));
  }

  @Test
  void aPublishedServiceNoLongerClaimsAProjectDoor() {
    // DELETED with the tie-breaks. A slug and a published service name used to be the same single
    // label in front of an environment, so the door had to be joined against the deployment
    // projection to see which of the two owned it. Positionally they are two different places —
    // `<project>.<domain>` against `<app>.<env>.<project>.<domain>` — so a deployment publishing a
    // name that happens to equal a slug takes nothing, and the door stays a door.
    seed.deploy(
        new JsonObject()
            .put("applicationName", "qits-acme")
            .put("environmentName", "prod")
            .put("browserHost", PROJECT)
            .put(
                "endpoints",
                new io.vertx.core.json.JsonArray()
                    .add(endpoint("/acme", upstream("qits.test.app-upstreams.mirror.dev")))));

    EdgeClient.Answer answer = client().get(PROJECT + ".example.com", "/acme", token());
    assertEquals(404, answer.status());
    assertNull(answer.line("upstream"), "the published service reaches nothing on the door's name");
    assertTrue(answer.body().contains("project's door"), answer.body());
  }

  @Test
  void aNameWithNoProjectLabelIsAnOrdinaryFourOhFour() {
    // The short-form refusal is GONE. `registry.dev.example.com` used to be a name with its project
    // label left out and got a sentence of its own about the spelling that works; now it is simply
    // a name whose project label says `dev`, and there is no project called that.
    EdgeClient.Answer app = client().get("registry.dev.example.com", "/v2/", token());
    assertEquals(404, app.status());
    assertNull(app.line("upstream"));
    assertTrue(app.body().contains("`dev` is not a project on this platform"), app.body());

    // And a name with a project label but too many in front of it is the other 404.
    EdgeClient.Answer deep = client().get("a.b.c." + PROJECT + ".example.com", "/v2/");
    assertEquals(404, deep.status());
    assertTrue(deep.body().contains("more labels than the grammar has"), deep.body());
  }

  @Test
  void anEnvironmentTheProjectDoesNotHaveIsRefusedByName() {
    EdgeClient.Answer answer =
        client().get("registry.staging." + PROJECT + ".example.com", "/v2/", token());
    assertEquals(404, answer.status());
    assertNull(answer.line("upstream"));
    assertTrue(answer.body().contains("does not have an environment by that name"), answer.body());
  }

  @Test
  void aNavigationOnAProjectsAppTierIsTheNamedEnvironments() {
    // The document an app's own shell reads. Getting the environment wrong here is the failure
    // this tier's authority reading exists to prevent: dev's app rendering prod's services.
    activateCi();
    JsonObject document =
        new JsonObject(
            client().get("registry.dev." + PROJECT + ".example.com", "/main-navigation").body());
    assertEquals("dev", document.getString("environment"));
    assertEquals("http://dev.acme.example.com", document.getString("origin"));
    assertEquals("http://dev.acme.example.com", document.getString("projectOrigin"));
    assertEquals(
        "http://ci.dev.acme.example.com",
        document
            .getJsonObject("slots")
            .getJsonArray("services.details")
            .getJsonObject(0)
            .getString("origin"));
  }

  // --- the apex, which is the domain itself ------------------------------------------------------

  @Test
  void theApexIsReadPositionallyNowAndIsStillADoor() {
    // No rescue by the canonical origin any more: the domain is a stated value, so the apex is the
    // name that IS it. The trailing dot a resolver writes is the same name, as it is everywhere.
    activateProjects("prod");
    for (String apex : List.of("example.com", "example.com.")) {
      // And it no longer redirects. qits-projects is at `projects.<project>.<domain>` like every
      // other application, so composing that name needs a project label — the apex carries none,
      // and this deployment's canonical origin (`https://example.com`) names none either. It used
      // to send a visitor to `http://projects.prod.example.com/`, which is not a name any more.
      EdgeClient.Answer landing = client().get(apex, "/");
      assertEquals(404, landing.status(), apex);
      assertNull(landing.headers().get("location"), apex);
      assertTrue(landing.body().contains("Every application is inside a project"), landing.body());
      assertFalse(landing.body().contains("Start at"), landing.body());
    }
    EdgeClient.Answer elsewhere = client().get("example.com", "/anything");
    assertEquals(404, elsewhere.status());
    assertTrue(elsewhere.body().contains("serves nothing"), elsewhere.body());
  }

  @Test
  void aRetiredMachineNameAnswersFourOhFourAndReachesNoUpstream() {
    // qits-1433: `registry.dev.localhost`, `githost.dev.internal` and the rest were machine names
    // the edge read as `<app>[.<env>].<machine-suffix>`. Nothing dials them any more — every client
    // uses the public names — so they are names outside the domain like any other: a 404, with a
    // credential or without, and no upstream sees the request.
    for (String host :
        List.of(
            "registry.dev.localhost",
            "registry.prod.localhost",
            "mirror.dev.localhost",
            "githost.dev.localhost",
            "githost.dev.internal")) {
      for (EdgeClient.Answer answer :
          List.of(client().get(host, "/v2/", token()), client().get(host, "/v2/"))) {
        assertEquals(404, answer.status(), host);
        assertNull(answer.line("upstream"), host);
        assertTrue(answer.body().contains("is outside `example.com`"), answer.body());
      }
    }
  }

  @Test
  void aNameOutsideTheDomainServesNothingEvenWhenItsLabelIsPublished() {
    // `ci` is a PUBLISHED host; a name outside the domain is still a 404 before the projection is
    // asked. Nothing is routed and no upstream sees it.
    activateCi();
    EdgeClient.Answer answer = client().get("ci.dev.somewhere-else.test", "/", token());
    assertEquals(404, answer.status());
    assertNull(answer.line("upstream"));
    assertTrue(answer.body().contains("is outside `example.com`"), answer.body());
  }

  // --- the project projection's own barrier -----------------------------------------------------

  @Test
  void aNameWhoseReadingNeedsMoreSlugsIs503WhileTheProjectionIsBehind() throws Exception {
    // The window this exists for: the deployment catch-up reaches head first, or qits-events is
    // down for this one consumer, and the slug set is short. A name in the project tiers then reads
    // as a name nobody serves — and a 404 telling a person to use a name that also 404s is a bug
    // report against a platform that is merely reading. A retryable 503 is recoverable.
    projectSans.authoritative(false);
    try {
      // The deepest name there is, one slug short of being served at all.
      EdgeClient.Answer app = client().get("registry.dev.nosuchproject.example.com", "/v2/");
      assertEquals(503, app.status());
      assertEquals("1", app.headers().get("retry-after"));
      assertTrue(app.body().contains("still reading the project log"), app.body());
      assertNull(app.line("upstream"), "nothing reaches an upstream while the answer is unknown");

      // And the project door, whose label would otherwise read as an application nobody routes.
      assertEquals(503, client().get("dev.nosuchproject.example.com", "/").status());
    } finally {
      projectSans.authoritative(true);
    }
  }

  @Test
  void theBarrierTouchesNoNameWhoseAnswerASlugCouldNotChange() throws Exception {
    // A restarted edge whose edge_project rows survived serves normally throughout, which is the
    // whole reason this is a per-name question rather than a second readiness gate: the only names
    // held are the ones that were going to 404 anyway.
    activateCi();
    activateProjects("prod");
    projectSans.authoritative(false);
    try {
      // A configured vhost, a published host, the apex, an environment's door, and the four-label
      // tier of a project this projection DOES know.
      assertEquals(
          "mirror-dev", client().get("ci.dev.acme.example.com", "/", token()).line("upstream"));
      // The apex is answered rather than held — a 404 rather than the 503 the barrier writes,
      // because no slug this projection has yet to read could turn a name inside no project into
      // an application address.
      assertEquals(404, client().get("example.com", "/").status());
      assertEquals(404, client().get("dev.acme.example.com", "/anything").status());
      assertEquals(
          "registry-dev",
          client()
              .get("registry.dev." + PROJECT + ".example.com", "/v2/", token())
              .line("upstream"));
    } finally {
      projectSans.authoritative(true);
    }
  }

  @Test
  void onceTheProjectionIsAuthoritativeTheSameNamesAre404Again() {
    // The barrier is a window, not a state: with the log read to its head an unknown slug is a slug
    // that does not exist, and the answer is the 404 that names the spelling which works.
    EdgeClient.Answer app = client().get("registry.dev.nosuchproject.example.com", "/v2/");
    assertEquals(404, app.status());
    assertTrue(
        app.body().contains("`nosuchproject` is not a project on this platform"), app.body());

    EdgeClient.Answer door = client().get("dev.nosuchproject.example.com", "/");
    assertEquals(404, door.status());
    assertTrue(
        door.body().contains("`nosuchproject` is not a project on this platform"), door.body());
  }

  @Test
  void theDefaultEnvironmentsNavigationCarriesItsLabelLikeEveryOther() {
    // FLIPPED. These origins used to be written in the SHORT form — `http://example.com` and
    // `http://ci.example.com` — because the default environment's door was the apex. They are the
    // names the shell links to, so they have to be names that still resolve, and only the labelled
    // spelling does. The APEX is not asked here: it is inside no project, so it composes no
    // application name at all — see `theApexIsReadPositionallyNowAndIsStillADoor`.
    activateCi("prod");
    for (String requested : List.of("prod.acme.example.com", "ci.prod.acme.example.com")) {
      JsonObject document = new JsonObject(client().get(requested, "/main-navigation").body());
      assertEquals("prod", document.getString("environment"), requested);
      assertEquals("http://prod.acme.example.com", document.getString("origin"), requested);
      assertEquals(
          "http://ci.prod.acme.example.com",
          document
              .getJsonObject("slots")
              .getJsonArray("services.details")
              .getJsonObject(0)
              .getString("origin"),
          requested);
    }
  }

  @Test
  void theEnvironmentsOwnNameIsADoorOnceTheProjectsHostIsKnown() {
    activateProjects();
    EdgeClient.Answer answer = client().get("dev.acme.example.com", "/");
    assertEquals(302, answer.status());
    assertEquals("http://projects.dev.acme.example.com/", answer.headers().get("location"));
  }

  @Test
  void anEnvLessProjectsDoorAndAppsComposeWithNoEnvironmentLabel() {
    // The other composition, and it is the same rule rather than a second one: `gizmo` has no
    // environment tier, so its own name IS the innermost door and its applications are one label in
    // front of it. Its apps are served in the DEFAULT environment, which is where the projection
    // for them has to be published.
    activateProjects("prod");
    activateCi("prod");

    EdgeClient.Answer landing = client().get(FLAT_PROJECT + ".example.com", "/");
    assertEquals(302, landing.status());
    assertEquals("http://projects.gizmo.example.com/", landing.headers().get("location"));

    JsonObject document =
        new JsonObject(
            client().get("ci." + FLAT_PROJECT + ".example.com", "/main-navigation").body());
    assertEquals("prod", document.getString("environment"));
    assertEquals("http://gizmo.example.com", document.getString("origin"));
    assertEquals("http://gizmo.example.com", document.getString("projectOrigin"));
    assertEquals(
        "http://ci.gizmo.example.com",
        document
            .getJsonObject("slots")
            .getJsonArray("services.details")
            .getJsonObject(0)
            .getString("origin"));
  }

  @Test
  void anEnvSupportingProjectsDoorComposesItsDefaultEnvironment() {
    // A project's door states no environment, so the name it sends a visitor to carries the default
    // one — the same environment the router itself reads that name as, which is what keeps the
    // redirect on a name this edge serves.
    activateProjects("prod");
    EdgeClient.Answer landing = client().get(PROJECT + ".example.com", "/");
    assertEquals(302, landing.status());
    assertEquals("http://projects.prod.acme.example.com/", landing.headers().get("location"));
  }

  @Test
  void theDoorHasNowhereToSendAnybodyUntilProjectsPublishesAHost() throws Exception {
    clearProjection();
    assertEquals(404, client().get("dev.acme.example.com", "/").status());
  }

  @Test
  void theImmutableDefaultLeavesTheEdgeOnlyOnAHashedName() {
    activateArtifacts();

    // The SPA document: the mutable pointer naming the hashed bundles, and so the one file whose
    // staleness decides which version of an application a returning browser runs. qits-gateway
    // rewrote this and the edge did not when it replaced it, which is how a green, deployed release
    // stayed invisible for a day.
    assertEquals(
        "no-cache",
        client()
            .get("registry.dev.acme.example.com", "/artifacts/spa/", token())
            .headers()
            .get("cache-control"));
    // A content-hashed name is the one place immutable is correct — a new build names a new file —
    // so this one keeps the day it was given.
    assertEquals(
        "public, immutable, max-age=86400",
        client()
            .get("registry.dev.acme.example.com", "/artifacts/spa/main-4RS6EA47.js", token())
            .headers()
            .get("cache-control"));
    // Unhashed and not the document either: a favicon replaced in place would otherwise outlive its
    // own build by a day.
    assertEquals(
        "no-cache",
        client()
            .get("registry.dev.acme.example.com", "/artifacts/spa/favicon.ico", token())
            .headers()
            .get("cache-control"));
  }

  @Test
  void aCacheHeaderTheUpstreamChoseIsNotOverruled() {
    activateArtifacts();

    // Only the untouched Quarkus default is known to be nobody's decision, and it is the only value
    // the edge may correct. no-store is somebody's decision, and the blanket rewrite this test
    // forbids would WEAKEN it.
    assertEquals(
        "no-store",
        client()
            .get("registry.dev.acme.example.com", "/artifacts/spa/private", token())
            .headers()
            .get("cache-control"));
  }

  /**
   * The project set this suite routes with, from a clean table.
   *
   * <p>The rows are cleared first rather than merely added to: {@code ProjectSansTest} shares this
   * JVM and drives the same projection with hand-dated frames, and this projection is
   * last-writer-wins by {@code (occurredAt, eventId)} — so a row it left dated in the future would
   * make an ordinary create here a no-op, and the slug would simply not be there.
   */
  // --- the reserved `landing` label, which is the project's own name ----------------------------

  /**
   * A deployable that wrote {@code host: landing}. The role of the repository behind it is no part
   * of this — {@code -landing-app} and {@code -landing-service} publish the same label and claim
   * the door identically — so the application name here is deliberately arbitrary.
   */
  private void activateLanding(String application, String environment, String path, String app) {
    seed.deploy(
        new JsonObject()
            .put("applicationName", application)
            .put("environmentName", environment)
            .put("browserHost", HostEnvironments.LANDING)
            .put(
                "endpoints",
                new io.vertx.core.json.JsonArray()
                    .add(
                        endpoint(
                            path,
                            upstream("qits.test.app-upstreams." + app + "." + environment)))));
  }

  @Test
  void anEnvLessProjectsOwnNameIsServedByItsLandingDeployment() {
    // The door becomes the front of the product. `gizmo` is deployed once, so its landing IS
    // `gizmo.example.com` — an ordinary deployment, reached through the ordinary gate.
    activateProjects("prod");
    EdgeClient.Answer door = client().get(FLAT_PROJECT + ".example.com", "/");
    assertEquals(302, door.status(), "no landing publisher yet, so the built-in door stays");
    assertEquals("http://projects.gizmo.example.com/", door.headers().get("location"));

    activateLanding("gizmo-landing-app", "prod", "/landing", "mirror");

    EdgeClient.Answer served = client().get(FLAT_PROJECT + ".example.com", "/", token());
    assertEquals(200, served.status(), served.body());
    assertEquals("mirror-prod", served.line("upstream"));
  }

  @Test
  void anEnvSupportingProjectsEnvironmentDoorIsServedByThatEnvironmentsLanding() {
    // One landing per tier, like every other application. `dev` has none here, so it is still the
    // door it was — which is the whole of the join: published, or not.
    activateProjects("dev");
    activateLanding("acme-landing-service", "prod", "/landing", "mirror");

    EdgeClient.Answer served = client().get("prod." + PROJECT + ".example.com", "/", token());
    assertEquals(200, served.status(), served.body());
    assertEquals("mirror-prod", served.line("upstream"));

    EdgeClient.Answer door = client().get("dev." + PROJECT + ".example.com", "/");
    assertEquals(302, door.status());
    assertEquals("http://projects.dev.acme.example.com/", door.headers().get("location"));
  }

  /**
   * The assertion that keeps the platform's own front page behind the login wall.
   *
   * <p>The owner's ruling is that the landing page is not public: a visitor with no credential is
   * sent to sign in, on the project's root name exactly as on every other vhost. The whole of that
   * ruling hangs on one fact — {@code landing} is absent from {@code
   * qits.edge.auth.anonymous-read-apps} — and nothing else stands between the front page and an
   * anonymous read. {@code EdgeRouter.target()} rebuilds the landing Route with the projection's
   * label as the app, so by the time {@code EdgeAuth.anonymousRead} runs the page IS an app route
   * with {@code app() == "landing"}, one list entry away from being open.
   *
   * <p>It is the counterpart of the {@code brochure} tests below, which prove the same mechanism
   * the other way round: a projected label that IS named is read anonymously. Those say nothing
   * about this page, deliberately — the fixture is not called {@code landing} — so this is the test
   * that fails if anyone ever names it.
   *
   * <p>The bearer half of each pair is not decoration: a name that 404s or redirects would refuse
   * an anonymous read too, and would refuse it for the wrong reason. The 200 is what proves the
   * root name really is served by the landing deployment, so the 401 beside it is a gate rather
   * than an absence.
   */
  @Test
  void theLandingPageRefusesAnAnonymousReadOnEitherProjectShape() {
    activateProjects("prod");
    activateLanding("acme-landing-service", "prod", "/landing", "mirror");
    activateLanding("gizmo-landing-app", "prod", "/landing", "mirror");

    for (String host : List.of("prod." + PROJECT + ".example.com", FLAT_PROJECT + ".example.com")) {
      EdgeClient.Answer served = client().get(host, "/", token());
      assertEquals(200, served.status(), host + ": " + served.body());
      assertEquals("mirror-prod", served.line("upstream"), host + " must be the landing page");

      EdgeClient.Answer anonymous = client().get(host, "/");
      assertEquals(401, anonymous.status(), host + " must not serve an anonymous GET");
      assertNull(anonymous.line("upstream"), host + " must not have reached the landing page");

      EdgeClient.Answer head = client().send(HttpMethod.HEAD, host, "/", null, Map.of());
      assertEquals(401, head.status(), host + " must not serve an anonymous HEAD");
      assertNull(head.line("upstream"), host + " must not have reached the landing page");
    }
  }

  @Test
  void anEnvSupportingProjectsBareNameRedirectsToTheDefaultEnvironmentsLanding() {
    // It cannot serve one itself — there are as many landings as environments and this name states
    // none — so it stays a door, and the door now opens onto the project's own front page rather
    // than onto the platform's.
    activateProjects("prod");
    activateLanding("acme-landing-app", "prod", "/landing", "mirror");

    EdgeClient.Answer answer = client().get(PROJECT + ".example.com", "/");
    assertEquals(302, answer.status());
    assertEquals("http://prod.acme.example.com/", answer.headers().get("location"));
    assertNull(answer.line("upstream"), "the bare name serves nothing itself");

    // Every other path on it is the 404 a door has always been, and it names where the page is.
    EdgeClient.Answer deep = client().get(PROJECT + ".example.com", "/deep/link");
    assertEquals(404, deep.status());
    assertTrue(deep.body().contains("prod.acme.example.com"), deep.body());
  }

  @Test
  void aProjectWithNoLandingPublisherStillGetsTheBuiltInRedirect() {
    // Nothing published it, so nothing changed: the door sends a visitor to the projects host.
    activateProjects("prod");
    activateProjects("dev");
    assertEquals(
        "http://projects.prod.acme.example.com/",
        client().get(PROJECT + ".example.com", "/").headers().get("location"));
    assertEquals(
        "http://projects.dev.acme.example.com/",
        client().get("dev." + PROJECT + ".example.com", "/").headers().get("location"));
    assertEquals(
        "http://projects.gizmo.example.com/",
        client().get(FLAT_PROJECT + ".example.com", "/").headers().get("location"));
  }

  @Test
  void theReservedLabelIsNotASecondAddressForTheDoorItServes() {
    // One thing, one address. `landing.…` would be a second origin and a second cookie scope for a
    // page that already has one, so it is a 404 that reaches nothing — published or not.
    activateLanding("gizmo-landing-app", "prod", "/landing", "mirror");
    for (String host :
        List.of(
            "landing." + FLAT_PROJECT + ".example.com",
            "landing.prod." + PROJECT + ".example.com",
            "landing.dev." + PROJECT + ".example.com")) {
      EdgeClient.Answer answer = client().get(host, "/");
      assertEquals(404, answer.status(), host);
      assertNull(answer.line("upstream"), host + " must reach no upstream");
      assertTrue(answer.body().contains("is not an application name"), answer.body());
    }
  }

  @Test
  void twoLandingPublishersRefuseTheTableAndTheFirstKeepsTheDoor() {
    // A whole table has no "newest frame" to let win, so the reserved label is held to the rule
    // every host is: two claimants in one environment refuse the table, the held one stays served,
    // and the reason names both.
    activateLanding("gizmo-landing-app", "prod", "/first-landing", "mirror");
    RouteTables.Outcome outcome =
        seed.deploy(
            new JsonObject()
                .put("applicationName", "gizmo-landing-service")
                .put("environmentName", "prod")
                .put("browserHost", HostEnvironments.LANDING)
                .put(
                    "endpoints",
                    new io.vertx.core.json.JsonArray()
                        .add(
                            endpoint(
                                "/second-landing",
                                upstream("qits.edge.apps.registry.hosts.prod")))));

    assertTrue(outcome instanceof RouteTables.Invalid, outcome.toString());
    String reason = ((RouteTables.Invalid) outcome).reason();
    assertTrue(reason.contains("gizmo-landing-app"), reason);
    assertTrue(reason.contains("gizmo-landing-service"), reason);
    assertTrue(reason.contains("prod"), reason);
    EdgeClient.Answer served = client().get(FLAT_PROJECT + ".example.com", "/", token());
    assertEquals(200, served.status(), served.body());
    assertEquals("mirror-prod", served.line("upstream"));
    assertNull(routes.primaryPath("prod", "gizmo-landing-service"));
  }

  private void publishProject() throws java.sql.SQLException {
    try (java.sql.Connection connection = edgeDataSource.getConnection();
        java.sql.PreparedStatement delete =
            connection.prepareStatement("delete from edge_project")) {
      delete.executeUpdate();
    }
    projects.load(null);
    projects.apply(
        PROJECT, "p-routing", true, true, java.util.UUID.randomUUID().toString(), Instant.now());
    // A project with no tier of environments under it, which is the OTHER composition: its
    // applications are `<app>.<project>.<domain>`, served in the default environment, and its own
    // door is the innermost one there is. The platform's own project becomes one of these.
    projects.apply(
        FLAT_PROJECT,
        "p-routing-flat",
        true,
        false,
        java.util.UUID.randomUUID().toString(),
        Instant.now());
  }

  /** An empty route table, served: nothing routed but the platform labels. */
  private void clearProjection() {
    seed.clear();
  }

  private static Upstream upstream(String property) {
    return Upstream.parse(ConfigProvider.getConfig().getValue(property, String.class), 8080);
  }

  @Test
  void aRetiredApplicationLeavesTheTableAndItsRoutesWithIt() {
    activateArtifacts();
    activateCi();
    assertNotNull(routes.resolve("dev", "qits-artifacts", "/artifacts/api/files"));
    seed.deploy(
        new JsonObject()
            .put("applicationName", "qits-artifacts")
            .put("environmentName", "dev")
            .put("endpoints", new io.vertx.core.json.JsonArray()));

    // /artifacts is nobody's route any more.
    assertNull(routes.resolve("dev", "qits-artifacts", "/artifacts/api/files"));
  }

  // --- the exposure class (epic qits-1976); this suite is a PUBLIC edge
  // ----------------------------

  @Test
  void aPublicEdgeServesPublicAndBothDeployments() {
    Instant at = Instant.now();
    seed.deploy(exposedCi("public", at));
    assertNotNull(routes.serviceHost("dev", "ci"));
    seed.deploy(exposedCi("both", at.plusMillis(1)));
    assertNotNull(routes.serviceHost("dev", "ci"));
    assertNotNull(routes.resolve("dev", "qits-ci", "/ci/api/runs"));
  }

  @Test
  void aPrivateFrameWithdrawsTheVhostFromThePublicEdge() {
    Instant at = Instant.now();
    seed.deploy(exposedCi(null, at));
    assertNotNull(routes.serviceHost("dev", "ci"));

    // Out of the public table and into its withdrawn list, so the host, the route and the
    // navigation row all go. The name is not a 404, though: the withdrawal says the private edge
    // serves it, so a request that reached this edge was misdirected by its resolver — 421, and the
    // HTTP/1.1 connection closed so the client's next request resolves again (qits-2223).
    seed.deploy(exposedCi("private", at.plusMillis(1)));
    assertNull(routes.serviceHost("dev", "ci"));
    assertNull(routes.resolve("dev", "qits-ci", "/ci/api/runs"));
    assertTrue(
        routes.navigation("dev").stream()
            .noneMatch(entry -> "qits-ci".equals(entry.application())));
    EdgeClient.Answer misdirected =
        client().get("ci.dev.acme.example.com", "/ci/api/runs", token());
    assertEquals(421, misdirected.status(), misdirected.body());
    assertEquals(List.of("close"), misdirected.headerValues("connection"));
    assertTrue(misdirected.body().startsWith("`ci` is served only by the private edge"));
    assertTrue(misdirected.body().contains("100.96.0.1"), misdirected.body());
    assertTrue(misdirected.headerValues("retry-after").isEmpty(), "nothing to wait for here");
    // A label nobody publishes or withdraws is still the ordinary 404.
    assertEquals(404, client().get("nosuch.dev.acme.example.com", "/", token()).status());

    // Back inside the class, the next table restores it.
    seed.deploy(exposedCi("both", at.plusMillis(2)));
    assertNotNull(routes.serviceHost("dev", "ci"));
  }

  @Test
  void aWithdrawnAppIsNamedToASignedInCallerAndRoutesNothing() {
    activateArtifacts();
    Instant at = Instant.now();
    seed.deploy(exposedCi(null, at));
    seed.deploy(exposedCi("private", at.plusMillis(1)));

    // The withdrawal keeps the host for the navigation and nothing that routes: no vhost, no path,
    // no applicationHosts row — the name is misdirected (421, qits-2223) even with a good token.
    assertEquals(
        new EdgeRoutes.Withdrawn("ci", "private"), routes.withdrawnHosts("dev").get("qits-ci"));
    assertNull(routes.serviceHost("dev", "ci"));
    assertNull(routes.applicationHost("dev", "qits-ci"));
    assertFalse(routes.applicationHosts("dev").containsKey("qits-ci"));
    assertNull(routes.resolve("dev", "qits-ci", "/ci/api/runs"));
    assertEquals(421, client().get("ci.dev.acme.example.com", "/ci/api/runs", token()).status());

    // Anonymous, and with a token the gate refuses: exactly today's document, no private name.
    for (Map<String, String> credential :
        List.of(Map.<String, String>of(), aliasToken("dev"), bearer("not-a-jwt"))) {
      EdgeClient.Answer answer =
          client().get("dev.acme.example.com", "/main-navigation", credential);
      assertEquals(200, answer.status(), credential.toString());
      JsonObject applications = new JsonObject(answer.body()).getJsonObject("applications");
      assertFalse(applications.containsKey("qits-ci"), answer.body());
      assertTrue(
          applications.getJsonObject("qits-artifacts").fieldNames().stream()
              .noneMatch("exposure"::equals),
          answer.body());
    }

    // A token the gate accepts: the private app's origin, marked private, and nothing else of it.
    JsonObject document =
        new JsonObject(client().get("dev.acme.example.com", "/main-navigation", token()).body());
    JsonObject applications = document.getJsonObject("applications");
    assertEquals(
        new JsonObject().put("origin", "http://ci.dev.acme.example.com").put("exposure", "private"),
        applications.getJsonObject("qits-ci"));
    assertFalse(
        applications.getJsonObject("qits-artifacts").containsKey("exposure"),
        "a public entry carries no exposure key");
    assertTrue(
        document.getJsonObject("slots").stream()
            .flatMap(slot -> ((io.vertx.core.json.JsonArray) slot.getValue()).stream())
            .noneMatch(entry -> "qits-ci".equals(((JsonObject) entry).getString("app"))),
        "a withdrawn app fills no slot");

    // Back inside the class: routed again, and an ordinary entry with no exposure.
    seed.deploy(exposedCi("public", at.plusMillis(2)));
    assertNull(routes.withdrawnHosts("dev").get("qits-ci"));
    assertNotNull(routes.serviceHost("dev", "ci"));
    JsonObject restored =
        new JsonObject(client().get("dev.acme.example.com", "/main-navigation", token()).body())
            .getJsonObject("applications")
            .getJsonObject("qits-ci");
    assertEquals("http://ci.dev.acme.example.com", restored.getString("origin"));
    assertFalse(restored.containsKey("exposure"), restored.encode());
  }

  @Test
  void anUnknownExposureChangesNothing() {
    Instant at = Instant.now();
    seed.deploy(exposedCi("public", at));
    seed.deploy(exposedCi("internal", at.plusMillis(1)));
    assertNotNull(routes.serviceHost("dev", "ci"), "a typo neither publishes nor withdraws");
  }

  /** ci's flipped deployment with an exposure — absent when null. */
  private JsonObject exposedCi(String exposure, Instant at) {
    JsonObject payload =
        new JsonObject()
            .put("applicationName", "qits-ci")
            .put("environmentName", "dev")
            .put("browserHost", "ci")
            .put(
                "endpoints",
                new io.vertx.core.json.JsonArray()
                    .add(endpoint("/ci", upstream("qits.test.app-upstreams.mirror.dev"))))
            .put(
                "navigation",
                new io.vertx.core.json.JsonArray().add(placement("services.details", "CI", 2)));
    if (exposure != null) {
      payload.put("exposure", exposure);
    }
    return payload;
  }

  /**
   * qits-artifacts as it is published after the flip: a primary route, a wire route, the public
   * name {@code registry} — which is also its CONFIGURED vhost, so the two have to agree — and one
   * placement.
   */
  private void activateArtifacts() {
    Upstream upstream = upstream("qits.edge.apps.registry.hosts.dev");
    seed.deploy(
        new JsonObject()
            .put("applicationName", "qits-artifacts")
            .put("environmentName", "dev")
            .put("browserHost", "registry")
            .put(
                "endpoints",
                new io.vertx.core.json.JsonArray()
                    .add(endpoint("/artifacts", upstream))
                    .add(endpoint("/v2", upstream)))
            .put(
                "navigation",
                new io.vertx.core.json.JsonArray()
                    .add(placement("services.details", "Artifacts", 3))));
  }

  /** A second flipped application, on a stub that names itself differently. */
  private void activateCi() {
    activateCi("dev");
  }

  private void activateCi(String environment) {
    seed.deploy(
        new JsonObject()
            .put("applicationName", "qits-ci")
            .put("environmentName", environment)
            .put("browserHost", "ci")
            .put("apiDocsPath", "/ci/q/swagger-ui")
            .put(
                "endpoints",
                new io.vertx.core.json.JsonArray()
                    .add(
                        endpoint("/ci", upstream("qits.test.app-upstreams.mirror." + environment))))
            .put(
                "navigation",
                new io.vertx.core.json.JsonArray().add(placement("services.details", "CI", 2))));
  }

  /**
   * One application, one container, TWO rows under the project node — the workspace list at its
   * root and a second view under a subpath.
   */
  private void activateWorkspaces() {
    seed.deploy(
        new JsonObject()
            .put("applicationName", "qits-workspaces")
            .put("environmentName", "dev")
            .put("browserHost", "workspaces")
            .put(
                "endpoints",
                new io.vertx.core.json.JsonArray()
                    .add(endpoint("/workspaces", upstream("qits.test.app-upstreams.mirror.dev"))))
            .put(
                "navigation",
                new io.vertx.core.json.JsonArray()
                    .add(placement("project.detail", "Workspaces", 1))
                    .add(placement("project.detail", "Runners", 2).put("subpath", "runners"))));
  }

  /** The landing service: what makes the environment's own name a door rather than a page. */
  private void activateProjects() {
    activateProjects("dev");
  }

  private void activateProjects(String environment) {
    seed.deploy(
        new JsonObject()
            .put("applicationName", "qits-projects")
            .put("environmentName", environment)
            .put("browserHost", "projects")
            .put(
                "endpoints",
                new io.vertx.core.json.JsonArray()
                    .add(endpoint("/projects", upstream("qits.edge.apps.registry.hosts.dev"))))
            .put(
                "navigation",
                new io.vertx.core.json.JsonArray().add(placement("system", "Overview", 1))));
  }

  /**
   * An application the edge knows ONLY from the projection: {@code brochure} has no {@code
   * qits.edge.apps.brochure} entry of any kind, so HostEnvironments can only answer its name as an
   * unknown app, and it becomes an app route solely because {@code EdgeRouter.target()} rebuilds
   * the Route with the projection's label as the app. That is the second of the two ways a label
   * reaches the anonymous-read gate, and the one a public SSR landing page depends on.
   *
   * <p>Its endpoint points at the REGISTRY stub deliberately, not mirror's. Every other
   * exempted-read assertion in this file reads {@code mirror-dev}, so answering {@code
   * registry-dev} here is what makes a 200 proof that the request reached the PROJECTION's own
   * upstream rather than mirror's configured route happening to answer.
   */
  private void activateBrochure() {
    seed.deploy(
        new JsonObject()
            .put("applicationName", "qits-brochure")
            .put("environmentName", "dev")
            .put("browserHost", "brochure")
            .put(
                "endpoints",
                new io.vertx.core.json.JsonArray()
                    .add(endpoint("/brochure", upstream("qits.edge.apps.registry.hosts.dev")))));
  }

  private static JsonObject endpoint(String path, Upstream upstream) {
    return new JsonObject()
        .put("path", path)
        .put("upstreamHost", upstream.host())
        .put("upstreamPort", upstream.port());
  }

  private static JsonObject placement(String slot, String label, int position) {
    return new JsonObject().put("slot", slot).put("label", label).put("position", position);
  }

  @Test
  void theApexAndAnUnknownHostReachTheDefaultEnvironment() {
    // Nothing is served on any of them, so what the resolution answers is read off the one document
    // the door still writes.
    for (String host : List.of("example.com", "staging.example.com", "127.0.0.1")) {
      assertEquals(
          "prod",
          new JsonObject(client().get(host, "/main-navigation").body()).getString("environment"),
          host);
    }
  }

  // --- verbatim forwarding, on the name that serves it ------------------------------------------

  @Test
  void thePathAndQueryReachTheUpstreamUnchanged() {
    // No path knowledge means no path rewriting: a service's own route table is written against the
    // paths a client typed, and a prefix stripped here would break every one of them.
    activateCi();
    assertEquals(
        "/deep/path/here?x=1&y=2",
        client().get("ci.dev.acme.example.com", "/deep/path/here?x=1&y=2", token()).line("uri"));
  }

  @Test
  void aPostBodyReachesTheUpstream() {
    activateCi();
    EdgeClient.Answer answer =
        client()
            .send(HttpMethod.POST, "ci.dev.acme.example.com", "/api/thing", "hello edge", token());
    assertEquals("POST", answer.line("method"));
    assertEquals("hello edge", answer.line("body"));
    assertEquals("10", answer.line("body-bytes"));
  }

  // --- request bodies and their framing ---------------------------------------------------

  /** A body streamed in parts, as git streams a push larger than its {@code http.postBuffer}. */
  private static final List<String> STREAMED_PARTS = List.of("hello ", "streamed ", "edge");

  @Test
  void anHttp2BodyWithoutALengthReachesTheUpstream() {
    // The defect: git pushes over 1 MB on HTTP/2 with no Content-Length, and the edge's HTTP/1.1
    // request to qits-githost carried neither a length nor chunked framing, so githost read an
    // empty body and answered 500 (measured 2026-10-03).
    activateCi();
    EdgeClient.Answer answer =
        http2()
            .sendStreamed(
                HttpMethod.POST, "ci.dev.acme.example.com", "/api/thing", STREAMED_PARTS, token());
    assertEquals(HttpVersion.HTTP_2, answer.version());
    assertEquals(200, answer.status(), answer.body());
    assertEquals("hello streamed edge", answer.line("body"), answer.body());
    assertEquals("19", answer.line("body-bytes"));
  }

  @Test
  void aLargeHttp2BodyWithoutALengthReachesTheUpstreamWhole() {
    // Larger than git's 1 MB postBuffer, in many parts, so it crosses many DATA frames and the
    // outbound write queue, not one buffer.
    activateCi();
    String part = "x".repeat(64 * 1024);
    List<String> parts = java.util.Collections.nCopies(24, part);
    EdgeClient.Answer answer =
        http2()
            .sendStreamed(HttpMethod.POST, "ci.dev.acme.example.com", "/api/thing", parts, token());
    assertEquals(200, answer.status());
    assertEquals(String.valueOf(24 * 64 * 1024), answer.line("body-bytes"));
  }

  @Test
  void anHttp2BodyWithALengthReachesTheUpstream() {
    activateCi();
    EdgeClient.Answer answer =
        http2()
            .send(HttpMethod.POST, "ci.dev.acme.example.com", "/api/thing", "hello edge", token());
    assertEquals(HttpVersion.HTTP_2, answer.version());
    assertEquals("hello edge", answer.line("body"));
    assertEquals("10", answer.upstreamHeader("Content-Length"));
  }

  @Test
  void anHttp11ChunkedBodyReachesTheUpstream() {
    activateCi();
    EdgeClient.Answer answer =
        client()
            .sendStreamed(
                HttpMethod.POST, "ci.dev.acme.example.com", "/api/thing", STREAMED_PARTS, token());
    assertEquals("hello streamed edge", answer.line("body"), answer.body());
    assertEquals("chunked", answer.upstreamHeader("Transfer-Encoding"));
  }

  @Test
  void anHttp2GetStaysWithoutABody() {
    // A GET carries no body, so the edge must not frame one: no chunked, no length.
    activateCi();
    EdgeClient.Answer answer = http2().get("ci.dev.acme.example.com", "/api/thing", token());
    assertEquals(200, answer.status());
    assertEquals("0", answer.line("body-bytes"));
    assertNull(answer.upstreamHeader("Transfer-Encoding"), answer.body());
  }

  @Test
  void everyMethodPassesThrough() {
    activateCi();
    for (HttpMethod method :
        new HttpMethod[] {HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH}) {
      assertEquals(
          method.name(),
          client().send(method, "ci.dev.acme.example.com", "/thing", "x", token()).line("method"),
          "the edge must not have an opinion about " + method);
    }
  }

  @Test
  void headersReachTheUpstreamUntouched() {
    // Beyond the browser cookie and the reserved prefix, the edge strips NOTHING: an unrelated
    // cookie, a custom header and the credential itself all arrive as they were sent.
    activateCi();
    Map<String, String> headers = new java.util.HashMap<>(token());
    headers.put("Cookie", "q_session=abc");
    headers.put("X-Custom", "kept");
    EdgeClient.Answer answer =
        client().send(HttpMethod.GET, "ci.dev.acme.example.com", "/thing", null, headers);

    assertEquals(headers.get("Authorization"), answer.upstreamHeader("Authorization"));
    assertEquals("q_session=abc", answer.upstreamHeader("Cookie"));
    assertEquals("kept", answer.upstreamHeader("X-Custom"));
  }

  @Test
  void theOriginalHostReachesTheUpstream() {
    // Load-bearing: every redirect, cookie domain and absolute URL a service builds comes from this
    // header. Rewriting it to the upstream's own name would break all three at once and leave
    // nothing in a log to say so.
    activateCi();
    String seen = client().get("ci.dev.acme.example.com", "/thing", token()).upstreamHeader("Host");
    assertTrue(
        seen != null && seen.startsWith("ci.dev.acme.example.com"),
        "the upstream must see the name the client asked for, but saw: " + seen);
  }

  @Test
  void aResponseHeaderReachesTheClientUnchanged() {
    activateCi();
    assertEquals(
        "mirror-dev",
        client().get("ci.dev.acme.example.com", "/thing", token()).headers().get("x-upstream"));
  }

  // --- the forwarded headers -----------------------------------------------------------------

  @Test
  void theEdgeDescribesTheOriginalClient() {
    activateCi();
    EdgeClient.Answer answer = client().get("ci.dev.acme.example.com", "/thing", token());
    assertEquals("127.0.0.1", answer.upstreamHeader("X-Forwarded-For"));
    assertEquals("http", answer.upstreamHeader("X-Forwarded-Proto"));
    assertTrue(answer.upstreamHeader("X-Forwarded-Host").startsWith("ci.dev.acme.example.com"));
  }

  @Test
  void anExistingForwardedHeaderIsKept() {
    // The edge is not always the outermost hop: a TLS terminator in front of it is the only thing
    // that can tell the truth about `https`, and overwriting would replace a true value with a
    // false one. Nothing downstream may make a trust decision on these three, and nothing does.
    activateCi();
    Map<String, String> headers = new java.util.HashMap<>(token());
    headers.put("X-Forwarded-For", "203.0.113.7");
    headers.put("X-Forwarded-Proto", "https");
    headers.put("X-Forwarded-Host", "edge.example.com");
    EdgeClient.Answer answer =
        client().send(HttpMethod.GET, "ci.dev.acme.example.com", "/thing", null, headers);

    assertEquals("203.0.113.7", answer.upstreamHeader("X-Forwarded-For"));
    assertEquals("https", answer.upstreamHeader("X-Forwarded-Proto"));
    assertEquals("edge.example.com", answer.upstreamHeader("X-Forwarded-Host"));
  }

  // --- streaming -----------------------------------------------------------------------------

  @Test
  void aChunkedResponseIsNotBuffered() {
    // The stub writes two chunks with a gap between them. A proxy that buffered would deliver both
    // at the end, so the FIRST chunk's arrival time is the assertion — the body alone would pass
    // either way. SSE channels and `git clone` are what this protects.
    activateCi();
    EdgeClient.Streamed streamed = client().stream("ci.dev.acme.example.com", "/stream", token());

    assertEquals("chunk-1\nchunk-2\n", streamed.body());
    assertTrue(
        streamed.firstChunkMillis() < StubGateways.STREAM_GAP_MILLIS,
        "the first chunk arrived after "
            + streamed.firstChunkMillis()
            + "ms, which is not before the upstream sent the second at "
            + StubGateways.STREAM_GAP_MILLIS
            + "ms — the response was buffered");
  }

  // --- hop-by-hop headers ------------------------------------------------------------------

  @Test
  void anUpstreamsHopByHopHeadersNeverReachTheClient() {
    // The apex's Express upstream answered with Connection and Keep-Alive, which the edge copied
    // through; on HTTP/1.1 that is merely wrong, and the ordinary headers must still arrive.
    activateCi();
    EdgeClient.Answer answer = client().get("ci.dev.acme.example.com", "/ci/hop-by-hop", token());
    assertEquals(302, answer.status(), answer.raw().toString());
    assertEquals(List.of("/projects"), answer.headerValues("Location"));
    assertEquals(List.of("Express"), answer.headerValues("X-Powered-By"));
    assertEquals(List.of(), answer.headerValues("Keep-Alive"), answer.raw().toString());
    assertEquals(List.of(), answer.headerValues("Proxy-Connection"), answer.raw().toString());
    assertEquals(List.of(), answer.headerValues("X-Custom-Hop"), answer.raw().toString());
    // Vert.x may frame its own answer with a Connection header of its own; what it must not say is
    // anything the upstream said about ITS connection.
    for (String connection : answer.headerValues("Connection")) {
      assertFalse(
          connection.toLowerCase(java.util.Locale.ROOT).contains("x-custom-hop"),
          answer.raw().toString());
    }
  }

  @Test
  void anHttp2ClientGetsTheAnswerAnUpstreamWithHopByHopHeadersGave() {
    // The defect itself: a connection-specific header on an h2 stream is malformed (RFC 9113
    // §8.2.2) and Netty resets the stream, so the request never completed at all.
    activateCi();
    EdgeClient.Answer answer = http2().get("ci.dev.acme.example.com", "/ci/hop-by-hop", token());
    assertEquals(HttpVersion.HTTP_2, answer.version());
    assertEquals(302, answer.status(), answer.raw().toString());
    assertEquals(List.of("/projects"), answer.headerValues("Location"));
    assertEquals(List.of("Express"), answer.headerValues("X-Powered-By"));
    assertEquals(List.of(), answer.headerValues("Connection"), answer.raw().toString());
    assertEquals(List.of(), answer.headerValues("Keep-Alive"), answer.raw().toString());
    assertEquals(List.of(), answer.headerValues("X-Custom-Hop"), answer.raw().toString());
  }

  @Test
  void aStreamedAnswerStillCompletesOverHttp2() {
    // The chunked stub's Transfer-Encoding is dropped like every hop-by-hop header; h2 frames the
    // body itself, so the whole of it must still arrive.
    activateCi();
    EdgeClient.Answer answer = http2().get("ci.dev.acme.example.com", "/stream", token());
    assertEquals(HttpVersion.HTTP_2, answer.version());
    assertEquals(200, answer.status());
    assertEquals("chunk-1\nchunk-2\n", answer.body());
    assertEquals(List.of(), answer.headerValues("Transfer-Encoding"));
  }

  // --- websockets ----------------------------------------------------------------------------

  @Test
  void aWebSocketUpgradeReachesTheServiceItsHostNames() {
    // Every interactive terminal on the platform is one of these. Getting a frame back at all is
    // what proves the handshake survived the hop.
    activateCi();
    String seen = client().handshake("ci.dev.acme.example.com", "/terminal", token());
    assertTrue(seen.lines().anyMatch("upstream=mirror-dev"::equals), seen);
  }

  @Test
  void aWebSocketUpgradeCarriesTheForwardedHeaders() {
    // The upgrade never reaches the interceptor chain — vertx-http-proxy short-circuits before
    // installing it — so this is a second code path with its own way of losing the headers.
    activateCi();
    String seen = client().handshake("ci.dev.acme.example.com", "/terminal", token());
    assertTrue(seen.lines().anyMatch("x-forwarded-for=127.0.0.1"::equals), seen);
    assertTrue(seen.lines().anyMatch("x-forwarded-proto=http"::equals), seen);
    assertTrue(
        seen.lines().anyMatch(l -> l.startsWith("x-forwarded-host=ci.dev.acme.example.com")), seen);
  }

  @Test
  void aWebSocketUpgradeStillCarriesTheClientsOwnHeaders() {
    // The edge strips nothing but the browser cookie on an upgrade either: an unrelated cookie is a
    // service's own and goes along with the socket.
    activateCi();
    Map<String, String> headers = new java.util.HashMap<>(token());
    headers.put("Cookie", "q_session=abc");
    String seen = client().handshake("ci.dev.acme.example.com", "/terminal", headers);
    assertTrue(seen.lines().anyMatch("cookie=q_session=abc"::equals), seen);
  }

  @Test
  void aWebSocketOpenedWithATokenReachesTheServiceCarryingTheJwt() {
    // The upgrade is the edge's own path, but the credential is settled before it: the router runs
    // the check, the check rewrites Authorization on the inbound map, and the handshake copies that
    // map. So a terminal opened with a token is a terminal holding the JWT, as a plain request is.
    activateCi();
    String seen =
        client().handshake("ci.dev.acme.example.com", "/terminal", bearer(StubGateways.TOKEN));
    assertTrue(seen.lines().anyMatch("upstream=mirror-dev"::equals), seen);
    String authorization =
        seen.lines()
            .filter(l -> l.startsWith("authorization="))
            .map(l -> l.substring("authorization=".length()))
            .findFirst()
            .orElseThrow();
    assertTrue(authorization.startsWith("Bearer "), authorization);
    assertFalse(authorization.contains(TokenValue.PREFIX), "the token itself never goes out");
    SignedJwt forwarded = SignedJwt.parse(authorization.substring("Bearer ".length()));
    assertEquals(StubGateways.TOKEN_SUBJECT, forwarded.claims().getString("sub"));
    assertTrue(forwarded.signatureMatches(TestTokens.IDP.getPublic()));
  }

  @Test
  void aWebSocketOpenedWithARefusedTokenIsA401BeforeAnyUpstream() {
    // Refused at the gate, which runs before the upgrade path exists for this request: the stub
    // upstream would have answered 101, so a 401 here is the edge's own and no socket was opened.
    activateCi();
    Map<String, String> headers = new java.util.HashMap<>(upgrade());
    headers.put("Authorization", "Bearer " + StubGateways.UNKNOWN_TOKEN);
    EdgeClient.Answer answer =
        client().send(HttpMethod.GET, "ci.dev.acme.example.com", "/terminal", null, headers);
    assertEquals(401, answer.status(), answer.body());
    assertTrue(
        answer.headers().get("www-authenticate").startsWith("Bearer realm="),
        answer.headers().get("www-authenticate"));
  }

  @Test
  void aRefusedUpgradeAnswersTheUpstreamsOwnStatus() {
    // The upstream said no; the caller learns what it said, not a generic 502 — a workspace
    // service answering 403 on a terminal socket is an authorization answer, not an edge fault.
    activateCi();
    EdgeClient.Answer answer =
        client()
            .send(HttpMethod.GET, "ci.dev.acme.example.com", "/terminal/refused", null, upgrade());
    assertEquals(403, answer.status());
  }

  @Test
  void aRefusedUpgradeReturnsItsPoolSlotEveryTime() {
    // The production outage this guards: an upgrade that failed after the upstream had accepted it
    // left its pool connection neither closed nor released — one slot per attempt, and the browser
    // retried until all 64 were gone and every request to the origin, plain GETs included, queued
    // forever. More refusals than the whole pool, then a plain GET: with a leak the attempts past
    // 64 hang and this test times out rather than fails an assertion.
    activateCi();
    for (int attempt = 0; attempt < 70; attempt++) {
      EdgeClient.Answer answer =
          client()
              .send(
                  HttpMethod.GET, "ci.dev.acme.example.com", "/terminal/refused", null, upgrade());
      assertEquals(403, answer.status(), "attempt " + attempt);
    }
    EdgeClient.Answer plain = client().get("ci.dev.acme.example.com", "/anything", token());
    assertEquals("mirror-dev", plain.line("upstream"), "the origin must survive 70 refusals");
  }

  /**
   * A complete handshake, sent raw: the JDK client of {@link EdgeClient#handshake} throws away the
   * response of a refused upgrade, and these tests are about exactly that response.
   */
  private Map<String, String> upgrade() {
    Map<String, String> headers = new java.util.HashMap<>(token());
    headers.put("Upgrade", "websocket");
    headers.put("Connection", "Upgrade");
    headers.put("Sec-WebSocket-Key", "AAAAAAAAAAAAAAAAAAAAAA==");
    headers.put("Sec-WebSocket-Version", "13");
    return headers;
  }

  // --- the edge's own surface ------------------------------------------------------------------

  @Test
  void healthIsAnsweredByTheEdgeItself() {
    // /q never leaves this process on a name that means the edge itself — the apex reading: no
    // Host, an address, a single label such as localhost or the wire alias, the bare domain. It is
    // the one thing an orchestrator asks the EDGE about, not an environment behind it. The health
    // JSON is what proves it was not proxied: a stub gateway answers with its own marker lines.
    for (String host : List.of("localhost", "dev-qits-edge", "127.0.0.1", "example.com")) {
      RestAssured.given()
          .header("Host", host)
          .when()
          .get("/q/health/ready")
          .then()
          .statusCode(200)
          .body("status", is("UP"))
          .body("checks.find { it.name == 'edge upstreams' }.data.default", is("prod"));
    }
  }

  @Test
  void anApplicationsNameNeverReportsTheEdgesHealth() {
    // qits-2222: answered whatever the Host said, ci's own /q/health/ready read UP from the EDGE
    // while ci itself was stopped and every other path on its name 404ed. On an app's name /q/* is
    // that app's path, proxied behind the gate like /queue/items is.
    activateCi();
    EdgeClient.Answer answer = client().get("ci.dev.acme.example.com", "/q/health/ready", token());
    assertEquals("mirror-dev", answer.line("upstream"), answer.body());
    assertEquals("/q/health/ready", answer.line("uri"));
  }

  @Test
  void anApplicationsNameIsGatedOnItsHealthPathToo() {
    // The ordinary path includes the gate: an anonymous caller gets the refusal any other path on
    // the vhost gets, never the edge's health and never the app's.
    activateCi();
    EdgeClient.Answer answer = client().get("ci.dev.acme.example.com", "/q/health/ready");
    assertEquals(401, answer.status(), answer.body());
    assertNull(answer.line("upstream"), "an anonymous request must not reach the application");
  }

  @Test
  void aNameNothingRoutesIs404OnItsHealthPathToo() {
    // The stopped app's case: nothing publishes the name, so /q/health/ready is the 404 every other
    // path on it is — not the edge's UP.
    EdgeClient.Answer answer =
        client().get("nosuchapp.dev." + PROJECT + ".example.com", "/q/health/ready", token());
    assertEquals(404, answer.status(), answer.body());
    assertNull(answer.line("upstream"));
    assertTrue(answer.body().contains("`nosuchapp` is not an application"), answer.body());
  }

  @Test
  void anEnvironmentDoorIsNoNameForTheEdgesHealth() {
    // A door is not the edge either: it answers /q/health/ready as it answers any other path —
    // the door's own 404, which serves nothing.
    EdgeClient.Answer answer = client().get("dev." + PROJECT + ".example.com", "/q/health/ready");
    assertEquals(404, answer.status(), answer.body());
    assertFalse(answer.body().contains("edge upstreams"), answer.body());
  }

  @Test
  void livenessIsAnsweredByTheEdgeItself() {
    RestAssured.when().get("/q/health/live").then().statusCode(200).body("status", is("UP"));
  }

  @Test
  void aPathThatOnlyLooksLikeTheManagementRootIsProxied() {
    // /q is the prefix, not a substring: /queue belongs to a service like any other path.
    activateCi();
    EdgeClient.Answer answer = client().get("ci.dev.acme.example.com", "/queue/items", token());
    assertEquals("mirror-dev", answer.line("upstream"));
    assertEquals("/queue/items", answer.line("uri"));
  }

  /**
   * Spellings Vert.x normalises to {@code /q/health/ready} — it decodes an unreserved escape,
   * collapses {@code //} and removes dot segments before it matches a route (qits-2229).
   */
  private static final List<String> SPELLED_HEALTH =
      List.of("/%71/health/ready", "//q/health/ready", "/x/../q/health/ready");

  @Test
  void anApplicationsNameNeverReportsTheEdgesHealthHoweverThePathIsSpelled() {
    // qits-2229: the qits-2222 guard read the raw path while Quarkus' /q routes match the
    // normalised one, so each of these on an app's name stepped past it and was answered 200 by
    // the edge's own health — live, on every app Host. Anonymous, each is the 401 the canonical
    // spelling gets.
    activateCi();
    for (String path : SPELLED_HEALTH) {
      EdgeClient.Answer answer = client().get("ci.dev.acme.example.com", path);
      assertEquals(401, answer.status(), path + ": " + answer.body());
      assertFalse(answer.body().contains("edge upstreams"), path + ": " + answer.body());
      assertNull(answer.line("upstream"), path + " must not reach the application anonymously");
    }
  }

  @Test
  void aSpelledHealthPathIsForwardedVerbatimToTheApplication() {
    // With a credential it is the app's path like any other, and the edge rewrites nothing: the
    // raw spelling arrives upstream, where the app's own router makes of it what it will.
    activateCi();
    for (String path : SPELLED_HEALTH) {
      EdgeClient.Answer answer = client().get("ci.dev.acme.example.com", path, token());
      assertEquals("mirror-dev", answer.line("upstream"), path + ": " + answer.body());
      assertEquals(path, answer.line("uri"), "forwarded as sent, not normalised");
    }
  }

  @Test
  void aNameNothingRoutesIs404OnASpelledHealthPathToo() {
    for (String path : SPELLED_HEALTH) {
      EdgeClient.Answer answer =
          client().get("nosuchapp.dev." + PROJECT + ".example.com", path, token());
      assertEquals(404, answer.status(), path + ": " + answer.body());
      assertNull(answer.line("upstream"));
      assertFalse(answer.body().contains("edge upstreams"), path + ": " + answer.body());
    }
  }

  @Test
  void theEdgesOwnNameStillAnswersItsHealthUnderTheNormalisedRoute() {
    // The guard now reads normalizedPath(); on the edge's own name that changes nothing — the
    // canonical path and every spelling of it reach Quarkus' health, as they always did.
    RestAssured.given()
        .header("Host", "localhost")
        .when()
        .get("/q/health/ready")
        .then()
        .statusCode(200)
        .body("status", is("UP"));
    for (String path : SPELLED_HEALTH) {
      EdgeClient.Answer answer = client().get("localhost", path);
      assertEquals(200, answer.status(), path + ": " + answer.body());
      assertTrue(answer.body().contains("edge upstreams"), path + ": " + answer.body());
    }
  }

  // --- idp auth, terminated here ---------------------------------------------------------------

  @Test
  void anApplicationVhostRefusesAnAnonymousCallerWithTheDockerChallenge() {
    // The exact string docker parses to find its token endpoint. Getting it wrong fails the pull
    // with no message anywhere, which is why it is asserted whole rather than by substring.
    EdgeClient.Answer answer = client().get("registry.dev.acme.example.com", "/v2/");
    assertEquals(401, answer.status());
    assertEquals(
        "Bearer realm=\"http://registry.dev.acme.example.com/token\",service=\"registry.dev.acme.example.com\"",
        answer.headers().get("www-authenticate"));
    assertTrue(answer.body().contains("UNAUTHORIZED"), answer.body());
    assertNull(answer.line("upstream"), "an anonymous request must not reach the application");
  }

  @Test
  void aGatedRefusalOffersBearerAndThenBasic() {
    // Two clients, two schemes, and the ORDER is the contract:
    //   * docker and containerd walk the challenges and act on the first they know, so Bearer must
    //     come first or the token flow stops being used;
    //   * maven's resolver only spends its configured credentials against a scheme it implements,
    //     so without the Basic line every uncached resolve in a build dies 401 with the right
    //     credentials sitting unused.
    // Asserted from the raw header list: a map collapses the two into one and proves nothing.
    EdgeClient.Answer answer = client().get("registry.dev.acme.example.com", "/v2/");
    assertEquals(401, answer.status());
    assertEquals(
        List.of(
            "Bearer realm=\"http://registry.dev.acme.example.com/token\",service=\"registry.dev.acme.example.com\"",
            "Basic realm=\"registry.dev.acme.example.com\""),
        answer.headerValues("www-authenticate"));
  }

  @Test
  void everyGatedRefusalCarriesBothChallengesAndNotJustTheAnonymousOne() {
    // A build resolves through both: the first request of a session carries nothing, and a later
    // one may carry a credential this vhost refuses. Both have to tell maven that Basic is taken.
    for (Map<String, String> credential :
        List.of(
            Map.<String, String>of(), basic(StubGateways.OTHER_ID, StubGateways.OTHER_SECRET))) {
      EdgeClient.Answer answer =
          client()
              .send(HttpMethod.PUT, "registry.dev.acme.example.com", "/v2/blob", "x", credential);
      assertEquals(401, answer.status());
      List<String> challenges = answer.headerValues("www-authenticate");
      assertEquals(2, challenges.size(), challenges.toString());
      assertTrue(challenges.get(0).startsWith("Bearer realm="), challenges.toString());
      assertEquals("Basic realm=\"registry.dev.acme.example.com\"", challenges.get(1));
    }
  }

  @Test
  void anApplicationVhostRefusesATokenSignedBySomebodyElse() {
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.acme.example.com",
                "/v2/",
                bearer(
                    TestTokens.mint(
                        TestTokens.IMPOSTOR,
                        TestTokens.KID,
                        "RS256",
                        TestTokens.claims(
                            issuer(),
                            List.of(StubGateways.PLATFORM_AUDIENCE),
                            Instant.now().plusSeconds(300)))));
    assertEquals(401, answer.status());
    // `error` is what tells docker the credential it holds is dead, so it re-fetches rather than
    // giving up. It is absent from the anonymous challenge above, on purpose.
    assertTrue(
        answer.headers().get("www-authenticate").contains("error=\"invalid_token\""),
        answer.headers().get("www-authenticate"));
    assertNull(answer.line("upstream"));
  }

  @Test
  void anApplicationVhostRefusesAnExpiredTokenAndOneForAnotherAudience() {
    assertEquals(
        401,
        client()
            .get(
                "registry.dev.acme.example.com",
                "/v2/",
                bearer(
                    TestTokens.mint(
                        TestTokens.IDP,
                        TestTokens.KID,
                        "RS256",
                        TestTokens.claims(
                            issuer(),
                            List.of(StubGateways.PLATFORM_AUDIENCE),
                            Instant.now().minusSeconds(3600)))))
            .status());
    assertEquals(
        401,
        client()
            .get(
                "registry.dev.acme.example.com",
                "/v2/",
                bearer(
                    TestTokens.mint(
                        TestTokens.IDP,
                        TestTokens.KID,
                        "RS256",
                        TestTokens.claims(
                            issuer(), List.of("somebody-else"), Instant.now().plusSeconds(300)))))
            .status());
  }

  @Test
  void anApplicationsWireAliasOpensNoVhost() {
    // `dev-qits-artifacts` is where registry.dev routes, not an audience: every gated vhost accepts
    // `qits-platform` and nothing else, in its own environment as much as in another.
    Map<String, String> aliases = aliasToken("dev");
    assertEquals(401, client().get("registry.dev.acme.example.com", "/v2/", aliases).status());
    assertEquals(401, client().get("registry.prod.acme.example.com", "/v2/", aliases).status());
    assertEquals(
        401,
        client()
            .send(HttpMethod.POST, "mirror.dev.acme.example.com", "/v2/blob", "x", aliases)
            .status());
  }

  @Test
  void onlyTheDerivedIssuerOpensTheVhostAndAnyOtherIsRefused() {
    // qits-730 wave 3: the legacy issuer is no longer accepted. The third is the qits-162 shape —
    // the dial address read as the claim.
    List<String> platform = List.of(StubGateways.PLATFORM_AUDIENCE);
    Map<String, String> derived =
        bearer(TestTokens.valid("https://idp.qits.example.com", platform));
    Map<String, String> legacy =
        bearer(TestTokens.valid("http://qits-platform-idp:8080/idp", platform));
    Map<String, String> address =
        bearer(TestTokens.valid("http://dev-qits-idp:8080/idp", platform));

    assertEquals(
        "registry-dev",
        client().get("registry.dev.acme.example.com", "/v2/", derived).line("upstream"));
    assertEquals(401, client().get("registry.dev.acme.example.com", "/v2/", legacy).status());
    assertEquals(401, client().get("registry.dev.acme.example.com", "/v2/", address).status());
  }

  @Test
  void thePlatformAudienceOpensEveryPlatformVhostInEveryEnvironment() {
    // A person's command-line token names only `qits-platform`. It must reach every service, and
    // its roles are the permission.
    Map<String, String> platform =
        bearer(TestTokens.valid(issuer(), List.of(StubGateways.PLATFORM_AUDIENCE)));
    for (String environment : List.of("dev", "prod")) {
      assertEquals(
          "registry-" + environment,
          client()
              .get("registry." + environment + ".acme.example.com", "/v2/", platform)
              .line("upstream"));
      assertEquals(
          "mirror-" + environment,
          client()
              .send(
                  HttpMethod.POST,
                  "mirror." + environment + ".acme.example.com",
                  "/v2/blob",
                  "x",
                  platform)
              .line("upstream"));
    }
  }

  @Test
  void gitsOauth2BasicFollowsThePlatformAudienceRule() {
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.acme.example.com",
                "/v2/",
                basic(
                    "oauth2", TestTokens.valid(issuer(), List.of(StubGateways.PLATFORM_AUDIENCE))));
    assertEquals("registry-dev", answer.line("upstream"));
    assertTrue(
        answer.body().contains("header:authorization=Bearer "),
        "the token goes on as a Bearer: " + answer.body());
    assertEquals(
        401,
        client()
            .get(
                "registry.dev.acme.example.com",
                "/v2/",
                basic("oauth2", TestTokens.valid(issuer(), List.of("somebody-else"))))
            .status());
  }

  @Test
  void aClientCommissionedForThePlatformAudienceOpensTheVhostWithBasic() {
    // The Basic client-credential path: the minted token carries only the platform audience.
    for (String environment : List.of("dev", "prod")) {
      assertEquals(
          "registry-" + environment,
          client()
              .get(
                  "registry." + environment + "." + PROJECT + ".example.com",
                  "/v2/",
                  basic(StubGateways.PLATFORM_ID, StubGateways.PLATFORM_SECRET))
              .line("upstream"));
    }
  }

  // --- the browser gate, dark
  // ---------------------------------------------------------------------

  @Test
  void theGateBeingOffTurnsOnNoSessionMachineryButStillStripsAForgedIdentity() {
    // qits.edge.sessions.enabled is off in this suite, which is the shipped default: no session
    // introspection and no identity WRITTEN. What is off is the browser gate — not the reserved-
    // prefix strip, which is orthogonal to it and unconditional. A client-supplied X-Qits-* is a
    // forged identity whatever the gate's state, and the edge cannot assume a downstream tier will
    // drop it — the reserved-namespace hygiene is its own to uphold on every path. `mirror` is the
    // vhost whose reads are open, so the request needs no credential at all — and the forged
    // headers
    // on it still must not survive.
    int before = StubGateways.introspections();
    EdgeClient.Answer answer =
        client()
            .send(
                HttpMethod.GET,
                "mirror.dev.acme.example.com",
                "/v2/",
                null,
                Map.of(
                    "Cookie", "qits-session=" + StubGateways.SESSION,
                    "X-Qits-User", "whoever",
                    "X-Qits-Roles", "qits:root",
                    "Sec-Fetch-Mode", "navigate"));

    assertEquals(200, answer.status(), "a navigation is not redirected while the gate is off");
    assertEquals("mirror-dev", answer.line("upstream"));
    assertNull(
        answer.upstreamHeader("X-Qits-User"), "a forged identity is stripped, gate or no gate");
    assertNull(answer.upstreamHeader("X-Qits-Roles"));
    assertEquals(before, StubGateways.introspections(), "and idp was never asked");
  }

  @Test
  void aWebSocketUpgradeIsNotGatedButStillStripsAForgedIdentityWhileTheGateIsOff() {
    activateCi();
    Map<String, String> headers = new java.util.HashMap<>(token());
    headers.put("X-Qits-User", "whoever");
    String seen = client().handshake("ci.dev.acme.example.com", "/terminal", headers);
    // The stub reports every reserved header slot as `name=value`, with `-` for absent — so the
    // strip shows as `x-qits-user=-`, and what must never appear is the forged value.
    assertTrue(
        seen.lines().noneMatch("x-qits-user=whoever"::equals),
        "a forged identity on an upgrade is stripped even with the gate off:\n" + seen);
  }

  // --- the anonymous-read exemption, per app ----------------------------------------------------

  @Test
  void anExemptedAppVhostServesAnAnonymousGet() {
    // `mirror` is named in qits.edge.auth.anonymous-read-apps. A pull with no credential is the
    // bootstrap case this exists for, and it has to reach the upstream rather than the challenge.
    assertEquals(
        "mirror-dev", client().get("mirror.dev.acme.example.com", "/v2/").line("upstream"));
    assertEquals(
        "mirror-prod", client().get("mirror.prod.acme.example.com", "/v2/").line("upstream"));
  }

  @Test
  void anExemptedAppVhostServesAnAnonymousHead() {
    // The other reading method, and docker uses it for every blob it checks before pulling. A HEAD
    // answer carries no body, so the upstream marker is read from the header the stub also sets.
    EdgeClient.Answer answer =
        client().send(HttpMethod.HEAD, "mirror.dev.acme.example.com", "/v2/blob", null, Map.of());
    assertEquals(200, answer.status());
    assertEquals("mirror-dev", answer.headers().get("x-upstream"));
  }

  @Test
  void anExemptedAppVhostStillGatesEveryWritingMethod() {
    // The exemption opens READS, never a service. A push is what changes what the platform will
    // run, and it gets the same challenge as before — including the realm docker needs to act on
    // it.
    for (HttpMethod method :
        new HttpMethod[] {HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE}) {
      EdgeClient.Answer answer =
          client().send(method, "mirror.dev.acme.example.com", "/v2/blob", "x", Map.of());
      assertEquals(401, answer.status(), method + " must still be gated");
      assertEquals(
          "Bearer realm=\"http://mirror.dev.acme.example.com/token\",service=\"mirror.dev.acme.example.com\"",
          answer.headers().get("www-authenticate"));
      assertNull(answer.line("upstream"), method + " must not have reached the application");
    }
  }

  @Test
  void anAuthenticatedWriteOnAnExemptedAppVhostPasses() {
    // The other half: the exemption is a way past the gate, not a replacement for it.
    EdgeClient.Answer answer =
        client().send(HttpMethod.POST, "mirror.dev.acme.example.com", "/v2/blob", "x", token());
    assertEquals("mirror-dev", answer.line("upstream"));
    assertEquals("POST", answer.line("method"));
    assertEquals("x", answer.line("body"));
  }

  @Test
  void aMachineVhostNeverReceivesTheBrowserSessionCookieButKeepsOtherCookies() {
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.acme.example.com",
                "/v2/",
                Map.of(
                    "Authorization",
                    token().get("Authorization"),
                    "Cookie",
                    "theme=dark; qits-session=" + StubGateways.SESSION + "; locale=en"));
    assertEquals("registry-dev", answer.line("upstream"));
    assertEquals("theme=dark; locale=en", answer.upstreamHeader("Cookie"));
  }

  @Test
  void theManagementSurfaceIsNeverOpenedByTheExemptionOnAReadOpenApp() {
    // qits-2222: `/q/*` on an app vhost is routed through this same gate rather than answered by
    // the edge itself, so the anonymous-read exemption would otherwise hand an internet caller
    // `mirror`'s own Quarkus management routes. It must stay gated even though `mirror`'s ordinary
    // reads are open.
    EdgeClient.Answer metrics = client().get("mirror.dev.acme.example.com", "/q/metrics");
    assertEquals(401, metrics.status());
    assertNull(metrics.line("upstream"), "the request must never have reached the upstream");

    assertEquals(401, client().get("mirror.dev.acme.example.com", "/q/openapi").status());
    assertEquals(401, client().get("mirror.dev.acme.example.com", "/q/health/ready").status());
    assertEquals(401, client().get("mirror.dev.acme.example.com", "/q").status());

    // An ordinary read on the very same vhost still passes with no credential at all.
    assertEquals(
        "mirror-dev", client().get("mirror.dev.acme.example.com", "/v2/").line("upstream"));
  }

  @Test
  void aSpelledManagementPathIsNeverOpenedByTheExemptionEither() {
    // qits-2229: every one of these is (or may be) /q/metrics to mirror's own router, which gets
    // the raw path verbatim, while the raw /q test read them as ordinary paths. Not canonical, so
    // not exempt: each meets the gate like any other path with no credential.
    for (String path :
        List.of(
            "//q/metrics", "/%71/metrics", "/q%2Fmetrics", "/x/../q/metrics", "/%2Fq/metrics")) {
      EdgeClient.Answer answer = client().get("mirror.dev.acme.example.com", path);
      assertEquals(401, answer.status(), path + ": " + answer.body());
      assertNull(answer.line("upstream"), path + " must never have reached the upstream");
    }
  }

  @Test
  void anAppThatWasNotNamedStillRefusesAnAnonymousRead() {
    // Per app label: `registry` is not on the list, so its reads are gated exactly as before.
    assertEquals(401, client().get("registry.dev.acme.example.com", "/v2/").status());
    assertEquals(
        401,
        client()
            .send(HttpMethod.HEAD, "registry.dev.acme.example.com", "/v2/", null, Map.of())
            .status());
  }

  @Test
  void anUnknownAppLabelIsStill404EvenWhereReadsAreOpen() {
    // The exemption is applied AFTER the label resolves, so it cannot turn a typo into a route.
    // `mirro` is one letter from an app whose reads are open and is still nobody's name.
    assertEquals(404, client().get("mirro.dev.acme.example.com", "/v2/").status());
  }

  @Test
  void aProjectedExemptedAppVhostServesAnAnonymousGetFromItsOwnUpstream() {
    // The second route to the same gate. `brochure` is named in qits.edge.auth.anonymous-read-apps
    // and configured nowhere, so HostEnvironments answers it as an unknown app; it reaches the
    // exemption at all only because EdgeRouter.target() rebuilds the Route with the projection's
    // label as the app, which is what makes toApp() true. This is the path a public SSR landing
    // page depends on, and until now nothing exercised it. `registry-dev` rather than `mirror-dev`
    // is the load-bearing half of the assertion: it is the projection's OWN upstream answering.
    activateBrochure();
    EdgeClient.Answer answer = client().get("brochure.dev.acme.example.com", "/brochure/");
    assertEquals(200, answer.status());
    assertEquals("registry-dev", answer.line("upstream"));
  }

  @Test
  void theProjectedExemptedAppVhostIsRoutedByNothingButTheProjection() {
    // The guard against this coverage silently degrading into a second copy of the `mirror` case:
    // if `brochure` ever became a platform app label, the test above would stop proving anything
    // new, and this test is what fails instead. (An override cannot add a label at all; see
    // EdgeRouter.requirePlatformLabels.)
    assertFalse(
        PlatformApps.contains("brochure"),
        "`brochure` must stay a projected name or it is no longer the projected case");
    assertFalse(
        ConfigProvider.getConfig()
            .getOptionalValue("qits.edge.apps.brochure.hosts.dev", String.class)
            .isPresent(),
        "`brochure` must stay unconfigured or it is no longer the projected case");

    // And the sharpest proof that configuration is not quietly supplying the route: with the
    // projection empty — @BeforeEach clears it — the very same anonymous read is nobody's name.
    assertEquals(404, client().get("brochure.dev.acme.example.com", "/brochure/").status());

    activateBrochure();
    assertNotNull(
        routes.serviceHost("dev", "brochure"),
        "the projection is the only thing on the estate that knows this name");
  }

  @Test
  void aProjectedAnonymousReadArrivesWithTheReservedNamespaceEmpty() {
    // Nobody vouched for anybody on this path, so there is no trusted identity to write — and
    // doing nothing is exactly what would let a stranger's `X-Qits-User: admin` reach a
    // service that believes it. The strip cannot be conditional on there being a real identity to
    // replace the forgery with: it is where there is none that a forgery survives. The rule is the
    // whole `X-Qits-` prefix, not the three names the edge happens to write, so a name nobody has
    // invented yet is stripped too.
    activateBrochure();
    EdgeClient.Answer answer =
        client()
            .send(
                HttpMethod.GET,
                "brochure.dev.acme.example.com",
                "/brochure/",
                null,
                Map.of(
                    "X-Qits-User", "admin",
                    "X-Qits-User-Id", "00000000-0000-0000-0000-000000000000",
                    "X-Qits-Roles", "qits:root",
                    "X-Qits-Something-Nobody-Invented-Yet", "whatever"));

    assertEquals(200, answer.status());
    assertEquals("registry-dev", answer.line("upstream"));
    assertNull(answer.upstreamHeader("X-Qits-User"));
    assertNull(answer.upstreamHeader("X-Qits-User-Id"));
    assertNull(answer.upstreamHeader("X-Qits-Roles"));
    assertNull(answer.upstreamHeader("X-Qits-Something-Nobody-Invented-Yet"));
  }

  @Test
  void aProjectedExemptedAppVhostStillGatesEveryWritingMethod() {
    // The exemption is method-scoped on the projected path too: rebuilding the Route makes the
    // label an app, it does not make the vhost open. A projected label is learned at RUNTIME from
    // an event, so this is the half that keeps a deployment from publishing itself a writable
    // door. Status and the absent upstream only: the challenge a projected vhost carries is built
    // from the request authority rather than from a per-app entry it does not have, so pinning
    // mirror's expected realm string here would assert the wrong thing.
    activateBrochure();
    for (HttpMethod method :
        new HttpMethod[] {HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE}) {
      EdgeClient.Answer answer =
          client().send(method, "brochure.dev.acme.example.com", "/brochure/", "x", Map.of());
      assertEquals(401, answer.status(), method + " must still be gated");
      assertNull(answer.line("upstream"), method + " must not have reached the application");
    }
  }

  @Test
  void anotherProjectedAppThatWasNotNamedStillRefusesAnAnonymousRead() {
    // The "one name over" property, for the dynamic path.
    // `anAppThatWasNotNamedStillRefusesAnAnonymousRead` proves it for a configured label; this is
    // the same property for a projected one, and it matters more here, because the projection is
    // attacker-adjacent: a label the edge learned at runtime must never inherit the exemption of
    // another label that happens to have been named.
    activateBrochure();
    activateCi();
    EdgeClient.Answer answer = client().get("ci.dev.acme.example.com", "/ci/api/runs");
    assertEquals(401, answer.status());
    assertNull(answer.line("upstream"), "and it reached no upstream");
  }

  // --- the docker token endpoint ----------------------------------------------------------------

  @Test
  void theTokenEndpointAsksForTheStoredLoginCredential() {
    EdgeClient.Answer answer =
        client().get("registry.dev.acme.example.com", "/token?service=x&scope=y");
    assertEquals(401, answer.status());
    // Basic ALONE, and that is the difference from a gated request: this is the endpoint that SELLS
    // bearer tokens, so a Bearer challenge here would point a client back at where it already is.
    assertEquals(
        List.of("Basic realm=\"registry.dev.acme.example.com\""),
        answer.headerValues("www-authenticate"));
  }

  @Test
  void theTokenEndpointBrokersAGrantAndHandsBackADockerStyleToken() {
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.acme.example.com",
                "/token?service=registry.dev.acme.example.com&scope=repository:qits/x:pull",
                basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
    assertEquals(200, answer.status());
    JsonObject issued = new JsonObject(answer.body());
    assertNotNull(issued.getString("token"));
    assertEquals(issued.getString("token"), issued.getString("access_token"));
    assertEquals(300, issued.getInteger("expires_in"));
  }

  @Test
  void theTokenEndpointRefusesCredentialsIdpDoesNotKnow() {
    assertEquals(
        401,
        client()
            .get("registry.dev.acme.example.com", "/token", basic("nobody", "nothing"))
            .status());
  }

  @Test
  void theWholeDockerFlowRoundTrips() {
    // Challenge, token, retry — the three hops a `docker pull` makes, in order, with no shortcut.
    EdgeClient.Answer challenged = client().get("registry.dev.acme.example.com", "/v2/");
    assertEquals(401, challenged.status());
    String realm = challenged.headers().get("www-authenticate").split("realm=\"")[1].split("\"")[0];
    assertTrue(realm.endsWith("/token"), realm);

    String issued =
        new JsonObject(
                client()
                    .get(
                        "registry.dev.acme.example.com",
                        "/token?service=registry.dev.acme.example.com",
                        basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET))
                    .body())
            .getString("token");

    assertEquals(
        "registry-dev",
        client().get("registry.dev.acme.example.com", "/v2/", bearer(issued)).line("upstream"));
  }

  @Test
  void theTokenEndpointHandsDockerTheJwtAStoredTokenStandsFor() throws Exception {
    // `docker login -u token -p qits_tok_…`: the realm introspects rather than grants, and docker
    // is handed an ordinary idp JWT that the registry vhost then validates offline.
    Thread.sleep(tokenCacheTtlMs() + 400);
    int grants = StubGateways.grants();
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.acme.example.com",
                "/token?service=registry.dev.acme.example.com&scope=repository:qits/x:pull",
                basic("token", StubGateways.TOKEN));
    assertEquals(200, answer.status(), answer.body());
    assertEquals("no-store", answer.headers().get("cache-control"));
    JsonObject issued = new JsonObject(answer.body());
    String jwt = issued.getString("token");
    assertEquals(jwt, issued.getString("access_token"));
    assertFalse(jwt.startsWith(TokenValue.PREFIX), "docker is handed the JWT, not the token");
    SignedJwt parsed = SignedJwt.parse(jwt);
    assertTrue(parsed.signatureMatches(TestTokens.IDP.getPublic()));
    assertNull(parsed.problem(List.of(issuer()), StubGateways.PLATFORM_AUDIENCE, Instant.now(), 0));
    assertEquals(StubGateways.TOKEN_SUBJECT, parsed.claims().getString("sub"));
    long expiresIn = issued.getLong("expires_in");
    assertTrue(
        expiresIn > 0 && expiresIn <= StubGateways.TOKEN_JWT_SECONDS,
        "what the JWT has left, never more than idp gave it: " + expiresIn);
    assertNotNull(issued.getString("issued_at"));
    assertEquals(grants, StubGateways.grants(), "a token is never spent as a client secret");

    assertEquals(
        "registry-dev",
        client().get("registry.dev.acme.example.com", "/v2/", bearer(jwt)).line("upstream"),
        "and the registry takes it as the Bearer it is");
  }

  @Test
  void aPublishedHostThatIsNoPlatformLabelAnswersDockersTokenFlowItself() throws Exception {
    // qits-2744: the challenge names `<host>/token` on every gated service host, so the realm must
    // be the edge's on every one of them — not only on a PlatformApps label. `ci` is published by
    // the route table alone and its upstream is a stub that would answer /token itself; when the
    // edge left it to the service, qits-mirror's SPA answered with index.html and docker died on
    // "invalid character '<'" (2026.1127.222912).
    activateCi();
    Thread.sleep(tokenCacheTtlMs() + 400);
    EdgeClient.Answer challenged = client().get("ci.dev.acme.example.com", "/v2/");
    assertEquals(401, challenged.status(), challenged.body());
    String realm = challenged.headers().get("www-authenticate").split("realm=\"")[1].split("\"")[0];
    assertEquals("http://ci.dev.acme.example.com/token", realm);

    EdgeClient.Answer answer =
        client()
            .get(
                "ci.dev.acme.example.com",
                "/token?service=ci.dev.acme.example.com&scope=repository:quay/x:pull",
                basic("token", StubGateways.TOKEN));
    assertEquals(200, answer.status(), answer.body());
    assertNull(answer.line("upstream"), "the edge answered it, not the service behind the host");
    assertTrue(
        answer.headers().get("content-type").startsWith("application/json"),
        answer.headers().get("content-type"));
    String jwt = new JsonObject(answer.body()).getString("token");
    assertFalse(jwt.startsWith(TokenValue.PREFIX), "docker is handed the JWT, not the token");

    assertEquals(
        "mirror-dev",
        client().get("ci.dev.acme.example.com", "/ci/v2/", bearer(jwt)).line("upstream"),
        "and the published host takes it as the Bearer it is");

    EdgeClient.Answer unknown =
        client()
            .get("ci.dev.acme.example.com", "/token", basic("token", StubGateways.UNKNOWN_TOKEN));
    assertEquals(401, unknown.status(), unknown.body());
    assertNull(unknown.line("upstream"), "a refused realm call never reaches the service either");
  }

  @Test
  void theTokenEndpointRefusesATokenIdpDoesNotKnow() {
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.acme.example.com",
                "/token",
                basic("token", StubGateways.UNKNOWN_TOKEN));
    assertEquals(401, answer.status(), answer.body());
    assertTrue(answer.body().contains("UNAUTHORIZED"), answer.body());
  }

  @Test
  void theTokenEndpointAnswers502WhenIdpCannotBeAskedAboutAToken() throws Exception {
    Thread.sleep(tokenCacheTtlMs() + 400);
    StubGateways.idpDown();
    try {
      EdgeClient.Answer answer =
          client()
              .get("registry.dev.acme.example.com", "/token", basic("token", StubGateways.TOKEN));
      assertEquals(502, answer.status(), answer.body());
      assertTrue(answer.body().contains("UNAVAILABLE"), answer.body());
    } finally {
      StubGateways.idpUp();
    }
  }

  @Test
  void theTokenEndpointHandsBackAPlatformJwtPresentedAsGitsOauth2Pair() {
    // `docker login -u oauth2 -p <JWT>`, the spelling git's credential helper already stores. The
    // JWT is the token docker wants, so it is checked offline and handed back as itself — never
    // spent at idp as a client secret, which idp could only refuse.
    int grants = StubGateways.grants();
    int introspections = StubGateways.tokenIntrospections();
    String jwt = TestTokens.valid(issuer(), List.of(StubGateways.PLATFORM_AUDIENCE));
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.acme.example.com",
                "/token?service=registry.dev.acme.example.com&scope=repository:qits/x:pull",
                basic("oauth2", jwt));
    assertEquals(200, answer.status(), answer.body());
    assertEquals("no-store", answer.headers().get("cache-control"));
    JsonObject issued = new JsonObject(answer.body());
    assertEquals(jwt, issued.getString("token"));
    assertEquals(jwt, issued.getString("access_token"));
    long expiresIn = issued.getLong("expires_in");
    assertTrue(expiresIn > 0 && expiresIn <= 300, "what the JWT has left: " + expiresIn);
    assertEquals(grants, StubGateways.grants(), "no grant was asked for");
    assertEquals(introspections, StubGateways.tokenIntrospections(), "nor an introspection");

    assertEquals(
        "registry-dev",
        client().get("registry.dev.acme.example.com", "/v2/", bearer(jwt)).line("upstream"),
        "and the registry takes it as the Bearer it is");
  }

  @Test
  void theTokenEndpointRefusesAnOauth2JwtThatFailsTheOfflineCheck() {
    int grants = StubGateways.grants();
    List<String> platform = List.of(StubGateways.PLATFORM_AUDIENCE);
    Map<String, String> refused =
        Map.of(
            "expired",
            TestTokens.mint(
                TestTokens.IDP,
                TestTokens.KID,
                "RS256",
                TestTokens.claims(issuer(), platform, Instant.now().minusSeconds(3600))),
            "another audience",
            TestTokens.valid(issuer(), List.of("somebody-else")),
            "another key",
            TestTokens.mint(
                TestTokens.IMPOSTOR,
                TestTokens.KID,
                "RS256",
                TestTokens.claims(issuer(), platform, Instant.now().plusSeconds(300))));
    refused.forEach(
        (why, jwt) -> {
          EdgeClient.Answer answer =
              client()
                  .get(
                      "registry.dev.acme.example.com",
                      "/token?service=registry.dev.acme.example.com",
                      basic("oauth2", jwt));
          assertEquals(401, answer.status(), why + ": " + answer.body());
          assertTrue(answer.body().contains("UNAUTHORIZED"), why + ": " + answer.body());
          assertTrue(answer.body().contains(EdgeAuth.JWT_REFUSED), why + ": " + answer.body());
          assertFalse(answer.body().contains(jwt), why + ": the token is never echoed");
        });
    assertEquals(grants, StubGateways.grants(), "none of them was spent at idp");
  }

  @Test
  void aJwtUnderAnotherUsernameIsStillAClientSecret() {
    // Only the `oauth2` user declares the password a token; under any other it is a secret, spent
    // at idp's grant like any other, and idp refuses it.
    int grants = StubGateways.grants();
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.acme.example.com",
                "/token",
                basic(
                    "a-client",
                    TestTokens.valid(issuer(), List.of(StubGateways.PLATFORM_AUDIENCE))));
    assertEquals(401, answer.status(), answer.body());
    assertTrue(StubGateways.grants() > grants, "it went to idp's grant");
  }

  // --- HTTP Basic, for the clients that cannot do docker's dance --------------------------------

  @Test
  void aClientIdAndSecretOpenAGatedVhostOnTheirOwn() {
    // maven, npm and git send Basic and nothing else. The edge spends the credential at idp and
    // reads the token that comes back, so one commissioned client works for all three.
    assertEquals(
        "registry-dev",
        client()
            .get(
                "registry.dev.acme.example.com",
                "/v2/",
                basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET))
            .line("upstream"));
    EdgeClient.Answer written =
        client()
            .send(
                HttpMethod.POST,
                "registry.dev.acme.example.com",
                "/v2/blob",
                "x",
                basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
    assertEquals("registry-dev", written.line("upstream"), "a write is the same decision");
    assertEquals("x", written.line("body"));
  }

  @Test
  void anAcceptedClientIdAndSecretReachTheUpstreamAsABearerAndNotAsThemselves() {
    // The whole of the "only CI may publish" change at this hop. A service cannot check a secret,
    // so a relayed pair tells it nothing about WHICH commissioned client is calling — and hands it
    // a secret it has no business holding. What goes out is the token the edge validated, exactly
    // as it does for git's `oauth2:` pair, so the service builds the roles from the JWT itself.
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.acme.example.com",
                "/v2/",
                basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
    assertEquals("registry-dev", answer.line("upstream"));
    String forwarded = answer.upstreamHeader("Authorization");
    assertNotNull(forwarded, "an accepted request must arrive with a credential: " + answer.body());
    assertTrue(forwarded.startsWith("Bearer "), forwarded);
    assertEquals(
        List.of(StubGateways.PLATFORM_AUDIENCE),
        SignedJwt.parse(forwarded.substring("Bearer ".length())).audiences().getList(),
        "and it is the token idp minted for this client, not something the edge made up");
    // The two ways the secret could still be there: the header, and the base64 of the pair.
    assertFalse(
        answer.body().contains(StubGateways.CLIENT_SECRET),
        "the client secret stops at the edge: " + answer.body());
    assertFalse(
        answer.body().contains("header:authorization=Basic"),
        "and nothing upstream sees a Basic credential at all: " + answer.body());
  }

  @Test
  void aCacheHitForwardsTheTokenItRememberedRatherThanNothingAtAll() throws Exception {
    // The trap the cache sets for this change: a remembered acceptance used to be a verdict and
    // nothing else, so a hit would have had no token to write — and a request that was ACCEPTED
    // would reach the service with no credential on it at all. Anonymously, silently.
    Thread.sleep(cacheTtlMs() + 400);
    int before = StubGateways.grants();
    EdgeClient.Answer cold =
        client()
            .get(
                "registry.dev.acme.example.com",
                "/v2/",
                basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
    assertEquals(before + 1, StubGateways.grants(), "the first request spends the credential");
    EdgeClient.Answer hit =
        client()
            .get(
                "registry.dev.acme.example.com",
                "/v2/",
                basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
    assertEquals(before + 1, StubGateways.grants(), "and the second is served from the cache");
    assertEquals("registry-dev", hit.line("upstream"));
    assertNotNull(
        hit.upstreamHeader("Authorization"),
        "a cache hit must forward a credential, not strip one: " + hit.body());
    assertEquals(
        cold.upstreamHeader("Authorization"),
        hit.upstreamHeader("Authorization"),
        "the same token, because the cache holds the token and not merely a yes");
  }

  @Test
  void aTokenTooCloseToItsExpiryIsMintedAgainRatherThanServedFromTheCache() {
    // What is cached is what will be FORWARDED, and a token is validated one hop further in, a
    // moment later, against another process' clock. So an entry is retired a minute before its
    // token's own `exp`, and a client whose tokens are shorter-lived than that margin is simply
    // one whose credential is spent on every request — never one served a token nobody would take.
    int before = StubGateways.grants();
    for (int request = 1; request <= 2; request++) {
      EdgeClient.Answer answer =
          client()
              .get(
                  "registry.dev.acme.example.com",
                  "/v2/",
                  basic(StubGateways.BRIEF_ID, StubGateways.BRIEF_SECRET));
      assertEquals("registry-dev", answer.line("upstream"), "request " + request);
      String forwarded = answer.upstreamHeader("Authorization");
      assertNotNull(forwarded, "request " + request + ": " + answer.body());
      assertTrue(forwarded.startsWith("Bearer "), forwarded);
    }
    assertEquals(
        before + 2,
        StubGateways.grants(),
        "a token inside the margin is never a belief worth keeping, so both requests ask idp");
  }

  @Test
  void aCredentialWhoseRemintFailsIsRefusedRatherThanForwardedBare() throws Exception {
    // The other half of the same invariant, from the failure side: once the belief has run out
    // there is no token to forward, and the only two answers left are a fresh one or a refusal.
    // Passing the request through with no credential would be the precise defect this closes.
    assertEquals(
        "registry-dev",
        client()
            .get(
                "registry.dev.acme.example.com",
                "/v2/",
                basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET))
            .line("upstream"));
    Thread.sleep(cacheTtlMs() + 400);
    StubGateways.idpDown();
    try {
      EdgeClient.Answer answer =
          client()
              .get(
                  "registry.dev.acme.example.com",
                  "/v2/",
                  basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
      assertEquals(401, answer.status(), answer.body());
      assertNull(
          answer.line("upstream"),
          "the request reached no service — a check that cannot be made refuses");
    } finally {
      StubGateways.idpUp();
    }
  }

  @Test
  void aBasicCredentialCarriesTheSameAudienceDemandAsABearer() {
    // The whole point of validating rather than trusting: the client is real, its secret is right,
    // and it is commissioned for an audience this vhost does not accept.
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.acme.example.com",
                "/v2/",
                basic(StubGateways.OTHER_ID, StubGateways.OTHER_SECRET));
    assertEquals(401, answer.status());
    assertTrue(
        answer.headers().get("www-authenticate").startsWith("Bearer realm="),
        // The challenge stays docker's, whatever the credential was: docker is the client that
        // reads it, and the one that sent Basic here does not read challenges at all.
        answer.headers().get("www-authenticate"));
    assertTrue(
        answer.headers().get("www-authenticate").contains("error=\"invalid_token\""),
        "a credential that was refused says so, unlike a request that carried none");
    assertNull(answer.line("upstream"));
  }

  @Test
  void aWrongSecretIsRefusedAndIsNotRememberedAsARefusal() {
    // Refusals are not cached: a rotated secret must start working the moment it is right, rather
    // than staying shut for as long as a cache says it was wrong.
    int before = StubGateways.grants();
    assertEquals(
        401,
        client().get("registry.dev.acme.example.com", "/v2/", basic("nobody", "nothing")).status());
    assertEquals(
        401,
        client().get("registry.dev.acme.example.com", "/v2/", basic("nobody", "nothing")).status());
    assertEquals(before + 2, StubGateways.grants(), "each attempt is idp's decision to make");
  }

  @Test
  void aValidatedCredentialIsRememberedForATimeAndThenAskedAboutAgain() throws Exception {
    // A Basic client resends its credential on EVERY request — that is what makes it a Basic
    // client — so without a cache each dependency fetch would put an idp round trip on the path.
    Thread.sleep(cacheTtlMs() + 400);
    int before = StubGateways.grants();
    assertEquals(
        "registry-dev",
        client()
            .get(
                "registry.dev.acme.example.com",
                "/v2/",
                basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET))
            .line("upstream"));
    assertEquals(before + 1, StubGateways.grants(), "the first request spends the credential");

    client()
        .get(
            "registry.dev.acme.example.com",
            "/v2/",
            basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
    client()
        .get(
            "registry.prod.acme.example.com",
            "/v2/",
            basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
    assertEquals(
        before + 1,
        StubGateways.grants(),
        "a remembered credential asks nobody — including on the other environment's vhost");

    Thread.sleep(cacheTtlMs() + 400);
    client()
        .get(
            "registry.dev.acme.example.com",
            "/v2/",
            basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
    assertEquals(before + 2, StubGateways.grants(), "and the belief runs out");
  }

  @Test
  void aBasicHeaderThatIsNotACredentialIsRefusedWithoutTroublingIdp() {
    // An empty credential store, a truncated helper answer. There is nothing to ask about, and
    // asking would hold the caller for the whole patience window while idp is being waited out.
    int before = StubGateways.grants();
    assertEquals(
        401,
        client()
            .get(
                "registry.dev.acme.example.com",
                "/v2/",
                Map.of("Authorization", "Basic !!not-base64"))
            .status());
    assertEquals(
        401,
        client()
            .get("registry.dev.acme.example.com", "/v2/", Map.of("Authorization", "Basic "))
            .status());
    assertEquals(before, StubGateways.grants(), "neither reached the identity provider");
  }

  @Test
  void anOpenReadStaysOpenWhateverTheCredentialSays() {
    // The exemption is decided before any credential is read, so a garbage one cannot close a door
    // that is meant to be open — the same as it has always been for a garbage Bearer.
    assertEquals(
        "mirror-dev",
        client()
            .get(
                "mirror.dev.acme.example.com",
                "/v2/",
                Map.of("Authorization", "Basic !!not-base64"))
            .line("upstream"));
  }

  // --- opaque tokens, introspected per request ---------------------------------------------------

  @Test
  void aTokenReachesTheServiceAsTheJwtItStandsFor() throws Exception {
    // The whole contract in one request: a `qits_tok_` value is asked about at idp, and what goes
    // upstream is the ordinary JWT idp stood it for — never the token, which no service could use.
    Thread.sleep(tokenCacheTtlMs() + 400);
    int before = StubGateways.tokenIntrospections();
    EdgeClient.Answer answer =
        client().get("registry.dev.acme.example.com", "/v2/", bearer(StubGateways.TOKEN));
    assertEquals("registry-dev", answer.line("upstream"), answer.body());
    assertForwardedTheTokensJwt(answer);
    assertEquals(before + 1, StubGateways.tokenIntrospections(), "the first request asks idp");

    EdgeClient.Answer again =
        client().get("registry.prod.acme.example.com", "/v2/", bearer(StubGateways.TOKEN));
    assertEquals("registry-prod", again.line("upstream"), again.body());
    assertForwardedTheTokensJwt(again);
    assertEquals(
        before + 1,
        StubGateways.tokenIntrospections(),
        "a remembered answer asks nobody — and the audience is still checked per request");
  }

  @Test
  void aTokenAsABasicPasswordOpensTheSameDoorWhateverTheUsername() throws Exception {
    // git's credential helpers say `oauth2:<token>`, and every username-and-password client says
    // whatever it likes. The password is what makes it a token, and none of them is ever spent at
    // idp as a client secret.
    Thread.sleep(tokenCacheTtlMs() + 400);
    int grants = StubGateways.grants();
    for (String user : List.of("oauth2", "anyuser", "token")) {
      EdgeClient.Answer answer =
          client().get("registry.dev.acme.example.com", "/v2/", basic(user, StubGateways.TOKEN));
      assertEquals("registry-dev", answer.line("upstream"), user + ": " + answer.body());
      assertForwardedTheTokensJwt(answer);
    }
    assertEquals(grants, StubGateways.grants(), "no token was spent as a client secret");
  }

  @Test
  void anUnknownTokenIsRefusedAndTheRefusalIsRemembered() throws Exception {
    // Unlike a wrong client secret, a refused token IS cached: a token is never corrected in place,
    // and a revoked one left in a CI config must not become an idp round trip per request.
    Thread.sleep(tokenCacheTtlMs() + 400);
    int before = StubGateways.tokenIntrospections();
    EdgeClient.Answer answer =
        client().get("registry.dev.acme.example.com", "/v2/", bearer(StubGateways.UNKNOWN_TOKEN));
    assertEquals(401, answer.status(), answer.body());
    assertNull(answer.line("upstream"));
    assertTrue(
        answer.headers().get("www-authenticate").startsWith("Bearer realm="),
        answer.headers().get("www-authenticate"));
    assertTrue(
        answer.headers().get("www-authenticate").contains("error=\"invalid_token\""),
        answer.headers().get("www-authenticate"));
    assertEquals(before + 1, StubGateways.tokenIntrospections(), "idp was asked once");

    assertEquals(
        401,
        client()
            .get("registry.dev.acme.example.com", "/v2/", basic("x", StubGateways.UNKNOWN_TOKEN))
            .status());
    assertEquals(
        before + 1,
        StubGateways.tokenIntrospections(),
        "and its no is believed, whichever spelling asks again");
  }

  @Test
  void aJwtIsNeverSentToTokenIntrospection() {
    // The prefix tells the two apart before either path begins: a JWT is validated offline, as it
    // always was, and idp's token door never hears of it.
    int before = StubGateways.tokenIntrospections();
    assertEquals(
        "registry-dev",
        client().get("registry.dev.acme.example.com", "/v2/", token()).line("upstream"));
    String jwt = TestTokens.valid(issuer(), List.of(StubGateways.PLATFORM_AUDIENCE));
    assertEquals(
        "registry-dev",
        client()
            .get("registry.dev.acme.example.com", "/v2/", basic("oauth2", jwt))
            .line("upstream"));
    assertEquals(before, StubGateways.tokenIntrospections());
  }

  @Test
  void aRevokedTokenStopsOpeningDoorsWithinTheCacheWindow() throws Exception {
    // The afterlife is bounded by token-cache-ttl-ms and by nothing longer: inside the window the
    // cached yes still stands, past it idp's no is obeyed.
    Thread.sleep(tokenCacheTtlMs() + 400);
    assertEquals(
        "registry-dev",
        client()
            .get("registry.dev.acme.example.com", "/v2/", bearer(StubGateways.TOKEN))
            .line("upstream"));
    StubGateways.revokeToken();
    try {
      assertEquals(
          "registry-dev",
          client()
              .get("registry.dev.acme.example.com", "/v2/", bearer(StubGateways.TOKEN))
              .line("upstream"),
          "inside the window the cached answer stands — that is the stated lag");
      Thread.sleep(tokenCacheTtlMs() + 400);
      EdgeClient.Answer answer =
          client().get("registry.dev.acme.example.com", "/v2/", bearer(StubGateways.TOKEN));
      assertEquals(401, answer.status(), answer.body());
      assertNull(answer.line("upstream"));
    } finally {
      StubGateways.restoreToken();
      // The refusal is cached as well; let it run out so it is not a later test's surprise.
      Thread.sleep(tokenCacheTtlMs() + 400);
    }
  }

  @Test
  void aTokenIdpCannotBeAskedAboutIsA503RatherThanAPassOrA401() throws Exception {
    // Not a pass: a check that cannot be made opens nothing. Not a 401 either: git's credential
    // helper erases a stored credential on one, and nothing is wrong with this token.
    Thread.sleep(tokenCacheTtlMs() + 400);
    StubGateways.idpDown();
    try {
      EdgeClient.Answer answer =
          client().get("registry.dev.acme.example.com", "/v2/", bearer(StubGateways.TOKEN));
      assertEquals(503, answer.status(), answer.body());
      assertNull(answer.line("upstream"), "the request reached no service");
      assertEquals("1", answer.headers().get("retry-after"));
      assertNull(answer.headers().get("www-authenticate"), "no challenge: the token is not bad");
      assertTrue(answer.body().contains("UNAVAILABLE"), answer.body());
    } finally {
      StubGateways.idpUp();
    }
  }

  /**
   * What the upstream received in the token's place: a Bearer JWT, signed by idp's key, naming the
   * token's own subject and its role — and nowhere the token itself.
   */
  private static void assertForwardedTheTokensJwt(EdgeClient.Answer answer) {
    String authorization = answer.upstreamHeader("Authorization");
    assertNotNull(authorization, answer.body());
    assertTrue(authorization.startsWith("Bearer "), authorization);
    assertFalse(authorization.contains(TokenValue.PREFIX), "the token itself never goes out");
    SignedJwt forwarded = SignedJwt.parse(authorization.substring("Bearer ".length()));
    assertEquals(StubGateways.TOKEN_SUBJECT, forwarded.claims().getString("sub"));
    assertTrue(
        forwarded.claims().getJsonArray("groups").contains(StubGateways.TOKEN_ROLE),
        forwarded.claims().encode());
    assertTrue(forwarded.signatureMatches(TestTokens.IDP.getPublic()));
    assertNull(
        forwarded.problem(List.of(issuer()), StubGateways.PLATFORM_AUDIENCE, Instant.now(), 0));
  }

  // --- an identity provider that is not there ---------------------------------------------------

  @Test
  void theBrokerWaitsOutAnIdpThatIsComingBack() throws Exception {
    // 2026-08-14: a deploy push died with "the identity provider could not be reached" because idp
    // was a few seconds into a redeploy. A refused connection is not an answer, so it is retried.
    StubGateways.idpDown();
    try {
      java.util.concurrent.CompletableFuture<EdgeClient.Answer> answer =
          client()
              .sending(
                  HttpMethod.GET,
                  "registry.dev.acme.example.com",
                  "/token?service=registry.dev.acme.example.com",
                  null,
                  basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
      Thread.sleep(400);
      StubGateways.idpUp();
      EdgeClient.Answer issued = answer.get(30, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(200, issued.status(), issued.body());
      assertNotNull(new JsonObject(issued.body()).getString("token"));
    } finally {
      StubGateways.idpUp();
    }
  }

  @Test
  void anIdpThatAcceptsAndNeverAnswersStillEndsInAnAnswerHere() {
    // THE HANG, and the only path in this process that could produce one: a Vert.x client is built
    // with no request timeout, so a connection that is accepted and never answered leaves the
    // caller with no status, no body and nothing to time out against. docker has no timeout of its
    // own on a realm call, so it waits for as long as the socket lives.
    long start = System.currentTimeMillis();
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.acme.example.com",
                "/token?service=registry.dev.acme.example.com",
                basic(StubGateways.SINKHOLE_ID, StubGateways.SINKHOLE_SECRET));
    long took = System.currentTimeMillis() - start;
    assertEquals(502, answer.status());
    assertTrue(answer.body().contains("UNAVAILABLE"), answer.body());
    assertTrue(took < 25_000, "the window bounds it, and it took " + took + "ms");
  }

  @Test
  void aBasicRequestAgainstASilentIdpIsDeniedRatherThanHeld() {
    // The same certainty on the gate: a check that cannot be made denies, and it denies in bounded
    // time. An open-ended wait here would hold the connection instead of answering it.
    long start = System.currentTimeMillis();
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.acme.example.com",
                "/v2/",
                basic(StubGateways.SINKHOLE_ID, StubGateways.SINKHOLE_SECRET));
    long took = System.currentTimeMillis() - start;
    assertEquals(401, answer.status());
    assertNull(answer.line("upstream"));
    assertTrue(took < 25_000, "the window bounds it, and it took " + took + "ms");
  }

  // --- the token endpoint's own credential-less arms ---------------------------------------------

  @Test
  void everyShapeOfMissingCredentialIsAnsweredPromptlyAndWhole() {
    // What docker does after the challenge is call the realm, and with nothing stored it calls it
    // with no credential or an empty one. Each of these must be a COMPLETE response — a body, a
    // length, an end — because the client that gets it is waiting with no timeout of its own.
    for (Map<String, String> headers :
        List.of(
            Map.<String, String>of(),
            Map.of("Authorization", "Basic"),
            Map.of("Authorization", "Basic "),
            Map.of(
                "Authorization",
                "Basic "
                    + Base64.getEncoder().encodeToString(":".getBytes(StandardCharsets.UTF_8))),
            Map.of("Authorization", "Basic !!not-base64"))) {
      for (HttpMethod method : new HttpMethod[] {HttpMethod.GET, HttpMethod.POST}) {
        long start = System.currentTimeMillis();
        EdgeClient.Answer answer =
            client()
                .send(
                    method,
                    "registry.dev.acme.example.com",
                    "/token?service=registry.dev.acme.example.com",
                    method == HttpMethod.POST ? "grant_type=client_credentials" : null,
                    headers);
        assertEquals(401, answer.status(), method + " " + headers);
        assertTrue(
            answer.headers().get("www-authenticate").startsWith("Basic realm="),
            answer.headers().get("www-authenticate"));
        assertTrue(answer.body().contains("UNAUTHORIZED"), answer.body());
        assertTrue(
            System.currentTimeMillis() - start < 5_000, method + " " + headers + " was not prompt");
      }
    }
  }

  // --- helpers -----------------------------------------------------------------------------------

  /**
   * {@code qits.edge.auth.basic-cache-ttl-ms}, which StubGateways shrinks to a suite's patience.
   */
  private static long cacheTtlMs() {
    return ConfigProvider.getConfig().getValue("qits.edge.auth.basic-cache-ttl-ms", Long.class);
  }

  /** {@code qits.edge.auth.token-cache-ttl-ms}, which StubGateways shrinks the same way. */
  private static long tokenCacheTtlMs() {
    return ConfigProvider.getConfig().getValue("qits.edge.auth.token-cache-ttl-ms", Long.class);
  }

  /** The issuer the stub idp stamps: the one the edge derives from the suite's domain. */
  private static String issuer() {
    return StubGateways.ISSUER;
  }

  /** A token that opens every gated name this suite routes: the platform audience. */
  private static Map<String, String> token() {
    return bearer(TestTokens.valid(issuer(), List.of(StubGateways.PLATFORM_AUDIENCE)));
  }

  /**
   * A token naming one environment's application wire aliases and not the platform audience — so it
   * opens nothing.
   */
  private static Map<String, String> aliasToken(String environment) {
    return bearer(
        TestTokens.valid(
            issuer(),
            List.of(
                environment + "-qits-artifacts",
                environment + "-qits-mirror",
                environment + "-qits-githost")));
  }

  private static Map<String, String> bearer(String jwt) {
    return Map.of("Authorization", "Bearer " + jwt);
  }

  private static Map<String, String> basic(String id, String secret) {
    return Map.of(
        "Authorization",
        "Basic "
            + Base64.getEncoder()
                .encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8)));
  }
}
