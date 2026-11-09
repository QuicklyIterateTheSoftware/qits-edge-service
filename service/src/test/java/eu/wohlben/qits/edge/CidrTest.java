package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The address-block reading the listener allow-list and the peer log both stand on. */
class CidrTest {

  @Test
  void anIpv4BlockContainsItsRangeAndNothingElse() {
    Cidr overlay = Cidr.parse("10.231.0.0/20");
    assertTrue(overlay.contains(Cidr.literal("10.231.0.1")));
    assertTrue(overlay.contains(Cidr.literal("10.231.15.255")));
    assertFalse(overlay.contains(Cidr.literal("10.231.16.0")));
    assertFalse(overlay.contains(Cidr.literal("10.230.255.255")));
  }

  @Test
  void anIpv4MappedAddressIsTheIpv4Address() {
    // A dual-stack socket reports an IPv4 caller this way; read as sixteen bytes it would match no
    // IPv4 block and the private edge would refuse its own tunnel.
    assertTrue(Cidr.parse("100.96.0.0/24").contains(Cidr.literal("::ffff:100.96.0.2")));
    assertTrue(Cidr.parse("100.96.0.0/24").contains(Cidr.literal("[::ffff:100.96.0.2]")));
    assertTrue(Cidr.parse("::ffff:100.96.0.0/120").contains(Cidr.literal("100.96.0.9")));
  }

  @Test
  void anIpv6BlockMatchesIpv6Only() {
    Cidr loopback = Cidr.parse("::1/128");
    assertTrue(loopback.contains(Cidr.literal("0:0:0:0:0:0:0:1")));
    assertTrue(
        loopback.contains(Cidr.literal("::1%lo")), "a zone names an interface, not an address");
    assertFalse(loopback.contains(Cidr.literal("127.0.0.1")));
    assertFalse(Cidr.parse("127.0.0.0/8").contains(Cidr.literal("::1")));
  }

  @Test
  void aBareAddressIsItsOwnHostBlock() {
    assertTrue(Cidr.parse("100.96.0.2").contains(Cidr.literal("100.96.0.2")));
    assertFalse(Cidr.parse("100.96.0.2").contains(Cidr.literal("100.96.0.3")));
    assertTrue(Cidr.parse("0.0.0.0/0").contains(Cidr.literal("8.8.8.8")));
  }

  @Test
  void onlyLiteralsAreRead() {
    // No name is ever resolved: a lookup per connection, and an answer that depends on a resolver.
    assertNull(Cidr.literal("localhost"));
    assertNull(Cidr.literal(null));
    assertNull(Cidr.literal(""));
    for (String bad : List.of("localhost/8", "10.0.0.0/33", "10.0.0.0/x", "", "10.0.0/8x")) {
      assertThrows(IllegalArgumentException.class, () -> Cidr.parse(bad), bad);
    }
  }

  @Test
  void theAllowListIsAnyUntilSetAndThenAlwaysAdmitsTheLoopback() {
    ListenerAllowList any = ListenerAllowList.of(List.of());
    assertTrue(any.any());
    assertTrue(any.admits("203.0.113.7"));

    ListenerAllowList tunnel = ListenerAllowList.of(List.of("100.96.0.0/24", "10.231.0.0/20"));
    assertTrue(tunnel.admits("100.96.0.1"));
    assertTrue(tunnel.admits("::ffff:10.231.0.5"));
    assertTrue(tunnel.admits("127.0.0.1"), "the deployer's health check is curl localhost");
    assertTrue(tunnel.admits("0:0:0:0:0:0:0:1"));
    assertFalse(tunnel.admits("172.18.0.4"), "qits-net, which the private edge only dials out on");
    assertFalse(tunnel.admits(null), "a socket with no address literal");

    assertThrows(IllegalArgumentException.class, () -> ListenerAllowList.of(List.of("tunnel")));
  }
}
