package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The per-origin count, the leases, the rule that turns them into a WARN, and the {@code
 * /upstream-pools} document — all without a socket or a boot. The live half, a real connection
 * counted and leased and released, is {@code UpstreamKeepAliveTest}'s; the route's gate and the
 * choice of pool are {@code EdgeRoutingTest}'s.
 *
 * <p>A pool of FOUR here rather than 256: the rule is about "at max", not about the number, and
 * four keeps every scenario readable. The clock is a fake one the test moves by hand, which is the
 * only way to say "thirty seconds at max" in less than thirty seconds.
 */
class UpstreamPoolsTest {

  private static final Upstream PROJECTS = new Upstream("dev-qits-projects", 8080);

  private static final Upstream CI = new Upstream("dev-qits-ci", 8080);

  /** Nanoseconds, moved only by {@link #at}. */
  private final AtomicLong now = new AtomicLong();

  private UpstreamPools pools() {
    return new UpstreamPools(UpstreamPools.STREAM, 4, Upstream::toString, now::get);
  }

  /** Move the fake clock to this many seconds after the pools were built. */
  private void at(double seconds) {
    now.set((long) (seconds * 1e9));
  }

  private static void open(UpstreamPools pools, Upstream origin, int connections) {
    for (int i = 0; i < connections; i++) {
      pools.opened(origin);
    }
  }

  private static UpstreamLease lease(String path, String workspaceId) {
    return new UpstreamLease(
        "GET", path, "10.0.0.7", "agent/1", workspaceId, "4bf92f3577b34da6a3ce929d0e0e4736");
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
  void aPoolAtMaxForLessThanHalfTheIntervalIsNotReported() {
    // A burst that touched max for twenty seconds is a pool doing its job.
    UpstreamPools pools = pools();
    open(pools, PROJECTS, 4);
    at(20);
    pools.closed(PROJECTS);
    at(60);
    assertEquals(List.of(), pools.check());
  }

  @Test
  void aPoolThatChurnsAtMaxIsReportedAlthoughItDippedEveryFewSeconds() {
    // The regression this rule exists for. A pool of streams is never full at two instants with
    // nothing closing in between — streams come and go — so the old "no dip between two full
    // checks" rule never fired while qits-projects' pool sat at max for hours. Time at max does
    // not care how many times the count brushed max-1 on the way.
    UpstreamPools pools = pools();
    open(pools, PROJECTS, 4);
    for (int second = 5; second < 60; second += 5) {
      at(second);
      pools.closed(PROJECTS);
      at(second + 0.5);
      pools.opened(PROJECTS);
    }
    at(60);
    // 60 s minus eleven half-second dips: 54.5 s at max.
    assertEquals(List.of(PROJECTS), pools.check());
  }

  @Test
  void aPoolThatStaysAtMaxIsReportedEveryIntervalUntilItDrains() {
    UpstreamPools pools = pools();
    open(pools, PROJECTS, 4);
    open(pools, CI, 3);
    at(60);
    assertEquals(List.of(PROJECTS), pools.check(), "a whole interval at max");
    at(120);
    assertEquals(
        List.of(PROJECTS),
        pools.check(),
        "a stretch at max still running is carried into the next interval, not lost at a check");
    at(121);
    pools.closed(PROJECTS);
    at(180);
    assertEquals(List.of(), pools.check(), "one second at max in this interval: not reported");
  }

  @Test
  void theTimeAtMaxIsResetAtEachCheck() {
    // Twenty seconds in each of two intervals is forty seconds, but no single interval was under
    // pressure, so nothing is reported — every check starts the count again.
    UpstreamPools pools = pools();
    open(pools, PROJECTS, 4);
    at(20);
    pools.closed(PROJECTS);
    at(60);
    assertEquals(List.of(), pools.check());
    at(70);
    pools.opened(PROJECTS);
    at(90);
    pools.closed(PROJECTS);
    at(120);
    assertEquals(List.of(), pools.check());
  }

  @Test
  void anAcquisitionThatWaitedOverFiveSecondsIsReportedForThatIntervalOnly() {
    // The pool never reached max here at all: a wait is pressure on its own — a queue behind a
    // full pool, or an origin that does not answer its SYN.
    UpstreamPools pools = pools();
    open(pools, CI, 1);
    pools.waited(PROJECTS, TimeUnit.MILLISECONDS.toNanos(UpstreamPools.SLOW_ACQUISITION_MS));
    pools.waited(CI, TimeUnit.MILLISECONDS.toNanos(200));
    at(60);
    assertEquals(List.of(), pools.check(), "exactly five seconds is not over five seconds");

    pools.waited(
        new Upstream("Dev-Qits-Projects", 8080),
        TimeUnit.MILLISECONDS.toNanos(UpstreamPools.SLOW_ACQUISITION_MS + 1));
    at(120);
    assertEquals(
        List.of(PROJECTS),
        pools.check(),
        "one slow wait, filed under the origin as a connection spells it");
    at(180);
    assertEquals(List.of(), pools.check(), "and forgotten at the next check");
  }

  @Test
  void aLeaseIsListedOldestFirstWithItsAgeAndAnIdleConnectionHasNone() {
    UpstreamPools pools = pools();
    open(pools, PROJECTS, 3);
    pools.lease("a", PROJECTS, lease("/projects/mcp", "ws-1"));
    at(10);
    pools.lease("b", PROJECTS, lease("/projects/events", null));
    at(12.5);

    List<UpstreamPools.Snapshot> snapshot = pools.snapshot();
    assertEquals(1, snapshot.size());
    UpstreamPools.Snapshot projects = snapshot.get(0);
    assertEquals(UpstreamPools.STREAM, projects.pool());
    assertEquals(3, projects.open(), "three open, two leased: one idle connection holds a slot");
    assertEquals(4, projects.max());
    assertEquals(
        List.of(
            new UpstreamPools.Holder(lease("/projects/mcp", "ws-1"), 12_500),
            new UpstreamPools.Holder(lease("/projects/events", null), 2_500)),
        projects.held());
  }

  @Test
  void aReleaseEndsOnlyItsOwnLease() {
    // A connection given back to the pool can be granted to the next request before the previous
    // exchange's end listener has run. That late release must not clear the newcomer's lease.
    UpstreamPools pools = pools();
    open(pools, PROJECTS, 1);
    UpstreamPools.Held first = pools.lease("conn", PROJECTS, lease("/first", null));
    pools.lease("conn", PROJECTS, lease("/second", null));
    pools.release("conn", first);
    assertEquals(
        List.of("/second"),
        pools.snapshot().get(0).held().stream().map(holder -> holder.lease().path()).toList());
  }

  @Test
  void theWarnNamesTheFiveOldestLeasesAndCountsTheRest() {
    UpstreamPools pools = pools();
    for (int i = 0; i < 7; i++) {
      at(i);
      pools.lease("conn-" + i, PROJECTS, lease("/s" + i, "ws-" + i));
    }
    at(100);
    String oldest = pools.oldest(PROJECTS, now.get());
    assertTrue(
        oldest.startsWith(
            "GET /s0 100.0 s client=10.0.0.7 workspace=ws-0"
                + " trace=4bf92f3577b34da6a3ce929d0e0e4736; GET /s1 99.0 s"),
        oldest);
    assertTrue(oldest.contains("GET /s4 96.0 s"), oldest);
    assertTrue(!oldest.contains("/s5"), "five, oldest first: " + oldest);
    assertTrue(oldest.endsWith("; and 2 more"), oldest);
    assertEquals("none", pools.oldest(CI, now.get()));
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
  void theDocumentIsOneEntryPerPoolAndOriginFullestFirstWithItsLeases() {
    UpstreamLease mcp =
        new UpstreamLease("GET", "/projects/mcp", "10.0.0.7", "agent/1", "ws-1", null);
    List<UpstreamPools.Snapshot> snapshots =
        List.of(
            new UpstreamPools.Snapshot(UpstreamPools.ORDINARY, CI, 3, 256, List.of()),
            new UpstreamPools.Snapshot(
                UpstreamPools.STREAM,
                PROJECTS,
                12,
                256,
                List.of(new UpstreamPools.Holder(mcp, 3_600_000))),
            new UpstreamPools.Snapshot(UpstreamPools.ORDINARY, PROJECTS, 3, 256, List.of()),
            new UpstreamPools.Snapshot(
                UpstreamPools.ORDINARY, new Upstream("dev-qits-docs", 8080), 3, 256, List.of()));
    JsonArray document =
        UpstreamPoolsRoute.document(
            snapshots, origin -> UpstreamPools.Owner.guess(origin, Set.of("dev")));

    assertEquals(
        new JsonObject()
            .put("name", "qits-projects")
            .put("environment", "dev")
            .put("origin", "dev-qits-projects:8080")
            .put("pool", "stream")
            .put("open", 12)
            .put("max", 256)
            .put(
                "held",
                new JsonArray()
                    .add(
                        new JsonObject()
                            .put("method", "GET")
                            .put("path", "/projects/mcp")
                            .put("ageMs", 3_600_000L)
                            .put("client", "10.0.0.7")
                            .put("userAgent", "agent/1")
                            .put("workspaceId", "ws-1")
                            .put("traceId", null))),
        document.getJsonObject(0),
        "exactly these fields, a null written as null, and max is the pool's own");
    assertTrue(
        document.getJsonObject(0).getJsonArray("held").getJsonObject(0).containsKey("traceId"),
        "a missing value is a null, never an absent key: a reader iterates one shape");
    // Equal counts keep one order between two reads: by origin, then by pool.
    assertEquals(
        List.of(
            "stream dev-qits-projects:8080",
            "ordinary dev-qits-ci:8080",
            "ordinary dev-qits-docs:8080",
            "ordinary dev-qits-projects:8080"),
        document.stream()
            .map(
                entry ->
                    ((JsonObject) entry).getString("pool")
                        + " "
                        + ((JsonObject) entry).getString("origin"))
            .toList());
  }
}
