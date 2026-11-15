package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The private edge's fetch at start (qits-2174), against a stub deployer on loopback: it retries
 * whatever goes wrong — a 404 from a deployer without the door included — and offers the table once
 * the door answers; the delays double up to 30 s; and only the private edge fetches at all.
 */
class RouteTableFetchTest {

  private HttpServer deployer;

  /** What the stub answers, in order; the last one repeats. */
  private final List<Integer> statuses = new CopyOnWriteArrayList<>();

  private final List<String> authorizations = new CopyOnWriteArrayList<>();

  private final AtomicInteger calls = new AtomicInteger();

  private static final JsonObject TABLE = RouteTableRouteTest.table(42).put("edge", "private");

  @BeforeEach
  void startTheDeployer() throws IOException {
    deployer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    deployer.createContext(
        RouteTableFetch.PATH,
        exchange -> {
          int call = calls.getAndIncrement();
          authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
          int status = statuses.get(Math.min(call, statuses.size() - 1));
          byte[] body =
              (status == 200 ? TABLE.encode() : "{\"error\":\"nope\"}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    deployer.start();
  }

  @AfterEach
  void stopTheDeployer() {
    deployer.stop(0);
  }

  private URI uri() {
    return URI.create("http://127.0.0.1:" + deployer.getAddress().getPort() + RouteTableFetch.PATH);
  }

  @Test
  void itRetriesUntilTheDoorAnswersAndOffersTheTable() throws Exception {
    statuses.addAll(List.of(404, 503, 404, 200));
    List<JsonObject> offered = new ArrayList<>();
    RouteTableFetch.Fetcher fetcher =
        new RouteTableFetch.Fetcher(
            uri(),
            () -> "the-qits-token",
            offered::add,
            Duration.ofMillis(5),
            Duration.ofMillis(20));
    Thread thread = Thread.ofVirtual().start(fetcher);
    thread.join(Duration.ofSeconds(10));

    assertFalse(thread.isAlive(), "it stops once the table is fetched");
    assertEquals(4, fetcher.attempts());
    assertEquals(List.of(TABLE), offered);
    assertTrue(
        authorizations.stream().allMatch("Bearer the-qits-token"::equals),
        authorizations.toString());
  }

  @Test
  void aBearerThatCannotBeMintedIsRetriedLikeAnyFailure() throws Exception {
    statuses.add(200);
    AtomicInteger mints = new AtomicInteger();
    List<JsonObject> offered = new ArrayList<>();
    RouteTableFetch.Fetcher fetcher =
        new RouteTableFetch.Fetcher(
            uri(),
            () -> {
              if (mints.getAndIncrement() == 0) {
                throw new IllegalStateException("idp is not there");
              }
              return "late";
            },
            offered::add,
            Duration.ofMillis(5),
            Duration.ofMillis(20));
    Thread thread = Thread.ofVirtual().start(fetcher);
    thread.join(Duration.ofSeconds(10));
    assertEquals(2, fetcher.attempts());
    assertEquals(1, offered.size());
  }

  @Test
  void aDeployerWithoutTheDoorIsRetriedUntilStopped() throws Exception {
    statuses.add(404);
    List<JsonObject> offered = new ArrayList<>();
    RouteTableFetch.Fetcher fetcher =
        new RouteTableFetch.Fetcher(
            uri(), () -> "t", offered::add, Duration.ofMillis(5), Duration.ofMillis(10));
    Thread thread = Thread.ofVirtual().start(fetcher);
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (calls.get() < 5 && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertTrue(calls.get() >= 5, "it keeps asking: " + calls.get());
    fetcher.stop();
    thread.interrupt();
    thread.join(Duration.ofSeconds(10));
    assertFalse(thread.isAlive());
    assertTrue(offered.isEmpty());
  }

  @Test
  void theDelayDoublesUpToThirtySeconds() {
    List<Long> delays = new ArrayList<>();
    Duration delay = RouteTableFetch.FIRST_DELAY;
    for (int i = 0; i < 8; i++) {
      delays.add(delay.toSeconds());
      delay = RouteTableFetch.next(delay, RouteTableFetch.MAX_DELAY);
    }
    assertEquals(List.of(1L, 2L, 4L, 8L, 16L, 30L, 30L, 30L), delays);
  }

  @Test
  void onlyThePrivateEdgeFetches() throws Exception {
    String url = "http://127.0.0.1:" + deployer.getAddress().getPort();

    RouteTableFetch publicEdge = new RouteTableFetch();
    publicEdge.config = config(ExposureClass.PUBLIC, Optional.of(url));
    publicEdge.start(null);
    RouteTableFetch breakGlass = new RouteTableFetch();
    breakGlass.config = config(ExposureClass.ALL, Optional.of(url));
    breakGlass.start(null);
    RouteTableFetch noUrl = new RouteTableFetch();
    noUrl.config = config(ExposureClass.PRIVATE, Optional.empty());
    noUrl.start(null);
    assertFalse(publicEdge.fetching());
    assertFalse(breakGlass.fetching());
    assertFalse(noUrl.fetching());

    RouteTableFetch privateEdge = new RouteTableFetch();
    privateEdge.config = config(ExposureClass.PRIVATE, Optional.of(url));
    try {
      privateEdge.start(null);
      assertTrue(privateEdge.fetching());
    } finally {
      privateEdge.stop(null);
    }
    Thread.sleep(50);
    assertEquals(0, calls.get(), "no oidc client here, so no bearer and no call");
  }

  /** An {@link EdgeConfig} answering only what the fetch reads. */
  private static EdgeConfig config(ExposureClass exposure, Optional<String> url) {
    EdgeConfig.Routes routes =
        (EdgeConfig.Routes)
            Proxy.newProxyInstance(
                EdgeConfig.class.getClassLoader(),
                new Class<?>[] {EdgeConfig.Routes.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "url" -> url;
                      case "publisher" -> "dev-qits-deployments";
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    return (EdgeConfig)
        Proxy.newProxyInstance(
            EdgeConfig.class.getClassLoader(),
            new Class<?>[] {EdgeConfig.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "exposure" -> exposure;
                  case "routes" -> routes;
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }
}
