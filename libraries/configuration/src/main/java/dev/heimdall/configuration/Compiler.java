package dev.heimdall.configuration;

import static dev.heimdall.contracts.Definitions.*;

import dev.heimdall.assertions.*;
import dev.heimdall.contracts.*;
import dev.heimdall.contracts.Definitions.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Compiles the whole approved tree. Directory scope is authoritative. */
public final class Compiler {
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,62}");
  private static final Set<String> CENTRAL =
      Set.of("Platform", "Teams", "Projects", "Location", "RoleBinding", "CiTrust", "AlertPolicy");
  private static final Set<String> TEAM =
      Set.of(
          "Team",
          "Application",
          "Service",
          "Environment",
          "Monitor",
          "Suite",
          "Authentication",
          "AlertPolicy");

  public Snapshot compile(Path root, String commit) throws Exception {
    Path config = root.resolve("config");
    var docs = new ArrayList<Doc>();
    var scripts = new TreeMap<String, String>();
    try (var paths = Files.walk(config)) {
      for (var p : paths.sorted().toList()) {
        if (Files.isSymbolicLink(p)) throw bad(p, "Symbolic links are prohibited");
        if (!Files.isRegularFile(p)) continue;
        String rel = config.relativize(p).toString().replace('\\', '/');
        if (rel.endsWith(".yaml") || rel.endsWith(".yml")) docs.add(parse(p, rel));
        else if (rel.endsWith(".js")) {
          var parts = rel.split("/");
          if (parts.length < 6
              || !parts[0].equals("teams")
              || !parts[2].equals("apps")
              || !parts[4].equals("scripts")) throw bad(p, "Scripts must be application scoped");
          String source = Files.readString(p);
          if (source.length() > 65536 || !source.contains("export default function"))
            throw bad(p, "Expected bounded default-function script");
          validateScript(p, source);
          scripts.put(
              "%s/%s/%s"
                  .formatted(
                      parts[1],
                      parts[3],
                      String.join("/", Arrays.copyOfRange(parts, 4, parts.length))),
              source);
        } else throw bad(p, "Unsupported configuration file");
      }
    }
    var teams = catalog(docs, "Teams");
    var projects = catalog(docs, "Projects");
    if (teams.isEmpty() || projects.isEmpty())
      throw new IllegalArgumentException("Central teams and projects catalogs required");
    String owners = Files.readString(root.resolve(".github/CODEOWNERS"));
    if (!owners.lines().anyMatch(l -> l.matches("/config/central/\\s+@.+")))
      throw new IllegalArgumentException("Central configuration requires CODEOWNERS");
    teams.forEach(
        (name, owner) -> {
          if (!NAME.matcher(name).matches() || !owner.startsWith("@"))
            throw new IllegalArgumentException("Invalid team catalog");
          if (!owners
              .lines()
              .anyMatch(l -> l.strip().equals("/config/teams/%s/ %s".formatted(name, owner))))
            throw new IllegalArgumentException("CODEOWNERS mismatch for %s".formatted(name));
        });
    var seen = new HashSet<String>();
    for (var d : docs) {
      if (!seen.add("%s:%s:%s".formatted(d.kind, d.scope, d.name)))
        throw bad(d.path, "Duplicate resource identity");
      if (d.rel.startsWith("teams/") && !teams.containsKey(d.team()))
        throw bad(d.path, "Unregistered team");
    }
    var platforms = docs.stream().filter(d -> d.kind.equals("Platform")).toList();
    if (platforms.size() != 1)
      throw new IllegalArgumentException("Exactly one central Platform required");
    var platform = typed(platforms.getFirst(), Platform.class);
    if (platform.maxSteps() < 1
        || platform.maxSteps() > 10000
        || platform.maxConcurrentRuns() < 1
        || platform.maxResponseBytes() < 1
        || platform.maxResponseBytes() > 10485760)
      throw new IllegalArgumentException("Invalid platform limits");
    var apps = new HashSet<String>();
    var services = new HashSet<String>();
    var defaults = new HashMap<String, Defaults>();
    for (var d : docs) {
      if (d.kind.equals("Application")) {
        var a = typed(d, Application.class);
        if (!projects.containsKey(a.project())) throw bad(d.path, "Unknown project");
        apps.add(d.scope);
        defaults.put(d.scope, a.defaults());
      } else if (d.kind.equals("Team")) defaults.put(d.scope, typed(d, Team.class).defaults());
      else if (d.kind.equals("Service")) {
        services.add(d.scope);
        defaults.put(d.scope, typed(d, Service.class).defaults());
      }
    }
    var monitors = new TreeMap<String, Monitor>();
    var environments = new TreeMap<String, Environment>();
    var auth = new TreeMap<String, Authentication>();
    var locations = new TreeMap<String, Location>();
    var roles = new ArrayList<RoleBinding>();
    var suites = new TreeMap<String, Suite>();
    var trust = new ArrayList<CiTrust>();
    var alerts = new TreeMap<String, AlertPolicy>();
    for (var d : docs) {
      if (d.rel.startsWith("teams/") && d.scope.contains("/") && !apps.contains(d.app()))
        throw bad(d.path, "Application registration required");
      switch (d.kind) {
        case "Environment" -> {
          var e = typed(d, Environment.class);
          var values = new LinkedHashMap<String, Object>();
          merge(values, platform.defaults());
          merge(values, defaults.get(d.team()));
          merge(values, defaults.get(d.app()));
          merge(values, e.defaults());
          values.putAll(e.values());
          environments.put(
              "%s/%s".formatted(d.scope, d.name),
              new Environment(values, e.production(), e.defaults()));
        }
        case "Authentication" -> {
          var a = typed(d, Authentication.class);
          if (!Set.of("basic", "bearer", "apiKey", "oauth2").contains(a.type())
              || a.maxRefresh() < 0
              || a.maxRefresh() > 3) throw bad(d.path, "Unsupported authentication profile");
          switch (a.type()) {
            case "basic" -> {
              required(a.usernameRef());
              required(a.passwordRef());
            }
            case "bearer", "apiKey" -> required(a.tokenRef());
            case "oauth2" -> {
              required(a.tokenUrl());
              required(a.clientIdRef());
              required(a.clientSecretRef());
            }
          }
          auth.put("%s/%s".formatted(d.scope, d.name), a);
        }
        case "Location" -> {
          var l = typed(d, Location.class);
          if (!Set.of("public", "private").contains(l.network())
              || l.capacity() < 1
              || !l.capabilities().contains("api")) throw bad(d.path, "Invalid API location");
          if (l.network().equals("private") && l.allowedHosts().isEmpty())
            throw bad(d.path, "Private location needs explicit hosts");
          locations.put(d.name, l);
        }
        case "RoleBinding" -> {
          var r = typed(d, RoleBinding.class);
          if (!Set.of("Viewer", "Runner", "PlatformAdministrator").contains(r.role())
              || r.subjects().isEmpty()
              || r.scopes().isEmpty()) throw bad(d.path, "Invalid role binding");
          roles.add(r);
        }
        case "Suite" -> suites.put("%s/%s".formatted(d.scope, d.name), typed(d, Suite.class));
        case "CiTrust" -> trust.add(typed(d, CiTrust.class));
        case "AlertPolicy" ->
            alerts.put(
                d.scope.isEmpty() ? d.name : "%s/%s".formatted(d.scope, d.name),
                typed(d, AlertPolicy.class));
        case "Monitor" -> {
          if (!services.contains(d.scope)) throw bad(d.path, "Service registration required");
          var m = typed(d, Monitor.class);
          if (!"api".equals(m.type())) throw bad(d.path, "Only API synthetics supported");
          var values = new LinkedHashMap<String, Object>();
          merge(values, defaults.get(d.scope));
          merge(values, m.defaults());
          String timeout = m.timeout();
          if (timeout == null) {
            timeout = "5m";
            for (var def :
                Arrays.asList(
                    platform.defaults(),
                    defaults.get(d.team()),
                    defaults.get(d.app()),
                    defaults.get(d.scope),
                    m.defaults()))
              if (def != null && def.timeout() != null) timeout = def.timeout();
          }
          positive(timeout, "monitor timeout");
          if (duration(timeout).compareTo(java.time.Duration.ofDays(1)) > 0)
            throw bad(d.path, "Monitor timeout exceeds 24 hours");
          if (m.environments().isEmpty() || m.locations().isEmpty() || m.steps().isEmpty())
            throw bad(d.path, "Environment, locations and steps required");
          int budget = m.maxExecutions() == 0 ? platform.maxSteps() : m.maxExecutions();
          if (budget < 1 || budget > platform.maxSteps())
            throw bad(d.path, "Step budget exceeds platform quota");
          validateSteps(m.steps(), platform.maxSteps());
          validateSteps(m.cleanup(), 100);
          if (m.schedule() != null) {
            var s = m.schedule();
            if (s.every() == null || s.cron() != null)
              throw bad(d.path, "Interval schedules supported; cron is not yet enabled");
            positive(s.every(), "schedule interval");
            if (duration(s.every()).compareTo(java.time.Duration.ofSeconds(10)) < 0)
              throw bad(d.path, "Schedule interval below 10 seconds");
            if (s.overlap() != null && !Set.of("skip", "bufferOne").contains(s.overlap()))
              throw bad(d.path, "Unsupported overlap");
            if (s.jitter() != null) positive(s.jitter(), "jitter");
          }
          m.parameters()
              .forEach(
                  (k, p) -> {
                    if (!Set.of("string", "number", "boolean").contains(p.type()))
                      throw new IllegalArgumentException("Unknown parameter type");
                  });
          monitors.put(
              "%s/%s".formatted(d.scope, d.name),
              new Monitor(
                  m.type(),
                  m.environments(),
                  m.locations(),
                  m.authRef(),
                  timeout,
                  budget,
                  m.parameters(),
                  m.steps(),
                  m.cleanup(),
                  m.schedule(),
                  new Defaults(null, values)));
        }
        default -> {}
      }
    }
    monitors.forEach(
        (id, m) -> {
          String app = appScope(id);
          for (var env : m.environments())
            if (!environments.containsKey("%s/%s".formatted(app, env)))
              throw new IllegalArgumentException("Unknown environment for %s".formatted(id));
          for (var loc : m.locations()) {
            var l = locations.get(loc);
            if (l == null || l.scopes().stream().noneMatch(s -> scopeMatches(s, id)))
              throw new IllegalArgumentException("Location not granted to %s".formatted(id));
          }
          if (m.authRef() != null && !auth.containsKey("%s/%s".formatted(app, m.authRef())))
            throw new IllegalArgumentException("Unknown authRef for %s".formatted(id));
          checkScripts(m.steps(), app, scripts);
          checkScripts(m.cleanup(), app, scripts);
        });
    suites.forEach(
        (id, s) -> {
          for (var ref : s.monitors())
            if (!monitors.containsKey(ref)
                || !appScope(ref).equals(id.substring(0, id.lastIndexOf('/'))))
              throw new IllegalArgumentException("Suite crosses application scope");
        });
    var snapshot =
        new Snapshot(
            commit,
            "",
            monitors,
            environments,
            auth,
            scripts,
            locations,
            roles,
            suites,
            platform,
            trust,
            alerts);
    return new Snapshot(
        commit,
        Json.sha256(Json.write(snapshot)),
        monitors,
        environments,
        auth,
        scripts,
        locations,
        roles,
        suites,
        platform,
        trust,
        alerts);
  }

  private static void checkScripts(List<Step> steps, String app, Map<String, String> scripts) {
    for (var s : steps) {
      if (s.postResponse() != null
          && (!s.postResponse().script().startsWith("scripts/")
              || s.postResponse().script().contains("..")
              || !scripts.containsKey("%s/%s".formatted(app, s.postResponse().script()))))
        throw new IllegalArgumentException("Unknown application script");
      for (var b : s.branches()) checkScripts(b.steps(), app, scripts);
    }
  }

  private static void validateScript(Path path, String source) throws Exception {
    var process =
        new ProcessBuilder(
                System.getenv().getOrDefault("HEIMDALL_NODE", "node"),
                "--input-type=module",
                "--check")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    try {
      try (var input = process.getOutputStream()) {
        input.write(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0)
        throw bad(path, "Invalid JavaScript syntax");
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  private static void validateSteps(List<Step> steps, int limit) {
    if (steps.size() > limit) throw new IllegalArgumentException("Too many steps");
    var ids = new LinkedHashMap<String, Step>();
    for (var s : steps) {
      required(s.id());
      if (!NAME.matcher(s.id()).matches() || ids.put(s.id(), s) != null)
        throw new IllegalArgumentException("Invalid or duplicate step id");
      if (s.type() == null
          || !Set.of("http", "wait", "poll", "assert", "set", "condition", "parallel", "end")
              .contains(s.type())) throw new IllegalArgumentException("Unknown step primitive");
      if (Set.of("http", "poll").contains(s.type())) {
        if (s.request() == null) throw new IllegalArgumentException("Request required");
        var r = s.request();
        required(r.url());
        if (r.method() != null
            && !Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .contains(r.method())) throw new IllegalArgumentException("Unsupported method");
        if ((r.json() != null ? 1 : 0) + (r.text() != null ? 1 : 0) + (!r.form().isEmpty() ? 1 : 0)
            > 1) throw new IllegalArgumentException("Multiple request bodies");
        if (r.timeout() != null) positive(r.timeout(), "request timeout");
      }
      for (var a : s.assertions()) Checks.validate(a);
      for (var x : s.extract().values()) {
        if (!Set.of("body", "header", "cookie").contains(x.source()))
          throw new IllegalArgumentException("Unknown extraction source");
        required(x.path());
      }
      if (s.type().equals("wait")) positive(s.duration(), "wait duration");
      if (s.type().equals("poll")) {
        positive(s.interval(), "poll interval");
        positive(s.timeout(), "poll timeout");
        Expressions.validate(s.until());
        if (s.failWhen() != null) Expressions.validate(s.failWhen());
      }
      if (Set.of("condition", "assert").contains(s.type())) Expressions.validate(s.expression());
      if (s.retry() != null && (s.retry().maxAttempts() < 1 || s.retry().maxAttempts() > 5))
        throw new IllegalArgumentException("Retries must be between 1 and 5");
      if (s.retry() != null && s.retry().backoff() != null)
        positive(s.retry().backoff(), "retry backoff");
      if (s.maxVisits() < 0) throw new IllegalArgumentException("Negative visit bound");
      if (s.type().equals("parallel")) {
        if (s.branches().isEmpty() || s.branches().size() > 8)
          throw new IllegalArgumentException("Parallel fanout must be 1..8");
        var branches = new HashSet<String>();
        var outputs = new HashSet<String>();
        for (var b : s.branches()) {
          if (!NAME.matcher(b.id()).matches() || !branches.add(b.id()))
            throw new IllegalArgumentException("Invalid branch id");
          for (var o : b.outputs())
            if (!outputs.add(o)) throw new IllegalArgumentException("Conflicting parallel outputs");
          validateSteps(b.steps(), limit);
        }
      }
    }
    for (var s : steps)
      for (var target : Arrays.asList(s.next(), s.onTrue(), s.onFalse()))
        if (target != null && !ids.containsKey(target))
          throw new IllegalArgumentException("Unknown step transition");
    // Conservatively require bounded targets for backward edges, including conditional loops.
    var order = new ArrayList<>(ids.keySet());
    for (var s : steps)
      for (var target : Arrays.asList(s.next(), s.onTrue(), s.onFalse()))
        if (target != null
            && order.indexOf(target) <= order.indexOf(s.id())
            && ids.get(target).maxVisits() < 1)
          throw new IllegalArgumentException("Cycles require maxVisits on their entry step");
  }

  private record Doc(
      Path path, String rel, String kind, String name, String scope, Map<String, Object> spec) {
    String team() {
      return scope.split("/")[0];
    }

    String app() {
      var p = scope.split("/");
      return p.length >= 2 ? "%s/%s".formatted(p[0], p[1]) : scope;
    }
  }

  @SuppressWarnings("unchecked")
  private Doc parse(Path path, String rel) throws Exception {
    if (Files.size(path) > 262144) throw bad(path, "YAML size limit exceeded");
    var options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0);
    options.setNestingDepthLimit(40);
    options.setCodePointLimit(262144);
    Object loaded = new Yaml(new SafeConstructor(options)).load(Files.readString(path));
    if (!(loaded instanceof Map<?, ?> raw)) throw bad(path, "Resource object required");
    var node = Json.MAPPER.valueToTree(raw);
    node.fieldNames()
        .forEachRemaining(
            k -> {
              if (!Set.of("apiVersion", "kind", "metadata", "spec").contains(k))
                throw bad(path, "Unknown resource field: %s".formatted(k));
            });
    if (!API_VERSION.equals(node.path("apiVersion").asText()))
      throw bad(path, "Unsupported apiVersion");
    String kind = node.path("kind").asText(), name = node.path("metadata").path("name").asText();
    if (!NAME.matcher(name).matches()) throw bad(path, "Invalid metadata.name");
    node.path("metadata")
        .fieldNames()
        .forEachRemaining(
            k -> {
              if (!Set.of("name", "labels").contains(k)) throw bad(path, "Unknown metadata field");
            });
    if (!node.path("spec").isObject()) throw bad(path, "Typed spec required");
    var p = rel.split("/");
    String scope = "";
    if (p[0].equals("central")) {
      if (!CENTRAL.contains(kind)) throw bad(path, "Resource kind is not central");
    } else {
      if (!p[0].equals("teams") || p.length < 3 || !TEAM.contains(kind))
        throw bad(path, "Invalid team resource");
      scope = p[1];
      if (p.length > 3) {
        if (!p[2].equals("apps") || p.length < 5) throw bad(path, "Invalid application path");
        scope += "/%s".formatted(p[3]);
        if (p[4].equals("services")) {
          if (p.length < 7) throw bad(path, "Invalid service path");
          scope += "/%s".formatted(p[5]);
          if (kind.equals("Monitor") && (p.length != 8 || !p[6].equals("monitors")))
            throw bad(path, "Monitor path required");
        } else if (kind.equals("Monitor") || kind.equals("Service"))
          throw bad(path, "Service scoped resource required");
      }
      if (kind.equals("Team") && p.length != 3
          || kind.equals("Application") && p.length != 5
          || kind.equals("Environment") && (p.length != 6 || !p[4].equals("environments"))
          || kind.equals("Authentication") && (p.length != 6 || !p[4].equals("authentication"))
          || kind.equals("Suite") && (p.length != 6 || !p[4].equals("suites")))
        throw bad(path, "Kind does not match directory scope");
    }
    return new Doc(path, rel, kind, name, scope, (Map<String, Object>) raw.get("spec"));
  }

  private static <T> T typed(Doc doc, Class<T> type) {
    try {
      return Json.MAPPER.convertValue(doc.spec, type);
    } catch (Exception e) {
      throw bad(doc.path, "Invalid %s spec: %s".formatted(doc.kind, e.getMessage()));
    }
  }

  private static Map<String, String> catalog(List<Doc> docs, String kind) {
    var matches = docs.stream().filter(d -> d.kind.equals(kind)).toList();
    if (matches.size() != 1)
      throw new IllegalArgumentException("Exactly one %s catalog required".formatted(kind));
    return typed(matches.getFirst(), Catalog.class).entries();
  }

  private static void merge(Map<String, Object> out, Defaults d) {
    if (d != null) out.putAll(d.values());
  }

  private static boolean scopeMatches(String grant, String scope) {
    return grant.equals("*") || scope.equals(grant) || scope.startsWith("%s/".formatted(grant));
  }

  private static void required(String s) {
    if (s == null || s.isBlank()) throw new IllegalArgumentException("Required field missing");
  }

  private static void positive(String s, String field) {
    if (duration(s).isNegative() || duration(s).isZero())
      throw new IllegalArgumentException("%s must be positive".formatted(field));
  }

  private static IllegalArgumentException bad(Path p, String m) {
    return new IllegalArgumentException("%s: %s".formatted(p, m));
  }
}
