package eu.wohlben.qits.edge;

import eu.wohlben.qits.eventstream.control.CatchupResult;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.jboss.logging.Logger;

/**
 * Makes the deployment projection authoritative only after its durable consumer has read the log to
 * a confirmed head.
 *
 * <p>PostgreSQL is a cache of this projection, not proof that it is current: a newer {@code
 * DeploymentActive} can have been published while this edge was down. At startup this coordinator
 * therefore holds readiness down and keeps direct path routing disabled until the eventstream
 * reader reports that {@link DeploymentActiveSubscriber#CONSUMER_ID} reached a page whose explicit
 * next cursor was absent. It retries indefinitely: qits-events being briefly down must not turn a
 * recoverable boot race into a permanently stale edge.
 *
 * <p>Before that replay it re-derives the exposure class (qits-2120): a projection built under
 * another class than this edge's — or under none recorded — is truncated first, and the change is
 * logged as {@code edge projection rebuilt: class <old> -> <new>}.
 */
@ApplicationScoped
public class DeploymentProjectionBootstrap {

  private static final Logger LOG = Logger.getLogger(DeploymentProjectionBootstrap.class);

  enum State {
    CATCHING_UP,
    READY
  }

  @jakarta.inject.Inject DeploymentProjectionCatchup catchup;
  @jakarta.inject.Inject EdgeConfig config;
  @jakarta.inject.Inject EdgeRoutes routes;

  /** The class to rebuild under when a suite states one; null reads {@code config.exposure()}. */
  private ExposureClass exposure;

  /** Test-only escape hatch for suites that deliberately turn qits-eventstream off. */
  boolean required;

  Duration retry;

  private final AtomicReference<State> state = new AtomicReference<>(State.CATCHING_UP);

  DeploymentProjectionBootstrap() {}

  DeploymentProjectionBootstrap(
      DeploymentProjectionCatchup catchup,
      EdgeRoutes routes,
      ExposureClass exposure,
      boolean required,
      Duration retry) {
    this.catchup = catchup;
    this.routes = routes;
    this.exposure = exposure;
    this.required = required;
    this.retry = retry;
  }

  void start(@Observes StartupEvent ignored) {
    required = config.projection().catchup().required();
    retry = config.projection().catchup().retry();
    if (!required) {
      state.set(State.READY);
      LOG.warn(
          "deployment projection catch-up is disabled; routes are trusted by explicit configuration");
      return;
    }
    Thread.ofVirtual().name("edge-deployment-projection-catchup").start(this::catchUpUntilReady);
  }

  /** Whether direct routes and edge-owned navigation are backed by a complete projection. */
  public boolean authoritative() {
    return state.get() == State.READY;
  }

  /**
   * Lower or raise the barrier by hand — for a suite that has to see what a catching-up edge
   * answers, the way {@code ProjectSansBootstrap#authoritative(boolean)} is for its own.
   */
  void authoritative(boolean value) {
    state.set(value ? State.READY : State.CATCHING_UP);
  }

  /**
   * A stable readiness data value, useful without exposing eventstream's implementation details.
   */
  public String state() {
    return state.get().name().toLowerCase().replace('_', '-');
  }

  void catchUpUntilReady() {
    // The edge projection may have been wiped while qits-eventstream's consumer watermark and
    // claims survived. An ordinary catch-up would then start after that watermark and faithfully
    // rebuild nothing. Resetting this replay-from-epoch consumer at every edge boot makes the log,
    // rather than either local database, the reconstruction source. EdgeRoutes' LWW replacement
    // makes replay over a surviving snapshot safe too, so there is no projection truncate before
    // this call — unless the exposure class changed (qits-2120): which frames were withdrawn
    // depends on the class, and LWW never re-applies a frame the snapshot already records, so a
    // withdrawal from the old class would survive the replay.
    while (true) {
      try {
        reconcileClass();
        break;
      } catch (RuntimeException failure) {
        LOG.warnf(
            "deployment routing projection class could not be checked (%s); retrying in %s",
            failure.getMessage(), retry);
        if (!waitForRetry()) {
          return;
        }
      }
    }
    CatchupResult result = catchup.rebuildFromEpoch(DeploymentActiveSubscriber.CONSUMER_ID);
    while (!authoritative()) {
      if (result.reachedLogHead()) {
        state.set(State.READY);
        LOG.infof(
            "deployment routing projection caught up to qits-events (%d frame(s) handled)",
            result.handled());
        return;
      }
      LOG.warnf(
          "deployment routing projection is not ready (%s); retrying in %s",
          result.status(), retry);
      if (!waitForRetry()) {
        return;
      }
      result = catchup.catchUp(DeploymentActiveSubscriber.CONSUMER_ID);
    }
  }

  /**
   * Truncates the projection when it was built under another class than this edge's, or under none
   * recorded, and records this one — so the replay that follows rebuilds every snapshot under the
   * current class. An unchanged class keeps the projection as it is.
   */
  private void reconcileClass() {
    ExposureClass exposure = exposure();
    String current = exposure.name().toLowerCase(Locale.ROOT);
    String stored = routes.projectionClass();
    if (current.equals(stored)) {
      return;
    }
    routes.resetProjection(exposure);
    LOG.infof(
        "edge projection rebuilt: class %s -> %s", stored == null ? "<none>" : stored, current);
  }

  private ExposureClass exposure() {
    return exposure != null ? exposure : config.exposure();
  }

  private boolean waitForRetry() {
    try {
      Thread.sleep(retry);
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      LOG.warn("deployment routing projection catch-up was interrupted; leaving readiness down");
      return false;
    }
  }
}
