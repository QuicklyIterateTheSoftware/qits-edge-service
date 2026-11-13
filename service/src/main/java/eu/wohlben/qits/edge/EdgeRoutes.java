package eu.wohlben.qits.edge;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The edge's persisted projection of successful deployments: where an environment's paths go, which
 * public name each service answers to, and where each service asks to be rendered in the platform's
 * navigation tree.
 *
 * <p>The database is the recoverable source: it is reconstructed from qits-events after a loss. The
 * immutable in-memory index is the serving copy, so selecting an upstream never blocks the Vert.x
 * event loop on PostgreSQL.
 */
@ApplicationScoped
public class EdgeRoutes {

  private static final org.jboss.logging.Logger LOG =
      org.jboss.logging.Logger.getLogger(EdgeRoutes.class);

  /**
   * The navigation vocabulary, closed and in the order {@code /main-navigation} renders it.
   *
   * <p>Closed because the shell instantiates a node per slot: a slot nothing knows about would be
   * published and never drawn, which is worse than a deployment being refused for a typo. The edge
   * knows nothing about projects or repositories — a slot says WHERE the shell hangs an entry, and
   * the shell decides what hangs there.
   *
   * <p>The archetype slots are the same words, in the same order, as qits-deployments' {@code
   * DeploymentSpecParser.SLOTS}: that parser refuses an unknown word at the source and this one
   * restates the refusal a hop later, so the two lists diverging would mean a spec that passes the
   * deployer and poisons the frame here. {@code apps.details} sits between {@code libs.details} and
   * {@code frontends.details} because an Angular SSR application is a deployable that serves its
   * own HTML, which puts it after the libraries and before the microfrontends.
   */
  public static final List<String> SLOTS =
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
          "images.details");

  /** The legacy shape's only slot: an old frame's one navigation label means "global". */
  static final String SYSTEM_SLOT = "system";

  private static final int LABEL_LIMIT = 64;

  private static final int SUBPATH_LIMIT = 255;

  private static final int API_DOCS_LIMIT = 255;

  @Inject
  @DataSource("edge")
  AgroalDataSource dataSource;

  /** Whose class a write is fenced against: this process's, as configured. */
  @Inject EdgeConfig config;

  private volatile View view = View.empty();

  /**
   * The stored class the last fenced write was refused under, so the WARN is said once per class.
   */
  private final AtomicReference<String> fencedUnder = new AtomicReference<>();

  void load(@Observes StartupEvent ignored) {
    view = readAll();
  }

  /**
   * Re-reads the serving copy from PostgreSQL, whoever wrote it (qits-2120).
   *
   * <p>The view is otherwise refreshed only by THIS process's own writes. Two edge tasks overlap on
   * every update — swarm starts the new one first and gives the old one a 10 m stop grace — and
   * they share the projection database and the durable consumer's claim ledger, so a frame the
   * other task claimed lands in the database and never in this view. The bootstrap calls this at
   * the head; the {@link #refreshIfChanged} tick covers frames the other task claims after it.
   */
  public void reload() {
    view = readAll();
  }

  /**
   * The tick that converges the serving copy on the database when another process wrote it: cheap
   * when nothing changed (one aggregate over the snapshot rows), a full {@link #reload} when it
   * did.
   */
  @Scheduled(every = "15s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void refreshIfChanged() {
    try (Connection connection = dataSource.getConnection()) {
      String stored = fingerprint(connection);
      if (!stored.equals(view.fingerprint())) {
        view = readAll(connection);
      }
    } catch (SQLException | RuntimeException failure) {
      LOG.debugf("edge routing projection refresh skipped: %s", failure.getMessage());
    }
  }

  /**
   * A write refused because the projection was rebuilt under another class than this process's
   * (qits-2120). It is not an {@link IllegalArgumentException}, so the subscriber does not settle
   * it as poison: it propagates, the durable claim rolls back with it, and the frame stays owed to
   * the process whose class the projection IS — the one that rebuilt it.
   */
  public static final class ProjectionFenced extends IllegalStateException {

    ProjectionFenced(String message) {
      super(message);
    }

    /** No stack: the durable funnel logs every refused attempt, and the message is the story. */
    @Override
    public synchronized Throwable fillInStackTrace() {
      return this;
    }
  }

  /**
   * The exposure class this projection was last rebuilt under, as stored ({@code public}, {@code
   * private}, {@code all}), or null on an edge that has not rebuilt since the table existed — see
   * {@code DeploymentProjectionBootstrap#catchUpUntilReady}.
   */
  public String projectionClass() {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement read =
            connection.prepareStatement("select exposure from edge_projection_class");
        ResultSet result = read.executeQuery()) {
      return result.next() ? result.getString(1) : null;
    } catch (SQLException failure) {
      throw new IllegalStateException("could not read the edge projection class", failure);
    }
  }

  /**
   * Whether the PERSISTED projection routes anything: some snapshot holds at least one endpoint.
   * Read from PostgreSQL rather than the serving copy, because it is asked at boot, before or
   * beside {@link #load} — see {@code DeploymentProjectionBootstrap#reconcileClass}.
   */
  public boolean storesRoutes() {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement read =
            connection.prepareStatement("select exists (select 1 from edge_endpoint)");
        ResultSet result = read.executeQuery()) {
      return result.next() && result.getBoolean(1);
    } catch (SQLException failure) {
      throw new IllegalStateException("could not read the edge routing projection", failure);
    }
  }

  /**
   * Empties the deployment projection and records the class it is about to be rebuilt under, in one
   * transaction (qits-2120). Only the replay that follows can fill it again: a frame withdrawn
   * under the old class is otherwise never re-read, because {@link #replace} writes only a strictly
   * newer frame and the replay brings the same ones.
   */
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public void resetProjection(ExposureClass exposure) {
    try (Connection connection = dataSource.getConnection()) {
      for (String statement :
          List.of(
              // First, and in the same order every fenced write takes it: a write already holding
              // its share lock commits before the truncate (and is truncated with the rest), and a
              // write that comes after it reads the new class and is fenced.
              "lock table edge_projection_class in exclusive mode",
              "delete from edge_navigation_entry",
              "delete from edge_endpoint",
              "delete from edge_deployment_snapshot",
              "delete from edge_projection_class")) {
        try (PreparedStatement delete = connection.prepareStatement(statement)) {
          delete.execute();
        }
      }
      fencedUnder.set(null);
      try (PreparedStatement insert =
          connection.prepareStatement("insert into edge_projection_class (exposure) values (?)")) {
        insert.setString(1, exposure.name().toLowerCase(Locale.ROOT));
        insert.executeUpdate();
      }
      view = readAll(connection);
    } catch (SQLException failure) {
      throw new IllegalStateException("could not reset the edge routing projection", failure);
    }
  }

  /**
   * One navigation placement an application published: where in the tree it asks to appear, under
   * what name, how high — and, optionally, which view of the application it opens.
   *
   * <p>{@code subpath} is a client-side route segment the shell appends after the scope it
   * composes; null is every entry declared before the field existed, the application's root under
   * that scope. Relative on purpose — the edge validates the shape and passes it through, because
   * what it names is a view of the declaring application's SPA, not an edge route.
   */
  public record NavigationEntry(String slot, String label, int position, String subpath) {

    public NavigationEntry {
      slot = slot == null ? null : slot.strip();
      if (!SLOTS.contains(slot)) {
        throw new IllegalArgumentException(
            "`" + slot + "` is not a navigation slot. The vocabulary is " + SLOTS + ".");
      }
      if (label == null || label.isBlank() || label.strip().length() > LABEL_LIMIT) {
        throw new IllegalArgumentException(
            "A navigation label is blank or longer than " + LABEL_LIMIT + " characters.");
      }
      label = label.strip();
      if (position < 1) {
        throw new IllegalArgumentException("A navigation position starts at 1.");
      }
      subpath = subpath == null || subpath.isBlank() ? null : subpath.strip();
      if (subpath != null
          && (subpath.length() > SUBPATH_LIMIT
              || !subpath.matches("[a-z0-9]+(?:-[a-z0-9]+)*(?:/[a-z0-9]+(?:-[a-z0-9]+)*)*"))) {
        throw new IllegalArgumentException(
            "A navigation subpath is a relative lowercase path of at most "
                + SUBPATH_LIMIT
                + " characters, got `"
                + subpath
                + "`.");
      }
    }

    /** The placement every entry was before subpaths existed. */
    public NavigationEntry(String slot, String label, int position) {
      this(slot, label, position, null);
    }
  }

  /**
   * One application's whole published state: its routes in declaration order, the public name it
   * answers to, and its navigation placements.
   *
   * @param endpoints the routes, PRIMARY FIRST — the first one is the segment the application's own
   *     SPA is served under, and the upstream its host resolves to
   * @param browserHost the DNS label of {@code <host>.<env>.<domain>}, or null while it publishes
   *     none
   * @param apiDocsPath where the application's browsable API document lives, under one of its
   *     routes, or null for a service that documents no HTTP surface
   * @param navigation the placements, at most one per {@code (slot, label)} pair — an application
   *     may hang SEVERAL rows under one heading, so the slot alone was never the claim. Two of the
   *     same pair is one row asked for twice and is refused; a repeated POSITION is not, because
   *     the document breaks that tie by label exactly as it does between applications
   * @param withdrawn null for every snapshot this edge serves; set only on the empty snapshot a
   *     frame outside this edge's exposure class stores — see {@link Withdrawn}
   */
  public record Snapshot(
      List<EdgeEndpoint> endpoints,
      String browserHost,
      String apiDocsPath,
      List<NavigationEntry> navigation,
      Withdrawn withdrawn) {

    public Snapshot {
      endpoints = endpoints == null ? null : List.copyOf(endpoints);
      apiDocsPath = apiDocsPath == null || apiDocsPath.isBlank() ? null : apiDocsPath.strip();
      navigation = navigation == null ? List.of() : List.copyOf(navigation);
    }

    /** A served snapshot: everything but the withdrawal, which only {@link #withdrawal} sets. */
    public Snapshot(
        List<EdgeEndpoint> endpoints,
        String browserHost,
        String apiDocsPath,
        List<NavigationEntry> navigation) {
      this(endpoints, browserHost, apiDocsPath, navigation, null);
    }

    /** The pre-api-docs shape, which every already-written caller and test still means. */
    public Snapshot(
        List<EdgeEndpoint> endpoints, String browserHost, List<NavigationEntry> navigation) {
      this(endpoints, browserHost, null, navigation);
    }

    /** The routes alone, which is what an application that has not been flipped publishes. */
    public static Snapshot ofEndpoints(List<EdgeEndpoint> endpoints) {
      return new Snapshot(endpoints, null, null, List.of());
    }

    /**
     * What a frame outside this edge's class stores: no endpoint, no host, no api docs and no
     * placement — exactly the empty snapshot a retirement is, as far as routing, vhosts, the
     * certificate and {@link #applicationHosts} can tell — plus the {@link Withdrawn} fact the
     * navigation reads.
     */
    public static Snapshot withdrawal(String host, String exposure) {
      return new Snapshot(List.of(), null, null, List.of(), new Withdrawn(host, exposure));
    }
  }

  /**
   * Why an application is absent from this edge, and what name it answers to on the other one
   * (qits-2061).
   *
   * <p><b>It routes nothing.</b> {@code host} is not a {@link ServiceHost}: it is in no vhost map,
   * resolves no path, claims no name against another application and feeds no certificate SAN. It
   * exists for one reader, {@link NavigationRoute}, which names such an application to a caller the
   * gate would admit, so a shell can tell "not reachable from here" from "does not exist".
   *
   * @param host the DNS label the deployment declared, validated as the served host would be, or
   *     null when it declared none or one this edge could not accept
   * @param exposure the deployment's declared exposure, normalised by {@link
   *     ExposureClass#declared} — {@code private} on the public edge, {@code public} on the private
   *     one, since {@code both} is never withdrawn
   */
  public record Withdrawn(String host, String exposure) {

    public Withdrawn {
      host = host == null || host.isBlank() ? null : host.strip().toLowerCase(Locale.ROOT);
      exposure = ExposureClass.declared(exposure);
    }
  }

  /**
   * A public name and the service behind it.
   *
   * @param upstream the primary route's upstream, which is the process that serves the name itself
   * @param primaryPath that route's path — the segment the application's own SPA is served under
   */
  public record ServiceHost(
      String application, String host, Upstream upstream, String primaryPath) {}

  /**
   * A placement as it is served: what an application published, plus the two facts the document
   * needs and the placement itself does not carry.
   *
   * @param host the application's public name, or null when it publishes none — a legacy frame
   * @param primaryPath its primary route: the segment the application is known by, and what a shell
   *     renders an entry under while the application publishes no host of its own
   * @param subpath the view this entry opens, relative to the scope the shell composes, or null for
   *     the application's root
   */
  public record NavigationPlacement(
      String application,
      String slot,
      String label,
      int position,
      String host,
      String primaryPath,
      String subpath) {}

  /**
   * The serving copy, replaced whole. One object rather than four fields because a request reads
   * several of these maps and must never see two halves of different snapshots.
   */
  private record View(
      Map<String, List<EdgeEndpoint>> endpoints,
      Map<String, Map<String, ServiceHost>> hostsByName,
      Map<String, Map<String, ServiceHost>> hostsByApplication,
      Map<String, Map<String, String>> primaryPaths,
      Map<String, Map<String, String>> apiDocsPaths,
      Map<String, List<NavigationPlacement>> navigation,
      Set<String> landings,
      Map<String, Map<String, Withdrawn>> withdrawn,
      String fingerprint) {

    static View empty() {
      return new View(
          Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), Map.of(), null);
    }
  }

  /**
   * Whether the serving copy routes anything at all: some environment holds at least one active
   * endpoint. A withdrawal routes nothing, so a projection of withdrawals only is empty here.
   *
   * <p>What {@link DeploymentProjectionBootstrap#routable()} asks before it serves a stored
   * snapshot during the catch-up (qits-2160), beside the boot's own {@link #storesRoutes()}: this
   * copy is loaded at startup, possibly after the bootstrap has looked, and an empty one would
   * answer 404 for names the replay is about to publish.
   */
  public boolean routesAnything() {
    for (List<EdgeEndpoint> endpoints : view.endpoints().values()) {
      if (!endpoints.isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /** The longest matching active prefix in this environment, or null when no deployment owns it. */
  public EdgeEndpoint resolve(String environment, String path) {
    for (EdgeEndpoint endpoint : view.endpoints().getOrDefault(environment, List.of())) {
      if (endpoint.matches(path)) {
        return endpoint;
      }
    }
    return null;
  }

  /**
   * The longest matching active prefix among ONE application's routes in this environment, or null
   * when none of them matches — what a service host resolves a path against, because on a service's
   * own name only that service's routes mean anything. Another application's route, however much
   * longer, is not a candidate.
   */
  public EdgeEndpoint resolve(String environment, String application, String path) {
    for (EdgeEndpoint endpoint : view.endpoints().getOrDefault(environment, List.of())) {
      if (endpoint.application().equals(application) && endpoint.matches(path)) {
        return endpoint;
      }
    }
    return null;
  }

  /**
   * The published route whose upstream is this address, in any environment, or null when no
   * deployment names it — which application, in which environment, an upstream pool belongs to. A
   * scan, because it is asked per {@code /upstream-pools} answer and per pool WARN, never per
   * proxied request.
   */
  public EdgeEndpoint owner(Upstream upstream) {
    for (List<EdgeEndpoint> endpoints : view.endpoints().values()) {
      for (EdgeEndpoint endpoint : endpoints) {
        if (endpoint.upstream().equals(upstream)) {
          return endpoint;
        }
      }
    }
    return null;
  }

  /** The service this public name reaches in this environment, or null when nothing claims it. */
  public ServiceHost serviceHost(String environment, String host) {
    return host == null
        ? null
        : view.hostsByName().getOrDefault(environment, Map.of()).get(host.toLowerCase(Locale.ROOT));
  }

  /**
   * The environments in which some deployment published the reserved {@link
   * HostEnvironments#LANDING} label, which is the claim on a project's own door.
   *
   * <p>This is what turns a door into an application position — see {@link
   * HostEnvironments#route(String, Map, Set)}. It is precomputed with the rest of the serving copy
   * rather than scanned per request: every request asks it.
   */
  public Set<String> landingEnvironments() {
    return view.landings();
  }

  /** This application's own public name in this environment, or null while it publishes none. */
  public ServiceHost applicationHost(String environment, String application) {
    return application == null
        ? null
        : view.hostsByApplication().getOrDefault(environment, Map.of()).get(application);
  }

  /**
   * Every application that publishes a public name in this environment, by application name — the
   * {@code origin} {@code /main-navigation} states for each, navigation entries or not.
   */
  public Map<String, ServiceHost> applicationHosts(String environment) {
    return view.hostsByApplication().getOrDefault(environment, Map.of());
  }

  /**
   * Every application withdrawn from this edge in this environment that declared a host, by
   * application name — see {@link Withdrawn}. Disjoint from {@link #applicationHosts}: a withdrawn
   * application publishes no host here, so nothing that routes reads this.
   */
  public Map<String, Withdrawn> withdrawnHosts(String environment) {
    return view.withdrawn().getOrDefault(environment, Map.of());
  }

  /**
   * This application's first-declared route in this environment, or null when it publishes none.
   *
   * <p>The primary route is the one an application is KNOWN by: the segment its SPA is served
   * under, and what a shell renders an entry under while the application publishes no host.
   */
  public String primaryPath(String environment, String application) {
    return application == null
        ? null
        : view.primaryPaths().getOrDefault(environment, Map.of()).get(application);
  }

  /** Every placement in this environment, sorted by slot, then position, then label. */
  public List<NavigationPlacement> navigation(String environment) {
    return view.navigation().getOrDefault(environment, List.of());
  }

  /**
   * Every application's api-docs path in this environment, by application name. Only applications
   * that published one appear; the paths are what {@code /main-navigation} serves as the {@code
   * applications} object.
   */
  public Map<String, String> apiDocs(String environment) {
    return view.apiDocsPaths().getOrDefault(environment, Map.of());
  }

  /**
   * Where a browser typing the environment's own name belongs: qits-projects' host.
   *
   * <p>Read from the projection rather than configured — the {@code system} placement qits-projects
   * publishes, and the conventional name as the fallback. Null until one of them is known, and the
   * door then has nowhere to send anybody and answers 404.
   */
  public ServiceHost projectsHost(String environment) {
    for (NavigationPlacement placement : navigation(environment)) {
      if (SYSTEM_SLOT.equals(placement.slot())
          && "qits-projects".equals(placement.application())
          && placement.host() != null) {
        return serviceHost(environment, placement.host());
      }
    }
    return serviceHost(environment, "projects");
  }

  /**
   * Replaces one application's complete snapshot, including an explicitly empty one. Events
   * delivered late cannot roll a newer active deployment back: frame time, then event id, is the
   * tie-safe order used for that application's projection.
   *
   * @return true only when this event became the active snapshot
   */
  // qits-eventstream owns its claim ledger in a different datasource. Suspending that transaction
  // avoids pretending two ordinary PostgreSQL pools are one XA transaction: this snapshot commits
  // wholly or not at all, and an outer claim failure merely replays the same idempotent event.
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public boolean replace(
      String environment,
      String application,
      String eventId,
      Instant occurredAt,
      Snapshot snapshot) {
    return replace(
        config.exposure(), environment, application, eventId, occurredAt, snapshot, null);
  }

  /**
   * {@link #replace(String, String, String, Instant, Snapshot)} by a writer of class {@code
   * writer}, recording the frame's declared {@code exposure} on the snapshot row.
   *
   * <p><b>Fenced on the stored class</b> (qits-2120). In the same transaction, under a share lock
   * on {@code edge_projection_class} that {@link #resetProjection} takes exclusively, the write
   * reads the class the projection was last rebuilt under; when one is recorded and it is not
   * {@code writer}'s, nothing is written and {@link ProjectionFenced} is thrown. That is the old
   * task of a class change: it runs beside the new one for up to the stop grace, on the same
   * database and the same durable consumer, and the new task's epoch reset makes it replay too.
   *
   * @param exposure the frame's declared exposure, {@code public}, {@code private} or {@code both};
   *     null only from a caller that has no frame, and such a row is rebuilt at the next boot — see
   *     {@link #outsideClass}
   * @throws ProjectionFenced when the projection belongs to another class than {@code writer}
   */
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public boolean replace(
      ExposureClass writer,
      String environment,
      String application,
      String eventId,
      Instant occurredAt,
      Snapshot snapshot,
      String exposure) {
    if (eventId == null || eventId.isBlank() || occurredAt == null) {
      throw new IllegalArgumentException("A DeploymentActive frame needs an id and occurredAt.");
    }
    validateSnapshot(environment, application, snapshot);
    String declared = exposure == null ? null : ExposureClass.declared(exposure);
    try (Connection connection = dataSource.getConnection()) {
      fence(connection, writer, eventId);
      if (!newer(connection, environment, application, eventId, occurredAt)) {
        return false;
      }
      rejectPathConflicts(connection, environment, application, snapshot.endpoints());
      rejectHostConflict(connection, environment, application, snapshot.browserHost());
      try (PreparedStatement delete =
          connection.prepareStatement(
              "delete from edge_deployment_snapshot where environment_name = ? and application_name = ?")) {
        delete.setString(1, environment);
        delete.setString(2, application);
        delete.executeUpdate();
      }
      try (PreparedStatement insert =
          connection.prepareStatement(
              "insert into edge_deployment_snapshot (environment_name, application_name, event_id, occurred_at, browser_host, api_docs_path, withdrawn_exposure, withdrawn_host, exposure) values (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
        insert.setString(1, environment);
        insert.setString(2, application);
        insert.setString(3, eventId);
        insert.setTimestamp(4, java.sql.Timestamp.from(occurredAt));
        insert.setString(5, snapshot.browserHost());
        insert.setString(6, snapshot.apiDocsPath());
        insert.setString(7, snapshot.withdrawn() == null ? null : snapshot.withdrawn().exposure());
        insert.setString(8, snapshot.withdrawn() == null ? null : snapshot.withdrawn().host());
        insert.setString(9, declared);
        insert.executeUpdate();
      }
      int ordinal = 0;
      for (EdgeEndpoint endpoint : snapshot.endpoints()) {
        try (PreparedStatement insert =
            connection.prepareStatement(
                "insert into edge_endpoint (environment_name, application_name, path, upstream_host, upstream_port, ordinal) values (?, ?, ?, ?, ?, ?)")) {
          insert.setString(1, environment);
          insert.setString(2, application);
          insert.setString(3, endpoint.path());
          insert.setString(4, endpoint.upstream().host());
          insert.setInt(5, endpoint.upstream().port());
          insert.setInt(6, ordinal++);
          insert.executeUpdate();
        }
      }
      for (NavigationEntry entry : snapshot.navigation()) {
        try (PreparedStatement insert =
            connection.prepareStatement(
                "insert into edge_navigation_entry (environment_name, application_name, slot, label, \"position\", subpath) values (?, ?, ?, ?, ?, ?)")) {
          insert.setString(1, environment);
          insert.setString(2, application);
          insert.setString(3, entry.slot());
          insert.setString(4, entry.label());
          insert.setInt(5, entry.position());
          insert.setString(6, entry.subpath());
          insert.executeUpdate();
        }
      }
      // This is after every statement that can fail. The durable event listener only sees success
      // once this method returns; a later restart loads PostgreSQL again, never this cache.
      view = readAll(connection);
      return true;
    } catch (SQLException failure) {
      throw new IllegalStateException("could not replace the edge routing projection", failure);
    }
  }

  private static void validateSnapshot(String environment, String application, Snapshot snapshot) {
    if (environment == null
        || environment.isBlank()
        || application == null
        || application.isBlank()) {
      throw new IllegalArgumentException(
          "A deployment route snapshot needs environment and application.");
    }
    if (snapshot == null || snapshot.endpoints() == null) {
      throw new IllegalArgumentException("A deployment route snapshot did not carry endpoints.");
    }
    for (EdgeEndpoint endpoint : snapshot.endpoints()) {
      if (!environment.equals(endpoint.environment())
          || !application.equals(endpoint.application())) {
        throw new IllegalArgumentException("An endpoint is not owned by its deployment snapshot.");
      }
    }
    if (snapshot.browserHost() != null) {
      if (!HostEnvironments.isLabel(snapshot.browserHost())) {
        throw new IllegalArgumentException(
            "`" + snapshot.browserHost() + "` cannot be a DNS label, so it cannot be a host.");
      }
      if (snapshot.endpoints().isEmpty()) {
        // The host resolves to the primary route's upstream, so an application with no route has
        // nothing for its own name to reach.
        throw new IllegalArgumentException(
            "`" + application + "` published a host and no route to serve it from.");
      }
    }
    if (snapshot.apiDocsPath() != null) {
      // The same rule the spec parser enforces, restated where a hand-crafted frame could skip it:
      // a document is reachable only under one of the routes this snapshot itself declares.
      String path = snapshot.apiDocsPath();
      if (path.length() > API_DOCS_LIMIT
          || !path.startsWith("/")
          || snapshot.endpoints().stream()
              .noneMatch(
                  endpoint ->
                      path.equals(endpoint.path())
                          || path.startsWith(
                              endpoint.path().endsWith("/")
                                  ? endpoint.path()
                                  : endpoint.path() + "/"))) {
        throw new IllegalArgumentException(
            "`"
                + application
                + "` published the api-docs path `"
                + path
                + "`, which sits under none of its own routes.");
      }
    }
    // The claim is the (slot, label) PAIR. One application contributes several rows to one heading
    // and the slot alone as the key refused that whole frame. What stays refused is the same pair
    // twice: one row asked
    // for twice, which the primary key could not hold and no shell could draw two of. A repeated
    // POSITION is deliberately not refused — readAll's comparator breaks that tie by label and then
    // by application, so two rows of one application at one number order as stably as two
    // applications at one number already do.
    Set<List<String>> claimed = new LinkedHashSet<>();
    for (NavigationEntry entry : snapshot.navigation()) {
      if (!claimed.add(List.of(entry.slot(), entry.label()))) {
        throw new IllegalArgumentException(
            "`"
                + application
                + "` published two placements of "
                + entry.slot()
                + "."
                + entry.label()
                + ".");
      }
    }
    if (snapshot.withdrawn() != null) {
      // A withdrawal routes nothing, so it may carry nothing that routes: everything that reads the
      // projection to route, to name a vhost or to order a certificate has to keep seeing the empty
      // snapshot a retirement is.
      if (!snapshot.endpoints().isEmpty()
          || snapshot.browserHost() != null
          || snapshot.apiDocsPath() != null
          || !snapshot.navigation().isEmpty()) {
        throw new IllegalArgumentException(
            "`" + application + "` was withdrawn and still carries routes, a host or placements.");
      }
      String host = snapshot.withdrawn().host();
      if (host != null && !HostEnvironments.isLabel(host)) {
        throw new IllegalArgumentException(
            "`" + host + "` cannot be a DNS label, so it cannot be a host.");
      }
    }
    if (snapshot.browserHost() == null && !snapshot.navigation().isEmpty()) {
      // A placement is a link to a service's own name, so a new-shape frame that forgot its host
      // would publish an entry pointing nowhere. The one exception is the shape that never had a
      // host: an old frame's single label, which the subscriber maps to `system` and the document
      // renders against the environment origin.
      boolean legacy =
          snapshot.navigation().size() == 1
              && SYSTEM_SLOT.equals(snapshot.navigation().get(0).slot());
      if (!legacy) {
        throw new IllegalArgumentException(
            "`" + application + "` published navigation placements and no host to send them to.");
      }
    }
  }

  /**
   * Refuses the write when the projection was rebuilt under another class than {@code writer}'s —
   * see {@link #replace(ExposureClass, String, String, String, Instant, Snapshot, String)}. No
   * recorded class fences nothing: the boot's reconcile truncates such a projection anyway.
   */
  private void fence(Connection connection, ExposureClass writer, String eventId)
      throws SQLException {
    if (writer == null) {
      throw new IllegalStateException("A projection write needs the writer's exposure class.");
    }
    try (PreparedStatement lock =
        connection.prepareStatement("lock table edge_projection_class in share mode")) {
      lock.execute();
    }
    String stored;
    try (PreparedStatement read =
            connection.prepareStatement("select exposure from edge_projection_class");
        ResultSet result = read.executeQuery()) {
      stored = result.next() ? result.getString(1) : null;
    }
    String mine = writer.name().toLowerCase(Locale.ROOT);
    if (stored == null || stored.equals(mine)) {
      fencedUnder.set(null);
      return;
    }
    String message =
        "edge projection is class "
            + stored
            + ", this edge is "
            + mine
            + ": not writing DeploymentActive "
            + eventId;
    if (!stored.equals(fencedUnder.getAndSet(stored))) {
      LOG.warnf(
          "%s (the projection was rebuilt by an edge of class %s; this one leaves every frame owed"
              + " to it until it stops)",
          message, stored);
    } else {
      LOG.debug(message);
    }
    throw new ProjectionFenced(message);
  }

  /**
   * Why the KEPT projection cannot be trusted under {@code exposure}, or null when it can
   * (qits-2120) — what {@code DeploymentProjectionBootstrap} asks when the stored class is
   * unchanged, and rebuilds on:
   *
   * <ul>
   *   <li>an active snapshot that routes something and whose recorded exposure this class does not
   *       keep — a private app on a public edge, written by an old task of another class;
   *   <li>an active snapshot that routes something and records no exposure at all — every row
   *       written before V12, which cannot be judged, so the first boot on V12 rebuilds once;
   *   <li>a withdrawal whose exposure this class DOES keep — the same pollution the other way
   *       round.
   * </ul>
   */
  public String outsideClass(ExposureClass exposure) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement read =
            connection.prepareStatement(
                "select s.environment_name, s.application_name, s.exposure, s.withdrawn_exposure,"
                    + " exists (select 1 from edge_endpoint e where e.environment_name ="
                    + " s.environment_name and e.application_name = s.application_name)"
                    + " from edge_deployment_snapshot s order by 1, 2");
        ResultSet result = read.executeQuery()) {
      while (result.next()) {
        String where = result.getString(2) + " in " + result.getString(1);
        String recorded = result.getString(3);
        String withdrawn = result.getString(4);
        boolean routes = result.getBoolean(5);
        if (withdrawn != null) {
          if (keeps(exposure, withdrawn)) {
            return "kept projection withdrew "
                + where
                + ", exposure "
                + withdrawn
                + ", which the class keeps";
          }
          continue;
        }
        if (!routes) {
          continue;
        }
        if (recorded == null) {
          return "kept projection routes " + where + " with no recorded exposure";
        }
        if (!keeps(exposure, recorded)) {
          return "kept projection routes "
              + where
              + ", exposure "
              + recorded
              + ", outside the class";
        }
      }
      return null;
    } catch (SQLException failure) {
      throw new IllegalStateException("could not read the edge routing projection", failure);
    }
  }

  /** {@link ExposureClass#keeps}, with a value it cannot read counted as kept by nobody. */
  private static boolean keeps(ExposureClass exposure, String declared) {
    try {
      return exposure.keeps(declared);
    } catch (IllegalArgumentException unreadable) {
      return false;
    }
  }

  private static boolean newer(
      Connection connection,
      String environment,
      String application,
      String eventId,
      Instant occurredAt)
      throws SQLException {
    try (PreparedStatement read =
        connection.prepareStatement(
            "select occurred_at, event_id from edge_deployment_snapshot where environment_name = ? and application_name = ? for update")) {
      read.setString(1, environment);
      read.setString(2, application);
      try (ResultSet result = read.executeQuery()) {
        if (!result.next()) {
          return true;
        }
        Instant activeAt = result.getTimestamp(1).toInstant();
        int order = occurredAt.compareTo(activeAt);
        return order > 0 || (order == 0 && eventId.compareTo(result.getString(2)) > 0);
      }
    }
  }

  private static void rejectPathConflicts(
      Connection connection, String environment, String application, List<EdgeEndpoint> endpoints)
      throws SQLException {
    for (EdgeEndpoint endpoint : endpoints) {
      try (PreparedStatement existing =
          connection.prepareStatement(
              "select application_name from edge_endpoint where environment_name = ? and path = ? and application_name <> ?")) {
        existing.setString(1, environment);
        existing.setString(2, endpoint.path());
        existing.setString(3, application);
        try (ResultSet result = existing.executeQuery()) {
          if (result.next()) {
            throw new IllegalArgumentException(
                "The active "
                    + environment
                    + " route "
                    + endpoint.path()
                    + " already belongs to "
                    + result.getString(1)
                    + ".");
          }
        }
      }
    }
  }

  /**
   * A public name belongs to one service. The unique index is the belt; this is the refusal that
   * carries a reason, and it keeps the frame poison rather than an SQL error.
   *
   * <p><b>The reserved {@link HostEnvironments#LANDING} label is the one exception, and it is a LOG
   * rather than a refusal.</b> Two deployables claiming a project's door is last-wins, because
   * there is nothing here to compare a claimant against: the deployment frame carries an
   * application name and no repository identity, so neither this hop nor the deployer can say which
   * of the two is entitled to the door. Refusing would blank a project's front page over an
   * ambiguity nobody can resolve from the frame, so the incumbent is displaced, the newest is
   * served, and the collision is reported at ERROR with both names in it — a collision found inside
   * a projection degrades to an answer, exactly as the certificate order drops project tiers past
   * the SAN ceiling rather than refusing.
   */
  private static void rejectHostConflict(
      Connection connection, String environment, String application, String host)
      throws SQLException {
    if (host == null) {
      return;
    }
    String incumbent = null;
    try (PreparedStatement existing =
        connection.prepareStatement(
            "select application_name from edge_deployment_snapshot where environment_name = ? and browser_host = ? and application_name <> ?")) {
      existing.setString(1, environment);
      existing.setString(2, host);
      existing.setString(3, application);
      try (ResultSet result = existing.executeQuery()) {
        if (result.next()) {
          incumbent = result.getString(1);
        }
      }
    }
    if (incumbent == null) {
      return;
    }
    if (!HostEnvironments.LANDING.equals(host)) {
      throw new IllegalArgumentException(
          "The " + environment + " host `" + host + "` already belongs to " + incumbent + ".");
    }
    LOG.errorf(
        "`%s` and `%s` both publish the reserved `%s` label in %s, so both claim that project's own"
            + " door. Nothing in a deployment frame says which of them owns it, so the door is NOT"
            + " blanked and NOT refused: `%s` is served from now on, and `%s` keeps its routes and"
            + " loses its public name. One of the two has to stop declaring it.",
        incumbent, application, host, environment, application, incumbent);
    try (PreparedStatement displace =
        connection.prepareStatement(
            "update edge_deployment_snapshot set browser_host = null where environment_name = ? and browser_host = ? and application_name <> ?")) {
      displace.setString(1, environment);
      displace.setString(2, host);
      displace.setString(3, application);
      displace.executeUpdate();
    }
  }

  private View readAll() {
    try (Connection connection = dataSource.getConnection()) {
      return readAll(connection);
    } catch (SQLException failure) {
      throw new IllegalStateException("could not load the edge routing projection", failure);
    }
  }

  /** One application's snapshot as it comes back out of PostgreSQL. */
  private static final class Read {

    private String host;
    private String apiDocsPath;
    private Withdrawn withdrawn;
    private EdgeEndpoint primary;
    private int primaryOrdinal = Integer.MAX_VALUE;
  }

  private static View readAll(Connection connection) throws SQLException {
    String fingerprint = fingerprint(connection);
    Map<String, List<EdgeEndpoint>> routes = new LinkedHashMap<>();
    Map<String, Map<String, Read>> applications = new LinkedHashMap<>();
    try (PreparedStatement query =
            connection.prepareStatement(
                "select environment_name, application_name, browser_host, api_docs_path, withdrawn_exposure, withdrawn_host from edge_deployment_snapshot");
        ResultSet result = query.executeQuery()) {
      while (result.next()) {
        Read read =
            applications
                .computeIfAbsent(result.getString(1), ignored -> new LinkedHashMap<>())
                .computeIfAbsent(result.getString(2), ignored -> new Read());
        read.host = result.getString(3);
        read.apiDocsPath = result.getString(4);
        String withdrawnExposure = result.getString(5);
        read.withdrawn =
            withdrawnExposure == null
                ? null
                : new Withdrawn(result.getString(6), withdrawnExposure);
      }
    }
    try (PreparedStatement query =
            connection.prepareStatement(
                "select environment_name, application_name, path, upstream_host, upstream_port, ordinal from edge_endpoint order by environment_name, length(path) desc, path");
        ResultSet result = query.executeQuery()) {
      while (result.next()) {
        EdgeEndpoint endpoint =
            new EdgeEndpoint(
                result.getString(1),
                result.getString(2),
                result.getString(3),
                new Upstream(result.getString(4), result.getInt(5)));
        routes.computeIfAbsent(endpoint.environment(), ignored -> new ArrayList<>()).add(endpoint);
        Read read =
            applications
                .computeIfAbsent(endpoint.environment(), ignored -> new LinkedHashMap<>())
                .computeIfAbsent(endpoint.application(), ignored -> new Read());
        int ordinal = result.getInt(6);
        if (ordinal < read.primaryOrdinal) {
          read.primaryOrdinal = ordinal;
          read.primary = endpoint;
        }
      }
    }

    Map<String, Map<String, ServiceHost>> byName = new LinkedHashMap<>();
    Map<String, Map<String, ServiceHost>> byApplication = new LinkedHashMap<>();
    Map<String, Map<String, String>> primaryPaths = new LinkedHashMap<>();
    Map<String, Map<String, String>> apiDocsPaths = new LinkedHashMap<>();
    Map<String, Map<String, Withdrawn>> withdrawn = new LinkedHashMap<>();
    applications.forEach(
        (environment, reads) ->
            reads.forEach(
                (application, read) -> {
                  if (read.withdrawn != null && read.withdrawn.host() != null) {
                    withdrawn
                        .computeIfAbsent(environment, ignored -> new LinkedHashMap<>())
                        .put(application, read.withdrawn);
                  }
                  if (read.primary != null) {
                    primaryPaths
                        .computeIfAbsent(environment, ignored -> new LinkedHashMap<>())
                        .put(application, read.primary.path());
                  }
                  if (read.apiDocsPath != null) {
                    apiDocsPaths
                        .computeIfAbsent(environment, ignored -> new LinkedHashMap<>())
                        .put(application, read.apiDocsPath);
                  }
                  if (read.host == null || read.primary == null) {
                    return;
                  }
                  ServiceHost host =
                      new ServiceHost(
                          application, read.host, read.primary.upstream(), read.primary.path());
                  byName
                      .computeIfAbsent(environment, ignored -> new LinkedHashMap<>())
                      .put(host.host(), host);
                  byApplication
                      .computeIfAbsent(environment, ignored -> new LinkedHashMap<>())
                      .put(application, host);
                }));

    Map<String, List<NavigationPlacement>> navigation = new LinkedHashMap<>();
    try (PreparedStatement query =
            connection.prepareStatement(
                "select environment_name, application_name, slot, label, \"position\", subpath from edge_navigation_entry");
        ResultSet result = query.executeQuery()) {
      while (result.next()) {
        String environment = result.getString(1);
        String application = result.getString(2);
        Read read =
            applications.getOrDefault(environment, Map.of()).getOrDefault(application, new Read());
        navigation
            .computeIfAbsent(environment, ignored -> new ArrayList<>())
            .add(
                new NavigationPlacement(
                    application,
                    result.getString(3),
                    result.getString(4),
                    result.getInt(5),
                    read.host,
                    read.primary == null ? null : read.primary.path(),
                    result.getString(6)));
      }
    }
    Comparator<NavigationPlacement> order =
        Comparator.comparingInt((NavigationPlacement placement) -> SLOTS.indexOf(placement.slot()))
            .thenComparingInt(NavigationPlacement::position)
            .thenComparing(NavigationPlacement::label)
            .thenComparing(NavigationPlacement::application);
    navigation.values().forEach(placements -> placements.sort(order));

    Set<String> landings = new LinkedHashSet<>();
    byName.forEach(
        (environment, hosts) -> {
          if (hosts.containsKey(HostEnvironments.LANDING)) {
            landings.add(environment);
          }
        });

    return new View(
        immutable(routes),
        immutableMaps(byName),
        immutableMaps(byApplication),
        immutablePaths(primaryPaths),
        immutablePaths(apiDocsPaths),
        immutable(navigation),
        Set.copyOf(landings),
        immutableWithdrawn(withdrawn),
        fingerprint);
  }

  /**
   * What the snapshot rows are, in one string: every replace changes its application's event id,
   * and a reset changes the set, so a view read under another fingerprint is out of date.
   */
  private static String fingerprint(Connection connection) throws SQLException {
    try (PreparedStatement query =
            connection.prepareStatement(
                "select count(*) || ':' || coalesce(md5(string_agg(environment_name || '/' ||"
                    + " application_name || '/' || event_id || '/' || coalesce(browser_host, ''),"
                    + " ',' order by environment_name, application_name)), '') from"
                    + " edge_deployment_snapshot");
        ResultSet result = query.executeQuery()) {
      result.next();
      return result.getString(1);
    }
  }

  private static Map<String, Map<String, Withdrawn>> immutableWithdrawn(
      Map<String, Map<String, Withdrawn>> values) {
    Map<String, Map<String, Withdrawn>> copy = new LinkedHashMap<>();
    values.forEach((key, map) -> copy.put(key, Map.copyOf(map)));
    return Map.copyOf(copy);
  }

  private static <T> Map<String, List<T>> immutable(Map<String, List<T>> values) {
    Map<String, List<T>> copy = new LinkedHashMap<>();
    values.forEach((key, list) -> copy.put(key, List.copyOf(list)));
    return Map.copyOf(copy);
  }

  private static Map<String, Map<String, String>> immutablePaths(
      Map<String, Map<String, String>> values) {
    Map<String, Map<String, String>> copy = new LinkedHashMap<>();
    values.forEach((key, map) -> copy.put(key, Map.copyOf(map)));
    return Map.copyOf(copy);
  }

  private static Map<String, Map<String, ServiceHost>> immutableMaps(
      Map<String, Map<String, ServiceHost>> values) {
    Map<String, Map<String, ServiceHost>> copy = new LinkedHashMap<>();
    values.forEach((key, map) -> copy.put(key, Map.copyOf(map)));
    return Map.copyOf(copy);
  }
}
