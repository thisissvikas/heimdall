package dev.heimdall.testing;

import dev.heimdall.contracts.Definitions.*;
import dev.heimdall.contracts.Execution.*;
import dev.heimdall.contracts.Json;
import java.util.*;

public final class Fixtures {
  private Fixtures() {}

  public static final String MONITOR = "payments/checkout/orders-api/order-processing";

  public static Step step(String json) {
    return Json.read(json, Step.class);
  }

  public static Monitor monitor(List<Step> steps, List<Step> cleanup) {
    return new Monitor(
        "api",
        List.of("staging"),
        List.of("local"),
        null,
        "25m",
        1000,
        Map.of(),
        steps,
        cleanup,
        null,
        null);
  }

  public static Snapshot snapshot(Monitor monitor, String baseUrl) {
    return new Snapshot(
        "approved-commit",
        "bundle-digest",
        Map.of(MONITOR, monitor),
        Map.of(
            "payments/checkout/staging", new Environment(Map.of("baseUrl", baseUrl), false, null)),
        Map.of(),
        Map.of(),
        Map.of(
            "local",
            new Location(
                "private",
                List.of("localhost"),
                List.of("payments/checkout"),
                List.of("api"),
                true,
                20,
                "fixture")),
        List.of(
            new RoleBinding(
                List.of("dev-runner", "scheduler"),
                "Runner",
                List.of("payments/checkout"),
                List.of("staging"),
                List.of("local"),
                List.of("secret/data/payments"),
                false,
                false),
            new RoleBinding(
                List.of("dev-viewer"),
                "Viewer",
                List.of("payments/checkout"),
                List.of("staging"),
                List.of("local"),
                List.of(),
                false,
                false),
            new RoleBinding(
                List.of("dev-admin"),
                "PlatformAdministrator",
                List.of("*"),
                List.of("staging"),
                List.of("local"),
                List.of(),
                true,
                true)),
        Map.of(),
        new Platform(null, 100, 1000, 1048576),
        List.of(),
        Map.of());
  }

  public static RunInput input(Monitor monitor) {
    return input(snapshot(monitor, "http://localhost"));
  }

  public static RunInput input(Snapshot snapshot) {
    return new RunInput(
        UUID.randomUUID().toString(),
        MONITOR,
        "staging",
        "local",
        snapshot,
        Map.of(),
        "dev-runner",
        "api",
        null);
  }
}
