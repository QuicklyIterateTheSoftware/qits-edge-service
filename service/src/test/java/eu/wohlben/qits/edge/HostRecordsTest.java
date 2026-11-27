package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.edge.acme.AddressRecords;
import eu.wohlben.qits.edge.acme.HetznerBearer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The host records reconciler (qits-2273, qits-2292) against a fake zone and a hand-built estate:
 * plain JUnit, because everything it decides is a function of what it reads, and none of it needs a
 * boot or a Hetzner.
 */
class HostRecordsTest {

  private static final String TUNNEL = "100.96.0.1";
  private static final String NODE_IP = "46.224.171.33";
  private static final String TARGET = "node.wohlben.eu.";

  /** A zone held in memory, every write recorded in order. */
  static class FakeZone implements AddressRecords {

    final Map<String, RrSet> rrsets = new TreeMap<>();
    final List<String> writes = new ArrayList<>();

    /** {@code exposure} null is a hand-made record, carrying some other label. */
    void put(String name, String type, int ttl, String exposure, String... values) {
      rrsets.put(
          name + "/" + type,
          new RrSet(
              name,
              type,
              ttl,
              exposure == null ? Map.of("someone", "by-hand") : Map.of(OWNER_LABEL, exposure),
              List.of(values)));
    }

    RrSet at(String name, String type) {
      return rrsets.get(name + "/" + type);
    }

    @Override
    public List<RrSet> owned() {
      return rrsets.values().stream()
          .filter(rrset -> rrset.owned() && TYPES.contains(rrset.type()))
          .toList();
    }

    @Override
    public Optional<RrSet> get(String name, String type) {
      return Optional.ofNullable(at(name, type));
    }

    @Override
    public void create(String name, String type, List<String> values, String exposure) {
      writes.add("create " + name + " " + type + " " + String.join(",", values));
      if (rrsets.keySet().stream().anyMatch(key -> key.startsWith(name + "/"))
          && (type.equals("CNAME") || rrsets.containsKey(name + "/CNAME"))) {
        throw new IllegalStateException("HTTP 422: a CNAME cannot stand beside another record");
      }
      put(name, type, TTL, exposure, values.toArray(String[]::new));
    }

    @Override
    public void setRecords(String name, String type, List<String> values) {
      writes.add("set " + name + " " + type + " " + String.join(",", values));
      RrSet old = at(name, type);
      rrsets.put(name + "/" + type, new RrSet(name, type, old.ttl(), old.labels(), values));
    }

    @Override
    public void changeTtl(String name, String type) {
      writes.add("ttl " + name + " " + type);
      RrSet old = at(name, type);
      rrsets.put(name + "/" + type, new RrSet(name, type, TTL, old.labels(), old.values()));
    }

    @Override
    public void setLabels(String name, String type, Map<String, String> labels) {
      writes.add("label " + name + " " + type + " " + labels.get(OWNER_LABEL));
      RrSet old = at(name, type);
      rrsets.put(name + "/" + type, new RrSet(name, type, old.ttl(), labels, old.values()));
    }

    @Override
    public void delete(String name, String type) {
      writes.add("delete " + name + " " + type);
      rrsets.remove(name + "/" + type);
    }
  }

  /** The estate a reconcile reads, every field settable by a test. */
  static final class Estate implements HostRecords.Estate {

    ExposureClass exposure = ExposureClass.PUBLIC;
    HetznerBearer bearer = () -> "not-a-real-token";
    Exception refusal;
    boolean authoritative = true;
    Optional<String> tunnel = Optional.of(TUNNEL);
    List<String> node = List.of();
    Map<String, Boolean> projects = new LinkedHashMap<>(Map.of("qits", false));
    final Map<String, Map<String, EdgeRoutes.Withdrawn>> withdrawn = new TreeMap<>();
    final Map<String, List<String>> hosts = new TreeMap<>();

    Estate withdraw(String environment, String application, String host, String exposure) {
      withdrawn
          .computeIfAbsent(environment, ignored -> new TreeMap<>())
          .put(application, new EdgeRoutes.Withdrawn(host, exposure));
      return this;
    }

    Estate serve(String environment, String... hosts) {
      this.hosts.put(environment, List.of(hosts));
      return this;
    }

    @Override
    public ExposureClass exposure() {
      return exposure;
    }

    @Override
    public HetznerBearer bearer() throws Exception {
      if (refusal != null) {
        throw refusal;
      }
      return bearer;
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
    public Collection<String> publicHosts(String environment) {
      return hosts.getOrDefault(environment, List.of());
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

    @Override
    public List<String> nodeAddress() {
      return node;
    }
  }

  private final FakeZone zone = new FakeZone();
  private final Estate estate = new Estate();
  private final List<String> zonesAskedFor = new ArrayList<>();

  private final HostRecords records =
      new HostRecords(
          estate,
          (token, domain) -> {
            zonesAskedFor.add(domain);
            return zone;
          },
          Duration.ofDays(1));

  private static HostRecords over(Estate estate, FakeZone zone) {
    return new HostRecords(estate, (token, domain) -> zone, Duration.ofDays(1));
  }

  // ---- the private hosts (qits-2273), unchanged ----

  @Test
  void createsTheMissingRecordsForEveryPrivateHost() throws Exception {
    estate
        .withdraw("dev", "qits-projects", "projects", "private")
        .withdraw("dev", "qits-mcp", "mcp", "private");

    records.reconcile();

    assertEquals(
        List.of("create mcp.qits A 100.96.0.1", "create projects.qits A 100.96.0.1"), zone.writes);
    assertEquals(List.of("wohlben.eu"), zonesAskedFor);
    assertTrue(zone.owned().stream().allMatch(rrset -> rrset.ttl() == AddressRecords.TTL));
    assertEquals(AddressRecords.PRIVATE, zone.at("mcp.qits", "A").exposure());

    zone.writes.clear();
    records.reconcile();
    assertEquals(List.of(), zone.writes, "a converged zone is left alone");
  }

  @Test
  void deletesTheStaleLabelledOnes() throws Exception {
    estate.withdraw("dev", "qits-projects", "projects", "private");
    zone.put("projects.qits", "A", 60, "private", TUNNEL);
    zone.put("retired.qits", "A", 60, "private", TUNNEL);

    records.reconcile();

    assertEquals(List.of("delete retired.qits A"), zone.writes);
  }

  @Test
  void leavesUnlabelledRrsetsAlone() throws Exception {
    zone.put("status.qits", "A", 300, null, "203.0.113.7");
    zone.put("_acme-challenge.qits", "TXT", 300, null, "\"digest\"");

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
    zone.put("projects.qits", "A", 300, null, "203.0.113.7");
    zone.put("mcp.qits", "CNAME", 300, null, "elsewhere.example.");
    zone.put("ci.qits", "TXT", 300, null, "\"not an address\"");

    records.reconcile();

    assertEquals(List.of("create ci.qits A 100.96.0.1"), zone.writes, "a TXT is no collision");
    assertEquals(List.of("203.0.113.7"), zone.at("projects.qits", "A").values());
  }

  @Test
  void withoutANodeAddressNothingIsWrittenBeforeTheProjectionIsAuthoritative() throws Exception {
    estate.authoritative = false;
    estate.withdraw("dev", "qits-projects", "projects", "private");
    zone.put("retired.qits", "A", 60, "private", TUNNEL);

    records.reconcile();

    assertEquals(List.of(), zone.writes);
    assertEquals(List.of(), zonesAskedFor, "the zone is not even read");
  }

  @Test
  void withNeitherATunnelNorANodeAddressTheZoneIsNotEvenRead() throws Exception {
    estate.tunnel = Optional.empty();
    estate.withdraw("dev", "qits-projects", "projects", "private");
    zone.put("retired.qits", "A", 60, "private", TUNNEL);

    records.reconcile();

    assertEquals(List.of(), zone.writes);
    assertEquals(List.of(), zonesAskedFor);
  }

  @Test
  void thePrivateEdgeIsInactive() throws Exception {
    estate.exposure = ExposureClass.PRIVATE;
    estate.node = List.of(NODE_IP);
    estate.withdraw("dev", "qits-stt", "stt", "public");
    zone.put("retired.qits", "A", 60, "private", TUNNEL);

    records.reconcile();

    assertEquals(List.of(), zone.writes);
    assertEquals(List.of(), zonesAskedFor);
  }

  @Test
  void anEdgeWithoutAHetznerTokenStillWritesThroughTheGateway() throws Exception {
    // qits-2617: the deployed edge holds no Hetzner token; its bearer is its own qits token and
    // qits-egress holds the Hetzner one. No token is no longer a reason to do nothing.
    HetznerBearer gateway = () -> "qits-jwt";
    estate.bearer = gateway;
    estate.withdraw("dev", "qits-projects", "projects", "private");
    List<HetznerBearer> handed = new ArrayList<>();
    HostRecords records =
        new HostRecords(
            estate,
            (bearer, domain) -> {
              handed.add(bearer);
              return zone;
            },
            Duration.ofDays(1));

    records.reconcile();

    assertEquals(List.of(gateway), handed);
    assertEquals(List.of("create projects.qits A 100.96.0.1"), zone.writes);
  }

  @Test
  void anUnreadableBearerIsARefusalAndWritesNothing() throws Exception {
    // An empty token file is refused in HetznerAccess; here it arrives as the estate throwing.
    estate.refusal = new IllegalStateException("The configured Hetzner token secret file is empty");
    estate.withdraw("dev", "qits-projects", "projects", "private");

    org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, records::reconcile);
    assertEquals(List.of(), zonesAskedFor);
    assertEquals(List.of(), zone.writes);
  }

  @Test
  void aChangedValueIsSetAndAWrongTtlChanged() throws Exception {
    estate
        .withdraw("dev", "qits-projects", "projects", "private")
        .withdraw("dev", "qits-mcp", "mcp", "private");
    zone.put("projects.qits", "A", 60, "private", "100.96.0.9");
    zone.put("mcp.qits", "A", 300, "private", TUNNEL);

    records.reconcile();

    assertEquals(List.of("ttl mcp.qits A", "set projects.qits A 100.96.0.1"), zone.writes);
    assertEquals(List.of(TUNNEL), zone.at("projects.qits", "A").values());
    assertEquals(60, zone.at("mcp.qits", "A").ttl());
  }

  @Test
  void aFailedListAbortsThePassWithNoWrites() {
    estate.node = List.of(NODE_IP);
    estate.serve("dev", "ci");
    estate.withdraw("dev", "qits-projects", "projects", "private");
    FakeZone blind =
        new FakeZone() {
          @Override
          public List<RrSet> owned() {
            throw new IllegalStateException(
                "Hetzner DNS GET zones/wohlben.eu/rrsets failed with HTTP 503: {}");
          }
        };
    blind.put("retired.qits", "A", 60, "private", TUNNEL);

    assertTrue(over(estate, blind).reconcileSafely(), "the run happened, and swallowed it");
    assertEquals(List.of(), blind.writes);
  }

  @Test
  void aFailedLookupAbortsThePassBeforeAnyWrite() {
    estate.node = List.of(NODE_IP);
    estate.serve("dev", "ci");
    FakeZone blind =
        new FakeZone() {
          @Override
          public Optional<RrSet> get(String name, String type) {
            throw new IllegalStateException("HTTP 503");
          }
        };
    blind.put("stale.qits", "CNAME", 60, "public", TARGET);
    estate.withdraw("dev", "qits-projects", "projects", "private");

    assertTrue(over(estate, blind).reconcileSafely());
    assertEquals(List.of(), blind.writes);
  }

  @Test
  void thePrivateNamesAreComposedPerProjectTierAndEnvironment() {
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
        HostRecords.privateHosts(
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

  // ---- the public names (qits-2292) ----

  @Test
  void theDesiredSetIsTheNodeTheApexAndACnamePerHostAndDoor() {
    Map<String, List<String>> hosts =
        Map.of("dev", List.of("ci", "idp", "landing"), "prod", List.of("ci"));
    Map<String, Boolean> projects = new LinkedHashMap<>();
    projects.put("qits", false);
    projects.put("acme", true);

    HostRecords.Plan plan =
        HostRecords.plan(
            "wohlben.eu",
            List.of("dev", "prod"),
            "dev",
            projects,
            environment -> hosts.getOrDefault(environment, List.of()),
            environment ->
                "dev".equals(environment)
                    ? Map.of("qits-projects", new EdgeRoutes.Withdrawn("projects", "private"))
                    : Map.of(),
            List.of(NODE_IP, "2a01:4f8:c17:1::1"),
            Optional.of(TUNNEL),
            ignored -> {});

    assertEquals(
        List.of(
            new HostRecords.Want("node", "A", List.of(NODE_IP), "public"),
            new HostRecords.Want("node", "AAAA", List.of("2a01:4f8:c17:1::1"), "public")),
        plan.node());
    assertEquals(
        List.of(
            new HostRecords.Want("@", "A", List.of(NODE_IP), "public"),
            new HostRecords.Want("@", "AAAA", List.of("2a01:4f8:c17:1::1"), "public")),
        plan.apex());
    // qits is env-less: its apps in the default environment only, its door, and `landing` IS the
    // door. acme has environments: a door per environment beside its own, and the apps under each.
    assertEquals(
        Set.of(
            "qits",
            "ci.qits",
            "idp.qits",
            "acme",
            "dev.acme",
            "prod.acme",
            "ci.dev.acme",
            "idp.dev.acme",
            "ci.prod.acme"),
        plan.cnames().stream().map(HostRecords.Want::name).collect(Collectors.toSet()));
    assertTrue(
        plan.cnames().stream()
            .allMatch(
                want ->
                    want.type().equals("CNAME")
                        && want.values().equals(List.of(TARGET))
                        && want.exposure().equals("public")));
    assertEquals(
        List.of(
            new HostRecords.Want("projects.dev.acme", "A", List.of(TUNNEL), "private"),
            new HostRecords.Want("projects.qits", "A", List.of(TUNNEL), "private")),
        plan.privates());
  }

  @Test
  void theDesiredSetHasACnamePerPlatformAppInEveryServedEnvironment() {
    Map<String, List<String>> table = Map.of("dev", List.of("ci", "registry"));
    Map<String, Boolean> projects = new LinkedHashMap<>();
    projects.put("qits", false);
    projects.put("acme", true);

    HostRecords.Plan plan =
        HostRecords.plan(
            "wohlben.eu",
            List.of("dev", "prod"),
            "dev",
            projects,
            environment -> HostRecords.publicHosts(table.getOrDefault(environment, List.of())),
            environment -> Map.of(),
            List.of(NODE_IP),
            Optional.empty(),
            ignored -> {});

    List<String> names = plan.cnames().stream().map(HostRecords.Want::name).toList();
    for (String app : List.of("registry", "githost", "idp")) {
      for (String name : List.of(app + ".qits", app + ".dev.acme", app + ".prod.acme")) {
        assertEquals(1, names.stream().filter(name::equals).count(), name + ", once");
      }
    }
    // qits-2744: mirror is not a platform label any more, so only the table can want its name.
    assertFalse(names.contains("mirror.qits"), "nothing publishes mirror here");
    assertTrue(names.contains("ci.qits"));
    assertFalse(names.contains("ci.prod.acme"), "the table serves ci in dev only");
  }

  @Test
  void theSeedPassWritesTheNodeTheApexAndThePlatformAppsAndDeletesNothing() throws Exception {
    estate.authoritative = false;
    estate.node = List.of(NODE_IP);
    estate.projects = new LinkedHashMap<>();
    estate.serve("dev", "ci");
    estate.withdraw("dev", "qits-projects", "projects", "private");
    zone.put("retired.qits", "CNAME", 60, "public", TARGET);
    zone.put("stale.qits", "A", 60, "private", TUNNEL);

    records.reconcile();

    // No table and no project yet: qits is composed anyway, the way the edge already routes it.
    assertEquals(
        List.of(
            "create node A " + NODE_IP,
            "create githost.qits CNAME " + TARGET,
            "create idp.qits CNAME " + TARGET,
            "create registry.qits CNAME " + TARGET,
            "create @ A " + NODE_IP),
        zone.writes);
    assertFalse(zone.writes.stream().anyMatch(write -> write.startsWith("delete ")));
    assertTrue(zone.rrsets.containsKey("retired.qits/CNAME"));
    assertTrue(zone.rrsets.containsKey("stale.qits/A"));

    zone.writes.clear();
    records.reconcile();
    assertEquals(List.of(), zone.writes, "a seeded zone is left alone");

    estate.authoritative = true;
    estate.projects = new LinkedHashMap<>(Map.of("qits", false));
    records.reconcile();
    assertTrue(zone.writes.contains("delete retired.qits CNAME"), "the full pass takes over");
  }

  @Test
  void theSeedPassSkipsACollidingUnlabelledRecordAndSwitchesNoType() throws Exception {
    estate.authoritative = false;
    estate.node = List.of(NODE_IP);
    zone.put("registry.qits", "A", 300, null, "203.0.113.7");
    zone.put("mirror.qits", "A", 60, "private", TUNNEL);

    records.reconcile();

    assertEquals(
        List.of(
            "create node A " + NODE_IP,
            "create githost.qits CNAME " + TARGET,
            "create idp.qits CNAME " + TARGET,
            "create @ A " + NODE_IP),
        zone.writes);
    assertEquals(List.of("203.0.113.7"), zone.at("registry.qits", "A").values());
    assertEquals(AddressRecords.PRIVATE, zone.at("mirror.qits", "A").exposure());
  }

  @Test
  void aProjectWithTheSlugNodeGetsNoNodeRecordAndNoCname() throws Exception {
    estate.node = List.of(NODE_IP);
    estate.projects = new LinkedHashMap<>(Map.of("qits", false, "node", false));
    estate.serve("dev", "ci");
    zone.put("node", "A", 60, "public", NODE_IP);

    HostRecords.Plan plan =
        HostRecords.plan(
            "wohlben.eu",
            List.of("dev"),
            "dev",
            estate.projects,
            estate::publicHosts,
            estate::withdrawn,
            estate.node,
            Optional.empty(),
            ignored -> {});
    assertEquals(List.of(), plan.node());
    assertFalse(plan.cnames().stream().anyMatch(want -> want.name().equals("node")));

    records.reconcile();

    assertEquals(List.of("create @ A " + NODE_IP), zone.writes, "the apex, and nothing else");
    assertTrue(zone.rrsets.containsKey("node/A"), "the reserved name is not deleted either");
  }

  @Test
  void aFreshZoneGetsTheNodeFirstThenTheCnamesThenTheApex() throws Exception {
    estate.node = List.of(NODE_IP);
    estate.serve("dev", "ci", "idp");
    estate.withdraw("dev", "qits-projects", "projects", "private");

    records.reconcile();

    assertEquals(
        List.of(
            "create node A " + NODE_IP,
            "create projects.qits A " + TUNNEL,
            "create ci.qits CNAME " + TARGET,
            "create idp.qits CNAME " + TARGET,
            "create qits CNAME " + TARGET,
            "create @ A " + NODE_IP),
        zone.writes);
    assertEquals(AddressRecords.PUBLIC, zone.at("ci.qits", "CNAME").exposure());

    zone.writes.clear();
    records.reconcile();
    assertEquals(List.of(), zone.writes, "a converged zone is left alone");
  }

  @Test
  void aNodeThatCannotBeWrittenGetsNoCnameWritten() throws Exception {
    estate.node = List.of(NODE_IP);
    estate.serve("dev", "ci");
    FakeZone refusing =
        new FakeZone() {
          @Override
          public void create(String name, String type, List<String> values, String exposure) {
            if (name.equals("node")) {
              writes.add("refused " + name);
              throw new IllegalStateException("Hetzner DNS POST ... failed with HTTP 422: {}");
            }
            super.create(name, type, values, exposure);
          }
        };

    over(estate, refusing).reconcile();

    assertEquals(List.of("refused node", "create @ A " + NODE_IP), refusing.writes);
  }

  @Test
  void deletesComeAfterEveryCreate() throws Exception {
    estate.node = List.of(NODE_IP);
    estate.serve("dev", "ci");
    zone.put("retired.qits", "CNAME", 60, "public", TARGET);
    zone.put("node", "AAAA", 60, "public", "2a01:4f8:c17:1::1");

    records.reconcile();

    assertEquals(
        List.of(
            "create node A " + NODE_IP,
            "create ci.qits CNAME " + TARGET,
            "create qits CNAME " + TARGET,
            "create @ A " + NODE_IP,
            "delete node AAAA",
            "delete retired.qits CNAME"),
        zone.writes);
  }

  @Test
  void anApplicationTurningPublicSwitchesItsRecordInOnePass() throws Exception {
    estate.node = List.of(NODE_IP);
    estate.serve("dev", "stt");
    zone.put("node", "A", 60, "public", NODE_IP);
    zone.put("@", "A", 60, "public", NODE_IP);
    zone.put("qits", "CNAME", 60, "public", TARGET);
    zone.put("stt.qits", "A", 60, "private", TUNNEL);

    records.reconcile();

    assertEquals(List.of("delete stt.qits A", "create stt.qits CNAME " + TARGET), zone.writes);
  }

  @Test
  void anApplicationTurningPrivateSwitchesItsRecordInOnePass() throws Exception {
    estate.node = List.of(NODE_IP);
    estate.withdraw("dev", "qits-stt", "stt", "private");
    zone.put("node", "A", 60, "public", NODE_IP);
    zone.put("@", "A", 60, "public", NODE_IP);
    zone.put("qits", "CNAME", 60, "public", TARGET);
    zone.put("stt.qits", "CNAME", 60, "public", TARGET);

    records.reconcile();

    assertEquals(List.of("delete stt.qits CNAME", "create stt.qits A " + TUNNEL), zone.writes);
  }

  @Test
  void anUnlabelledCnameAtAPublicNameIsSkipped() throws Exception {
    estate.node = List.of(NODE_IP);
    estate.serve("dev", "status");
    zone.put("status.qits", "CNAME", 300, null, "statuspage.example.");

    records.reconcile();

    assertFalse(zone.writes.stream().anyMatch(write -> write.contains("status.qits")));
    assertEquals(List.of("statuspage.example."), zone.at("status.qits", "CNAME").values());
  }

  @Test
  void withoutANodeAddressNoPublicRecordIsWrittenOrDeleted() throws Exception {
    estate.serve("dev", "ci");
    estate.withdraw("dev", "qits-projects", "projects", "private");
    zone.put("ci.qits", "CNAME", 60, "public", TARGET);
    zone.put("retired.qits", "CNAME", 60, "public", TARGET);
    zone.put("qits", "A", 300, null, NODE_IP);
    zone.put("*.qits", "A", 300, null, NODE_IP);

    records.reconcile();

    assertEquals(List.of("create projects.qits A " + TUNNEL), zone.writes);
    assertTrue(zone.rrsets.containsKey("retired.qits/CNAME"));
  }

  @Test
  void withoutATunnelAddressThePublicPartStillRunsAndThePrivateRecordsStay() throws Exception {
    estate.tunnel = Optional.empty();
    estate.node = List.of(NODE_IP);
    estate.serve("dev", "ci");
    estate.withdraw("dev", "qits-projects", "projects", "private");
    zone.put("projects.qits", "A", 60, "private", TUNNEL);

    records.reconcile();

    assertEquals(
        List.of(
            "create node A " + NODE_IP,
            "create ci.qits CNAME " + TARGET,
            "create qits CNAME " + TARGET,
            "create @ A " + NODE_IP),
        zone.writes);
    assertTrue(zone.rrsets.containsKey("projects.qits/A"));
  }

  // ---- the migration off the hand-made records (qits-2292, section 5) ----

  /** The live zone of 2026-11-17, give or take. */
  private void liveZone(FakeZone zone) {
    zone.put("@", "A", 300, null, NODE_IP);
    zone.put("*", "A", 300, null, NODE_IP);
    zone.put("*.dev", "A", 300, null, NODE_IP);
    zone.put("*.*", "A", 300, null, NODE_IP);
    zone.put("*.*.*", "A", 300, null, NODE_IP);
    zone.put("*.*.*.*", "A", 300, null, NODE_IP);
    zone.put("qits", "A", 300, null, NODE_IP);
    zone.put("*.qits", "A", 300, null, NODE_IP);
    zone.put("projects.qits", "A", 60, "private", TUNNEL);
    zone.put("_acme-challenge.qits", "TXT", 300, null, "\"digest\"");
  }

  @Test
  void theLiveZoneMigratesInOnePass() throws Exception {
    liveZone(zone);
    estate.node = List.of(NODE_IP);
    estate.serve("dev", "ci", "idp");
    estate.withdraw("dev", "qits-projects", "projects", "private");

    records.reconcile();

    assertEquals(
        List.of(
            "create node A " + NODE_IP,
            "create ci.qits CNAME " + TARGET,
            "create idp.qits CNAME " + TARGET,
            "delete qits A",
            "create qits CNAME " + TARGET,
            "label @ A public",
            "ttl @ A",
            "delete *.qits A"),
        zone.writes);
    for (String untouched : List.of("*", "*.dev", "*.*", "*.*.*", "*.*.*.*")) {
      assertFalse(zone.at(untouched, "A").owned(), untouched + " is the owner's to delete");
    }
    assertTrue(zone.rrsets.containsKey("_acme-challenge.qits/TXT"));
    assertEquals(List.of(NODE_IP), zone.at("@", "A").values());

    zone.writes.clear();
    records.reconcile();
    assertEquals(List.of(), zone.writes, "the migration is idempotent");
  }

  @Test
  void theHandMadeDoorAndItsWildcardWaitForEveryCnameBelow() throws Exception {
    estate.node = List.of(NODE_IP);
    estate.serve("dev", "ci", "idp");
    FakeZone refusing =
        new FakeZone() {
          @Override
          public void create(String name, String type, List<String> values, String exposure) {
            if (name.equals("idp.qits")) {
              writes.add("refused " + name);
              throw new IllegalStateException("HTTP 503");
            }
            super.create(name, type, values, exposure);
          }
        };
    liveZone(refusing);

    over(estate, refusing).reconcile();

    assertTrue(refusing.writes.contains("refused idp.qits"));
    assertFalse(refusing.writes.contains("delete qits A"));
    assertFalse(refusing.writes.contains("delete *.qits A"));
    assertFalse(refusing.at("qits", "A").owned(), "the hand-made door still answers");
    assertTrue(refusing.rrsets.containsKey("*.qits/A"));
  }

  @Test
  void ownedShadowsAreSwitchedOrDeleted() throws Exception {
    zone.put("*", "A", 300, null, NODE_IP);
    zone.put("@", "A", 60, "public", NODE_IP);
    zone.put("projects.qits", "A", 60, "private", TUNNEL);
    zone.put("qits", "A", 60, "private", NODE_IP);
    zone.put("*.qits", "A", 60, "private", NODE_IP);
    zone.put("acme", "A", 60, "private", NODE_IP);
    zone.put("*.acme", "A", 60, "private", NODE_IP);
    estate.node = List.of(NODE_IP);
    estate.projects = new LinkedHashMap<>(Map.of("qits", false, "acme", false));
    estate.serve("dev", "ci");
    estate.withdraw("dev", "qits-projects", "projects", "private");

    records.reconcile();

    // The door switches A -> CNAME back to back; the wildcard shadows go last.
    int deleteQits = zone.writes.indexOf("delete qits A");
    assertEquals("create qits CNAME " + TARGET, zone.writes.get(deleteQits + 1));
    int deleteAcme = zone.writes.indexOf("delete acme A");
    assertEquals("create acme CNAME " + TARGET, zone.writes.get(deleteAcme + 1));
    assertEquals(
        Set.of("delete *.acme A", "delete *.qits A"),
        Set.copyOf(zone.writes.subList(zone.writes.size() - 2, zone.writes.size())));
    assertTrue(zone.rrsets.containsKey("projects.qits/A"));
    assertFalse(zone.at("*", "A").owned());
  }

  @Test
  void withoutANodeAddressOwnedShadowsStay() throws Exception {
    zone.put("projects.qits", "A", 60, "private", TUNNEL);
    zone.put("qits", "A", 60, "private", NODE_IP);
    zone.put("*.qits", "A", 60, "private", NODE_IP);
    estate.withdraw("dev", "qits-projects", "projects", "private");

    records.reconcile();

    assertEquals(List.of(), zone.writes, "they may still be what answers below them");
  }

  @Test
  void aHandMadeApexWithAnotherAddressIsLeftAlone() throws Exception {
    zone.put("@", "A", 300, null, "203.0.113.7");
    estate.node = List.of(NODE_IP);

    records.reconcile();

    assertFalse(zone.writes.stream().anyMatch(write -> write.contains(" @ ")));
    assertEquals(List.of("203.0.113.7"), zone.at("@", "A").values());
    assertFalse(zone.at("@", "A").owned());
  }

  @Test
  void anIpv6NodeAddressAddsAnAaaaBesideTheHandMadeApexA() throws Exception {
    zone.put("@", "A", 60, null, NODE_IP);
    estate.node = List.of(NODE_IP, "2a01:4f8:c17:1::1");

    records.reconcile();

    assertTrue(zone.writes.contains("label @ A public"));
    assertTrue(zone.writes.contains("create @ AAAA 2a01:4f8:c17:1::1"));
    assertTrue(zone.writes.contains("create node AAAA 2a01:4f8:c17:1::1"));
  }

  @Test
  void theNodeAddressIsReadAsLiteralsOnly() {
    List<String> invalid = new ArrayList<>();

    assertEquals(
        List.of(NODE_IP, "2a01:4f8:c17:1::1"),
        HostRecords.nodeAddresses(
            List.of(" 46.224.171.33", "2A01:4f8:c17:1::1", "node.example.com", "", "46.224.171.33"),
            invalid::add));
    assertEquals(List.of("node.example.com"), invalid);
  }
}
