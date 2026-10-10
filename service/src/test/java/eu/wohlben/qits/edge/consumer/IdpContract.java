package eu.wohlben.qits.edge.consumer;

import au.com.dius.pact.core.model.V4Interaction;
import au.com.dius.pact.core.model.V4Pact;
import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import java.util.List;

/**
 * <b>What the edge asks qits-idp, and why</b> (ticket qits-1149). Every call goes to {@code
 * qits.edge.idp.dial-url} ({@code .../idp}), from the edge's own small Vert.x clients:
 *
 * <ul>
 *   <li>{@code GET /idp/.well-known/openid-configuration}, then {@code GET <jwks_uri>} ({@code
 *       IdpKeys}) — the first token the edge checks, and every unknown kid after it;
 *   <li>{@code POST /idp/token} ({@code IdpGrants}), {@code client_credentials}, the caller's Basic
 *       header relayed — a Basic credential on a vhost ({@code EdgeAuth.checkBasic}) and the docker
 *       realm ({@code EdgeAuth.token});
 *   <li>{@code POST /idp/api/sessions/introspect} ({@code IdpIntrospection}) — a browser's {@code
 *       qits-session} cookie, with the edge's own client in Basic ({@code
 *       EdgeSessions.introspect});
 *   <li>{@code POST /idp/api/tokens/introspect} ({@code IdpIntrospection}) — an opaque {@code
 *       qits_tok_} token, the same credential ({@code EdgeAuth.checkToken}, and the realm).
 * </ul>
 *
 * <p>Every row reads a status first: anything but 200 is a refusal, and its body is not read.
 */
final class IdpContract {

  static final String CONSUMER = "qits-edge-service";

  static final GoldenMasters IDP = GoldenMasters.of("qits-idp-service", "qits-idp");

  static final String A_PUBLISHED_SIGNING_KEY = "a published signing key";
  static final String A_SERVICE_CLIENT = "a service client with the system role";
  static final String A_SIGNED_IN_PERSON = "a signed-in person";
  static final String A_COMMISSIONED_TOKEN = "a commissioned token";

  /** The edge fetches keys lazily: the first token it checks makes both calls. */
  private static final Trigger FIRST_AUTHENTICATED_REQUEST =
      Trigger.operation("the first authenticated request");

  static final GoldenInteraction DISCOVERY =
      GoldenInteraction.of(
              FIRST_AUTHENTICATED_REQUEST, A_PUBLISHED_SIGNING_KEY, "getOpenIdConfiguration")
          .consumes("issuer", "jwks_uri", "token_endpoint");

  static final GoldenInteraction JWKS =
      GoldenInteraction.of(FIRST_AUTHENTICATED_REQUEST, A_PUBLISHED_SIGNING_KEY, "getJwks")
          .consumes("keys[].kid", "keys[].kty", "keys[].n", "keys[].e", "keys[].alg", "keys[].use");

  static final GoldenInteraction GRANT_FOR_BASIC =
      GoldenInteraction.of(Trigger.operation("EdgeAuth.checkBasic"), A_SERVICE_CLIENT, "issueToken")
          .header("Authorization", "{authorization}")
          .consumes("access_token");

  static final GoldenInteraction GRANT_FOR_REALM =
      GoldenInteraction.of(Trigger.operation("EdgeAuth.token"), A_SERVICE_CLIENT, "issueToken")
          .header("Authorization", "{authorization}")
          .consumes("access_token", "expires_in");

  static final GoldenInteraction SESSION =
      GoldenInteraction.of(
              Trigger.operation("EdgeSessions.introspect"), A_SIGNED_IN_PERSON, "introspectSession")
          .header("Authorization", "{authorization}")
          .consumes("userId", "username", "roles[]", "expiresAt");

  static final GoldenInteraction TOKEN_FOR_VHOST =
      GoldenInteraction.of(
              Trigger.operation("EdgeAuth.checkToken"), A_COMMISSIONED_TOKEN, "introspectToken")
          .header("Authorization", "{authorization}")
          .consumes("accessToken", "expiresIn");

  static final GoldenInteraction TOKEN_FOR_REALM =
      GoldenInteraction.of(
              Trigger.operation("EdgeAuth.token"), A_COMMISSIONED_TOKEN, "introspectToken")
          .header("Authorization", "{authorization}")
          .consumes("accessToken", "expiresIn");

  static final ConsumerPact PACT =
      ConsumerPact.of(
          CONSUMER,
          IDP,
          DISCOVERY,
          JWKS,
          GRANT_FOR_BASIC,
          GRANT_FOR_REALM,
          SESSION,
          TOKEN_FOR_VHOST,
          TOKEN_FOR_REALM);

  private IdpContract() {}

  /**
   * The pact of {@code rows}, with discovery's {@code issuer} bound exactly as recorded: the edge
   * compares it with the issuer it derives, so a different value is a different contract. The
   * library matches every leaf by type, so that one rule is taken out here.
   */
  static V4Pact pact(List<GoldenInteraction> rows) {
    V4Pact pact = PACT.pact(rows);
    for (var interaction : pact.getInteractions()) {
      if (interaction instanceof V4Interaction.SynchronousHttp http
          && http.getDescription().equals(DISCOVERY.description())) {
        http.getResponse()
            .getMatchingRules()
            .rulesForCategory("body")
            .getMatchingRules()
            .remove("$.issuer");
      }
    }
    return pact;
  }
}
