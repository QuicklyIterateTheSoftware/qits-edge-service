package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * THE ISSUER AND THE ADDRESS ARE TWO FACTS, and these are the assertions that keep them apart.
 *
 * <p>The issuer is DERIVED from the stated domain and never configured (qits-730); the address is
 * {@code qits.edge.idp.dial-url} and nothing else. Every test here holds the two on different hosts
 * and asserts which of them each answer is built from, so a change that re-merged them — an issuer
 * read from configuration, or a dial address falling back to an issuer — fails here rather than on
 * the estate, where every machine token would be refused at once (qits-162).
 */
class IdpTest {

  private static Idp idp(String dial) {
    Idp idp = new Idp();
    idp.configuredDial = dial;
    return idp;
  }

  @Test
  void theIssuerIsDerivedFromTheDomainWithNoPathAndNoTrailingSlash() {
    assertEquals("https://idp.qits.wohlben.eu", Idp.issuer("wohlben.eu"));
    assertEquals("https://idp.qits.localhost", Idp.issuer("localhost"));
    assertEquals(
        "https://idp.qits.wohlben.eu",
        Idp.issuer(" Wohlben.EU. "),
        "the domain is normalised the way every other name composed from it is");
  }

  @Test
  void onlyTheDerivedIssuerIsAccepted() {
    assertEquals(
        List.of("https://idp.qits.wohlben.eu"),
        Idp.issuers("wohlben.eu"),
        "qits-730 wave 3: the legacy issuer is no longer accepted");
  }

  @Test
  void theDialAddressIsNeverAnIssuer() {
    List<String> issuers = Idp.issuers("wohlben.eu");

    assertFalse(issuers.contains("http://dev-qits-idp:8080/idp"));
    assertFalse(issuers.contains("http://dev-qits-platform-idp:8080/idp"));
    assertFalse(issuers.contains("http://qits-platform-idp:8080/idp"));
  }

  @Test
  void everyEndpointIsBuiltFromTheDialAddress() {
    Idp idp = idp("http://dev-qits-platform-idp:8080/idp");

    assertEquals("http://dev-qits-platform-idp:8080/idp/jwks", idp.jwksUri());
    assertEquals("http://dev-qits-platform-idp:8080/idp/token", idp.tokenEndpoint());
    assertEquals(
        "http://dev-qits-platform-idp:8080/idp/api/sessions/introspect",
        idp.introspectionEndpoint());
    assertEquals(
        "http://dev-qits-platform-idp:8080/idp/api/tokens/introspect",
        idp.tokenIntrospectionEndpoint());
  }

  @Test
  void theDialAddressIsTrimmedOfTrailingSlashesBeforeAnythingIsComposedOntoIt() {
    assertEquals(
        "http://dev-qits-platform-idp:8080/idp/jwks",
        idp(" http://dev-qits-platform-idp:8080/idp// ").jwksUri(),
        "a doubled slash is exactly the 404 a key fetch fails on");
  }
}
