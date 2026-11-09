package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Where {@link WireguardPeers} reads the peer list the peer log and the device share. */
class WireguardPeersTest {

  private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

  @Test
  void theShippedFileIsTheProofPeerOnAPrivateEdge() {
    List<String> warnings = new ArrayList<>();
    List<WireguardPeers.Peer> peers =
        WireguardPeers.configured(Optional.empty(), ExposureClass.PRIVATE, warnings::add);

    assertEquals(List.of(), warnings);
    assertEquals(1, peers.size());
    assertEquals("proof-workspace", peers.get(0).name());
    assertEquals("95pJhHSoEe/esnOiCdHvLmFIMzADJiaoQSZ6WcMJvwM=", peers.get(0).publicKey());
    assertEquals("100.96.0.2", peers.get(0).tunnelIp().getHostAddress());
  }

  @Test
  void thePropertyWinsOverTheFile() {
    List<WireguardPeers.Peer> peers =
        WireguardPeers.configured(
            Optional.of("laptop:" + KEY + ":100.96.0.3"), ExposureClass.PRIVATE, ignored -> {});
    assertEquals(List.of("laptop"), peers.stream().map(WireguardPeers.Peer::name).toList());
  }

  @Test
  void aPublicEdgeNeverReadsTheFile() {
    assertEquals(
        List.of(),
        WireguardPeers.configured(Optional.empty(), ExposureClass.PUBLIC, ignored -> {}));
    assertEquals(
        List.of(),
        WireguardPeers.configured(Optional.of(" "), ExposureClass.PUBLIC, ignored -> {}));
  }
}
