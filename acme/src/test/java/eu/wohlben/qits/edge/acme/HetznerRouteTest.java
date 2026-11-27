package eu.wohlben.qits.edge.acme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A {@link HetznerRoute} asked per request (qits-2760): a hop without a bearer sends no {@code
 * Authorization} at all (its client certificate is the credential), and the next request may take
 * another way out.
 */
class HetznerRouteTest {

  private static final String EMPTY =
      "{\"rrsets\":[],\"meta\":{\"pagination\":{\"page\":1,\"next_page\":null}}}";

  private final List<HttpServer> servers = new ArrayList<>();

  /** What each server saw: its name and the Authorization header (or "none"). */
  private final List<String> seen = new ArrayList<>();

  @AfterEach
  void stop() {
    servers.forEach(server -> server.stop(0));
  }

  @Test
  void aHopWithoutABearerSendsNoAuthorizationAndTheRouteIsAskedPerRequest() throws Exception {
    URI byCertificate = serve("8082");
    URI byBearer = serve("8081");
    AtomicReference<HetznerRoute.Hop> hop =
        new AtomicReference<>(
            new HetznerRoute.Hop(HetznerApi.defaultClient(), byCertificate, null));
    HetznerAddressRecords records = new HetznerAddressRecords(hop::get, "wohlben.eu");

    records.owned();
    hop.set(new HetznerRoute.Hop(HetznerApi.defaultClient(), byBearer, () -> "qits-jwt"));
    records.owned();

    assertThat(seen).containsExactly("8082 none", "8081 Bearer qits-jwt");
  }

  @Test
  void aBlankBearerIsStillARefusal() throws Exception {
    URI api = serve("8081");
    HetznerAddressRecords records =
        new HetznerAddressRecords(
            HetznerRoute.fixed(HetznerApi.defaultClient(), api, () -> " "), "wohlben.eu");
    assertThatThrownBy(records::owned).hasMessageContaining("no bearer");
    assertThat(seen).isEmpty();
  }

  private URI serve(String name) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          String authorization = exchange.getRequestHeaders().getFirst("Authorization");
          synchronized (seen) {
            seen.add(name + " " + (authorization == null ? "none" : authorization));
          }
          byte[] bytes = EMPTY.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    servers.add(server);
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
  }
}
