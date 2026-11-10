package eu.wohlben.qits.edge;

import eu.wohlben.qits.edge.wireguard.PeerEventsSubscriber;
import eu.wohlben.qits.edge.wireguard.PrivateEdgeAnnounced;
import eu.wohlben.qits.eventstream.control.EventFrame;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native registrations for both live event frames and catch-up pages, plus the consumers' DTOs and
 * the one event this edge publishes.
 */
@RegisterForReflection(
    targets = {
      EventFrame.class,
      DeploymentActiveSubscriber.DeploymentActivePayload.class,
      DeploymentActiveSubscriber.EndpointPayload.class,
      DeploymentActiveSubscriber.NavigationPayload.class,
      ProjectLifecycleSubscriber.ProjectLifecyclePayload.class,
      PeerEventsSubscriber.PeerRegisteredPayload.class,
      PeerEventsSubscriber.PeerRevokedPayload.class,
      PrivateEdgeAnnounced.class
    },
    classNames = {"eu.wohlben.qits.eventstream.control.EventPage"})
final class EventWireReflection {

  private EventWireReflection() {}
}
