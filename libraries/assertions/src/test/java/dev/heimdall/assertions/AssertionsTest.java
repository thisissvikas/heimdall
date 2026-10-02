package dev.heimdall.assertions;

import static org.junit.jupiter.api.Assertions.*;

import dev.heimdall.contracts.Definitions.Assertion;
import dev.heimdall.contracts.Json;
import java.util.*;
import org.junit.jupiter.api.Test;

class AssertionsTest {
  private Checks.Response response(String json) {
    return new Checks.Response(
        200, Map.of("x-id", "42"), json, Json.read(json, Map.class), Map.of("total", 12L));
  }

  @Test
  void differentiatesNullMissingAndEmptyCollections() {
    var r = response("{\"nullField\":null,\"items\":[{\"id\":1},{\"id\":null}],\"empty\":[]}");
    assertTrue(
        Checks.evaluate(new Assertion("body", "$.nullField", "isNull", null, null), r).passed());
    assertFalse(
        Checks.evaluate(new Assertion("body", "$.absent", "isNull", null, null), r).passed());
    assertFalse(
        Checks.evaluate(new Assertion("body", "$.items[*].id", "allNotNull", null, null), r)
            .passed());
    assertFalse(
        Checks.evaluate(new Assertion("body", "$.empty", "allNotNull", null, null), r).passed());
    assertTrue(Checks.evaluate(new Assertion("status", null, "equals", 200L, null), r).passed());
    assertTrue(Checks.evaluate(new Assertion("latency", null, "lessThan", 20, null), r).passed());
    assertTrue(Checks.evaluate(new Assertion("header", "X-ID", "equals", "42", null), r).passed());
  }

  @Test
  void malformedBodyAndRemoteSchemaFailClosed() {
    var r = new Checks.Response(200, Map.of(), "invalid", null, Map.of());
    assertFalse(Checks.evaluate(new Assertion("body", "$.id", "exists", null, null), r).passed());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Checks.validate(
                new Assertion(
                    "body", null, "schema", Map.of("$ref", "https://internal/schema"), null)));
    assertTrue(
        Checks.evaluate(
                new Assertion(
                    "body",
                    "$",
                    "schema",
                    Map.of("type", "object", "required", List.of("id")),
                    null),
                response("{\"id\":1}"))
            .passed());
  }

  @Test
  void celSupportsConditionsCollectionsAndStrictVariableLookup() {
    var context =
        Map.<String, Object>of(
            "response",
            Map.of("status", 200L, "body", Map.of("items", List.of(Map.of("id", 1L)))),
            "vars",
            Map.of(),
            "env",
            Map.of(),
            "run",
            Map.of());
    assertTrue(
        Expressions.test(
            "response.status == 200 && response.body.items.all(i, i.id != null)", context));
    assertThrows(
        IllegalArgumentException.class, () -> Expressions.validate("response.status === 200"));
    assertThrows(
        IllegalArgumentException.class, () -> Expressions.test("vars.missing == 'x'", context));
  }

  @Test
  void encodesUrlSegmentsAndBuildsStructuredBodies() {
    var context =
        Map.<String, Object>of(
            "env",
            Map.of("baseUrl", "https://example.com"),
            "vars",
            Map.of("id", "a/b ?&", "count", 3L));
    assertEquals(
        "https://example.com/jobs/a%2Fb%20%3F%26",
        Templates.render("${env.baseUrl}/jobs/${vars.id}", context, true));
    assertEquals(
        Map.of("count", 3L), Templates.structured(Map.of("count", "${vars.count}"), context));
    assertThrows(
        IllegalArgumentException.class, () -> Templates.render("${vars.missing}", context, false));
  }
}
