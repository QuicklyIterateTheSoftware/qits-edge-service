package eu.wohlben.qits.edge;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The edge's whole configuration surface: which environments exist and which one is the fallback.
 * The platform's own app vhosts are not configuration; they are {@link PlatformApps}.
 *
 * <p>There is no route table and no path knowledge here, deliberately. The edge demultiplexes by
 * <b>host name</b> only — an environment name, and since the ingress campaign an optional
 * application name in front of it. Deployment events own environment-vhost paths; {@link
 * PlatformApps} maps a whole platform NAME to a whole service, never a path to one.
 *
 * <p>Every upstream is derived from configuration and code ONLY. No part of a request selects a
 * host or a port: the Host name picks an environment out of a fixed list, and a name that is not in
 * the list picks the default. That is the SSRF guard, and it is why {@link #environments()} is a
 * list rather than a pattern the request could satisfy.
 *
 * <p>Since config sources include environment variables, a deployment declares all of it without a
 * file:
 *
 * <pre>
 * QITS_DOMAIN=wohlben.eu
 * QITS_EDGE_ENVIRONMENTS=prod,dev
 * QITS_EDGE_DEFAULT_ENVIRONMENT=prod
 * </pre>
 */
@ConfigMapping(prefix = "qits.edge")
public interface EdgeConfig {

  /**
   * <b>The one domain this platform states about itself</b>, and the primitive every composed name
   * here is built from: {@code wohlben.eu}, {@code localhost}.
   *
   * <p>It is a DEPLOYMENT fact of the estate rather than of this service, so it is injected under
   * the platform's own spelling — {@code QITS_DOMAIN}, beside {@code QITS_ENVIRONMENT}, written by
   * qits-deployments into every container — and {@code application.properties} maps that name onto
   * this key. One fact, stated once: the certificate's domain, the grammar every Host is read
   * against, the browser return authorities and the canonical origin are all THIS value, and none
   * of them is configured beside it. {@code QITS_EDGE_ACME_DOMAIN} and {@code
   * QITS_EDGE_SESSIONS_CANONICAL_ORIGIN} were the same fact under two more names and are gone.
   *
   * <p>It cannot be derived from a host name — {@code example.co.uk} is two labels of domain and
   * {@code localhost} is one — which is why it is stated at all.
   *
   * <p>The default is the local one, so a clone with no environment at all serves {@code
   * *.localhost:8080} exactly as it did.
   */
  @WithDefault("localhost")
  String domain();

  /**
   * The environments this edge can reach, by name. A Host name resolves to one of these or to
   * {@link #defaultEnvironment()}; nothing else is routable.
   *
   * <p>A name becomes a DNS label in an upstream host, so it is checked at startup against the
   * label charset — a name that could not be resolved is a configuration error worth failing on
   * rather than a 502 per request.
   */
  @WithDefault("prod")
  List<String> environments();

  /**
   * Where the apex domain and every unmatched Host name go. It must be one of {@link
   * #environments()}; a default naming an environment the edge cannot reach fails startup, because
   * the alternative is an edge that answers most of its traffic with a connection error.
   */
  @WithDefault("prod")
  String defaultEnvironment();

  /**
   * Address overrides for the platform's own app vhosts, keyed by the {@code $app} label. Which
   * labels exist, and where each one goes, is NOT here: it is {@link PlatformApps}, in code,
   * because every platform application answers at {@code <env>-<application>} and that is not
   * something a deployment decides.
   *
   * <p>What is left is {@link App#hosts()}: a fixed {@code host:port} for one label in one
   * environment, for a developer's local process and for this repository's own suites, whose
   * stand-ins listen on {@code 127.0.0.1}. A live deployment sets none. A key naming a label that
   * is not a platform app fails startup — an override cannot add a vhost.
   */
  Map<String, App> apps();

  /** The startup proof that turns a persisted deployment snapshot into an authoritative one. */
  Projection projection();

  interface Projection {

    Catchup catchup();

    interface Catchup {

      /** False only in deliberately offline test/dev setups. */
      @WithDefault("true")
      boolean required();

      /** Delay between unsuccessful reads of qits-events while readiness remains down. */
      @WithDefault("PT1S")
      Duration retry();
    }
  }

  /** One platform app label's overrides. */
  interface App {

    /**
     * Per-environment address overrides, {@code qits.edge.apps.<app>.hosts.<env> = host} or {@code
     * host:port}. It exists for a developer's local process and for this repository's test suites;
     * a stale override sends a whole tier's traffic to the wrong process, which is why a live
     * deployment carries none.
     */
    Map<String, String> hosts();
  }
}
