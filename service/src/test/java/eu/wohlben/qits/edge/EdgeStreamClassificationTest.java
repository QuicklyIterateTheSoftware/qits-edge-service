package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.http.HttpMethod;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which exchanges the edge proxies through its STREAM pool rather than its ordinary one — the rule
 * alone, without a request. That an exchange so classified really is counted in the stream pool is
 * {@code EdgeRoutingTest}'s; a WebSocket upgrade, the other half of the rule, is read off a live
 * request and needs no table here.
 */
class EdgeStreamClassificationTest {

  @Test
  void anEventSourceGetIsAStream() {
    // What a browser's EventSource and an MCP client's server-to-client channel both send.
    assertTrue(EdgeRouter.streams(HttpMethod.GET, List.of("text/event-stream")));
    assertTrue(EdgeRouter.streams(HttpMethod.HEAD, List.of("text/event-stream")));
  }

  @Test
  void theMediaTypeIsFoundAmongOthersInAnySpellingAndInAnyRepeatOfTheHeader() {
    assertTrue(EdgeRouter.streams(HttpMethod.GET, List.of("Text/Event-Stream")));
    assertTrue(
        EdgeRouter.streams(HttpMethod.GET, List.of("application/json, text/event-stream;q=0.9")));
    assertTrue(
        EdgeRouter.streams(HttpMethod.GET, List.of("application/json", "text/event-stream")));
  }

  @Test
  void anMcpPostIsOrdinaryAlthoughItAcceptsAnEventStream() {
    // The case the method rule exists for: every MCP JSON-RPC POST carries exactly this Accept,
    // and an agent sends many a minute. Each is a short exchange, so each belongs to the ordinary
    // pool — read by Accept alone, all of an agent's traffic would land in the stream pool.
    assertFalse(
        EdgeRouter.streams(HttpMethod.POST, List.of("application/json, text/event-stream")));
    assertFalse(EdgeRouter.streams(HttpMethod.PUT, List.of("text/event-stream")));
    assertFalse(EdgeRouter.streams(HttpMethod.DELETE, List.of("text/event-stream")));
  }

  @Test
  void aGetThatDoesNotAskForAnEventStreamIsOrdinary() {
    assertFalse(EdgeRouter.streams(HttpMethod.GET, List.of()));
    assertFalse(EdgeRouter.streams(HttpMethod.GET, List.of("text/html, */*")));
    assertFalse(EdgeRouter.streams(HttpMethod.GET, List.of("application/json")));
  }
}
