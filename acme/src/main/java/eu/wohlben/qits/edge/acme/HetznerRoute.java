package eu.wohlben.qits.edge.acme;

import java.net.URI;
import java.net.http.HttpClient;

/**
 * Where one Hetzner RRSet request goes, asked for on every request (qits-2760): the client, the API
 * base and the bearer. The deployed edge answers with qits-egress' TLS proxy listener and NO bearer
 * while it holds its workload certificate — the client certificate proves who it is — and with the
 * 8081 gateway and its {@code qits} token otherwise; the seed edge with api.hetzner.cloud and the
 * Hetzner token. This module does not know which one it holds; the service decides.
 */
@FunctionalInterface
public interface HetznerRoute {

  /**
   * One request's way out.
   *
   * @param http the client, its TLS context included
   * @param api the RRSet API's base, ending in {@code /v1/}
   * @param bearer the bearer to send, or null to send no {@code Authorization} at all
   */
  record Hop(HttpClient http, URI api, HetznerBearer bearer) {}

  /**
   * @throws Exception when no way out can be had now; the call fails and is retried like any other
   *     failed DNS call
   */
  Hop hop() throws Exception;

  /** The client of the 8081 gateway and of api.hetzner.cloud: the JVM's trust, no key. */
  static HttpClient plainClient() {
    return HetznerApi.defaultClient();
  }

  /**
   * The client of qits-egress' TLS proxy listener: {@code context} presents the edge's workload
   * certificate and trusts the platform CA. Made at run time, never held statically.
   */
  static HttpClient certificateClient(javax.net.ssl.SSLContext context) {
    return HetznerApi.client(context);
  }

  /** One way out, always. */
  static HetznerRoute fixed(HttpClient http, URI api, HetznerBearer bearer) {
    Hop hop = new Hop(http, api, bearer);
    return () -> hop;
  }
}
