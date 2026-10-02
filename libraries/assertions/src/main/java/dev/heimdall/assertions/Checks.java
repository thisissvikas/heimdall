package dev.heimdall.assertions;

import com.jayway.jsonpath.*;
import com.networknt.schema.*;
import dev.heimdall.contracts.*;
import dev.heimdall.contracts.Definitions.*;
import dev.heimdall.contracts.Execution.*;
import java.math.BigDecimal;
import java.util.*;

public final class Checks {
  private Checks() {}

  public static final Object MISSING = new Object();

  public record Response(
      int status, Map<String, String> headers, String text, Object body, Map<String, Long> timing) {
    public Map<String, Object> context() {
      var m = new LinkedHashMap<String, Object>();
      m.put("status", (long) status);
      m.put("headers", headers);
      m.put("body", body);
      m.put("text", text);
      return m;
    }
  }

  public static Object extract(Response response, String source, String path) {
    return switch (source) {
      case "status" -> response.status();
      case "body" -> path(response.body(), path);
      case "text" -> response.text();
      case "header" -> response.headers().getOrDefault(path.toLowerCase(Locale.ROOT), null);
      case "cookie" -> cookie(response.headers().get("set-cookie"), path);
      case "latency" -> response.timing().getOrDefault("total", 0L);
      case "certificateExpiry" -> response.timing().get("certificateExpiryDays");
      default -> throw new IllegalArgumentException("Unknown assertion source");
    };
  }

  private static Object cookie(String header, String name) {
    if (header == null) return null;
    for (var part : header.split("[;,]")) {
      var p = part.trim().split("=", 2);
      if (p.length == 2 && p[0].equals(name)) return p[1];
    }
    return null;
  }

  public static Object path(Object body, String path) {
    if (body == null) return MISSING;
    try {
      return JsonPath.read(body, path == null ? "$" : path);
    } catch (PathNotFoundException e) {
      return MISSING;
    }
  }

  public static void validate(Assertion a) {
    if (!Set.of("status", "body", "text", "header", "cookie", "latency", "certificateExpiry")
        .contains(a.source())) throw new IllegalArgumentException("Unsupported assertion source");
    if (!Set.of(
            "equals",
            "notEquals",
            "greaterThan",
            "greaterOrEqual",
            "lessThan",
            "lessOrEqual",
            "matches",
            "contains",
            "exists",
            "notNull",
            "isNull",
            "allNotNull",
            "schema")
        .contains(a.operator()))
      throw new IllegalArgumentException("Unsupported assertion operator");
    if (a.source().equals("body") && a.path() != null) JsonPath.compile(a.path());
    if (a.operator().equals("matches")) com.google.re2j.Pattern.compile(String.valueOf(a.value()));
    if (a.operator().equals("schema")) schema(a.value());
  }

  public static AssertionResult evaluate(Assertion a, Response r) {
    try {
      Object actual = extract(r, a.source(), a.path());
      boolean found = actual != MISSING;
      boolean result =
          switch (a.operator()) {
            case "exists" -> found;
            case "isNull" -> found && actual == null;
            case "notNull" -> found && actual != null;
            case "equals" -> found && equal(actual, a.value());
            case "notEquals" -> found && !equal(actual, a.value());
            case "greaterThan" -> found && compare(actual, a.value()) > 0;
            case "greaterOrEqual" -> found && compare(actual, a.value()) >= 0;
            case "lessThan" -> found && compare(actual, a.value()) < 0;
            case "lessOrEqual" -> found && compare(actual, a.value()) <= 0;
            case "matches" ->
                found
                    && actual instanceof String s
                    && com.google.re2j.Pattern.compile(String.valueOf(a.value())).matcher(s).find();
            case "contains" ->
                found
                    && (actual instanceof Collection<?> c
                        ? c.stream().anyMatch(v -> equal(v, a.value()))
                        : actual instanceof String s && s.contains(String.valueOf(a.value())));
            case "allNotNull" ->
                found
                    && actual instanceof Collection<?> c
                    && !c.isEmpty()
                    && c.stream().allMatch(Objects::nonNull);
            case "schema" ->
                found && schema(a.value()).validate(Json.MAPPER.valueToTree(actual)).isEmpty();
            default -> false;
          };
      return new AssertionResult(
          a.message() == null ? "%s %s".formatted(a.source(), a.operator()) : a.message(), result);
    } catch (Exception e) {
      return new AssertionResult(
          a.message() == null ? "%s %s".formatted(a.source(), a.operator()) : a.message(), false);
    }
  }

  private static boolean equal(Object a, Object b) {
    if (a instanceof Number && b instanceof Number) return compare(a, b) == 0;
    return Objects.equals(a, b);
  }

  private static int compare(Object a, Object b) {
    return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
  }

  private static JsonSchema schema(Object value) {
    var node = Json.MAPPER.valueToTree(value);
    rejectRemote(node);
    return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(node);
  }

  private static void rejectRemote(com.fasterxml.jackson.databind.JsonNode node) {
    if (node.isObject()) {
      if (node.has("$ref") && !node.get("$ref").asText().startsWith("#"))
        throw new IllegalArgumentException("Only local schema references allowed");
      node.elements().forEachRemaining(Checks::rejectRemote);
    } else if (node.isArray()) node.elements().forEachRemaining(Checks::rejectRemote);
  }
}
