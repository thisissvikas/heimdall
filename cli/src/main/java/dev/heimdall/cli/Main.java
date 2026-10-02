package dev.heimdall.cli;

import dev.heimdall.configuration.Compiler;
import dev.heimdall.contracts.Execution.*;
import dev.heimdall.contracts.Json;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

public final class Main {
  private Main() {}

  public static void main(String[] args) {
    try {
      System.exit(execute(args));
    } catch (Exception e) {
      System.err.println("Heimdall command failed: %s".formatted(e.getMessage()));
      System.exit(2);
    }
  }

  static int execute(String[] args) throws Exception {
    if (args.length == 0)
      throw new IllegalArgumentException(
          "Use validate <repository> or run --url ... --reference ... --environment ... [--wait]");
    if (args[0].equals("validate")) {
      if (args.length != 2) throw new IllegalArgumentException("validate requires repository path");
      System.out.println(new Compiler().compile(Path.of(args[1]), "validation").digest());
      return 0;
    }
    if (!args[0].equals("run")) throw new IllegalArgumentException("Unknown command");
    var options = new HashMap<String, String>();
    boolean wait = false;
    for (int i = 1; i < args.length; i++) {
      if (args[i].equals("--wait")) {
        wait = true;
        continue;
      }
      if (!args[i].startsWith("--") || i + 1 == args.length)
        throw new IllegalArgumentException("Invalid option");
      options.put(args[i].substring(2), args[++i]);
    }
    String token = System.getenv(options.getOrDefault("token-env", "HEIMDALL_TOKEN"));
    if (token == null) throw new IllegalArgumentException("Token environment variable required");
    URI api = URI.create(required(options, "url"));
    if (!api.getScheme().equals("https") && !api.getHost().equals("localhost"))
      throw new IllegalArgumentException("API URL requires HTTPS");
    var client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    var request =
        new StartRun(
            List.of(required(options, "reference").split(",")),
            required(options, "environment"),
            options.containsKey("locations")
                ? List.of(options.get("locations").split(","))
                : List.of(),
            Map.of(),
            null);
    String response =
        call(
            client,
            api.resolve("/v1/runs"),
            token,
            "POST",
            Json.write(request),
            options.getOrDefault("idempotency-key", UUID.randomUUID().toString()));
    String id = Json.MAPPER.readTree(response).path("id").asText();
    System.out.println(id);
    if (!wait) return 0;
    long deadline =
        System.nanoTime()
            + Duration.ofSeconds(Long.parseLong(options.getOrDefault("timeout-seconds", "1800")))
                .toNanos();
    while (System.nanoTime() < deadline) {
      var run =
          Json.read(
              call(client, api.resolve("/v1/runs/%s".formatted(id)), token, "GET", null, null),
              RunView.class);
      if (run.status().terminal()) {
        if (options.containsKey("json")) writeReport(Path.of(options.get("json")), Json.write(run));
        if (options.containsKey("junit")) writeReport(Path.of(options.get("junit")), junit(run));
        return passesGate(run) ? 0 : 1;
      }
      Thread.sleep(1000);
    }
    throw new IllegalStateException("Deployment gate timed out");
  }

  private static String required(Map<String, String> options, String key) {
    var value = options.get(key);
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("--%s required".formatted(key));
    return value;
  }

  private static void writeReport(Path path, String content) throws Exception {
    Path parent = path.toAbsolutePath().getParent();
    if (parent != null) Files.createDirectories(parent);
    Files.writeString(path, content);
  }

  private static String call(
      HttpClient client, URI uri, String token, String method, String body, String key)
      throws Exception {
    var request =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer %s".formatted(token))
            .header("Content-Type", "application/json");
    if (key != null) request.header("Idempotency-Key", key);
    var response =
        client.send(
            request
                .method(
                    method,
                    body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300)
      throw new IllegalStateException("API returned HTTP %d".formatted(response.statusCode()));
    return response.body();
  }

  public static boolean passesGate(RunView run) {
    return run.status() == Status.passed
        && run.publication().equals("published")
        && !run.outcomes().isEmpty()
        && run.outcomes().stream()
            .allMatch(r -> r.status() == Status.passed && "passed".equals(r.cleanup()));
  }

  public static String junit(RunView run) {
    var cases = new ArrayList<String>();
    int failures = 0;
    for (var outcome : run.outcomes()) {
      boolean passed = outcome.status() == Status.passed && "passed".equals(outcome.cleanup());
      if (!passed) failures++;
      cases.add(
          "<testcase classname=\"%s\" name=\"%s\">%s</testcase>"
              .formatted(
                  xml(outcome.monitorRef()),
                  xml(outcome.location()),
                  passed
                      ? ""
                      : "<failure message=\"%s\"/>".formatted(xml(outcome.status().name()))));
    }
    if (!passesGate(run) && failures == 0) {
      cases.add("<testcase name=\"coverage\"><failure message=\"Results incomplete\"/></testcase>");
      failures++;
    }
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><testsuite name=\"Heimdall\" tests=\"%d\" failures=\"%d\">%s</testsuite>"
        .formatted(cases.size(), failures, String.join("", cases));
  }

  private static String xml(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }
}
