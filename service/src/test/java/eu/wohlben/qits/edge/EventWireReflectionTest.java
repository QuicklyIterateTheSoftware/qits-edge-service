package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.edge.wireguard.PrivateEdgeAnnounced;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Guards {@link EventWireReflection}'s <em>completeness</em>, as qits-ci's test of the same name
 * does. On a JVM every class reflects whether registered or not, so nothing here proves the native
 * binary publishes; what is checkable is that the list still names what the publish path touches. A
 * plain JUnit test on purpose: a {@code @QuarkusTest} with its own configuration restarts Quarkus
 * (see {@code OtelLogConfigTest}).
 */
class EventWireReflectionTest {

  private static RegisterForReflection registration() {
    RegisterForReflection registration =
        EventWireReflection.class.getAnnotation(RegisterForReflection.class);
    assertNotNull(registration, "the annotation IS the class");
    return registration;
  }

  @Test
  void thePublishPathIsRegisteredTheEventTheEnvelopeAndTheMixin() {
    Set<Class<?>> targets = Set.of(registration().targets());
    // qits-edge-private 2026.1110.173435 shipped with the event and without the envelope or the
    // mix-in: every PUT of PrivateEdgeAnnounced failed to serialize, silently (qits-1977).
    assertTrue(targets.contains(PrivateEdgeAnnounced.class), "the event this edge publishes");
    assertTrue(targets.contains(EventEnvelope.class), "the PUT body");
    assertTrue(targets.contains(EventFrame.class), "what the stream delivers");
    assertTrue(Set.of(registration().classNames()).contains(EventWireReflection.MIXIN));
    assertTrue(
        Set.of(registration().classNames())
            .contains("eu.wohlben.qits.eventstream.control.EventPage"));
  }

  @Test
  void theMixinNamedByStringStillExistsAndStillHidesTheIdentity() throws Exception {
    Class<?> mixin = Class.forName(EventWireReflection.MIXIN);
    assertNotNull(mixin.getDeclaredMethod("eventId").getAnnotation(JsonIgnore.class));
    assertNotNull(mixin.getDeclaredMethod("occurredAt").getAnnotation(JsonIgnore.class));
    Class.forName("eu.wohlben.qits.eventstream.control.EventPage");
  }

  /** The PUT body the registration is for: the envelope, with identity outside the payload. */
  @Test
  void theAnnouncementSerializesAsTheEnvelopeQitsEventsTakes() {
    PrivateEdgeAnnounced event =
        new PrivateEdgeAnnounced(
            UUID.randomUUID(),
            "qits-edge-private",
            "95pJhHSoEe/esnOiCdHvLmFIMzADJiaoQSZ6WcMJvwM=",
            "46.224.171.33",
            47812,
            "100.96.0.1",
            "100.96.0.0/24",
            "100.96.0.1",
            1380,
            "wohlben.eu",
            Instant.parse("2026-11-10T17:52:27Z"));

    JsonNode body = CanonicalJson.parse(CanonicalJson.envelope(EventEnvelope.of(event)));

    assertEquals("PrivateEdgeAnnounced", body.get("name").asText());
    JsonNode payload = CanonicalJson.parse(body.get("payload").asText());
    assertEquals("46.224.171.33", payload.get("publicAddress").asText());
    assertEquals(47812, payload.get("listenPort").asInt());
    assertFalse(payload.has("eventId"), "identity travels on the envelope only");
    assertFalse(payload.has("occurredAt"), "so does the time");
  }
}
