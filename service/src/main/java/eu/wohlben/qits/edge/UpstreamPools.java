package eu.wohlben.qits.edge;

import io.vertx.core.http.HttpConnection;
import io.vertx.core.net.SocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.jboss.logging.Logger;

/**
 * How many connections the proxy client holds open to each origin, and a WARN for an origin whose
 * pool stays full.
 *
 * <p><b>Why it exists.</b> A pool that filled up used to be silent until the first request queued
 * behind it timed out — and then every request to that origin did, thirty seconds each, with a WARN
 * per request naming the origin but nothing saying it had been full for an hour. This counts what
 * the pool holds, so the edge can say so itself ({@link #check}) and answer {@code /upstream-pools}
 * ({@link UpstreamPoolsRoute}).
 *
 * <p><b>The key is the origin as dialled</b>, {@code host:port} — the address Vert.x pools by. It
 * is read off the connection's remote address, whose host is the name the client resolved rather
 * than the IP it reached, so {@code dev-qits-projects:8080} is one key however often swarm moves
 * the task behind it.
 *
 * <p><b>Counted from the connection's own life</b>: up in the client's connect handler, down in the
 * connection's close handler. Both run on the connection's event loop, so a connection cannot close
 * before it was counted. A pooled idle connection counts — it holds a slot, which is the question.
 */
final class UpstreamPools {

  private static final Logger LOG = Logger.getLogger(UpstreamPools.class);

  /** How often {@link #check} runs in the edge, and therefore how long "stays full" means. */
  static final long CHECK_INTERVAL_MS = 60_000;

  private final int max;

  /** How a WARN names an origin: its application and environment where the edge knows them. */
  private final Function<Upstream, String> describe;

  private final Map<Upstream, Origin> origins = new ConcurrentHashMap<>();

  /**
   * One origin's count, and the two facts {@link #check} needs to tell "full at two checks" apart
   * from "full for the whole interval between them".
   */
  private final class Origin {

    final AtomicInteger open = new AtomicInteger();

    /** Bumped every time a close leaves the pool below max. Written by the close handlers. */
    final AtomicLong dips = new AtomicLong();

    /** {@link #check}'s own memory of the previous check; nothing else touches these. */
    boolean fullAtLastCheck;

    long dipsAtLastCheck;
  }

  /**
   * Whose an origin is: the application label and the environment.
   *
   * @param environment null when nothing says — see {@link #guess}
   */
  record Owner(String name, String environment) {

    /**
     * The best label for an origin neither the projection nor the platform grid names — a
     * deployment that has since withdrawn the route, while the connection it left still holds a
     * slot. The platform's wire alias is {@code <env>-<application>}, so a host that starts with a
     * known environment and a dash is read that way: {@code dev-qits-projects} is {@code
     * qits-projects} in {@code dev}. Anything else is named by its host, with no environment,
     * rather than left out: an origin holding slots is exactly what the document is for.
     */
    static Owner guess(Upstream origin, java.util.Collection<String> environments) {
      String host = origin.host();
      for (String environment : environments) {
        String prefix = environment + "-";
        if (host.startsWith(prefix) && host.length() > prefix.length()) {
          return new Owner(host.substring(prefix.length()), environment);
        }
      }
      return new Owner(host, null);
    }

    /** How a log line names the origin: {@code qits-projects in dev (dev-qits-projects:8080)}. */
    String describe(Upstream origin) {
      return name + (environment == null ? "" : " in " + environment) + " (" + origin + ")";
    }
  }

  /**
   * @param max the client's configured pool size per origin — what "full" means
   * @param describe how a WARN names an origin, beyond its address
   */
  UpstreamPools(int max, Function<Upstream, String> describe) {
    this.max = max;
    this.describe = describe;
  }

  /** The configured pool size per origin. */
  int max() {
    return max;
  }

  /**
   * The proxy client's connect handler: set the socket's keepalive timers, count the connection
   * against its origin, and uncount it when it closes. Never throws — a connect handler that threw
   * would fail a request over bookkeeping.
   */
  void connected(HttpConnection connection) {
    UpstreamKeepAlive.apply(connection);
    Upstream origin = origin(connection.remoteAddress());
    if (origin == null) {
      return;
    }
    opened(origin);
    connection.closeHandler(closed -> closed(origin));
  }

  /**
   * The origin a connection was dialled as, or null for an address that is not one.
   *
   * <p>The host is {@link SocketAddress#host()}, which Vert.x fills from the socket address's host
   * STRING: the name it resolved when there was one, the literal otherwise. No lookup happens here.
   */
  static Upstream origin(SocketAddress remote) {
    if (remote == null || remote.host() == null || remote.host().isBlank()) {
      return null;
    }
    try {
      return new Upstream(remote.host().toLowerCase(Locale.ROOT), remote.port());
    } catch (IllegalArgumentException notAnOrigin) {
      return null;
    }
  }

  void opened(Upstream origin) {
    origins.computeIfAbsent(origin, ignored -> new Origin()).open.incrementAndGet();
  }

  void closed(Upstream origin) {
    Origin counted = origins.get(origin);
    if (counted == null) {
      return;
    }
    if (counted.open.decrementAndGet() < max) {
      counted.dips.incrementAndGet();
    }
  }

  /** Every origin with at least one open connection, and how many. A copy: the counts move on. */
  Map<Upstream, Integer> open() {
    Map<Upstream, Integer> open = new LinkedHashMap<>();
    origins.forEach(
        (origin, counted) -> {
          int now = counted.open.get();
          if (now > 0) {
            open.put(origin, now);
          }
        });
    return open;
  }

  /**
   * The periodic check: one WARN per origin whose pool has been at max for the whole interval since
   * the previous check — at max then, at max now, and no close in between took it below. A pool
   * that brushed max at two ticks while connections came and went in between is busy, not stuck,
   * and is not reported. An origin that stays stuck is reported at every check, once a minute,
   * until it drains.
   *
   * <p>{@code dips} is read BEFORE {@code open}, so a close racing the check is either seen as a
   * count below max or as a dip at the next check — never as an unbroken full interval.
   *
   * @return the origins reported, for the test that pins the rule
   */
  synchronized List<Upstream> check() {
    List<Upstream> stuck = new ArrayList<>();
    origins.forEach(
        (origin, counted) -> {
          long dips = counted.dips.get();
          int now = counted.open.get();
          boolean full = now >= max;
          if (full && counted.fullAtLastCheck && dips == counted.dipsAtLastCheck) {
            stuck.add(origin);
            LOG.warnf(
                "the upstream pool to %s has been full (%d/%d) for a whole check, %d s: every"
                    + " further request to it waits up to %d ms for a slot and then fails",
                describe.apply(origin),
                now,
                max,
                CHECK_INTERVAL_MS / 1000,
                EdgeRouter.ACQUIRE_TIMEOUT_MS);
          }
          counted.fullAtLastCheck = full;
          counted.dipsAtLastCheck = dips;
        });
    return stuck;
  }
}
