package eu.wohlben.qits.edge.wireguard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * wg0's address and MTU are set by the init helper and announced by this process, so the two
 * defaults — {@code docker/edge-init/main.go}'s constants and application.properties' expressions —
 * must be the same values, or the edge announces a tunnel it does not have.
 */
class WireguardDefaultsTest {

  @Test
  void theAnnouncedDefaultsAreTheInitHelpersDefaults() throws Exception {
    String go = Files.readString(Path.of("../docker/edge-init/main.go"));
    String properties = Files.readString(Path.of("src/main/resources/application.properties"));

    assertEquals("100.96.0.1/24", goConstant(go, "defaultAddress"));
    assertEquals("1380", goConstant(go, "defaultMTU"));
    assertEquals(
        goConstant(go, "defaultAddress"),
        propertyDefault(properties, "qits.edge.wireguard.address", "QITS_EDGE_WIREGUARD_ADDRESS"));
    assertEquals(
        goConstant(go, "defaultMTU"),
        propertyDefault(properties, "qits.edge.wireguard.mtu", "QITS_EDGE_WIREGUARD_MTU"));
  }

  private static String goConstant(String go, String name) {
    Matcher found = Pattern.compile("(?m)^\\s*" + name + "\\s*=\\s*\"([^\"]*)\"").matcher(go);
    if (!found.find()) {
      throw new AssertionError("main.go declares no constant " + name);
    }
    return found.group(1);
  }

  private static String propertyDefault(String properties, String key, String variable) {
    Matcher found =
        Pattern.compile(
                "(?m)^" + Pattern.quote(key) + "=\\$\\{" + Pattern.quote(variable) + ":([^}]*)}$")
            .matcher(properties);
    if (!found.find()) {
      throw new AssertionError(
          "application.properties states no " + key + "=${" + variable + ":<default>}");
    }
    return found.group(1);
  }
}
