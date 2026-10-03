package eu.wohlben.qits.edge;

import io.smallrye.config.ConfigMapping;
import java.util.Optional;

/**
 * Where the edge dials idp, and the static client id and secret it introspects sessions and tokens
 * with.
 *
 * <p><b>Why its own prefix, and why not {@code qits.edge.sessions.client-id} any more</b>
 * (qits-163). The pair is read ONLY from the deployer's {@code QITS_RESOURCE_IDP_CLIENT_ID} /
 * {@code _SECRET} (qits-540); the bootstrap-era {@code QITS_EDGE_SESSIONS_CLIENT_ID} / {@code
 * _SECRET} are not read at all. But those older names are the ENVIRONMENT SPELLING of {@code
 * qits.edge.sessions.client-*}, and SmallRye's environment source (ordinal 300) outranks {@code
 * application.properties} (250) — under those keys the stale pair a container still carries would
 * be read straight past the expression, which is exactly how the edge's introspection broke the day
 * idp stopped accepting environment secrets. No variable anything injects spells {@code
 * qits.edge.idp.*}, so the expression in {@code application.properties} is what decides here.
 *
 * <p><b>No default, and that is deliberate.</b> A credential is a deployment fact. Absent while
 * {@link SessionsConfig#enabled()} is off is the ordinary state and costs nothing; absent while it
 * is ON fails at STARTUP — see {@link EdgeSessions}, because the alternative is an edge that
 * refuses every browser for a reason only a stack trace holds.
 */
@ConfigMapping(prefix = "qits.edge.idp")
public interface IdpConfig {

  /**
   * The address this process CONNECTS to: {@code QITS_RESOURCE_IDP_URL}, else {@code
   * http://<env>-qits-idp:8080/idp} derived from the environment — see {@link Idp}. It was {@code
   * qits.idp.dial-url}, whose environment spelling is {@code QITS_IDP_DIAL_URL}: the same shadowing
   * as the client pair below, and that variable is not read any more.
   */
  String dialUrl();

  /** The edge's own idp client id: the {@code idp:client} resource's, {@code <env>-qits-edge}. */
  Optional<String> clientId();

  /**
   * The secret half of {@link #clientId()}. Never logged, never cached, never sent anywhere but
   * idp's introspection endpoints.
   */
  Optional<String> clientSecret();
}
