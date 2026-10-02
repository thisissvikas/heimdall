package dev.heimdall.control;

import dev.heimdall.contracts.*;
import dev.heimdall.contracts.Definitions.*;
import dev.heimdall.contracts.Execution.*;
import dev.heimdall.providers.Providers.*;
import dev.heimdall.results.JdbcResults;
import dev.heimdall.workflow.*;
import io.temporal.client.*;
import io.temporal.common.RetryOptions;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

public class RunService implements AdmissionActivities {
  private final JdbcResults results;
  private final ConfigurationRepository config;
  private final Authorization auth;
  private final WorkflowClient temporal;
  private final TransactionTemplate tx;

  public RunService(
      DataSource ds,
      JdbcResults results,
      ConfigurationRepository config,
      Authorization auth,
      WorkflowClient temporal) {
    this.results = results;
    this.config = config;
    this.auth = auth;
    this.temporal = temporal;
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
  }

  public String start(Identity identity, StartRun request, String key) {
    String id = create(identity, request, key, "api");
    try {
      dispatch(id);
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("Run {} awaits dispatch retry", id);
    }
    return id;
  }

  private String create(Identity identity, StartRun request, String key, String trigger) {
    if (key == null || key.isBlank() || key.length() > 200)
      throw new IllegalArgumentException("Idempotency-Key required (max 200 characters)");
    var snapshot = config.active();
    var inputs = resolve(identity, request, snapshot, "validation", trigger);
    String hash = Json.sha256(Json.write(request));
    return tx.execute(
        s -> {
          // Serialize admission by identity/key, including concurrent first submissions.
          results
              .jdbc()
              .queryForObject(
                  "SELECT pg_advisory_xact_lock(hashtext(?))",
                  Object.class,
                  "%s:%s".formatted(identity.subject(), key));
          var existing =
              results
                  .jdbc()
                  .queryForList(
                      "SELECT * FROM control.idempotency WHERE subject=? AND key=?",
                      identity.subject(),
                      key);
          if (!existing.isEmpty()) {
            if (!existing.getFirst().get("request_hash").equals(hash))
              throw new IllegalArgumentException("Idempotency key used with a different request");
            return (String) existing.getFirst().get("run_id");
          }
          results
              .jdbc()
              .queryForObject(
                  "SELECT pg_advisory_xact_lock(hashtext('heimdall-admission'))", Object.class);
          int active =
              results
                  .jdbc()
                  .queryForObject(
                      "SELECT count(*) FROM control.runs WHERE status IN ('queued','running','waiting')",
                      Integer.class);
          if (active >= snapshot.platform().maxConcurrentRuns())
            throw new IllegalStateException("Execution quota exhausted");
          String id = UUID.randomUUID().toString();
          results.create(
              id,
              identity.subject(),
              request,
              snapshot,
              inputs.stream()
                  .map(
                      i ->
                          new RegionalResult(
                              i.monitorRef(), i.location(), Status.queued, null, null, 0))
                  .toList());
          results
              .jdbc()
              .update(
                  "INSERT INTO control.idempotency(subject,key,request_hash,run_id) VALUES (?,?,?,?)",
                  identity.subject(),
                  key,
                  hash,
                  id);
          results
              .jdbc()
              .update(
                  "UPDATE control.runs SET dispatch_state=? WHERE id=?",
                  trigger.equals("schedule") ? "scheduled" : "pending",
                  id);
          results.audit(identity.subject(), "run.start", id);
          return id;
        });
  }

  private List<RunInput> resolve(
      Identity identity, StartRun request, Snapshot snapshot, String id, String trigger) {
    if (request.references().isEmpty()
        || request.references().size() > 100
        || request.environment() == null)
      throw new IllegalArgumentException("Approved references and environment required");
    var refs = new LinkedHashSet<String>();
    for (var ref : request.references()) {
      if (snapshot.monitors().containsKey(ref)) refs.add(ref);
      else if (snapshot.suites().containsKey(ref)) {
        var suite = snapshot.suites().get(ref);
        if (!suite.environments().contains(request.environment()))
          throw new IllegalArgumentException("Suite environment not permitted");
        if (!request.locations().isEmpty() && !suite.locations().containsAll(request.locations()))
          throw new IllegalArgumentException("Suite location not permitted");
        refs.addAll(suite.monitors());
      } else throw new IllegalArgumentException("Unknown approved reference");
    }
    var out = new ArrayList<RunInput>();
    for (var ref : refs) {
      var m = snapshot.monitors().get(ref);
      if (!m.environments().contains(request.environment())
          || !m.parameters().keySet().containsAll(request.parameters().keySet()))
        throw new IllegalArgumentException("Environment or runtime input not declared");
      var params = new LinkedHashMap<String, Object>();
      m.parameters()
          .forEach(
              (name, p) -> {
                Object v = request.parameters().getOrDefault(name, p.defaultValue());
                if (v == null && p.required())
                  throw new IllegalArgumentException("Required parameter missing");
                if (v != null) {
                  if (!(p.type().equals("string") && v instanceof String
                      || p.type().equals("number") && v instanceof Number
                      || p.type().equals("boolean") && v instanceof Boolean))
                    throw new IllegalArgumentException("Parameter type mismatch");
                  if (Json.write(v).length() > 4096)
                    throw new IllegalArgumentException("Parameter size exceeded");
                  params.put(name, v);
                }
              });
      var locations = request.locations().isEmpty() ? m.locations() : request.locations();
      if (!m.locations().containsAll(locations))
        throw new IllegalArgumentException("Location not declared by monitor");
      for (var loc : new LinkedHashSet<>(locations)) {
        auth.require(identity, "run", ref, request.environment(), loc, snapshot);
        var l = snapshot.locations().get(loc);
        if (l == null || !l.enabled())
          throw new IllegalStateException("Requested location is unavailable");
        out.add(
            new RunInput(
                id,
                ref,
                request.environment(),
                loc,
                snapshot,
                params,
                identity.subject(),
                trigger,
                request.deployment()));
      }
    }
    return out;
  }

  public void dispatch(String id) {
    var row = results.jdbc().queryForMap("SELECT * FROM control.runs WHERE id=?", id);
    if (!row.get("dispatch_state").equals("pending")) {
      if (Boolean.TRUE.equals(row.get("cancel_requested"))) signalCancellation(id);
      return;
    }
    var snapshot = Json.read(row.get("snapshot").toString(), Snapshot.class);
    var request = Json.read(row.get("request").toString(), StartRun.class);
    var inputs =
        resolve(new Identity((String) row.get("subject"), Map.of()), request, snapshot, id, "api");
    for (var input : inputs) {
      var stub =
          temporal.newWorkflowStub(
              ApiWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue("workflow")
                  .setWorkflowId(workflowId(input))
                  .setWorkflowRunTimeout(
                      Definitions.duration(input.monitor().timeout()).plusMinutes(30))
                  .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                  .build());
      try {
        WorkflowClient.start(stub::execute, input);
      } catch (WorkflowExecutionAlreadyStarted ignored) {
      }
    }
    results.jdbc().update("UPDATE control.runs SET dispatch_state='dispatched' WHERE id=?", id);
    if (Boolean.TRUE.equals(
        results
            .jdbc()
            .queryForObject(
                "SELECT cancel_requested FROM control.runs WHERE id=?", Boolean.class, id)))
      signalCancellation(id);
  }

  @Scheduled(fixedDelayString = "${heimdall.dispatch-delay-ms:5000}")
  @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(
      name = "run-dispatch-recovery",
      lockAtMostFor = "PT5M",
      lockAtLeastFor = "PT1S")
  public void retryDispatch() {
    net.javacrumbs.shedlock.core.LockAssert.assertLocked();
    var ids =
        results
            .jdbc()
            .query(
                "SELECT id FROM control.runs WHERE dispatch_state='pending' OR (cancel_requested AND status IN ('queued','running','waiting')) ORDER BY created_at LIMIT 100",
                (rs, n) -> rs.getString(1));
    for (var id : ids)
      try {
        dispatch(id);
      } catch (Exception ignored) {
      }
  }

  public void cancel(Identity identity, String id) {
    var run = results.get(id);
    var snapshot = config.active();
    for (var r : run.outcomes())
      auth.require(identity, "cancel", r.monitorRef(), run.environment(), r.location(), snapshot);
    tx.executeWithoutResult(
        s -> {
          results.jdbc().update("UPDATE control.runs SET cancel_requested=TRUE WHERE id=?", id);
          results.audit(identity.subject(), "run.cancel", id);
        });
    signalCancellation(id);
  }

  private void signalCancellation(String id) {
    for (var r : results.get(id).outcomes()) {
      if (r.status().terminal()) continue;
      String workflow = ExecutionIds.workflowId(id, r.monitorRef(), r.location());
      try {
        temporal.newWorkflowStub(ApiWorkflow.class, workflow).cancelRun();
      } catch (WorkflowNotFoundException ignored) {
        // Admission may be committed before dispatch. The durable request is retried after start.
      } catch (WorkflowServiceException e) {
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Run {} awaits cancellation retry", id);
      }
    }
  }

  public RunView get(Identity identity, String id) {
    var run = results.get(id);
    for (var r : run.outcomes())
      auth.require(
          identity, "read", r.monitorRef(), run.environment(), r.location(), config.active());
    return run;
  }

  public boolean readable(Identity identity, RunView run) {
    try {
      get(identity, run.id());
      return true;
    } catch (SecurityException e) {
      return false;
    }
  }

  @Override
  public List<RunInput> admit(
      String monitor, String environment, String generation, String occurrence) {
    var snapshot = config.active();
    if (!snapshot.monitors().containsKey(monitor)
        || !generation.equals(ConfigurationRepository.generation(snapshot, monitor, environment))) {
      results.audit("scheduler", "schedule.skipped_stale", monitor);
      return List.of();
    }
    var identity = new Identity("scheduler", Map.of());
    var request = new StartRun(List.of(monitor), environment, List.of(), Map.of(), null);
    String id = create(identity, request, occurrence, "schedule");
    // An activity retry retains the snapshot from first admission, even across reconciliation.
    var pinned =
        Json.read(
            results
                .jdbc()
                .queryForObject("SELECT snapshot FROM control.runs WHERE id=?", String.class, id),
            Snapshot.class);
    return resolve(identity, request, pinned, id, "schedule");
  }

  public static String workflowId(RunInput input) {
    return ExecutionIds.workflowId(input);
  }
}
