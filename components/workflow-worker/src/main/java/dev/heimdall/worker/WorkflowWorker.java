package dev.heimdall.worker;

import dev.heimdall.results.KafkaPublication;
import dev.heimdall.workflow.*;
import io.temporal.client.*;
import io.temporal.serviceclient.*;
import io.temporal.worker.*;

public final class WorkflowWorker {
  public static void main(String[] args) {
    var service =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget(System.getenv().getOrDefault("TEMPORAL_ADDRESS", "localhost:7233"))
                .build());
    var client =
        WorkflowClient.newInstance(
            service,
            WorkflowClientOptions.newBuilder()
                .setNamespace(System.getenv().getOrDefault("TEMPORAL_NAMESPACE", "default"))
                .build());
    var factory = WorkerFactory.newInstance(client);
    factory
        .newWorker("workflow")
        .registerWorkflowImplementationTypes(
            ApiWorkflowImpl.class, dev.heimdall.workflow.ScheduledWorkflowImpl.class);
    factory
        .newWorker("publication")
        .registerActivitiesImplementations(
            new KafkaPublication(
                System.getenv().getOrDefault("KAFKA_BOOTSTRAP", "localhost:9092")));
    factory.start();
    Runtime.getRuntime().addShutdownHook(new Thread(factory::shutdown));
  }
}
