package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The private edge's forwarding guard, against a stand-in for {@code
 * /proc/sys/net/ipv4/ip_forward}.
 */
class ForwardingGuardHealthCheckTest {

  @TempDir Path dir;

  @Test
  void forwardingOnMakesAPrivateEdgeUnready() throws Exception {
    Path ipForward = Files.writeString(dir.resolve("ip_forward"), "1\n");
    HealthCheckResponse response =
        ForwardingGuardHealthCheck.check(ExposureClass.PRIVATE, true, ipForward);
    assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
    assertEquals(
        true, String.valueOf(response.getData().orElseThrow().get("reason")).contains("= 0"));
  }

  @Test
  void forwardingOffIsReady() throws Exception {
    Path ipForward = Files.writeString(dir.resolve("ip_forward"), "0\n");
    assertEquals(
        HealthCheckResponse.Status.UP,
        ForwardingGuardHealthCheck.check(ExposureClass.PRIVATE, true, ipForward).getStatus());
  }

  @Test
  void anUnreadableSwitchIsReadyWithANote() {
    HealthCheckResponse response =
        ForwardingGuardHealthCheck.check(ExposureClass.PRIVATE, true, dir.resolve("absent"));
    assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    assertEquals(
        true,
        String.valueOf(response.getData().orElseThrow().get("ip_forward")).contains("unreadable"));
  }

  @Test
  void aPublicEdgeDoesNotCheck() throws Exception {
    Path ipForward = Files.writeString(dir.resolve("ip_forward"), "1\n");
    assertEquals(
        HealthCheckResponse.Status.UP,
        ForwardingGuardHealthCheck.check(ExposureClass.PUBLIC, false, ipForward).getStatus());
  }

  @Test
  void theOverlayEdgeWithoutATunnelDoesNotCheck() throws Exception {
    // qits-2304: private, but no wg0 — the host's ip_forward=1 must not keep it unready.
    Path ipForward = Files.writeString(dir.resolve("ip_forward"), "1\n");
    HealthCheckResponse response =
        ForwardingGuardHealthCheck.check(ExposureClass.PRIVATE, false, ipForward);
    assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    assertEquals(
        true,
        String.valueOf(response.getData().orElseThrow().get("ip_forward")).contains("wireguard"));
  }
}
