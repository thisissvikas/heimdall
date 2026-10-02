package dev.heimdall.contracts;

import dev.heimdall.contracts.Execution.RunInput;

/** Stable identifiers shared by admission, child workflows, cancellation and result replay. */
public final class ExecutionIds {
  private ExecutionIds() {}

  public static String regionalKey(String runId, String monitor, String location) {
    return "%s/%s/%s".formatted(runId, monitor, location);
  }

  public static String workflowId(String runId, String monitor, String location) {
    String digest = Json.sha256(regionalKey(runId, monitor, location)).substring(0, 16);
    return "api-%s-%s".formatted(runId, digest);
  }

  public static String workflowId(RunInput input) {
    return workflowId(input.id(), input.monitorRef(), input.location());
  }
}
