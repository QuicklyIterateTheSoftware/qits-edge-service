package eu.wohlben.qits.edge;

import eu.wohlben.qits.eventstream.contracts.EventstreamPact;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>What qits-eventstream asks qits-events on the edge's behalf</b> (qits-1163): the catch-up
 * reads of its two durable listeners — the sweeper's, and the explicit {@code catchUp} calls of
 * {@link DeploymentProjectionBootstrap} and {@link ProjectSansBootstrap}, which send the same page
 * query — pacted by this service against the library version it pins and committed as {@code
 * pacts/qits-edge-service_qits-eventstream-javalib_qits-events-service.json}. Both listeners replay
 * from the epoch, so there is no initialize row; the edge publishes nothing. Here rather than in
 * {@code consumer/} because {@link ProjectLifecycleSubscriber}'s constructors are package-private.
 * The guard fails naming any listener or publisher in {@code target/classes} left out here.
 */
class EventstreamPactTest {

  static final EventstreamPact PACT =
      EventstreamPact.forConsumer("qits-edge-service")
          .listeners(new DeploymentActiveSubscriber(), new ProjectLifecycleSubscriber())
          .publishes(false)
          .guard(Path.of("target/classes"));

  @TestFactory
  Stream<DynamicTest> rows() {
    return PACT.tests();
  }

  @Test
  void pactFile() {
    PACT.compareOrWritePactFile();
  }
}
