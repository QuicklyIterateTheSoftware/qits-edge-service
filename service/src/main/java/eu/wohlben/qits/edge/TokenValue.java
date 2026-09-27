package eu.wohlben.qits.edge;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * What an opaque platform token looks like on the wire, and the only place that decides it.
 *
 * <p>A token is a value idp issued and stores hashed — {@code qits_tok_} and 43 characters of
 * base64url. It is <b>not</b> a JWT and carries nothing this process could check on its own: what
 * it stands for is a row at idp, which is exactly what makes it revocable. So the edge never parses
 * one, never looks for a key for one, and never forwards one. It asks idp what the token currently
 * stands for ({@link EdgeAuth}'s {@code checkToken}) and forwards the ordinary JWT that comes back.
 *
 * <p><b>The prefix is the whole recogniser, on purpose.</b> A JWT's first segment is base64url of a
 * JSON object, so it always begins {@code eyJ} and can never begin {@code qits_tok_}; the two can
 * therefore be told apart before anything is parsed, and neither path ever sees the other's value.
 * The length and the alphabet after the prefix are idp's to police: checking them here as well
 * would be a second copy of idp's format that an idp change would silently break, and a malformed
 * value is refused by idp — and that refusal is cached — at no more cost than a well-formed unknown
 * one.
 *
 * <p><b>Three spellings, one value.</b> {@code Authorization: Bearer qits_tok_…} for a client that
 * can set a header; {@code Basic base64(oauth2:qits_tok_…)} for git's credential helpers; and
 * {@code Basic base64(<anything>:qits_tok_…)} for every client that only knows a username and a
 * password — {@code docker login -u token}, maven's {@code settings.xml}, npm. The username is
 * ignored: the password is what says it is a token.
 */
final class TokenValue {

  /** The fixed prefix idp puts on every token it issues. */
  static final String PREFIX = "qits_tok_";

  private TokenValue() {}

  /** Whether this presented value is a token: the prefix, and something after it. */
  static boolean isToken(String value) {
    return value != null && value.length() > PREFIX.length() && value.startsWith(PREFIX);
  }

  /**
   * The token an HTTP Basic credential carries as its password, or null when the password is not a
   * token — a client secret, a JWT, nothing at all.
   *
   * <p>Split at the FIRST colon, which is RFC 7617's rule: a user-id cannot contain one, a password
   * can. A value that is not base64 is not a token either, and is left for the existing Basic path
   * to refuse in its own words.
   *
   * @param base64 what follows {@code Basic } in the header, trimmed
   */
  static String fromBasic(String base64) {
    if (base64 == null || base64.isBlank()) {
      return null;
    }
    String decoded;
    try {
      decoded = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException notBase64) {
      return null;
    }
    int colon = decoded.indexOf(':');
    if (colon < 0) {
      return null;
    }
    String password = decoded.substring(colon + 1);
    return isToken(password) ? password : null;
  }
}
