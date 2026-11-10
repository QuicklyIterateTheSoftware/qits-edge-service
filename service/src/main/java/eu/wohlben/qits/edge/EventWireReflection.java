package eu.wohlben.qits.edge;

import eu.wohlben.qits.edge.wireguard.PeerEventsSubscriber;
import eu.wohlben.qits.edge.wireguard.PrivateEdgeAnnounced;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native registrations for both live event frames and catch-up pages, plus the consumers' DTOs and
 * the one event this edge publishes.
 *
 * <p><b>Publishing needs two more entries than consuming, and the edge learned that live</b>
 * (qits-1977). Until {@link PrivateEdgeAnnounced} the edge only ever read events, so this list held
 * the frame, the page and the payloads it binds. A publish also serializes {@link EventEnvelope} —
 * the PUT body — and reads the {@code @JsonIgnore}s of {@code CanonicalJson$QitsEventMixin} with
 * {@code getDeclaredMethods()}, both through qits-eventstream's own {@code ObjectMapper}, which
 * Quarkus' reflection scan cannot see. Without the envelope the native binary's writer throws
 * inside {@code EventsPublisher.send}, which reports it as an unreachable bus at DEBUG; the row
 * goes to the outbox and the sweeper retries it, failing the same way, at DEBUG, forever.
 * qits-edge-private 2026.1110.173435 went ready and never announced itself, with nothing in its log
 * to say why. Without the mix-in the payload carries {@code eventId} and {@code occurredAt}, which
 * travel on the envelope only. qits-eventstream's AGENTS.md names all three entries; {@code
 * EventWireReflectionTest} keeps them here. On a JVM every class reflects registered or not, so
 * only the binary proves it.
 */
@RegisterForReflection(
    targets = {
      EventFrame.class,
      EventEnvelope.class,
      DeploymentActiveSubscriber.DeploymentActivePayload.class,
      DeploymentActiveSubscriber.EndpointPayload.class,
      DeploymentActiveSubscriber.NavigationPayload.class,
      ProjectLifecycleSubscriber.ProjectLifecyclePayload.class,
      PeerEventsSubscriber.PeerRegisteredPayload.class,
      PeerEventsSubscriber.PeerRevokedPayload.class,
      PrivateEdgeAnnounced.class
    },
    classNames = {"eu.wohlben.qits.eventstream.control.EventPage", EventWireReflection.MIXIN})
final class EventWireReflection {

  /** Private in qits-eventstream, so named by string; the test resolves it. */
  static final String MIXIN = "eu.wohlben.qits.eventstream.control.CanonicalJson$QitsEventMixin";

  private EventWireReflection() {}
}
