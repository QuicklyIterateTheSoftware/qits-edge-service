package eu.wohlben.qits.edge;

import java.util.Locale;
import java.util.Set;

/**
 * Which deployments this edge serves: {@code qits.edge.exposure} ({@code QITS_EDGE_EXPOSURE}),
 * {@code public} unless stated (epic qits-1976).
 *
 * <p>A deployment declares {@code exposure: public | private | both} and the deployer carries it on
 * {@code DeploymentActive.exposure}. An edge is one class, never {@code both}: the public edge
 * keeps {@code public} and {@code both}, the private edge — the same image behind the WireGuard
 * tunnel — keeps {@code private} and {@code both}. A frame with no exposure, which is every frame
 * written before the field existed, is {@code public}; that is why neither edge needed a migration
 * or a replay reset.
 *
 * <p><b>What a frame outside the class does is withdraw.</b> It is stored as an empty snapshot, not
 * skipped — see {@code DeploymentActiveSubscriber} — so a deployment that moves from {@code public}
 * to {@code private} leaves the public edge's vhost as surely as a retirement does.
 *
 * <p>The platform's own app labels ({@link PlatformApps}) are public-edge code: a private edge
 * serves them as the ordinary unknown names they are there, and does not answer them before its
 * catch-up, because it dials qits-events and idp on their internal aliases and never waits for
 * itself.
 */
public enum ExposureClass {
  PUBLIC,
  PRIVATE;

  /** The declared exposure both edges keep. */
  static final String BOTH = "both";

  /** The exposure a frame without the field has. */
  static final String DEFAULT = "public";

  private static final Set<String> DECLARED = Set.of("public", "private", BOTH);

  /** Whether a deployment declaring {@code exposure} is served by an edge of this class. */
  boolean keeps(String exposure) {
    String declared = declared(exposure);
    return declared.equals(BOTH) || declared.equals(name().toLowerCase(Locale.ROOT));
  }

  /** Whether this edge answers {@link PlatformApps}' labels from code: the public edge only. */
  boolean servesPlatformApps() {
    return this == PUBLIC;
  }

  /**
   * A frame's exposure, normalised: null or blank is {@link #DEFAULT}. A value that is none of the
   * three is refused rather than guessed at — the frame is then settled without changing routes,
   * exactly as any other unusable snapshot is, so a typo can neither publish nor withdraw a vhost.
   */
  static String declared(String exposure) {
    if (exposure == null || exposure.isBlank()) {
      return DEFAULT;
    }
    String declared = exposure.strip().toLowerCase(Locale.ROOT);
    if (!DECLARED.contains(declared)) {
      throw new IllegalArgumentException(
          "`" + exposure + "` is not an exposure; a deployment is public, private or both.");
    }
    return declared;
  }
}
