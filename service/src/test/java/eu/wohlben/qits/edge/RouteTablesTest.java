package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * {@link RouteTables#problem}: every refusal a whole table can earn, without a boot (qits-2174).
 */
class RouteTablesTest {

  private static final List<String> ENVIRONMENTS = List.of("prod", "dev");

  private static String problem(JsonObject table, ExposureClass exposure) {
    return RouteTables.problem(
        table, RouteTables.edgeOf(exposure), ENVIRONMENTS, exposure, Map.of());
  }

  private static String problem(JsonObject table) {
    return problem(table, ExposureClass.PUBLIC);
  }

  private static JsonObject table() {
    return RouteTableRouteTest.table(7);
  }

  private static JsonObject changed(Consumer<JsonObject> application) {
    JsonObject table = table();
    application.accept(table.getJsonArray("applications").getJsonObject(0));
    return table;
  }

  /** A second application in dev, publishing `/second` on `second`. */
  private static JsonObject withSecond(Consumer<JsonObject> second) {
    JsonObject table = table();
    JsonObject copy = table.getJsonArray("applications").getJsonObject(0).copy();
    copy.put("application", "qits-second").put("browserHost", "second");
    copy.put("apiDocsPath", "/second/q/openapi");
    copy.getJsonArray("endpoints").getJsonObject(0).put("path", "/second");
    second.accept(copy);
    table.getJsonArray("applications").add(copy);
    return table;
  }

  private static void refused(String problem, String expected) {
    assertTrue(
        problem != null && problem.contains(expected), "expected `" + expected + "`: " + problem);
  }

  @Test
  void aValidTableHasNoProblem() {
    assertNull(problem(table()));
    assertNull(problem(withSecond(second -> {})));
    assertNull(problem(table().put("applications", new JsonArray())), "an empty table is a table");
    assertNull(problem(changed(app -> app.remove("browserHost"))), "a host is optional");
    assertNull(problem(changed(app -> app.remove("navigation"))));
  }

  @Test
  void theTableMustBeForThisEdge() {
    refused(problem(table(), ExposureClass.PRIVATE), "is for the `public` edge");
  }

  @Test
  void theVersionIsAPositiveInteger() {
    refused(problem(table().put("version", 0)), "`version`");
    refused(problem(table().put("version", "7")), "`version`");
    refused(problem(table().put("version", 1.5)), "`version`");
    refused(problem(table().putNull("version")), "`version`");
  }

  @Test
  void aHostIsADnsLabelAndNoEnvironment() {
    refused(problem(changed(app -> app.put("browserHost", "ci.example"))), "DNS label");
    refused(problem(changed(app -> app.put("browserHost", "prod"))), "is an environment name");
    refused(problem(changed(app -> app.put("browserHost", "DEV"))), "is an environment name");
  }

  @Test
  void aPlatformLabelOnThePublicEdgeMustBeThatApplication() {
    refused(
        problem(changed(app -> app.put("browserHost", "registry"))),
        "is a platform application vhost");
    // The platform's own address: qits-artifacts publishing `registry` is the same service.
    assertNull(
        problem(
            changed(
                app -> {
                  app.put("browserHost", "registry");
                  app.getJsonArray("endpoints")
                      .getJsonObject(0)
                      .put("upstreamHost", "dev-qits-artifacts")
                      .put("upstreamPort", 8080);
                })));
    // A private edge serves no platform label, so there it is an ordinary name.
    JsonObject privateTable = changed(app -> app.put("browserHost", "registry"));
    privateTable.put("edge", "private");
    assertNull(problem(privateTable, ExposureClass.PRIVATE));
  }

  @Test
  void anApiDocsPathSitsUnderOneOfTheApplicationsOwnRoutes() {
    assertNull(problem(changed(app -> app.put("apiDocsPath", "/ci"))), "the route itself is under");
    assertNull(problem(changed(app -> app.remove("apiDocsPath"))), "api docs are optional");
    refused(
        problem(changed(app -> app.put("apiDocsPath", "/docs/q/swagger-ui"))),
        "sits under none of its own routes");
    refused(
        problem(changed(app -> app.put("apiDocsPath", "/cix/q/openapi"))),
        "sits under none of its own routes");
  }

  @Test
  void aPathOrAHostBelongsToOneApplicationPerEnvironment() {
    refused(
        problem(
            withSecond(
                second -> second.getJsonArray("endpoints").getJsonObject(0).put("path", "/ci"))),
        "route /ci is published by both qits-ci and qits-second");
    refused(
        problem(withSecond(second -> second.put("browserHost", "ci"))),
        "host `ci` is published by both");
    refused(
        problem(table().put("applications", twoLandings())), "host `landing` is published by both");
    // Another environment is another namespace.
    assertNull(
        problem(
            withSecond(
                second -> {
                  second.put("environment", "prod").put("browserHost", "ci");
                  second.put("apiDocsPath", "/ci/q/openapi");
                  second.getJsonArray("endpoints").getJsonObject(0).put("path", "/ci");
                })));
    refused(problem(withSecond(second -> second.put("application", "qits-ci"))), "twice");
  }

  private static JsonArray twoLandings() {
    JsonObject first = table().getJsonArray("applications").getJsonObject(0).copy();
    first.put("browserHost", "landing");
    JsonObject second =
        first.copy().put("application", "qits-other-landing").put("apiDocsPath", "/other");
    second.getJsonArray("endpoints").getJsonObject(0).put("path", "/other");
    return new JsonArray().add(first).add(second);
  }

  @Test
  void endpointsAndNavigationAreChecked() {
    refused(
        problem(changed(app -> app.put("endpoints", new JsonArray()))), "publishes no endpoint");
    refused(
        problem(changed(app -> app.getJsonArray("endpoints").getJsonObject(0).put("path", "ci"))),
        "not absolute");
    refused(
        problem(
            changed(app -> app.getJsonArray("endpoints").getJsonObject(0).put("upstreamPort", 0))),
        "no upstream");
    refused(
        problem(
            changed(app -> app.getJsonArray("navigation").getJsonObject(0).put("slot", "nope"))),
        "is not a navigation slot");
    refused(
        problem(
            changed(
                app ->
                    app.getJsonArray("navigation")
                        .add(app.getJsonArray("navigation").getJsonObject(0).copy()))),
        "twice");
    refused(problem(changed(app -> app.put("exposure", "secret"))), "is not an exposure");
    refused(problem(changed(app -> app.put("endpoints", "nope"))), "wrong type");
  }

  @Test
  void withdrawnEntriesAreChecked() {
    JsonObject table = table();
    table.getJsonArray("withdrawn").getJsonObject(0).put("host", "not a label");
    refused(problem(table), "cannot be a DNS label");
    JsonObject missing = table();
    missing.getJsonArray("withdrawn").getJsonObject(0).remove("application");
    refused(problem(missing), "`application` is missing");
  }

  @Test
  void theHeldDocumentHasAFixedShape() {
    JsonObject none = new RouteTables.Held("private", null, null).document();
    assertEquals(List.of("edge", "version", "receivedAt"), List.copyOf(none.fieldNames()));
    assertEquals("private", none.getString("edge"));
    assertNull(none.getValue("version"));
  }
}
