package eu.wohlben.qits.edge;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness on a private edge: this container does not forward IP packets (epic qits-1976).
 *
 * <p>The tunnel ends in this process and must never route a peer onward — to qits-net, to the host,
 * to another peer. Peers are configured with {@code AllowedIPs = 100.96.0.1/32} on their side, and
 * this is the edge's own half: {@code /proc/sys/net/ipv4/ip_forward} reading {@code 1} makes
 * readiness DOWN with the reason, so the deployer never puts such a container into service. The
 * entrypoint refuses to start one as well; this catches a namespace whose setting changed after.
 *
 * <p>A file that cannot be read is UP with a note: not every host this runs on — a laptop, a test
 * runner — has it, and the entrypoint already checked where it matters. A public edge does not
 * check at all; it terminates no tunnel.
 *
 * <p><b>Nor does the overlay edge</b> (qits-edge-overlay, qits-2304): it is private, but {@code
 * qits.edge.wireguard.enabled} is false there, so there is no {@code wg0} and no peer whose packets
 * the kernel could route onward. Its {@code ip_forward} is the host's {@code 1}, as in every other
 * container on {@code qits-private}, and its deployment declares no {@code sysctls}; refusing it
 * would make that edge never ready for a guard that protects nothing. What is checked is the
 * tunnel, not the exposure class.
 */
@Readiness
@ApplicationScoped
public class ForwardingGuardHealthCheck implements HealthCheck {

  static final String NAME = "edge forwarding guard";

  static final Path IP_FORWARD = Path.of("/proc/sys/net/ipv4/ip_forward");

  @Inject EdgeConfig config;

  @Inject WireguardConfig wireguard;

  @Override
  public HealthCheckResponse call() {
    return check(config.exposure(), wireguard.enabled(), IP_FORWARD);
  }

  /**
   * The answer for an edge of {@code exposure}, terminating a tunnel or not, whose forwarding
   * switch is {@code ipForward}.
   */
  static HealthCheckResponse check(ExposureClass exposure, boolean tunnel, Path ipForward) {
    if (exposure != ExposureClass.PRIVATE) {
      return HealthCheckResponse.named(NAME)
          .up()
          .withData("exposure", "public")
          .withData("ip_forward", "not checked: a public edge terminates no tunnel")
          .build();
    }
    if (!tunnel) {
      return HealthCheckResponse.named(NAME)
          .up()
          .withData("exposure", "private")
          .withData("ip_forward", "not checked: wireguard is off, this edge terminates no tunnel")
          .build();
    }
    String value;
    try {
      value = Files.readString(ipForward, StandardCharsets.US_ASCII).strip();
    } catch (IOException | RuntimeException unreadable) {
      return HealthCheckResponse.named(NAME)
          .up()
          .withData("exposure", "private")
          .withData("ip_forward", "unreadable at " + ipForward + "; not checked")
          .build();
    }
    if ("0".equals(value)) {
      return HealthCheckResponse.named(NAME)
          .up()
          .withData("exposure", "private")
          .withData("ip_forward", "0")
          .build();
    }
    return HealthCheckResponse.named(NAME)
        .down()
        .withData("exposure", "private")
        .withData("ip_forward", value)
        .withData(
            "reason",
            "IP forwarding is on in this container, so the tunnel could route a peer onward;"
                + " a private edge requires "
                + ipForward
                + " = 0")
        .build();
  }
}
