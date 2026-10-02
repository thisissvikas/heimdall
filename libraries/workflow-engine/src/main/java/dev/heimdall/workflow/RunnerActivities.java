package dev.heimdall.workflow;

import dev.heimdall.contracts.Definitions.*;
import dev.heimdall.contracts.Execution.*;
import io.temporal.activity.*;
import java.util.List;

@ActivityInterface
public interface RunnerActivities {
  @ActivityMethod
  StepOutcome execute(StepCommand command);

  @ActivityMethod
  void fork(RunInput run, String parent, String branch);

  @ActivityMethod
  void merge(RunInput run, String parent, String branch, List<String> outputs);
}
