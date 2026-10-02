package dev.heimdall.workflow;

import io.temporal.workflow.*;

@WorkflowInterface
public interface ScheduledWorkflow {
  @WorkflowMethod
  void execute(String monitor, String environment, String generation);
}
