package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.MultiMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class EdgeWebSocketBearerTest {

  private static final String JWT = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.c2ln-_";

  private static MultiMap offering(String... lines) {
    MultiMap headers = MultiMap.caseInsensitiveMultiMap();
    for (String line : lines) {
      headers.add("sec-websocket-protocol", line);
    }
    return headers;
  }

  @Test
  void theOfferIsReadAcrossLinesAndCommasInOrder() {
    assertEquals(
        List.of("a", "qits.bearer", JWT, "b"),
        EdgeWebSocketBearer.offered(offering("a, qits.bearer", " " + JWT + " ,b,")));
    assertEquals(List.of(), EdgeWebSocketBearer.offered(MultiMap.caseInsensitiveMultiMap()));
  }

  @Test
  void theTokenAfterTheMarkerMovesIntoAuthorizationAndTheRestStays() {
    MultiMap headers = offering("tty, qits.bearer, " + JWT + ", chat");
    headers.set("Authorization", "Basic c29tZTpvbmU=");

    assertTrue(EdgeWebSocketBearer.moveToAuthorization(headers));

    assertEquals("Bearer " + JWT, headers.get("Authorization"));
    assertEquals("tty, qits.bearer, chat", headers.get("Sec-WebSocket-Protocol"));
    assertTrue(EdgeWebSocketBearer.offersBearer(headers), "the marker stays for the answer");
  }

  @Test
  void aMarkerWithNothingAfterItMovesNothing() {
    for (MultiMap headers :
        List.of(
            offering("qits.bearer"),
            offering("tty, qits.bearer"),
            offering("qits.bearer, qits.bearer"))) {
      String before = headers.get("Sec-WebSocket-Protocol");
      assertFalse(EdgeWebSocketBearer.moveToAuthorization(headers), before);
      assertNull(headers.get("Authorization"));
      assertEquals(before, headers.get("Sec-WebSocket-Protocol"));
    }
  }

  @Test
  void theForwardedOfferDropsTheMarkerAndTheHeaderWhenNothingIsLeft() {
    MultiMap alone = offering("qits.bearer");
    EdgeWebSocketBearer.forwardedOffer(alone);
    assertFalse(alone.contains("Sec-WebSocket-Protocol"));

    MultiMap shared = offering("tty, qits.bearer", "chat");
    EdgeWebSocketBearer.forwardedOffer(shared);
    assertEquals(List.of("tty, chat"), shared.getAll("Sec-WebSocket-Protocol"));

    MultiMap none = MultiMap.caseInsensitiveMultiMap();
    EdgeWebSocketBearer.forwardedOffer(none);
    assertTrue(none.isEmpty());
  }

  @Test
  void theAnswerIsAlwaysSomethingTheBrowserOffered() {
    List<String> offer = List.of("qits.bearer", "tty");
    assertEquals("qits.bearer", EdgeWebSocketBearer.answer(offer, null));
    assertEquals("qits.bearer", EdgeWebSocketBearer.answer(offer, ""));
    assertEquals("tty", EdgeWebSocketBearer.answer(offer, "tty"));
    assertEquals("qits.bearer", EdgeWebSocketBearer.answer(offer, "never-offered"));
  }

  @Test
  void aHandshakeWithoutTheMarkerKeepsTheUpstreamsAnswer() {
    assertNull(EdgeWebSocketBearer.answer(List.of(), null));
    assertEquals("tty", EdgeWebSocketBearer.answer(List.of("tty"), "tty"));
  }
}
