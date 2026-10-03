package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import org.junit.jupiter.api.Test;

class EdgeRequestFramingTest {

  private static MultiMap headers(String... pairs) {
    MultiMap headers = MultiMap.caseInsensitiveMultiMap();
    for (int i = 0; i < pairs.length; i += 2) {
      headers.add(pairs[i], pairs[i + 1]);
    }
    return headers;
  }

  @Test
  void anHttp2BodyWithoutALengthIsChunked() {
    for (HttpMethod method :
        new HttpMethod[] {HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE}) {
      assertTrue(
          EdgeRequestFraming.needsChunked(HttpVersion.HTTP_2, method, headers()), method.name());
    }
  }

  @Test
  void aKnownLengthKeepsIt() {
    assertFalse(
        EdgeRequestFraming.needsChunked(
            HttpVersion.HTTP_2, HttpMethod.POST, headers("content-length", "10")));
  }

  @Test
  void http11IsFramedByItsOwnHeaders() {
    assertFalse(EdgeRequestFraming.needsChunked(HttpVersion.HTTP_1_1, HttpMethod.POST, headers()));
  }

  @Test
  void readsCarryNoBody() {
    assertFalse(EdgeRequestFraming.needsChunked(HttpVersion.HTTP_2, HttpMethod.GET, headers()));
    assertFalse(EdgeRequestFraming.needsChunked(HttpVersion.HTTP_2, HttpMethod.HEAD, headers()));
  }
}
