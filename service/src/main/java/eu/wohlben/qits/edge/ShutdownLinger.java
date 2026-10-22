package eu.wohlben.qits.edge;

import io.quarkus.runtime.ShutdownEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import org.jboss.logging.Logger;

/**
 * <b>The drain is over; stay alive a moment longer</b>, so the kernel can send what the last
 * responses handed it (qits-1170).
 *
 * <p>Quarkus counts a response complete once its last bytes are written to the socket — into the
 * kernel's send buffer, not onto the client. Exit right then and swarm stops the container and
 * tears down its network namespace, and whatever was still queued is gone. The live verification
 * saw it one hop in, on qits-mirror: a 201 MB jar downloaded through this edge lost its LAST
 * 639,236 bytes, and the client hung until its TCP keepalive gave up. The edge's own client-facing
 * connections are lost the same way when it exits. A closed socket keeps transmitting its queue and
 * its FIN for as long as the namespace exists, so all it takes is to not exit yet.
 *
 * <p><b>Why {@code ShutdownEvent}</b> — the event {@link EdgeRouter#draining} must NOT use, for the
 * mirror-image reason. {@code Application.stop} runs {@code ShutdownRecorder.runShutdown()} first:
 * every listener's {@code preShutdown}, then every {@code shutdown}, where {@code
 * GracefulShutdownFilter} waits for in-flight requests and the recorder waits up to {@code
 * quarkus.shutdown.timeout} for it. Only then comes {@code doStop()}, whose {@code
 * StartupContext.close()} runs the shutdown task ArC registered to fire this event. So this
 * observer runs after "All HTTP requests complete" (or after the timeout gave up), never during the
 * drain. The latest priority so every other {@code ShutdownEvent} observer has done its cleanup
 * first.
 */
@ApplicationScoped
public class ShutdownLinger {

  private static final Logger LOG = Logger.getLogger(ShutdownLinger.class);

  @Inject EdgeConfig config;

  void lingering(@Observes @Priority(Integer.MAX_VALUE) ShutdownEvent event) {
    linger(config.shutdown().linger());
  }

  /** Sleeps {@code linger} on the calling thread; zero or negative returns at once. */
  static void linger(Duration linger) {
    if (linger.isZero() || linger.isNegative()) {
      LOG.debug("shutting down: no linger configured");
      return;
    }
    LOG.infof(
        "shutting down: lingering %dms so the kernel can flush what the last responses sent",
        linger.toMillis());
    try {
      Thread.sleep(linger.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
