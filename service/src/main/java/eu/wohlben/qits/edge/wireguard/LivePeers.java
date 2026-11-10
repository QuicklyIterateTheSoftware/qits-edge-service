package eu.wohlben.qits.edge.wireguard;

import eu.wohlben.qits.edge.Cidr;
import eu.wohlben.qits.edge.DeploymentProjectionCatchup;
import eu.wohlben.qits.edge.EdgeConfig;
import eu.wohlben.qits.edge.WireguardConfig;
import eu.wohlben.qits.edge.WireguardPeers;
import eu.wohlben.qits.eventstream.control.CatchupResult;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/**
 * The peers {@code wg0} serves (epic qits-1977): those stated by hand in {@code
 * qits.edge.wireguard.peers} ({@link WireguardPeers}) plus every ACTIVE peer qits-network-access
 * registered, as {@link PeerEventsSubscriber} hands them over.
 *
 * <p><b>One full set, then one operation per frame.</b> At start the active rows of {@code
 * edge_wireguard_peer} are loaded, and a bootstrap — the one {@code DeploymentProjectionBootstrap}
 * runs, for {@value PeerEventsSubscriber#CONSUMER_ID} — rebuilds from the epoch and catches up to
 * the head. Only then is the device given the merged list, in ONE {@code set=1} with {@code
 * replace_peers=true}; until it answered {@code errno=0}, readiness is DOWN with {@value
 * #CATCHING_UP}. Afterwards each change a frame makes is one UAPI operation: an added or moved peer
 * is {@code public_key} + {@code replace_allowed_ips} + its {@code /32}, a removed one {@code
 * public_key} + {@code remove=true}; every other peer stays. An operation that is not answered
 * {@code errno=0} is a WARN and readiness DOWN, and from then on the full set is resent every
 * {@link #RESEND} until it is taken — no single operation is retried, because the full set is what
 * restores the truth whatever was missed.
 *
 * <p><b>The public edge stores and applies nothing.</b> There {@code qits.edge.wireguard.enabled}
 * is false: the rows are kept and this set is held in memory, so a public edge that is made private
 * has nothing to learn, and no UAPI socket is ever dialled.
 *
 * <p>One INFO line per applied change, {@code wireguard peer added: <name> <tunnelIp>} or {@code
 * wireguard peer removed: <name> <tunnelIp> (<reason>)}. A key is never logged.
 */
@ApplicationScoped
public class LivePeers {

  static final String CATCHING_UP = "peers not caught up";

  /** How often a failed device is given the full set again. */
  static final Duration RESEND = Duration.ofSeconds(30);

  private static final Logger LOG = Logger.getLogger(LivePeers.class);

  @Inject WireguardConfig config;

  @Inject WireguardPeers stated;

  @Inject WireguardPeerProjection rows;

  @Inject DeploymentProjectionCatchup catchup;

  @Inject EdgeConfig edge;

  private boolean enabled;

  private Path socket;

  private List<WireguardPeers.Peer> statedPeers = List.of();

  /** The registered, active peers, by peer id; guarded by {@code this}. */
  private final Map<UUID, WireguardPeers.Peer> registered = new LinkedHashMap<>();

  /** {@link #merged()} as of the last change, for readers off the lock — {@code PeerLog}. */
  private volatile List<WireguardPeers.Peer> current = List.of();

  private byte[] privateKey;

  private int listenPort;

  private volatile boolean caughtUp;

  /** Null while the device holds the set; else why it may not. */
  private volatile String failure;

  private volatile ScheduledExecutorService resender;

  LivePeers() {}

  /** An enabled set over {@code socket}, for a test: no CDI, no bootstrap, no resend timer. */
  LivePeers(Path socket, List<WireguardPeers.Peer> statedPeers) {
    this.enabled = true;
    this.socket = socket;
    this.statedPeers = List.copyOf(statedPeers);
    this.current = merged();
  }

  void start(@Observes StartupEvent ignored) {
    synchronized (this) {
      enabled = config.enabled();
      socket = Path.of(config.uapiSocket());
      statedPeers = stated.peers();
      load(rows.rows());
    }
    if (enabled) {
      ScheduledExecutorService schedule =
          Executors.newSingleThreadScheduledExecutor(
              new DefaultThreadFactory("edge-wireguard-peers-resend", true));
      schedule.scheduleWithFixedDelay(
          this::resendIfFailed, RESEND.toSeconds(), RESEND.toSeconds(), TimeUnit.SECONDS);
      resender = schedule;
    }
    if (!edge.projection().catchup().required()) {
      LOG.warn(
          "wireguard peer catch-up is disabled; the peers loaded from the projection are used");
      markCaughtUp();
      return;
    }
    Thread.ofVirtual().name("edge-wireguard-peers-catchup").start(this::catchUpUntilReady);
  }

  void stop(@Observes ShutdownEvent ignored) {
    ScheduledExecutorService schedule = resender;
    if (schedule != null) {
      resender = null;
      schedule.shutdownNow();
    }
  }

  /** The active rows of the projection, as the set's registered half. Unusable rows are skipped. */
  synchronized void load(List<WireguardPeerProjection.Row> rows) {
    registered.clear();
    for (WireguardPeerProjection.Row row : rows) {
      if (!row.active()) {
        continue;
      }
      WireguardPeers.Peer peer = peer(row.name(), row.publicKey(), row.tunnelIp());
      if (peer != null) {
        registered.put(row.peerId(), peer);
      }
    }
    current = merged();
  }

  /** A usable peer from stored or decoded strings, or null when the key or address is not one. */
  static WireguardPeers.Peer peer(String name, String publicKey, String tunnelIp) {
    if (publicKey == null || !WireguardPeers.isKey(publicKey) || tunnelIp == null) {
      return null;
    }
    InetAddress ip = Cidr.literal(tunnelIp.strip());
    if (ip == null) {
      return null;
    }
    return new WireguardPeers.Peer(name == null || name.isBlank() ? "-" : name, publicKey, ip);
  }

  private void catchUpUntilReady() {
    Duration retry = edge.projection().catchup().retry();
    // From the epoch, like the routing projection: the edge database may have been wiped while the
    // consumer's watermark survived. The projection's last-writer-wins keeps a replay over
    // surviving rows harmless.
    CatchupResult result = catchup.rebuildFromEpoch(PeerEventsSubscriber.CONSUMER_ID);
    while (!result.reachedLogHead()) {
      LOG.warnf("wireguard peers are not caught up (%s); retrying in %s", result.status(), retry);
      try {
        Thread.sleep(retry);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        LOG.warn("wireguard peer catch-up was interrupted; readiness stays down");
        return;
      }
      result = catchup.catchUp(PeerEventsSubscriber.CONSUMER_ID);
    }
    LOG.infof(
        "wireguard peer projection caught up to qits-events (%d frame(s) handled)",
        result.handled());
    markCaughtUp();
  }

  /** The log head was reached: from here on the device is given the set. */
  synchronized void markCaughtUp() {
    caughtUp = true;
    if (enabled) {
      sendFullSet();
    }
  }

  /**
   * The device took its startup set: the key and port the full set restates. Called by {@link
   * WireguardDevice}; a set that is already caught up is sent at once.
   */
  synchronized void deviceConfigured(byte[] privateKey, int listenPort) {
    this.privateKey = privateKey.clone();
    this.listenPort = listenPort;
    if (enabled && caughtUp) {
      sendFullSet();
    }
  }

  /** Every {@link #RESEND}: the full set again, while the last operation failed. */
  synchronized void resendIfFailed() {
    if (enabled && caughtUp && failure != null) {
      sendFullSet();
    }
  }

  /**
   * One frame's change, after the projection recorded it: {@code peer} is the peer now active under
   * {@code peerId}, or null when it is not any more.
   *
   * @param reason why it went, for the log line of a removal
   */
  public synchronized void apply(UUID peerId, WireguardPeers.Peer peer, String reason) {
    WireguardPeers.Peer before =
        peer == null ? registered.remove(peerId) : registered.put(peerId, peer);
    current = merged();
    if (!enabled || !caughtUp) {
      // The public edge applies nothing; a catching-up one sends the whole set when it is done.
      return;
    }
    if (failure != null) {
      // The device is already behind: the next full set carries this change too.
      return;
    }
    Set<String> served = keys(current);
    if (before != null
        && (peer == null || !before.publicKey().equals(peer.publicKey()))
        && !served.contains(before.publicKey())) {
      String why = peer == null ? (reason == null ? "revoked" : reason) : "REPLACED";
      if (!send(Uapi.removePeerRequest(before), "removing a peer")) {
        return;
      }
      LOG.infof("wireguard peer removed: %s %s (%s)", printable(before.name()), ip(before), why);
    }
    if (peer != null && !peer.equals(before)) {
      if (!send(Uapi.addPeerRequest(peer), "adding a peer")) {
        return;
      }
      LOG.infof("wireguard peer added: %s %s", printable(peer.name()), ip(peer));
    }
  }

  /** The set as it is now: stated peers first, then registered ones, one per key. */
  public List<WireguardPeers.Peer> peers() {
    return current;
  }

  /**
   * Readiness' part in this: null while the device holds the set, {@value #CATCHING_UP} until the
   * first full set was taken, else the failure that has the device behind.
   */
  public String unready() {
    if (!enabled) {
      return null;
    }
    if (!caughtUp) {
      return CATCHING_UP;
    }
    String failed = failure;
    if (failed == null && privateKey == null) {
      return CATCHING_UP;
    }
    return failed;
  }

  private void sendFullSet() {
    if (privateKey == null) {
      // Not configured yet, or never: WireguardDevice reports why, and deviceConfigured sends it.
      return;
    }
    List<WireguardPeers.Peer> peers = merged();
    String request = Uapi.setRequest(privateKey, listenPort, peers);
    if (send(request, "the full peer set")) {
      failure = null;
      LOG.infof(
          "%s peers set: %d peers %s",
          WireguardDevice.INTERFACE,
          peers.size(),
          peers.stream().map(p -> printable(p.name()) + " " + ip(p)).toList());
    }
  }

  /** One exchange; false, with {@link #failure} set and a WARN, when it was not taken. */
  private boolean send(String request, String what) {
    String reason;
    try {
      int errno = Uapi.errno(Uapi.exchange(socket, request));
      if (errno == 0) {
        return true;
      }
      reason = "set=1 (" + what + ") on " + socket + " answered errno=" + errno;
    } catch (IOException failed) {
      reason = "set=1 (" + what + ") on " + socket + " failed: " + failed.getMessage();
    }
    failure = reason + "; the full peer set is resent every " + RESEND.toSeconds() + "s";
    LOG.warnf("%s is behind its peers: %s", WireguardDevice.INTERFACE, failure);
    return false;
  }

  private List<WireguardPeers.Peer> merged() {
    List<WireguardPeers.Peer> merged = new ArrayList<>(statedPeers);
    Set<String> keys = keys(merged);
    for (WireguardPeers.Peer peer : registered.values()) {
      if (keys.add(peer.publicKey())) {
        merged.add(peer);
      }
    }
    return List.copyOf(merged);
  }

  private static Set<String> keys(List<WireguardPeers.Peer> peers) {
    Set<String> keys = new LinkedHashSet<>();
    peers.forEach(peer -> keys.add(peer.publicKey()));
    return keys;
  }

  private static String ip(WireguardPeers.Peer peer) {
    return peer.tunnelIp().getHostAddress();
  }

  /** A name as one log token: whitespace and control characters become {@code _}. */
  static String printable(String name) {
    StringBuilder out = new StringBuilder(name.length());
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      out.append(c <= 0x20 || c == 0x7f ? '_' : c);
    }
    return out.toString();
  }
}
