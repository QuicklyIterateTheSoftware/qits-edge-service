package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * <b>What the private edge asks qits-deployments</b> (epic qits-2174): its route table, fetched at
 * start ({@link RouteTableFetch}) — {@code GET /deployments/api/edge-routes/private} — built from
 * qits-deployments' golden master {@code getEdgeRoutes} in {@code two-edges-with-routes} and
 * committed as {@code pacts/qits-edge-service_qits-deployments-service.json}.
 *
 * <p>The row is driven through the real fetch loop against a pact mock server, and the table it
 * hands over is then held to the store's own checks ({@link RouteTables#problem}) as the private
 * edge would: the deployer's answer must be a table this edge accepts, not merely one it can read.
 * Here rather than in {@code consumer/} because {@link RouteTableFetch.Fetcher} is package-private.
 */
class DeploymentsConsumerPactTest {

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  static final GoldenMasters DEPLOYMENTS =
      GoldenMasters.of("qits-deployments-service", "qits-deployments");

  static final String TWO_EDGES_WITH_ROUTES = "two edges with routes";

  static final GoldenInteraction EDGE_ROUTES =
      GoldenInteraction.of(
              Trigger.operation("RouteTableFetch (the private edge, at start)"),
              TWO_EDGES_WITH_ROUTES,
              "getEdgeRoutes")
          .consumes(
              "edge",
              "version",
              "applications[].environment",
              "applications[].application",
              "applications[].exposure",
              "applications[].browserHost",
              "applications[].apiDocsPath",
              "applications[].endpoints[].path",
              "applications[].endpoints[].upstreamHost",
              "applications[].endpoints[].upstreamPort")
          // A live table carries every routed application, and an application several routes: the
          // recording's one of each is an example, not a length the deployer is held to.
          .anyLength("applications", "applications[].endpoints");

  static final ConsumerPact PACT = ConsumerPact.of("qits-edge-service", DEPLOYMENTS, EDGE_ROUTES);

  @Test
  void thePrivateEdgeFetchesATableItAccepts() {
    PACT.run(
        EDGE_ROUTES,
        (url, recorded) -> {
          AtomicReference<JsonObject> fetched = new AtomicReference<>();
          RouteTableFetch.Fetcher fetcher =
              new RouteTableFetch.Fetcher(
                  URI.create(url + RouteTableFetch.PATH),
                  () -> "a-qits-client-token",
                  fetched::set,
                  Duration.ofMillis(10),
                  Duration.ofMillis(10));
          fetcher.run();
          JsonObject table = fetched.get();
          assertNotNull(table, "the fetch must hand the answer over");
          assertEquals(1, fetcher.attempts(), "a 200 is fetched once");
          assertEquals(RouteTables.PRIVATE, table.getString("edge"));
          assertNull(
              RouteTables.problem(
                  table, RouteTables.PRIVATE, List.of("dev"), ExposureClass.PRIVATE, Map.of()),
              "the private edge must accept the deployer's table");
        });
  }

  @Test
  void theCommittedPactIsWhatTheRowsWrite() {
    PACT.compareOrWritePactFile();
  }

  @Test
  void everyInteractionCarriesBothReferences() {
    PACT.assertEveryInteractionCarriesBothReferences();
    assertEquals(1, PACT.recorded().size(), "the row is recorded: " + PACT.unrecorded());
  }
}
