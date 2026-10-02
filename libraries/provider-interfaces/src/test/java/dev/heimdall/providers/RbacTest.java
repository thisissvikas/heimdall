package dev.heimdall.providers;

import static org.junit.jupiter.api.Assertions.*;

import dev.heimdall.providers.Providers.Identity;
import dev.heimdall.testing.Fixtures;
import java.util.*;
import org.junit.jupiter.api.Test;

class RbacTest {
  @Test
  void enforcesRolesScopesLocationsAndArtifactPrivileges() {
    var snapshot = Fixtures.input(Fixtures.monitor(List.of(), List.of())).snapshot();
    var rbac = new Rbac();
    rbac.require(
        new Identity("dev-viewer", Map.of()),
        "read",
        Fixtures.MONITOR,
        "staging",
        "local",
        snapshot);
    rbac.require(
        new Identity("dev-runner", Map.of()),
        "run",
        Fixtures.MONITOR,
        "staging",
        "local",
        snapshot);
    assertThrows(
        SecurityException.class,
        () ->
            rbac.require(
                new Identity("dev-viewer", Map.of()),
                "run",
                Fixtures.MONITOR,
                "staging",
                "local",
                snapshot));
    assertThrows(
        SecurityException.class,
        () ->
            rbac.require(
                new Identity("dev-runner", Map.of()),
                "read",
                "shipping/tracking/service/test",
                "staging",
                "local",
                snapshot));
    assertThrows(
        SecurityException.class,
        () ->
            rbac.require(
                new Identity("dev-runner", Map.of()),
                "run",
                Fixtures.MONITOR,
                "staging",
                "unknown",
                snapshot));
    assertThrows(
        SecurityException.class,
        () ->
            rbac.require(
                new Identity("dev-runner", Map.of()),
                "artifacts",
                Fixtures.MONITOR,
                "staging",
                "local",
                snapshot));
    assertFalse(Rbac.matches("payments/checkout", "payments/checkout-other/test"));
  }
}
