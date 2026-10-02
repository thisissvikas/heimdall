package dev.heimdall.contracts;

import java.time.Duration;
import java.util.*;

public final class Definitions {
  private Definitions() {}

  public static final String API_VERSION = "synthetics.heimdall.dev/v1alpha1";

  public static <T> List<T> list(List<T> v) {
    return v == null ? List.of() : List.copyOf(v);
  }

  public static <K, V> Map<K, V> map(Map<K, V> v) {
    return v == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(v));
  }

  public static Duration duration(String v) {
    if (v == null) throw new IllegalArgumentException("Duration required");
    if (v.startsWith("P")) return Duration.parse(v);
    var m = java.util.regex.Pattern.compile("([0-9]+)(ms|s|m|h|d)").matcher(v);
    if (!m.matches()) throw new IllegalArgumentException("Invalid duration: %s".formatted(v));
    long n = Long.parseLong(m.group(1));
    return switch (m.group(2)) {
      case "ms" -> Duration.ofMillis(n);
      case "s" -> Duration.ofSeconds(n);
      case "m" -> Duration.ofMinutes(n);
      case "h" -> Duration.ofHours(n);
      default -> Duration.ofDays(n);
    };
  }

  public record Defaults(String timeout, Map<String, Object> values) {
    public Defaults {
      values = map(values);
    }
  }

  public record Catalog(Map<String, String> entries) {
    public Catalog {
      entries = map(entries);
    }
  }

  public record Platform(
      Defaults defaults, int maxConcurrentRuns, int maxSteps, int maxResponseBytes) {}

  public record Team(Defaults defaults) {}

  public record Application(String project, Defaults defaults) {}

  public record Service(Defaults defaults) {}

  public record Environment(Map<String, Object> values, boolean production, Defaults defaults) {
    public Environment {
      values = map(values);
    }
  }

  public record Parameter(String type, boolean required, Object defaultValue) {}

  public record Schedule(
      String every, String cron, String timezone, boolean paused, String overlap, String jitter) {}

  public record Monitor(
      String type,
      List<String> environments,
      List<String> locations,
      String authRef,
      String timeout,
      int maxExecutions,
      Map<String, Parameter> parameters,
      List<Step> steps,
      List<Step> cleanup,
      Schedule schedule,
      Defaults defaults) {
    public Monitor {
      environments = list(environments);
      locations = list(locations);
      parameters = map(parameters);
      steps = list(steps);
      cleanup = list(cleanup);
    }
  }

  public record Suite(List<String> monitors, List<String> environments, List<String> locations) {
    public Suite {
      monitors = list(monitors);
      environments = list(environments);
      locations = list(locations);
    }
  }

  public record Request(
      String method,
      String url,
      Map<String, String> headers,
      Map<String, Object> query,
      Object json,
      String text,
      Map<String, String> form,
      String timeout) {
    public Request {
      headers = map(headers);
      query = map(query);
      form = map(form);
    }
  }

  public record Assertion(
      String source, String path, String operator, Object value, String message) {}

  public record Extraction(String source, String path) {}

  public record Retry(int maxAttempts, List<Integer> statuses, String backoff, boolean idempotent) {
    public Retry {
      statuses = list(statuses);
    }
  }

  public record Script(String script, List<String> outputs) {
    public Script {
      outputs = list(outputs);
    }
  }

  public record Branch(String id, List<Step> steps, List<String> outputs) {
    public Branch {
      steps = list(steps);
      outputs = list(outputs);
    }
  }

  public record Step(
      String id,
      String type,
      Request request,
      List<Assertion> assertions,
      Map<String, Extraction> extract,
      Map<String, Object> values,
      String expression,
      String next,
      String onTrue,
      String onFalse,
      String duration,
      String interval,
      String timeout,
      String until,
      String failWhen,
      List<Branch> branches,
      Retry retry,
      Script postResponse,
      int maxVisits) {
    public Step {
      assertions = list(assertions);
      extract = map(extract);
      values = map(values);
      branches = list(branches);
    }
  }

  public record Authentication(
      String type,
      String usernameRef,
      String passwordRef,
      String tokenRef,
      String header,
      String tokenUrl,
      String clientIdRef,
      String clientSecretRef,
      String scope,
      int maxRefresh) {}

  public record Location(
      String network,
      List<String> allowedHosts,
      List<String> scopes,
      List<String> capabilities,
      boolean enabled,
      int capacity,
      String region) {
    public Location {
      allowedHosts = list(allowedHosts);
      scopes = list(scopes);
      capabilities = list(capabilities);
    }
  }

  public record RoleBinding(
      List<String> subjects,
      String role,
      List<String> scopes,
      List<String> environments,
      List<String> locations,
      List<String> secretPrefixes,
      boolean production,
      boolean artifacts) {
    public RoleBinding {
      subjects = list(subjects);
      scopes = list(scopes);
      environments = list(environments);
      locations = list(locations);
      secretPrefixes = list(secretPrefixes);
    }
  }

  public record CiTrust(
      String repository,
      String workflow,
      String ref,
      String environment,
      String subject,
      List<String> suites) {
    public CiTrust {
      suites = list(suites);
    }
  }

  public record AlertPolicy(
      int consecutiveFailures, int quorum, long latencyMs, String webhookRef) {}

  public record Snapshot(
      String commit,
      String digest,
      Map<String, Monitor> monitors,
      Map<String, Environment> environments,
      Map<String, Authentication> authentication,
      Map<String, String> scripts,
      Map<String, Location> locations,
      List<RoleBinding> roles,
      Map<String, Suite> suites,
      Platform platform,
      List<CiTrust> ciTrust,
      Map<String, AlertPolicy> alertPolicies) {
    public Snapshot {
      monitors = map(monitors);
      environments = map(environments);
      authentication = map(authentication);
      scripts = map(scripts);
      locations = map(locations);
      roles = list(roles);
      suites = map(suites);
      ciTrust = list(ciTrust);
      alertPolicies = map(alertPolicies);
    }
  }

  public static String appScope(String monitor) {
    var p = monitor.split("/");
    if (p.length != 4)
      throw new IllegalArgumentException("Expected team/application/service/monitor");
    return "%s/%s".formatted(p[0], p[1]);
  }
}
