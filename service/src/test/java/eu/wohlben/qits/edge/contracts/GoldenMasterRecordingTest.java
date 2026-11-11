package eu.wohlben.qits.edge.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import eu.wohlben.qits.edge.StubGateways;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>Records qits-edge's provider golden masters</b> — {@code golden-masters/} at the repository
 * root, the source of the published golden-master packages consumers write their pacts against.
 *
 * <p>For each (state, operation) pair in {@link #INTERACTIONS} it runs the state ({@link
 * ProviderStates}), asks the edge on the state's host and renders {@code
 * golden-masters/<state-slug>/<operationId>.json}; then it renders {@code
 * golden-masters/index.json} in the format qits-projects-service set (format version 1).
 *
 * <p><b>Only per-run strings are frozen.</b> The edge's answers here carry no ids or instants: the
 * state fixes every name. What can differ per run is an address a test stub was given — {@code
 * getUpstreamPools} names the registry stub's ephemeral {@code 127.0.0.1:<port>} — so a state lists
 * such strings with the stable value to record instead ({@link ProviderStates#frozenStrings}).
 * Every string value holding one is rewritten, and its JSONPath ({@code $.a.b}, array elements as
 * {@code [*]}, in order of first appearance) goes into the index's {@code frozen.strings}, the
 * format qits-projects-service set: a consumer must not match that value literally. {@code
 * frozen.ids} and {@code frozen.instants} stay empty.
 *
 * <p>It <b>compares by default</b> and fails with a unified diff per differing file — including a
 * committed {@code .json} no interaction produces any more. {@code -Dgolden.update=true} (or {@code
 * QITS_GOLDEN_UPDATE=true}) rewrites instead, and deletes such stale files; see {@link
 * GoldenFiles}.
 */
@QuarkusTest
@WithTestResource(StubGateways.class)
class GoldenMasterRecordingTest {

  static final int FORMAT_VERSION = 1;

  /** The application name, as every provider's index names itself. */
  static final String PROVIDER = "qits-edge";

  /**
   * One recorded interaction.
   *
   * @param sortedKeys objects ({@code $.a.b} paths) the edge builds from a hash map, so their keys
   *     come in no guaranteed order: they are sorted before rendering. A consumer reads them by key
   *     and must not depend on the order.
   */
  record Interaction(
      String state,
      String operationId,
      String method,
      String path,
      int status,
      List<String> sortedKeys) {}

  /**
   * Each operationId is the one {@code META-INF/openapi.yaml} declares (committed as {@code
   * docs/openapi.yml}), which is what the platform's coverage check matches these entries against;
   * renaming one is a contract change on both sides.
   */
  static final List<Interaction> INTERACTIONS =
      List.of(
          new Interaction(
              ProviderStates.A_PUBLISHED_NAVIGATION,
              "getMainNavigation",
              "GET",
              "/main-navigation",
              200,
              List.of("$.applications")),
          new Interaction(
              ProviderStates.A_SIGNED_IN_CALLER_AND_A_PRIVATE_APP,
              "getMainNavigation",
              "GET",
              "/main-navigation",
              200,
              List.of("$.applications")),
          new Interaction(
              ProviderStates.AN_OPEN_UPSTREAM_POOL,
              "getUpstreamPools",
              "GET",
              "/upstream-pools",
              200,
              List.of()));

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject ProviderStates states;

  @TestHTTPResource("/")
  URL base;

  @Test
  void goldenMastersMatchTheProvider() throws Exception {
    Path dir = GoldenFiles.repositoryRoot().resolve("golden-masters");
    boolean update = GoldenFiles.updating();
    List<String> failures = new ArrayList<>();
    Set<String> written = new TreeSet<>();

    Map<String, ObjectNode> indexStates = new TreeMap<>();
    Map<String, Map<String, ObjectNode>> indexOperations = new TreeMap<>();

    for (Interaction interaction : INTERACTIONS) {
      Map<String, String> params = states.params(interaction.state());
      JsonNode body = sortKeys(call(interaction, params), interaction.sortedKeys());
      Set<String> frozenStrings = new LinkedHashSet<>();
      body = freeze(body, "$", states.frozenStrings(interaction.state()), frozenStrings);
      String slug = ProviderStates.slug(interaction.state());
      String file = slug + "/" + interaction.operationId() + ".json";

      ObjectNode frozenParams = JsonNodeFactory.instance.objectNode();
      params.forEach(frozenParams::put);
      ObjectNode state = indexStates.get(slug);
      if (state == null) {
        state = JsonNodeFactory.instance.objectNode();
        state.put("name", interaction.state());
        state.put("slug", slug);
        state.set("params", frozenParams);
        state.set("dependsOn", JsonNodeFactory.instance.arrayNode());
        indexStates.put(slug, state);
      } else if (!state.get("params").equals(frozenParams)) {
        failures.add("State '" + interaction.state() + "' gave different params per operation");
      }

      ObjectNode operation = JsonNodeFactory.instance.objectNode();
      operation.put("operationId", interaction.operationId());
      operation.put("method", interaction.method());
      operation.put("path", interaction.path());
      operation.put("status", interaction.status());
      operation.put("file", file);
      ObjectNode frozen = operation.putObject("frozen");
      frozen.putArray("ids");
      frozen.putArray("instants");
      ArrayNode strings = frozen.putArray("strings");
      frozenStrings.forEach(strings::add);
      frozen.putNull("listFilteredTo");
      if (indexOperations
              .computeIfAbsent(slug, k -> new TreeMap<>())
              .put(interaction.operationId(), operation)
          != null) {
        failures.add("Duplicate interaction " + file);
      }

      written.add(file);
      check(dir.resolve(file), GoldenJson.render(body), update, failures);
    }

    ObjectNode index = JsonNodeFactory.instance.objectNode();
    index.put("formatVersion", FORMAT_VERSION);
    index.put("provider", PROVIDER);
    ArrayNode stateArray = index.putArray("states");
    indexStates.forEach(
        (slug, state) -> {
          ArrayNode operations = state.putArray("operations");
          indexOperations.get(slug).values().forEach(operations::add);
          stateArray.add(state);
        });
    written.add("index.json");
    check(dir.resolve("index.json"), GoldenJson.render(index), update, failures);

    for (String stale : committedJson(dir)) {
      if (written.contains(stale)) {
        continue;
      }
      if (update) {
        Files.delete(dir.resolve(stale));
      } else {
        failures.add(
            dir.resolve(stale)
                + " is committed but no interaction records it any more — rerun with"
                + " -Dgolden.update=true to delete it.");
      }
    }

    if (!failures.isEmpty()) {
      throw new AssertionError(String.join("\n\n", failures));
    }
  }

  /**
   * The edge's answer on the state's host. The JDK client, because it can send {@code Host}: the
   * surefire argLine allows that header, and the edge routes on nothing else.
   */
  private JsonNode call(Interaction interaction, Map<String, String> params) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base.toString()).resolve(interaction.path()))
            .method(interaction.method(), HttpRequest.BodyPublishers.noBody());
    ProviderStates.requestHeaders(interaction.state()).forEach(request::header);
    HttpResponse<String> response;
    try (HttpClient client = HttpClient.newHttpClient()) {
      response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    if (response.statusCode() != interaction.status()) {
      throw new AssertionError(
          interaction.method()
              + " "
              + interaction.path()
              + " in state '"
              + interaction.state()
              + "' answered "
              + response.statusCode()
              + ", expected "
              + interaction.status()
              + ": "
              + response.body());
    }
    return JSON.readTree(response.body());
  }

  /** The body with the objects at {@code paths} rebuilt in key order. */
  private static JsonNode sortKeys(JsonNode body, List<String> paths) {
    for (String path : paths) {
      if (!path.startsWith("$.")) {
        throw new IllegalArgumentException("Only $.a.b paths are supported: " + path);
      }
      JsonNode node = body;
      for (String segment : path.substring(2).split("\\.")) {
        node = node.path(segment);
      }
      if (!node.isObject()) {
        throw new IllegalStateException(path + " is not an object in " + body);
      }
      ObjectNode object = (ObjectNode) node;
      Map<String, JsonNode> sorted = new TreeMap<>();
      object.properties().forEach(field -> sorted.put(field.getKey(), field.getValue()));
      object.removeAll();
      object.setAll(sorted);
    }
    return body;
  }

  /**
   * The body with every string value that holds one of {@code frozen}'s keys rewritten to hold its
   * value instead; each rewritten value's path is added to {@code paths}.
   */
  private static JsonNode freeze(
      JsonNode node, String path, Map<String, String> frozen, Set<String> paths) {
    if (frozen.isEmpty()) {
      return node;
    }
    if (node.isTextual()) {
      String text = node.asText();
      String rewritten = text;
      for (Map.Entry<String, String> entry : frozen.entrySet()) {
        rewritten = rewritten.replace(entry.getKey(), entry.getValue());
      }
      if (rewritten.equals(text)) {
        return node;
      }
      paths.add(path);
      return TextNode.valueOf(rewritten);
    }
    if (node.isArray()) {
      ArrayNode out = JsonNodeFactory.instance.arrayNode();
      for (JsonNode element : node) {
        out.add(freeze(element, path + "[*]", frozen, paths));
      }
      return out;
    }
    if (node.isObject()) {
      ObjectNode out = JsonNodeFactory.instance.objectNode();
      for (Map.Entry<String, JsonNode> field : node.properties()) {
        out.set(
            field.getKey(), freeze(field.getValue(), path + key(field.getKey()), frozen, paths));
      }
      return out;
    }
    return node;
  }

  private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  /** One key's path segment: {@code .key}, or {@code ['key']} when it is not a plain name. */
  private static String key(String key) {
    if (PLAIN_KEY.matcher(key).matches()) {
      return "." + key;
    }
    return "['" + key.replace("\\", "\\\\").replace("'", "\\'") + "']";
  }

  private static void check(Path golden, String actual, boolean update, List<String> failures) {
    String failure = GoldenFiles.check(golden, actual, update, UnaryOperator.identity());
    if (failure != null) {
      failures.add(failure);
    }
  }

  /** Every committed {@code .json} under the directory, relative and {@code /}-separated. */
  private static List<String> committedJson(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.walk(dir)) {
      return files
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .map(p -> dir.relativize(p).toString().replace('\\', '/'))
          .sorted()
          .toList();
    }
  }
}
