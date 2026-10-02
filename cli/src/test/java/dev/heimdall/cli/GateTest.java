package dev.heimdall.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.heimdall.contracts.Execution.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GateTest {
  RunView run(Status status, String publication, List<RegionalResult> results) {
    return new RunView(
        "id",
        "runner",
        Instant.now(),
        "staging",
        "config",
        "digest",
        status,
        publication,
        results,
        null);
  }

  @Test
  void incompleteErrorsCancellationAndMissingCoverageCannotPassPromotion() {
    for (var status : Status.values())
      if (status != Status.passed)
        assertFalse(
            Main.passesGate(
                run(
                    status,
                    "published",
                    List.of(new RegionalResult("a", "local", status, "passed", null, 1)))));
    assertFalse(
        Main.passesGate(
            run(
                Status.passed,
                "pending",
                List.of(new RegionalResult("a", "local", Status.passed, "passed", null, 1)))));
    assertFalse(Main.passesGate(run(Status.passed, "published", List.of())));
    assertTrue(
        Main.passesGate(
            run(
                Status.passed,
                "published",
                List.of(new RegionalResult("a", "local", Status.passed, "passed", null, 1)))));
  }

  @Test
  void junitEscapesContentAndRecordsFailures() throws Exception {
    var xml =
        Main.junit(
            run(
                Status.failed,
                "published",
                List.of(new RegionalResult("a&b", "<local>", Status.failed, "passed", null, 1))));
    var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    var doc =
        factory
            .newDocumentBuilder()
            .parse(
                new java.io.ByteArrayInputStream(
                    xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertEquals("1", doc.getDocumentElement().getAttribute("failures"));
  }
}
