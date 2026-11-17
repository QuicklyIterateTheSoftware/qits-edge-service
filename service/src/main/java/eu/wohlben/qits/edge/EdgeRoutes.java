package eu.wohlben.qits.edge;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What this edge routes: the route table qits-deployments built for it (epic qits-2174), as an
 * immutable serving copy — where an environment's paths go, which public name each service answers
 * to, and where each service asks to be rendered in the platform's navigation tree.
 *
 * <p><b>The table is the source, and {@link RouteTables} its only writer.</b> It validates a pushed
 * or fetched table whole, stores it in {@code edge_route_table} and hands it here, and this swaps
 * the {@link View} in one write: a request reads one table or the next, never half of each. At boot
 * the stored table is served before anything is pushed or fetched ({@link RouteTables#load}).
 *
 * <p><b>Readiness is "a table is loaded"</b> ({@link #ready()}), with one exception: the public
 * edge with no table ever received is ready too, and serves {@link PlatformApps} alone. It sits on
 * {@code qits-net}, where the deployer cannot reach it to fetch from, and the deployer pushes its
 * first table only once it reports healthy — so waiting for that push would be waiting forever.
 *
 * <p>Selecting an upstream never blocks the Vert.x event loop on PostgreSQL: every read here is the
 * in-memory copy.
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
   * deployer and refuses the whole table here. {@code apps.details} sits between {@code
   * libs.details} and {@code frontends.details} because an Angular SSR application is a deployable
   * that serves its own HTML, which puts it after the libraries and before the microfrontends.
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

  /** Where qits-projects hangs its own entry, which is how {@link #projectsHost} finds it. */
  static final String SYSTEM_SLOT = "system";

  private static final int LABEL_LIMIT = 64;

  private static final int SUBPATH_LIMIT = 255;

  /**
   * Which variant this is: only the public edge reads a table's withdrawals, and is ready empty.
   */
  @Inject EdgeConfig config;

  private volatile View view = View.empty();

  /** The served table's version, or null while none is. */
  private volatile Long version;

  /** Whether the boot has looked for a stored table; nothing is ready before it has. */
  private volatile boolean loaded;

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
   * @param exposure the deployment's declared exposure, normalised by {@link RouteTables#exposure}
   *     — {@code private} on the public edge, the only one whose table withdraws anything
   */
  public record Withdrawn(String host, String exposure) {

    public Withdrawn {
      host = host == null || host.isBlank() ? null : host.strip().toLowerCase(Locale.ROOT);
      exposure = RouteTables.exposure(exposure);
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
   * @param host the application's public name, or null when it publishes none
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
   * The serving copy, replaced whole. One object rather than several fields because a request reads
   * several of these maps and must never see two halves of different tables.
   */
  private record View(
      Map<String, List<EdgeEndpoint>> endpoints,
      Map<String, Map<String, ServiceHost>> hostsByName,
      Map<String, Map<String, ServiceHost>> hostsByApplication,
      Map<String, Map<String, String>> primaryPaths,
      Map<String, Map<String, String>> apiDocsPaths,
      Map<String, List<NavigationPlacement>> navigation,
      Set<String> landings,
      Map<String, Map<String, Withdrawn>> withdrawn) {

    static View empty() {
      return new View(
          Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), Map.of());
    }
  }

  /**
   * Serves this table from now on, replacing whatever was served — the one write, from {@link
   * RouteTables} once it validated and stored the table, or loaded the stored one at boot.
   *
   * @param table a table {@link RouteTables#problem} found nothing wrong with
   */
  public void serve(long version, JsonObject table) {
    View built = build(table, config.exposure().servesPlatformApps());
    synchronized (this) {
      view = built;
      this.version = version;
      loaded = true;
    }
  }

  /** The boot found no table stored: an empty view, ready only on the public edge. */
  void serveNothing() {
    synchronized (this) {
      view = View.empty();
      version = null;
      loaded = true;
    }
  }

  /** The served table's version, or null while this edge has none. */
  public Long version() {
    return version;
  }

  /**
   * Whether requests are routed and navigation served: a table is loaded — or, on the public edge
   * alone, the boot found none ever received, and {@link PlatformApps} are served by themselves.
   */
  public boolean ready() {
    return loaded && (version != null || config.exposure().servesPlatformApps());
  }

  /**
   * Whether a table is loaded at all — what anything that withdraws or deletes on the table's word
   * asks ({@link HostRecords}), since an edge that never received one knows nothing yet.
   */
  public boolean tableLoaded() {
    return version != null;
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
   * <p>Read from the table rather than configured — the {@code system} placement qits-projects
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

  /** The serving copy of one table. Static so it is asserted without a boot. */
  private static View build(JsonObject table, boolean readsWithdrawn) {
    Map<String, List<EdgeEndpoint>> routes = new LinkedHashMap<>();
    Map<String, Map<String, ServiceHost>> byName = new LinkedHashMap<>();
    Map<String, Map<String, ServiceHost>> byApplication = new LinkedHashMap<>();
    Map<String, Map<String, String>> primaryPaths = new LinkedHashMap<>();
    Map<String, Map<String, String>> apiDocsPaths = new LinkedHashMap<>();
    Map<String, List<NavigationPlacement>> navigation = new LinkedHashMap<>();
    Map<String, Map<String, Withdrawn>> withdrawn = new LinkedHashMap<>();
    JsonArray applications = table.getJsonArray("applications", new JsonArray());
    for (int i = 0; i < applications.size(); i++) {
      JsonObject application = applications.getJsonObject(i);
      String environment = application.getString("environment").strip();
      String name = application.getString("application").strip();
      JsonArray endpoints = application.getJsonArray("endpoints", new JsonArray());
      EdgeEndpoint primary = null;
      for (int e = 0; e < endpoints.size(); e++) {
        JsonObject endpoint = endpoints.getJsonObject(e);
        EdgeEndpoint route =
            new EdgeEndpoint(
                environment,
                name,
                endpoint.getString("path"),
                new Upstream(
                    endpoint.getString("upstreamHost"), endpoint.getInteger("upstreamPort")));
        routes.computeIfAbsent(environment, ignored -> new ArrayList<>()).add(route);
        if (primary == null) {
          primary = route;
        }
      }
      if (primary != null) {
        primaryPaths
            .computeIfAbsent(environment, ignored -> new LinkedHashMap<>())
            .put(name, primary.path());
      }
      String apiDocsPath = blankToNull(application.getString("apiDocsPath"));
      if (apiDocsPath != null) {
        apiDocsPaths
            .computeIfAbsent(environment, ignored -> new LinkedHashMap<>())
            .put(name, apiDocsPath);
      }
      String host = blankToNull(application.getString("browserHost"));
      host = host == null ? null : host.toLowerCase(Locale.ROOT);
      if (host != null && primary != null) {
        ServiceHost served = new ServiceHost(name, host, primary.upstream(), primary.path());
        byName.computeIfAbsent(environment, ignored -> new LinkedHashMap<>()).put(host, served);
        byApplication
            .computeIfAbsent(environment, ignored -> new LinkedHashMap<>())
            .put(name, served);
      }
      JsonArray placements = application.getJsonArray("navigation", new JsonArray());
      for (int n = 0; n < placements.size(); n++) {
        JsonObject placement = placements.getJsonObject(n);
        Integer position = placement.getInteger("position");
        NavigationEntry entry =
            new NavigationEntry(
                placement.getString("slot"),
                placement.getString("label"),
                position == null ? 1 : position,
                placement.getString("subpath"));
        navigation
            .computeIfAbsent(environment, ignored -> new ArrayList<>())
            .add(
                new NavigationPlacement(
                    name,
                    entry.slot(),
                    entry.label(),
                    entry.position(),
                    host,
                    primary == null ? null : primary.path(),
                    entry.subpath()));
      }
    }
    if (readsWithdrawn) {
      JsonArray absent = table.getJsonArray("withdrawn", new JsonArray());
      for (int i = 0; i < absent.size(); i++) {
        JsonObject entry = absent.getJsonObject(i);
        Withdrawn withdrawal = new Withdrawn(entry.getString("host"), entry.getString("exposure"));
        if (withdrawal.host() == null) {
          continue;
        }
        withdrawn
            .computeIfAbsent(
                entry.getString("environment").strip(), ignored -> new LinkedHashMap<>())
            .put(entry.getString("application").strip(), withdrawal);
      }
    }
    // Longest prefix first, which is the order resolve() relies on to pick the most specific route.
    Comparator<EdgeEndpoint> longestFirst =
        Comparator.comparingInt((EdgeEndpoint endpoint) -> endpoint.path().length())
            .reversed()
            .thenComparing(EdgeEndpoint::path);
    routes.values().forEach(endpoints -> endpoints.sort(longestFirst));
    Comparator<NavigationPlacement> order =
        Comparator.comparingInt((NavigationPlacement placement) -> SLOTS.indexOf(placement.slot()))
            .thenComparingInt(NavigationPlacement::position)
            .thenComparing(NavigationPlacement::label)
            .thenComparing(NavigationPlacement::application);
    navigation.values().forEach(entries -> entries.sort(order));

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
        immutableWithdrawn(withdrawn));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
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
