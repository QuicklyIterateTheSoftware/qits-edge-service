package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.edge.HostEnvironments.Reading;
import eu.wohlben.qits.edge.HostEnvironments.Route;
import java.util.Set;
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
    assertTrue(
        EdgeRouter.servedBeforeCatchUp(names.route("githost.dev.localhost:8080", projects)),
        "a machine name of a platform label too");

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
}
