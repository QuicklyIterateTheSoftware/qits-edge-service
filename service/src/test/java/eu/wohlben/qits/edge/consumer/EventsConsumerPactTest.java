package eu.wohlben.qits.edge.consumer;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>The consumer half of the qits-events contract</b> (ticket qits-1149): qits-eventstream's real
 * {@code EventsQuery}, pointed at a pact mock server that answers what {@link EventsContract}'s row
 * promises. One mock server per row, as in qits-maintenance-service: rows that send the same
 * request differ only by trigger. A row whose provider state is not recorded yet is skipped with
 * its reason.
 */
class EventsConsumerPactTest {

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  @TestFactory
  Stream<DynamicTest> everyRowIsWhatTheCatchUpAsksAndUnderstands() {
    return EventsContract.CASES.stream()
        .map(
            row ->
                DynamicTest.dynamicTest(
                    row.description() + " [" + row.state() + "]",
                    () -> {
                      Assumptions.assumeTrue(row.pending() == null, row.pending());
                      PactVerificationResult result =
                          ConsumerPactRunnerKt.runConsumerTest(
                              EventsContract.pact(List.of(row)),
                              MockProviderConfig.createDefault(PactSpecVersion.V4),
                              (mockServer, context) -> {
                                row.run(mockServer.getUrl());
                                return null;
                              });
                      if (!(result instanceof PactVerificationResult.Ok)) {
                        fail(describe(result));
                      }
                    }));
  }

  static String describe(PactVerificationResult result) {
    if (result instanceof PactVerificationResult.Error error) {
      return "error: " + error.getError();
    }
    return result.getDescription() + " — " + result;
  }
}
