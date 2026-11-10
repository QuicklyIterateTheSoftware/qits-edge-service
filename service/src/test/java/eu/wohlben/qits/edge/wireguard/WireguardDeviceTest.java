package eu.wohlben.qits.edge.wireguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.edge.WireguardPeers;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link WireguardDevice} against a fake wireguard-go UAPI socket. */
class WireguardDeviceTest {

  private static final String PROOF_KEY = "95pJhHSoEe/esnOiCdHvLmFIMzADJiaoQSZ6WcMJvwM=";

  /** A UAPI server: records every request and answers it with {@code reply}. */
  static final class FakeUapi implements AutoCloseable {
    final List<String> requests = new CopyOnWriteArrayList<>();
    final ServerSocketChannel server;
    volatile Function<String, String> reply;

    FakeUapi(Path socket, Function<String, String> reply) throws IOException {
      this.reply = reply;
      server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
      server.bind(UnixDomainSocketAddress.of(socket));
      Thread.ofVirtual().start(this::serve);
    }

    private void serve() {
      while (server.isOpen()) {
        try (SocketChannel client = server.accept()) {
          ByteArrayOutputStream in = new ByteArrayOutputStream();
          ByteBuffer buffer = ByteBuffer.allocate(1024);
          while (!in.toString(StandardCharsets.US_ASCII).endsWith("\n\n")) {
            if (client.read(buffer) < 0) {
              break;
            }
            in.write(buffer.array(), 0, buffer.position());
            buffer.clear();
          }
          String request = in.toString(StandardCharsets.US_ASCII);
          requests.add(request);
          client.write(ByteBuffer.wrap(reply.apply(request).getBytes(StandardCharsets.US_ASCII)));
        } catch (IOException closed) {
          return;
        }
      }
    }

    @Override
    public void close() throws IOException {
      server.close();
    }
  }

  private static WireguardPeers.Peer proofPeer() throws Exception {
    return new WireguardPeers.Peer(
        "proof-workspace", PROOF_KEY, InetAddress.getByName("100.96.0.2"));
  }

  @Test
  void theSetIsTheKeyThePortAndExactlyThePeers(@TempDir Path dir) throws Exception {
    Path socket = dir.resolve("wg0.sock");
    Path keyFile = dir.resolve("private.key");
    try (FakeUapi uapi = new FakeUapi(socket, request -> "errno=0\n\n")) {
      assertNull(
          WireguardDevice.configure(
              keyFile, socket, 47812, List.of(proofPeer()), 1, Duration.ZERO));

      WireguardKey key = WireguardKey.loadOrCreate(keyFile);
      HexFormat hex = HexFormat.of();
      assertEquals(
          List.of(
              "set=1\n"
                  + "private_key="
                  + hex.formatHex(key.privateKey())
                  + "\n"
                  + "listen_port=47812\n"
                  + "replace_peers=true\n"
                  + "public_key="
                  + hex.formatHex(Base64.getDecoder().decode(PROOF_KEY))
                  + "\n"
                  + "replace_allowed_ips=true\n"
                  + "allowed_ip=100.96.0.2/32\n"
                  + "\n"),
          uapi.requests);
      assertEquals(64, hex.formatHex(key.privateKey()).length());
    }
  }

  @Test
  void aRefusedSetIsTheReasonAfterEveryAttempt(@TempDir Path dir) throws Exception {
    Path socket = dir.resolve("wg0.sock");
    try (FakeUapi uapi = new FakeUapi(socket, request -> "errno=22\n\n")) {
      String reason =
          WireguardDevice.configure(
              dir.resolve("private.key"), socket, 47812, List.of(), 3, Duration.ZERO);
      assertTrue(reason.contains("errno=22"), reason);
      assertEquals(3, uapi.requests.size());
    }
  }

  @Test
  void aMissingSocketIsRetriedThenTheReason(@TempDir Path dir) {
    String reason =
        WireguardDevice.configure(
            dir.resolve("private.key"),
            dir.resolve("absent.sock"),
            47812,
            List.of(),
            2,
            Duration.ofMillis(1));
    assertTrue(reason.startsWith("set=1 on "), reason);
    assertTrue(Files.exists(dir.resolve("private.key")), "the key is kept for the next start");
  }

  @Test
  void readinessIsUpWhenTheDeviceAnswersThePortAndWg0IsUp(@TempDir Path dir) throws Exception {
    Path socket = dir.resolve("wg0.sock");
    try (FakeUapi uapi =
        new FakeUapi(socket, request -> "private_key=00\nlisten_port=47812\nerrno=0\n\n")) {
      HealthCheckResponse up = WireguardDevice.check(null, socket, 47812, name -> true);
      assertEquals(HealthCheckResponse.Status.UP, up.getStatus());
      assertEquals(List.of("get=1\n\n"), uapi.requests);
      assertTrue(
          up.getData().orElseThrow().values().stream()
              .noneMatch(value -> String.valueOf(value).contains("private_key")),
          "the get answer is never repeated");
    }
  }

  @Test
  void readinessDownPaths(@TempDir Path dir) throws Exception {
    Path socket = dir.resolve("wg0.sock");
    assertDown(WireguardDevice.check("not configured yet", socket, 47812, n -> true), "not");
    assertDown(WireguardDevice.check(null, socket, 47812, n -> true), "get=1 on");
    try (FakeUapi uapi = new FakeUapi(socket, request -> "listen_port=51820\nerrno=0\n\n")) {
      assertDown(WireguardDevice.check(null, socket, 47812, n -> true), "port 51820");
      uapi.reply = request -> "errno=5\n\n";
      assertDown(WireguardDevice.check(null, socket, 47812, n -> true), "errno=5");
      uapi.reply = request -> "listen_port=47812\nerrno=0\n\n";
      assertDown(WireguardDevice.check(null, socket, 47812, n -> false), "wg0 is not up");
    }
  }

  private static void assertDown(HealthCheckResponse response, String reasonPart) {
    assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
    String reason = String.valueOf(response.getData().orElseThrow().get("reason"));
    assertTrue(reason.contains(reasonPart), reason);
  }

  @Test
  void theFailureNamesWhetherTheDeviceExists() {
    assertEquals("interface lo is up", WireguardDevice.deviceState("lo"));
    assertEquals("interface qitsnone0 does not exist", WireguardDevice.deviceState("qitsnone0"));
  }
}
