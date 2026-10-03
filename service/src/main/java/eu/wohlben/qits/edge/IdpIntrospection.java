package eu.wohlben.qits.edge;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The one dial this process makes at idp's introspection doors — {@code /api/sessions/introspect}
 * for a browser's cookie and {@code /api/tokens/introspect} for an opaque token — and the
 * credential it dials with.
 *
 * <p><b>One class because it is one dial.</b> Both doors take the same request: a POST of {@code
 * {"token": …}}, the edge's OWN static client id and secret in HTTP Basic, and a status that is the
 * whole verdict. Both deserve the same patience, because idp is redeployed like any other container
 * and a cookie and a token are equally unanswerable while it is. Written twice, the two would drift
 * the first time one of them learnt a lesson — a timeout, a header — and the other did not; so
 * {@link EdgeSessions} and {@link EdgeAuth} both come here, and only the endpoint differs.
 *
 * <p><b>The credential is the edge's, and it lives in {@link IdpConfig}</b> — once under the
 * sessions keys, because sessions were its first use (qits-163 moved it). It is what makes
 * introspection a privilege rather than an oracle: without it, anything on the network could ask
 * idp about any cookie or any token. Absent is an ordinary state — the bootstrap seeds it, a clone
 * has none — and {@link #hasCredential()} says so, so each caller decides what absence means for
 * it: the session gate refuses to start, the token path refuses every token.
 *
 * <p><b>The patience is {@link IdpGrants}' own</b>: each attempt bounded by {@link
 * AuthConfig#idpCallTimeoutMs()}, connection included, and connection-classed failures retried with
 * the same doubling backoff until {@link AuthConfig#idpRetryWindowMs()} runs out. An ANSWER is
 * never retried: idp saying no is idp deciding, and asking again would turn one refusal into a
 * burst.
 */
@ApplicationScoped
public class IdpIntrospection {

  /** What idp answered, whatever its status — an unreachable idp is a failed future instead. */
  public record Answer(int status, String body) {}

  @Inject Vertx vertx;

  @Inject AuthConfig authConfig;

  @Inject IdpConfig idpConfig;

  private HttpClient client;

  /** {@code Basic <id>:<secret>} for the edge's own idp client, built once; null when unset. */
  private String authorization;

  @PostConstruct
  void open() {
    // Its own client, like IdpGrants': the proxy's is tuned for 64 concurrent layer pushes with no
    // idle timeout, which is the opposite of a small JSON POST that must fail fast and be retried.
    client = vertx.createHttpClient();
    authorization =
        basicAuthorization(
            idpConfig.clientId().orElse(null), idpConfig.clientSecret().orElse(null));
  }

  /**
   * {@code Basic base64(<id>:<secret>)}, or null when either half is missing or blank — a half
   * credential is no credential, and dialling with one would only earn a 401 per request.
   *
   * <p>Package-private and static so the header can be asserted without booting anything.
   */
  static String basicAuthorization(String clientId, String clientSecret) {
    if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
      return null;
    }
    return "Basic "
        + Base64.getEncoder()
            .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Whether the edge holds a client of its own to introspect with. Without one, nothing is sent.
   */
  public boolean hasCredential() {
    return authorization != null;
  }

  /**
   * Ask one introspection door about one value, waiting out an identity provider that is on its way
   * back.
   *
   * @param endpoint the door — {@link Idp#introspectionEndpoint()} or {@link
   *     Idp#tokenIntrospectionEndpoint()}
   * @param value the cookie or the token, sent as the body's {@code token} and nowhere else
   * @return idp's answer, whatever its status; a FAILED future once the window has run out, or at
   *     once when {@link #hasCredential()} is false — the callers check that first
   */
  public Future<Answer> introspect(String endpoint, String value) {
    if (authorization == null) {
      return Future.failedFuture(
          new IllegalStateException("the edge holds no idp client to introspect with"));
    }
    return attempt(endpoint, value, System.currentTimeMillis() + authConfig.idpRetryWindowMs(), 0);
  }

  private Future<Answer> attempt(String endpoint, String value, long deadlineMillis, int made) {
    return post(endpoint, value)
        .recover(
            failure -> {
              long backoff = IdpGrants.backoffMs(made);
              if (!IdpGrants.connectionClassed(failure)
                  || System.currentTimeMillis() + backoff >= deadlineMillis) {
                return Future.failedFuture(failure);
              }
              Promise<Answer> next = Promise.promise();
              vertx.setTimer(
                  backoff,
                  id -> attempt(endpoint, value, deadlineMillis, made + 1).onComplete(next));
              return next.future();
            });
  }

  private Future<Answer> post(String endpoint, String value) {
    RequestOptions options =
        new RequestOptions()
            .setMethod(HttpMethod.POST)
            .setAbsoluteURI(endpoint)
            // Both halves, the same as every other dial at idp: the connect timeout bounds a
            // dropped SYN — a swarm VIP exists before any task behind it does — and the request
            // timeout bounds the worse case, a connection accepted and never answered.
            .setConnectTimeout(authConfig.idpCallTimeoutMs())
            .setTimeout(authConfig.idpCallTimeoutMs());
    return client
        .request(options)
        .compose(
            request -> {
              // The edge's own client id and secret, which is what makes introspection a privilege
              // rather than an oracle anyone on the network could ask about any value.
              request.putHeader(HttpHeaders.AUTHORIZATION, authorization);
              request.putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
              request.putHeader(HttpHeaders.ACCEPT, "application/json");
              return request.send(new JsonObject().put("token", value).encode());
            })
        .compose(
            response ->
                response.body().map(body -> new Answer(response.statusCode(), body.toString())));
  }
}
