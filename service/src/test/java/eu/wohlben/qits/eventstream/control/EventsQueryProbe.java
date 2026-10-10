package eu.wohlben.qits.eventstream.control;

import java.util.Collection;
import java.util.List;

/**
 * The edge's consumer pact against qits-events (ticket qits-1149) drives qits-eventstream's own
 * {@link EventsQuery} — the client that makes the call — rather than a copy of it. Its read method
 * and page type are package-private, so this probe sits in the library's package, in the edge's
 * test tree only.
 */
public final class EventsQueryProbe {

  /** One page, as the catch-up reads it. */
  public record Page(List<EventFrame> events, String nextCursor) {}

  private EventsQueryProbe() {}

  /** The catch-up's page read: {@code CatchupSweeper}'s page size, ascending, after a cursor. */
  public static Page after(String eventsUrl, Collection<String> names, String cursor) {
    EventsQuery query = new EventsQuery();
    query.eventsUrl = eventsUrl;
    EventPage page = query.after(names, cursor, CatchupSweeper.PAGE_SIZE);
    return new Page(page.events(), page.nextCursor());
  }
}
