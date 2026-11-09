package eu.wohlben.qits.edge.contracts;

import eu.wohlben.qits.edge.DeploymentActiveSubscriber;
import eu.wohlben.qits.edge.EdgeConfig;
import eu.wohlben.qits.edge.EdgeProjects;
import eu.wohlben.qits.edge.EdgeRoutes;
import eu.wohlben.qits.edge.StubGateways;
import eu.wohlben.qits.edge.Upstream;
import eu.wohlben.qits.eventstream.control.EventFrame;
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
   * One idle ordinary connection pooled to qits-artifacts' registry in {@code dev}, the only
   * connection the edge holds open. {@code /upstream-pools} answers only a caller the edge would
   * let through to a service, so {@link #requestHeaders} carries a bearer for this state, which the
   * verifier sets over whatever the pact's request holds: <b>a consumer pact against this state
   * must send {@code Authorization}</b> (any {@code Bearer} value — the provider substitutes a live
   * one), as the consumer itself must on the platform.
   */
  public static final String AN_OPEN_UPSTREAM_POOL = "an open upstream pool";

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

  @Inject DeploymentActiveSubscriber deployments;
  @Inject EdgeProjects projects;
  @Inject EdgeRoutes routes;
  @Inject EdgeConfig config;

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();

  public ProviderStates() {
    states.put(A_PUBLISHED_NAVIGATION, this::aPublishedNavigation);
    states.put(AN_OPEN_UPSTREAM_POOL, this::anOpenUpstreamPool);
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
          // Any name answers /upstream-pools; the platform project's own door is the one a shell
          // asks it on.
          AN_OPEN_UPSTREAM_POOL,
          PROJECT + "." + DOMAIN);

  /** The states whose operation is answered only to an admitted caller. */
  private static final Set<String> CREDENTIALED = Set.of(AN_OPEN_UPSTREAM_POOL);

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

    // The subscriber settles a frame it refuses with a log line and nothing else, so a state that
    // published less than it says would only show up later as a confusing body diff.
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

  /** One application's whole snapshot in one environment, as the deployments service sends it. */
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
    deployments.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            "DeploymentActive",
            Instant.now(),
            payload.encode(),
            null,
            null,
            environment));
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
