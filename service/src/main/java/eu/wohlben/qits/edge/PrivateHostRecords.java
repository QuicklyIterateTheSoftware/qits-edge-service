package eu.wohlben.qits.edge;

import eu.wohlben.qits.edge.acme.AddressRecords;
import eu.wohlben.qits.edge.acme.HetznerAddressRecords;
import eu.wohlben.qits.edge.wireguard.PrivateEdgeAddressProjection;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jboss.logging.Logger;

/**
 * Publishes one DNS {@code A} record in the public zone for every PRIVATE application host
 * (qits-2273): {@code projects.qits.wohlben.eu A 100.96.0.1}, the tunnel address the private edge
 * listens on. An exact name outranks the zone's wildcard, so a client off the tunnel resolves a
 * private name to an address it cannot reach, and one on the tunnel reaches the private edge
 * whatever resolver it asked. Because a record below a name turns that name into an empty
 * non-terminal the wildcard no longer answers for, each ancestor also gets SHADOW records restating
 * the wildcard's answer ({@link #shadows}) — without them the qits-2273 rollout made {@code
 * qits.<domain>} and every other {@code *.qits.<domain>} name stop resolving.
 *
 * <p><b>Who writes.</b> The PUBLIC edge, and only while it holds a Hetzner token — the same token,
 * read the same way ({@link HetznerToken}), that its DNS-01 orders use, and independent of whether
 * ACME is enabled. The private edge holds no token and projects nothing private, so it is inactive
 * by construction; either reason is said once at INFO. The break-glass class {@link
 * ExposureClass#ALL} is the one exception to "public only": it withdraws nothing, so it desires no
 * record and DELETES the owned ones — the public edge serves those names then, and a record
 * pointing at a broken tunnel would keep every client off the tunnel away from it.
 *
 * <p><b>What is desired.</b> Every host the deployment projection holds as a {@code private}
 * withdrawal ({@link EdgeRoutes#withdrawnHosts}) in every environment this edge serves, composed
 * into a name the way the navigation document composes it ({@link EnvironmentAuthority#hostOrigin})
 * — once per known project, since the grammar serves an application under every project's tier:
 * {@code <host>.<env>.<project>.<domain>} where the project has environments, {@code
 * <host>.<project>.<domain>} in the default environment where it has none. The value is the newest
 * announced tunnel address ({@link PrivateEdgeAddressProjection#newest}); with none known nothing
 * is written and nothing deleted.
 *
 * <p><b>What it touches.</b> Only RRSets labelled {@code qits-exposure=private} ({@link
 * AddressRecords#OWNER_LABEL}). Missing ones are created, changed ones set, and labelled ones no
 * longer desired deleted. A name that already holds an UNLABELLED {@code A} or {@code CNAME} — a
 * record a person made — is skipped with a WARN, and nothing unlabelled is ever written or deleted.
 *
 * <p><b>Nothing is decided before the projection is authoritative.</b> Until both the deployment
 * and the project projections have read the log to its head, a host missing from them may simply
 * not have arrived yet, and deleting its record would be a wrong answer published to the internet:
 * a reconcile before then returns and relies on the next trigger.
 *
 * <p><b>Triggers</b>: {@link #requestReconcile()} — from {@link DeploymentActiveSubscriber} when a
 * frame changes or clears a private withdrawal, from the address listener, and from both bootstraps
 * once they are authoritative — and the {@link #scheduledReconcile 10-minute backstop}. A Hetzner
 * failure is a WARN with its status and body and is retried on the next trigger; nothing here ever
 * throws into routing or event consumption.
 */
@ApplicationScoped
public class PrivateHostRecords {

  private static final Logger LOG = Logger.getLogger(PrivateHostRecords.class);

  /**
   * How long a request waits for the frames behind it. A redeploy moves several applications within
   * seconds, and an epoch replay delivers every frame at once; one window covers either. A constant
   * rather than a key: there is nothing for a deployment to tune (no new setting, qits-2273).
   */
  static final Duration DEBOUNCE = Duration.ofSeconds(5);

  /** The withdrawal exposure whose hosts get a record; {@link ExposureClass#declared} spells it. */
  static final String PRIVATE = "private";

  /**
   * Everything a reconcile reads, behind one seam so it can be pinned without a boot. {@link
   * #token()} may throw: an unreadable or empty secret file is a refusal, not an absence.
   */
  interface Estate {

    ExposureClass exposure();

    Optional<String> token() throws Exception;

    /** The zone, {@code QITS_DOMAIN} normalised — the one {@code EdgeCertificateManager} uses. */
    String domain();

    List<String> environments();

    String defaultEnvironment();

    /** Slug to whether that project has environments — {@link EdgeProjects#projects()}. */
    Map<String, Boolean> projects();

    Map<String, EdgeRoutes.Withdrawn> withdrawn(String environment);

    boolean authoritative();

    Optional<String> tunnelAddress();
  }

  private final Estate estate;
  private final BiFunction<String, String, AddressRecords> zones;
  private final Duration debounce;
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicBoolean pending = new AtomicBoolean();

  /** The reason last logged for doing nothing, so each reason is said once. */
  private volatile String quiet;

  /** Names last reported as colliding with an unlabelled RRSet, so each is warned about once. */
  private final Set<String> collisions = java.util.concurrent.ConcurrentHashMap.newKeySet();

  @Inject
  PrivateHostRecords(
      EdgeConfig config,
      AcmeConfig acme,
      EdgeRoutes routes,
      EdgeProjects projects,
      DeploymentProjectionBootstrap projection,
      ProjectSansBootstrap projectSans,
      PrivateEdgeAddressProjection addresses) {
    this(
        new Estate() {
          @Override
          public ExposureClass exposure() {
            return config.exposure();
          }

          @Override
          public Optional<String> token() throws Exception {
            return HetznerToken.of(acme);
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
          public Map<String, EdgeRoutes.Withdrawn> withdrawn(String environment) {
            return routes.withdrawnHosts(environment);
          }

          @Override
          public boolean authoritative() {
            return projection.authoritative() && projectSans.authoritative();
          }

          @Override
          public Optional<String> tunnelAddress() {
            return addresses.newest();
          }
        },
        HetznerAddressRecords::new,
        DEBOUNCE);
  }

  /** For a suite: the estate, the zone client per (token, zone), and the debounce window. */
  PrivateHostRecords(
      Estate estate, BiFunction<String, String, AddressRecords> zones, Duration debounce) {
    this.estate = estate;
    this.zones = zones;
    this.debounce = debounce;
  }

  /**
   * One request at boot, which is what says at INFO why an edge is inactive; on a public edge with
   * a token it returns at once until the projections are authoritative, and the bootstraps ask
   * again then.
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
          "private host records were not reconciled, retried on the next trigger: %s",
          failure.getMessage());
    } finally {
      running.set(false);
    }
    return true;
  }

  /** One pass: decide whether to act at all, then converge the owned RRSets on the desired set. */
  void reconcile() throws Exception {
    boolean breakGlass = estate.exposure() == ExposureClass.ALL;
    if (estate.exposure() != ExposureClass.PUBLIC && !breakGlass) {
      quiet(
          "private host records are inactive: this is the "
              + estate.exposure().name().toLowerCase(Locale.ROOT)
              + " edge, and only the public edge writes them");
      return;
    }
    Optional<String> token = estate.token();
    if (token.isEmpty()) {
      quiet(
          "private host records are inactive: no Hetzner token is configured"
              + " (qits.edge.acme.hetzner-token or -file)");
      return;
    }
    String domain = estate.domain();
    if (domain == null || domain.isEmpty()) {
      quiet("private host records are inactive: this deployment states no domain (QITS_DOMAIN)");
      return;
    }
    if (!estate.authoritative()) {
      // Not quiet(): a catch-up is a window, not a state worth a line, and the bootstraps ask
      // again.
      LOG.debug("private host records wait for the projections to reach the log head");
      return;
    }
    if (breakGlass) {
      // The public edge serves every class (README, "Break-glass"), so no name is private and the
      // desired set is empty: every owned record goes, and a private name falls back to the
      // wildcard — this edge — for a client off the tunnel. The way back to public recreates them.
      quiet = null;
      apply(zones.apply(token.orElseThrow(), domain), Map.of());
      return;
    }
    Optional<String> address = estate.tunnelAddress();
    if (address.isEmpty()) {
      quiet(
          "private host records: no private edge has announced a tunnel address yet; nothing is"
              + " written and nothing deleted");
      return;
    }
    quiet = null;
    Map<String, String> desired =
        desired(
            domain,
            estate.environments(),
            estate.defaultEnvironment(),
            estate.projects(),
            estate::withdrawn,
            address.orElseThrow(),
            outside ->
                LOG.warnf(
                    "private host %s is outside the zone %s; no record is written for it",
                    outside, domain));
    apply(zones.apply(token.orElseThrow(), domain), desired);
  }

  /**
   * Converges the owned RRSets on {@code desired} plus the shadows it needs (see {@link #shadows}).
   *
   * <p><b>Order is the safety</b> (the qits-2273 outage): every shadow is created or set before any
   * private host record, and a private record is written only when every shadow above it is in
   * place — written in this pass, already there, or a hand-made record at that name; deletes go the
   * other way, private records first and a shadow only once nothing owned remains under it. So no
   * pass ever leaves a private record standing without its shadows, which is the state that turned
   * the zone's {@code *} off for a whole subtree.
   */
  private void apply(AddressRecords zone, Map<String, String> desired) throws Exception {
    Map<String, AddressRecords.RrSet> actual = new TreeMap<>();
    for (AddressRecords.RrSet rrset : zone.owned()) {
      // The label selector is the zone's filter; this is ours. An RRSet that came back without the
      // label — whatever the selector did — is not one this edge may touch.
      if (rrset.owned() && "A".equalsIgnoreCase(rrset.type())) {
        actual.put(rrset.name().toLowerCase(Locale.ROOT), rrset);
      }
    }
    // Every lookup before any write: a wildcard that cannot be read aborts the pass untouched.
    Map<String, Set<String>> needs = new TreeMap<>();
    Map<String, List<String>> shadows = shadows(zone, desired.keySet(), needs);

    List<String> created = new ArrayList<>();
    List<String> set = new ArrayList<>();
    List<String> deleted = new ArrayList<>();
    Set<String> failedShadows = new HashSet<>();
    for (Map.Entry<String, List<String>> shadow : shadows.entrySet()) {
      if (!write(zone, actual, shadow.getKey(), shadow.getValue(), created, set)) {
        failedShadows.add(shadow.getKey());
      }
    }
    for (Map.Entry<String, String> entry : desired.entrySet()) {
      String name = entry.getKey();
      Set<String> missing = new TreeSet<>(needs.getOrDefault(name, Set.of()));
      missing.retainAll(failedShadows);
      if (!missing.isEmpty()) {
        LOG.warnf(
            "private host record %s is not written: its shadow record(s) %s could not be, and"
                + " without them the zone's wildcard stops answering beside it",
            name, missing);
        continue;
      }
      write(zone, actual, name, List.of(entry.getValue()), created, set);
    }

    Set<String> stale = new TreeSet<>(actual.keySet());
    stale.removeAll(desired.keySet());
    stale.removeAll(shadows.keySet());
    List<String> staleShadows = new ArrayList<>();
    Set<String> remaining = new TreeSet<>(desired.keySet());
    for (String name : stale) {
      if (shadowLike(name, actual.keySet())) {
        staleShadows.add(name);
        continue;
      }
      if (delete(zone, name)) {
        deleted.add(name);
      } else {
        remaining.add(name);
      }
    }
    for (String name : staleShadows) {
      String base = name.startsWith("*.") ? name.substring(2) : name;
      if (remaining.stream().anyMatch(other -> other.endsWith("." + base))) {
        // A private record under it is still there — one whose delete just failed: keep its
        // shadow, or the wildcard goes dark beside it.
        continue;
      }
      if (delete(zone, name)) {
        deleted.add(name);
      }
    }
    Set<String> wanted = new HashSet<>(desired.keySet());
    wanted.addAll(shadows.keySet());
    collisions.retainAll(wanted);
    if (!created.isEmpty() || !set.isEmpty() || !deleted.isEmpty()) {
      LOG.infof(
          "private host records reconciled: created %s, set %s, deleted %s", created, set, deleted);
    }
  }

  /**
   * One owned RRSet brought to {@code values}: created (unless a hand-made A or CNAME is there), or
   * set and re-TTL'd where it differs.
   *
   * @return whether the name is in place after the call — written, unchanged, or hand-made
   */
  private boolean write(
      AddressRecords zone,
      Map<String, AddressRecords.RrSet> actual,
      String name,
      List<String> values,
      List<String> created,
      List<String> set) {
    AddressRecords.RrSet existing = actual.get(name);
    try {
      if (existing == null) {
        if (collides(zone, name)) {
          return true;
        }
        zone.create(name, values);
        created.add(name);
        return true;
      }
      boolean changed = false;
      if (!new HashSet<>(existing.values()).equals(new HashSet<>(values))) {
        zone.setRecords(name, values);
        changed = true;
      }
      if (existing.ttl() != AddressRecords.TTL) {
        zone.changeTtl(name);
        changed = true;
      }
      if (changed) {
        set.add(name);
      }
      return true;
    } catch (Exception failure) {
      LOG.warnf(
          "private host record %s was not written, retried on the next trigger: %s",
          name, failure.getMessage());
      return false;
    }
  }

  private boolean delete(AddressRecords zone, String name) {
    try {
      zone.delete(name);
      return true;
    } catch (Exception failure) {
      LOG.warnf(
          "private host record %s was not deleted, retried on the next trigger: %s",
          name, failure.getMessage());
      return false;
    }
  }

  /**
   * A stale owned name that is a shadow: a {@code *.} name, or an ancestor of another owned one.
   */
  private static boolean shadowLike(String name, Set<String> owned) {
    return name.startsWith("*.") || owned.stream().anyMatch(other -> other.endsWith("." + name));
  }

  /**
   * The SHADOW records the private names need, name to the A values they carry.
   *
   * <p><b>Why they exist</b> (RFC 4592). A wildcard answers only for names that do not exist, and a
   * name exists as soon as anything exists BELOW it: creating {@code projects.qits} makes {@code
   * qits} an empty non-terminal, so the zone's {@code *} no longer answers {@code qits} itself
   * (NOERROR, no data) nor anything else under it ({@code idp.qits} becomes NXDOMAIN — {@code *}
   * never synthesises below an existing name). So for every ancestor P strictly between a private
   * name and the apex, the value W that answered P before — the nearest UNLABELLED wildcard A
   * walking up from P's parent, {@code *.<parent>} ... {@code *} — is restated as two owned RRSets,
   * {@code P → W} and {@code *.P → W}. No wildcard above P: nothing answered P before, and nothing
   * is needed.
   *
   * @param needs filled with each private name's shadow names, for the write order
   */
  Map<String, List<String>> shadows(
      AddressRecords zone, Set<String> privateNames, Map<String, Set<String>> needs)
      throws Exception {
    Map<String, Optional<AddressRecords.RrSet>> looked = new HashMap<>();
    Map<String, List<String>> shadows = new TreeMap<>();
    for (String name : privateNames) {
      Set<String> mine = new TreeSet<>();
      String[] labels = name.split("\\.");
      for (int i = 1; i < labels.length; i++) {
        String ancestor = String.join(".", List.of(labels).subList(i, labels.length));
        List<String> covering = wildcardAbove(zone, ancestor, looked);
        if (covering.isEmpty()) {
          continue;
        }
        for (String shadow : List.of(ancestor, "*." + ancestor)) {
          if (privateNames.contains(shadow)) {
            continue;
          }
          shadows.put(shadow, covering);
          mine.add(shadow);
        }
      }
      needs.put(name, mine);
    }
    return shadows;
  }

  /**
   * The A values of the nearest unlabelled wildcard above {@code ancestor}, or empty. Each wildcard
   * is fetched by its own path ({@link AddressRecords#get}), never through a list filter whose
   * reading of {@code *} is unknown, and once per pass.
   */
  private static List<String> wildcardAbove(
      AddressRecords zone, String ancestor, Map<String, Optional<AddressRecords.RrSet>> looked)
      throws Exception {
    String parent = ancestor.contains(".") ? ancestor.substring(ancestor.indexOf('.') + 1) : null;
    while (true) {
      String wildcard = parent == null ? "*" : "*." + parent;
      Optional<AddressRecords.RrSet> found = looked.get(wildcard);
      if (found == null) {
        found = zone.get(wildcard, "A");
        looked.put(wildcard, found);
      }
      if (found.isPresent()) {
        AddressRecords.RrSet rrset = found.orElseThrow();
        if (!rrset.owned()
            && "A".equalsIgnoreCase(rrset.type())
            && wildcard.equalsIgnoreCase(rrset.name())
            && !rrset.values().isEmpty()) {
          return rrset.values().stream().sorted().toList();
        }
      }
      if (parent == null) {
        return List.of();
      }
      parent = parent.contains(".") ? parent.substring(parent.indexOf('.') + 1) : null;
    }
  }

  /**
   * Whether {@code name} already holds an UNLABELLED {@code A} or {@code CNAME} — a record somebody
   * made by hand, which this edge neither overwrites nor sits beside. Warned about once per name
   * while it lasts.
   */
  private boolean collides(AddressRecords zone, String name) throws Exception {
    List<AddressRecords.RrSet> there = new ArrayList<>();
    zone.get(name, "A").ifPresent(there::add);
    zone.get(name, "CNAME").ifPresent(there::add);
    for (AddressRecords.RrSet rrset : there) {
      if (!rrset.owned()
          && name.equalsIgnoreCase(rrset.name())
          && ("A".equalsIgnoreCase(rrset.type()) || "CNAME".equalsIgnoreCase(rrset.type()))) {
        if (collisions.add(name)) {
          LOG.warnf(
              "private host record %s is skipped: the zone already holds an unlabelled %s RRSet"
                  + " there, which this edge never touches",
              name, rrset.type().toUpperCase(Locale.ROOT));
        }
        return true;
      }
    }
    collisions.remove(name);
    return false;
  }

  /**
   * The desired RRSets, name relative to {@code domain} to value, in name order.
   *
   * <p>Package-visible and static so the composition is pinned without a boot — see {@code
   * PrivateHostRecordsTest}.
   *
   * @param outside told each composed name that is not inside the zone; it gets no record
   */
  static Map<String, String> desired(
      String domain,
      List<String> environments,
      String defaultEnvironment,
      Map<String, Boolean> projects,
      Function<String, Map<String, EdgeRoutes.Withdrawn>> withdrawn,
      String value,
      Consumer<String> outside) {
    Map<String, String> desired = new TreeMap<>();
    Set<String> served = new LinkedHashSet<>(environments);
    if (defaultEnvironment != null) {
      served.add(defaultEnvironment);
    }
    Set<String> reported = new HashSet<>();
    String suffix = "." + domain;
    for (String environment : served) {
      Map<String, EdgeRoutes.Withdrawn> absent = withdrawn.apply(environment);
      if (absent == null) {
        continue;
      }
      for (EdgeRoutes.Withdrawn entry : absent.values()) {
        if (entry == null || entry.host() == null || !PRIVATE.equals(entry.exposure())) {
          continue;
        }
        for (Map.Entry<String, Boolean> project : projects.entrySet()) {
          // EnvironmentAuthority's own rule: the env label is there unless the project says it has
          // no environments, and then its applications live in the default environment only.
          boolean tiered = project.getValue() == null || project.getValue();
          if (!tiered && !environment.equals(defaultEnvironment)) {
            continue;
          }
          String authority = (tiered ? environment + "." : "") + project.getKey() + "." + domain;
          String origin =
              new EnvironmentAuthority("https", authority, project.getKey())
                  .hostOrigin(entry.host());
          String fqdn = EnvironmentAuthority.name(origin.substring("https://".length()));
          if (!fqdn.endsWith(suffix)) {
            if (reported.add(fqdn)) {
              outside.accept(fqdn);
            }
            continue;
          }
          desired.put(fqdn.substring(0, fqdn.length() - suffix.length()), value);
        }
      }
    }
    return desired;
  }

  private void quiet(String reason) {
    if (!reason.equals(quiet)) {
      quiet = reason;
      LOG.info(reason);
    }
  }
}
