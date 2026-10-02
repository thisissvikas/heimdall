package dev.heimdall.workflow;

import static dev.heimdall.contracts.Definitions.duration;

import dev.heimdall.contracts.Definitions.*;
import dev.heimdall.contracts.Execution.*;
import io.temporal.activity.*;
import io.temporal.common.RetryOptions;
import io.temporal.failure.*;
import io.temporal.workflow.*;
import java.time.*;
import java.util.*;

/** Deterministic interpreter. Sensitive state and all external I/O remain in activities. */
public final class ApiWorkflowImpl implements ApiWorkflow {
  private RunInput input;
  private RunnerActivities runner;
  private PublicationActivities publication;
  private CancellationScope scope;
  private boolean cancelled;
  private int executions;
  private int cleanupExecutions;
  private boolean cleaningUp;
  private long sequence;
  private long deadline;
  private Instant partitionTime;

  @Override
  public RegionalResult execute(RunInput input) {
    this.input = input;
    partitionTime = Instant.ofEpochMilli(Workflow.currentTimeMillis());
    deadline = Workflow.currentTimeMillis() + duration(input.monitor().timeout()).toMillis();
    runner =
        Workflow.newActivityStub(
            RunnerActivities.class,
            ActivityOptions.newBuilder()
                .setTaskQueue("http-%s".formatted(input.location()))
                .setStartToCloseTimeout(Duration.ofMinutes(15))
                .setScheduleToCloseTimeout(Duration.ofMinutes(20))
                .setHeartbeatTimeout(Duration.ofSeconds(10))
                .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .build());
    publication =
        Workflow.newActivityStub(
            PublicationActivities.class,
            ActivityOptions.newBuilder()
                .setTaskQueue("publication")
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .setRetryOptions(
                    RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofSeconds(1))
                        .setMaximumInterval(Duration.ofMinutes(1))
                        .build())
                .build());
    Status[] status = {Status.passed};
    String[] error = {null};
    String[] cleanup = {"passed"};
    emit(Status.running, null, null);
    try {
      scope = Workflow.newCancellationScope(() -> executeSteps(input.monitor().steps(), "main"));
      if (cancelled) scope.cancel();
      Promise<Void> work = Async.procedure(scope::run);
      if (!Workflow.await(duration(input.monitor().timeout()), work::isCompleted)) {
        scope.cancel();
        try {
          work.get();
        } catch (CanceledFailure ignored) {
        }
        throw new WorkflowProblem(Status.timed_out, "Execution deadline exceeded");
      }
      work.get();
    } catch (CanceledFailure e) {
      status[0] = Status.cancelled;
      error[0] = "Cancellation requested";
    } catch (WorkflowProblem e) {
      status[0] = e.status;
      error[0] = e.getMessage();
    } catch (ActivityFailure e) {
      status[0] = Status.error;
      error[0] = "Runner activity failed; inspect attempts";
    } catch (Exception e) {
      status[0] = Status.error;
      error[0] = "Workflow execution error";
    }
    if (cancelled) {
      status[0] = Status.cancelled;
      error[0] = "Cancellation requested";
    }
    long previousDeadline = deadline;
    Workflow.newDetachedCancellationScope(
            () -> {
              deadline = Workflow.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
              cleaningUp = true;
              try {
                var cleanupScope =
                    Workflow.newCancellationScope(
                        () -> executeSteps(input.monitor().cleanup(), "main"));
                Promise<Void> work = Async.procedure(cleanupScope::run);
                if (!Workflow.await(Duration.ofSeconds(30), work::isCompleted)) {
                  cleanupScope.cancel();
                  cleanup[0] = "failed";
                } else work.get();
              } catch (Exception e) {
                cleanup[0] = "failed";
              }
            })
        .run();
    deadline = previousDeadline;
    if (cleanup[0].equals("failed") && status[0] == Status.passed) {
      status[0] = Status.failed;
      error[0] = "Cleanup failed";
    }
    Status finalStatus = status[0];
    Workflow.newDetachedCancellationScope(() -> emit(finalStatus, cleanup[0], error[0])).run();
    return new RegionalResult(
        input.monitorRef(), input.location(), finalStatus, cleanup[0], error[0], sequence);
  }

  private void executeSteps(List<Step> steps, String branch) {
    var index = new HashMap<String, Integer>();
    for (int i = 0; i < steps.size(); i++) index.put(steps.get(i).id(), i);
    var visits = new HashMap<String, Integer>();
    int position = 0;
    while (position < steps.size()) {
      checkDeadline();
      if (cleaningUp ? ++cleanupExecutions > 100 : ++executions > input.monitor().maxExecutions())
        throw new WorkflowProblem(Status.failed, "Step execution budget exhausted");
      Step step = steps.get(position);
      int visit = visits.merge(step.id(), 1, Integer::sum);
      if (step.maxVisits() > 0 && visit > step.maxVisits())
        throw new WorkflowProblem(Status.failed, "Step visit bound exhausted");
      String instance =
          "%s:%s:%s:%s"
              .formatted(
                  cleaningUp ? "cleanup" : "step",
                  branch,
                  step.id(),
                  cleaningUp ? cleanupExecutions : executions);
      String next = step.next();
      switch (step.type()) {
        case "wait" -> sleep(duration(step.duration()));
        case "end" -> {
          return;
        }
        case "parallel" -> {
          var promises = new ArrayList<Promise<Void>>();
          for (var b : step.branches()) {
            String child = "%s/%s".formatted(instance, b.id());
            runner.fork(input, branch, child);
            promises.add(Async.procedure(() -> executeSteps(b.steps(), child)));
          }
          Promise.allOf(promises).get();
          for (var b : step.branches())
            runner.merge(input, branch, "%s/%s".formatted(instance, b.id()), b.outputs());
        }
        case "poll" -> {
          long pollDeadline =
              Math.min(
                  deadline, Workflow.currentTimeMillis() + duration(step.timeout()).toMillis());
          int attempt = 0;
          while (true) {
            checkDeadline();
            if (++attempt > 1 && ++executions > input.monitor().maxExecutions())
              throw new WorkflowProblem(Status.failed, "Poll attempt budget exhausted");
            StepOutcome o = runner.execute(new StepCommand(input, step, branch, instance, attempt));
            if (o.terminalFailure()) throw new WorkflowProblem(Status.failed, o.error());
            if (o.success() && o.complete()) break;
            long remaining = pollDeadline - Workflow.currentTimeMillis();
            if (remaining <= 0)
              throw new WorkflowProblem(Status.timed_out, "Polling deadline exceeded");
            sleep(Duration.ofMillis(Math.min(duration(step.interval()).toMillis(), remaining)));
          }
        }
        default -> {
          int maximum = step.retry() == null ? 1 : step.retry().maxAttempts();
          for (int attempt = 1; attempt <= maximum; attempt++) {
            checkDeadline();
            StepOutcome o = runner.execute(new StepCommand(input, step, branch, instance, attempt));
            if (o.success()) {
              if (o.next() != null) next = o.next();
              break;
            }
            if (o.terminalFailure() || attempt == maximum)
              throw new WorkflowProblem(Status.failed, o.error());
            sleep(
                step.retry().backoff() == null
                    ? Duration.ofSeconds(1)
                    : duration(step.retry().backoff()));
          }
        }
      }
      checkDeadline();
      position = next == null ? position + 1 : index.get(next);
    }
  }

  private void sleep(Duration wait) {
    checkDeadline();
    long remaining = deadline - Workflow.currentTimeMillis();
    emit(Status.waiting, null, null);
    Workflow.sleep(Duration.ofMillis(Math.min(wait.toMillis(), remaining)));
    checkDeadline();
    emit(Status.running, null, null);
  }

  private void checkDeadline() {
    if (Workflow.currentTimeMillis() >= deadline)
      throw new WorkflowProblem(Status.timed_out, "Execution deadline exceeded");
  }

  private void emit(Status status, String cleanup, String error) {
    long s = ++sequence;
    publication.publish(
        new ResultEvent(
            "%s:%s".formatted(input.key(), s),
            input.id(),
            input.monitorRef(),
            input.location(),
            s,
            partitionTime,
            status,
            cleanup,
            error,
            input.trigger()));
  }

  @Override
  public void cancelRun() {
    cancelled = true;
    if (scope != null) scope.cancel();
  }

  private static final class WorkflowProblem extends RuntimeException {
    final Status status;

    WorkflowProblem(Status status, String message) {
      super(message);
      this.status = status;
    }
  }
}
