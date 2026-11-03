package eu.wohlben.qits.edge;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The platform's own machine vhosts, and where each one goes. This is code, not configuration.
 *
 * <p>An {@code $app} label here — {@code registry.dev.<domain>} — names a platform application, and
 * every platform application answers at {@code <env>-<application>} on qits-net, port 8080. That
 * address is how the platform works, not something one installation decides. So neither the label
 * set nor the alias is configurable. The edge used to read both from {@code
 * qits.edge.apps.<app>.host-pattern} and {@code audience-pattern}, and the live store held six
 * entries spelling the same rule. One of them named a tier that no longer exists ({@code
 * {env}-qits-platform-mirror}). The owner's ruling (2026-10-03, qits-528): "it should be code. its
 * PLATFORM".
 *
 * <p><b>What a label maps to is the APPLICATION</b>, the name its {@code deployments.yml} deploys
 * as. The wire alias is derived from it the same way qits-deployments derives it: {@code registry}
 * fronts {@code qits-artifacts}, so {@code registry.dev} reaches {@code dev-qits-artifacts:8080}.
 *
 * <p><b>The audience is not per application.</b> Every gated vhost, in every environment, accepts
 * exactly {@link #PLATFORM_AUDIENCE}; the token's roles are the permission.
 *
 * <p>A label that is not here reaches a service only through the deployment projection: the service
 * publishes the name it answers to. Projected services are what every other host is.
 *
 * <p><b>These labels are served before the deployment catch-up ends</b> (qits-1403), and that is
 * the second reason they are code. Every other name waits behind the startup replay with a {@code
 * 503}; a name here does not, because the edge's own catch-up dials qits-events, and qits-events
 * fetches its signing keys from {@code idp.qits.<domain>} — through this process. Holding {@link
 * #IDP} behind the replay would be a boot that waits for itself. So the whole label set answers
 * from this class while the projection is still reading, exactly as it would with nothing
 * published.
 */
final class PlatformApps {

  /**
   * The one audience every gated vhost accepts, in every environment. The same literal idp mints
   * and every service accepts; no placeholder, on purpose.
   */
  static final String PLATFORM_AUDIENCE = "qits-platform";

  /** Every platform application listens here; an override carries its own port. */
  static final int PORT = 8080;

  /**
   * The label idp answers on: {@code idp.qits.<domain>}, where the login page, {@code /jwks} and
   * {@code /token} live, and the issuer every accepted token names.
   */
  static final String IDP = "idp";

  /** App label → the application behind it. */
  private static final Map<String, String> APPLICATIONS =
      Map.of(
          "registry",
          "qits-artifacts",
          "mirror",
          "qits-mirror",
          "githost",
          "qits-githost",
          IDP,
          "qits-idp");

  /**
   * The route a platform application owns, where the edge has to know it without the projection:
   * idp's {@code /idp} (its {@code deployments.yml} {@code routes:}). It is what lets the anonymous
   * prefixes ({@code qits.edge.sessions.anonymous-prefixes}, {@code /idp/}) be served on idp's own
   * name before idp's deployment has been replayed — the same "on the name that OWNS them" rule the
   * projection answers once it has. A label with no entry owns no anonymous path here; registry,
   * mirror and githost need none.
   */
  private static final Map<String, String> ROUTES = Map.of(IDP, "/idp");

  private PlatformApps() {}

  /** The routable platform app labels. */
  static Set<String> labels() {
    return APPLICATIONS.keySet();
  }

  /** Whether {@code label} is one of the platform's own app vhosts. */
  static boolean contains(String label) {
    return label != null && APPLICATIONS.containsKey(label.toLowerCase(Locale.ROOT));
  }

  /** The application a platform label fronts: {@code registry} → {@code qits-artifacts}. */
  static String application(String label) {
    String application = APPLICATIONS.get(label.toLowerCase(Locale.ROOT));
    if (application == null) {
      throw new IllegalArgumentException("`" + label + "` is not a platform app label");
    }
    return application;
  }

  /**
   * Whether {@code path} is under the route the platform application behind {@code label} owns —
   * {@link #ROUTES}, matched as a published endpoint matches: the route itself or anything below
   * it. False for a label with no stated route.
   */
  static boolean owns(String label, String path) {
    String route = label == null ? null : ROUTES.get(label.toLowerCase(Locale.ROOT));
    return route != null && path != null && (path.equals(route) || path.startsWith(route + "/"));
  }

  /** The application's wire alias in one environment: {@code dev-qits-artifacts}. */
  static String alias(String label, String environment) {
    return environment + "-" + application(label);
  }

  /** Where a platform label goes in one environment, by the platform's own addressing. */
  static Upstream upstream(String label, String environment) {
    return new Upstream(alias(label, environment), PORT);
  }
}
