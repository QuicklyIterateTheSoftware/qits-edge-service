package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.edge.HostEnvironments.Reading;
import eu.wohlben.qits.edge.HostEnvironments.Route;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The decisions {@code EdgeRouter} makes out of names alone, asserted without a boot.
 *
 * <p>Where the stated domain comes from is the first of them: every reading hangs off it, and it is
 * a value rather than something derived from a request. The rest are about caller input reaching an
 * answer — the 404 bodies write a label a client chose back out. None of them needs a Vert.x server
 * to be wrong, and a {@code @QuarkusTest} for any of them would be a restart the socket tests
 * cannot afford — see {@code EdgeRoutingTest}'s javadoc.
 */
class EdgeRouterNamesTest {

  @Test
  void theStatedDomainIsTheOneStatedValue() {
    // QITS_DOMAIN, and nothing else: no certificate domain and no canonical origin to read it back
    // off. It is normalised the way a request's own Host is — one spelling of that reading.
    assertEquals("wohlben.eu", EdgeRouter.domain("wohlben.eu"));
    assertEquals("wohlben.eu", EdgeRouter.domain("  WOHLBEN.eu  "));
    assertEquals("wohlben.eu", EdgeRouter.domain("wohlben.eu."), "a resolver's trailing root dot");
    assertEquals("localhost", EdgeRouter.domain("localhost"), "the local default");
  }

  @Test
  void onlyAPlatformLabelIsServedBeforeTheCatchUpEnds() {
    // qits-1403: the edge's own catch-up dials qits-events, which fetches its keys from
    // idp.qits.<domain> through the edge — so idp's name cannot wait behind the replay. Every
    // platform label is answered from code; everything else keeps the 503.
    HostEnvironments names =
        HostEnvironments.of(
            java.util.List.of("prod", "dev"), "prod", PlatformApps.labels(), "wohlben.eu");
    java.util.Map<String, Boolean> projects = java.util.Map.of("qits", false, "acme", true);

    assertTrue(EdgeRouter.servedBeforeCatchUp(names.route("idp.qits.wohlben.eu", projects)));
    assertTrue(EdgeRouter.servedBeforeCatchUp(names.route("registry.qits.wohlben.eu", projects)));
    assertFalse(
        EdgeRouter.servedBeforeCatchUp(names.route("githost.dev.localhost:8080", projects)),
        "a retired machine name is no application position (qits-1433)");

    assertFalse(
        EdgeRouter.servedBeforeCatchUp(names.route("ci.dev.acme.wohlben.eu", projects)),
        "a published name needs the projection");
    assertFalse(EdgeRouter.servedBeforeCatchUp(names.route("qits.wohlben.eu", projects)));
    assertFalse(EdgeRouter.servedBeforeCatchUp(names.route("wohlben.eu", projects)));
    assertFalse(
        EdgeRouter.servedBeforeCatchUp(names.route("idp.nosuchproject.wohlben.eu", projects)),
        "an unknown project is no application position");
  }

  @Test
  void onlyTheEdgesOwnNameAnswersItsManagementSurface() {
    // qits-2222: answered whatever the Host said, an app's /q/health/ready reported the EDGE's
    // health — a stopped app read healthy while every other path on its name 404ed. The apex
    // reading is the edge's own name; every other reading routes /q/* like any other path.
    HostEnvironments names =
        HostEnvironments.of(
            java.util.List.of("prod", "dev"), "prod", PlatformApps.labels(), "wohlben.eu");
    java.util.Map<String, Boolean> projects = java.util.Map.of("qits", false, "acme", true);
    String ready = "/q/health/ready";

    for (String own :
        new String[] {
          null,
          "",
          "localhost",
          "localhost:8080",
          "dev-qits-edge",
          "127.0.0.1",
          "[::1]:443",
          "wohlben.eu",
          "WOHLBEN.eu."
        }) {
      Route named = names.route(own, projects);
      assertEquals(Reading.APEX, named.reading(), String.valueOf(own));
      assertTrue(EdgeRouter.answeredByTheEdge(named, ready, "/q"), String.valueOf(own));
      assertTrue(EdgeRouter.answeredByTheEdge(named, "/q", "/q"), "the root itself");
      assertTrue(EdgeRouter.answeredByTheEdge(named, "/q/health/live", "/q"));
      assertFalse(
          EdgeRouter.answeredByTheEdge(named, "/queue/items", "/q"),
          "the prefix is a path segment, not a substring");
      assertFalse(EdgeRouter.answeredByTheEdge(named, "/", "/q"), "only the management root");
    }
    assertTrue(
        EdgeRouter.answeredByTheEdge(names.route("localhost", projects), "/mgmt/health", "/mgmt"),
        "the configured root, not a literal /q");
    assertFalse(
        EdgeRouter.answeredByTheEdge(names.route("localhost", projects), ready, "/mgmt"),
        "under another root /q is an ordinary path");

    for (String other :
        new String[] {
          "orchestrator.qits.wohlben.eu", // a platform app
          "idp.qits.wohlben.eu", // the one served before the catch-up
          "ci.dev.acme.wohlben.eu", // a published app
          "nosuchapp.dev.acme.wohlben.eu", // an app-shaped name nothing routes
          "acme.wohlben.eu", // a project door
          "dev.acme.wohlben.eu", // an environment door
          "qits.wohlben.eu", // the platform project's door
          "idp.nosuchproject.wohlben.eu", // an unknown project
          "a.b.c.d.wohlben.eu", // unreadable
          "example.org" // outside the domain
        }) {
      Route named = names.route(other, projects);
      assertFalse(
          EdgeRouter.answeredByTheEdge(named, ready, "/q"),
          other + " reads " + named.reading() + " and must never report the edge's health");
    }
  }

  @Test
  void theManagementDecisionIsHandedTheNormalisedPath() {
    // qits-2229: answeredByTheEdge compares a prefix and nothing more, so it is only as good as the
    // spelling it is handed. Quarkus' /q routes are matched by Vert.x on normalizedPath(), so that
    // is what EdgeRouter passes — never request.path(). These rows pin the contract: the raw
    // spellings below all normalise to /q/health/ready, and read raw they would not be recognised
    // as
    // management at all — which on an app's name is exactly how they used to slip past the
    // non-application route and reach the edge's own health.
    HostEnvironments names =
        HostEnvironments.of(
            java.util.List.of("prod", "dev"), "prod", PlatformApps.labels(), "wohlben.eu");
    java.util.Map<String, Boolean> projects = java.util.Map.of("qits", false, "acme", true);
    String normalised = io.vertx.core.http.impl.HttpUtils.normalizePath("/q/health/ready");
    Route apex = names.route("localhost", projects);
    Route app = names.route("ci.dev.acme.wohlben.eu", projects);

    for (String raw :
        new String[] {"/%71/health/ready", "//q/health/ready", "/x/../q/health/ready"}) {
      assertEquals(
          normalised,
          io.vertx.core.http.impl.HttpUtils.normalizePath(raw),
          raw + " is /q/health/ready to the router");
      assertFalse(
          EdgeRouter.answeredByTheEdge(apex, raw, "/q"),
          raw + " read raw is not recognised, which is why the caller normalises first");
    }
    assertTrue(EdgeRouter.answeredByTheEdge(apex, normalised, "/q"));
    assertFalse(
        EdgeRouter.answeredByTheEdge(app, normalised, "/q"),
        "an app's name never answers the edge's health, however it was spelled");
  }

  @Test
  void onlyACredentialOnIdpsOwnRoutePassesThroughUnchecked() {
    io.vertx.core.MultiMap credential =
        io.vertx.core.MultiMap.caseInsensitiveMultiMap().add("authorization", "Basic eDp5");
    io.vertx.core.MultiMap none = io.vertx.core.MultiMap.caseInsensitiveMultiMap();
    Route idp = Route.app("prod", "idp", "qits");

    assertTrue(EdgeRouter.passesThroughToIdp(idp, "/idp/token", credential));
    assertTrue(EdgeRouter.passesThroughToIdp(idp, "/idp/api/tokens/introspect", credential));
    assertFalse(
        EdgeRouter.passesThroughToIdp(idp, "/idp/login", none),
        "no credential keeps the ordinary path: anonymous prefix or session");
    assertFalse(EdgeRouter.passesThroughToIdp(idp, "/v2/", credential), "off idp's own route");
    assertFalse(
        EdgeRouter.passesThroughToIdp(
            Route.app("prod", "registry", "qits"), "/idp/token", credential),
        "another label's /idp/ is not idp's");
  }

  @Test
  void nothingStatedIsNothingComposed() {
    // Not reachable through configuration — the key has a default — but the normalisation is the
    // same function a Host goes through, and an empty domain is what EdgeSessions refuses at boot.
    assertEquals("", EdgeRouter.domain("  "));
    assertEquals("", EdgeRouter.domain(null));
  }

  @Test
  void theUnknownAppAnswerEchoesTheLabelOnlyWhenItIsOne() {
    Set<String> apps = Set.of("registry");

    assertTrue(
        EdgeRouter.unknownAppBody(new HostEnvironments.Route("dev", null, "nosuchapp"), apps)
            .contains("`nosuchapp` is not an application"));
    assertTrue(
        EdgeRouter.unknownAppBody(
                new HostEnvironments.Route("dev", null, "nosuchapp", "acme", Reading.UNKNOWN_APP),
                apps)
            .contains("the project `acme`"));
  }

  @Test
  void aLabelThatIsNotOneIsDescribedRatherThanQuotedBack() {
    // The label is the leftmost one of a Host header, so it is attacker input wherever it is
    // written. `Host: .dev.acme.example.com` produced a sentence about an empty name, and anything
    // a
    // header parser let through came back verbatim into a body a browser renders.
    Set<String> apps = Set.of("registry");

    for (String label : new String[] {"", "<script>alert(1)</script>", "a b", "-nope-", null}) {
      String body = EdgeRouter.unknownAppBody(new HostEnvironments.Route("dev", null, label), apps);
      assertTrue(body.startsWith("That first label is not an application"), body);
      if (label != null && !label.isEmpty()) {
        assertFalse(body.contains(label), body);
      }
    }
  }

  @Test
  void anUnknownProjectLabelIsDescribedRatherThanQuotedBackToo() {
    // The same rule one tier to the right: the project label is caller input as well, and this
    // sentence is the one a mistyped project name gets.
    assertTrue(
        EdgeRouter.unknownProjectBody(Route.unknownProject("prod", "nosuch"), "wohlben.eu")
            .startsWith("`nosuch` is not a project"));
    String laundered =
        EdgeRouter.unknownProjectBody(Route.unknownProject("prod", null), "wohlben.eu");
    assertTrue(laundered.startsWith("That label is not a project"), laundered);
    assertTrue(laundered.contains("wohlben.eu"), "and it names the domain it was read from");
  }

  @Test
  void eachDoorSaysWhichDoorItIs() {
    assertTrue(
        EdgeRouter.doorBody(Route.apex("prod"), "wohlben.eu")
            .contains("<app>.<project>.wohlben.eu"),
        "the apex offers the grammar and names no project at all");
    assertTrue(
        EdgeRouter.doorBody(Route.projectDoor("prod", "acme"), "wohlben.eu")
            .contains("<app>.acme.wohlben.eu"));
    assertTrue(
        EdgeRouter.doorBody(Route.environmentDoor("dev", "acme"), "wohlben.eu")
            .contains("<app>.dev.acme.wohlben.eu"));
  }

  @Test
  void aNameOutsideTheGrammarIsToldWhichWayItIsWrong() {
    assertTrue(
        EdgeRouter.unreadableBody(Route.unreadable("prod", null), "wohlben.eu")
            .contains("more labels than the grammar has"));
    assertTrue(
        EdgeRouter.unreadableBody(Route.unreadable("prod", "acme"), "wohlben.eu")
            .contains("does not have an environment by that name"));
  }

  // --- a private deployment's name on the public edge (qits-2223)
  // ---------------------------------

  private static Function<String, Map<String, EdgeRoutes.Withdrawn>> withdrawn(
      String host, String exposure) {
    Map<String, EdgeRoutes.Withdrawn> dev =
        Map.of("qits-mcp", new EdgeRoutes.Withdrawn(host, exposure));
    return environment -> "dev".equals(environment) ? dev : Map.of();
  }

  @Test
  void aPrivateWithdrawalOnThePublicEdgeIsMisdirected() {
    assertTrue(
        EdgeRouter.withdrawnToPrivateEdge(
            new Route("dev", null, "mcp"), ExposureClass.PUBLIC, withdrawn("mcp", "private")));
    assertTrue(
        EdgeRouter.withdrawnToPrivateEdge(
            new Route("dev", null, "MCP"), ExposureClass.PUBLIC, withdrawn("Mcp", "private")),
        "case-insensitive, as a DNS name is");
    assertTrue(
        EdgeRouter.withdrawnToPrivateEdge(
            new Route("dev", null, "mcp", "acme", Reading.UNKNOWN_APP),
            ExposureClass.PUBLIC,
            withdrawn("mcp", "private")),
        "a project's name the same");
  }

  @Test
  void onlyThePublicEdgeAndOnlyAPrivateWithdrawalAreMisdirected() {
    Route mcp = new Route("dev", null, "mcp");
    assertFalse(
        EdgeRouter.withdrawnToPrivateEdge(mcp, ExposureClass.PRIVATE, withdrawn("mcp", "private")),
        "the private edge withdraws public deployments; this rule is not about it");
    assertFalse(
        EdgeRouter.withdrawnToPrivateEdge(mcp, ExposureClass.ALL, withdrawn("mcp", "private")));
    assertFalse(
        EdgeRouter.withdrawnToPrivateEdge(mcp, ExposureClass.PUBLIC, withdrawn("mcp", "public")),
        "a public withdrawal is what the private edge stores, never a 421 here");
    assertFalse(
        EdgeRouter.withdrawnToPrivateEdge(mcp, ExposureClass.PUBLIC, withdrawn(null, "private")),
        "a withdrawal whose host failed the rules names nothing");
    assertFalse(
        EdgeRouter.withdrawnToPrivateEdge(
            new Route("dev", null, "nosuch"), ExposureClass.PUBLIC, withdrawn("mcp", "private")),
        "a label nobody publishes or withdraws keeps its 404");
    assertFalse(
        EdgeRouter.withdrawnToPrivateEdge(
            new Route("prod", null, "mcp"), ExposureClass.PUBLIC, withdrawn("mcp", "private")),
        "withdrawals are per environment");
    assertFalse(
        EdgeRouter.withdrawnToPrivateEdge(
            Route.apex("dev"), ExposureClass.PUBLIC, withdrawn("mcp", "private")),
        "a door is never misdirected");
  }

  @Test
  void theMisdirectedAnswerNamesThePrivateEdgeAndLaundersTheLabel() {
    String body = EdgeRouter.misdirectedBody(new Route("dev", null, "mcp"));
    assertTrue(body.startsWith("`mcp` is served only by the private edge"), body);
    assertTrue(body.contains("100.96.0.1"), body);
    assertTrue(body.endsWith("\n"), body);
    for (String label : new String[] {"", "<script>alert(1)</script>", "a b", null}) {
      String laundered = EdgeRouter.misdirectedBody(new Route("dev", null, label));
      assertTrue(laundered.startsWith("That first label is served only by"), laundered);
      if (label != null && !label.isEmpty()) {
        assertFalse(laundered.contains(label), laundered);
      }
    }
    // And the 404 an unknown label gets is untouched by it.
    assertTrue(
        EdgeRouter.unknownAppBody(new Route("dev", null, "nosuch"), Set.of("registry"))
            .startsWith("`nosuch` is not an application this edge routes. Configured: [registry]"));
  }
}
