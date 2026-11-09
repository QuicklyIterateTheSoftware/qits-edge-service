package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The private edge's per-request line: who the caller is, and how the line reads. */
class PeerLogTest {

  /** 32 bytes of base64, the shape of a WireGuard public key. */
  private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

  @Test
  void aConfiguredPeerIsNamedByItsTunnelAddress() {
    List<String> warnings = new ArrayList<>();
    PeerLog log =
        new PeerLog(
            WireguardPeers.parse(
                "proof-workspace:" + KEY + ":100.96.0.2, laptop:" + KEY + ":100.96.0.3",
                warnings::add),
            Cidr.parse("10.231.0.0/20"));

    assertEquals(List.of(), warnings);
    assertEquals("proof-workspace", log.peer("100.96.0.2"));
    assertEquals("proof-workspace", log.peer("::ffff:100.96.0.2"));
    assertEquals("laptop", log.peer("100.96.0.3"));
    assertEquals("overlay", log.peer("10.231.3.4"), "a private service on qits-private");
    assertEquals("unknown", log.peer("100.96.0.9"), "on the tunnel, but nobody configured it");
    assertEquals("unknown", log.peer("127.0.0.1"));
    assertEquals("unknown", log.peer(null));
  }

  @Test
  void aMalformedEntryIsDroppedLoudlyAndTheRestStillCount() {
    List<String> warnings = new ArrayList<>();
    List<WireguardPeers.Peer> peers =
        WireguardPeers.parse(
            "good:"
                + KEY
                + ":100.96.0.2,"
                + "two:fields,"
                + "short-key:AAAA:100.96.0.4,"
                + "no-ip:"
                + KEY
                + ":nowhere,"
                + ":"
                + KEY
                + ":100.96.0.6,",
            warnings::add);

    assertEquals(List.of("good"), peers.stream().map(WireguardPeers.Peer::name).toList());
    assertEquals(4, warnings.size(), String.valueOf(warnings));
    assertEquals(
        "qits.edge.wireguard.peers entry 2 is ignored: it has 2 fields where"
            + " name:publicKey:tunnelIp has three (name:base64PublicKey:tunnelIp)",
        warnings.get(0));
    warnings.forEach(
        warning -> assertEquals(false, warning.contains(KEY), "a key is never logged: " + warning));
  }

  @Test
  void noPeersIsNoList() {
    assertEquals(List.of(), WireguardPeers.parse(null, ignored -> {}));
    assertEquals(List.of(), WireguardPeers.parse("  ", ignored -> {}));
  }

  @Test
  void theLineHasItsFieldsInOrder() {
    assertEquals(
        "peer=proof-workspace ip=100.96.0.2 method=GET host=ci.dev.acme.wohlben.eu"
            + " path=/ci/api/runs status=200",
        PeerLog.line(
            "proof-workspace", "100.96.0.2", "GET", "ci.dev.acme.wohlben.eu", "/ci/api/runs", 200));
  }

  @Test
  void aWireValueCannotEndTheLineOrForgeAField() {
    assertEquals(
        "peer=unknown ip=10.0.0.1 method=GET host=- path=/a%20b%0Apeer=admin%25 status=404",
        PeerLog.line("unknown", "10.0.0.1", "GET", null, "/a b\npeer=admin%", 404));
  }
}
