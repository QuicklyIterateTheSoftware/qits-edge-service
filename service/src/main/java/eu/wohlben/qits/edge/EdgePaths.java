package eu.wohlben.qits.edge;

/**
 * What a request path means, as opposed to how it was spelled (qits-2229).
 *
 * <p><b>Why it exists.</b> The edge is a reverse proxy between two parsers that disagree. Vert.x
 * matches routes on {@code RoutingContext.normalizedPath()}, which decodes a {@code %XX} that names
 * an unreserved character, removes {@code .} and {@code ..} segments and collapses {@code //} — so
 * {@code /%71/health}, {@code //q/health} and {@code /x/../q/health} all reach the route registered
 * for {@code /q/health}. Every decision the edge made about a path read the RAW {@code
 * request.path()} instead, and each one that compared it to a prefix could be stepped around by a
 * spelling the router — and the upstream's own Vert.x router — would read as that prefix anyway.
 * Measured live: an anonymous {@code GET /%71/health} on any app's name answered the edge's own
 * health with a 200, where the canonical {@code /q/health} was correctly routed and gated.
 *
 * <p><b>Two answers, for two kinds of decision.</b> A routing decision — which handler answers —
 * reads the normalised path, because that is the path the router itself matched: the guard must see
 * what the thing it guards will see. An EXEMPTION — a path that skips the credential — is granted
 * only on a {@link #canonical} spelling, because the edge forwards the raw path upstream untouched,
 * and a second normaliser on the far side (another Vert.x, an nginx, a servlet container) is free
 * to read a dot segment or an escaped slash differently from anything this process could compute. A
 * path that is not canonical is not refused: it merely loses the exemption and meets the ordinary
 * credential check, which no honest client ever notices, since none of them spells its paths that
 * way.
 */
final class EdgePaths {

  private EdgePaths() {}

  /**
   * Whether {@code raw} is spelled the one way every normaliser agrees on, so that a prefix
   * compared against it means the same thing here and upstream. True only when all of these hold:
   *
   * <ul>
   *   <li>it starts with {@code /};
   *   <li>it holds no {@code //} — Vert.x collapses one, so {@code //q/health} is {@code /q/health}
   *       to the router;
   *   <li>it holds no {@code \} — a slash to more than one parser, whatever RFC 3986 says;
   *   <li>no segment is {@code .} or {@code ..}, literally or percent-encoded ({@code %2e}, {@code
   *       .%2E}, …) — {@code /idp/../q/health} starts with the {@code /idp/} prefix and IS {@code
   *       /q/health} upstream;
   *   <li>no {@code %XX} decodes to an unreserved character ({@code A-Za-z0-9-._~}), which a
   *       normaliser is entitled to decode in place ({@code /%71/health} is {@code /q/health}), nor
   *       to {@code /} or {@code \}, which some decode into a separator;
   *   <li>every {@code %} is followed by two hex digits — a malformed escape is read differently by
   *       every parser that meets it, which is reason enough to trust none of them.
   * </ul>
   *
   * Any other escape — {@code %20}, {@code %3A}, a UTF-8 byte — is canonical: no normaliser changes
   * what it means, so a prefix check against it is sound.
   */
  static boolean canonical(String raw) {
    if (raw == null || !raw.startsWith("/") || raw.contains("//") || raw.indexOf('\\') >= 0) {
      return false;
    }
    for (int i = 0; i < raw.length(); i++) {
      if (raw.charAt(i) != '%') {
        continue;
      }
      if (i + 2 >= raw.length()) {
        return false;
      }
      int high = Character.digit(raw.charAt(i + 1), 16);
      int low = Character.digit(raw.charAt(i + 2), 16);
      if (high < 0 || low < 0) {
        return false;
      }
      char decoded = (char) (high * 16 + low);
      if (unreserved(decoded) || decoded == '/' || decoded == '\\') {
        return false;
      }
      i += 2;
    }
    for (String segment : raw.split("/", -1)) {
      // The escape rule above already refuses %2e, so this reads the literal dots; it decodes them
      // anyway so the rule stands on its own if that one is ever relaxed.
      String dots = segment.replace("%2e", ".").replace("%2E", ".");
      if (dots.equals(".") || dots.equals("..")) {
        return false;
      }
    }
    return true;
  }

  /** RFC 3986 §2.3's unreserved set: the characters an escape may be decoded to in place. */
  private static boolean unreserved(char c) {
    return (c >= 'A' && c <= 'Z')
        || (c >= 'a' && c <= 'z')
        || (c >= '0' && c <= '9')
        || c == '-'
        || c == '.'
        || c == '_'
        || c == '~';
  }
}
