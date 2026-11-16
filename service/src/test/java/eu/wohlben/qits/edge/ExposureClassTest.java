package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The two variants, and what a private edge makes of the platform's own labels — asserted without a
 * boot, because {@code EdgeRoutingTest} runs the public profile only and a second boot is the
 * restart its socket tests cannot afford.
 */
class ExposureClassTest {

  @Test
  void theVariantIsPublicOrPrivate() {
    assertEquals(
        List.of(ExposureClass.PUBLIC, ExposureClass.PRIVATE), List.of(ExposureClass.values()));
    assertTrue(ExposureClass.PUBLIC.servesPlatformApps());
    assertFalse(ExposureClass.PRIVATE.servesPlatformApps());
  }

  @Test
  void onlyAPublicEdgeServesThePlatformLabels() {
    assertEquals(PlatformApps.labels(), EdgeRouter.platformLabels(ExposureClass.PUBLIC));
    assertTrue(EdgeRouter.platformLabels(ExposureClass.PRIVATE).isEmpty());
  }

  @Test
  void onAPrivateEdgeAPlatformLabelIsAnOrdinaryUnknownNameAndWaitsForTheCatchUp() {
    Map<String, Boolean> projects = Map.of("qits", false);
    HostEnvironments privateNames =
        HostEnvironments.of(
            List.of("prod"),
            "prod",
            EdgeRouter.platformLabels(ExposureClass.PRIVATE),
            "wohlben.eu");
    HostEnvironments publicNames =
        HostEnvironments.of(
            List.of("prod"), "prod", EdgeRouter.platformLabels(ExposureClass.PUBLIC), "wohlben.eu");

    for (String label : PlatformApps.labels()) {
      String host = label + ".qits.wohlben.eu";
      HostEnvironments.Route named = privateNames.route(host, projects);
      assertEquals(HostEnvironments.Reading.UNKNOWN_APP, named.reading(), host);
      assertEquals(label, named.unknownApp(), host);
      assertFalse(EdgeRouter.servedBeforeCatchUp(named), "qits-1403 is public-edge only: " + host);

      assertTrue(EdgeRouter.servedBeforeCatchUp(publicNames.route(host, projects)), host);
    }
  }
}
