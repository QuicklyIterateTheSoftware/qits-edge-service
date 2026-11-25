package eu.wohlben.qits.edge.acme;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HetznerDnsChallengeProviderTest {

  private HttpServer server;

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
  }

  @Test
  void createsAMissingTxtRrsetWithAQuotedValue() throws Exception {
    List<Request> requests = new ArrayList<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          requests.add(
              new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(), body));
          byte[] response =
              (exchange.getRequestMethod().equals("GET") ? "{\"rrsets\":[]}" : "{\"action\":{}}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    URI api = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
    var provider =
        new HetznerDnsChallengeProvider(
            HttpClient.newHttpClient(),
            new ObjectMapper(),
            api,
            () -> "not-a-real-token",
            "wohlben.eu");

    provider.present("_acme-challenge.dev.wohlben.eu.", "digest");

    assertThat(requests).hasSize(2);
    assertThat(requests.get(0).path()).contains("name=_acme-challenge.dev");
    assertThat(requests.get(1).method()).isEqualTo("POST");
    assertThat(requests.get(1).path()).isEqualTo("/v1/zones/wohlben.eu/rrsets");
    assertThat(requests.get(1).body())
        .contains("\"name\":\"_acme-challenge.dev\"")
        .contains("\\\"digest\\\"");
  }

  @Test
  void throughTheGatewayTheChallengeCarriesTheQitsBearerUnderTheHetznerRoute() throws Exception {
    List<Request> requests = new ArrayList<>();
    List<String> authorizations = new ArrayList<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          requests.add(
              new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(), body));
          authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] response =
              (exchange.getRequestMethod().equals("GET") ? "{\"rrsets\":[]}" : "{\"action\":{}}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    URI gateway = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hetzner/v1/");
    var provider =
        new HetznerDnsChallengeProvider(
            HttpClient.newHttpClient(),
            new ObjectMapper(),
            gateway,
            () -> "qits-jwt",
            "wohlben.eu");

    provider.present("_acme-challenge.dev.wohlben.eu.", "digest");

    assertThat(requests)
        .extracting(Request::path)
        .allMatch(path -> path.startsWith("/hetzner/v1/"));
    assertThat(requests.get(0).path()).startsWith("/hetzner/v1/zones/wohlben.eu/rrsets?type=TXT");
    assertThat(requests.get(1).path()).isEqualTo("/hetzner/v1/zones/wohlben.eu/rrsets");
    assertThat(authorizations).containsOnly("Bearer qits-jwt");
  }

  @Test
  void aGatewayRefusalCarriesItsStatusAndBody() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] response = "{\"error\":\"no grant\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(403, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    URI gateway = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hetzner/v1/");
    var provider =
        new HetznerDnsChallengeProvider(
            HttpClient.newHttpClient(),
            new ObjectMapper(),
            gateway,
            () -> "qits-jwt",
            "wohlben.eu");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> provider.present("_acme-challenge.dev.wohlben.eu.", "digest"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HTTP 403: {\"error\":\"no grant\"}")
        .hasMessageNotContaining("qits-jwt");
  }

  private record Request(String method, String path, String body) {}
}
