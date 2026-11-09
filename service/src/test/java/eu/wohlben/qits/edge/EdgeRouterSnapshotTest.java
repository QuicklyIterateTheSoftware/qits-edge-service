package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/**
 * {@link EdgeRouter#routesToSnapshot}, the question the tunnel DNS responder asks, read through the
 * router's own join on a private edge — no platform labels, so only what was published routes.
 */
class EdgeRouterSnapshotTest {

  private static final HostEnvironments PRIVATE_EDGE =
      HostEnvironments.of(
          List.of("prod", "dev"),
          "prod",
          EdgeRouter.platformLabels(ExposureClass.PRIVATE),
          "wohlben.eu");

  private static final HostEnvironments PUBLIC_EDGE =
      HostEnvironments.of(
          List.of("prod", "dev"),
          "prod",
          EdgeRouter.platformLabels(ExposureClass.PUBLIC),
          "wohlben.eu");

  private static final Map<String, Map<String, EdgeRoutes.ServiceHost>> PUBLISHED =
      Map.of(
          "prod", Map.of("landing", host("qits-landing", "landing"), "ci", host("qits-ci", "ci")),
          "dev", Map.of("ci", host("qits-ci", "ci")));

  private static final BiFunction<String, String, EdgeRoutes.ServiceHost> JOIN =
      (environment, label) -> PUBLISHED.getOrDefault(environment, Map.of()).get(label);

  private static EdgeRoutes.ServiceHost host(String application, String label) {
    return new EdgeRoutes.ServiceHost(
        application, label, new Upstream("prod-" + application, 8080), "/" + label);
  }

  private static boolean routes(HostEnvironments edge, String name) {
    Map<String, Boolean> projects = new LinkedHashMap<>();
    projects.put("qits", false);
    projects.put("acme", true);
    return EdgeRouter.routesToSnapshot(edge.route(name, projects, Set.of("prod")), JOIN);
  }

  @Test
  void aPublishedNameRoutesToItsSnapshot() {
    assertTrue(routes(PRIVATE_EDGE, "ci.qits.wohlben.eu"));
    assertTrue(routes(PRIVATE_EDGE, "ci.dev.acme.wohlben.eu"));
    assertTrue(routes(PRIVATE_EDGE, "CI.Dev.Acme.wohlben.eu."), "case and the root dot");
    assertTrue(routes(PRIVATE_EDGE, "qits.wohlben.eu"), "an env-less project's landing");
  }

  @Test
  void everythingElseDoesNot() {
    assertFalse(routes(PRIVATE_EDGE, "wohlben.eu"), "the apex is a door");
    assertFalse(routes(PRIVATE_EDGE, "acme.wohlben.eu"), "a project door");
    assertFalse(routes(PRIVATE_EDGE, "dev.acme.wohlben.eu"), "an environment door");
    assertFalse(routes(PRIVATE_EDGE, "nope.dev.acme.wohlben.eu"), "nothing published it");
    assertFalse(routes(PRIVATE_EDGE, "ci.nobody.wohlben.eu"), "an unknown project");
    assertFalse(routes(PRIVATE_EDGE, "ci.qits.example.com"), "outside the domain");
    assertFalse(routes(PRIVATE_EDGE, "registry.qits.wohlben.eu"), "no platform labels here");
  }

  @Test
  void aPlatformLabelNothingPublishedIsNoSnapshot() {
    // The public edge routes it from code, but holds no snapshot for it.
    assertFalse(routes(PUBLIC_EDGE, "registry.qits.wohlben.eu"));
    assertTrue(routes(PUBLIC_EDGE, "ci.qits.wohlben.eu"));
  }
}
