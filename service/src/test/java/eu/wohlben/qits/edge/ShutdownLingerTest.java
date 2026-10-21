package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The linger itself, framework-free: that it runs AFTER the drain is Quarkus' ordering, argued in
 * {@link ShutdownLinger}'s javadoc and seen in any booting suite's shutdown log.
 */
class ShutdownLingerTest {

  @Test
  void zeroReturnsAtOnce() {
    long start = System.nanoTime();
    ShutdownLinger.linger(Duration.ZERO);
    assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(1)) < 0);
  }

  @Test
  void aPositiveLingerSleepsAtLeastThatLong() {
    long start = System.nanoTime();
    ShutdownLinger.linger(Duration.ofMillis(30));
    assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofMillis(30)) >= 0);
  }
}
