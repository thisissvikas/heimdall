package dev.heimdall.contracts;

import static dev.heimdall.contracts.Definitions.*;

import java.time.Instant;
import java.util.*;

public final class Execution {
  private Execution() {}

  public enum Status {
    queued,
    running,
    waiting,
    passed,
    failed,
    timed_out,
    cancelled,
    error;

    public boolean terminal() {
      return ordinal() >= passed.ordinal();
    }
  }

  public record StartRun(
      List<String> references,
      String environment,
      List<String> locations,
      Map<String, Object> parameters,
      Deployment deployment) {
    public StartRun {
      references = list(references);
      locations = list(locations);
      parameters = map(parameters);
    }
  }

  public record Deployment(String commit, String artifactDigest, String repository) {}

  public record RunInput(
      String id,
      String monitorRef,
      String environment,
      String location,
      Snapshot snapshot,
      Map<String, Object> parameters,
      String subject,
      String trigger,
      Deployment deployment) {
    public RunInput {
      parameters = map(parameters);
    }

    public Monitor monitor() {
      return snapshot.monitors().get(monitorRef);
    }

    public String key() {
      return ExecutionIds.regionalKey(id, monitorRef, location);
    }
  }

  public record StepCommand(
      RunInput run, Step step, String branch, String instanceId, int attempt) {}

  public record StepOutcome(
      boolean success, boolean complete, boolean terminalFailure, String next, String error) {
    public static StepOutcome ok() {
      return new StepOutcome(true, true, false, null, null);
    }
  }

  public record AssertionResult(String message, boolean passed) {}

  public record Attempt(
      String id,
      String runId,
      String monitorRef,
      String location,
      String stepId,
      int attempt,
      Instant startedAt,
      Integer statusCode,
      String classification,
      boolean success,
      Map<String, Long> timing,
      List<AssertionResult> assertions,
      String error,
      boolean recovered) {
    public Attempt {
      timing = map(timing);
      assertions = list(assertions);
    }
  }

  public record ResultEvent(
      String eventId,
      String runId,
      String monitorRef,
      String location,
      long sequence,
      Instant partitionTime,
      Status status,
      String cleanup,
      String error,
      String trigger) {}

  public record RegionalResult(
      String monitorRef,
      String location,
      Status status,
      String cleanup,
      String error,
      long sequence) {}

  public record RunView(
      String id,
      String subject,
      Instant createdAt,
      String environment,
      String configCommit,
      String bundleDigest,
      Status status,
      String publication,
      List<RegionalResult> outcomes,
      Deployment deployment) {
    public RunView {
      outcomes = list(outcomes);
    }
  }

  public record Page<T>(List<T> items, String nextCursor) {
    public Page {
      items = list(items);
    }
  }

  public record SyncStatus(
      String desiredCommit,
      String activeConfigurationCommit,
      String schedulesAppliedCommit,
      String status,
      String lastError,
      Instant lastSuccessfulSync) {}

  public record AuditEvent(
      String id, Instant timestamp, String subject, String action, String resource) {}
}
