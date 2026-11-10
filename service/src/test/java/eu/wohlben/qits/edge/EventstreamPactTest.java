package eu.wohlben.qits.edge;

import eu.wohlben.qits.edge.wireguard.PeerEventsSubscriber;
import eu.wohlben.qits.eventstream.contracts.EventstreamPact;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>What qits-eventstream asks qits-events on the edge's behalf</b> (qits-1163): the catch-up
 * reads of its three durable listeners — the sweeper's, and the explicit {@code catchUp} calls of
 * {@link DeploymentProjectionBootstrap}, {@link ProjectSansBootstrap} and {@code LivePeers}, which
 * send the same page query — pacted by this service against the library version it pins and
 * committed as {@code pacts/qits-edge-service_qits-eventstream-javalib_qits-events-service.json}.
 * Every listener replays from the epoch, so there is no initialize row. A private edge publishes
 * one event, {@code PrivateEdgeAnnounced} (epic qits-1977), so the publish rows are in. Here rather
 * than in {@code consumer/} because {@link ProjectLifecycleSubscriber}'s constructors are
 * package-private. The guard fails naming any listener or publisher in {@code target/classes} left
 * out here.
 */
class EventstreamPactTest {

  static final EventstreamPact PACT =
      EventstreamPact.forConsumer("qits-edge-service")
          .listeners(
              new DeploymentActiveSubscriber(),
              new ProjectLifecycleSubscriber(),
              new PeerEventsSubscriber())
          .publishes(true)
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
