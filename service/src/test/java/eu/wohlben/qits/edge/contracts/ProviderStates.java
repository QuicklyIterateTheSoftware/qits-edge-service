package eu.wohlben.qits.edge.contracts;

import eu.wohlben.qits.edge.DeploymentActiveSubscriber;
import eu.wohlben.qits.edge.EdgeConfig;
import eu.wohlben.qits.edge.EdgeProjects;
import eu.wohlben.qits.edge.EdgeRoutes;
import eu.wohlben.qits.eventstream.control.EventFrame;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
 * same projection. What it relies on is that nothing else publishes in its JVM — the {@code
 * contract-tests} surefire execution in {@code service/pom.xml} runs this package alone, on a
 * database Flyway cleaned at start.
 *
 * <p><b>The edge routes on {@code Host}</b>, so a state names the host its operation is asked on
 * ({@code host}) and both callers send it, with {@code X-Forwarded-Proto: https}, as {@link
 * #requestHeaders} says. The verifier reads it by state name, not from the pact's params, so a
 * consumer cannot point the edge at another host.
 */
@ApplicationScoped
public class ProviderStates {

  public static final String A_PUBLISHED_NAVIGATION = "a published navigation";

  /**
   * The platform's own project. It has no environment tier, so its applications are {@code
   * <app>.qits.<domain>} and they are served in the default environment.
   */
  static final String PROJECT = "qits";

  /** {@code StubGateways.DOMAIN}, the domain the test configuration states. */
  static final String DOMAIN = "example.com";

  /** What a state hands back: its parameters, keys sorted. */
  public record Setup(Map<String, String> params) {}

  @Inject DeploymentActiveSubscriber deployments;
  @Inject EdgeProjects projects;
  @Inject EdgeRoutes routes;
  @Inject EdgeConfig config;

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();

  public ProviderStates() {
    states.put(A_PUBLISHED_NAVIGATION, this::aPublishedNavigation);
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
      Map.of(A_PUBLISHED_NAVIGATION, "projects." + PROJECT + "." + DOMAIN);

  /**
   * The headers a request in a state carries: the state's host, and the scheme a TLS front reports,
   * so every origin in the answer is {@code https} as it is on the platform.
   */
  public static Map<String, String> requestHeaders(String state) {
    String host = HOSTS.get(state);
    if (host == null) {
      throw new IllegalArgumentException("No host for the provider state '" + state + "'");
    }
    return Map.of("Host", host, "X-Forwarded-Proto", "https");
  }

  // --- the states ------------------------------------------------------------------------------

  /**
   * The platform project {@code qits} with six applications deployed, each on a host of its own:
   * qits-projects, qits-githost, qits-events, qits-maintenance, qits-idp and qits-workspaces. Each
   * places navigation and publishes api docs, so every part of the document has content. Asked on
   * qits-projects' host.
   *
   * <p>qits-landing is left out on purpose: the edge reports it at {@code landing.<project>}, which
   * is a known, parked defect, and a golden master would publish that as the contract.
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
        List.of(
            placement("project.detail", "Workspaces", 1, null),
            placement("project.detail", "Editor", 2, "editor")));

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
            "qits-workspaces")) {
      if (!published.contains(application)) {
        throw new IllegalStateException(
            "State '" + A_PUBLISHED_NAVIGATION + "' did not publish " + application);
      }
    }
    Map<String, String> params = new TreeMap<>();
    params.put("host", HOSTS.get(A_PUBLISHED_NAVIGATION));
    return new Setup(Collections.unmodifiableMap(params));
  }

  /** One application's whole snapshot in one environment, as the deployments service sends it. */
  private void publish(
      String environment, String application, String host, String path, List<JsonObject> nav) {
    JsonArray navigation = new JsonArray();
    nav.forEach(navigation::add);
    JsonObject payload =
        new JsonObject()
            .put("applicationName", application)
            .put("environmentName", environment)
            .put("browserHost", host)
            .put("apiDocsPath", path + "/q/swagger-ui")
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
