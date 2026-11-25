package eu.wohlben.qits.edge;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Runtime ownership of the edge certificate; {@link #enabled()} off, or {@link Mode#OFF}, means no
 * public TLS automation.
 *
 * <p><b>The domain is not here.</b> It used to be, as {@code qits.edge.acme.domain}, and it was the
 * platform's one stated domain under a second name — the same value the grammar reads every Host
 * against. It is {@link EdgeConfig#domain()} now, injected as {@code QITS_DOMAIN}, and the
 * certificate is ordered for whatever this deployment says its domain is rather than for a value
 * that could disagree with the router's.
 */
@ConfigMapping(prefix = "qits.edge.acme")
public interface AcmeConfig {

  enum Mode {
    OFF,
    STAGING,
    PRODUCTION
  }

  @WithDefault("false")
  boolean enabled();

  @WithDefault("off")
  Mode mode();

  Optional<String> email();

  /**
   * The Hetzner token by value — the SEED edge's direct mode only (qits-2617), and only where no
   * {@link #hetznerTokenFile()} is set. Absent is the ordinary deployed edge: its DNS calls go
   * through qits-egress with its own qits bearer, and qits-egress holds the token. See {@link
   * HetznerAccess}.
   */
  Optional<String> hetznerToken();

  /**
   * The base of Hetzner's RRSet API, ending in {@code /v1/} (qits-2617).
   *
   * <p>By default the qits-egress gateway's {@code hetzner} route on qits-net, which forwards
   * {@code /hetzner/v1/zones/...} to {@code https://api.hetzner.cloud/v1/zones/...} and injects the
   * Hetzner token (the grant is this repository's {@code .config/qits/egress.yml}). The seed edge a
   * bootstrap starts is given {@code QITS_EDGE_ACME_HETZNER_API_URL=https://api.hetzner.cloud/v1/}
   * together with its token file, because qits-egress does not exist yet when it needs its first
   * certificate.
   */
  @WithDefault("http://${QITS_ENVIRONMENT:dev}-qits-egress:8081/hetzner/v1/")
  String hetznerApiUrl();

  /**
   * The names this certificate must carry beyond the wildcard set the edge derives for itself.
   *
   * <p>A wildcard is leftmost-only, so {@code *.<domain>} answers for {@code idp.<domain>} and for
   * nothing under it, and {@code *.<env>.<domain>} only holds where that middle label is an
   * environment. The edge derives a tier per known project for both of the depths a project label
   * makes — see {@code CertificateNames} — so a project's app hosts need no name here at all. What
   * is left for this key is what it always said it was: a list of NAMES, for the ones no tier
   * describes.
   *
   * <p>The bootstrap renders it, whole or relative to the domain:
   *
   * <pre>
   * QITS_EDGE_ACME_ADDITIONAL_NAMES=status.acme,status.gizmo.wohlben.eu
   * </pre>
   *
   * <p>Absent is the ordinary platform and orders exactly the derived set. A name added here
   * reaches the certificate at the next order — the 12h reconcile, or a restart at once.
   */
  Optional<List<String>> additionalNames();

  /**
   * How long a requested reconcile waits for the frames behind it before it orders anything.
   *
   * <p>It exists because of epoch replay. A fresh edge reads every {@code ProjectCreated} ever
   * published in one burst, and each of them grows the desired name set: without a debounce the
   * first frame would place an order for a certificate missing every project after it, and the
   * second order would draw on Let's Encrypt's production duplicate-certificate limit of five a
   * week. One window covers the whole burst, and the order that follows carries every slug.
   *
   * <p>Thirty seconds is chosen against the two things it trades: an order placed too early is a
   * rate-limited mistake, and an order placed late is a project whose app hosts have no certificate
   * for half a minute longer. There is no key in the bootstrap for it; it is here for an operator
   * who has a reason.
   */
  @WithDefault("PT30S")
  Duration reconcileDebounce();

  /**
   * The Hetzner token as a Docker/Kubernetes secret file; wins over {@link #hetznerToken()}, and an
   * empty file is a refusal. The seed edge's direct mode only — see {@link #hetznerToken()}.
   */
  Optional<Path> hetznerTokenFile();

  @WithDefault("/work/.letsencrypt")
  Path directory();

  @WithDefault("30d")
  Duration renewBefore();

  @WithDefault("10m")
  Duration dnsTimeout();
}
