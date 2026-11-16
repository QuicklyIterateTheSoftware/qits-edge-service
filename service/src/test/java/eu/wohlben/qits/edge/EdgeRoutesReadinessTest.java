package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

/**
 * When each variant is ready (epic qits-2174), asserted without a boot: {@code EdgeRoutingTest}
 * runs the public profile only, and the private edge is the one that waits for a table.
 */
class EdgeRoutesReadinessTest {

  @Test
  void nothingIsReadyBeforeTheBootHasLooked() {
    assertFalse(routes(ExposureClass.PUBLIC).ready());
    assertFalse(routes(ExposureClass.PRIVATE).ready());
  }

  @Test
  void thePrivateEdgeIsReadyOnlyOnceATableIsLoaded() {
    EdgeRoutes routes = routes(ExposureClass.PRIVATE);
    routes.serveNothing();
    assertFalse(routes.ready(), "an empty database waits for the fetch at start");
    assertFalse(routes.tableLoaded());

    routes.serve(7, table("private"));
    assertTrue(routes.ready());
    assertTrue(routes.tableLoaded());
    assertEquals(7L, routes.version());
    assertEquals("qits-ci", routes.serviceHost("dev", "ci").application());
  }

  @Test
  void thePublicEdgeIsReadyWithNoTableEverReceived() {
    // The deployer pushes the public edge's first table only once the edge reports healthy, so
    // waiting for that push would be waiting forever: PlatformApps alone is served meanwhile.
    EdgeRoutes routes = routes(ExposureClass.PUBLIC);
    routes.serveNothing();
    assertTrue(routes.ready());
    assertFalse(routes.tableLoaded(), "but nothing may be withdrawn or deleted on its word");
    assertNull(routes.version());

    routes.serve(8, table("public"));
    assertTrue(routes.tableLoaded());
    assertEquals(8L, routes.version());
  }

  @Test
  void onlyThePublicEdgeReadsAWithdrawal() {
    EdgeRoutes publicEdge = routes(ExposureClass.PUBLIC);
    publicEdge.serve(1, table("public"));
    assertEquals(
        new EdgeRoutes.Withdrawn("stt", "private"),
        publicEdge.withdrawnHosts("dev").get("qits-stt"));

    EdgeRoutes privateEdge = routes(ExposureClass.PRIVATE);
    privateEdge.serve(1, table("private"));
    assertTrue(privateEdge.withdrawnHosts("dev").isEmpty());
  }

  @Test
  void theServedViewIsTheTable() {
    EdgeRoutes routes = routes(ExposureClass.PUBLIC);
    routes.serve(1, table("public"));
    // Longest prefix first, the primary route is the first declared, and every placement carries
    // the application's host and primary path.
    assertEquals("/ci/hooks", routes.resolve("dev", "/ci/hooks/push").path());
    assertEquals("/ci", routes.resolve("dev", "/ci/api").path());
    assertEquals("/ci", routes.primaryPath("dev", "qits-ci"));
    assertEquals("/ci/q/openapi", routes.apiDocs("dev").get("qits-ci"));
    assertEquals(
        new EdgeRoutes.NavigationPlacement(
            "qits-ci", "services.details", "CI", 2, "ci", "/ci", null),
        routes.navigation("dev").get(0));
    assertTrue(routes.landingEnvironments().isEmpty());
  }

  private static JsonObject table(String edge) {
    return new JsonObject()
        .put("edge", edge)
        .put("version", 1)
        .put(
            "applications",
            new JsonArray()
                .add(
                    new JsonObject()
                        .put("environment", "dev")
                        .put("application", "qits-ci")
                        .put("exposure", edge)
                        .put("browserHost", "CI")
                        .put("apiDocsPath", "/ci/q/openapi")
                        .put(
                            "endpoints",
                            new JsonArray()
                                .add(endpoint("/ci", 8080))
                                .add(endpoint("/ci/hooks", 8081)))
                        .put(
                            "navigation",
                            new JsonArray()
                                .add(
                                    new JsonObject()
                                        .put("slot", "services.details")
                                        .put("label", "CI")
                                        .put("position", 2)))))
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

  private static JsonObject endpoint(String path, int port) {
    return new JsonObject()
        .put("path", path)
        .put("upstreamHost", "dev-qits-ci")
        .put("upstreamPort", port);
  }

  private static EdgeRoutes routes(ExposureClass exposure) {
    EdgeRoutes routes = new EdgeRoutes();
    routes.config =
        (EdgeConfig)
            Proxy.newProxyInstance(
                EdgeConfig.class.getClassLoader(),
                new Class<?>[] {EdgeConfig.class},
                (proxy, method, args) -> {
                  if ("exposure".equals(method.getName())) {
                    return exposure;
                  }
                  throw new UnsupportedOperationException(method.getName());
                });
    return routes;
  }
}
