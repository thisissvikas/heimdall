package dev.heimdall.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.*;
import java.time.Duration;
import java.util.*;

public final class ScheduledWorkflowImpl implements ScheduledWorkflow {
  @Override
  public void execute(String monitor, String environment, String generation) {
    var admission =
        Workflow.newActivityStub(
            AdmissionActivities.class,
            ActivityOptions.newBuilder()
                .setTaskQueue("admission")
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .build());
    var inputs =
        admission.admit(monitor, environment, generation, Workflow.getInfo().getWorkflowId());
    var promises = new ArrayList<Promise<dev.heimdall.contracts.Execution.RegionalResult>>();
    for (var input : inputs) {
      var child =
          Workflow.newChildWorkflowStub(
              ApiWorkflow.class,
              ChildWorkflowOptions.newBuilder()
                  .setTaskQueue("workflow")
                  .setWorkflowId(dev.heimdall.contracts.ExecutionIds.workflowId(input))
                  .build());
      promises.add(Async.function(child::execute, input));
    }
    Promise.allOf(promises).get();
  }
}
