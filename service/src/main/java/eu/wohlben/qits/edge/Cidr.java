package eu.wohlben.qits.edge;

import java.net.InetAddress;
import java.util.Arrays;

/**
 * One address block, {@code 10.231.0.0/20} or {@code ::1/128}, and the question whether an address
 * is inside it.
 *
 * <p><b>Literals only, never a name.</b> Both the block and the address asked about are parsed with
 * {@link InetAddress#ofLiteral}, which does no DNS: a listener check that could resolve a name
 * would be a lookup per connection and a way to make the answer depend on a resolver.
 *
 * <p><b>An IPv4-mapped IPv6 address is the IPv4 address.</b> A dual-stack socket reports an IPv4
 * peer as {@code ::ffff:100.96.0.2}; read as sixteen bytes it would match no IPv4 block and every
 * check written in IPv4 would refuse it. So both sides are unwrapped to four bytes first, and a
 * mapped block ({@code ::ffff:10.0.0.0/104}) is the IPv4 block it maps.
 */
final class Cidr {

  private final byte[] network;
  private final int prefix;
  private final String spelling;

  private Cidr(byte[] network, int prefix, String spelling) {
    this.network = network;
    this.prefix = prefix;
    this.spelling = spelling;
  }

  /**
   * {@code address/prefix}; a bare address is its own host block ({@code /32} or {@code /128}).
   *
   * @throws IllegalArgumentException on anything that is not a literal block
   */
  static Cidr parse(String spelling) {
    if (spelling == null || spelling.isBlank()) {
      throw new IllegalArgumentException("An address block is empty.");
    }
    String text = spelling.strip();
    int slash = text.indexOf('/');
    InetAddress raw = literalOrNull(slash < 0 ? text : text.substring(0, slash));
    if (raw == null) {
      throw new IllegalArgumentException("`" + spelling + "` is not an address block.");
    }
    String address = slash < 0 ? text : text.substring(0, slash);
    byte[] bytes = raw.getAddress();
    // Written in IPv6 notation, the prefix counts 128 bits even where the JDK has already collapsed
    // an IPv4-mapped literal to its four bytes.
    int width = address.indexOf(':') >= 0 ? 128 : 32;
    int prefix;
    try {
      prefix = slash < 0 ? width : Integer.parseInt(text.substring(slash + 1));
    } catch (NumberFormatException unreadable) {
      throw new IllegalArgumentException("`" + spelling + "` has no readable prefix length.");
    }
    if (prefix < 0 || prefix > width) {
      throw new IllegalArgumentException(
          "`" + spelling + "` has a prefix longer than its address.");
    }
    byte[] mapped = mappedIpv4(bytes);
    if (mapped != null) {
      bytes = mapped;
    }
    if (width == 128 && bytes.length == 4) {
      if (prefix < 96) {
        throw new IllegalArgumentException(
            "`" + spelling + "` is an IPv4-mapped block wider than the mapped range.");
      }
      prefix -= 96;
    }
    return new Cidr(mask(bytes, prefix), prefix, text);
  }

  /** Whether {@code address} is inside this block; an IPv4 address is never in an IPv6 block. */
  boolean contains(InetAddress address) {
    if (address == null) {
      return false;
    }
    byte[] bytes = unwrap(address.getAddress());
    return bytes.length == network.length && Arrays.equals(mask(bytes, prefix), network);
  }

  /**
   * A socket's address string — {@code 100.96.0.2}, {@code 0:0:0:0:0:0:0:1}, {@code [fe80::1%eth0]}
   * — as an address, or null when it is not a literal at all.
   */
  static InetAddress literal(String text) {
    return literalOrNull(text);
  }

  private static InetAddress literalOrNull(String text) {
    if (text == null) {
      return null;
    }
    String literal = text.strip();
    if (literal.startsWith("[") && literal.endsWith("]")) {
      literal = literal.substring(1, literal.length() - 1);
    }
    int scope = literal.indexOf('%');
    if (scope >= 0) {
      // A link-local zone names an interface, not part of the address; no block here has one.
      literal = literal.substring(0, scope);
    }
    if (literal.isEmpty()) {
      return null;
    }
    try {
      return InetAddress.ofLiteral(literal);
    } catch (IllegalArgumentException notALiteral) {
      return null;
    }
  }

  private static byte[] unwrap(byte[] bytes) {
    byte[] mapped = mappedIpv4(bytes);
    return mapped == null ? bytes : mapped;
  }

  /** The four bytes of {@code ::ffff:a.b.c.d}, or null for any other address. */
  private static byte[] mappedIpv4(byte[] bytes) {
    if (bytes.length != 16) {
      return null;
    }
    for (int i = 0; i < 10; i++) {
      if (bytes[i] != 0) {
        return null;
      }
    }
    if (bytes[10] != (byte) 0xff || bytes[11] != (byte) 0xff) {
      return null;
    }
    return Arrays.copyOfRange(bytes, 12, 16);
  }

  private static byte[] mask(byte[] bytes, int prefix) {
    byte[] masked = bytes.clone();
    for (int i = 0; i < masked.length; i++) {
      int bits = Math.max(0, Math.min(8, prefix - i * 8));
      masked[i] = (byte) (masked[i] & (0xff << (8 - bits)));
    }
    return masked;
  }

  @Override
  public String toString() {
    return spelling;
  }
}
