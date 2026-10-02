package dev.heimdall.configuration;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CompilerTest {
  @TempDir Path root;
  private Path monitor;

  @BeforeEach
  void copy() throws Exception {
    Path source = Path.of(System.getProperty("heimdall.root"));
    for (String name : List.of("config", ".github"))
      try (var paths = Files.walk(source.resolve(name))) {
        for (var p : paths.toList()) {
          var target = root.resolve(source.relativize(p));
          if (Files.isDirectory(p)) Files.createDirectories(target);
          else Files.copy(p, target);
        }
      }
    monitor =
        root.resolve(
            "config/teams/payments/apps/checkout/services/orders-api/monitors/order-processing.yaml");
  }

  @Test
  void compilesAndIdentitySurvivesRename() throws Exception {
    var before = new Compiler().compile(root, "first");
    assertEquals(1, before.monitors().size());
    Files.move(monitor, monitor.resolveSibling("renamed.yaml"));
    var after = new Compiler().compile(root, "first");
    assertEquals(before.digest(), after.digest());
  }

  @Test
  void rejectsDuplicateKeysUnknownFieldsAndUnsafeTags() throws Exception {
    String original = Files.readString(monitor);
    for (String invalid :
        List.of(
            original.replace("type: api", "type: api\n  type: api"),
            original.replace("type: api", "type: api\n  permissions: all"),
            "!!java.net.URL [https://example.com]")) {
      Files.writeString(monitor, invalid);
      assertThrows(Exception.class, () -> new Compiler().compile(root, "bad"));
    }
  }

  @Test
  void rejectsUnboundedCyclesAndMissingReferences() throws Exception {
    String original = Files.readString(monitor);
    Files.writeString(monitor, original.replace("id: create", "id: create\n      next: create"));
    assertThrows(IllegalArgumentException.class, () -> new Compiler().compile(root, "bad"));
    Files.writeString(monitor, original.replace("locations: [local]", "locations: [unknown]"));
    assertThrows(IllegalArgumentException.class, () -> new Compiler().compile(root, "bad"));
  }

  @Test
  void rejectsCentralGrantsFromTeamFilesAndOwnerMismatch() throws Exception {
    Files.writeString(
        root.resolve("config/teams/payments/privileges.yaml"),
        "apiVersion: synthetics.heimdall.dev/v1alpha1\nkind: RoleBinding\nmetadata:\n  name: escalate\nspec: {}\n");
    assertThrows(IllegalArgumentException.class, () -> new Compiler().compile(root, "bad"));
    Files.delete(root.resolve("config/teams/payments/privileges.yaml"));
    Files.writeString(
        root.resolve(".github/CODEOWNERS"),
        "/config/central/ @platform\n/config/teams/payments/ @wrong\n");
    assertThrows(IllegalArgumentException.class, () -> new Compiler().compile(root, "bad"));
  }

  @Test
  void prohibitsSymlinksAndBrowserMonitors() throws Exception {
    String original = Files.readString(monitor);
    Files.writeString(monitor, original.replace("type: api", "type: browser"));
    assertThrows(IllegalArgumentException.class, () -> new Compiler().compile(root, "bad"));
    Files.writeString(monitor, original);
    Files.createSymbolicLink(monitor.resolveSibling("linked.yaml"), monitor);
    assertThrows(IllegalArgumentException.class, () -> new Compiler().compile(root, "bad"));
  }
}
