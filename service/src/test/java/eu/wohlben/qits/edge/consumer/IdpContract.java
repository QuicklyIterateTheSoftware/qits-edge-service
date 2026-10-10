package eu.wohlben.qits.edge.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.Matchers;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.edge.IdpProbe;
import eu.wohlben.qits.edge.consumer.GoldenMasters.Provider;
import eu.wohlben.qits.edge.consumer.GoldenMasters.Request;
import eu.wohlben.qits.edge.consumer.GoldenMasters.Trigger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * <b>What the edge asks qits-idp, and why</b> (ticket qits-1149). Every call goes to {@code
 * qits.edge.idp.dial-url} ({@code .../idp}), from the edge's own small Vert.x clients:
 *
 * <ul>
 *   <li>{@code GET /idp/jwks} ({@code IdpKeys}) — a token names a key the edge has not cached;
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
 *
 * <p><b>qits-idp-service records no golden masters yet</b> and states no operationIds; the ids
 * below are the ones this pact proposes. Every row is therefore skipped with the provider state it
 * needs, and no pact file is committed for qits-idp until the recordings are published and pinned.
 */
final class IdpContract {

  static final Provider PROVIDER = new Provider("qits-idp-service", "qits-idp");

  static final String GET_JWKS = "getJwks";
  static final String TOKEN = "token";
  static final String INTROSPECT_SESSION = "introspectSession";
  static final String INTROSPECT_TOKEN = "introspectToken";

  static final String A_PUBLISHED_SIGNING_KEY = "a published signing key";
  static final String A_CONFIDENTIAL_CLIENT = "a confidential client";
  static final String A_LIVE_BROWSER_SESSION = "a live browser session";
  static final String A_PERSONAL_TOKEN = "a personal token";

  /** How the edge's own client authenticates: its static id and secret, never a JWT. */
  private static final Object EDGE_BASIC =
      Matchers.regexp(
          "^Basic [A-Za-z0-9+/=]+$",
          "Basic "
              + java.util.Base64.getEncoder()
                  .encodeToString(
                      (IdpProbe.CLIENT_ID + ":" + IdpProbe.CLIENT_SECRET)
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8)));

  /**
   * One (trigger, call). {@code pending} is the skip reason while the provider state is missing.
   */
  record Case(
      Trigger trigger,
      String state,
      String operationId,
      Supplier<Request> request,
      Set<String> consumes,
      Call call) {

    String description() {
      return GoldenMasters.description(operationId, trigger);
    }

    String pending() {
      return "needs provider state '"
          + state
          + "' for "
          + operationId
          + " in qits-idp-service (it publishes no golden masters yet)";
    }
  }

  /** What the edge does with the client for one row, asserting what that code path reads. */
  @FunctionalInterface
  interface Call {
    void run(IdpProbe probe, JsonNode recorded, Map<String, String> params) throws Exception;
  }

  private static Request jwks() {
    return Request.get("/idp/jwks");
  }

  /** The caller's own Basic header, relayed verbatim; the state names the client it is. */
  private static Request grant() {
    Map<String, Object> headers = new LinkedHashMap<>();
    headers.put("Content-Type", "application/x-www-form-urlencoded");
    headers.put(
        "Authorization",
        Matchers.fromProviderState("${clientAuthorization}", "Basic Y2xpZW50OnNlY3JldA=="));
    return new Request("POST", "/idp/token", Map.of(), headers, "grant_type=client_credentials");
  }

  private static Request introspect(String path, String param, String example) {
    Map<String, Object> headers = new LinkedHashMap<>();
    headers.put("Content-Type", "application/json");
    headers.put("Authorization", EDGE_BASIC);
    DslPart body =
        new PactDslJsonBody().valueFromProviderState("token", "${" + param + "}", example);
    return new Request("POST", path, Map.of(), headers, body);
  }

  private static final Call READS_THE_KEY =
      (probe, recorded, params) ->
          assertNotNull(probe.key(recorded.path("keys").get(0).path("kid").asText()));

  private static final Call READS_THE_GRANT =
      (probe, recorded, params) -> {
        var grant = probe.grant(params.get("clientAuthorization"));
        assertEquals(200, grant.status());
        JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(grant.body());
        assertEquals(recorded.path("access_token").asText(), body.path("access_token").asText());
      };

  private static final Call READS_THE_SESSION =
      (probe, recorded, params) -> {
        var answer = probe.introspectSession(params.get("sessionToken"));
        assertEquals(200, answer.status());
        var session = IdpProbe.session(answer.body());
        assertNotNull(session, "the edge accepts the answer as a session");
        assertEquals(recorded.path("userId").asText(), session.userId());
        assertEquals(recorded.path("username").asText(), session.username());
      };

  private static final Call READS_THE_TOKEN =
      (probe, recorded, params) -> {
        var answer = probe.introspectToken(params.get("personalToken"));
        assertEquals(200, answer.status());
        JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(answer.body());
        assertEquals(recorded.path("accessToken").asText(), body.path("accessToken").asText());
      };

  private static final Set<String> JWKS_READS =
      Set.of("$.keys[*].kid", "$.keys[*].kty", "$.keys[*].n", "$.keys[*].e");

  static final List<Case> CASES =
      List.of(
          new Case(
              Trigger.operation("EdgeAuth.checkCredential"),
              A_PUBLISHED_SIGNING_KEY,
              GET_JWKS,
              IdpContract::jwks,
              JWKS_READS,
              READS_THE_KEY),
          new Case(
              Trigger.operation("EdgeAuth.checkBasic"),
              A_CONFIDENTIAL_CLIENT,
              TOKEN,
              IdpContract::grant,
              Set.of("$.access_token"),
              READS_THE_GRANT),
          new Case(
              Trigger.operation("EdgeAuth.token"),
              A_CONFIDENTIAL_CLIENT,
              TOKEN,
              IdpContract::grant,
              Set.of("$.access_token", "$.expires_in"),
              READS_THE_GRANT),
          new Case(
              Trigger.operation("EdgeSessions.introspect"),
              A_LIVE_BROWSER_SESSION,
              INTROSPECT_SESSION,
              () -> introspect("/idp/api/sessions/introspect", "sessionToken", "a-session-token"),
              Set.of("$.userId", "$.username", "$.roles", "$.expiresAt"),
              READS_THE_SESSION),
          new Case(
              Trigger.operation("EdgeAuth.checkToken"),
              A_PERSONAL_TOKEN,
              INTROSPECT_TOKEN,
              () -> introspect("/idp/api/tokens/introspect", "personalToken", "qits_tok_example"),
              Set.of("$.accessToken", "$.expiresIn"),
              READS_THE_TOKEN),
          new Case(
              Trigger.operation("EdgeAuth.token"),
              A_PERSONAL_TOKEN,
              INTROSPECT_TOKEN,
              () -> introspect("/idp/api/tokens/introspect", "personalToken", "qits_tok_example"),
              Set.of("$.accessToken", "$.expiresIn"),
              READS_THE_TOKEN));

  private IdpContract() {}

  static V4Pact pact(List<Case> cases) {
    PactBuilder builder =
        new PactBuilder(GoldenMasters.CONSUMER, PROVIDER.repository(), PactSpecVersion.V4);
    for (Case c : cases) {
      GoldenMasters.interaction(
          builder,
          PROVIDER,
          c.state(),
          c.operationId(),
          c.trigger(),
          c.request().get(),
          UnaryOperator.identity(),
          c.consumes());
    }
    return builder.toPact();
  }
}
