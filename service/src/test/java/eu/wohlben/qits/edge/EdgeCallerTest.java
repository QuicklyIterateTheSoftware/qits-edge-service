package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.vertx.core.MultiMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * {@code X-Qits-Caller} for browser traffic, asserted without a boot: which page a request comes
 * from ({@link EdgeHeaders#callerOrigin}), which application serves that page ({@link
 * EdgeRouter#caller(String, Predicate, HostEnvironments, Map, Set, BiFunction)}), and that the
 * header a client sent itself never survives ({@link EdgeHeaders#applyIdentity}).
 *
 * <p>The mapping is read through the router's own routing join, so the estate here is spelled the
 * way {@code HostEnvironmentsTest} spells it: {@code qits} is the platform's env-less project,
 * whose own name is its landing and whose applications are one label in front of it; {@code acme}
 * has environments.
 */
class EdgeCallerTest {

  private static final String DOMAIN = "wohlben.eu";

  private static final HostEnvironments EDGE =
      HostEnvironments.of(List.of("prod", "dev"), "prod", PlatformApps.labels(), DOMAIN);

  private static final Map<String, Boolean> PROJECTS = projects();

  /**
   * Landing claimed in the default environment, which is what makes {@code qits.<domain>} a page.
   */
  private static final Set<String> LANDINGS = Set.of("prod");

  /** {@code EdgeSessions.admitsOrigin} under a real domain: https, and a name under the domain. */
  private static final Predicate<String> ADMITTED =
      origin ->
          origin.regionMatches(true, 0, "https://", 0, "https://".length())
              && EdgeSessions.underDomain(
                  EdgeSessions.authority(origin.substring("https://".length())),
                  Set.of(DOMAIN),
                  List.of(DOMAIN),
                  Integer.MAX_VALUE);

  /** environment → published label → service, as the projection serves it. */
  private static final Map<String, Map<String, EdgeRoutes.ServiceHost>> PUBLISHED =
      Map.of(
          "prod",
          Map.of(
              "landing", host("qits-landing", "landing"),
              "ci", host("qits-ci", "ci"),
              "projects", host("qits-projects", "projects")),
          "dev",
          Map.of("ci", host("qits-ci", "ci")));

  private static Map<String, Boolean> projects() {
    Map<String, Boolean> projects = new LinkedHashMap<>();
    projects.put("qits", false);
    projects.put("acme", true);
    return projects;
  }

  private static EdgeRoutes.ServiceHost host(String application, String label) {
    return new EdgeRoutes.ServiceHost(
        application, label, new Upstream("prod-" + application, 8080), "/" + label);
  }

  private static String caller(String origin) {
    return EdgeRouter.caller(
        origin,
        ADMITTED,
        EDGE,
        PROJECTS,
        LANDINGS,
        (environment, label) -> PUBLISHED.getOrDefault(environment, Map.of()).get(label));
  }

  // --- which page --------------------------------------------------------------------------------

  @Test
  void theOriginHeaderIsThePage() {
    assertEquals(
        "https://ci.qits.wohlben.eu",
        EdgeHeaders.callerOrigin(
            "https://ci.qits.wohlben.eu", "https://projects.qits.wohlben.eu/x"),
        "a present Origin wins over the Referer");
  }

  @Test
  void withoutAnOriginTheReferersOriginIsThePage() {
    // A same-origin GET carries no Origin; its Referer names the page, and only the origin of it is
    // kept — path, query and fragment are nobody's business here.
    assertEquals(
        "https://ci.qits.wohlben.eu",
        EdgeHeaders.callerOrigin(null, "https://ci.qits.wohlben.eu/runs/42?tab=log#step-3"));
    assertEquals(
        "http://ci.dev.acme.localhost:8080",
        EdgeHeaders.callerOrigin("  ", "http://ci.dev.acme.localhost:8080/"),
        "a blank Origin is an absent one, and the port is part of an origin");
  }

  @Test
  void aRefererThatIsNoPageNamesNone() {
    assertNull(EdgeHeaders.callerOrigin(null, null));
    assertNull(EdgeHeaders.callerOrigin(null, "/relative/path"));
    assertNull(EdgeHeaders.callerOrigin(null, "not a url at all"));
    assertNull(
        EdgeHeaders.callerOrigin(null, "https://user:pw@ci.qits.wohlben.eu/"),
        "user-info is not something a browser sends in a Referer");
  }

  @Test
  void anOpaqueOriginIsFinalAndTheRefererIsNotReadInstead() {
    // `Origin: null` is a browser deliberately hiding the page. It is kept, and is admitted by
    // nothing, so the request is nobody's.
    assertEquals("null", EdgeHeaders.callerOrigin("null", "https://ci.qits.wohlben.eu/"));
    assertNull(caller("null"));
  }

  // --- which application -------------------------------------------------------------------------

  @Test
  void aPublishedNameIsTheApplicationThatPublishedIt() {
    assertEquals("qits-ci", caller("https://ci.qits.wohlben.eu"));
    assertEquals("qits-projects", caller("https://projects.qits.wohlben.eu"));
    assertEquals("qits-ci", caller("https://ci.dev.acme.wohlben.eu"), "per environment");
    assertEquals("qits-ci", caller("HTTPS://CI.QITS.WOHLBEN.EU"), "case is not significant");
  }

  @Test
  void anEnvLessProjectsOwnNameIsItsLanding() {
    assertEquals("qits-landing", caller("https://qits.wohlben.eu"));
  }

  @Test
  void aPlatformVhostIsTheApplicationItFronts() {
    // registry published no host here, so the label's own mapping answers.
    assertEquals("qits-artifacts", caller("https://registry.qits.wohlben.eu"));
  }

  @Test
  void theReferersOriginMapsTheSameWay() {
    assertEquals(
        "qits-ci", caller(EdgeHeaders.callerOrigin(null, "https://ci.qits.wohlben.eu/runs/42")));
  }

  @Test
  void aForeignPageIsNobodys() {
    assertNull(caller("https://ci.qits.wohlben.eu.evil.example"));
    assertNull(caller("https://evil.example"));
    assertNull(caller("http://ci.qits.wohlben.eu"), "the wrong scheme is not the platform's page");
    assertNull(caller("http://localhost:4200"), "a loopback ng serve is admitted by CORS only");
  }

  @Test
  void aNameNoApplicationServesIsNobodys() {
    assertNull(caller("https://nosuchapp.qits.wohlben.eu"), "an unknown label");
    assertNull(
        caller("https://projects.dev.acme.wohlben.eu"), "a label published in another env only");
    assertNull(caller("https://ci.nosuchproject.wohlben.eu"), "an unknown project");
    assertNull(caller("https://acme.wohlben.eu"), "a door serves no page");
    assertNull(caller("https://wohlben.eu"), "nor does the apex");
    assertNull(
        caller("https://landing.qits.wohlben.eu"),
        "the reserved label at an app position is no address of the landing's");
    assertNull(caller(null));
  }

  // --- the header itself -------------------------------------------------------------------------

  @Test
  void aClientsOwnCallerIsStrippedAndTheEdgesReadingReplacesIt() {
    MultiMap headers = MultiMap.caseInsensitiveMultiMap();
    headers.add("x-qits-caller", "qits-projects");
    headers.add(EdgeHeaders.CALLER, "qits-idp");

    EdgeHeaders.applyIdentity(headers, null, "qits-ci");

    assertEquals(List.of("qits-ci"), headers.getAll(EdgeHeaders.CALLER));
  }

  @Test
  void aClientsOwnCallerIsStrippedAndNothingReplacesItWhenThePageIsNobodys() {
    MultiMap headers = MultiMap.caseInsensitiveMultiMap();
    headers.add(EdgeHeaders.CALLER, "qits-idp");

    EdgeHeaders.applyIdentity(headers, null, null);

    assertFalse(headers.contains(EdgeHeaders.CALLER));
  }

  @Test
  void theCallerIsAssertedAlongsideASessionsIdentity() {
    MultiMap headers = MultiMap.caseInsensitiveMultiMap();
    headers.add(EdgeHeaders.CALLER, "forged");

    EdgeHeaders.applyIdentity(
        headers, new EdgeSessions.Session("id-1", "operator", "qits:admin", 0L), "qits-landing");

    assertEquals("qits-landing", headers.get(EdgeHeaders.CALLER));
    assertEquals("operator", headers.get(EdgeHeaders.USER));
  }
}
