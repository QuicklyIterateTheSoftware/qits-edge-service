package eu.wohlben.qits.edge.contracts;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.core.model.ProviderState;
import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactSource;
import eu.wohlben.qits.edge.StubGateways;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URL;
import java.util.List;
import java.util.Map;
import kotlin.Pair;
import org.apache.hc.core5.http.HttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * <b>Verifies every consumer's pact against the running edge</b>, the way qits-projects-service and
 * qits-maintenance-service do.
 *
 * <p>The pacts come off the test classpath: each consumer publishes its pact as a jar holding
 * {@code pacts/<consumer>_qits-edge-service.json} (repository names on both sides), this repo pins
 * that jar as a test dependency, and qits-maintenance bumps the pin when the consumer releases a
 * changed pact. {@link ClasspathPactLoader} finds them all.
 *
 * <p><b>No consumer pins a pact yet</b>, so {@code @IgnoreNoPactsToVerify} lets an empty classpath
 * pass and the loader logs that nothing was verified. When the first consumer's pact jar is pinned,
 * drop the annotation and set {@link ClasspathPactLoader#REQUIRED} to true.
 *
 * <p><b>The edge routes on {@code Host}</b>, which a consumer's pact does not carry: the consumer
 * asks its own origin. So each request gets the {@code Host} its provider state names, and {@code
 * X-Forwarded-Proto: https}, before it is sent ({@link EdgeTarget}, {@link
 * ProviderStates#requestHeaders}).
 *
 * <p>Each interaction runs against this {@code @QuarkusTest} application over real HTTP,
 * unauthenticated: {@code /main-navigation} is open. Every {@code @State} method delegates to
 * {@link ProviderStates}; {@link #target} fails an unknown state, and an interaction without {@code
 * comments.references.qits-call} or {@code qits-trigger}.
 *
 * <p>It runs in the {@code contract-tests} surefire execution, a JVM of its own: see {@code
 * service/pom.xml}.
 */
@QuarkusTest
@WithTestResource(StubGateways.class)
@Provider(ConsumerPactVerificationTest.PROVIDER)
@PactSource(ClasspathPactLoader.class)
@IgnoreNoPactsToVerify
class ConsumerPactVerificationTest {

  /** The provider's name in a pact: the repository name, not the application name. */
  static final String PROVIDER = "qits-edge-service";

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  @Inject ProviderStates states;

  @TestHTTPResource("/")
  URL base;

  @BeforeEach
  void target(PactVerificationContext context, Pact pact, Interaction interaction) {
    if (context == null) {
      return; // no pact to verify: @IgnoreNoPactsToVerify's single empty run
    }
    String consumer = pact.getConsumer().getName();
    for (ProviderState state : interaction.getProviderStates()) {
      if (!states.names().contains(state.getName())) {
        fail(
            "Consumer '"
                + consumer
                + "' needs the provider state '"
                + state.getName()
                + "' (interaction '"
                + interaction.getDescription()
                + "'), which qits-edge does not answer for — it answers for "
                + states.names());
      }
    }
    var references = interaction.getComments().get("references");
    for (String key : List.of("qits-call", "qits-trigger")) {
      if (references == null || !references.isObject() || !references.asObject().has(key)) {
        fail(
            "Consumer '"
                + consumer
                + "' interaction '"
                + interaction.getDescription()
                + "' carries no comments.references."
                + key);
      }
    }
    context.setTarget(new EdgeTarget(base.getHost(), base.getPort()));
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void consumerPactHolds(PactVerificationContext context) {
    if (context != null) {
      context.verifyInteraction();
    }
  }

  /**
   * The test port, with each request given the {@code Host} its provider state names. Read from the
   * interaction, never from a field a {@code @State} method set: pact-jvm calls that method on
   * JUnit's instance, and Quarkus runs this class's methods on its own.
   */
  static final class EdgeTarget extends HttpTestTarget {

    EdgeTarget(String host, int port) {
      super(host, port);
    }

    @Override
    public Pair<Object, Object> prepareRequest(
        Pact pact, Interaction interaction, Map<String, Object> context) {
      Pair<Object, Object> prepared = super.prepareRequest(pact, interaction, context);
      List<ProviderState> named = interaction.getProviderStates();
      if (named.isEmpty()) {
        fail(
            "Interaction '"
                + interaction.getDescription()
                + "' names no provider state, so nothing says which host to ask the edge on");
      }
      HttpRequest request = (HttpRequest) prepared.getFirst();
      ProviderStates.requestHeaders(named.get(0).getName()).forEach(request::setHeader);
      return prepared;
    }
  }

  // --- the states: each one line into the registry -------------------------------------------

  @State(ProviderStates.A_PUBLISHED_NAVIGATION)
  Map<String, String> aPublishedNavigation() {
    return states.params(ProviderStates.A_PUBLISHED_NAVIGATION);
  }
}
