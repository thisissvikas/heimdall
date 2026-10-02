package dev.heimdall.workflow;

import dev.heimdall.contracts.Execution.*;
import io.temporal.workflow.*;

@WorkflowInterface
public interface ApiWorkflow {
  @WorkflowMethod
  RegionalResult execute(RunInput input);

  @SignalMethod
  void cancelRun();
}
