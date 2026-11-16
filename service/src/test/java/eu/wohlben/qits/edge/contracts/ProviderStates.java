package eu.wohlben.qits.edge.contracts;

import eu.wohlben.qits.edge.EdgeConfig;
import eu.wohlben.qits.edge.EdgeProjects;
import eu.wohlben.qits.edge.EdgeRoutes;
import eu.wohlben.qits.edge.RouteTables;
import eu.wohlben.qits.edge.StubGateways;
import eu.wohlben.qits.edge.TableSeed;
import eu.wohlben.qits.edge.Upstream;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.restassured.RestAssured;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * <b>qits-edge's provider states</b> (epic qits-112): each publishes the projection one consumer
 * situation needs and hands back its parameters.
 *
 * <p>Two callers. {@link GoldenMasterRecordingTest} runs a state before it records each operation
 * that names it, and {@link ConsumerPactVerificationTest}'s {@code @State} methods delegate here.
 *
 * <p><b>A state is applied, never cleared.</b> The projection's tables and its reload are
 * package-private to the edge, so a state cannot empty them. It does not need to: every frame here
 * is a whole snapshot of one application in one environment, so applying a state twice gives the
 * same projection; and the one connection {@link #AN_OPEN_UPSTREAM_POOL} leaves pooled is reused,
 * not added to, when it runs again. What it relies on is that nothing else publishes, and nothing
 * else is proxied, in its JVM — the {@code contract-tests} surefire execution in {@code
 * service/pom.xml} runs this package alone, on a database Flyway cleaned at start.
 *
 * <p><b>The edge routes on {@code Host}</b>, so a state names the host its operation is asked on
 * ({@code host}) and both callers send it, with {@code X-Forwarded-Proto: https}, as {@link
 * #requestHeaders} says. The verifier reads it by state name, not from the pact's params, so a
 * consumer cannot point the edge at another host. A state whose operation is answered only to an
 * admitted caller adds the credential there too (see {@link #AN_OPEN_UPSTREAM_POOL}).
 */
@ApplicationScoped
public class ProviderStates {

  public static final String A_PUBLISHED_NAVIGATION = "a published navigation";

  /**
   * {@link #A_PUBLISHED_NAVIGATION} plus one PRIVATE application, qits-stt on {@code stt}, which
   * this public edge withdraws and names only to a caller the gate admits (qits-2061). {@link
   * #requestHeaders} carries a bearer for this state, which the verifier sets over whatever the
   * pact's request holds: <b>a consumer pact against this state must send {@code Authorization}</b>
   * (any {@code Bearer} value — the provider substitutes a live one), as a signed-in shell does.
   */
  public static final String A_SIGNED_IN_CALLER_AND_A_PRIVATE_APP =
      "a signed-in caller and a private app";

  /**
   * One idle ordinary connection pooled to qits-artifacts' registry in {@code dev}, the only
   * connection the edge holds open. {@code /upstream-pools} answers only a caller the edge would
   * let through to a service, so {@link #requestHeaders} carries a bearer for this state, which the
   * verifier sets over whatever the pact's request holds: <b>a consumer pact against this state
   * must send {@code Authorization}</b> (any {@code Bearer} value — the provider substitutes a live
   * one), as the consumer itself must on the platform.
   */
  public static final String AN_OPEN_UPSTREAM_POOL = "an open upstream pool";

  /**
   * The edge holds no route table (qits-2174): a push of {@link #ROUTE_TABLE} is stored. {@link
   * #requestHeaders} carries qits-deployments' own service bearer for this state and the two below,
   * which the verifier sets over whatever the pact's request holds: <b>a consumer pact against them
   * must send {@code Authorization}</b> (any {@code Bearer} value — the provider substitutes the
   * deployer's).
   */
  public static final String NO_ROUTE_TABLE_HELD = "no route table held";

  /**
   * The edge holds {@link #ROUTE_TABLE} at {@link #ROUTE_TABLE_VERSION}: pushing it again is
   * answered with the held version, and {@code GET /edge-route-table} reports it.
   */
  public static final String A_ROUTE_TABLE_HELD = "a route table held";

  /**
   * The edge holds no route table, and a push that publishes one host for two applications is
   * refused whole with a 422 ({@link #INVALID_ROUTE_TABLE}).
   */
  public static final String NO_ROUTE_TABLE_HELD_AND_AN_INVALID_PUSH =
      "no route table held and an invalid push";

  /** The version {@link #ROUTE_TABLE} carries: the deployer's epoch-millis versions. */
  public static final long ROUTE_TABLE_VERSION = 1763025600000L;

  /** A public table: qits-ci on {@code ci} in the default environment, and qits-stt withdrawn. */
  public static final String ROUTE_TABLE = routeTable("ci", ROUTE_TABLE_VERSION).encode();

  /** {@link #ROUTE_TABLE} with a second application on qits-ci's host, which no edge accepts. */
  public static final String INVALID_ROUTE_TABLE = invalidRouteTable().encode();

  /**
   * The platform's own project. It has no environment tier, so its applications are {@code
   * <app>.qits.<domain>} and they are served in the default environment.
   */
  static final String PROJECT = "qits";

  /** {@code StubGateways.DOMAIN}, the domain the test configuration states. */
  static final String DOMAIN = "example.com";

  /**
   * A project WITH a tier of environments, so {@code registry.dev.<project>.<domain>} names the
   * {@code dev} environment's registry; the platform's own {@link #PROJECT} has no tier and could
   * only ever name the default environment's.
   */
  static final String TIERED_PROJECT = "acme";

  /** The pooled upstream {@link #AN_OPEN_UPSTREAM_POOL} opens: the registry, in {@code dev}. */
  static final String POOLED_APP = "registry";

  static final String POOLED_ENVIRONMENT = "dev";

  /**
   * What the pooled origin is recorded as. The test's registry stub listens on an ephemeral
   * loopback port, so its real {@code 127.0.0.1:<port>} changes every run; the golden master holds
   * the wire alias the same label dials on the platform ({@code PlatformApps.upstream}) instead.
   */
  static final String FROZEN_POOLED_ORIGIN = "dev-qits-artifacts:8080";

  /** How long a state waits for the edge to show what it set up. */
  private static final Duration SETTLE = Duration.ofSeconds(10);

  /** What a state hands back: its parameters, keys sorted. */
  public record Setup(Map<String, String> params) {}

  @Inject TableSeed seed;
  @Inject EdgeProjects projects;
  @Inject EdgeRoutes routes;
  @Inject RouteTables tables;
  @Inject EdgeConfig config;

  @Inject
  @DataSource("edge")
  AgroalDataSource edgeDataSource;

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();

  public ProviderStates() {
    states.put(A_PUBLISHED_NAVIGATION, this::aPublishedNavigation);
    states.put(A_SIGNED_IN_CALLER_AND_A_PRIVATE_APP, this::aSignedInCallerAndAPrivateApp);
    states.put(AN_OPEN_UPSTREAM_POOL, this::anOpenUpstreamPool);
    states.put(NO_ROUTE_TABLE_HELD, () -> routeTableState(NO_ROUTE_TABLE_HELD, false));
    states.put(A_ROUTE_TABLE_HELD, () -> routeTableState(A_ROUTE_TABLE_HELD, true));
    states.put(
        NO_ROUTE_TABLE_HELD_AND_AN_INVALID_PUSH,
        () -> routeTableState(NO_ROUTE_TABLE_HELD_AND_AN_INVALID_PUSH, false));
  }

  /** Every state name this provider answers for. */
  public Set<String> names() {
    return Collections.unmodifiableSet(states.keySet());
  }

  /** Runs the named state; an unknown name is a programming error, not an empty state. */
  public Setup setUp(String state) {
    Supplier<Setup> setup = states.get(state);
    if (setup == null) {
      throw new IllegalArgumentException(
          "No provider state '" + state + "' — this provider answers for " + states.keySet());
    }
    return setup.get();
  }

  /** {@link #setUp} for a pact {@code @State} method, which returns only the params. */
  public Map<String, String> params(String state) {
    return setUp(state).params();
  }

  /** The state's slug: lower-cased, every run of non-alphanumerics replaced by {@code -}. */
  public static String slug(String state) {
    return state.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }

  /** The host each state's operations are asked on; also its {@code host} param. */
  private static final Map<String, String> HOSTS =
      Map.of(
          A_PUBLISHED_NAVIGATION,
          "projects." + PROJECT + "." + DOMAIN,
          A_SIGNED_IN_CALLER_AND_A_PRIVATE_APP,
          "projects." + PROJECT + "." + DOMAIN,
          // Any name answers /upstream-pools; the platform project's own door is the one a shell
          // asks it on.
          AN_OPEN_UPSTREAM_POOL,
          PROJECT + "." + DOMAIN,
          // The public edge's own address, where qits-deployments pushes: https://qits.<domain>.
          NO_ROUTE_TABLE_HELD,
          PROJECT + "." + DOMAIN,
          A_ROUTE_TABLE_HELD,
          PROJECT + "." + DOMAIN,
          NO_ROUTE_TABLE_HELD_AND_AN_INVALID_PUSH,
          PROJECT + "." + DOMAIN);

  /** The states whose operation only qits-deployments' own bearer may ask (qits-2174). */
  private static final Set<String> PUBLISHED =
      Set.of(NO_ROUTE_TABLE_HELD, A_ROUTE_TABLE_HELD, NO_ROUTE_TABLE_HELD_AND_AN_INVALID_PUSH);

  /** The states whose operation is answered only to an admitted caller. */
  private static final Set<String> CREDENTIALED =
      Set.of(AN_OPEN_UPSTREAM_POOL, A_SIGNED_IN_CALLER_AND_A_PRIVATE_APP);

  /**
   * The headers a request in a state carries: the state's host, and the scheme a TLS front reports,
   * so every origin in the answer is {@code https} as it is on the platform — and, for a state
   * whose operation admits only a caller the edge would let through to a service, a live platform
   * bearer ({@link StubGateways#platformBearer}), minted per call so it never expires mid-suite.
   */
  public static Map<String, String> requestHeaders(String state) {
    String host = HOSTS.get(state);
    if (host == null) {
      throw new IllegalArgumentException("No host for the provider state '" + state + "'");
    }
    if (PUBLISHED.contains(state)) {
      return Map.of(
          "Host",
          host,
          "X-Forwarded-Proto",
          "https",
          "Authorization",
          StubGateways.serviceBearer(
              ConfigProvider.getConfig().getValue("qits.edge.routes.publisher", String.class)));
    }
    if (CREDENTIALED.contains(state)) {
      return Map.of(
          "Host",
          host,
          "X-Forwarded-Proto",
          "https",
          "Authorization",
          StubGateways.platformBearer());
    }
    return Map.of("Host", host, "X-Forwarded-Proto", "https");
  }

  /**
   * The strings a state's answers hold that differ per run, each mapped to the stable value the
   * golden master records instead: for {@link #AN_OPEN_UPSTREAM_POOL}, the registry stub's
   * ephemeral {@code 127.0.0.1:<port>}.
   */
  public Map<String, String> frozenStrings(String state) {
    if (AN_OPEN_UPSTREAM_POOL.equals(state)) {
      return Map.of(pooledOrigin().toString(), FROZEN_POOLED_ORIGIN);
    }
    return Map.of();
  }

  // --- the states ------------------------------------------------------------------------------

  /**
   * The platform project {@code qits} with seven applications deployed. Six are on a host of their
   * own — qits-projects, qits-githost, qits-events, qits-maintenance, qits-idp and qits-workspaces
   * — and each places navigation and publishes api docs, so every part of the document has content.
   * The seventh, qits-landing, publishes the reserved {@code landing} label and nothing else, so
   * its origin is the project's root. Asked on qits-projects' host.
   */
  private Setup aPublishedNavigation() {
    projects.apply(PROJECT, "p-contract", true, false, UUID.randomUUID().toString(), Instant.now());
    String environment = config.defaultEnvironment();
    publish(
        environment,
        "qits-projects",
        "projects",
        "/projects",
        List.of(placement("system", "Overview", 1, null)));
    publish(
        environment,
        "qits-githost",
        "githost",
        "/githost",
        List.of(placement("platform", "Git", 1, null)));
    publish(
        environment,
        "qits-events",
        "events",
        "/events",
        List.of(placement("platform", "Events", 2, null)));
    publish(
        environment,
        "qits-maintenance",
        "maintenance",
        "/maintenance",
        List.of(placement("platform", "Maintenance", 3, null)));
    publish(
        environment,
        "qits-idp",
        "idp",
        "/idp",
        List.of(placement("platform", "Identity", 4, null)));
    publish(
        environment,
        "qits-workspaces",
        "workspaces",
        "/workspaces",
        List.of(placement("project.detail", "Workspaces", 1, null)));
    // The project's root: no navigation, no api docs, and the reserved label as its host.
    publish(environment, "qits-landing", "landing", "/landing", null, List.of());

    // A state that published less than it says would only show up later as a confusing body diff.
    Set<String> published = routes.applicationHosts(environment).keySet();
    for (String application :
        List.of(
            "qits-projects",
            "qits-githost",
            "qits-events",
            "qits-maintenance",
            "qits-idp",
            "qits-workspaces",
            "qits-landing")) {
      if (!published.contains(application)) {
        throw new IllegalStateException(
            "State '" + A_PUBLISHED_NAVIGATION + "' did not publish " + application);
      }
    }
    Map<String, String> params = new TreeMap<>();
    params.put("host", HOSTS.get(A_PUBLISHED_NAVIGATION));
    return new Setup(Collections.unmodifiableMap(params));
  }

  /**
   * Everything {@link #A_PUBLISHED_NAVIGATION} publishes — applied in full, so the answer is the
   * same whichever of the two states ran first in the JVM — and qits-stt deployed {@code private}
   * on the host {@code stt} with no placement, which this public edge withdraws: it routes nothing
   * and is named, to an admitted caller, as {@code {"origin", "exposure": "private"}}.
   */
  private Setup aSignedInCallerAndAPrivateApp() {
    aPublishedNavigation();
    String environment = config.defaultEnvironment();
    JsonObject payload =
        new JsonObject()
            .put("applicationName", "qits-stt")
            .put("environmentName", environment)
            .put("browserHost", "stt")
            .put("exposure", "private")
            .put(
                "endpoints",
                new JsonArray()
                    .add(
                        new JsonObject()
                            .put("path", "/stt")
                            .put("upstreamHost", environment + "-qits-stt")
                            .put("upstreamPort", 8080)))
            .put("navigation", new JsonArray());
    deploy(payload);
    if (!routes.withdrawnHosts(environment).containsKey("qits-stt")
        || routes.applicationHosts(environment).containsKey("qits-stt")) {
      throw new IllegalStateException(
          "State '" + A_SIGNED_IN_CALLER_AND_A_PRIVATE_APP + "' did not withdraw qits-stt");
    }
    Map<String, String> params = new TreeMap<>();
    params.put("host", HOSTS.get(A_SIGNED_IN_CALLER_AND_A_PRIVATE_APP));
    return new Setup(Collections.unmodifiableMap(params));
  }

  /**
   * The edge's proxy pools hold exactly one connection: an idle ordinary one to qits-artifacts'
   * registry in {@code dev}. It is opened by one admitted {@code GET /v2/} through the edge on
   * {@code registry.dev.acme.<domain>} — the request {@code EdgeRoutingTest}'s pool test makes —
   * which leaves its connection in the pool, keep-alive being the point of one. The state then
   * waits until {@code /upstream-pools} lists it with nothing held, since the connection goes back
   * to the pool only after the response has reached the caller.
   *
   * <p>Run again, the request reuses that idle connection (or, should the pool's keep-alive timeout
   * have closed it, opens one in its place), so the answer stays one connection. Nothing else in
   * the {@code contract-tests} JVM is proxied, which is what makes this entry the WHOLE document; a
   * second entry fails here, naming it, rather than later as a body diff.
   */
  private Setup anOpenUpstreamPool() {
    projects.apply(
        TIERED_PROJECT,
        "p-contract-tiered",
        true,
        true,
        UUID.randomUUID().toString(),
        Instant.now());
    String registry = POOLED_APP + "." + POOLED_ENVIRONMENT + "." + TIERED_PROJECT + "." + DOMAIN;
    HttpResponse<String> proxied = get(registry, "/v2/");
    if (proxied.statusCode() != 200 || !proxied.body().contains("upstream=registry-dev")) {
      throw new IllegalStateException(
          "State '"
              + AN_OPEN_UPSTREAM_POOL
              + "': GET /v2/ on "
              + registry
              + " did not reach dev's registry stub — "
              + proxied.statusCode()
              + ": "
              + proxied.body());
    }
    String origin = pooledOrigin().toString();
    long deadline = System.nanoTime() + SETTLE.toNanos();
    String last = null;
    while (System.nanoTime() < deadline) {
      HttpResponse<String> pools = get(HOSTS.get(AN_OPEN_UPSTREAM_POOL), "/upstream-pools");
      last = pools.statusCode() + ": " + pools.body();
      if (pools.statusCode() == 200) {
        JsonArray document = new JsonArray(pools.body());
        if (document.size() == 1
            && origin.equals(document.getJsonObject(0).getString("origin"))
            && "ordinary".equals(document.getJsonObject(0).getString("pool"))
            && document.getJsonObject(0).getJsonArray("held").isEmpty()) {
          Map<String, String> params = new TreeMap<>();
          params.put("host", HOSTS.get(AN_OPEN_UPSTREAM_POOL));
          return new Setup(Collections.unmodifiableMap(params));
        }
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    throw new IllegalStateException(
        "State '"
            + AN_OPEN_UPSTREAM_POOL
            + "': /upstream-pools never showed exactly one idle ordinary connection to "
            + origin
            + " within "
            + SETTLE
            + " — something else in this JVM proxied a request? Last answer: "
            + last);
  }

  /**
   * The edge's route table emptied and, when {@code held}, {@link #ROUTE_TABLE} stored. Emptied
   * rather than left as the last state found it, because the version only goes up: a state that ran
   * earlier in the JVM would otherwise turn this one's 204 into a 200.
   */
  private Setup routeTableState(String state, boolean held) {
    try (Connection connection = edgeDataSource.getConnection();
        PreparedStatement delete = connection.prepareStatement("delete from edge_route_table")) {
      delete.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("State '" + state + "' could not empty the route table", e);
    }
    // And served nothing: the edge is as an empty database boots it. The seeded model goes too, so
    // the next state that publishes offers only what it publishes itself.
    seed.coldStart();
    if (held) {
      RouteTables.Outcome outcome = tables.offer(ROUTE_TABLE);
      if (!(outcome instanceof RouteTables.Stored)) {
        throw new IllegalStateException(
            "State '" + state + "' did not store its route table: " + outcome);
      }
    }
    Map<String, String> params = new TreeMap<>();
    params.put("host", HOSTS.get(state));
    return new Setup(Collections.unmodifiableMap(params));
  }

  /** A public table, one application on {@code host} in prod, the default environment. */
  private static JsonObject routeTable(String host, long version) {
    return new JsonObject()
        .put("edge", "public")
        .put("version", version)
        .put("builtAt", "2026-11-13T09:20:00Z")
        .put("applications", new JsonArray().add(routedApplication("qits-ci", host, "/ci")))
        .put(
            "withdrawn",
            new JsonArray()
                .add(
                    new JsonObject()
                        .put("environment", "prod")
                        .put("application", "qits-stt")
                        .put("host", "stt")
                        .put("exposure", "private")));
  }

  private static JsonObject routedApplication(String application, String host, String path) {
    return new JsonObject()
        .put("environment", "prod")
        .put("application", application)
        .put("exposure", "public")
        .put("browserHost", host)
        .put("apiDocsPath", path + "/q/openapi")
        .put(
            "endpoints",
            new JsonArray()
                .add(
                    new JsonObject()
                        .put("path", path)
                        .put("upstreamHost", "prod-" + application)
                        .put("upstreamPort", 8080)))
        .put(
            "navigation",
            new JsonArray()
                .add(
                    new JsonObject()
                        .put("slot", "platform")
                        .put("label", "CI")
                        .put("position", 30)
                        .putNull("subpath")));
  }

  private static JsonObject invalidRouteTable() {
    JsonObject table = routeTable("ci", ROUTE_TABLE_VERSION + 1);
    table.getJsonArray("applications").add(routedApplication("qits-ci-copy", "ci", "/ci-copy"));
    return table;
  }

  /** Where the edge dials {@link #POOLED_APP} in {@link #POOLED_ENVIRONMENT}: the test override. */
  private Upstream pooledOrigin() {
    String address = config.apps().get(POOLED_APP).hosts().get(POOLED_ENVIRONMENT);
    return Upstream.parse(address, 8080);
  }

  /**
   * An admitted GET through the edge's own listener on {@code host}, as a TLS front would send it.
   * The JDK client, because it can send {@code Host} (the surefire argLine allows it); the port is
   * the one the {@code @QuarkusTest} harness bound, which it hands rest-assured before any test
   * runs. HTTP/1.1, as a TLS front speaks to the edge: the JDK client's default h2c upgrade makes
   * the edge pipe an empty DATA frame upstream, which Vert.x logs as an uncaught exception.
   */
  private static HttpResponse<String> get(String host, String path) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + RestAssured.port + path))
            .timeout(SETTLE)
            .header("Host", host)
            .header("X-Forwarded-Proto", "https")
            .header("Authorization", StubGateways.platformBearer())
            .GET();
    try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException("GET " + path + " on " + host + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** One application in one environment, as the deployer's table carries it. */
  private void publish(
      String environment, String application, String host, String path, List<JsonObject> nav) {
    publish(environment, application, host, path, path + "/q/swagger-ui", nav);
  }

  /** {@link #publish}, with the api docs path stated; null publishes none. */
  private void publish(
      String environment,
      String application,
      String host,
      String path,
      String apiDocsPath,
      List<JsonObject> nav) {
    JsonArray navigation = new JsonArray();
    nav.forEach(navigation::add);
    JsonObject payload =
        new JsonObject()
            .put("applicationName", application)
            .put("environmentName", environment)
            .put("browserHost", host)
            .put("apiDocsPath", apiDocsPath)
            .put(
                "endpoints",
                new JsonArray()
                    .add(
                        new JsonObject()
                            .put("path", path)
                            .put("upstreamHost", environment + "-" + application)
                            .put("upstreamPort", 8080)))
            .put("navigation", navigation);
    deploy(payload);
  }

  /** One deployment into the seeded route table; a refused table is this state's bug. */
  private void deploy(JsonObject deployment) {
    RouteTables.Outcome outcome = seed.deploy(deployment);
    if (!(outcome instanceof RouteTables.Stored)) {
      throw new IllegalStateException(
          "The route table refused " + deployment.getString("applicationName") + ": " + outcome);
    }
  }

  private static JsonObject placement(String slot, String label, int position, String subpath) {
    JsonObject placement =
        new JsonObject().put("slot", slot).put("label", label).put("position", position);
    if (subpath != null) {
      placement.put("subpath", subpath);
    }
    return placement;
  }
}
