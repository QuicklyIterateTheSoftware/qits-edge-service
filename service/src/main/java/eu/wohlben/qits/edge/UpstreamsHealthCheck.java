package eu.wohlben.qits.edge;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness: this edge serves a route table — see {@link EdgeRoutes#ready()}: the private edge once
 * a table is loaded, the public edge with a table or, never having received one, with {@link
 * PlatformApps} alone.
 *
 * <p>It deliberately does not probe application endpoints: an edge whose downstream is down is
 * still correctly configured, and restarting it for that outage would make recovery worse.
 *
 * <p>{@code route-table} reports the served table's version, or {@code none}. Nothing routes on
 * this check (qits-2160) — swarm's healthcheck is {@code /q/health/live}, because a not-ready edge
 * must still receive traffic: the barrier lives in the router, not in swarm's routing mesh.
 */
@Readiness
@ApplicationScoped
public class UpstreamsHealthCheck implements HealthCheck {

  @Inject EdgeRouter router;

  @Inject EdgeRoutes routes;

  @Override
  public HealthCheckResponse call() {
    HealthCheckResponseBuilder response =
        HealthCheckResponse.named("edge upstreams")
            .status(routes.ready())
            .withData(
                "route-table", routes.version() == null ? "none" : routes.version().toString());
    response.withData("default", router.defaultEnvironment());
    return response.build();
  }
}
