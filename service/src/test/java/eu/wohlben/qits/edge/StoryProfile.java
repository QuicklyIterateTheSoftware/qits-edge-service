package eu.wohlben.qits.edge;

import eu.wohlben.qits.edge.testdb.EmbeddedPg;
import eu.wohlben.qits.servicemock.MockService;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>One launched qits-platform-edge for the whole story catalogue</b>, and every seam a story
 * moves, declared once.
 *
 * <p>A {@code @TestProfile} is what failsafe launches a process for, so two profiles would be two
 * front doors — two boots, two databases, two session caches, two routing projections. Every story
 * class in this repository names this one; {@code ForwardAuthBootstrapIT} included, which is a
 * story class like the others and happens to be the oldest.
 *
 * <p><b>Every key here is a RUNTIME key.</b> A packaged process takes its configuration as {@code
 * -D} arguments on an artifact that was already built, so a build-time key would be silently
 * ignored and these stories would prove something other than what they say. Everything that makes
 * this service what it is stays exactly as it ships: the 1088M body ceiling, the one-hour idle
 * timeout that keeps terminals and SSE channels alive, the {@code /q} non-application root, the
 * OTel logging quartet, ACME off, and — above all — the {@code X-Qits-*} strip rule itself and the
 * {@code Cache-Control} rewrite, which are code and not configuration.
 *
 * <h2>What is configured, and why each one</h2>
 *
 * <ul>
 *   <li><b>The two database triples</b> — the platform's generic resource contract, which
 *       qits-platform-deployments injects and the shipped datasource expressions read with no
 *       defaults on purpose. {@code edge} is the routing projection; {@code eventstream} is
 *       qits-eventstream's claim ledger, and it is needed even though the bus is dark below,
 *       because dark does not mean absent: Quarkus opens the connection and runs Flyway at boot
 *       regardless. Both are databases of this catalogue's own on the same embedded postgres the
 *       surefire suite spawns, so a launched process and any suite can never mean the same schema.
 *       Their urls are carried through system properties rather than static fields, because a test
 *       profile is instantiated in more than one classloader and a field written by one copy is not
 *       the field another reads.
 *   <li><b>The environment list and FOUR application vhosts</b> — the environments stated exactly
 *       as {@code QITS_EDGE_ENVIRONMENTS} states them; three hosts seeded into the projection (see
 *       below) and the mirror, a platform label, re-addressed by its {@code hosts.dev} override.
 *       The mirror's derived alias is a name nothing here resolves: a request that reached it would
 *       be a resolution bug rather than a test that happened to pass.
 *       <ul>
 *         <li>{@code tracker} and {@code docs} are two ordinary services, gated identically, and
 *             they exist as a PAIR: they answer the same paths, so which of them received a request
 *             is the whole of what a Host name decided.
 *         <li>{@code mirror} is a MACHINE vhost whose reads a deployment opened. No browser talks
 *             to it, which makes it the name on which a person's cookie must not arrive.
 *         <li>{@code offline} is published, routed, and points at a port nothing listens on. That
 *             is precisely NOT the same as a name nobody claims: this one is somebody's, and the
 *             somebody is not there.
 *       </ul>
 *   <li><b>The browser gate</b> — the flag this service ships DARK, plus the credential and the
 *       origins that make it legal to turn on. This is the point of the whole run: with the flag
 *       false a request takes the path it took before the gate existed, and most of what is below
 *       would not be under test.
 *   <li><b>Four neutralisations.</b> The event bus is dark and its address is a closed port, so a
 *       projection this run does not use cannot spend it dialling {@code qits-events}, a name that
 *       resolves on qits-net and nowhere else. The startup catch-up barrier is off, which is the
 *       code's own escape hatch for a deliberately offline setup and the same line the suite's
 *       properties file carries — with it on, every request answers 503 while a bus that is not
 *       there is waited for. And OTel is dark, like {@code %dev}/{@code %test}: the shipped
 *       exporter points at {@code http://dev-qits-observability:8080}, another qits-net-only name.
 * </ul>
 *
 * <h2>The deployment projection is seeded, not replayed</h2>
 *
 * <p>A name reaches a service two ways: a platform app label ({@code PlatformApps}, in code — the
 * mirror here), and the projection, which is rebuilt from qits-events at boot. A launched process
 * has no bus, and these stories are about what the edge does WITH a request rather than about how
 * it learnt the route. So the projection's rows are written into the edge database before the
 * process starts — exactly the rows {@code EdgeRoutes.apply} writes for a DeploymentActive that
 * publishes a host — and the process loads them at {@code StartupEvent} like any persisted
 * snapshot. {@code qits.edge.projection.catchup.required=false} is the escape hatch the code
 * carries for the bus being absent.
 *
 * <p>{@code tracker} (qits-projects' stand-in), {@code docs} and {@code offline} are therefore
 * PUBLISHED hosts, which is what they are on the platform too: an ordinary service publishes the
 * name it answers to. They used to be configured {@code qits.edge.apps} entries, and that door is
 * gone (qits-528): the platform's app labels and their addresses are code, and a {@code
 * hosts.<env>} override can re-address one of them — the mirror's stand-in, below — but can no
 * longer add a name.
 */
public class StoryProfile implements QuarkusTestProfile {

  /** Where each url is parked for whichever copy of this class is asked second. */
  private static final String EDGE_URL_PROPERTY = "qits.test.userflow-it.edge-db-url";

  private static final String EVENTSTREAM_URL_PROPERTY = "qits.test.userflow-it.eventstream-db-url";

  /**
   * Marks the far side as started and armed, for the same reason {@code MockService.ensureStarted}
   * parks its port: a test profile is instantiated in more than one classloader and a static field
   * written by one copy is not the field another reads, while the JVM has exactly one property
   * table.
   */
  private static final String ARMED_PROPERTY = "qits.edge.it.far-side-armed";

  @Override
  public Map<String, String> getConfigOverrides() {
    // Started HERE rather than in a story class, because the launched process needs their addresses
    // in its command line — which is built from exactly this map. `named` is start-or-attach, so
    // the
    // second classloader to arrive gets the first one's ports.
    StoryUpstream projects = StoryUpstream.named(StoryTarget.PROJECTS);
    StoryUpstream docs = StoryUpstream.named(StoryTarget.DOCS);
    StoryUpstream mirror = StoryUpstream.named(StoryTarget.MIRROR);
    MockService idp = MockService.ensureStarted(StoryTarget.IDP);
    // The handle is HANDED to arm() rather than fetched again inside it. MockService.ensureStarted
    // starts once per JVM and every later call comes back ATTACHED — and an attached handle refuses
    // to register a stub, by design, because a stub lives on the instance that owns the server. So
    // the one call that may be the owner is the one whose result is passed along.
    arm(idp);

    // LinkedHashMap rather than Map.of: the order is the order this file explains them in, and a
    // reader diffing a launch command should find them in it.
    Map<String, String> config = new LinkedHashMap<>();

    String edgeUrl = databaseUrl(EDGE_URL_PROPERTY, "edge_userflows_it");
    seedProject(edgeUrl);
    config.put("QITS_RESOURCE_EDGE_URL", edgeUrl);
    config.put("QITS_RESOURCE_EDGE_USERNAME", EmbeddedPg.USER);
    config.put("QITS_RESOURCE_EDGE_PASSWORD", EmbeddedPg.PASSWORD);
    config.put(
        "QITS_RESOURCE_EVENTSTREAM_URL",
        databaseUrl(EVENTSTREAM_URL_PROPERTY, "edge_eventstream_userflows_it"));
    config.put("QITS_RESOURCE_EVENTSTREAM_USERNAME", EmbeddedPg.USER);
    config.put("QITS_RESOURCE_EVENTSTREAM_PASSWORD", EmbeddedPg.PASSWORD);

    config.put("qits.edge.environments", "prod," + StoryTarget.ENVIRONMENT);
    config.put("qits.edge.default-environment", "prod");
    // Three published hosts, written into the projection before the launch (see the class javadoc).
    seedPublishedHosts(
        edgeUrl,
        List.of(
            new Published(StoryTarget.PROJECTS, StoryTarget.PROJECTS_APP, projects.address()),
            new Published(StoryTarget.DOCS, StoryTarget.DOCS_APP, docs.address()),
            new Published(
                StoryTarget.OFFLINE, StoryTarget.OFFLINE_APP, StoryTarget.CLOSED_PORT_ADDRESS)));
    // The mirror is a PLATFORM app label: where it goes is code (dev-qits-mirror), so the stand-in
    // reaches it through the one override that exists for a local process and a suite.
    config.put(
        "qits.edge.apps." + StoryTarget.MIRROR_APP + ".hosts." + StoryTarget.ENVIRONMENT,
        mirror.address());
    // ONE of the four, which is the point: the exemption is per app label, so the catalogue has a
    // vhost whose reads are open beside three that are gated on every method.
    config.put("qits.edge.auth.anonymous-read-apps", StoryTarget.MIRROR_APP);

    config.put("qits.edge.sessions.enabled", "true");
    config.put("qits.edge.domain", StoryTarget.DOMAIN);
    // No browser-host list and no canonical origin: both are derived from that one stated domain.
    config.put("qits.edge.sessions.client-id", StoryTarget.EDGE_CLIENT_ID);
    config.put("qits.edge.sessions.client-secret", StoryTarget.EDGE_CLIENT_SECRET);

    // The dial address only: /jwks, /token and /api/sessions/introspect are derived from it in
    // Idp.java, so a rename on either side fails here rather than in production. The issuer is not
    // a key at all — it is derived from qits.edge.domain.
    config.put("qits.idp.dial-url", idp.baseUrl() + "/idp");

    config.put("qits.eventstream.enabled", "false");
    config.put("qits.events.url", StoryTarget.CLOSED_PORT_URL);
    config.put("qits.edge.projection.catchup.required", "false");
    config.put("quarkus.otel.sdk.disabled", "true");

    return Map.copyOf(config);
  }

  /**
   * Every route the catalogue's far side answers, armed once per JVM.
   *
   * <p><b>Only the paths that must ARRIVE are armed.</b> Every path that must not is left unarmed
   * on purpose: an unarmed route is still RECORDED, so "the request never reached the service" is
   * asserted on the recordings rather than inferred from a status the edge chose, and a strip that
   * silently started forwarding refused requests would fail loudly rather than pass quietly.
   *
   * <p>idp gets one route, and it answers the same session for any cookie. That is the honest limit
   * of a canned stub: what is under test here is what the edge does WITH idp's answer and that it
   * asked for one over a real socket with its own credential, not idp's ability to tell a live
   * cookie from a revoked one — {@code EdgeSessionGateTest}'s stub idp carries three sessions, a
   * revocation and an outage, and pins that half against the suite's own JVM. The answer's SHAPE is
   * qits-platform-idp's own, because {@code EdgeSessions.read} refuses one it cannot read and a
   * stub that got it wrong would prove a refusal while looking like a proof of admission.
   */
  static synchronized void arm(MockService idp) {
    if (System.getProperty(ARMED_PROPERTY) != null) {
      return;
    }

    StoryUpstream projects = StoryUpstream.named(StoryTarget.PROJECTS);
    projects.json(StoryTarget.IDENTITY_PATH, "{\"answered\":\"qits-projects read its caller\"}");
    projects.json(
        StoryTarget.SECOND_PATH, "{\"answered\":\"qits-projects read its caller again\"}");
    projects.json(StoryTarget.ROUTED_PATH, "{\"service\":\"qits-projects\"}");
    projects.json(StoryTarget.OUTAGE_PATH, "{\"service\":\"qits-projects\",\"repositories\":[]}");
    // The three shapes a Quinoa-served SPA has. Every static resource carries the Quarkus default
    // whether or not its name is content-hashed; a handler that made a decision of its own carries
    // that decision instead. Which of those the edge may correct is EdgeCacheControl's whole
    // subject, so all three are served rather than only the interesting one.
    projects.answer(
        StoryTarget.SPA_DOCUMENT,
        200,
        "text/html; charset=utf-8",
        "<!doctype html><html><head><script src=\""
            + StoryTarget.SPA_BUNDLE
            + "\"></script>"
            + "</head><body>qits-projects</body></html>",
        Map.of("Cache-Control", EdgeCacheControl.STATIC_DEFAULT));
    projects.answer(
        StoryTarget.SPA_BUNDLE,
        200,
        "text/javascript; charset=utf-8",
        "console.log('the build this document names');",
        Map.of("Cache-Control", EdgeCacheControl.STATIC_DEFAULT));
    projects.answer(
        StoryTarget.SPA_LOGO,
        200,
        "image/svg+xml",
        "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>",
        Map.of("Cache-Control", EdgeCacheControl.STATIC_DEFAULT));
    projects.answer(
        StoryTarget.PRIVATE_READ,
        200,
        "application/json",
        "{\"answered\":\"a read the service marked private itself\"}",
        Map.of("Cache-Control", "no-store"));
    // An interactive terminal: the upgrade is accepted and one frame is pushed. What the story
    // asserts is not this text but the HEADERS the handshake arrived with, which is the one plane
    // that never reaches the edge's interceptor chain.
    projects.socket(StoryTarget.TERMINAL_PATH, "qits-projects: the terminal is attached");
    // An answer written over time. The gap is what a client can measure; a proxy that buffered
    // would
    // deliver both halves at the end and no body assertion could tell.
    projects.chunked(
        StoryTarget.STREAM_PATH,
        "text/event-stream",
        List.of("event: opened\ndata: the channel is open\n\n", "event: closed\ndata: done\n\n"),
        STREAM_GAP_MILLIS);

    // The second ordinary service. It answers the SAME path as the first, on purpose: only the name
    // differs, so only the name can have decided.
    StoryUpstream.named(StoryTarget.DOCS)
        .json(StoryTarget.ROUTED_PATH, "{\"service\":\"qits-docs\"}");

    // The machine vhost. Its READ is armed and its WRITE is not: opening reads is not opening a
    // service, and the write must be refused before it ever gets here.
    StoryUpstream.named(StoryTarget.MIRROR)
        .json(StoryTarget.MIRROR_READ, "{\"answered\":\"the mirror served an anonymous read\"}")
        .json(StoryTarget.MIRROR_LAYER, "{\"answered\":\"the mirror served a layer\"}");

    idp.stub(
        "POST",
        StoryTarget.INTROSPECT,
        Map.of(
            "userId", StoryTarget.SESSION_USER_ID,
            "username", StoryTarget.SESSION_USER,
            "roles", StoryTarget.SESSION_ROLES,
            // Twelve hours out: a session's own expiry is honoured whatever any cache believes,
            // so one that had already run out would make every admission below a refusal.
            "expiresAt", Instant.now().plusSeconds(43_200).toString()));

    System.setProperty(ARMED_PROPERTY, "true");
  }

  /**
   * How long {@link StoryTarget#STREAM_PATH} waits between its two halves.
   *
   * <p>Long enough that a buffering proxy is unmistakable from a client's timing, short enough that
   * a story never reads as a hang. The assertion it feeds is one-sided — the first half arrives
   * well inside the gap — so a slow machine makes the story slower and never makes it wrong.
   */
  static final long STREAM_GAP_MILLIS = 700;

  /**
   * The one project every name in this catalogue lives inside, written before the process starts.
   *
   * <p><b>It has to be a ROW rather than a key.</b> The grammar is read right to left and the
   * project label is mandatory, so an edge whose projection knows no projects serves nothing at all
   * — and that projection is loaded once, at {@code StartupEvent}, from PostgreSQL. A launched
   * process has no qits-events to learn a project from (the bus is dark here on purpose, see the
   * class javadoc), and a row written after it booted would be a row it never reads. So the
   * migrations are run from this JVM first and the row is inserted behind them: the launched
   * process then finds every migration applied, does nothing, and loads a projection that already
   * holds {@code acme}.
   *
   * <p>Exactly the columns {@code EdgeProjects.apply} writes, because this stands in for the frame
   * it would have written: present, with environments, at the epoch so any real frame would win.
   */
  private static synchronized void seedProject(String url) {
    if (System.getProperty(PROJECT_SEEDED_PROPERTY) != null) {
      return;
    }
    org.flywaydb.core.Flyway.configure()
        .dataSource(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD)
        .locations("classpath:db/edge/migration")
        .load()
        .migrate();
    try (java.sql.Connection connection =
            java.sql.DriverManager.getConnection(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD);
        java.sql.PreparedStatement insert =
            connection.prepareStatement(
                """
                insert into edge_project
                  (slug, project_id, present, supports_environments, event_id, occurred_at)
                values (?, ?, true, true, ?, ?)
                on conflict (slug) do nothing
                """)) {
      insert.setString(1, StoryTarget.PROJECT);
      insert.setString(2, "p-userflows");
      insert.setString(3, "seeded-before-the-launch");
      insert.setTimestamp(4, java.sql.Timestamp.from(Instant.EPOCH));
      insert.executeUpdate();
    } catch (java.sql.SQLException failure) {
      throw new IllegalStateException("could not seed the story catalogue's project", failure);
    }
    System.setProperty(PROJECT_SEEDED_PROPERTY, "true");
  }

  /** One host a deployment published: the application, its public label, and its one route. */
  private record Published(String application, String host, String address) {}

  /**
   * The hosts the stories reach, as the projection would hold them had a DeploymentActive arrived:
   * one snapshot per application with its {@code browser_host}, and one endpoint — the primary
   * route, {@code /<host>} — at the stand-in's address. A path no route names falls to the host's
   * own service, so every path a story sends on that name reaches that stand-in.
   *
   * <p>Behind {@link #seedProject}'s migrations, at the epoch, so any real frame would win.
   */
  private static synchronized void seedPublishedHosts(String url, List<Published> hosts) {
    if (System.getProperty(HOSTS_SEEDED_PROPERTY) != null) {
      return;
    }
    try (java.sql.Connection connection =
            java.sql.DriverManager.getConnection(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD);
        java.sql.PreparedStatement snapshot =
            connection.prepareStatement(
                """
                insert into edge_deployment_snapshot
                  (environment_name, application_name, event_id, occurred_at, browser_host)
                values (?, ?, ?, ?, ?)
                on conflict (environment_name, application_name) do nothing
                """);
        java.sql.PreparedStatement endpoint =
            connection.prepareStatement(
                """
                insert into edge_endpoint
                  (environment_name, application_name, path, upstream_host, upstream_port, ordinal)
                values (?, ?, ?, ?, ?, 0)
                on conflict (environment_name, application_name, path) do nothing
                """)) {
      for (Published host : hosts) {
        Upstream upstream = Upstream.parse(host.address(), PlatformApps.PORT);
        snapshot.setString(1, StoryTarget.ENVIRONMENT);
        snapshot.setString(2, host.application());
        snapshot.setString(3, "seeded-before-the-launch-" + host.host());
        snapshot.setTimestamp(4, java.sql.Timestamp.from(Instant.EPOCH));
        snapshot.setString(5, host.host());
        snapshot.executeUpdate();
        endpoint.setString(1, StoryTarget.ENVIRONMENT);
        endpoint.setString(2, host.application());
        endpoint.setString(3, "/" + host.host());
        endpoint.setString(4, upstream.host());
        endpoint.setInt(5, upstream.port());
        endpoint.executeUpdate();
      }
    } catch (java.sql.SQLException failure) {
      throw new IllegalStateException(
          "could not seed the story catalogue's published hosts", failure);
    }
    System.setProperty(HOSTS_SEEDED_PROPERTY, "true");
  }

  private static final String HOSTS_SEEDED_PROPERTY = "qits.test.userflow-it.hosts-seeded";

  /** Parked in the property table for the same reason as the urls: two classloaders, one JVM. */
  private static final String PROJECT_SEEDED_PROPERTY = "qits.test.userflow-it.project-seeded";

  private static synchronized String databaseUrl(String property, String database) {
    String recorded = System.getProperty(property);
    if (recorded != null) {
      return recorded;
    }
    // localhost resolves for the launched process too — it is a child of this JVM on this host.
    String url = EmbeddedPg.url(database);
    System.setProperty(property, url);
    return url;
  }
}
