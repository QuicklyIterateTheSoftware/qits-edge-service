package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.edge.acme.AddressRecords;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The private host records reconciler (qits-2273) against a fake zone and a hand-built estate:
 * plain JUnit, because everything it decides is a function of what it reads, and none of it needs a
 * boot or a Hetzner.
 */
class PrivateHostRecordsTest {

  private static final String TUNNEL = "100.96.0.1";

  /** A zone held in memory, every write recorded in order. */
  static class FakeZone implements AddressRecords {

    final Map<String, RrSet> rrsets = new TreeMap<>();
    final List<String> writes = new ArrayList<>();

    void put(String name, String type, int ttl, boolean owned, String... values) {
      rrsets.put(
          name + "/" + type,
          new RrSet(
              name,
              type,
              ttl,
              owned ? Map.of(OWNER_LABEL, OWNER_VALUE) : Map.of("someone", "by-hand"),
              List.of(values)));
    }

    @Override
    public List<RrSet> owned() {
      return rrsets.values().stream()
          .filter(rrset -> rrset.owned() && rrset.type().equals("A"))
          .toList();
    }

    @Override
    public List<RrSet> named(String name) {
      return rrsets.values().stream().filter(rrset -> rrset.name().equals(name)).toList();
    }

    @Override
    public void create(String name, String value) {
      writes.add("create " + name + " " + value);
      put(name, "A", TTL, true, value);
    }

    @Override
    public void setRecords(String name, String value) {
      writes.add("set " + name + " " + value);
      RrSet old = rrsets.get(name + "/A");
      rrsets.put(name + "/A", new RrSet(name, "A", old.ttl(), old.labels(), List.of(value)));
    }

    @Override
    public void changeTtl(String name) {
      writes.add("ttl " + name);
      RrSet old = rrsets.get(name + "/A");
      rrsets.put(name + "/A", new RrSet(name, "A", TTL, old.labels(), old.values()));
    }

    @Override
    public void delete(String name) {
      writes.add("delete " + name);
      rrsets.remove(name + "/A");
    }
  }

  /** The estate a reconcile reads, every field settable by a test. */
  static final class Estate implements PrivateHostRecords.Estate {

    ExposureClass exposure = ExposureClass.PUBLIC;
    Optional<String> token = Optional.of("not-a-real-token");
    boolean authoritative = true;
    Optional<String> tunnel = Optional.of(TUNNEL);
    Map<String, Boolean> projects = new LinkedHashMap<>(Map.of("qits", false));
    final Map<String, Map<String, EdgeRoutes.Withdrawn>> withdrawn = new TreeMap<>();

    Estate withdraw(String environment, String application, String host, String exposure) {
      withdrawn
          .computeIfAbsent(environment, ignored -> new TreeMap<>())
          .put(application, new EdgeRoutes.Withdrawn(host, exposure));
      return this;
    }

    @Override
    public ExposureClass exposure() {
      return exposure;
    }

    @Override
    public Optional<String> token() {
      return token;
    }

    @Override
    public String domain() {
      return "wohlben.eu";
    }

    @Override
    public List<String> environments() {
      return List.of("dev");
    }

    @Override
    public String defaultEnvironment() {
      return "dev";
    }

    @Override
    public Map<String, Boolean> projects() {
      return projects;
    }

    @Override
    public Map<String, EdgeRoutes.Withdrawn> withdrawn(String environment) {
      return withdrawn.getOrDefault(environment, Map.of());
    }

    @Override
    public boolean authoritative() {
      return authoritative;
    }

    @Override
    public Optional<String> tunnelAddress() {
      return tunnel;
    }
  }

  private final FakeZone zone = new FakeZone();
  private final Estate estate = new Estate();
  private final List<String> zonesAskedFor = new ArrayList<>();

  private final PrivateHostRecords records =
      new PrivateHostRecords(
          estate,
          (token, domain) -> {
            zonesAskedFor.add(domain);
            return zone;
          },
          Duration.ofDays(1));

  @Test
  void createsTheMissingRecordsForEveryPrivateHost() throws Exception {
    estate
        .withdraw("dev", "qits-projects", "projects", "private")
        .withdraw("dev", "qits-mcp", "mcp", "private");

    records.reconcile();

    assertEquals(
        List.of("create mcp.qits 100.96.0.1", "create projects.qits 100.96.0.1"), zone.writes);
    assertEquals(List.of("wohlben.eu"), zonesAskedFor);
    assertTrue(zone.owned().stream().allMatch(rrset -> rrset.ttl() == AddressRecords.TTL));

    zone.writes.clear();
    records.reconcile();
    assertEquals(List.of(), zone.writes, "a converged zone is left alone");
  }

  @Test
  void deletesTheStaleLabelledOnes() throws Exception {
    estate.withdraw("dev", "qits-projects", "projects", "private");
    zone.put("projects.qits", "A", 60, true, TUNNEL);
    zone.put("retired.qits", "A", 60, true, TUNNEL);

    records.reconcile();

    assertEquals(List.of("delete retired.qits"), zone.writes);
  }

  @Test
  void leavesUnlabelledRrsetsAlone() throws Exception {
    // A hand-made A record no private host wants, and an unlabelled A beside a label-less TXT.
    zone.put("status.qits", "A", 300, false, "203.0.113.7");
    zone.put("_acme-challenge.qits", "TXT", 300, false, "\"digest\"");

    records.reconcile();

    assertEquals(List.of(), zone.writes);
    assertEquals(2, zone.rrsets.size(), "both are still there");
  }

  @Test
  void aNameThatHoldsAnUnlabelledAOrCnameIsSkipped() throws Exception {
    estate
        .withdraw("dev", "qits-projects", "projects", "private")
        .withdraw("dev", "qits-mcp", "mcp", "private")
        .withdraw("dev", "qits-ci", "ci", "private");
    zone.put("projects.qits", "A", 300, false, "203.0.113.7");
    zone.put("mcp.qits", "CNAME", 300, false, "elsewhere.example.");
    zone.put("ci.qits", "TXT", 300, false, "\"not an address\"");

    records.reconcile();

    assertEquals(List.of("create ci.qits 100.96.0.1"), zone.writes, "a TXT is no collision");
    assertEquals(List.of("203.0.113.7"), zone.rrsets.get("projects.qits/A").values());
  }

  @Test
  void nothingIsWrittenOrDeletedBeforeTheProjectionIsAuthoritative() throws Exception {
    estate.authoritative = false;
    estate.withdraw("dev", "qits-projects", "projects", "private");
    zone.put("retired.qits", "A", 60, true, TUNNEL);

    records.reconcile();

    assertEquals(List.of(), zone.writes);
    assertEquals(List.of(), zonesAskedFor, "the zone is not even read");
  }

  @Test
  void withNoTunnelAddressNothingIsWrittenAndNothingDeleted() throws Exception {
    estate.tunnel = Optional.empty();
    estate.withdraw("dev", "qits-projects", "projects", "private");
    zone.put("retired.qits", "A", 60, true, TUNNEL);

    records.reconcile();

    assertEquals(List.of(), zone.writes);
    assertEquals(List.of(), zonesAskedFor);
  }

  @Test
  void thePrivateEdgeIsInactive() throws Exception {
    estate.exposure = ExposureClass.PRIVATE;
    estate.withdraw("dev", "qits-stt", "stt", "public");
    zone.put("retired.qits", "A", 60, true, TUNNEL);

    records.reconcile();

    assertEquals(List.of(), zone.writes);
    assertEquals(List.of(), zonesAskedFor);
  }

  @Test
  void anEdgeWithoutAHetznerTokenIsInactive() throws Exception {
    estate.token = Optional.empty();
    estate.withdraw("dev", "qits-projects", "projects", "private");

    records.reconcile();

    assertEquals(List.of(), zonesAskedFor);
  }

  @Test
  void aChangedValueIsSetAndAWrongTtlChanged() throws Exception {
    estate
        .withdraw("dev", "qits-projects", "projects", "private")
        .withdraw("dev", "qits-mcp", "mcp", "private");
    zone.put("projects.qits", "A", 60, true, "100.96.0.9");
    zone.put("mcp.qits", "A", 300, true, TUNNEL);

    records.reconcile();

    assertEquals(List.of("ttl mcp.qits", "set projects.qits 100.96.0.1"), zone.writes);
    assertEquals(List.of(TUNNEL), zone.rrsets.get("projects.qits/A").values());
    assertEquals(60, zone.rrsets.get("mcp.qits/A").ttl());
  }

  @Test
  void theBreakGlassClassDeletesEveryOwnedRecordAndNothingElse() throws Exception {
    estate.exposure = ExposureClass.ALL;
    zone.put("projects.qits", "A", 60, true, TUNNEL);
    zone.put("status.qits", "A", 300, false, "203.0.113.7");

    records.reconcile();

    assertEquals(List.of("delete projects.qits"), zone.writes);
    assertTrue(zone.rrsets.containsKey("status.qits/A"));
  }

  @Test
  void aFailedWriteIsRetriedOnTheNextReconcileAndNeverThrown() {
    estate.withdraw("dev", "qits-projects", "projects", "private");
    PrivateHostRecords failing =
        new PrivateHostRecords(
            estate,
            (token, domain) ->
                new FakeZone() {
                  @Override
                  public List<RrSet> owned() {
                    throw new IllegalStateException(
                        "Hetzner DNS GET zones/wohlben.eu/rrsets failed with HTTP 503: {}");
                  }
                },
            Duration.ofDays(1));

    assertTrue(failing.reconcileSafely(), "the run happened, and swallowed its failure");
  }

  @Test
  void theNamesAreComposedPerProjectTierAndEnvironment() {
    Map<String, Map<String, EdgeRoutes.Withdrawn>> withdrawn =
        Map.of(
            "dev",
            Map.of(
                "qits-ci", new EdgeRoutes.Withdrawn("ci", "private"),
                "qits-stt", new EdgeRoutes.Withdrawn("stt", "public"),
                "qits-hostless", new EdgeRoutes.Withdrawn(null, "private")),
            "prod",
            Map.of("qits-ci", new EdgeRoutes.Withdrawn("ci", "private")));
    Map<String, Boolean> projects = new LinkedHashMap<>();
    projects.put("qits", false);
    projects.put("acme", true);
    List<String> outside = new ArrayList<>();

    Map<String, String> desired =
        PrivateHostRecords.desired(
            "wohlben.eu",
            List.of("dev", "prod"),
            "dev",
            projects,
            environment -> withdrawn.getOrDefault(environment, Map.of()),
            TUNNEL,
            outside::add);

    // An env-less project serves its apps in the default environment only; an env-supporting one
    // in each environment, under its env label. A public withdrawal and a hostless one get nothing.
    assertEquals(Map.of("ci.qits", TUNNEL, "ci.dev.acme", TUNNEL, "ci.prod.acme", TUNNEL), desired);
    assertEquals(List.of(), outside);
  }
}
