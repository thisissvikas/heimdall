package dev.heimdall.control;

import dev.heimdall.contracts.*;
import dev.heimdall.contracts.Definitions.*;
import dev.heimdall.contracts.Execution.*;
import dev.heimdall.providers.Providers.*;
import dev.heimdall.results.JdbcResults;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public final class Api {
  private final RunService runs;
  private final JdbcResults results;
  private final ConfigurationRepository configs;
  private final Authorization auth;
  private final Environment env;
  private final org.springframework.beans.factory.ObjectProvider<
          dev.heimdall.results.DiagnosticArtifacts>
      diagnostics;

  public Api(
      RunService runs,
      JdbcResults results,
      ConfigurationRepository configs,
      Authorization auth,
      Environment env,
      org.springframework.beans.factory.ObjectProvider<dev.heimdall.results.DiagnosticArtifacts>
          diagnostics) {
    this.runs = runs;
    this.results = results;
    this.configs = configs;
    this.auth = auth;
    this.env = env;
    this.diagnostics = diagnostics;
  }

  private Identity identity(Authentication authentication) {
    if (authentication.getPrincipal() instanceof Jwt jwt) {
      if ("https://token.actions.githubusercontent.com".equals(jwt.getIssuer().toString())) {
        return CiIdentity.resolve(jwt.getClaims(), configs.active().ciTrust());
      }
      return new Identity(jwt.getSubject(), jwt.getClaims());
    }
    return new Identity(authentication.getName(), Map.of());
  }

  @PostMapping("/v1/runs")
  public ResponseEntity<?> start(
      Authentication a, @RequestHeader("Idempotency-Key") String key, @RequestBody String body) {
    var request = Json.read(body, StartRun.class);
    var identity = identity(a);
    CiIdentity.requireReferences(identity, request.references(), configs.active().ciTrust());
    String id = runs.start(identity, request, key);
    return ResponseEntity.accepted()
        .header("Location", "/v1/runs/%s".formatted(id))
        .body(Map.of("id", id));
  }

  @GetMapping("/v1/runs/{id}")
  public RunView get(Authentication a, @PathVariable String id) {
    return runs.get(identity(a), id);
  }

  @GetMapping("/v1/runs")
  public Page<RunView> history(
      Authentication a,
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "50") int limit) {
    var identity = identity(a);
    var page = results.list(from, to, cursor, limit);
    return new Page<>(
        page.items().stream().filter(r -> runs.readable(identity, r)).toList(), page.nextCursor());
  }

  @PostMapping("/v1/runs/{id}/cancel")
  public ResponseEntity<?> cancel(Authentication a, @PathVariable String id) {
    runs.cancel(identity(a), id);
    return ResponseEntity.accepted().body(Map.of("id", id, "cancellationRequested", true));
  }

  @GetMapping("/v1/runs/{id}/steps")
  public List<Attempt> steps(Authentication a, @PathVariable String id) {
    runs.get(identity(a), id);
    return results.steps(id);
  }

  @GetMapping("/v1/runs/{id}/artifacts")
  public List<?> artifacts(Authentication a, @PathVariable String id) {
    var run = runs.get(identity(a), id);
    for (var r : run.outcomes())
      auth.require(
          identity(a),
          "artifacts",
          r.monitorRef(),
          run.environment(),
          r.location(),
          configs.active());
    var store = diagnostics.getIfAvailable();
    return store == null ? List.of() : store.list(id);
  }

  @GetMapping(
      value = "/v1/runs/{id}/artifacts/{artifact}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public byte[] artifact(Authentication a, @PathVariable String id, @PathVariable String artifact) {
    artifacts(a, id);
    var store = diagnostics.getIfAvailable();
    if (store == null) throw new NoSuchElementException("Artifact not found");
    return store.get(id, artifact);
  }

  @GetMapping(value = "/v1/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(Authentication a, @PathVariable String id) {
    var identity = identity(a);
    runs.get(identity, id);
    var emitter = new SseEmitter(60000L);
    Thread.startVirtualThread(
        () -> {
          try {
            String previous = "";
            for (int i = 0; i < 60; i++) {
              var run = runs.get(identity, id);
              String value = Json.write(run);
              if (!value.equals(previous)) {
                emitter.send(SseEmitter.event().name("run").data(value));
                previous = value;
              }
              if (run.status().terminal()) {
                emitter.complete();
                return;
              }
              Thread.sleep(1000);
            }
            emitter.complete();
          } catch (Exception e) {
            emitter.completeWithError(e);
          }
        });
    return emitter;
  }

  @GetMapping("/v1/locations")
  public Map<String, Location> locations(Authentication a) {
    var snapshot = configs.active();
    var out = new TreeMap<String, Location>();
    var identity = identity(a);
    snapshot
        .monitors()
        .forEach(
            (id, m) -> {
              for (var location : m.locations())
                for (var environment : m.environments())
                  try {
                    auth.require(identity, "read", id, environment, location, snapshot);
                    out.put(location, snapshot.locations().get(location));
                  } catch (SecurityException ignored) {
                  }
            });
    return out;
  }

  @GetMapping("/v1/status/reconciliation")
  public SyncStatus status(Authentication a) {
    admin(a);
    return configs.status();
  }

  @GetMapping("/v1/audit-events")
  public List<AuditEvent> audit(
      Authentication a,
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestParam(defaultValue = "100") int limit) {
    admin(a);
    range(from, to, 365, limit);
    return results
        .jdbc()
        .query(
            "SELECT * FROM control.audit WHERE timestamp>=? AND timestamp<? ORDER BY timestamp DESC LIMIT ?",
            (rs, n) ->
                new AuditEvent(
                    rs.getString("id"),
                    rs.getTimestamp("timestamp").toInstant(),
                    rs.getString("subject"),
                    rs.getString("action"),
                    rs.getString("resource")),
            Timestamp.from(from),
            Timestamp.from(to),
            limit);
  }

  @GetMapping("/v1/results/summary")
  public List<Map<String, Object>> summary(
      Authentication a,
      @RequestParam String monitor,
      @RequestParam String environment,
      @RequestParam Instant from,
      @RequestParam Instant to) {
    range(from, to, 90, 100);
    var snapshot = configs.active();
    var m = snapshot.monitors().get(monitor);
    if (m == null) throw new NoSuchElementException("Monitor not found");
    for (var l : m.locations())
      auth.require(identity(a), "read", monitor, environment, l, snapshot);
    return results
        .jdbc()
        .queryForList(
            "SELECT day,location,passed,failed FROM results.aggregates WHERE monitor=? AND environment=? AND day>=? AND day<? ORDER BY day,location",
            monitor,
            environment,
            from.atZone(ZoneOffset.UTC).toLocalDate(),
            to.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1));
  }

  @GetMapping("/v1/agents")
  public List<Map<String, Object>> agents(Authentication a) {
    admin(a);
    return results
        .jdbc()
        .queryForList(
            "SELECT id,location,seen_at,runtime FROM control.agents ORDER BY id LIMIT 100");
  }

  public record AgentRequest(String id, String location, String runtime) {}

  @PostMapping("/v1/integrations/agents/register")
  public ResponseEntity<?> register(Authentication a, @RequestBody String body) {
    var request = Json.read(body, AgentRequest.class);
    var identity = identity(a);
    var snapshot = configs.active();
    if (!identity.subject().equals("agent:%s".formatted(request.id()))
        || !snapshot.locations().containsKey(request.location())
        || request.runtime() == null
        || request.runtime().length() > 100)
      throw new SecurityException("Agent identity is not approved");
    boolean allowed =
        snapshot.roles().stream()
            .anyMatch(
                r ->
                    r.subjects().contains(identity.subject())
                        && r.locations().contains(request.location()));
    if (!allowed) throw new SecurityException("Agent location not granted");
    results
        .jdbc()
        .update(
            "INSERT INTO control.agents(id,location,subject,seen_at,runtime) VALUES (?,?,?,now(),?) ON CONFLICT(id) DO UPDATE SET seen_at=now(),runtime=excluded.runtime WHERE control.agents.location=excluded.location AND control.agents.subject=excluded.subject",
            request.id(),
            request.location(),
            identity.subject(),
            request.runtime());
    return ResponseEntity.accepted().body(Map.of("id", request.id()));
  }

  @PostMapping("/v1/integrations/github/webhook")
  public ResponseEntity<?> webhook(
      @RequestHeader("X-Hub-Signature-256") String signature,
      @RequestHeader("X-GitHub-Delivery") String delivery,
      @RequestHeader("X-GitHub-Event") String event,
      @RequestBody byte[] body)
      throws Exception {
    String secret = env.getRequiredProperty("heimdall.webhook-secret");
    if (body.length > 262144
        || delivery.length() > 200
        || !signature.matches("sha256=[a-f0-9]{64}"))
      throw new SecurityException("Invalid webhook");
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(
        new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
    if (!java.security.MessageDigest.isEqual(
        mac.doFinal(body), HexFormat.of().parseHex(signature.substring(7))))
      throw new SecurityException("Invalid webhook signature");
    var data = Json.MAPPER.readTree(body);
    if (!event.equals("push")
        || !data.path("repository")
            .path("full_name")
            .asText()
            .equals(env.getRequiredProperty("heimdall.github-repository"))
        || !data.path("ref")
            .asText()
            .equals("refs/heads/%s".formatted(env.getProperty("heimdall.git-branch", "main"))))
      throw new SecurityException("Webhook source not approved");
    results
        .jdbc()
        .update(
            "INSERT INTO control.webhook_deliveries(id) VALUES (?) ON CONFLICT DO NOTHING",
            delivery);
    // The durable delivery is a notification; polling resolves the current protected-branch head.
    return ResponseEntity.accepted().body(Map.of("accepted", true));
  }

  private void admin(Authentication a) {
    auth.require(identity(a), "admin", "*", null, null, configs.active());
  }

  private static void range(Instant from, Instant to, int days, int limit) {
    if (from == null
        || to == null
        || !to.isAfter(from)
        || Duration.between(from, to).compareTo(Duration.ofDays(days)) > 0
        || limit < 1
        || limit > 100) throw new IllegalArgumentException("Invalid bounded query");
  }

  @ExceptionHandler(SecurityException.class)
  public ResponseEntity<?> forbidden(SecurityException e) {
    return error(403, "forbidden", e.getMessage());
  }

  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<?> missing(NoSuchElementException e) {
    return error(404, "not_found", e.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<?> invalid(IllegalArgumentException e) {
    return error(400, "invalid_request", e.getMessage());
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<?> unavailable(IllegalStateException e) {
    return error(503, "unavailable", e.getMessage());
  }

  private static ResponseEntity<?> error(int status, String code, String message) {
    return ResponseEntity.status(status)
        .body(Map.of("code", code, "message", message == null ? code : message));
  }
}
