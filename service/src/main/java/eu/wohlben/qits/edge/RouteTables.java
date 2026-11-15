package eu.wohlben.qits.edge;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * The route table qits-deployments builds and pushes (epic qits-2174), validated whole and kept in
 * {@code edge_route_table}: one row, the newest table this edge accepted.
 *
 * <p><b>D2a stores it and serves nothing from it.</b> Routing, {@code /main-navigation} and
 * readiness are still the {@code DeploymentActive} projection's ({@link EdgeRoutes}); a stored
 * table is logged and reported by {@code GET /edge-route-table}, so the deployer's tables can be
 * compared with what the projection routes before D2b switches serving over.
 *
 * <p><b>The version only goes up.</b> A table whose {@code version} is not above the held one
 * changes nothing and is answered with the held version. An invalid table is refused whole — the
 * held one stays — with the reason, and an ERROR line.
 *
 * <p><b>The checks are the projection's, moved to the whole table</b>: {@code
 * DeploymentActiveSubscriber.browserHost} (a host is a DNS label, not an environment name, and on
 * the public edge not a {@link PlatformApps} label pointing anywhere but that platform
 * application's own address) and {@code EdgeRoutes.rejectPathConflicts}/{@code rejectHostConflict}
 * (no {@code (environment, path)} and no {@code (environment, host)} twice). A whole table has no
 * "newest frame" to let win, so the reserved {@link HostEnvironments#LANDING} label is held to the
 * same rule as every other host: two claimants refuse the table.
 */
@ApplicationScoped
public class RouteTables {

  private static final Logger LOG = Logger.getLogger(RouteTables.class);

  /** The public edge's table name; also the break-glass class's, which is the public edge. */
  static final String PUBLIC = "public";

  static final String PRIVATE = "private";

  private static final int PORT_LIMIT = 65535;

  @Inject
  @DataSource("edge")
  AgroalDataSource dataSource;

  @Inject EdgeConfig config;

  Clock clock = Clock.systemUTC();

  /** What this edge holds: nothing yet, or one table's version and when it arrived. */
  public record Held(String edge, Long version, Instant receivedAt) {

    JsonObject document() {
      return new JsonObject()
          .put("edge", edge)
          .put("version", version)
          .put("receivedAt", receivedAt == null ? null : receivedAt.toString());
    }
  }

  /** What an offered table came to. */
  public sealed interface Outcome {}

  /** Stored: it is the held table now. */
  public record Stored(long previous, long version, int applications) implements Outcome {}

  /** Not newer than what is held, which is unchanged. */
  public record AlreadyHeld(long held) implements Outcome {}

  /** Refused whole; the held table is unchanged. */
  public record Invalid(String reason) implements Outcome {}

  /** The table name this edge answers for: {@code private} on the private edge, else public. */
  String edge() {
    return edgeOf(config.exposure());
  }

  static String edgeOf(ExposureClass exposure) {
    return exposure == ExposureClass.PRIVATE ? PRIVATE : PUBLIC;
  }

  /** What this edge holds now, read from the row. Blocking. */
  public Held held() {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "select edge, version, received_at from edge_route_table where id = 1");
        ResultSet row = select.executeQuery()) {
      if (!row.next()) {
        return new Held(edge(), null, null);
      }
      return new Held(row.getString(1), row.getLong(2), row.getTimestamp(3).toInstant());
    } catch (SQLException failure) {
      throw new IllegalStateException("could not read the edge route table", failure);
    }
  }

  /** {@link #offer(JsonObject)}, from the raw body; a body that is not a JSON object is invalid. */
  public Outcome offer(String body) {
    JsonObject table;
    try {
      table = new JsonObject(body == null ? "" : body);
    } catch (DecodeException | ClassCastException unreadable) {
      return refused(null, "the body is not a JSON object");
    }
    return offer(table);
  }

  /**
   * Validates the whole table and stores it when it is newer than the held one. Blocking, and
   * serialised: two pushes racing cannot both decide they are newer.
   */
  public synchronized Outcome offer(JsonObject table) {
    Object rawVersion = table.getValue("version");
    Long version = rawVersion instanceof Number number ? number.longValue() : null;
    String reason = problem(table, edge(), config.environments(), config.exposure(), config.apps());
    if (reason != null) {
      return refused(version, reason);
    }
    int applications = table.getJsonArray("applications").size();
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        long previous = 0;
        try (PreparedStatement select =
                connection.prepareStatement(
                    "select version from edge_route_table where id = 1 for update");
            ResultSet row = select.executeQuery()) {
          if (row.next()) {
            previous = row.getLong(1);
            if (version <= previous) {
              connection.rollback();
              return new AlreadyHeld(previous);
            }
          }
        }
        try (PreparedStatement upsert =
            connection.prepareStatement(
                "insert into edge_route_table (id, edge, version, body, received_at)"
                    + " values (1, ?, ?, ?::jsonb, ?)"
                    + " on conflict (id) do update set edge = excluded.edge,"
                    + " version = excluded.version, body = excluded.body,"
                    + " received_at = excluded.received_at")) {
          upsert.setString(1, edge());
          upsert.setLong(2, version);
          upsert.setString(3, table.encode());
          // Millisecond precision: what PostgreSQL keeps (microseconds) and the GET reports agree.
          upsert.setTimestamp(4, Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS)));
          upsert.executeUpdate();
        }
        connection.commit();
        LOG.infof(
            "route table %s %d -> %d: %d applications", edge(), previous, version, applications);
        return new Stored(previous, version, applications);
      } catch (SQLException | RuntimeException failure) {
        connection.rollback();
        throw failure;
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("could not store the edge route table", failure);
    }
  }

  private static Invalid refused(Long version, String reason) {
    LOG.errorf("refused route table %s: %s", version == null ? "(no version)" : version, reason);
    return new Invalid(reason);
  }

  /**
   * What is wrong with this table for this edge, or null when nothing is. Static so every rule is
   * asserted without a boot.
   *
   * @param edge the table name this edge answers for ({@code public} or {@code private})
   * @param environments the environment names, which no host may be
   * @param exposure this edge's class: only an edge that serves {@link PlatformApps} guards them
   * @param overrides the platform labels' address overrides, as the router dials them
   */
  static String problem(
      JsonObject table,
      String edge,
      List<String> environments,
      ExposureClass exposure,
      Map<String, EdgeConfig.App> overrides) {
    try {
      check(table, edge, environments, exposure, overrides);
      return null;
    } catch (IllegalArgumentException | ClassCastException invalid) {
      return invalid instanceof ClassCastException
          ? "a field has the wrong type: " + invalid.getMessage()
          : invalid.getMessage();
    }
  }

  private static void check(
      JsonObject table,
      String edge,
      List<String> environments,
      ExposureClass exposure,
      Map<String, EdgeConfig.App> overrides) {
    String named = table.getString("edge");
    if (!edge.equals(named)) {
      throw new IllegalArgumentException(
          "the table is for the `" + named + "` edge, and this is the " + edge + " edge");
    }
    Object version = table.getValue("version");
    if (!(version instanceof Number number)
        || number.longValue() < 1
        || number.doubleValue() != number.longValue()) {
      throw new IllegalArgumentException("`version` is not a positive integer");
    }
    JsonArray applications = table.getJsonArray("applications");
    if (applications == null) {
      throw new IllegalArgumentException("`applications` is missing");
    }
    Set<String> environmentNames = new HashSet<>();
    for (String environment : environments) {
      if (environment != null) {
        environmentNames.add(environment.strip().toLowerCase(Locale.ROOT));
      }
    }
    Set<String> applicationsSeen = new HashSet<>();
    Map<String, String> paths = new HashMap<>();
    Map<String, String> hosts = new HashMap<>();
    for (int i = 0; i < applications.size(); i++) {
      JsonObject application = applications.getJsonObject(i);
      if (application == null) {
        throw new IllegalArgumentException("applications[" + i + "] is missing");
      }
      String environment = required(application, "environment", "applications[" + i + "]");
      String name = required(application, "application", "applications[" + i + "]");
      String who = environment + " " + name;
      if (!applicationsSeen.add(environment + "\n" + name)) {
        throw new IllegalArgumentException(who + " is in the table twice");
      }
      ExposureClass.declared(application.getString("exposure"));

      JsonArray endpoints = application.getJsonArray("endpoints");
      if (endpoints == null || endpoints.isEmpty()) {
        throw new IllegalArgumentException(who + " publishes no endpoint");
      }
      Upstream primary = null;
      for (int e = 0; e < endpoints.size(); e++) {
        JsonObject endpoint = endpoints.getJsonObject(e);
        if (endpoint == null) {
          throw new IllegalArgumentException(who + " has a missing endpoint");
        }
        String path = endpoint.getString("path");
        if (path == null || !path.startsWith("/")) {
          throw new IllegalArgumentException(who + " publishes a path that is not absolute");
        }
        String upstreamHost = endpoint.getString("upstreamHost");
        Integer port = endpoint.getInteger("upstreamPort");
        if (upstreamHost == null
            || upstreamHost.isBlank()
            || port == null
            || port < 1
            || port > PORT_LIMIT) {
          throw new IllegalArgumentException(who + " publishes " + path + " with no upstream");
        }
        Upstream upstream = new Upstream(upstreamHost, port);
        if (primary == null) {
          primary = upstream;
        }
        String incumbent = paths.putIfAbsent(environment + "\n" + path, name);
        if (incumbent != null) {
          throw new IllegalArgumentException(
              "The "
                  + environment
                  + " route "
                  + path
                  + " is published by both "
                  + incumbent
                  + " and "
                  + name
                  + ".");
        }
      }

      String raw = application.getString("browserHost");
      if (raw != null && !raw.isBlank()) {
        String host = browserHost(raw, environment, environmentNames, exposure, overrides, primary);
        String incumbent = hosts.putIfAbsent(environment + "\n" + host, name);
        if (incumbent != null) {
          throw new IllegalArgumentException(
              "The "
                  + environment
                  + " host `"
                  + host
                  + "` is published by both "
                  + incumbent
                  + " and "
                  + name
                  + ".");
        }
      }

      JsonArray navigation = application.getJsonArray("navigation");
      if (navigation != null) {
        Set<String> placements = new HashSet<>();
        for (int n = 0; n < navigation.size(); n++) {
          JsonObject placement = navigation.getJsonObject(n);
          if (placement == null) {
            throw new IllegalArgumentException(who + " has a missing navigation placement");
          }
          Integer position = placement.getInteger("position");
          EdgeRoutes.NavigationEntry entry =
              new EdgeRoutes.NavigationEntry(
                  placement.getString("slot"),
                  placement.getString("label"),
                  position == null ? 1 : position,
                  placement.getString("subpath"));
          if (!placements.add(entry.slot() + "\n" + entry.label())) {
            throw new IllegalArgumentException(
                who + " places `" + entry.label() + "` in " + entry.slot() + " twice");
          }
        }
      }
    }

    JsonArray withdrawn = table.getJsonArray("withdrawn");
    if (withdrawn != null) {
      for (int i = 0; i < withdrawn.size(); i++) {
        JsonObject entry = withdrawn.getJsonObject(i);
        if (entry == null) {
          throw new IllegalArgumentException("withdrawn[" + i + "] is missing");
        }
        required(entry, "environment", "withdrawn[" + i + "]");
        required(entry, "application", "withdrawn[" + i + "]");
        String host = entry.getString("host");
        if (host != null && !HostEnvironments.isLabel(host.strip().toLowerCase(Locale.ROOT))) {
          throw new IllegalArgumentException(
              "withdrawn[" + i + "]: `" + host + "` cannot be a DNS label");
        }
        ExposureClass.declared(entry.getString("exposure"));
      }
    }
  }

  /** {@code DeploymentActiveSubscriber.browserHost}'s three refusals, for one table entry. */
  private static String browserHost(
      String raw,
      String environment,
      Set<String> environmentNames,
      ExposureClass exposure,
      Map<String, EdgeConfig.App> overrides,
      Upstream published) {
    String host = raw.strip().toLowerCase(Locale.ROOT);
    if (!HostEnvironments.isLabel(host)) {
      throw new IllegalArgumentException(
          "`" + raw + "` cannot be a DNS label, so it cannot be a service host.");
    }
    if (environmentNames.contains(host)) {
      throw new IllegalArgumentException(
          "`"
              + host
              + "` is an environment name. The first label reads as an application, so that"
              + " environment would stop being reachable by name.");
    }
    if (exposure.servesPlatformApps() && PlatformApps.contains(host)) {
      Upstream pattern = EdgeRouter.appUpstream(host, environment, overrides);
      if (!pattern.host().equalsIgnoreCase(published.host())
          || pattern.port() != published.port()) {
        throw new IllegalArgumentException(
            "`"
                + host
                + "` is a platform application vhost pointing at "
                + pattern
                + ", and the table publishes it as "
                + published
                + ".");
      }
    }
    return host;
  }

  private static String required(JsonObject object, String field, String where) {
    String value = object.getString(field);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(where + ": `" + field + "` is missing");
    }
    return value.strip();
  }
}
