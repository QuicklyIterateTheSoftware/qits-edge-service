package eu.wohlben.qits.edge;

import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.net.SocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.jboss.logging.Logger;

/**
 * One proxy client's pool, seen from outside: how many connections it holds open to each origin,
 * which exchange each leased connection is carrying, and a WARN for an origin whose pool is under
 * pressure.
 *
 * <p><b>Why it exists.</b> A pool that filled up used to be silent until the first request queued
 * behind it timed out — and then every request to that origin did, thirty seconds each, with a WARN
 * per request naming the origin but nothing saying it had been full for an hour. This counts what
 * the pool holds, so the edge can say so itself ({@link #check}) and answer {@code /upstream-pools}
 * ({@link UpstreamPoolsRoute}).
 *
 * <p><b>There are two of these, one per client.</b> {@link EdgeRouter} proxies long-lived streams —
 * an SSE GET, a WebSocket — through a client of their own, because each one holds a pooled HTTP/1.1
 * connection for its whole life: dozens of agents' MCP event streams to qits-projects filled the
 * single pool, and every ordinary request to it queued behind them. {@link #pool} names which
 * client an instance counts — {@link #ORDINARY} or {@link #STREAM} — in the WARN and in the
 * document, because "the pool to qits-projects is full" means opposite things for the two.
 *
 * <p><b>The key is the origin as dialled</b>, {@code host:port} — the address Vert.x pools by. It
 * is read off the connection's remote address, whose host is the name the client resolved rather
 * than the IP it reached, so {@code dev-qits-projects:8080} is one key however often swarm moves
 * the task behind it.
 *
 * <p><b>Counted from the connection's own life</b>: up in the client's connect handler, down when
 * the Netty CHANNEL under it closes — not in the {@link HttpConnection}'s close handler. In Vert.x
 * 4.5.26 the two agree: a tunnel ({@code request.connect()} and {@code response.netSocket()}, which
 * is how both {@link EdgeWebSocketUpgrade} and {@code vertx-http-proxy} splice a {@code 101}) keeps
 * the HTTP connection as the channel's handler, so its close handler does fire — measured in {@code
 * UpstreamKeepAliveTest}. The channel is preferred anyway because it is the one signal no change of
 * handler can take away (Vert.x's own {@code Http1xClientConnection.toNetSocket} replaces the
 * handler and with it the close handler), and it leaves the public close handler free for anybody
 * else. A listener added to a channel that has already closed fires at once, so nothing counted is
 * left uncounted.
 *
 * <p><b>What {@code open} counts.</b> A pooled idle connection — it holds a slot, which is the
 * question. And a WebSocket, or any other spliced {@code 101}: the edge opens it through a client
 * like this one as an ordinary pooled HTTP/1.1 request, so the connect handler fires for it, and
 * Vert.x does not evict a tunnel from the pool — it keeps its slot until it closes. So {@code open}
 * is the pool's real occupancy, terminals included, and an origin full of terminals is a full pool.
 *
 * <p><b>What a lease is.</b> The {@link UpstreamLease} of the exchange a connection is carrying,
 * from the moment the pool GRANTED it to an origin request until that exchange is over. An idle
 * pooled connection has none, so {@code open} minus the leases is the pool's slack. The lease is
 * keyed by the connection's channel — HTTP/1.1 carries one exchange per connection at a time — and
 * ends on the first of three signals, none of which takes a handler anybody else installed:
 *
 * <ul>
 *   <li>the upstream response's {@code end()} future, success or failure. It is a promise of its
 *       own in Vert.x 4.5.26 ({@code HttpEventHandler.endPromise}), completed beside — not instead
 *       of — the end and exception handlers the proxy's pipe sets, and it fails when the response
 *       is reset or its connection drops mid-body;
 *   <li>the origin request's {@code response()} future failing: no answer ever came. The same
 *       future the proxy maps, asked a second time; a future has as many listeners as want it;
 *   <li>the channel closing, which is the backstop for everything else — an inbound client that
 *       went away makes the proxy reset the origin request, and a reset HTTP/1.1 request closes its
 *       connection. A spliced socket has no response end at all, so for a WebSocket this is the
 *       ONLY signal, and it is the right one: a tunnel holds its slot until it closes.
 * </ul>
 *
 * The first two remove the lease only if it is still THEIR lease ({@code remove(key, value)}): a
 * connection returned to the pool can be granted to the next request before the previous one's end
 * listener has run, and that must not clear the newcomer.
 *
 * <p><b>The WARN is about pressure, not about a full count at two instants.</b> It used to fire
 * when an origin was full at two checks with no close in between — which a pool of streams never
 * satisfies: streams come and go, every close is a dip, and the pool sat full for hours with the
 * rule never firing once. So {@link #check} now reads two measurements of the interval since the
 * previous check: how long the origin spent AT max, accumulated from the transitions themselves,
 * and how long requests waited for a connection, timed from {@code client.request(...)} to the
 * grant or the failure. Either one over its bound is the WARN — see {@link #AT_MAX_WARN_MS} and
 * {@link #SLOW_ACQUISITION_MS} — and the line names the five oldest leases, which is to say what
 * the pool is full OF.
 */
final class UpstreamPools {

  private static final Logger LOG = Logger.getLogger(UpstreamPools.class);

  /** The pool ordinary requests are proxied through: everything that is not a stream. */
  static final String ORDINARY = "ordinary";

  /**
   * The pool long-lived streams are proxied through — an SSE GET and a WebSocket upgrade. See
   * {@link EdgeRouter#isStream}.
   */
  static final String STREAM = "stream";

  /** How often {@link #check} runs in the edge, and therefore what "the interval" is. */
  static final long CHECK_INTERVAL_MS = 60_000;

  /**
   * How long in one interval an origin may sit at max before it is reported: half of it. A burst
   * that touches max for a few seconds is a pool doing its job; half a minute of every request
   * finding no free slot is not, whatever opened and closed in between.
   */
  static final long AT_MAX_WARN_MS = 30_000;

  /**
   * An acquisition that waited longer than this is reported. A free slot is granted in microseconds
   * and a fresh connection in a few milliseconds, so five seconds is either a queue behind a full
   * pool or an origin that does not answer its SYN — the connect timeout is the same five seconds —
   * and either is worth a line.
   */
  static final long SLOW_ACQUISITION_MS = 5_000;

  /** How many of an origin's leases a WARN names, oldest first. */
  static final int WARN_OLDEST_LEASES = 5;

  private final String pool;

  private final int max;

  /** How a WARN names an origin: its application and environment where the edge knows them. */
  private final Function<Upstream, String> describe;

  /** Monotonic nanoseconds, {@link System#nanoTime} outside tests. */
  private final LongSupplier nanoTime;

  private final Map<Upstream, Origin> origins = new ConcurrentHashMap<>();

  /** One entry per leased connection, keyed by {@link #key} — see the class comment. */
  private final Map<Object, Held> held = new ConcurrentHashMap<>();

  /** When the previous {@link #check} ran; only {@code check} touches it. */
  private long lastCheckNanos;

  /**
   * One origin's count and the interval's measurements. Every method is synchronized on the origin:
   * the count moves on whichever event loop a connection lives on, and the at-max clock has to see
   * the transitions in the order they happened.
   */
  private static final class Origin {

    private int open;

    /** When the count last reached max, or -1 while it is below. */
    private long atMaxSince = -1;

    /** Nanoseconds at max in this interval, not counting the stretch still running. */
    private long atMaxNanos;

    private long longestWaitNanos;

    private int slowWaits;

    synchronized void opened(int max, long now) {
      open++;
      if (open >= max && atMaxSince < 0) {
        atMaxSince = now;
      }
    }

    synchronized void closed(int max, long now) {
      if (open > 0) {
        open--;
      }
      if (open < max && atMaxSince >= 0) {
        atMaxNanos += now - atMaxSince;
        atMaxSince = -1;
      }
    }

    synchronized void waited(long nanos) {
      longestWaitNanos = Math.max(longestWaitNanos, nanos);
      if (nanos > TimeUnit.MILLISECONDS.toNanos(SLOW_ACQUISITION_MS)) {
        slowWaits++;
      }
    }

    synchronized int open() {
      return open;
    }

    /**
     * The interval that ends now, and the start of the next: a stretch at max still running is
     * counted up to now and carried on from now, so a pool that never leaves max reports every
     * interval whole.
     */
    synchronized Interval drain(long now) {
      long atMax = atMaxNanos + (atMaxSince >= 0 ? now - atMaxSince : 0);
      Interval interval = new Interval(open, atMax, longestWaitNanos, slowWaits);
      atMaxNanos = 0;
      if (atMaxSince >= 0) {
        atMaxSince = now;
      }
      longestWaitNanos = 0;
      slowWaits = 0;
      return interval;
    }
  }

  /** What one origin did in one interval, as {@link #check} reads it. */
  private record Interval(int open, long atMaxNanos, long longestWaitNanos, int slowWaits) {}

  /** One leased connection: whose origin, which exchange, since when. */
  record Held(Upstream origin, UpstreamLease lease, long sinceNanos) {}

  /** A lease as the document shows it: the exchange and how long it has held its connection. */
  record Holder(UpstreamLease lease, long ageMs) {}

  /**
   * One origin of one pool, as {@code /upstream-pools} lists it.
   *
   * @param held the origin's leases, oldest first
   */
  record Snapshot(String pool, Upstream origin, int open, int max, List<Holder> held) {}

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
   * @param pool which client this counts — {@link #ORDINARY} or {@link #STREAM}
   * @param max the client's configured pool size per origin — what "full" means
   * @param describe how a WARN names an origin, beyond its address
   */
  UpstreamPools(String pool, int max, Function<Upstream, String> describe) {
    this(pool, max, describe, System::nanoTime);
  }

  /**
   * @param nanoTime the clock the at-max time, the waits and a lease's age are read from — a fake
   *     one in {@code UpstreamPoolsTest}, which is the only way to test a rule about thirty seconds
   *     in less than thirty seconds
   */
  UpstreamPools(String pool, int max, Function<Upstream, String> describe, LongSupplier nanoTime) {
    this.pool = pool;
    this.max = max;
    this.describe = describe;
    this.nanoTime = nanoTime;
    this.lastCheckNanos = nanoTime.getAsLong();
  }

  /** Which client this counts: {@link #ORDINARY} or {@link #STREAM}. */
  String pool() {
    return pool;
  }

  /** The configured pool size per origin. */
  int max() {
    return max;
  }

  /**
   * The proxy client's connect handler: set the socket's keepalive timers, count the connection
   * against its origin, and uncount it — and drop any lease it still carries — when its channel
   * closes. Never throws — a connect handler that threw would fail a request over bookkeeping.
   */
  void connected(HttpConnection connection) {
    UpstreamKeepAlive.apply(connection);
    Upstream origin = origin(connection.remoteAddress());
    if (origin == null) {
      return;
    }
    opened(origin);
    try {
      // The channel, not the connection — see the class comment. A listener added to a future
      // already done runs at once, so a socket that closed in between is still uncounted.
      io.netty.channel.Channel key = UpstreamChannel.of(connection);
      key.closeFuture()
          .addListener(
              closed -> {
                closed(origin);
                held.remove(key);
              });
    } catch (RuntimeException unreachable) {
      // The internal cast no longer holds (UpstreamKeepAlive has said so, once). The connection's
      // own close handler is the public fallback, and in 4.5.26 it covers tunnels too. key()
      // falls back to the connection itself on the same condition, so the lease key still agrees.
      connection.closeHandler(
          closed -> {
            closed(origin);
            held.remove(connection);
          });
    }
  }

  /**
   * What a lease is keyed by: the connection's Netty channel, the same object whose close uncounts
   * it — and the connection itself when the internal cast no longer holds, which is exactly what
   * {@link #connected} falls back to as well.
   */
  static Object key(HttpConnection connection) {
    try {
      return UpstreamChannel.of(connection);
    } catch (RuntimeException unreachable) {
      return connection;
    }
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
    origins.computeIfAbsent(origin, ignored -> new Origin()).opened(max, nanoTime.getAsLong());
  }

  void closed(Upstream origin) {
    Origin counted = origins.get(origin);
    if (counted != null) {
      counted.closed(max, nanoTime.getAsLong());
    }
  }

  /** The moment an acquisition starts, for {@link #granted} or {@link #failed} to time it from. */
  long acquiring() {
    return nanoTime.getAsLong();
  }

  /**
   * An origin request was granted a connection: time the wait, and lease the connection to the
   * exchange until it is over — see the class comment for the three signals that end it.
   *
   * <p>Never throws, for the same reason as {@link #connected}: this runs on the request's path.
   *
   * @param dialled the origin as the edge dialled it, for the wait; the lease is filed under the
   *     connection's own remote origin, which is what {@code open} is counted by
   * @param untilClose true for a request that becomes a tunnel — a WebSocket handshake — whose
   *     lease ends only when its channel closes, because a spliced socket has no response end
   */
  void granted(
      Upstream dialled,
      long since,
      HttpClientRequest request,
      UpstreamLease lease,
      boolean untilClose) {
    try {
      waited(dialled, nanoTime.getAsLong() - since);
      HttpConnection connection = request.connection();
      if (connection == null) {
        return;
      }
      Upstream origin = origin(connection.remoteAddress());
      Object key = key(connection);
      Held leased = lease(key, origin == null ? normalised(dialled) : origin, lease);
      if (key instanceof io.netty.channel.Channel channel && !channel.isOpen()) {
        // Closed between the grant and the put above: the close listener may already have run and
        // found nothing to drop, so the lease is dropped here rather than held forever. A channel
        // is marked closed before its close future's listeners run, so one of the two always sees
        // the other.
        release(key, leased);
        return;
      }
      if (untilClose) {
        return;
      }
      request
          .response()
          .onComplete(
              answered -> {
                if (answered.failed()) {
                  release(key, leased);
                } else {
                  answered.result().end().onComplete(ended -> release(key, leased));
                }
              });
    } catch (RuntimeException failure) {
      LOG.errorf(failure, "could not record the lease on a connection to %s", dialled);
    }
  }

  /** An origin request was refused a connection — a full queue, a timeout, a refused connect. */
  void failed(Upstream dialled, long since) {
    try {
      waited(dialled, nanoTime.getAsLong() - since);
    } catch (RuntimeException failure) {
      LOG.errorf(failure, "could not record a failed acquisition to %s", dialled);
    }
  }

  /** One acquisition's wait against its origin. Package-visible so the rule is testable alone. */
  void waited(Upstream dialled, long nanos) {
    origins.computeIfAbsent(normalised(dialled), ignored -> new Origin()).waited(nanos);
  }

  /** Lease a connection to an exchange, from now. Package-visible for the tests. */
  Held lease(Object key, Upstream origin, UpstreamLease lease) {
    Held leased = new Held(origin, lease, nanoTime.getAsLong());
    held.put(key, leased);
    return leased;
  }

  /** End a lease, if it is still this one — see the class comment. */
  void release(Object key, Held leased) {
    held.remove(key, leased);
  }

  /** Every lease now held, whatever its origin's count: what a leak test has to see. */
  List<UpstreamLease> leases() {
    return held.values().stream().map(Held::lease).toList();
  }

  /** The origin key spelled the way a connection's remote address spells it. */
  private static Upstream normalised(Upstream dialled) {
    return new Upstream(dialled.host().toLowerCase(Locale.ROOT), dialled.port());
  }

  /** Every origin with at least one open connection, and how many. A copy: the counts move on. */
  Map<Upstream, Integer> open() {
    Map<Upstream, Integer> open = new LinkedHashMap<>();
    origins.forEach(
        (origin, counted) -> {
          int now = counted.open();
          if (now > 0) {
            open.put(origin, now);
          }
        });
    return open;
  }

  /** One origin's leases, oldest first. */
  private List<Held> heldBy(Upstream origin) {
    return held.values().stream()
        .filter(leased -> leased.origin().equals(origin))
        .sorted(Comparator.comparingLong(Held::sinceNanos))
        .toList();
  }

  /**
   * Every origin with at least one open connection, its count and its leases oldest first — what
   * {@code /upstream-pools} lists for this pool. A copy, read now.
   */
  List<Snapshot> snapshot() {
    long now = nanoTime.getAsLong();
    List<Snapshot> snapshot = new ArrayList<>();
    open()
        .forEach(
            (origin, open) ->
                snapshot.add(
                    new Snapshot(
                        pool,
                        origin,
                        open,
                        max,
                        heldBy(origin).stream()
                            .map(
                                leased ->
                                    new Holder(
                                        leased.lease(),
                                        TimeUnit.NANOSECONDS.toMillis(now - leased.sinceNanos())))
                            .toList())));
    return snapshot;
  }

  /**
   * The periodic check: one WARN per origin that was under pressure in the interval since the
   * previous check — at max for longer than {@link #AT_MAX_WARN_MS}, or with any acquisition that
   * waited longer than {@link #SLOW_ACQUISITION_MS}. Every interval measurement is reset here, so
   * each WARN is about one interval, and an origin that stays under pressure is reported at every
   * check, once a minute, until it is not.
   *
   * @return the origins reported, for the test that pins the rule
   */
  synchronized List<Upstream> check() {
    long now = nanoTime.getAsLong();
    long intervalNanos = now - lastCheckNanos;
    lastCheckNanos = now;
    List<Upstream> pressed = new ArrayList<>();
    origins.forEach(
        (origin, counted) -> {
          Interval interval = counted.drain(now);
          boolean longAtMax = interval.atMaxNanos() > TimeUnit.MILLISECONDS.toNanos(AT_MAX_WARN_MS);
          boolean slowAcquisition = interval.slowWaits() > 0;
          if (!longAtMax && !slowAcquisition) {
            return;
          }
          pressed.add(origin);
          LOG.warnf(
              "the %s upstream pool to %s is under pressure: %d/%d open, %s of the last %s at max,"
                  + " longest wait for a connection %s, %d waits over %d s (a request waits up to"
                  + " %d ms and then fails); oldest held: %s",
              pool,
              describe.apply(origin),
              interval.open(),
              max,
              seconds(interval.atMaxNanos()),
              seconds(intervalNanos),
              seconds(interval.longestWaitNanos()),
              interval.slowWaits(),
              SLOW_ACQUISITION_MS / 1000,
              EdgeRouter.ACQUIRE_TIMEOUT_MS,
              oldest(origin, now));
        });
    return pressed;
  }

  /**
   * The five oldest leases of one origin, as a WARN quotes them: {@code GET /projects/mcp 3605.2 s
   * client=10.0.0.7 workspace=ws-1 trace=4bf9…}. What the pool is full OF is the line's point.
   */
  String oldest(Upstream origin, long now) {
    List<Held> leases = heldBy(origin);
    if (leases.isEmpty()) {
      return "none";
    }
    List<String> named = new ArrayList<>();
    for (Held leased : leases.subList(0, Math.min(WARN_OLDEST_LEASES, leases.size()))) {
      UpstreamLease lease = leased.lease();
      named.add(
          lease.method()
              + " "
              + lease.path()
              + " "
              + seconds(now - leased.sinceNanos())
              + " client="
              + lease.client()
              + " workspace="
              + lease.workspaceId()
              + " trace="
              + lease.traceId());
    }
    return String.join("; ", named)
        + (leases.size() > WARN_OLDEST_LEASES
            ? "; and " + (leases.size() - WARN_OLDEST_LEASES) + " more"
            : "");
  }

  private static String seconds(long nanos) {
    return String.format(Locale.ROOT, "%.1f s", nanos / 1e9);
  }
}
