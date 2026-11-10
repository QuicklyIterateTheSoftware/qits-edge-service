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
import java.util.UUID;
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
  void theStartupSetIsTheKeyAndThePortWithNoPeers(@TempDir Path dir) throws Exception {
    Path socket = dir.resolve("wg0.sock");
    Path keyFile = dir.resolve("private.key");
    List<WireguardKey> told = new CopyOnWriteArrayList<>();
    try (FakeUapi uapi = new FakeUapi(socket, request -> "errno=0\n\n")) {
      assertNull(WireguardDevice.configure(keyFile, socket, 47812, 1, Duration.ZERO, told::add));

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
                  + "\n"),
          uapi.requests);
      assertEquals(64, hex.formatHex(key.privateKey()).length());
      assertEquals(1, told.size(), "the key is handed on once the device took it");
      assertEquals(key.publicKeyBase64(), told.get(0).publicKeyBase64());
    }
  }

  // --- LivePeers, against the same fake --------------------------------------------------------

  private static final String LAPTOP_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

  private static WireguardPeers.Peer laptop() throws Exception {
    return new WireguardPeers.Peer("laptop", LAPTOP_KEY, InetAddress.getByName("100.96.0.10"));
  }

  private static String hex(String base64) {
    return HexFormat.of().formatHex(Base64.getDecoder().decode(base64));
  }

  @Test
  void theBootstrapSendsOneFullSetOfStatedAndRegisteredPeers(@TempDir Path dir) throws Exception {
    Path socket = dir.resolve("wg0.sock");
    byte[] privateKey = new byte[32];
    privateKey[0] = 1;
    try (FakeUapi uapi = new FakeUapi(socket, request -> "errno=0\n\n")) {
      LivePeers live = new LivePeers(socket, List.of(proofPeer()));
      live.load(
          List.of(
              new WireguardPeerProjection.Row(
                  UUID.randomUUID(), "laptop", LAPTOP_KEY, "100.96.0.10", true),
              new WireguardPeerProjection.Row(
                  UUID.randomUUID(), "gone", PROOF_KEY.replace('9', '8'), "100.96.0.11", false)));
      live.deviceConfigured(privateKey, 47812);

      assertEquals(LivePeers.CATCHING_UP, live.unready(), "DOWN until the log head is reached");
      assertEquals(List.of(), uapi.requests, "nothing is sent while catching up");

      live.markCaughtUp();

      assertEquals(
          List.of(
              "set=1\n"
                  + "private_key="
                  + HexFormat.of().formatHex(privateKey)
                  + "\n"
                  + "listen_port=47812\n"
                  + "replace_peers=true\n"
                  + "public_key="
                  + hex(PROOF_KEY)
                  + "\n"
                  + "replace_allowed_ips=true\n"
                  + "allowed_ip=100.96.0.2/32\n"
                  + "public_key="
                  + hex(LAPTOP_KEY)
                  + "\n"
                  + "replace_allowed_ips=true\n"
                  + "allowed_ip=100.96.0.10/32\n"
                  + "\n"),
          uapi.requests,
          "ONE full set: the stated peer, then the active registered one, never the revoked");
      assertNull(live.unready());
      assertEquals(
          List.of("proof-workspace", "laptop"),
          live.peers().stream().map(WireguardPeers.Peer::name).toList());
    }
  }

  @Test
  void afterTheBootstrapEachFrameIsOneOperation(@TempDir Path dir) throws Exception {
    Path socket = dir.resolve("wg0.sock");
    try (FakeUapi uapi = new FakeUapi(socket, request -> "errno=0\n\n")) {
      LivePeers live = new LivePeers(socket, List.of());
      live.deviceConfigured(new byte[32], 47812);
      live.markCaughtUp();
      uapi.requests.clear();
      UUID peerId = UUID.randomUUID();

      live.apply(peerId, laptop(), null);
      live.apply(peerId, null, "REVOKED");

      assertEquals(
          List.of(
              "set=1\npublic_key="
                  + hex(LAPTOP_KEY)
                  + "\nreplace_allowed_ips=true\nallowed_ip=100.96.0.10/32\n\n",
              "set=1\npublic_key=" + hex(LAPTOP_KEY) + "\nremove=true\n\n"),
          uapi.requests);
      assertNull(live.unready());
      assertEquals(List.of(), live.peers());
    }
  }

  @Test
  void aFailedOperationIsReadinessDownUntilTheFullSetIsResent(@TempDir Path dir) throws Exception {
    Path socket = dir.resolve("wg0.sock");
    try (FakeUapi uapi = new FakeUapi(socket, request -> "errno=0\n\n")) {
      LivePeers live = new LivePeers(socket, List.of());
      live.deviceConfigured(new byte[32], 47812);
      live.markCaughtUp();
      uapi.requests.clear();

      uapi.reply = request -> "errno=22\n\n";
      live.apply(UUID.randomUUID(), laptop(), null);
      String reason = live.unready();
      assertTrue(reason != null && reason.contains("errno=22"), reason);
      assertDown(WireguardDevice.check(null, live.unready(), socket, 47812, n -> true), "errno=22");

      uapi.reply = request -> "errno=0\n\n";
      live.apply(UUID.randomUUID(), proofPeer(), null);
      assertEquals(1, uapi.requests.size(), "a device that is behind gets no single operation");

      live.resendIfFailed();
      assertNull(live.unready());
      assertEquals(2, uapi.requests.size());
      assertTrue(uapi.requests.get(1).contains("replace_peers=true\n"), uapi.requests.get(1));
      assertTrue(uapi.requests.get(1).contains("allowed_ip=100.96.0.10/32"));
      assertTrue(uapi.requests.get(1).contains("allowed_ip=100.96.0.2/32"));

      live.resendIfFailed();
      assertEquals(2, uapi.requests.size(), "nothing is resent while the device holds the set");
    }
  }

  @Test
  void aPublicEdgeHoldsThePeersAndSendsNothing() throws Exception {
    LivePeers live = new LivePeers();
    live.markCaughtUp();
    live.apply(UUID.randomUUID(), laptop(), null);
    assertEquals(List.of(laptop()), live.peers());
    assertNull(live.unready(), "off is never a reason to be DOWN");
  }

  @Test
  void aRefusedSetIsTheReasonAfterEveryAttempt(@TempDir Path dir) throws Exception {
    Path socket = dir.resolve("wg0.sock");
    try (FakeUapi uapi = new FakeUapi(socket, request -> "errno=22\n\n")) {
      String reason =
          WireguardDevice.configure(
              dir.resolve("private.key"), socket, 47812, 3, Duration.ZERO, key -> {});
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
            2,
            Duration.ofMillis(1),
            key -> {});
    assertTrue(reason.startsWith("set=1 on "), reason);
    assertTrue(Files.exists(dir.resolve("private.key")), "the key is kept for the next start");
  }

  @Test
  void readinessIsUpWhenTheDeviceAnswersThePortAndWg0IsUp(@TempDir Path dir) throws Exception {
    Path socket = dir.resolve("wg0.sock");
    try (FakeUapi uapi =
        new FakeUapi(socket, request -> "private_key=00\nlisten_port=47812\nerrno=0\n\n")) {
      HealthCheckResponse up = WireguardDevice.check(null, null, socket, 47812, name -> true);
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
    assertDown(WireguardDevice.check("not configured yet", null, socket, 47812, n -> true), "not");
    assertDown(
        WireguardDevice.check(null, LivePeers.CATCHING_UP, socket, 47812, n -> true),
        "peers not caught up");
    assertDown(WireguardDevice.check(null, null, socket, 47812, n -> true), "get=1 on");
    try (FakeUapi uapi = new FakeUapi(socket, request -> "listen_port=51820\nerrno=0\n\n")) {
      assertDown(WireguardDevice.check(null, null, socket, 47812, n -> true), "port 51820");
      uapi.reply = request -> "errno=5\n\n";
      assertDown(WireguardDevice.check(null, null, socket, 47812, n -> true), "errno=5");
      uapi.reply = request -> "listen_port=47812\nerrno=0\n\n";
      assertDown(WireguardDevice.check(null, null, socket, 47812, n -> false), "wg0 is not up");
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
