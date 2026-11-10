package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Where {@link WireguardPeers} reads the hand-stated peers: the property, and nothing else. */
class WireguardPeersTest {

  private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

  @Test
  void unsetIsNoPeersOnEveryEdge() {
    List<String> warnings = new ArrayList<>();
    assertEquals(List.of(), WireguardPeers.configured(Optional.empty(), warnings::add));
    assertEquals(List.of(), WireguardPeers.configured(Optional.of(" "), warnings::add));
    assertEquals(List.of(), warnings);
  }

  @Test
  void thePropertyIsTheStatedPeers() {
    List<WireguardPeers.Peer> peers =
        WireguardPeers.configured(Optional.of("laptop:" + KEY + ":100.96.0.3"), ignored -> {});
    assertEquals(List.of("laptop"), peers.stream().map(WireguardPeers.Peer::name).toList());
  }

  @Test
  void theImageShipsNoStaticPeersAnyMore() {
    // Epic qits-1977 replaced the proof peer's classpath file with registered peers.
    assertNull(WireguardPeers.class.getClassLoader().getResource("wireguard/static-peers"));
  }
}
