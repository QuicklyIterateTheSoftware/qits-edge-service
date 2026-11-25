package eu.wohlben.qits.edge;

import eu.wohlben.qits.edge.acme.AddressRecords;
import eu.wohlben.qits.edge.acme.HetznerAddressRecords;
import eu.wohlben.qits.edge.acme.HetznerBearer;
import eu.wohlben.qits.edge.wireguard.PrivateEdgeAddressProjection;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jboss.logging.Logger;

/**
 * Writes every name this platform answers into its public DNS zone (qits-2292), and the private
 * ones beside them (qits-2273). Before qits-2292 the public names resolved only because a person
 * had typed wildcards ({@code @}, {@code *}, {@code *.dev}, {@code *.*} ... and, since qits-2280,
 * {@code qits} and {@code *.qits}); a fresh zone resolved nothing, a made-up name got a TLS
 * handshake and a 404, and mixing exact private names with those wildcards turned their parents
 * into empty non-terminals (RFC 4592) — the qits-2273 and qits-2280 outages, and the "shadow"
 * records that existed only to paper over them. Now every host has an exact record, nothing here
 * writes a wildcard, and an unknown name is NXDOMAIN.
 *
 * <p><b>What is desired</b> — see {@link #plan}:
 *
 * <ul>
 *   <li><b>The node</b>, {@code node.<domain>}: {@code A} with every IPv4 of {@code
 *       qits.edge.dns.node-address} ({@link EdgeConfig.PublicDns#nodeAddress}), {@code AAAA} with
 *       every IPv6, when any is given. A project with the slug {@code node} would make its door a
 *       CNAME to itself, so then no node record is written (nor any CNAME) and a WARN says why.
 *   <li><b>The apex</b>, {@code @}: the same {@code A}/{@code AAAA}, because a zone apex cannot
 *       hold a CNAME.
 *   <li><b>One {@code CNAME} to {@code node.<domain>.} per public name</b>: every host the served
 *       route table publishes ({@link EdgeRoutes#applicationHosts}) and every {@link PlatformApps}
 *       label, composed for every served environment and every known project the way the navigation
 *       document composes them ({@link EnvironmentAuthority#hostOrigin}, {@code landing} being the
 *       door itself); every project door {@code <project>.<domain>}, and {@code
 *       <env>.<project>.<domain>} for each served environment of a project that has environments.
 *   <li><b>One {@code A} per private host</b>, unchanged since qits-2273: every {@code private}
 *       withdrawal ({@link EdgeRoutes#withdrawnHosts}) composed the same way, to the newest
 *       announced tunnel address ({@link PrivateEdgeAddressProjection#newest}).
 * </ul>
 *
 * <p>The two halves are independent. Without a node address the PUBLIC half writes and deletes
 * nothing, and without a tunnel address the PRIVATE half does nothing — each said once at INFO;
 * with neither the zone is not even read.
 *
 * <p><b>What it touches.</b> Only RRSets labelled {@code qits-exposure} ({@link
 * AddressRecords#OWNER_LABEL}): {@code public} for the node, the apex and every CNAME, {@code
 * private} for a tunnel {@code A}. A name that already holds an UNLABELLED record of a conflicting
 * type — a record a person made — is skipped with one WARN. The migration off the hand-made records
 * is the only exception (see {@link #apply}): the apex is adopted when it already says what the
 * edge would, a hand-made {@code A} at a name that is now a CNAME (the {@code qits} door) is
 * switched, and a hand-made {@code *.<project>} wildcard under a project door is deleted — each
 * only once everything below it has its own record. {@code *}, {@code *.dev}, {@code *.*} and every
 * other unlabelled record are never touched; one INFO per boot names the remaining root wildcards
 * as no longer needed, and the owner decides.
 *
 * <p><b>Who writes.</b> The PUBLIC edge, independent of whether ACME is enabled, through the same
 * base URL and bearer its DNS-01 orders use ({@link HetznerAccess}): the qits-egress gateway with
 * the edge's own qits token, or — the seed edge only — api.hetzner.cloud with the Hetzner token
 * (qits-2617). The private edge projects nothing, so it is inactive by construction, said once at
 * INFO.
 *
 * <p><b>Nothing is deleted before a route table is loaded and the project projection is
 * authoritative.</b> A public edge that never received a table routes {@link PlatformApps} and
 * knows nothing of the other hosts, and a project projection behind its head may not have a project
 * yet; deleting a record then would be a wrong answer published to the internet. Until both are up
 * a <b>seed pass</b> (qits-2293) writes only what code knows — the node, the apex and the {@link
 * PlatformApps} CNAMEs — and deletes nothing, switches no type and migrates nothing: a fresh
 * bootstrap waits for {@code registry.qits.<domain>} before the first deploy, and no deploy would
 * ever push the table that names it.
 *
 * <p><b>Triggers</b>: {@link #requestReconcile()} — from {@link RouteTables} when it serves a table
 * (the cached one at boot, and every newer one stored), from the address listener, and from the
 * project bootstrap once it is authoritative — and the {@link #scheduledReconcile 10-minute
 * backstop}. A Hetzner failure is a WARN with its status and body and is retried on the next
 * trigger; nothing here ever throws into routing or event consumption. Hetzner allows 3600 requests
 * an hour per token — the gateway spends the same token for every caller — so the owned set is
 * listed once per pass and a name is looked up only when it is missing from it.
 */
@ApplicationScoped
public class HostRecords {

  private static final Logger LOG = Logger.getLogger(HostRecords.class);

  /**
   * How long a request waits for the frames behind it. A redeploy moves several applications within
   * seconds, and an epoch replay delivers every frame at once; one window covers either. A constant
   * rather than a key: there is nothing for a deployment to tune (no new setting, qits-2273).
   */
  static final Duration DEBOUNCE = Duration.ofSeconds(5);

  /** The withdrawal exposure whose hosts get a record; {@link ExposureClass#declared} spells it. */
  static final String PRIVATE = "private";

  /** The node's fixed label: {@code node.<domain>}, what every CNAME points at (qits-2292). */
  static final String NODE = "node";

  /** The zone apex, as Hetzner spells it. */
  static final String APEX = "@";

  /**
   * The hand-made root wildcards the migration leaves to the owner, named once per boot when they
   * are still there: {@code *}, {@code *.<env>} for every served environment, and these.
   */
  static final List<String> DEEP_WILDCARDS = List.of("*.*", "*.*.*", "*.*.*.*");

  private static final Pattern IPV4 =
      Pattern.compile(
          "((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");

  private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-fA-F:.]+");

  /**
   * Everything a reconcile reads, behind one seam so it can be pinned without a boot. {@link
   * #bearer()} may throw: an unreadable or empty secret file is a refusal.
   */
  interface Estate {

    ExposureClass exposure();

    /** The bearer for this pass — see {@link HetznerAccess#bearer()}. */
    HetznerBearer bearer() throws Exception;

    /** The zone, {@code QITS_DOMAIN} normalised — the one {@code EdgeCertificateManager} uses. */
    String domain();

    List<String> environments();

    String defaultEnvironment();

    /** Slug to whether that project has environments — {@link EdgeProjects#projects()}. */
    Map<String, Boolean> projects();

    /**
     * The public host labels served in {@code environment}: the route table's ({@link
     * EdgeRoutes#applicationHosts}) and {@link PlatformApps}', which this edge answers from code.
     */
    Collection<String> publicHosts(String environment);

    Map<String, EdgeRoutes.Withdrawn> withdrawn(String environment);

    boolean authoritative();

    Optional<String> tunnelAddress();

    /** {@code qits.edge.dns.node-address} as stated, unparsed; empty when none is. */
    List<String> nodeAddress();
  }

  /**
   * One RRSet this edge wants.
   *
   * @param name relative to the zone
   * @param exposure the ownership label's value it carries
   */
  record Want(String name, String type, List<String> values, String exposure) {}

  /**
   * What one pass converges on, in write order: the node first, the private hosts, the CNAMEs once
   * the node is in place, the apex.
   *
   * @param doors every project slug — the doors under which a hand-made {@code *.<project>} may be
   *     left from before qits-2292
   * @param reserved names that are neither written nor deleted: {@code node} while a project claims
   *     it
   */
  record Plan(
      List<Want> node,
      List<Want> privates,
      List<Want> cnames,
      List<Want> apex,
      Set<String> doors,
      Set<String> reserved,
      boolean publicActive,
      boolean privateActive) {

    Stream<Want> wants() {
      return Stream.of(node, privates, cnames, apex).flatMap(List::stream);
    }

    Set<String> names() {
      Set<String> names = new TreeSet<>(reserved);
      wants().forEach(want -> names.add(want.name()));
      return names;
    }
  }

  private final Estate estate;
  private final BiFunction<HetznerBearer, String, AddressRecords> zones;
  private final Duration debounce;
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicBoolean pending = new AtomicBoolean();

  /** The reason last logged per topic for doing (part of) nothing, so each is said once. */
  private final Map<String, String> notes = new ConcurrentHashMap<>();

  /** Names last reported as colliding with an unlabelled RRSet, so each is warned about once. */
  private final Set<String> collisions = ConcurrentHashMap.newKeySet();

  /** Node address values already reported as no literal. */
  private final Set<String> invalidAddresses = ConcurrentHashMap.newKeySet();

  /** {@code *.<project>} wildcards found absent or deleted this boot, never looked up again. */
  private final Set<String> wildcardsGone = ConcurrentHashMap.newKeySet();

  /** Whether the leftover root wildcards were named this boot. */
  private final AtomicBoolean rootWildcardsReported = new AtomicBoolean();

  @Inject
  HostRecords(
      EdgeConfig config,
      HetznerAccess hetzner,
      EdgeRoutes routes,
      EdgeProjects projects,
      ProjectSansBootstrap projectSans,
      PrivateEdgeAddressProjection addresses) {
    this(
        new Estate() {
          @Override
          public ExposureClass exposure() {
            return config.exposure();
          }

          @Override
          public HetznerBearer bearer() throws Exception {
            return hetzner.bearer();
          }

          @Override
          public String domain() {
            return EdgeRouter.domain(config.domain());
          }

          @Override
          public List<String> environments() {
            return config.environments();
          }

          @Override
          public String defaultEnvironment() {
            return config.defaultEnvironment();
          }

          @Override
          public Map<String, Boolean> projects() {
            return projects.projects();
          }

          @Override
          public Collection<String> publicHosts(String environment) {
            return HostRecords.publicHosts(
                routes.applicationHosts(environment).values().stream()
                    .map(EdgeRoutes.ServiceHost::host)
                    .toList());
          }

          @Override
          public Map<String, EdgeRoutes.Withdrawn> withdrawn(String environment) {
            return routes.withdrawnHosts(environment);
          }

          @Override
          public boolean authoritative() {
            return routes.tableLoaded() && projectSans.authoritative();
          }

          @Override
          public Optional<String> tunnelAddress() {
            return addresses.newest();
          }

          @Override
          public List<String> nodeAddress() {
            return config.dns().nodeAddress().orElse(List.of());
          }
        },
        (bearer, zone) -> new HetznerAddressRecords(hetzner.api(), bearer, zone),
        DEBOUNCE);
  }

  /** For a suite: the estate, the zone client per (bearer, zone), and the debounce window. */
  HostRecords(
      Estate estate, BiFunction<HetznerBearer, String, AddressRecords> zones, Duration debounce) {
    this.estate = estate;
    this.zones = zones;
    this.debounce = debounce;
  }

  /**
   * One request at boot, which is what says at INFO why an edge is inactive; on a public edge it is
   * a seed pass until the projections are authoritative, and the bootstraps ask again then.
   */
  void start(@Observes StartupEvent ignored) {
    requestReconcile();
  }

  /**
   * Asks for a reconcile after {@link #DEBOUNCE}, never inline — the caller is the event funnel's
   * delivery thread, or a bootstrap's — exactly as {@code EdgeCertificateManager#requestReconcile}
   * does. Because it runs after a window rather than inside the caller, the projection write that
   * asked for it has committed by the time the desired set is read. At most one request is
   * outstanding; one that loses to a run in flight re-arms one window later.
   */
  public void requestReconcile() {
    if (!pending.compareAndSet(false, true)) {
      return;
    }
    schedule(debounce);
  }

  private void schedule(Duration delay) {
    CompletableFuture.runAsync(
        () -> {
          pending.set(false);
          if (reconcileSafely() || !pending.compareAndSet(false, true)) {
            return;
          }
          schedule(reArmDelay());
        },
        CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS));
  }

  private Duration reArmDelay() {
    return debounce.compareTo(EdgeCertificateManager.RE_ARM_FLOOR) < 0
        ? EdgeCertificateManager.RE_ARM_FLOOR
        : debounce;
  }

  /** The backstop: a missed trigger, a failed call or a hand edit converges within ten minutes. */
  @Scheduled(every = "10m", concurrentExecution = ConcurrentExecution.SKIP)
  void scheduledReconcile() {
    reconcileSafely();
  }

  /**
   * @return false only when another run held the guard, which a debounced request must not treat as
   *     done
   */
  boolean reconcileSafely() {
    if (!running.compareAndSet(false, true)) {
      return false;
    }
    try {
      reconcile();
    } catch (Exception failure) {
      LOG.warnf(
          "host records were not reconciled, retried on the next trigger: %s",
          failure.getMessage());
    } finally {
      running.set(false);
    }
    return true;
  }

  /** One pass: decide whether to act at all, then converge the owned RRSets on the plan. */
  void reconcile() throws Exception {
    if (estate.exposure() != ExposureClass.PUBLIC) {
      note(
          "edge",
          "host records are inactive: this is the "
              + estate.exposure().name().toLowerCase(Locale.ROOT)
              + " edge, and only the public edge writes them");
      return;
    }
    String domain = estate.domain();
    if (domain == null || domain.isEmpty()) {
      note("edge", "host records are inactive: this deployment states no domain (QITS_DOMAIN)");
      return;
    }
    notes.remove("edge");
    if (!estate.authoritative()) {
      seed(estate.bearer(), domain);
      return;
    }
    notes.remove("seed");
    Optional<String> tunnel = estate.tunnelAddress();
    if (tunnel.isEmpty()) {
      note(
          "private",
          "host records: no private edge has announced a tunnel address yet; no private host"
              + " record is written and none deleted");
    } else {
      notes.remove("private");
    }
    List<String> node = nodeAddresses(estate.nodeAddress(), this::warnInvalid);
    if (node.isEmpty()) {
      note(
          "public",
          "host records: no node address is configured (qits.edge.dns.node-address,"
              + " QITS_EDGE_DNS_NODE_ADDRESS); no public record is written and none deleted");
    } else {
      notes.remove("public");
    }
    if (tunnel.isEmpty() && node.isEmpty()) {
      return;
    }
    Map<String, Boolean> projects = estate.projects();
    if (!node.isEmpty() && projects.containsKey(NODE)) {
      note(
          "reserved",
          "host records: a project has the slug '"
              + NODE
              + "', which is the node's own name; no node record and no CNAME is written while it"
              + " exists, because its door would be a CNAME to itself");
    } else {
      notes.remove("reserved");
    }
    Plan plan =
        plan(
            domain,
            estate.environments(),
            estate.defaultEnvironment(),
            projects,
            estate::publicHosts,
            estate::withdrawn,
            node,
            tunnel,
            outside ->
                LOG.warnf(
                    "host %s is outside the zone %s; no record is written for it",
                    outside, domain));
    AddressRecords zone = zones.apply(estate.bearer(), domain);
    apply(zone, plan, false);
    if (plan.publicActive()) {
      reportRootWildcards(zone);
    }
  }

  /**
   * The seed pass (qits-2293): before a route table and the project log head, only the names code
   * knows — {@link #seedPlan} — are created or set. Nothing is deleted, no type switched and no
   * hand-made record migrated, because what else the zone should hold is not known yet; the full
   * pass takes over once both gates are up. The private half waits: its hosts come from the table.
   */
  private void seed(HetznerBearer bearer, String domain) throws Exception {
    List<String> node = nodeAddresses(estate.nodeAddress(), this::warnInvalid);
    if (node.isEmpty()) {
      // Not note(): a wait is a window, not a state worth a line, and the table store and the
      // project bootstrap ask again.
      LOG.debug("host records wait for a route table and the project log head");
      return;
    }
    note(
        "seed",
        "host records: no route table or project log head yet; a seed pass writes only the node,"
            + " the apex and the platform applications' names "
            + new TreeSet<>(PlatformApps.labels())
            + ", and deletes nothing");
    Plan plan =
        seedPlan(
            domain,
            estate.environments(),
            estate.defaultEnvironment(),
            estate.projects(),
            node,
            outside ->
                LOG.warnf(
                    "host %s is outside the zone %s; no record is written for it",
                    outside, domain));
    apply(zones.apply(bearer, domain), plan, true);
  }

  /**
   * Converges the owned RRSets on {@code plan}.
   *
   * <p><b>Order is the safety.</b> Every lookup comes before any write, so a pass that cannot list
   * or read the zone writes and deletes nothing. Then the node record, the private hosts, the
   * CNAMEs — only once the node is in place, deepest name first — and the apex. Deletes come only
   * after every create and set, and a stale record is one whose (name, type) nothing wants.
   *
   * <p><b>A type switch at one name</b> (an owned shadow {@code A} at {@code qits} that is now a
   * door, an application that turned from private to public or back) is a delete and a create back
   * to back in the same pass, logged: Hetzner refuses a CNAME beside an A at one name, and the
   * zone's negative TTL is an hour, so the gap must never be split across passes. A failed create
   * after the delete is retried on the next trigger.
   *
   * <p><b>The migration</b> (qits-2292) rides in the same pass and is idempotent. Owned shadows are
   * simply no longer wanted: {@code *.qits} is deleted, {@code qits} switched to its CNAME. An
   * unlabelled {@code A} at a name that is now a CNAME — the hand-made {@code qits} of qits-2280 —
   * is switched, and a hand-made {@code *.<project>} wildcard under a project door deleted, each
   * only once every desired name below it is in place; the same gate holds an owned wildcard or an
   * owned record with names below it, since it may be what still answers them. The hand-made apex
   * (and node) is adopted by setting its label when its values already are the node's, and left
   * with a WARN when they are not.
   *
   * <p>A {@code seed} pass makes only the creates and sets: no delete, no type switch (an owned
   * record of another type, or a hand-made {@code A} at a CNAME's name, waits for the full pass),
   * and no wildcard is read.
   */
  private void apply(AddressRecords zone, Plan plan, boolean seed) throws Exception {
    // ---- reads: all of them, before any write ----
    Map<String, Map<String, AddressRecords.RrSet>> actual = new TreeMap<>();
    for (AddressRecords.RrSet rrset : zone.owned()) {
      // The label selector is the zone's filter; this is ours. An RRSet that came back without the
      // label — whatever the selector did — is not one this edge may touch.
      String type = rrset.type().toUpperCase(Locale.ROOT);
      if (rrset.owned() && AddressRecords.TYPES.contains(type)) {
        actual
            .computeIfAbsent(rrset.name().toLowerCase(Locale.ROOT), ignored -> new TreeMap<>())
            .put(type, rrset);
      }
    }
    // The hand-made records in each missing want's way, keyed name/type; one lookup per (name,
    // type) per pass, and none at all for a name the owned list already answers.
    Map<String, Optional<AddressRecords.RrSet>> looked = new HashMap<>();
    Map<String, List<AddressRecords.RrSet>> handMade = new HashMap<>();
    for (Want want : plan.wants().toList()) {
      Map<String, AddressRecords.RrSet> there = actual.getOrDefault(want.name(), Map.of());
      if (there.containsKey(want.type())) {
        continue;
      }
      List<AddressRecords.RrSet> found = new ArrayList<>();
      for (String type : conflicting(want.type(), true)) {
        if (there.containsKey(type)) {
          continue;
        }
        Optional<AddressRecords.RrSet> rrset = looked.get(want.name() + "/" + type);
        if (rrset == null) {
          rrset = zone.get(want.name(), type).filter(r -> unlabelled(r, want.name(), type));
          looked.put(want.name() + "/" + type, rrset);
        }
        rrset.ifPresent(found::add);
      }
      handMade.put(want.name() + "/" + want.type(), found);
    }
    Map<String, AddressRecords.RrSet> doorWildcards = new TreeMap<>();
    if (plan.publicActive() && !seed) {
      for (String door : plan.doors()) {
        String wildcard = "*." + door;
        if (wildcardsGone.contains(wildcard)) {
          continue;
        }
        Optional<AddressRecords.RrSet> found =
            zone.get(wildcard, "A").filter(rrset -> unlabelled(rrset, wildcard, "A"));
        if (found.isPresent()) {
          doorWildcards.put(wildcard, found.orElseThrow());
        } else {
          wildcardsGone.add(wildcard);
        }
      }
    }

    // ---- writes, in order ----
    Pass pass = new Pass(zone, actual, handMade, seed);
    boolean nodeInPlace = !plan.node().isEmpty();
    for (Want want : plan.node()) {
      nodeInPlace &= pass.put(want, plan) == Outcome.IN_PLACE;
    }
    for (Want want : plan.privates()) {
      pass.put(want, plan);
    }
    if (nodeInPlace) {
      List<Want> deepestFirst = new ArrayList<>(plan.cnames());
      deepestFirst.sort(
          Comparator.comparingInt((Want want) -> -depth(want.name())).thenComparing(Want::name));
      for (Want want : deepestFirst) {
        pass.put(want, plan);
      }
    } else if (!plan.cnames().isEmpty() && !plan.node().isEmpty()) {
      LOG.warnf(
          "host records: node record %s.<domain> is not in place; no CNAME is written this pass",
          NODE);
    }
    for (Want want : plan.apex()) {
      pass.put(want, plan);
    }

    if (seed) {
      pass.report();
      return;
    }

    // ---- deletes, after every create and set ----
    for (Map.Entry<String, AddressRecords.RrSet> wildcard : doorWildcards.entrySet()) {
      String base = wildcard.getKey().substring(2);
      if (!nodeInPlace || !pass.allInPlaceBelow(base, plan)) {
        LOG.debugf(
            "host records: the hand-made %s waits until every name under %s has its own record",
            wildcard.getKey(), base);
        continue;
      }
      if (pass.delete(wildcard.getKey(), "A", "hand-made ")) {
        wildcardsGone.add(wildcard.getKey());
      }
    }
    Set<String> wantedNames = plan.names();
    for (Map.Entry<String, Map<String, AddressRecords.RrSet>> entry : actual.entrySet()) {
      String name = entry.getKey();
      for (AddressRecords.RrSet rrset : List.copyOf(entry.getValue().values())) {
        String type = rrset.type().toUpperCase(Locale.ROOT);
        if (pass.wanted(name, type, plan) || plan.reserved().contains(name)) {
          continue;
        }
        if (wantedNames.contains(name) && pass.conflictsWithWant(name, type, plan)) {
          // A type switch this pass did not make — the node was not in place, or the switch's
          // delete failed: the old record still answers, so it stays for the next pass.
          continue;
        }
        String base = name.startsWith("*.") ? name.substring(2) : name;
        boolean covering =
            name.startsWith("*.")
                || Stream.concat(actual.keySet().stream(), wantedNames.stream())
                    .anyMatch(other -> other.endsWith("." + name));
        if (covering) {
          // A former shadow, or anything else that may still be what answers below it.
          if (!plan.publicActive() || !nodeInPlace || !pass.allInPlaceBelow(base, plan)) {
            continue;
          }
        } else if (AddressRecords.PUBLIC.equals(rrset.exposure())
            ? !plan.publicActive()
            : !plan.privateActive()) {
          continue;
        }
        pass.delete(name, type, "");
      }
    }
    collisions.retainAll(wantedNames);
    pass.report();
  }

  /** What one write left at its name. */
  enum Outcome {
    /** Written, unchanged or adopted: an owned record answers. */
    IN_PLACE,
    /** A hand-made record answers, which this edge leaves alone. */
    HAND_MADE,
    /** Not written; retried on the next trigger. */
    FAILED
  }

  /** One pass's writes and what they did, in the order they were made. */
  private final class Pass {

    final AddressRecords zone;
    final Map<String, Map<String, AddressRecords.RrSet>> actual;
    final Map<String, List<AddressRecords.RrSet>> handMade;
    final boolean seed;
    final Map<String, Outcome> outcomes = new HashMap<>();
    final List<String> created = new ArrayList<>();
    final List<String> set = new ArrayList<>();
    final List<String> labelled = new ArrayList<>();
    final List<String> switched = new ArrayList<>();
    final List<String> deleted = new ArrayList<>();

    Pass(
        AddressRecords zone,
        Map<String, Map<String, AddressRecords.RrSet>> actual,
        Map<String, List<AddressRecords.RrSet>> handMade,
        boolean seed) {
      this.zone = zone;
      this.actual = actual;
      this.handMade = handMade;
      this.seed = seed;
    }

    Outcome put(Want want, Plan plan) {
      Outcome outcome = write(want, plan);
      // A name with two types (the node's A and AAAA) is in place only when both are.
      outcomes.merge(want.name(), outcome, (a, b) -> a == Outcome.FAILED ? a : b);
      return outcome;
    }

    private Outcome write(Want want, Plan plan) {
      String name = want.name();
      String type = want.type();
      Map<String, AddressRecords.RrSet> there = actual.getOrDefault(name, Map.of());
      try {
        AddressRecords.RrSet existing = there.get(type);
        if (existing != null) {
          update(want, existing);
          return Outcome.IN_PLACE;
        }
        List<AddressRecords.RrSet> unlabelled = handMade.getOrDefault(name + "/" + type, List.of());
        if (!unlabelled.isEmpty()) {
          return handMade(want, unlabelled, plan);
        }
        List<String> old = new ArrayList<>();
        for (String other : conflicting(type, false)) {
          if (there.containsKey(other)) {
            old.add(other);
          }
        }
        if (seed && !old.isEmpty()) {
          LOG.debugf(
              "host record %s %s waits for the full pass: switching it from %s is not a seed write",
              name, type, String.join("+", old));
          return Outcome.HAND_MADE;
        }
        for (String other : old) {
          zone.delete(name, other);
          there.remove(other);
        }
        zone.create(name, type, want.values(), want.exposure());
        if (old.isEmpty()) {
          created.add(name + "/" + type);
        } else {
          switched.add(name + "/" + String.join("+", old) + "->" + type);
          LOG.infof(
              "host record %s switched from %s to %s: deleted and re-created back to back",
              name, String.join("+", old), type);
        }
        return Outcome.IN_PLACE;
      } catch (Exception failure) {
        LOG.warnf(
            "host record %s %s was not written, retried on the next trigger: %s",
            name, type, failure.getMessage());
        return Outcome.FAILED;
      }
    }

    private void update(Want want, AddressRecords.RrSet existing) throws Exception {
      boolean changed = false;
      if (!want.exposure().equals(existing.exposure())) {
        Map<String, String> labels = new LinkedHashMap<>(existing.labels());
        labels.put(AddressRecords.OWNER_LABEL, want.exposure());
        zone.setLabels(want.name(), want.type(), labels);
        changed = true;
      }
      if (!same(want.type(), existing.values(), want.values())) {
        zone.setRecords(want.name(), want.type(), want.values());
        changed = true;
      }
      if (existing.ttl() != AddressRecords.TTL) {
        zone.changeTtl(want.name(), want.type());
        changed = true;
      }
      if (changed) {
        set.add(want.name() + "/" + want.type());
      }
    }

    /** A hand-made record is where {@code want} would go: adopt, switch, wait, or skip. */
    private Outcome handMade(Want want, List<AddressRecords.RrSet> unlabelled, Plan plan)
        throws Exception {
      String name = want.name();
      String type = want.type();
      boolean adoptable = APEX.equals(name) || NODE.equals(name);
      if (adoptable && unlabelled.size() == 1 && type.equalsIgnoreCase(unlabelled.get(0).type())) {
        AddressRecords.RrSet rrset = unlabelled.get(0);
        if (same(type, rrset.values(), want.values())) {
          Map<String, String> labels = new LinkedHashMap<>(rrset.labels());
          labels.put(AddressRecords.OWNER_LABEL, want.exposure());
          zone.setLabels(name, type, labels);
          if (rrset.ttl() != AddressRecords.TTL) {
            zone.changeTtl(name, type);
          }
          labelled.add(name + "/" + type);
          collisions.remove(name);
          return Outcome.IN_PLACE;
        }
        warnOnce(
            name,
            "host record %s %s is left alone: the zone holds a hand-made %s there whose values %s"
                + " are not the node address %s; adopt it by hand or delete it",
            name,
            type,
            type,
            rrset.values(),
            want.values());
        return Outcome.HAND_MADE;
      }
      boolean switchable =
          !seed
              && "CNAME".equals(type)
              && unlabelled.stream().allMatch(rrset -> "A".equalsIgnoreCase(rrset.type()));
      if (switchable) {
        if (!allInPlaceBelow(name, plan)) {
          LOG.debugf(
              "host records: the hand-made %s A waits until every name under it has its own record",
              name);
          return Outcome.HAND_MADE;
        }
        zone.delete(name, "A");
        zone.create(name, type, want.values(), want.exposure());
        switched.add(name + "/A(hand-made)->" + type);
        collisions.remove(name);
        LOG.infof(
            "host record %s switched from the hand-made A to %s: deleted and re-created back to"
                + " back",
            name, type);
        return Outcome.IN_PLACE;
      }
      warnOnce(
          name,
          "host record %s %s is skipped: the zone already holds an unlabelled %s RRSet there, which"
              + " this edge never touches",
          name,
          type,
          unlabelled.get(0).type().toUpperCase(Locale.ROOT));
      return Outcome.HAND_MADE;
    }

    /**
     * Whether every wanted name strictly below {@code base} has a record answering it — written,
     * unchanged, adopted or hand-made — after this pass's writes.
     */
    boolean allInPlaceBelow(String base, Plan plan) {
      String suffix = "." + base;
      return plan.wants()
          .map(Want::name)
          .filter(name -> name.endsWith(suffix))
          .allMatch(
              name -> {
                Outcome outcome = outcomes.get(name);
                return outcome == Outcome.IN_PLACE || outcome == Outcome.HAND_MADE;
              });
    }

    boolean wanted(String name, String type, Plan plan) {
      return plan.wants().anyMatch(want -> want.name().equals(name) && want.type().equals(type));
    }

    boolean conflictsWithWant(String name, String type, Plan plan) {
      return plan.wants()
          .anyMatch(
              want -> want.name().equals(name) && conflicting(want.type(), false).contains(type));
    }

    boolean delete(String name, String type, String what) {
      try {
        zone.delete(name, type);
        deleted.add(what + name + "/" + type);
        return true;
      } catch (Exception failure) {
        LOG.warnf(
            "host record %s %s was not deleted, retried on the next trigger: %s",
            name, type, failure.getMessage());
        return false;
      }
    }

    void report() {
      if (!created.isEmpty()
          || !set.isEmpty()
          || !labelled.isEmpty()
          || !switched.isEmpty()
          || !deleted.isEmpty()) {
        LOG.infof(
            "host records %s: created %s, set %s, labelled %s, switched %s, deleted %s",
            seed ? "seeded" : "reconciled", created, set, labelled, switched, deleted);
      }
    }
  }

  /**
   * The types that cannot stand beside {@code type} at one name: everything for a CNAME, a CNAME
   * for an address. With {@code withSelf}, {@code type} itself too — the collision lookup's list.
   */
  static List<String> conflicting(String type, boolean withSelf) {
    List<String> types = new ArrayList<>();
    for (String other : List.of("A", "AAAA", "CNAME")) {
      boolean clash = "CNAME".equals(type) || "CNAME".equals(other);
      if (other.equals(type) ? withSelf : clash) {
        types.add(other);
      }
    }
    return types;
  }

  private static boolean unlabelled(AddressRecords.RrSet rrset, String name, String type) {
    return !rrset.owned()
        && name.equalsIgnoreCase(rrset.name())
        && type.equalsIgnoreCase(rrset.type());
  }

  private static int depth(String name) {
    return name.split("\\.").length;
  }

  /** Whether two value lists say the same, as a set, read the way the zone reads them. */
  static boolean same(String type, List<String> a, List<String> b) {
    return normalised(type, a).equals(normalised(type, b));
  }

  private static Set<String> normalised(String type, List<String> values) {
    Set<String> out = new HashSet<>();
    for (String value : values) {
      String v = value.strip().toLowerCase(Locale.ROOT);
      if ("CNAME".equals(type) && v.endsWith(".")) {
        v = v.substring(0, v.length() - 1);
      } else if ("AAAA".equals(type)) {
        v = ipv6(v).orElse(v);
      }
      out.add(v);
    }
    return out;
  }

  /** The canonical spelling of an IPv6 literal; never a lookup, since only a literal is parsed. */
  private static Optional<String> ipv6(String value) {
    // Hex digits, colons and dots only: anything else could be read as a host name and looked up.
    if (!value.contains(":") || !IPV6_CHARS.matcher(value).matches()) {
      return Optional.empty();
    }
    try {
      InetAddress parsed = InetAddress.getByName(value);
      return parsed instanceof Inet6Address
          ? Optional.of(parsed.getHostAddress())
          : Optional.empty();
    } catch (Exception notALiteral) {
      return Optional.empty();
    }
  }

  /**
   * {@code qits.edge.dns.node-address} read: stripped, de-duplicated, each kept only when it is an
   * IPv4 or IPv6 literal. Nothing is looked up.
   *
   * @param invalid told each value that is neither, which gets no record
   */
  static List<String> nodeAddresses(List<String> stated, Consumer<String> invalid) {
    Set<String> addresses = new LinkedHashSet<>();
    for (String raw : stated == null ? List.<String>of() : stated) {
      String value = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
      if (value.isEmpty()) {
        continue;
      }
      if (IPV4.matcher(value).matches() || ipv6(value).isPresent()) {
        addresses.add(value);
      } else {
        invalid.accept(value);
      }
    }
    return List.copyOf(addresses);
  }

  private void warnInvalid(String value) {
    if (invalidAddresses.add(value)) {
      LOG.warnf(
          "host records: node address %s is not an IPv4 or IPv6 literal and is ignored", value);
    }
  }

  /**
   * The whole desired state for one pass. Package-visible and static so the composition is pinned
   * without a boot — see {@code HostRecordsTest}.
   *
   * @param node the parsed node addresses; empty, the public half is inactive
   * @param tunnel the tunnel address; empty, the private half is inactive
   * @param outside told each composed name that is not inside the zone; it gets no record
   */
  static Plan plan(
      String domain,
      List<String> environments,
      String defaultEnvironment,
      Map<String, Boolean> projects,
      Function<String, Collection<String>> publicHosts,
      Function<String, Map<String, EdgeRoutes.Withdrawn>> withdrawn,
      List<String> node,
      Optional<String> tunnel,
      Consumer<String> outside) {
    return plan(
        domain,
        environments,
        defaultEnvironment,
        projects,
        publicHosts,
        withdrawn,
        node,
        tunnel,
        true,
        outside);
  }

  /**
   * The seed pass's plan (qits-2293): the node, the apex and every {@link PlatformApps} label
   * composed exactly as {@link #plan} composes it — no project door, no private host. {@code qits},
   * the platform project, is composed even while the projection does not know it, env-less, because
   * the edge already answers {@code <label>.qits.<domain>} then ({@link
   * HostEnvironments#PLATFORM_PROJECT}).
   */
  static Plan seedPlan(
      String domain,
      List<String> environments,
      String defaultEnvironment,
      Map<String, Boolean> projects,
      List<String> node,
      Consumer<String> outside) {
    Map<String, Boolean> known = new LinkedHashMap<>(projects);
    known.putIfAbsent(HostEnvironments.PLATFORM_PROJECT, false);
    return plan(
        domain,
        environments,
        defaultEnvironment,
        known,
        ignored -> PlatformApps.labels(),
        ignored -> Map.of(),
        node,
        Optional.empty(),
        false,
        outside);
  }

  /**
   * The public host labels of one environment: the route table's and every {@link PlatformApps}
   * label, once each. PlatformApps answer from code on every tier, table or not — idp above all,
   * whose name qits-events fetches its keys through — so they get their records whether or not the
   * table lists them.
   */
  static Set<String> publicHosts(Collection<String> table) {
    Set<String> hosts = new TreeSet<>(PlatformApps.labels());
    hosts.addAll(table);
    return hosts;
  }

  private static Plan plan(
      String domain,
      List<String> environments,
      String defaultEnvironment,
      Map<String, Boolean> projects,
      Function<String, Collection<String>> publicHosts,
      Function<String, Map<String, EdgeRoutes.Withdrawn>> withdrawn,
      List<String> node,
      Optional<String> tunnel,
      boolean doors,
      Consumer<String> outside) {
    boolean publicActive = !node.isEmpty();
    boolean privateActive = tunnel.isPresent();
    boolean reserved = publicActive && projects.containsKey(NODE);
    List<String> v4 = node.stream().filter(value -> !value.contains(":")).toList();
    List<String> v6 = node.stream().filter(value -> value.contains(":")).toList();
    List<Want> nodeWants = new ArrayList<>();
    List<Want> apexWants = new ArrayList<>();
    if (publicActive) {
      for (String name : reserved ? List.of(APEX) : List.of(NODE, APEX)) {
        List<Want> into = APEX.equals(name) ? apexWants : nodeWants;
        if (!v4.isEmpty()) {
          into.add(new Want(name, "A", v4, AddressRecords.PUBLIC));
        }
        if (!v6.isEmpty()) {
          into.add(new Want(name, "AAAA", v6, AddressRecords.PUBLIC));
        }
      }
    }
    Map<String, String> privateNames =
        privateActive
            ? privateHosts(
                domain,
                environments,
                defaultEnvironment,
                projects,
                withdrawn,
                tunnel.orElseThrow(),
                outside)
            : Map.of();
    List<Want> privates = new ArrayList<>();
    privateNames.forEach(
        (name, value) -> privates.add(new Want(name, "A", List.of(value), AddressRecords.PRIVATE)));
    List<Want> cnames = new ArrayList<>();
    if (publicActive) {
      String target = NODE + "." + domain + ".";
      for (String name :
          publicNames(
              domain, environments, defaultEnvironment, projects, publicHosts, doors, outside)) {
        if (privateNames.containsKey(name) || (reserved && NODE.equals(name))) {
          continue;
        }
        cnames.add(new Want(name, "CNAME", List.of(target), AddressRecords.PUBLIC));
      }
    }
    return new Plan(
        nodeWants,
        privates,
        cnames,
        apexWants,
        doors ? new TreeSet<>(projects.keySet()) : Set.of(),
        reserved ? Set.of(NODE) : Set.of(),
        publicActive,
        privateActive);
  }

  /**
   * Every public name, relative to {@code domain}, in name order: each public host composed for
   * every served environment and project, and with {@code doors} each project door and each
   * environment door of a project that has environments.
   */
  static Set<String> publicNames(
      String domain,
      List<String> environments,
      String defaultEnvironment,
      Map<String, Boolean> projects,
      Function<String, Collection<String>> publicHosts,
      boolean doors,
      Consumer<String> outside) {
    Set<String> names = new TreeSet<>();
    Set<String> reported = new HashSet<>();
    String suffix = "." + domain;
    for (String environment : served(environments, defaultEnvironment)) {
      Collection<String> hosts = publicHosts.apply(environment);
      for (Map.Entry<String, Boolean> project : projects.entrySet()) {
        String authority = authority(domain, environment, defaultEnvironment, project);
        if (authority == null) {
          continue;
        }
        if (doors) {
          names.add(project.getKey());
          names.add(authority.substring(0, authority.length() - suffix.length()));
        }
        for (String host : hosts == null ? List.<String>of() : hosts) {
          if (host == null || host.isBlank()) {
            continue;
          }
          compose(authority, project.getKey(), host, suffix, reported, outside)
              .ifPresent(names::add);
        }
      }
    }
    return names;
  }

  /**
   * The private hosts, name relative to {@code domain} to value, in name order — the qits-2273
   * composition, unchanged.
   */
  static Map<String, String> privateHosts(
      String domain,
      List<String> environments,
      String defaultEnvironment,
      Map<String, Boolean> projects,
      Function<String, Map<String, EdgeRoutes.Withdrawn>> withdrawn,
      String value,
      Consumer<String> outside) {
    Map<String, String> desired = new TreeMap<>();
    Set<String> reported = new HashSet<>();
    String suffix = "." + domain;
    for (String environment : served(environments, defaultEnvironment)) {
      Map<String, EdgeRoutes.Withdrawn> absent = withdrawn.apply(environment);
      if (absent == null) {
        continue;
      }
      for (EdgeRoutes.Withdrawn entry : absent.values()) {
        if (entry == null || entry.host() == null || !PRIVATE.equals(entry.exposure())) {
          continue;
        }
        for (Map.Entry<String, Boolean> project : projects.entrySet()) {
          String authority = authority(domain, environment, defaultEnvironment, project);
          if (authority != null) {
            compose(authority, project.getKey(), entry.host(), suffix, reported, outside)
                .ifPresent(name -> desired.put(name, value));
          }
        }
      }
    }
    return desired;
  }

  private static Set<String> served(List<String> environments, String defaultEnvironment) {
    Set<String> served = new LinkedHashSet<>(environments);
    if (defaultEnvironment != null) {
      served.add(defaultEnvironment);
    }
    return served;
  }

  /**
   * The innermost door of {@code project} in {@code environment}, or null where the project is not
   * served there — EnvironmentAuthority's own rule: the env label is there unless the project says
   * it has no environments, and then its applications live in the default environment only.
   */
  private static String authority(
      String domain,
      String environment,
      String defaultEnvironment,
      Map.Entry<String, Boolean> project) {
    boolean tiered = project.getValue() == null || project.getValue();
    if (!tiered && !environment.equals(defaultEnvironment)) {
      return null;
    }
    return (tiered ? environment + "." : "") + project.getKey() + "." + domain;
  }

  /** One host label composed onto a door, relative to the zone; empty when outside it. */
  private static Optional<String> compose(
      String authority,
      String project,
      String host,
      String suffix,
      Set<String> reported,
      Consumer<String> outside) {
    String origin = new EnvironmentAuthority("https", authority, project).hostOrigin(host);
    String fqdn = EnvironmentAuthority.name(origin.substring("https://".length()));
    if (!fqdn.endsWith(suffix)) {
      if (reported.add(fqdn)) {
        outside.accept(fqdn);
      }
      return Optional.empty();
    }
    return Optional.of(fqdn.substring(0, fqdn.length() - suffix.length()));
  }

  /**
   * Names, once per boot, the hand-made root wildcards still in the zone: with every public name
   * written exactly they answer only for names that do not exist, and the owner decides whether
   * they go. A failed lookup here is not worth a line and is tried again on the next pass.
   */
  private void reportRootWildcards(AddressRecords zone) {
    if (rootWildcardsReported.get()) {
      return;
    }
    List<String> candidates = new ArrayList<>();
    candidates.add("*");
    served(estate.environments(), estate.defaultEnvironment())
        .forEach(env -> candidates.add("*." + env));
    candidates.addAll(DEEP_WILDCARDS);
    List<String> found = new ArrayList<>();
    try {
      for (String wildcard : candidates) {
        if (zone.get(wildcard, "A").filter(rrset -> unlabelled(rrset, wildcard, "A")).isPresent()) {
          found.add(wildcard);
        }
      }
    } catch (Exception failure) {
      LOG.debugf("host records: the root wildcards could not be read: %s", failure.getMessage());
      return;
    }
    rootWildcardsReported.set(true);
    if (!found.isEmpty()) {
      LOG.infof(
          "host records: the zone still holds the hand-made wildcard(s) %s; every public name has"
              + " its own record now, so they answer only for names that do not exist and are no"
              + " longer needed. This edge never touches them: the owner decides",
          found);
    }
  }

  private void warnOnce(String name, String format, Object... params) {
    if (collisions.add(name)) {
      LOG.warnf(format, params);
    }
  }

  private void note(String topic, String reason) {
    if (!reason.equals(notes.put(topic, reason))) {
      LOG.info(reason);
    }
  }
}
