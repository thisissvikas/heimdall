package dev.heimdall.workflow;

import static org.junit.jupiter.api.Assertions.*;

import dev.heimdall.contracts.Execution.*;
import dev.heimdall.testing.Fixtures;
import io.temporal.client.*;
import io.temporal.testing.*;
import io.temporal.worker.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

@Tag("integration")
@Timeout(value = 45, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class WorkflowIntegrationTest {
  TestWorkflowEnvironment env;
  final List<String> calls = new CopyOnWriteArrayList<>();
  final List<ResultEvent> events = new CopyOnWriteArrayList<>();
  final AtomicInteger publications = new AtomicInteger();
  RunnerActivities runner =
      new RunnerActivities() {
        @Override
        public StepOutcome execute(StepCommand c) {
          calls.add(c.step().id());
          if (c.step().id().equals("fail"))
            return new StepOutcome(false, true, true, null, "Primary failure");
          if (c.step().type().equals("poll"))
            return new StepOutcome(true, c.attempt() >= 3, false, null, null);
          if (c.step().type().equals("condition"))
            return new StepOutcome(true, true, false, "end", null);
          return StepOutcome.ok();
        }

        @Override
        public void fork(RunInput input, String parent, String branch) {
          calls.add("fork");
        }

        @Override
        public void merge(RunInput input, String parent, String branch, List<String> outputs) {
          calls.add("merge");
        }
      };

  @BeforeEach
  void setup() {
    env =
        TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkerFactoryOptions(
                    WorkerFactoryOptions.newBuilder().setWorkflowCacheSize(0).build())
                .build());
    register(env.getWorkerFactory());
    env.start();
  }

  void register(WorkerFactory factory) {
    factory.newWorker("workflow").registerWorkflowImplementationTypes(ApiWorkflowImpl.class);
    factory.newWorker("http-local").registerActivitiesImplementations(runner);
    factory
        .newWorker("publication")
        .registerActivitiesImplementations(
            (PublicationActivities)
                event -> {
                  if (publications.incrementAndGet() <= 2)
                    throw new IllegalStateException("Transient publication failure");
                  events.add(event);
                });
  }

  @AfterEach
  void close() {
    env.close();
  }

  ApiWorkflow stub(String id) {
    return env.getWorkflowClient()
        .newWorkflowStub(
            ApiWorkflow.class,
            WorkflowOptions.newBuilder().setWorkflowId(id).setTaskQueue("workflow").build());
  }

  @Test
  void twentyMinuteWaitUsesDurableTimerAndSupportsHistoryReplay() throws Exception {
    var input =
        Fixtures.input(
            Fixtures.monitor(
                List.of(
                    Fixtures.step("{\"id\":\"create\",\"type\":\"http\"}"),
                    Fixtures.step("{\"id\":\"wait\",\"type\":\"wait\",\"duration\":\"20m\"}"),
                    Fixtures.step("{\"id\":\"check\",\"type\":\"http\"}")),
                List.of()));
    var workflow = stub("durable-replacement");
    var execution = WorkflowClient.start(workflow::execute, input);
    env.sleep(Duration.ofMinutes(5));
    assertTrue(
        env.getWorkflowClient().fetchHistory(execution.getWorkflowId()).getEvents().stream()
            .anyMatch(
                e ->
                    e.hasTimerStartedEventAttributes()
                        && e.getTimerStartedEventAttributes().getStartToFireTimeout().getSeconds()
                            == 1200));
    var result = WorkflowStub.fromTyped(workflow).getResult(RegionalResult.class);
    assertEquals(Status.passed, result.status());
    assertEquals(List.of("create", "check"), calls);
    WorkflowReplayer.replayWorkflowExecution(
        env.getWorkflowClient().fetchHistory(execution.getWorkflowId()), ApiWorkflowImpl.class);
    assertTrue(events.stream().anyMatch(e -> e.status() == Status.waiting));
    assertEquals(2, calls.size()); // publication retries did not call the target again
  }

  @Test
  void pollsWithDurableTimersAndHonorsTimeout() {
    var poll =
        Fixtures.step(
            "{\"id\":\"poll\",\"type\":\"poll\",\"interval\":\"6m\",\"timeout\":\"20m\"}");
    var result =
        stub("polling").execute(Fixtures.input(Fixtures.monitor(List.of(poll), List.of())));
    assertEquals(Status.passed, result.status());
    assertEquals(List.of("poll", "poll", "poll"), calls);
    var timeout =
        Fixtures.step("{\"id\":\"poll\",\"type\":\"poll\",\"interval\":\"6m\",\"timeout\":\"5m\"}");
    result =
        stub("poll-timeout").execute(Fixtures.input(Fixtures.monitor(List.of(timeout), List.of())));
    assertEquals(Status.timed_out, result.status());
  }

  @Test
  void cancellationRunsCleanupAndPrimaryFailureIsPreserved() {
    var cleanup = List.of(Fixtures.step("{\"id\":\"cleanup\",\"type\":\"http\"}"));
    var input =
        Fixtures.input(
            Fixtures.monitor(
                List.of(Fixtures.step("{\"id\":\"wait\",\"type\":\"wait\",\"duration\":\"20m\"}")),
                cleanup));
    var workflow = stub("cancel-cleanup");
    WorkflowClient.start(workflow::execute, input);
    env.sleep(Duration.ofMinutes(1));
    workflow.cancelRun();
    var result = WorkflowStub.fromTyped(workflow).getResult(RegionalResult.class);
    assertEquals(Status.cancelled, result.status());
    assertTrue(calls.contains("cleanup"));
    result =
        stub("failure-cleanup")
            .execute(
                Fixtures.input(
                    Fixtures.monitor(
                        List.of(Fixtures.step("{\"id\":\"fail\",\"type\":\"http\"}")), cleanup)));
    assertEquals(Status.failed, result.status());
    assertEquals("Primary failure", result.error());
  }

  @Test
  void parallelBranchesJoinAndMergeAndConditionsSelectTransitions() {
    var parallel =
        Fixtures.step(
            "{\"id\":\"parallel\",\"type\":\"parallel\",\"branches\":[{\"id\":\"a\",\"steps\":[{\"id\":\"a\",\"type\":\"set\"}],\"outputs\":[\"a\"]},{\"id\":\"b\",\"steps\":[{\"id\":\"b\",\"type\":\"set\"}],\"outputs\":[\"b\"]}]}");
    var condition = Fixtures.step("{\"id\":\"branch\",\"type\":\"condition\",\"onTrue\":\"end\"}");
    var steps =
        List.of(
            parallel,
            condition,
            Fixtures.step("{\"id\":\"skipped\",\"type\":\"http\"}"),
            Fixtures.step("{\"id\":\"end\",\"type\":\"end\"}"));
    assertEquals(
        Status.passed,
        stub("parallel").execute(Fixtures.input(Fixtures.monitor(steps, List.of()))).status());
    assertEquals(2, Collections.frequency(calls, "fork"));
    assertEquals(2, Collections.frequency(calls, "merge"));
    assertFalse(calls.contains("skipped"));
  }
}
