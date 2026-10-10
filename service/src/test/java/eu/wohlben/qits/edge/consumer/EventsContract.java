package eu.wohlben.qits.edge.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import au.com.dius.pact.consumer.dsl.Matchers;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.edge.consumer.GoldenMasters.Provider;
import eu.wohlben.qits.edge.consumer.GoldenMasters.Request;
import eu.wohlben.qits.edge.consumer.GoldenMasters.Trigger;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.eventstream.control.EventsQueryProbe;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * <b>What the edge asks qits-events, and why</b> (ticket qits-1149). The edge makes this call
 * through qits-eventstream's catch-up ({@code CatchupSweeper} → {@code EventsQuery}), never its own
 * code: two durable consumers rebuild their projections from the log.
 *
 * <ul>
 *   <li>{@code edge-active-endpoints} ({@code DeploymentActiveSubscriber}) — the routing
 *       projection, read with {@code name=DeploymentActive};
 *   <li>{@code edge-project-sans} ({@code ProjectLifecycleSubscriber}) — the project set, read with
 *       {@code name=} the three project lifecycle events, in {@code Set.of} order, so any order.
 * </ul>
 *
 * <p>Each is pressed at startup (the bootstrap barrier) and every sweep. Both replay from the
 * epoch, so the call is always {@code order=asc&limit=200}, never the head read ({@code limit=1}).
 * A follow-up page adds {@code cursor=}; it is the same operation and the same answer shape.
 *
 * <p><b>The edge reads</b> {@code events[*].id}, {@code name}, {@code occurredAt} and {@code
 * payload}, and {@code nextCursor} (absent means the last page). The recordings hold no {@code
 * nextCursor}, so it is not bound. The payload is a JSON string: its own fields are the event's
 * contract, not this call's.
 */
final class EventsContract {

  static final Provider PROVIDER = new Provider("qits-events-service", "qits-events");

  static final String LIST_EVENTS = "listEvents";
  static final String PATH = "/events/api/events";

  static final String A_FEW_RECENT_EVENTS = "a few recent events";

  /** Not recorded by qits-events yet: see {@link Case#pending}. */
  static final String PROJECT_LIFECYCLE_EVENTS = "project lifecycle events";

  static final String DEPLOYMENT_ACTIVE = "DeploymentActive";
  static final List<String> PROJECT_EVENTS =
      List.of("ProjectCreated", "ProjectChanged", "ProjectDeleted");

  static final Set<String> CONSUMES =
      Set.of("$.events[*].id", "$.events[*].name", "$.events[*].occurredAt", "$.events[*].payload");

  /**
   * One (trigger, call). {@code pending} is the skip reason while the provider state is missing.
   */
  record Case(Trigger trigger, String state, List<String> names, Object nameQuery, String pending) {

    String description() {
      return GoldenMasters.description(LIST_EVENTS, trigger);
    }

    Request request() {
      Map<String, Object> query = new LinkedHashMap<>();
      query.put("order", "asc");
      query.put("limit", "200");
      query.put("name", nameQuery);
      return new Request("GET", PATH, query, Map.of(), null);
    }

    /** The recording, narrowed to the events this request's {@code name} filter selects. */
    UnaryOperator<JsonNode> view() {
      return recorded -> {
        ObjectNode copy = ((ObjectNode) recorded).deepCopy();
        ArrayNode kept = copy.arrayNode();
        copy.path("events")
            .forEach(
                e -> {
                  if (names.contains(e.path("name").asText())) {
                    kept.add(e);
                  }
                });
        copy.set("events", kept);
        return copy;
      };
    }

    /** Run the real client against {@code baseUrl}; assert it read what the recording holds. */
    void run(String baseUrl) {
      EventsQueryProbe.Page page = EventsQueryProbe.after(baseUrl, Set.copyOf(names), null);
      JsonNode expected = view().apply(GoldenMasters.json(PROVIDER, state, LIST_EVENTS));
      assertEquals(expected.path("events").size(), page.events().size());
      for (int i = 0; i < page.events().size(); i++) {
        EventFrame frame = page.events().get(i);
        JsonNode recorded = expected.path("events").get(i);
        assertEquals(recorded.path("id").asText(), frame.id());
        assertEquals(recorded.path("name").asText(), frame.name());
        assertEquals(recorded.path("payload").asText(), frame.payload());
        assertEquals(
            java.time.Instant.parse(recorded.path("occurredAt").asText()), frame.occurredAt());
      }
      assertNull(page.nextCursor(), "the recording is one whole page");
    }
  }

  private static final String PROJECT_NAMES_REGEX =
      "^(ProjectCreated|ProjectChanged|ProjectDeleted)(,(ProjectCreated|ProjectChanged|ProjectDeleted)){2}$";

  private static final String PENDING_PROJECTS =
      "needs provider state '"
          + PROJECT_LIFECYCLE_EVENTS
          + "' for listEvents in qits-events-service";

  static final List<Case> CASES =
      List.of(
          new Case(
              Trigger.schedule("DeploymentProjectionBootstrap.catchUpUntilReady"),
              A_FEW_RECENT_EVENTS,
              List.of(DEPLOYMENT_ACTIVE),
              DEPLOYMENT_ACTIVE,
              null),
          new Case(
              Trigger.schedule("CatchupSweeper.tick(edge-active-endpoints)"),
              A_FEW_RECENT_EVENTS,
              List.of(DEPLOYMENT_ACTIVE),
              DEPLOYMENT_ACTIVE,
              null),
          new Case(
              Trigger.schedule("ProjectSansBootstrap.catchUpAndRequestReconcile"),
              PROJECT_LIFECYCLE_EVENTS,
              PROJECT_EVENTS,
              Matchers.regexp(PROJECT_NAMES_REGEX, String.join(",", PROJECT_EVENTS)),
              PENDING_PROJECTS),
          new Case(
              Trigger.schedule("CatchupSweeper.tick(edge-project-sans)"),
              PROJECT_LIFECYCLE_EVENTS,
              PROJECT_EVENTS,
              Matchers.regexp(PROJECT_NAMES_REGEX, String.join(",", PROJECT_EVENTS)),
              PENDING_PROJECTS));

  /** The rows whose provider state exists: the ones the committed pact holds. */
  static List<Case> ready() {
    return CASES.stream().filter(c -> c.pending() == null).toList();
  }

  private EventsContract() {}

  static V4Pact pact() {
    return pact(ready());
  }

  static V4Pact pact(List<Case> cases) {
    PactBuilder builder =
        new PactBuilder(GoldenMasters.CONSUMER, PROVIDER.repository(), PactSpecVersion.V4);
    for (Case c : cases) {
      GoldenMasters.interaction(
          builder, PROVIDER, c.state(), LIST_EVENTS, c.trigger(), c.request(), c.view(), CONSUMES);
    }
    return builder.toPact();
  }
}
