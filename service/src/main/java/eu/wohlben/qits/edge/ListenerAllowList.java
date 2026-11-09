package eu.wohlben.qits.edge;

import java.util.ArrayList;
import java.util.List;

/**
 * The local addresses a connection may arrive on: {@code qits.edge.listen.allowed-local-cidrs}.
 *
 * <p><b>Empty is any</b>, which is the public edge and every edge before epic qits-1976. Set, a
 * connection whose LOCAL address — the interface it reached this process on, not the caller — is in
 * none of the blocks is closed before anything reads its request; see {@link EdgeListener}. The
 * private edge sets {@code 100.96.0.0/24,10.231.0.0/20}: the tunnel and the private overlay. It is
 * also attached to qits-net for its own egress, and this is what keeps a public service there from
 * walking in through its listener.
 *
 * <p><b>The loopback is always allowed</b> once a list is set, because the deployer's health check
 * is {@code curl http://localhost…} inside the container.
 */
final class ListenerAllowList {

  /** What a configured list always admits besides itself. */
  static final List<Cidr> LOOPBACK = List.of(Cidr.parse("127.0.0.0/8"), Cidr.parse("::1/128"));

  private final List<Cidr> allowed;

  private ListenerAllowList(List<Cidr> allowed) {
    this.allowed = List.copyOf(allowed);
  }

  /**
   * The list as configured: empty admits everything, anything else admits those blocks and {@link
   * #LOOPBACK}. A block that is not one fails startup — an allow-list that silently dropped an
   * entry would refuse exactly the traffic it was written for.
   */
  static ListenerAllowList of(List<String> configured) {
    List<Cidr> blocks = new ArrayList<>();
    for (String block : configured == null ? List.<String>of() : configured) {
      if (block != null && !block.isBlank()) {
        try {
          blocks.add(Cidr.parse(block));
        } catch (IllegalArgumentException unreadable) {
          throw new IllegalArgumentException(
              "qits.edge.listen.allowed-local-cidrs: " + unreadable.getMessage(), unreadable);
        }
      }
    }
    if (!blocks.isEmpty()) {
      blocks.addAll(LOOPBACK);
    }
    return new ListenerAllowList(blocks);
  }

  /** Exactly these blocks, loopback NOT added: what a test needs to refuse {@code 127.0.0.1}. */
  static ListenerAllowList exactly(List<Cidr> blocks) {
    return new ListenerAllowList(blocks);
  }

  /** Whether every local address is admitted, and the check can be skipped. */
  boolean any() {
    return allowed.isEmpty();
  }

  /**
   * Whether a connection whose local address reads {@code hostAddress} is admitted. Something that
   * is not an address literal — a unix socket's, or none — is refused once a list is set.
   */
  boolean admits(String hostAddress) {
    if (any()) {
      return true;
    }
    java.net.InetAddress address = Cidr.literal(hostAddress);
    for (Cidr block : allowed) {
      if (block.contains(address)) {
        return true;
      }
    }
    return false;
  }

  /** Whether an address literal is on the loopback: the in-container health check's caller. */
  static boolean loopback(String hostAddress) {
    java.net.InetAddress address = Cidr.literal(hostAddress);
    return LOOPBACK.stream().anyMatch(block -> block.contains(address));
  }

  @Override
  public String toString() {
    return any() ? "any" : allowed.toString();
  }
}
