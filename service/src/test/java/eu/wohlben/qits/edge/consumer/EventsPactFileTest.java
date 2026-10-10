package eu.wohlben.qits.edge.consumer;

import org.junit.jupiter.api.Test;

/**
 * <b>The committed {@code pacts/qits-edge-service_qits-events-service.json}</b> is what {@link
 * EventsContract} writes, and every interaction in it carries both references. Rows still waiting
 * on a provider state are left out until qits-events records it.
 */
class EventsPactFileTest {

  @Test
  void theCommittedPactIsWhatTheContractWrites() {
    PactFiles.compareOrWrite(EventsContract.PROVIDER.repository(), EventsContract.pact());
  }

  @Test
  void everyInteractionCarriesBothReferences() {
    PactFiles.assertReferences(
        EventsContract.PROVIDER.repository(), EventsContract.pact(), EventsContract.ready().size());
  }
}
