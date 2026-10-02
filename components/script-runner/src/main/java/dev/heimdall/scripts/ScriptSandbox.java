package dev.heimdall.scripts;

import dev.heimdall.contracts.*;
import dev.heimdall.contracts.Execution.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

public final class ScriptSandbox {
  public record Output(
      List<AssertionResult> assertions, Map<String, Object> outputs, String error) {}

  private final List<String> command;
  private final java.net.URI endpoint;

  public ScriptSandbox(List<String> command) {
    this.command = List.copyOf(command);
    this.endpoint = null;
  }

  public ScriptSandbox(java.net.URI endpoint) {
    this.endpoint = endpoint;
    this.command = List.of();
  }

  public static ScriptSandbox production() {
    String endpoint = System.getenv("SCRIPT_ENDPOINT");
    if (endpoint != null && !endpoint.isBlank())
      return new ScriptSandbox(java.net.URI.create(endpoint));
    String image = System.getenv("SCRIPT_IMAGE");
    if (image == null || !image.contains("@sha256:"))
      throw new IllegalArgumentException("SCRIPT_IMAGE must pin the sandbox image digest");
    return new ScriptSandbox(
        List.of(
            "docker",
            "run",
            "--rm",
            "--interactive",
            "--runtime=runsc",
            "--network=none",
            "--read-only",
            "--memory=96m",
            "--cpus=0.5",
            "--pids-limit=32",
            "--cap-drop=ALL",
            "--security-opt=no-new-privileges",
            "--user=65532:65532",
            image));
  }

  /** For local fixtures only; production isolation requires the gVisor container above. */
  public static ScriptSandbox local(Path root) {
    String node = System.getenv().getOrDefault("HEIMDALL_NODE", "node");
    String script =
        root.resolve("components/script-runner/sandbox.mjs").toAbsolutePath().toString();
    return new ScriptSandbox(
        List.of(
            node,
            "--permission",
            "--allow-fs-read=%s".formatted(script),
            "--max-old-space-size=32",
            script));
  }

  public Output evaluate(
      String source,
      int status,
      Map<String, String> headers,
      String text,
      Map<String, Object> vars) {
    if (endpoint != null) {
      try (var client =
          java.net.http.HttpClient.newBuilder()
              .connectTimeout(java.time.Duration.ofSeconds(2))
              .build()) {
        String body =
            Json.write(
                Map.of(
                    "source",
                    source,
                    "response",
                    Map.of("status", status, "headers", headers, "text", text),
                    "vars",
                    vars));
        var response =
            client.send(
                java.net.http.HttpRequest.newBuilder(endpoint.resolve("/evaluate"))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
          byte[] bytes = stream.readNBytes(65537);
          if (response.statusCode() != 200 || bytes.length > 65536)
            throw new IllegalArgumentException("Sandbox unavailable or output limit exceeded");
          var output =
              Json.read(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), Output.class);
          if (output.error() != null)
            throw new IllegalArgumentException("Script failed or exceeded its limits");
          return output;
        }
      } catch (Exception e) {
        throw new IllegalArgumentException("Script failed or exceeded its limits", e);
      }
    }
    Process p = null;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      p = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      Process process = p;
      Future<byte[]> read = executor.submit(() -> process.getInputStream().readNBytes(65537));
      try (var out = p.getOutputStream()) {
        out.write(
            Json.write(
                    Map.of(
                        "source",
                        source,
                        "response",
                        Map.of("status", status, "headers", headers, "text", text),
                        "vars",
                        vars))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      if (!p.waitFor(5, TimeUnit.SECONDS)) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        throw new IllegalStateException("Script exceeded deadline");
      }
      byte[] data = read.get(1, TimeUnit.SECONDS);
      if (data.length > 65536) throw new IllegalStateException("Script output exceeded limit");
      var output =
          Json.read(new String(data, java.nio.charset.StandardCharsets.UTF_8), Output.class);
      if (p.exitValue() != 0 || output.error() != null)
        throw new IllegalArgumentException("Script failed or exceeded its limits");
      return output;
    } catch (Exception e) {
      throw new IllegalArgumentException("Script failed or exceeded its limits", e);
    } finally {
      if (p != null && p.isAlive()) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
      }
    }
  }
}
