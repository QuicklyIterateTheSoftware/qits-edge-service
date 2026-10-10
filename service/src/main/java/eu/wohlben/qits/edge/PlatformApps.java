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
 * <p><b>The audience comes from the same identity.</b> A vhost requires its application's own
 * resource audience, {@code <env>-<application>}, or the platform audience {@link
 * #PLATFORM_AUDIENCE}. The token's roles are the permission. The env-prefixed audience keeps the
 * tiers apart: a token minted for dev's githost does not open prod's. {@link #PLATFORM_AUDIENCE}
 * opens every vhost in every tier. That is the open calling model, and it is the platform's choice.
 *
 * <p>A label that is not here reaches a service only through the deployment projection: the service
 * publishes the name it answers to. Projected services are what every other host is.
 */
final class PlatformApps {

  /**
   * The one audience that opens every gated vhost, whatever its tier. The same literal every
   * qits-idp client is commissioned for and every service accepts; no placeholder, on purpose.
   */
  static final String PLATFORM_AUDIENCE = "qits-platform";

  /** Every platform application listens here; an override carries its own port. */
  static final int PORT = 8080;

  /** App label → the application behind it. */
  private static final Map<String, String> APPLICATIONS =
      Map.of(
          "registry", "qits-artifacts",
          "mirror", "qits-mirror",
          "githost", "qits-githost");

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

  /** The application's wire alias in one environment: {@code dev-qits-artifacts}. */
  static String alias(String label, String environment) {
    return environment + "-" + application(label);
  }

  /** Where a platform label goes in one environment, by the platform's own addressing. */
  static Upstream upstream(String label, String environment) {
    return new Upstream(alias(label, environment), PORT);
  }

  /** The resource audience a platform label's vhost requires: its application's wire alias. */
  static String audience(String label, String environment) {
    return alias(label, environment);
  }
}
