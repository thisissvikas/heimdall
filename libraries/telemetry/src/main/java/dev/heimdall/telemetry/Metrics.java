package dev.heimdall.telemetry;

import dev.heimdall.providers.Providers.Telemetry;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;

public final class Metrics implements Telemetry {
  private final MeterRegistry registry;

  public Metrics(MeterRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void record(String monitor, String location, String status, long durationMs) {
    // Monitor identity comes exclusively from the bounded approved catalog.
    registry
        .timer("heimdall.execution", "monitor", monitor, "location", location, "status", status)
        .record(durationMs, TimeUnit.MILLISECONDS);
  }
}
