package eu.wohlben.qits.edge.consumer;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import eu.wohlben.qits.edge.IdpProbe;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>The consumer half of the qits-idp contract</b> (ticket qits-1149): the edge's real idp clients
 * ({@link IdpProbe}) against a pact mock server per row. Every row is skipped until
 * qits-idp-service publishes golden masters for the state it names; then pin {@code
 * eu.wohlben.qits:qits-idp-golden-masters}, drop the skip, and add an {@code IdpPactFileTest} like
 * {@link EventsPactFileTest}.
 */
class IdpConsumerPactTest {

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  /** Flip to true once qits-idp's golden masters are pinned. */
  static final boolean PROVIDER_RECORDED = false;

  @TestFactory
  Stream<DynamicTest> everyRowIsWhatTheEdgeAsksAndUnderstands() {
    return IdpContract.CASES.stream()
        .map(
            row ->
                DynamicTest.dynamicTest(
                    row.description() + " [" + row.state() + "]",
                    () -> {
                      Assumptions.assumeTrue(PROVIDER_RECORDED, row.pending());
                      PactVerificationResult result =
                          ConsumerPactRunnerKt.runConsumerTest(
                              IdpContract.pact(List.of(row)),
                              MockProviderConfig.createDefault(PactSpecVersion.V4),
                              (mockServer, context) -> {
                                try (IdpProbe probe = new IdpProbe(mockServer.getUrl() + "/idp")) {
                                  row.call()
                                      .run(
                                          probe,
                                          GoldenMasters.json(
                                              IdpContract.PROVIDER, row.state(), row.operationId()),
                                          GoldenMasters.params(IdpContract.PROVIDER, row.state()));
                                }
                                return null;
                              });
                      if (!(result instanceof PactVerificationResult.Ok)) {
                        fail(EventsConsumerPactTest.describe(result));
                      }
                    }));
  }
}
