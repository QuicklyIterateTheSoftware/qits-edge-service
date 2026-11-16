package eu.wohlben.qits.edge;

/**
 * Which variant this edge is: {@code qits.edge.exposure} ({@code QITS_EDGE_EXPOSURE}), {@code
 * public} unless stated (epic qits-1976). The private variant is the same image behind the
 * WireGuard tunnel.
 *
 * <p>Which deployments each one routes is no longer decided here: qits-deployments builds one route
 * table per edge (epic qits-2174) — {@code public} for this class's {@link #PUBLIC}, {@code
 * private} for {@link #PRIVATE} — and {@link RouteTables} refuses a table named for the other edge.
 *
 * <p>The platform's own app labels ({@link PlatformApps}) are public-edge code: a private edge
 * serves them as the ordinary unknown names they are there.
 */
public enum ExposureClass {
  PUBLIC,
  PRIVATE;

  /**
   * Whether this edge answers {@link PlatformApps}' labels from code: the public edge, and never
   * the private one.
   */
  boolean servesPlatformApps() {
    return this != PRIVATE;
  }
}
