package dev.heimdall.workflow;

import static org.junit.jupiter.api.Assertions.*;

import dev.heimdall.contracts.Execution.*;
import dev.heimdall.testing.Fixtures;
import io.temporal.client.*;
import io.temporal.serviceclient.*;
import io.temporal.worker.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

@Tag("integration")
@Timeout(120)
class WorkerRecoveryIntegrationTest {
  @Test
  void aNewWorkerProcessResumesPersistedTimersWithoutRepeatingCompletedRequests() throws Exception {
    try (var server =
        new GenericContainer<>("temporalio/temporal:1.9.1")
            .withCommand("server", "start-dev", "--ip", "0.0.0.0", "--log-level", "warn")
            .withExposedPorts(7233)
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofSeconds(90))) {
      server.start();
      var service =
          WorkflowServiceStubs.newServiceStubs(
              WorkflowServiceStubsOptions.newBuilder()
                  .setTarget("%s:%d".formatted(server.getHost(), server.getMappedPort(7233)))
                  .build());
      var calls = new CopyOnWriteArrayList<String>();
      RunnerActivities runner =
          new RunnerActivities() {
            public StepOutcome execute(StepCommand command) {
              calls.add(command.step().id());
              return StepOutcome.ok();
            }

            public void fork(RunInput i, String parent, String branch) {}

            public void merge(RunInput i, String parent, String branch, List<String> outputs) {}
          };
      var client =
          WorkflowClient.newInstance(
              service, WorkflowClientOptions.newBuilder().setIdentity("original").build());
      var first = worker(client, runner);
      first.start();
      var workflow =
          client.newWorkflowStub(
              ApiWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setWorkflowId("restart-%s".formatted(UUID.randomUUID()))
                  .setTaskQueue("workflow")
                  .build());
      var input =
          Fixtures.input(
              Fixtures.monitor(
                  List.of(
                      Fixtures.step("{\"id\":\"create\",\"type\":\"http\"}"),
                      Fixtures.step("{\"id\":\"wait\",\"type\":\"wait\",\"duration\":\"5s\"}"),
                      Fixtures.step("{\"id\":\"check\",\"type\":\"http\"}")),
                  List.of()));
      var execution = WorkflowClient.start(workflow::execute, input);
      long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (System.nanoTime() < end
          && client.fetchHistory(execution.getWorkflowId()).getEvents().stream()
              .noneMatch(
                  e ->
                      e.hasTimerStartedEventAttributes()
                          && e.getTimerStartedEventAttributes().getStartToFireTimeout().getSeconds()
                              == 5)) Thread.sleep(50);
      assertEquals(List.of("create"), calls);
      first.shutdownNow();
      first.awaitTermination(10, TimeUnit.SECONDS);
      assertTrue(first.isTerminated());
      var second =
          worker(
              WorkflowClient.newInstance(
                  service, WorkflowClientOptions.newBuilder().setIdentity("replacement").build()),
              runner);
      second.start();
      try {
        assertEquals(
            Status.passed,
            WorkflowStub.fromTyped(workflow)
                .getResult(30, TimeUnit.SECONDS, RegionalResult.class)
                .status());
        assertEquals(List.of("create", "check"), calls);
      } finally {
        second.shutdownNow();
        second.awaitTermination(10, TimeUnit.SECONDS);
        service.shutdown();
      }
    }
  }

  private static WorkerFactory worker(WorkflowClient client, RunnerActivities runner) {
    var factory =
        WorkerFactory.newInstance(
            client, WorkerFactoryOptions.newBuilder().setWorkflowCacheSize(0).build());
    factory.newWorker("workflow").registerWorkflowImplementationTypes(ApiWorkflowImpl.class);
    factory.newWorker("http-local").registerActivitiesImplementations(runner);
    factory
        .newWorker("publication")
        .registerActivitiesImplementations((PublicationActivities) event -> {});
    return factory;
  }
}
