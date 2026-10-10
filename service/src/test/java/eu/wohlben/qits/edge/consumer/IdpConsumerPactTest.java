package eu.wohlben.qits.edge.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.DefaultPactWriter;
import au.com.dius.pact.core.model.PactSpecVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.edge.IdpProbe;
import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenFiles;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer half of the qits-idp contract</b> (ticket qits-1149): the edge's real idp clients
 * ({@link IdpProbe}) against a pact mock server per row, and the committed {@code
 * pacts/qits-edge-service_qits-idp-service.json}.
 */
class IdpConsumerPactTest {

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The domain qits-idp recorded under: its issuer is {@code https://idp.qits.localhost}. */
  private static final String DOMAIN = "localhost";

  @Test
  void discoveryNamesTheKeySet() {
    run(
        IdpContract.DISCOVERY,
        (probe, recorded, params) ->
            assertEquals(recorded.path("jwks_uri").asText(), probe.discoverJwksUri()));
  }

  @Test
  void theKeySetHoldsTheSigningKey() {
    run(
        IdpContract.JWKS,
        (probe, recorded, params) ->
            assertNotNull(probe.key(probe.dial() + "/jwks", params.get("kid"))));
  }

  @Test
  void aBasicCredentialOnAVhostIsExchanged() {
    run(IdpContract.GRANT_FOR_BASIC, IdpConsumerPactTest::readsTheGrant);
  }

  @Test
  void theRegistryRealmExchangesABasicCredential() {
    run(IdpContract.GRANT_FOR_REALM, IdpConsumerPactTest::readsTheGrant);
  }

  @Test
  void aBrowserSessionIsIntrospected() {
    run(
        IdpContract.SESSION,
        (probe, recorded, params) -> {
          var answer = probe.introspectSession(params.get("sessionToken"));
          assertEquals(200, answer.status());
          var session = IdpProbe.session(answer.body());
          assertNotNull(session, "the edge accepts the answer as a session");
          assertEquals(recorded.path("userId").asText(), session.userId());
          assertEquals(recorded.path("username").asText(), session.username());
        });
  }

  @Test
  void anOpaqueTokenOnAVhostIsIntrospected() {
    run(IdpContract.TOKEN_FOR_VHOST, IdpConsumerPactTest::readsTheToken);
  }

  @Test
  void theRegistryRealmIntrospectsAnOpaqueToken() {
    run(IdpContract.TOKEN_FOR_REALM, IdpConsumerPactTest::readsTheToken);
  }

  @Test
  void theCommittedPactIsWhatTheRowsWrite() throws Exception {
    IdpContract.PACT.assertEveryInteractionCarriesBothReferences();
    StringWriter out = new StringWriter();
    try (PrintWriter writer = new PrintWriter(out)) {
      DefaultPactWriter.INSTANCE.writePact(
          IdpContract.pact(IdpContract.PACT.recorded()), writer, PactSpecVersion.V4);
    }
    GoldenFiles.compareOrWrite(
        ConsumerPact.pactsDirectory().resolve(IdpContract.PACT.file()),
        ConsumerPact.normalise(out.toString()));
  }

  private static void readsTheGrant(IdpProbe probe, JsonNode recorded, Map<String, String> params)
      throws Exception {
    var grant = probe.grant(params.get("authorization"));
    assertEquals(200, grant.status());
    JsonNode body = MAPPER.readTree(grant.body());
    assertEquals(recorded.path("access_token").asText(), body.path("access_token").asText());
  }

  private static void readsTheToken(IdpProbe probe, JsonNode recorded, Map<String, String> params)
      throws Exception {
    var answer = probe.introspectToken(params.get("token"));
    assertEquals(200, answer.status());
    JsonNode body = MAPPER.readTree(answer.body());
    assertEquals(recorded.path("accessToken").asText(), body.path("accessToken").asText());
  }

  @FunctionalInterface
  private interface Call {
    void run(IdpProbe probe, JsonNode recorded, Map<String, String> params) throws Exception;
  }

  /**
   * One row against a mock server that answers it and nothing else. The edge's own client is the
   * state's caller ({@code authorization}), so the request carries the credential the provider
   * seeds.
   */
  private static void run(GoldenInteraction row, Call call) {
    Map<String, String> params = IdpContract.IDP.params(row.state());
    JsonNode recorded = IdpContract.IDP.json(row.state(), row.operationId());
    PactVerificationResult result =
        ConsumerPactRunnerKt.runConsumerTest(
            IdpContract.pact(List.of(row)),
            MockProviderConfig.createDefault(PactSpecVersion.V4),
            (mockServer, context) -> {
              try (IdpProbe probe =
                  new IdpProbe(mockServer.getUrl() + "/idp", DOMAIN, params.get("authorization"))) {
                call.run(probe, recorded, params);
              }
              return null;
            });
    if (result instanceof PactVerificationResult.Error error
        && error.getError() instanceof AssertionError assertion) {
      throw assertion;
    }
    if (!(result instanceof PactVerificationResult.Ok)) {
      fail(row.description() + ": " + result);
    }
  }
}
