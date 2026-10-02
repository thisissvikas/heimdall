package dev.heimdall.control;

import static org.junit.jupiter.api.Assertions.*;

import dev.heimdall.contracts.Definitions.CiTrust;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlUnitTest {
  @Test
  void ciTrustCannotInheritSuitesFromAnotherWorkflowSharingTheSubject() {
    var rules =
        List.of(
            new CiTrust(
                "acme/app",
                "acme/app/.github/workflows/staging.yml@refs/heads/main",
                "refs/heads/main",
                "staging",
                "ci:app",
                List.of("staging-suite")),
            new CiTrust(
                "acme/app",
                "acme/app/.github/workflows/prod.yml@refs/heads/main",
                "refs/heads/main",
                "production",
                "ci:app",
                List.of("production-suite")));
    var claims =
        Map.<String, Object>of(
            "iss",
            CiIdentity.ISSUER,
            "repository",
            "acme/app",
            "job_workflow_ref",
            rules.getFirst().workflow(),
            "ref",
            "refs/heads/main",
            "environment",
            "staging");
    var identity = CiIdentity.resolve(claims, rules);
    assertDoesNotThrow(
        () -> CiIdentity.requireReferences(identity, List.of("staging-suite"), rules));
    assertThrows(
        SecurityException.class,
        () -> CiIdentity.requireReferences(identity, List.of("production-suite"), rules));
    var fork = new HashMap<>(claims);
    fork.put("repository", "attacker/app");
    assertThrows(SecurityException.class, () -> CiIdentity.resolve(fork, rules));
  }

  @Test
  void localRevisionTracksTeamYamlScriptsAndCodeowners(@TempDir Path root) throws Exception {
    Files.createDirectories(root.resolve("config/teams"));
    Files.createDirectories(root.resolve(".github"));
    Files.writeString(root.resolve(".github/CODEOWNERS"), "* @acme/platform");
    Files.writeString(root.resolve("config/teams/monitor.yaml"), "first");
    var source = new GitSource.LocalTree(root);
    var first = source.head().commit();
    assertEquals(first, source.head().commit());
    Files.writeString(root.resolve("config/teams/monitor.yaml"), "second");
    var second = source.head().commit();
    assertNotEquals(first, second);
    Files.writeString(root.resolve("config/teams/assert.js"), "export default function() {}");
    assertNotEquals(second, source.head().commit());
    Files.createSymbolicLink(root.resolve("config/alias"), root.resolve("config/teams"));
    assertThrows(IllegalArgumentException.class, source::head);
  }
}
