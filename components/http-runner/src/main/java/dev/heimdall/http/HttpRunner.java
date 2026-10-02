package dev.heimdall.http;

import dev.heimdall.providers.VaultSecrets;
import dev.heimdall.results.*;
import dev.heimdall.scripts.ScriptSandbox;
import io.temporal.client.*;
import io.temporal.serviceclient.*;
import io.temporal.worker.*;

public final class HttpRunner {
  public static void main(String[] args) {
    var ds = Database.connect();
    var state = new EncryptedState(ds, System.getenv("STATE_KEY"));
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
    var artifacts =
        new dev.heimdall.providers.S3Artifacts(
            java.net.URI.create(
                System.getenv().getOrDefault("S3_ENDPOINT", "http://localhost:9000")),
            System.getenv().getOrDefault("S3_REGION", "us-east-1"),
            System.getenv().getOrDefault("S3_BUCKET", "heimdall"),
            System.getenv("STATE_KEY"));
    var results = new JdbcResults(ds);
    var sandbox =
        "local".equals(System.getenv("SCRIPT_MODE"))
            ? ScriptSandbox.local(
                java.nio.file.Path.of(System.getenv().getOrDefault("HEIMDALL_ROOT", ".")))
            : ScriptSandbox.production();
    factory
        .newWorker("http-%s".formatted(System.getenv().getOrDefault("LOCATION", "local")))
        .registerActivitiesImplementations(
            new HttpActivities(
                state,
                results,
                new VaultSecrets(),
                sandbox,
                new DiagnosticArtifacts(results, artifacts)));
    factory.start();
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  factory.shutdown();
                  artifacts.close();
                  ds.close();
                }));
  }
}
