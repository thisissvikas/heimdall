package dev.heimdall.workflow;

import dev.heimdall.contracts.Execution.*;
import io.temporal.activity.*;
import java.util.List;

@ActivityInterface
public interface AdmissionActivities {
  @ActivityMethod
  List<RunInput> admit(String monitor, String environment, String generation, String occurrenceId);
}
