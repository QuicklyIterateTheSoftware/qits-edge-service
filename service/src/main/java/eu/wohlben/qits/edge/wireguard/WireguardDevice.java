package eu.wohlben.qits.edge.wireguard;

import eu.wohlben.qits.edge.WireguardConfig;
import eu.wohlben.qits.edge.WireguardPeers;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

/**
 * The private edge's {@code wg0}, configured from this process (epic qits-1976).
 *
 * <p>The device is brought up by the image's root init helper ({@code qits-edge-init --wireguard}):
 * wireguard-go holds the TUN, and its UAPI socket is chowned to uid 1001, which is all this process
 * needs. At start it loads — or on first start generates — the private key, logs the public key a
 * peer configures, and sends one {@code set=1}: the key, {@code qits.edge.wireguard.listen-port}
 * and exactly the configured peers ({@link WireguardPeers}), each allowed its one tunnel address. A
 * changed peer list takes a restart in this slice; qits-1977 sends the same operation live.
 *
 * <p>Readiness is DOWN until that {@code set=1} was answered {@code errno=0}, and afterwards
 * whenever {@code get=1} does not answer, answers another listen port, or {@code wg0} is not up —
 * so a wireguard-go that died takes the private edge out of service rather than leaving it serving
 * nothing on the tunnel. Off ({@code qits.edge.wireguard.enabled=false}, every public edge), it is
 * UP and touches nothing.
 */
@Readiness
@ApplicationScoped
public class WireguardDevice implements HealthCheck {

  static final String NAME = "edge wireguard device";

  static final String INTERFACE = "wg0";

  private static final Logger LOG = Logger.getLogger(WireguardDevice.class);

  /** Attempts at the first {@code set=1}, {@link #PAUSE} apart, for a socket still appearing. */
  static final int ATTEMPTS = 10;

  static final Duration PAUSE = Duration.ofMillis(500);

  @Inject WireguardConfig config;

  @Inject WireguardPeers peers;

  /** Null once configured; until then, why not. */
  private volatile String unconfigured = "not configured yet";

  void start(@Observes StartupEvent ignored) {
    if (!config.enabled()) {
      return;
    }
    unconfigured =
        configure(
            Path.of(config.keyFile()),
            Path.of(config.uapiSocket()),
            config.listenPort(),
            peers.peers(),
            ATTEMPTS,
            PAUSE);
  }

  /**
   * Load or create the key and send the configuration.
   *
   * @return null when the device took it, else the reason it did not
   */
  static String configure(
      Path keyFile,
      Path socket,
      int listenPort,
      List<WireguardPeers.Peer> peers,
      int attempts,
      Duration pause) {
    WireguardKey key;
    try {
      key = WireguardKey.loadOrCreate(keyFile);
    } catch (IOException | GeneralSecurityException | RuntimeException failed) {
      String reason = "the private key at " + keyFile + " could not be read or created: " + failed;
      LOG.error(reason);
      return reason;
    }
    LOG.infof("wireguard public key: %s", key.publicKeyBase64());
    String request = Uapi.setRequest(key.privateKey(), listenPort, peers);
    String reason = null;
    for (int attempt = 1; attempt <= attempts; attempt++) {
      try {
        int errno = Uapi.errno(Uapi.exchange(socket, request));
        if (errno == 0) {
          LOG.infof(
              "%s configured: listen port %d, %d peers %s",
              INTERFACE,
              listenPort,
              peers.size(),
              peers.stream().map(WireguardPeers.Peer::name).toList());
          return null;
        }
        reason = "set=1 on " + socket + " answered errno=" + errno;
      } catch (IOException failed) {
        reason = "set=1 on " + socket + " failed: " + failed.getMessage();
      }
      if (attempt < attempts) {
        try {
          Thread.sleep(pause);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    LOG.errorf("%s is NOT configured, the tunnel carries nothing: %s", INTERFACE, reason);
    return reason;
  }

  @Override
  public HealthCheckResponse call() {
    if (!config.enabled()) {
      return HealthCheckResponse.named(NAME).up().withData("wireguard", "off").build();
    }
    return check(
        unconfigured, Path.of(config.uapiSocket()), config.listenPort(), WireguardDevice::isUp);
  }

  /**
   * Readiness, with its inputs passed in.
   *
   * @param unconfigured null once the start-up configuration was taken, else why it was not
   * @param interfaceUp whether the named interface exists and is up
   */
  static HealthCheckResponse check(
      String unconfigured, Path socket, int listenPort, Predicate<String> interfaceUp) {
    if (unconfigured != null) {
      return down(unconfigured);
    }
    String answer;
    try {
      // The answer carries the private key: it is read here and never logged or returned.
      answer = Uapi.exchange(socket, Uapi.GET);
    } catch (IOException failed) {
      return down("get=1 on " + socket + " failed: " + failed.getMessage());
    }
    int errno = Uapi.errno(answer);
    if (errno != 0) {
      return down("get=1 on " + socket + " answered errno=" + errno);
    }
    String port = Uapi.value(answer, "listen_port");
    if (!String.valueOf(listenPort).equals(port)) {
      return down(
          "the device listens on port " + port + ", where " + listenPort + " is configured");
    }
    if (!interfaceUp.test(INTERFACE)) {
      return down(INTERFACE + " is not up");
    }
    return HealthCheckResponse.named(NAME)
        .up()
        .withData("interface", INTERFACE)
        .withData("listen_port", listenPort)
        .build();
  }

  private static HealthCheckResponse down(String reason) {
    return HealthCheckResponse.named(NAME)
        .down()
        .withData("interface", INTERFACE)
        .withData("reason", reason)
        .build();
  }

  static boolean isUp(String name) {
    try {
      NetworkInterface device = NetworkInterface.getByName(name);
      return device != null && device.isUp();
    } catch (SocketException unreadable) {
      return false;
    }
  }
}
