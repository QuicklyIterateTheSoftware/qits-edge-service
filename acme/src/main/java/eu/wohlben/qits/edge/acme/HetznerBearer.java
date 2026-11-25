package eu.wohlben.qits.edge.acme;

/**
 * The bearer {@link HetznerApi} sends, asked for on every request so a rotated secret or a renewed
 * token is picked up without rebuilding the client.
 *
 * <p>Two answers exist (qits-2523, qits-2617): the Hetzner token itself, where the base URL is
 * {@code api.hetzner.cloud} — the SEED edge a bootstrap starts, before anything else runs — and the
 * edge's own {@code qits} service-client token, where the base URL is the qits-egress gateway,
 * which authenticates the edge by it, strips it and injects the Hetzner token it alone holds. This
 * module does not know which one it holds; the service decides.
 */
@FunctionalInterface
public interface HetznerBearer {

  /**
   * @return the token, never blank
   * @throws Exception when no token can be had now; the call fails and is retried like any other
   *     failed DNS call
   */
  String token() throws Exception;
}
