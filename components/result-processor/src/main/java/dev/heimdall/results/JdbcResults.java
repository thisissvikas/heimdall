package dev.heimdall.results;

import dev.heimdall.contracts.*;
import dev.heimdall.contracts.Definitions.*;
import dev.heimdall.contracts.Execution.*;
import dev.heimdall.providers.Providers.Results;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcResults implements Results {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  public JdbcResults(DataSource ds) {
    jdbc = new JdbcTemplate(ds);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
  }

  public JdbcTemplate jdbc() {
    return jdbc;
  }

  @Override
  public void create(
      String id,
      String subject,
      StartRun request,
      Snapshot snapshot,
      List<RegionalResult> expected) {
    tx.executeWithoutResult(
        s -> {
          jdbc.update(
              "INSERT INTO control.runs(id,subject,created_at,environment,config_commit,bundle_digest,status,request,snapshot) VALUES (?,?,now(),?,?,?,'queued',?::jsonb,?::jsonb)",
              id,
              subject,
              request.environment(),
              snapshot.commit(),
              snapshot.digest(),
              Json.write(request),
              Json.write(snapshot));
          for (var r : expected)
            jdbc.update(
                "INSERT INTO control.run_locations(run_id,monitor,location,status) VALUES (?,?,?,'queued')",
                id,
                r.monitorRef(),
                r.location());
        });
  }

  @Override
  public RunView get(String id) {
    var rows =
        jdbc.query(
            "SELECT * FROM control.runs WHERE id=?",
            (rs, n) -> {
              var outcomes =
                  jdbc.query(
                      "SELECT * FROM control.run_locations WHERE run_id=? ORDER BY monitor,location",
                      (r, i) ->
                          new RegionalResult(
                              r.getString("monitor"),
                              r.getString("location"),
                              Status.valueOf(r.getString("status")),
                              r.getString("cleanup"),
                              r.getString("error"),
                              r.getLong("sequence")),
                      id);
              return new RunView(
                  id,
                  rs.getString("subject"),
                  rs.getTimestamp("created_at").toInstant(),
                  rs.getString("environment"),
                  rs.getString("config_commit"),
                  rs.getString("bundle_digest"),
                  Status.valueOf(rs.getString("status")),
                  rs.getString("publication"),
                  outcomes,
                  Json.read(rs.getString("request"), StartRun.class).deployment());
            },
            id);
    if (rows.isEmpty()) throw new NoSuchElementException("Run not found");
    return rows.getFirst();
  }

  @Override
  public Page<RunView> list(Instant from, Instant to, String cursor, int limit) {
    if (limit < 1
        || limit > 100
        || from == null
        || to == null
        || !to.isAfter(from)
        || Duration.between(from, to).compareTo(Duration.ofDays(30)) > 0)
      throw new IllegalArgumentException("History requires a range up to 30 days and limit 1..100");
    Instant before = to;
    String last = "~";
    if (cursor != null) {
      try {
        var p =
            new String(
                    Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8)
                .split("\\|", 2);
        before = Instant.parse(p[0]);
        last = p[1];
      } catch (Exception e) {
        throw new IllegalArgumentException("Invalid cursor");
      }
    }
    var ids =
        jdbc.query(
            "SELECT id,created_at FROM control.runs WHERE created_at>=? AND created_at<? AND (created_at,id)<(?,?) ORDER BY created_at DESC,id DESC LIMIT ?",
            (rs, n) -> Map.entry(rs.getString(1), rs.getTimestamp(2).toInstant()),
            Timestamp.from(from),
            Timestamp.from(to),
            Timestamp.from(before),
            last,
            limit + 1);
    String next = null;
    if (ids.size() > limit) {
      ids = new ArrayList<>(ids.subList(0, limit));
      var row = ids.getLast();
      next =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  ("%s|%s".formatted(row.getValue(), row.getKey()))
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    return new Page<>(ids.stream().map(r -> get(r.getKey())).toList(), next);
  }

  @Override
  public void attempt(Attempt attempt) {
    tx.executeWithoutResult(
        s -> {
          jdbc.execute("SET LOCAL lock_timeout='5s'");
          jdbc.queryForObject(
              "SELECT results.ensure_partitions(?)",
              Object.class,
              Timestamp.from(attempt.startedAt()));
          jdbc.update(
              "INSERT INTO results.step_attempts(started_at,id,run_id,monitor,location,step_id,success,duration_ms,payload) VALUES (?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING",
              Timestamp.from(attempt.startedAt()),
              attempt.id(),
              attempt.runId(),
              attempt.monitorRef(),
              attempt.location(),
              attempt.stepId(),
              attempt.success(),
              attempt.timing().getOrDefault("total", 0L),
              Json.write(attempt));
        });
  }

  @Override
  public List<Attempt> steps(String id) {
    return jdbc.query(
        "SELECT payload FROM results.step_attempts WHERE run_id=? ORDER BY started_at,id LIMIT 1000",
        (rs, n) -> Json.read(rs.getString(1), Attempt.class),
        id);
  }

  @Override
  public void ingest(ResultEvent event) {
    tx.executeWithoutResult(
        s -> {
          var run =
              jdbc.queryForMap("SELECT * FROM control.runs WHERE id=? FOR UPDATE", event.runId());
          var expected =
              jdbc.queryForMap(
                  "SELECT * FROM control.run_locations WHERE run_id=? AND monitor=? AND location=?",
                  event.runId(),
                  event.monitorRef(),
                  event.location());
          jdbc.queryForObject(
              "SELECT results.ensure_partitions(?)",
              Object.class,
              Timestamp.from(event.partitionTime()));
          int inserted =
              jdbc.update(
                  "INSERT INTO results.events(partition_time,event_id,run_id,payload) VALUES (?,?,?,?::jsonb) ON CONFLICT DO NOTHING",
                  Timestamp.from(event.partitionTime()),
                  event.eventId(),
                  event.runId(),
                  Json.write(event));
          if (inserted == 0
              || event.sequence() <= ((Number) expected.get("sequence")).longValue()
              || Status.valueOf((String) expected.get("status")).terminal()) return;
          jdbc.update(
              "UPDATE control.run_locations SET status=?,sequence=?,cleanup=?,error=? WHERE run_id=? AND monitor=? AND location=?",
              event.status().name(),
              event.sequence(),
              event.cleanup(),
              event.error(),
              event.runId(),
              event.monitorRef(),
              event.location());
          if (event.status().terminal()) {
            LocalDate day = event.partitionTime().atZone(ZoneOffset.UTC).toLocalDate();
            boolean passed = event.status() == Status.passed;
            jdbc.update(
                "INSERT INTO results.aggregates(day,monitor,environment,location,passed,failed) VALUES (?,?,?,?,?,?) ON CONFLICT(day,monitor,environment,location) DO UPDATE SET passed=results.aggregates.passed+excluded.passed,failed=results.aggregates.failed+excluded.failed",
                day,
                event.monitorRef(),
                run.get("environment"),
                event.location(),
                passed ? 1 : 0,
                passed ? 0 : 1);
            if ("schedule".equals(event.trigger())) alert(event, run, passed);
          }
          var states =
              jdbc.query(
                  "SELECT status FROM control.run_locations WHERE run_id=?",
                  (rs, n) -> Status.valueOf(rs.getString(1)),
                  event.runId());
          boolean terminal = states.stream().allMatch(Status::terminal);
          Status status =
              terminal
                  ? states.stream()
                      .filter(v -> v != Status.passed)
                      .findFirst()
                      .orElse(Status.passed)
                  : states.contains(Status.running)
                      ? Status.running
                      : states.contains(Status.waiting) ? Status.waiting : Status.queued;
          jdbc.update(
              "UPDATE control.runs SET status=?,publication=? WHERE id=?",
              status.name(),
              terminal ? "published" : "pending",
              event.runId());
        });
  }

  private void alert(ResultEvent event, Map<String, Object> run, boolean passed) {
    String env = (String) run.get("environment");
    var old =
        jdbc.queryForList(
            "SELECT * FROM results.alert_state WHERE monitor=? AND environment=? AND location=? FOR UPDATE",
            event.monitorRef(),
            env,
            event.location());
    if (!old.isEmpty()
        && ((Timestamp) old.getFirst().get("last_time")).toInstant().isAfter(event.partitionTime()))
      return;
    var snapshot = Json.read(run.get("snapshot").toString(), Snapshot.class);
    var policy =
        snapshot
            .alertPolicies()
            .getOrDefault(
                "%s/default".formatted(Definitions.appScope(event.monitorRef())),
                new AlertPolicy(3, 1, 0, null));
    int failures =
        passed ? 0 : (old.isEmpty() ? 1 : ((Number) old.getFirst().get("failures")).intValue() + 1);
    boolean wasAlerting = !old.isEmpty() && (boolean) old.getFirst().get("alerting"),
        alerting = failures >= Math.max(1, policy.consecutiveFailures());
    jdbc.update(
        "INSERT INTO results.alert_state(monitor,environment,location,failures,last_time,alerting) VALUES (?,?,?,?,?,?) ON CONFLICT(monitor,environment,location) DO UPDATE SET failures=excluded.failures,last_time=excluded.last_time,alerting=excluded.alerting",
        event.monitorRef(),
        env,
        event.location(),
        failures,
        Timestamp.from(event.partitionTime()),
        alerting);
    if (wasAlerting != alerting && policy.webhookRef() != null)
      jdbc.update(
          "INSERT INTO control.notification_intents(id,destination,body) VALUES (?,?,?::jsonb) ON CONFLICT DO NOTHING",
          "%s:alert".formatted(event.eventId()),
          policy.webhookRef(),
          Json.write(
              Map.of(
                  "monitor",
                  event.monitorRef(),
                  "environment",
                  env,
                  "location",
                  event.location(),
                  "state",
                  alerting ? "failing" : "recovered")));
  }

  @Override
  public void audit(String subject, String action, String resource) {
    jdbc.update(
        "INSERT INTO control.audit(id,timestamp,subject,action,resource) VALUES (?,now(),?,?,?)",
        UUID.randomUUID().toString(),
        subject,
        action,
        resource);
  }
}
