package dev.heimdall.telemetry;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MetricsTest {
  @Test
  void usesOnlyApprovedMonitorLocationAndStatusTags() {
    var registry = new SimpleMeterRegistry();
    new Metrics(registry).record("payments/checkout/api/test", "local", "passed", 25);
    var timer = registry.get("heimdall.execution").timer();
    assertEquals(1, timer.count());
    assertEquals(3, timer.getId().getTags().size());
  }
}
