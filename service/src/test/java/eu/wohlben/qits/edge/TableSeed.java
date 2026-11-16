package eu.wohlben.qits.edge;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Seeds what this edge routes the one way production does: a whole route table offered to {@link
 * RouteTables}, as qits-deployments pushes it (epic qits-2174).
 *
 * <p>It keeps the table as a model — one entry per {@code (environment, application)}, plus the
 * withdrawals — and every change offers the whole model again under a newer version. {@link
 * #deploy} takes a deployment in the shape the deployer reads it from a service's labels ({@code
 * applicationName}, {@code environmentName}, {@code browserHost}, {@code apiDocsPath}, {@code
 * endpoints}, {@code navigation}, {@code exposure}) and files it the way the deployer's table
 * builder does: an exposure this edge does not serve moves it to {@code withdrawn} on the public
 * edge, an empty {@code endpoints} retires it. A change the store refuses leaves the model as it
 * was, so the served table and the model never drift apart.
 */
@ApplicationScoped
public class TableSeed {

  /** Versions only go up, across every suite in the JVM and every boot of the edge database. */
  private static final AtomicLong VERSION = new AtomicLong(System.currentTimeMillis());

  @Inject RouteTables tables;

  @Inject EdgeRoutes routes;

  @Inject EdgeConfig config;

  @Inject
  @DataSource("edge")
  AgroalDataSource dataSource;

  private final Map<String, JsonObject> applications = new LinkedHashMap<>();

  private final Map<String, JsonObject> withdrawn = new LinkedHashMap<>();

  /** The entries {@link #deployUnchecked} put in, which the store would refuse. */
  private final java.util.Set<String> unchecked = new java.util.HashSet<>();

  /** The next version a push carries: above every one offered before it. */
  public static long nextVersion() {
    return VERSION.incrementAndGet();
  }

  /** The edge's table name: what {@code edge} a table offered here must carry. */
  public String edge() {
    return RouteTables.edgeOf(config.exposure());
  }

  /**
   * A cold start: no table stored and none served — the public edge serves {@link PlatformApps}
   * alone, the private edge is not ready.
   */
  public synchronized void coldStart() {
    applications.clear();
    withdrawn.clear();
    unchecked.clear();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement delete = connection.prepareStatement("delete from edge_route_table")) {
      delete.executeUpdate();
    } catch (SQLException failure) {
      throw new IllegalStateException("could not clear the route table", failure);
    }
    tables.load();
  }

  /** An empty table, served: every application gone, and the edge ready with nothing routed. */
  public synchronized void clear() {
    applications.clear();
    withdrawn.clear();
    unchecked.clear();
    push();
  }

  /**
   * One deployment into the table, then the whole table offered — see the class javadoc.
   *
   * @return what the store made of it; on anything but {@link RouteTables.Stored} the model is
   *     unchanged
   */
  public synchronized RouteTables.Outcome deploy(JsonObject deployment) {
    Map<String, JsonObject> keptApplications = new LinkedHashMap<>(applications);
    Map<String, JsonObject> keptWithdrawn = new LinkedHashMap<>(withdrawn);
    if (deployment.getJsonArray("endpoints") == null) {
      return new RouteTables.AlreadyHeld(0);
    }
    model(deployment);
    RouteTables.Outcome outcome = push();
    if (!(outcome instanceof RouteTables.Stored)) {
      applications.clear();
      applications.putAll(keptApplications);
      withdrawn.clear();
      withdrawn.putAll(keptWithdrawn);
    }
    return outcome;
  }

  /**
   * {@link #deploy}, served WITHOUT the store's checks and without being stored — for a suite that
   * needs a shape the store would refuse, such as a platform label published at a stand-in's
   * address. While the model holds such an entry, every later change is served unchecked too, until
   * a {@link #deploy} of that application, {@link #clear} or {@link #coldStart} replaces it.
   */
  public synchronized void deployUnchecked(JsonObject deployment) {
    Map<String, JsonObject> keptApplications = new LinkedHashMap<>(applications);
    Map<String, JsonObject> keptWithdrawn = new LinkedHashMap<>(withdrawn);
    try {
      model(deployment);
      for (String environment : environments(deployment)) {
        unchecked.add(environment + "\n" + deployment.getString("applicationName"));
      }
      push();
    } catch (RuntimeException failure) {
      applications.clear();
      applications.putAll(keptApplications);
      withdrawn.clear();
      withdrawn.putAll(keptWithdrawn);
      throw failure;
    }
  }

  /** Files one deployment into the model, as the deployer's table builder does. */
  private void model(JsonObject deployment) {
    String application = deployment.getString("applicationName");
    JsonArray endpoints = deployment.getJsonArray("endpoints");
    for (String environment : environments(deployment)) {
      String key = environment + "\n" + application;
      unchecked.remove(key);
      applications.remove(key);
      withdrawn.remove(key);
      if (endpoints.isEmpty()) {
        continue;
      }
      String exposure = deployment.getString("exposure");
      if (served(exposure)) {
        JsonObject entry =
            new JsonObject()
                .put("environment", environment)
                .put("application", application)
                .put("exposure", exposure == null ? "public" : exposure)
                .put("browserHost", deployment.getString("browserHost"))
                .put("endpoints", endpoints.copy())
                .put("navigation", deployment.getJsonArray("navigation", new JsonArray()).copy());
        if (deployment.getString("apiDocsPath") != null) {
          entry.put("apiDocsPath", deployment.getString("apiDocsPath"));
        }
        applications.put(key, entry);
      } else if (config.exposure().servesPlatformApps()) {
        withdrawn.put(
            key,
            new JsonObject()
                .put("environment", environment)
                .put("application", application)
                .put("host", deployment.getString("browserHost"))
                .put("exposure", exposure));
      }
    }
  }

  private List<String> environments(JsonObject deployment) {
    String named = deployment.getString("environmentName");
    return named == null || named.isBlank() ? config.environments() : List.of(named);
  }

  /**
   * The model as a table under a fresh version, offered whole — or, while it holds an unchecked
   * entry, served without the store.
   */
  public synchronized RouteTables.Outcome push() {
    long version = nextVersion();
    JsonObject table = table(version);
    if (unchecked.isEmpty()) {
      return tables.offer(table);
    }
    routes.serve(version, table);
    return new RouteTables.Stored(0, version, applications.size());
  }

  /** The model as a table carrying {@code version}, without offering it. */
  public synchronized JsonObject table(long version) {
    JsonArray entries = new JsonArray();
    applications.values().forEach(entry -> entries.add(entry.copy()));
    JsonArray absent = new JsonArray();
    withdrawn.values().forEach(entry -> absent.add(entry.copy()));
    return new JsonObject()
        .put("edge", edge())
        .put("version", version)
        .put("builtAt", "2026-11-16T00:00:00Z")
        .put("applications", entries)
        .put("withdrawn", absent);
  }

  /**
   * Whether this edge's table carries a deployment declaring {@code exposure}: {@code both} and its
   * own class always. An exposure no edge knows is kept, so the store refuses the table for it — a
   * typo neither publishes nor withdraws.
   */
  private boolean served(String exposure) {
    if (exposure == null || exposure.isBlank()) {
      return config.exposure() == ExposureClass.PUBLIC;
    }
    String declared = exposure.strip().toLowerCase(Locale.ROOT);
    if (!List.of("public", "private", "both").contains(declared)) {
      return true;
    }
    return declared.equals("both") || declared.equals(edge());
  }

  /** One deployment in {@link #deploy}'s shape: its routes, primary first, and its public name. */
  public static JsonObject deployment(
      String environment, String application, String browserHost, JsonObject... endpoints) {
    JsonArray routes = new JsonArray();
    for (JsonObject endpoint : endpoints) {
      routes.add(endpoint);
    }
    JsonObject deployment =
        new JsonObject()
            .put("applicationName", application)
            .put("environmentName", environment)
            .put("endpoints", routes);
    if (browserHost != null) {
      deployment.put("browserHost", browserHost);
    }
    return deployment;
  }

  /** One route of a deployment. */
  public static JsonObject endpoint(String path, Upstream upstream) {
    return new JsonObject()
        .put("path", path)
        .put("upstreamHost", upstream.host())
        .put("upstreamPort", upstream.port());
  }

  /** One navigation placement of a deployment. */
  public static JsonObject placement(String slot, String label, int position) {
    return new JsonObject().put("slot", slot).put("label", label).put("position", position);
  }

  /** A deployment's retirement: its {@code endpoints} explicitly empty. */
  public static JsonObject retirement(String environment, String application) {
    return deployment(environment, application, null);
  }
}
