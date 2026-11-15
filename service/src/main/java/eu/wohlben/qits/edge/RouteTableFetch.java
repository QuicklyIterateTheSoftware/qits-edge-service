package eu.wohlben.qits.edge;

import io.quarkus.oidc.client.OidcClients;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * <b>The private edge fetches its route table at start</b> (epic qits-2174): {@code GET
 * ${qits.edge.routes.url}/deployments/api/edge-routes/private} with the {@code qits} oidc client's
 * own bearer, retried after 1, 2, 4 … 30 s until the deployer answers, on a virtual thread of its
 * own, and stopped at shutdown. The public edge never fetches: it is on {@code qits-net} and the
 * deployer on {@code qits-private}, and nothing bridges them, so it waits for a push.
 *
 * <p><b>Nothing waits on it.</b> Readiness, startup and serving are untouched: D2a only stores the
 * table ({@link RouteTables}). Until qits-deployments ships the door (D1) every attempt is a 404,
 * and that is retried as quietly as any other failure — one WARN, {@code route table fetch failed:
 * <reason>}, for the whole run, and DEBUG lines after it.
 */
@ApplicationScoped
public class RouteTableFetch {

  private static final Logger LOG = Logger.getLogger(RouteTableFetch.class);

  static final String PATH = "/deployments/api/edge-routes/private";

  static final Duration FIRST_DELAY = Duration.ofSeconds(1);

  static final Duration MAX_DELAY = Duration.ofSeconds(30);

  static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);

  @Inject EdgeConfig config;

  @Inject RouteTables tables;

  @Inject OidcClients oidcClients;

  private volatile Fetcher fetcher;

  private volatile Thread thread;

  void start(@Observes StartupEvent ignored) {
    Optional<String> url = config.routes().url();
    if (config.exposure() != ExposureClass.PRIVATE || url.isEmpty() || url.get().isBlank()) {
      return;
    }
    URI uri = URI.create(url.get().replaceAll("/+$", "") + PATH);
    Fetcher started =
        new Fetcher(
            uri,
            () ->
                oidcClients
                    .getClient("qits")
                    .getTokens()
                    .await()
                    .atMost(CALL_TIMEOUT)
                    .getAccessToken(),
            table -> tables.offer(table),
            FIRST_DELAY,
            MAX_DELAY);
    fetcher = started;
    thread = Thread.ofVirtual().name("edge-route-table-fetch").start(started);
  }

  /**
   * Whether this process started the fetch at all: the private edge with a url, and nothing else.
   */
  boolean fetching() {
    return thread != null;
  }

  void stop(@Observes ShutdownEvent ignored) {
    Fetcher running = fetcher;
    if (running != null) {
      running.stop();
    }
    Thread runningThread = thread;
    if (runningThread != null) {
      runningThread.interrupt();
    }
  }

  /**
   * The loop itself, with its collaborators handed in, so it runs against a stub server without a
   * boot. It returns once the deployer answered {@code 200} and the table was offered — whatever
   * the store made of it: a refused table is the store's ERROR, not a reason to ask again — or once
   * stopped.
   */
  static final class Fetcher implements Runnable {

    private final URI uri;
    private final Supplier<String> bearer;
    private final Consumer<JsonObject> sink;
    private final Duration first;
    private final Duration max;
    private volatile boolean stopped;
    private volatile int attempts;
    private boolean warned;

    Fetcher(
        URI uri, Supplier<String> bearer, Consumer<JsonObject> sink, Duration first, Duration max) {
      this.uri = uri;
      this.bearer = bearer;
      this.sink = sink;
      this.first = first;
      this.max = max;
    }

    void stop() {
      stopped = true;
    }

    int attempts() {
      return attempts;
    }

    @Override
    public void run() {
      Duration delay = first;
      try (HttpClient client =
          HttpClient.newBuilder()
              .version(HttpClient.Version.HTTP_1_1)
              .connectTimeout(CALL_TIMEOUT)
              .build()) {
        while (!stopped) {
          attempts++;
          String failure;
          try {
            failure = once(client);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
          } catch (Exception e) {
            failure =
                e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
          }
          if (failure == null) {
            return;
          }
          if (!warned) {
            warned = true;
            LOG.warnf("route table fetch failed: %s", failure);
          } else {
            LOG.debugf("route table fetch failed again, retrying in %s: %s", delay, failure);
          }
          try {
            Thread.sleep(delay);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
          }
          delay = next(delay, max);
        }
      }
    }

    /** One attempt: null when the table was fetched and offered, else what went wrong. */
    private String once(HttpClient client) throws Exception {
      HttpRequest request =
          HttpRequest.newBuilder(uri)
              .timeout(CALL_TIMEOUT)
              .header("Accept", "application/json")
              .header("Authorization", "Bearer " + bearer.get())
              .GET()
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return uri + " answered " + response.statusCode();
      }
      JsonObject table;
      try {
        table = new JsonObject(response.body());
      } catch (RuntimeException unreadable) {
        return uri + " answered a body that is not a JSON object";
      }
      sink.accept(table);
      return null;
    }
  }

  /** The delay after {@code delay}: doubled, and never above {@code max}. */
  static Duration next(Duration delay, Duration max) {
    Duration doubled = delay.multipliedBy(2);
    return doubled.compareTo(max) > 0 ? max : doubled;
  }
}
