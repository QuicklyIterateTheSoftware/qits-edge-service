package eu.wohlben.qits.edge.wireguard;

import eu.wohlben.qits.edge.EdgeConfig;
import eu.wohlben.qits.edge.EdgeRouter;
import eu.wohlben.qits.edge.WireguardConfig;
import eu.wohlben.qits.edge.dns.TunnelDnsConfig;
import eu.wohlben.qits.eventstream.QitsEventBus;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/**
 * Publishes {@link PrivateEdgeAnnounced} (epic qits-1977): once per start, as soon as both halves
 * are known — {@code wg0} took its configuration ({@link WireguardDevice}, which is only ever on a
 * private edge) and the tunnel DNS knows the public address ({@code TunnelDns}) — and again
 * whenever that address, looked up every five minutes, changes. Nothing else is a reason to
 * publish: every other field is fixed for the life of the process.
 *
 * <p><b>Only an edge that terminates the tunnel ever announces</b>: the gate is {@code
 * qits.edge.wireguard.enabled}, not the exposure class. The overlay edge (qits-edge-overlay,
 * qits-2304) is private but has no tunnel; announcing it would hand every peer a key and endpoint
 * that answer nothing, and with several private edges the newest announcement wins the public
 * zone's private records. So with wireguard off nothing is published whatever this is told — its
 * {@code TunnelDns} does not tell it the public address in the first place, and no {@code
 * WireguardDevice} ever configures a key there — and the reason is logged once at INFO.
 */
@ApplicationScoped
public class PrivateEdgeAnnouncer {

  private static final Logger LOG = Logger.getLogger(PrivateEdgeAnnouncer.class);

  /** The fixed half of the event, derived once from configuration. */
  record Settings(
      String application,
      int listenPort,
      String tunnelAddress,
      String tunnelCidr,
      String dnsAddress,
      int mtu,
      String domain) {

    /**
     * @param address wg0's {@code <ip>/<prefix>}, {@code qits.edge.wireguard.address}
     */
    static Settings of(
        String application,
        int listenPort,
        String address,
        String dnsAddress,
        int mtu,
        String domain) {
      String[] parts = address.strip().split("/", -1);
      if (parts.length != 2) {
        throw new IllegalArgumentException(
            "qits.edge.wireguard.address `" + address + "` is not an IPv4 CIDR like 100.96.0.1/24");
      }
      Inet4Address ip = ipv4(parts[0]);
      int prefix;
      try {
        prefix = Integer.parseInt(parts[1]);
      } catch (NumberFormatException notANumber) {
        prefix = -1;
      }
      if (ip == null || prefix < 0 || prefix > 32) {
        throw new IllegalArgumentException(
            "qits.edge.wireguard.address `" + address + "` is not an IPv4 CIDR like 100.96.0.1/24");
      }
      byte[] network = ip.getAddress();
      for (int bit = prefix; bit < 32; bit++) {
        network[bit / 8] &= (byte) ~(0x80 >>> (bit % 8));
      }
      String cidr;
      try {
        cidr = InetAddress.getByAddress(network).getHostAddress() + "/" + prefix;
      } catch (UnknownHostException impossible) {
        throw new IllegalStateException(impossible);
      }
      return new Settings(
          application, listenPort, ip.getHostAddress(), cidr, dnsAddress, mtu, domain);
    }
  }

  @Inject QitsEventBus bus;

  @Inject WireguardConfig wireguard;

  @Inject EdgeConfig edge;

  @Inject TunnelDnsConfig dns;

  private Settings settings;

  private Consumer<PrivateEdgeAnnounced> publisher;

  private Clock clock = Clock.systemUTC();

  private String publicKey;

  private Inet4Address publicAddress;

  /** The address last announced, or null before the first announcement. */
  private Inet4Address announced;

  /** What {@link #announceIfDue} last declined for, so each reason is logged once. */
  private String declined;

  /** Whether this edge terminates the tunnel at all: {@code qits.edge.wireguard.enabled}. */
  private boolean tunnel = true;

  PrivateEdgeAnnouncer() {}

  PrivateEdgeAnnouncer(Settings settings, Consumer<PrivateEdgeAnnounced> publisher, Clock clock) {
    this(settings, publisher, clock, true);
  }

  /** As a test's, with the tunnel switch stated: false is an edge that never announces. */
  PrivateEdgeAnnouncer(
      Settings settings, Consumer<PrivateEdgeAnnounced> publisher, Clock clock, boolean tunnel) {
    this.settings = settings;
    this.publisher = publisher;
    this.clock = clock;
    this.tunnel = tunnel;
  }

  @PostConstruct
  void init() {
    // Off the caller's thread: publishing dials qits-events, and the callers are the startup
    // observer and the DNS refresher, neither of which should wait on it.
    publisher =
        event ->
            Thread.ofVirtual()
                .name("edge-private-announce")
                .start(
                    () -> {
                      bus.publish(event);
                      logAnnounced(event);
                    });
    tunnel = wireguard.enabled();
    if (!tunnel) {
      return;
    }
    try {
      settings =
          Settings.of(
              wireguard.application(),
              wireguard.listenPort(),
              wireguard.address(),
              dns.bind(),
              wireguard.mtu(),
              EdgeRouter.domain(edge.domain()));
    } catch (IllegalArgumentException unusable) {
      LOG.errorf("this private edge is NOT announced: %s", unusable.getMessage());
    }
  }

  /** wg0 took its configuration: this is its public key. */
  public synchronized void deviceConfigured(String publicKeyBase64) {
    publicKey = publicKeyBase64;
    announceIfDue();
  }

  /** The tunnel DNS knows the public address now, first or changed. */
  public synchronized void publicAddress(Inet4Address address) {
    publicAddress = address;
    announceIfDue();
  }

  private void announceIfDue() {
    String missing = missing();
    if (missing != null) {
      declined(missing);
      return;
    }
    declined = null;
    if (publicAddress.equals(announced)) {
      return;
    }
    PrivateEdgeAnnounced event =
        new PrivateEdgeAnnounced(
            UUID.randomUUID(),
            settings.application(),
            publicKey,
            publicAddress.getHostAddress(),
            settings.listenPort(),
            settings.tunnelAddress(),
            settings.tunnelCidr(),
            settings.dnsAddress(),
            settings.mtu(),
            settings.domain(),
            clock.instant());
    announced = publicAddress;
    LOG.infof(
        "announcing this private edge: %s at %s:%d, tunnel %s, dns %s, mtu %d, zone %s",
        event.application(),
        event.publicAddress(),
        event.listenPort(),
        event.tunnelCidr(),
        event.dnsAddress(),
        event.mtu(),
        event.domain());
    publisher.accept(event);
  }

  /** Why no announcement can be made yet, or null when it can. */
  private String missing() {
    if (!tunnel) {
      return "wireguard is off";
    }
    if (settings == null) {
      return "its settings are unusable (qits.edge.wireguard.address, see the ERROR at start)";
    }
    if (publicKey == null) {
      return "wg0 has no key yet (not configured)";
    }
    if (publicAddress == null) {
      return "the tunnel DNS knows no public address yet";
    }
    return null;
  }

  /** One line per change of reason: every call repeats it, the log does not. */
  private void declined(String missing) {
    if (missing.equals(declined)) {
      return;
    }
    declined = missing;
    if (settings == null && tunnel) {
      LOG.warnf("private edge not announced: %s", missing);
    } else {
      LOG.infof("private edge not announced yet: %s", missing);
    }
  }

  /** The bus took the event: delivered, or owned by its outbox, which the bus does not say. */
  static void logAnnounced(PrivateEdgeAnnounced event) {
    LOG.infof(
        "private edge announced: key %s at %s:%d",
        event.publicKey(), event.publicAddress(), event.listenPort());
  }

  /** The reason last logged, or null since an announcement was due; for a test. */
  synchronized String declined() {
    return declined;
  }

  private static Inet4Address ipv4(String literal) {
    String text = literal.strip();
    if (!text.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
      return null;
    }
    try {
      return InetAddress.getByName(text) instanceof Inet4Address v4 ? v4 : null;
    } catch (UnknownHostException unreadable) {
      return null;
    }
  }
}
