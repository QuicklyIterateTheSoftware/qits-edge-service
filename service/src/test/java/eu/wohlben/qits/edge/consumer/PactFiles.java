package eu.wohlben.qits.edge.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.core.model.DefaultPactWriter;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.edge.contracts.GoldenFiles;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>The committed consumer pacts under {@code pacts/}</b>, written the way
 * qits-maintenance-service writes its own: pact-jvm's output, interactions sorted by description
 * then state, pact-jvm's version dropped from {@code metadata} (a library bump is not a contract
 * change), 2-space indent, one trailing newline. A difference fails with a diff; {@code
 * -Dgolden.update=true} (or {@code QITS_GOLDEN_UPDATE=true}) rewrites the file.
 */
final class PactFiles {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private PactFiles() {}

  /** {@code <consumer>_<provider>.json}, repository names on both sides. */
  static String fileName(String provider) {
    return GoldenMasters.CONSUMER + "_" + provider + ".json";
  }

  /** Compare the pact with the committed file, or rewrite it. */
  static void compareOrWrite(String provider, V4Pact pact) {
    String raw = written(pact);
    String file = fileName(provider);
    try {
      Path scratch = Path.of("target", "pacts", file);
      Files.createDirectories(scratch.getParent());
      Files.writeString(scratch, raw);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    GoldenFiles.compareOrWrite(
        GoldenFiles.repositoryRoot().resolve("pacts").resolve(file), normalise(raw));
  }

  /**
   * Both references on every interaction, as the platform reads them: {@code qits-call} naming the
   * provider operation, {@code qits-trigger} naming the kind and its key, every value a string.
   */
  static void assertReferences(String provider, V4Pact pact, int rows) {
    JsonNode tree = read(normalise(written(pact)));
    assertEquals(GoldenMasters.CONSUMER, tree.path("consumer").path("name").asText());
    assertEquals(provider, tree.path("provider").path("name").asText());
    assertEquals("4.0", tree.path("metadata").path("pactSpecification").path("version").asText());
    JsonNode interactions = tree.path("interactions");
    assertEquals(rows, interactions.size(), "one interaction per row");
    Map<String, String> keyOfKind = Map.of("operation", "operationId", "schedule", "schedule");
    Set<String> unique = new HashSet<>();
    for (JsonNode interaction : interactions) {
      String description = interaction.path("description").asText();
      JsonNode references = interaction.path("comments").path("references");
      JsonNode call = references.path("qits-call");
      assertStrings(description, call, "app", "operationId");
      assertEquals(provider, call.path("app").asText(), description);
      JsonNode trigger = references.path("qits-trigger");
      String kind = trigger.path("kind").asText();
      assertTrue(
          keyOfKind.containsKey(kind), description + ": unknown trigger kind '" + kind + "'");
      assertStrings(description, trigger, "kind", "app", keyOfKind.get(kind));
      assertEquals(GoldenMasters.CONSUMER, trigger.path("app").asText(), description);
      assertTrue(
          description.startsWith(trigger.path(keyOfKind.get(kind)).asText() + ": "),
          description + ": the description leads with the trigger");
      String state = interaction.path("providerStates").path(0).path("name").asText();
      assertTrue(unique.add(description + "\u0000" + state), "(description, state) repeats");
    }
  }

  private static void assertStrings(String description, JsonNode group, String... keys) {
    assertTrue(group.isObject(), description + ": reference group missing");
    assertEquals(keys.length, group.size(), description + ": " + group + " holds other keys");
    for (String key : keys) {
      JsonNode value = group.path(key);
      assertTrue(
          value.isTextual() && !value.asText().isBlank(),
          description + ": " + key + " must be a non-blank string, got " + value);
    }
  }

  static String written(V4Pact pact) {
    StringWriter out = new StringWriter();
    try (PrintWriter writer = new PrintWriter(out)) {
      DefaultPactWriter.INSTANCE.writePact(pact, writer, PactSpecVersion.V4);
    }
    return out.toString();
  }

  /** Sorted, version-stripped, 2-space indented, one trailing newline. */
  static String normalise(String raw) {
    ObjectNode pact = (ObjectNode) read(raw);
    if (pact.path("metadata") instanceof ObjectNode meta) {
      meta.remove("pact-jvm");
    }
    if (pact.path("interactions") instanceof ArrayNode interactions) {
      List<JsonNode> sorted = new ArrayList<>();
      interactions.forEach(sorted::add);
      sorted.sort(
          Comparator.comparing((JsonNode i) -> i.path("description").asText())
              .thenComparing(i -> i.path("providerStates").path(0).path("name").asText()));
      interactions.removeAll();
      sorted.forEach(interactions::add);
    }
    StringBuilder out = new StringBuilder();
    print(pact, "", out);
    return out.append('\n').toString();
  }

  private static JsonNode read(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * {@code JSON.stringify(value, null, 2)}: no space before a colon, empty containers as {} / [].
   */
  private static void print(JsonNode node, String indent, StringBuilder out) {
    String inner = indent + "  ";
    try {
      if (node.isObject()) {
        if (node.isEmpty()) {
          out.append("{}");
          return;
        }
        out.append("{\n");
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
          Map.Entry<String, JsonNode> field = fields.next();
          out.append(inner).append(MAPPER.writeValueAsString(field.getKey())).append(": ");
          print(field.getValue(), inner, out);
          out.append(fields.hasNext() ? ",\n" : "\n");
        }
        out.append(indent).append('}');
      } else if (node.isArray()) {
        if (node.isEmpty()) {
          out.append("[]");
          return;
        }
        out.append("[\n");
        for (int i = 0; i < node.size(); i++) {
          out.append(inner);
          print(node.get(i), inner, out);
          out.append(i < node.size() - 1 ? ",\n" : "\n");
        }
        out.append(indent).append(']');
      } else {
        out.append(MAPPER.writeValueAsString(node));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
