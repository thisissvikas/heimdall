package dev.heimdall.configuration;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentationTest {
  @Test
  void beginnerMonitorFromTheGuideCompilesWithTheCheckoutCatalog(@TempDir Path copy)
      throws Exception {
    Path root = Path.of(System.getProperty("heimdall.root"));
    for (var name : List.of("config", ".github"))
      try (var paths = Files.walk(root.resolve(name))) {
        for (var source : paths.toList()) {
          var target = copy.resolve(root.relativize(source));
          if (Files.isDirectory(source)) Files.createDirectories(target);
          else Files.copy(source, target);
        }
      }
    var guide = Files.readString(root.resolve("docs/user-guide/02-first-monitor.md"));
    var yaml = Pattern.compile("```yaml\\R(.*?)```", Pattern.DOTALL).matcher(guide);
    assertTrue(yaml.find());
    Files.writeString(
        copy.resolve(
            "config/teams/payments/apps/checkout/services/orders-api/monitors/health.yaml"),
        yaml.group(1));
    var snapshot = new Compiler().compile(copy, "documentation");
    assertTrue(snapshot.monitors().containsKey("payments/checkout/orders-api/health"));
    Files.writeString(
        copy.resolve("config/teams/payments/apps/checkout/scripts/invalid.js"),
        "export default function( { broken syntax;");
    assertThrows(
        IllegalArgumentException.class, () -> new Compiler().compile(copy, "invalid-script"));
  }

  @Test
  void userGuideLocalLinksResolve() throws Exception {
    Path root = Path.of(System.getProperty("heimdall.root"));
    var links = Pattern.compile("\\[[^]\\r\\n]+]\\(([^)]+)\\)");
    try (var files = Files.walk(root.resolve("docs"))) {
      for (var file : files.filter(p -> p.toString().endsWith(".md")).toList()) {
        var matcher = links.matcher(Files.readString(file));
        while (matcher.find()) {
          String target = matcher.group(1).split("#", 2)[0];
          if (target.isBlank() || target.contains("://")) continue;
          assertTrue(
              Files.exists(file.getParent().resolve(target)),
              "%s links to missing %s".formatted(file, target));
        }
      }
    }
  }
}
