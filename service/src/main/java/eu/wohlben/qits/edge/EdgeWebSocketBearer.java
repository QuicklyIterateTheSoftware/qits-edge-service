package eu.wohlben.qits.edge;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpHeaders;
import java.util.ArrayList;
import java.util.List;

/**
 * A bearer carried on a WebSocket handshake's {@code Sec-WebSocket-Protocol}, for the one caller
 * that has no other way to send it: a page on the loopback (epic qits-1230, feature qits-1246).
 *
 * <p><b>Why it exists.</b> Under {@code ng serve} an SPA on {@code http://localhost:<port>} calls
 * the platform with {@code Authorization: Bearer <token>} — see {@link EdgeCors} for that ruling. A
 * browser's {@code WebSocket} cannot set {@code Authorization}, and the loopback page holds no
 * session cookie, so its terminal and chat sockets had no credential at all. The one header a page
 * DOES control on a handshake is the subprotocol list, so the token rides there:
 *
 * <pre>{@code new WebSocket(url, ['qits.bearer', token])}</pre>
 *
 * <p><b>The contract.</b> The offer names {@link #MARKER}, and the entry immediately after it is
 * the token — a JWT or a {@code qits_tok_} value, both of which are RFC 7230 {@code token}s
 * (base64url and dots; no comma, space or separator), so the browser's constructor accepts them as
 * subprotocol names. Other subprotocols may be offered before or after the pair and are forwarded
 * untouched.
 *
 * <p><b>What the edge does with it</b> ({@link EdgeRouter}):
 *
 * <ol>
 *   <li>a page that is not on the loopback ({@link EdgeCors#isLoopback}) offering the marker is
 *       refused {@code 403} — a page under the platform domain has the cookie and uses it, and
 *       nothing else is meant to open a socket this way;
 *   <li>a marker with no token after it is refused {@code 401};
 *   <li>otherwise the token is MOVED: out of the offer and into {@code Authorization: Bearer}, the
 *       one place the rest of the edge reads a credential from. From there it is the ordinary
 *       machine path — {@link EdgeAuth#checkCredential} validates it exactly as any bearer on that
 *       name, rewrites an opaque token to its JWT, and the cookie is stripped. It is always
 *       checked, even on a name whose reads are open: a page that offered a credential asked for it
 *       to be held to every rule;
 *   <li>the upgrade path ({@link EdgeWebSocketUpgrade}) drops the marker from the offer it forwards
 *       ({@link #forwardedOffer}), and answers the browser with the marker when the upstream
 *       selected nothing ({@link #answer}). A browser that offered subprotocols fails a handshake
 *       whose answer selects none, and fails one that selects a value it did not offer; the marker
 *       is always one it offered.
 * </ol>
 *
 * <p><b>The token never leaves the inbound map in the offer</b>, so nothing downstream of the gate
 * — the upstream, the lease, a log — can see it there. No line in this process logs request headers
 * in the first place; this is the part that keeps it so.
 */
final class EdgeWebSocketBearer {

  /** The subprotocol that says "the next entry is my bearer token". */
  static final String MARKER = "qits.bearer";

  static final String PROTOCOL = "Sec-WebSocket-Protocol";

  private EdgeWebSocketBearer() {}

  /**
   * Every subprotocol offered, in order, across however many header lines carried them.
   *
   * @return empty when none was offered
   */
  static List<String> offered(MultiMap headers) {
    List<String> offered = new ArrayList<>();
    for (String value : headers.getAll(PROTOCOL)) {
      for (String entry : value.split(",")) {
        String name = entry.strip();
        if (!name.isEmpty()) {
          offered.add(name);
        }
      }
    }
    return offered;
  }

  /** Whether this offer carries the marker. */
  static boolean offersBearer(MultiMap headers) {
    return offered(headers).contains(MARKER);
  }

  /**
   * Take the token out of the offer and put it in {@code Authorization: Bearer}, leaving the marker
   * and every other entry behind. An {@code Authorization} already present is replaced: the offer
   * is the credential this handshake asked to be judged by.
   *
   * @return true when a token was moved, false when the marker has nothing after it — the headers
   *     are then untouched and the caller refuses
   */
  static boolean moveToAuthorization(MultiMap headers) {
    List<String> offered = offered(headers);
    int marker = offered.indexOf(MARKER);
    if (marker < 0 || marker + 1 >= offered.size()) {
      return false;
    }
    String token = offered.get(marker + 1);
    if (token.equals(MARKER)) {
      return false;
    }
    List<String> rest = new ArrayList<>(offered);
    rest.remove(marker + 1);
    headers.set(PROTOCOL, String.join(", ", rest));
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    return true;
  }

  /**
   * The offer as an upstream sees it: the marker removed — it is the edge's, not the service's —
   * and the header dropped altogether when nothing else was offered.
   */
  static void forwardedOffer(MultiMap headers) {
    if (!headers.contains(PROTOCOL)) {
      return;
    }
    List<String> rest = new ArrayList<>(offered(headers));
    rest.removeIf(MARKER::equals);
    if (rest.isEmpty()) {
      headers.remove(PROTOCOL);
    } else {
      headers.set(PROTOCOL, String.join(", ", rest));
    }
  }

  /**
   * The subprotocol the browser is answered with, given what it offered and what the upstream
   * selected.
   *
   * @param inbound the offer as the browser made it, token already removed
   * @param selected the upstream's {@code Sec-WebSocket-Protocol}, or null
   * @return the upstream's selection when the browser offered it, the marker when the browser
   *     offered the marker and the upstream's selection is absent or one it did not offer, and the
   *     upstream's selection unchanged in every other case — a handshake without the marker is not
   *     this class's business
   */
  static String answer(List<String> inbound, String selected) {
    if (!inbound.contains(MARKER)) {
      return selected;
    }
    if (selected != null && !selected.isBlank() && inbound.contains(selected.strip())) {
      return selected.strip();
    }
    return MARKER;
  }
}
