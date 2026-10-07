package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.SocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The per-origin count, the rule that turns it into a WARN, and the {@code /upstream-pools}
 * document — all without a socket or a boot. The live half, a real connection counted and
 * uncounted, is {@code UpstreamKeepAliveTest}'s; the route's gate is {@code EdgeRoutingTest}'s.
 *
 * <p>A pool of FOUR here rather than 64: the rule is about "at max", not about the number, and four
 * keeps every scenario readable.
 */
class UpstreamPoolsTest {

  private static final Upstream PROJECTS = new Upstream("dev-qits-projects", 8080);

  private static final Upstream CI = new Upstream("dev-qits-ci", 8080);

  private static UpstreamPools pools() {
    return new UpstreamPools(4, Upstream::toString);
  }

  private static void open(UpstreamPools pools, Upstream origin, int connections) {
    for (int i = 0; i < connections; i++) {
      pools.opened(origin);
    }
  }

  @Test
  void openCountsEveryConnectionPerOriginAndForgetsAnOriginThatDrained() {
    UpstreamPools pools = pools();
    open(pools, PROJECTS, 3);
    open(pools, CI, 1);
    pools.closed(CI);
    assertEquals(Map.of(PROJECTS, 3), pools.open(), "an origin at zero is not in the document");
  }

  @Test
  void aPoolFullAtOneCheckIsNotYetReported() {
    // It may have filled a second ago. "Stays full" needs a whole interval.
    UpstreamPools pools = pools();
    open(pools, PROJECTS, 4);
    assertEquals(List.of(), pools.check());
  }

  @Test
  void aPoolFullForAWholeIntervalIsReportedAndKeepsBeingReportedWhileItStaysFull() {
    UpstreamPools pools = pools();
    open(pools, PROJECTS, 4);
    open(pools, CI, 3);
    pools.check();
    assertEquals(List.of(PROJECTS), pools.check(), "full then, full now, nothing left between");
    assertEquals(List.of(PROJECTS), pools.check(), "and once a minute after that, until it drains");
  }

  @Test
  void aPoolThatDippedBelowMaxBetweenTwoFullChecksIsBusyRatherThanStuck() {
    // Full at both ticks, but a connection closed and another took its place in between: the
    // pool is turning over, which is exactly what a full-but-healthy burst looks like.
    UpstreamPools pools = pools();
    open(pools, PROJECTS, 4);
    pools.check();
    pools.closed(PROJECTS);
    pools.opened(PROJECTS);
    assertEquals(List.of(), pools.check());
    // From here it stays full with nothing closing, so the NEXT interval is a whole one.
    assertEquals(List.of(PROJECTS), pools.check());
  }

  @Test
  void aPoolThatDrainedIsNotReported() {
    UpstreamPools pools = pools();
    open(pools, PROJECTS, 4);
    pools.check();
    pools.closed(PROJECTS);
    assertEquals(List.of(), pools.check());
    assertEquals(List.of(), pools.check(), "below max is never stuck, however long it stays");
  }

  @Test
  void theOriginIsTheDialledNameAndPortLowerCased() {
    assertEquals(
        PROJECTS, UpstreamPools.origin(SocketAddress.inetSocketAddress(8080, "Dev-Qits-Projects")));
    assertNull(UpstreamPools.origin(null));
    assertNull(UpstreamPools.origin(SocketAddress.domainSocketAddress("/tmp/a.sock")));
  }

  @Test
  void anOriginNobodyClaimsIsReadAsTheWireAliasItIs() {
    Set<String> environments = Set.of("dev", "prod");
    assertEquals(
        new UpstreamPools.Owner("qits-projects", "dev"),
        UpstreamPools.Owner.guess(PROJECTS, environments));
    // Not an alias of any environment's: named by its host, still listed.
    assertEquals(
        new UpstreamPools.Owner("127.0.0.1", null),
        UpstreamPools.Owner.guess(new Upstream("127.0.0.1", 9000), environments));
    assertEquals(
        "qits-projects in dev (dev-qits-projects:8080)",
        new UpstreamPools.Owner("qits-projects", "dev").describe(PROJECTS));
  }

  @Test
  void theDocumentIsOneEntryPerOriginFullestFirst() {
    Map<Upstream, Integer> open = new LinkedHashMap<>();
    open.put(CI, 3);
    open.put(PROJECTS, 12);
    open.put(new Upstream("dev-qits-docs", 8080), 3);
    JsonArray document =
        UpstreamPoolsRoute.document(
            open, 64, origin -> UpstreamPools.Owner.guess(origin, Set.of("dev")));

    assertEquals(
        new JsonObject()
            .put("name", "qits-projects")
            .put("environment", "dev")
            .put("origin", "dev-qits-projects:8080")
            .put("open", 12)
            .put("max", 64),
        document.getJsonObject(0),
        "exactly the five fields, and max is the number the caller passed rather than a literal");
    // Equal counts keep one order between two reads: by origin.
    assertEquals(
        List.of("dev-qits-projects:8080", "dev-qits-ci:8080", "dev-qits-docs:8080"),
        document.stream().map(entry -> ((JsonObject) entry).getString("origin")).toList());
  }
}
