package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.vertx.core.MultiMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class EdgeHopByHopTest {

  @Test
  void everyFixedHopByHopHeaderIsRemovedWhateverItsCase() {
    MultiMap headers = MultiMap.caseInsensitiveMultiMap();
    headers.add("connection", "keep-alive");
    headers.add("KEEP-ALIVE", "timeout=5");
    headers.add("Proxy-Connection", "keep-alive");
    headers.add("Transfer-Encoding", "chunked");
    headers.add("Upgrade", "h2c");
    headers.add("te", "trailers");
    headers.add("Trailer", "Expires");
    headers.add("X-Powered-By", "Express");
    headers.add("Location", "/projects");

    EdgeHopByHop.strip(headers);

    assertEquals(java.util.Set.of("X-Powered-By", "Location"), headers.names());
  }

  @Test
  void aHeaderTheConnectionHeaderNamesIsRemovedToo() {
    MultiMap headers = MultiMap.caseInsensitiveMultiMap();
    headers.add("Connection", "keep-alive");
    headers.add("Connection", "close, X-Custom-Hop ,  x-other-hop");
    headers.add("X-Custom-Hop", "1");
    headers.add("X-Other-Hop", "2");
    headers.add("Vary", "Origin");

    EdgeHopByHop.strip(headers);

    assertFalse(headers.contains("X-Custom-Hop"));
    assertFalse(headers.contains("X-Other-Hop"));
    assertFalse(headers.contains("Connection"));
    assertEquals(List.of("Origin"), headers.getAll("Vary"));
  }
}
