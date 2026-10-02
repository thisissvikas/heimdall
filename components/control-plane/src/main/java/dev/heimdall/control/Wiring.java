package dev.heimdall.control;

import dev.heimdall.providers.*;
import dev.heimdall.providers.Providers.*;
import dev.heimdall.results.*;
import io.temporal.client.*;
import io.temporal.client.schedules.*;
import io.temporal.serviceclient.*;
import io.temporal.worker.*;
import java.nio.file.Path;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
public class Wiring {
  @Bean(destroyMethod = "close")
  S3Artifacts artifactStorage(Environment env) {
    String endpoint = env.getProperty("heimdall.s3-endpoint");
    return new S3Artifacts(
        endpoint == null ? null : java.net.URI.create(endpoint),
        env.getProperty("heimdall.s3-region", "us-east-1"),
        env.getProperty("heimdall.s3-bucket", "heimdall"),
        env.getRequiredProperty("heimdall.state-key"));
  }

  @Bean
  dev.heimdall.results.DiagnosticArtifacts diagnosticArtifacts(
      JdbcResults results, S3Artifacts storage) {
    return new dev.heimdall.results.DiagnosticArtifacts(results, storage);
  }

  @Bean
  Retention retention(JdbcResults results) {
    return new Retention(results);
  }

  @Bean
  net.javacrumbs.shedlock.core.LockProvider lockProvider(DataSource ds) {
    return new net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider(
        net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider.Configuration
            .builder()
            .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(ds))
            .withTableName("control.shedlock")
            .usingDbTime()
            .build());
  }

  @Bean(destroyMethod = "close")
  DataSource dataSource(Environment env) {
    var ds =
        Database.connect(
            env.getProperty("heimdall.jdbc-url", "jdbc:postgresql://localhost:5432/heimdall"),
            env.getProperty("heimdall.db-user", "heimdall"),
            env.getProperty("heimdall.db-password", "heimdall-local"),
            4);
    Database.migrate(ds);
    return ds;
  }

  @Bean
  JdbcResults results(DataSource ds) {
    return new JdbcResults(ds);
  }

  @Bean
  ConfigurationRepository configurations(DataSource ds) {
    return new ConfigurationRepository(ds);
  }

  @Bean
  Authorization authorization(Environment env) {
    String endpoint = env.getProperty("heimdall.opa-url");
    return endpoint == null ? new Rbac() : new OpaAuthorization(java.net.URI.create(endpoint));
  }

  @Bean(destroyMethod = "shutdown")
  WorkflowServiceStubs temporalService(Environment env) {
    return WorkflowServiceStubs.newServiceStubs(
        WorkflowServiceStubsOptions.newBuilder()
            .setTarget(env.getProperty("heimdall.temporal-address", "localhost:7233"))
            .setEnableHttps(env.getProperty("heimdall.temporal-tls", Boolean.class, false))
            .build());
  }

  @Bean
  WorkflowClient temporalClient(WorkflowServiceStubs service, Environment env) {
    return WorkflowClient.newInstance(
        service,
        WorkflowClientOptions.newBuilder()
            .setNamespace(env.getProperty("heimdall.temporal-namespace", "default"))
            .build());
  }

  @Bean
  ScheduleSynchronizer schedules(WorkflowServiceStubs service, Environment env) {
    return new TemporalSchedules(
        ScheduleClient.newInstance(
            service,
            ScheduleClientOptions.newBuilder()
                .setNamespace(env.getProperty("heimdall.temporal-namespace", "default"))
                .build()));
  }

  @Bean
  GitSource gitSource(Environment env) {
    if (Arrays.asList(env.getActiveProfiles()).contains("local")) {
      Path root = Path.of(env.getProperty("heimdall.local-root", "."));
      return new GitSource.LocalTree(root);
    }
    return new GitSource.ProtectedBranch(
        Path.of(env.getProperty("heimdall.git-checkout", ".local/approved")),
        env.getRequiredProperty("heimdall.git-url"),
        env.getProperty("heimdall.git-branch", "main"));
  }

  @Bean
  Reconciler reconciler(
      GitSource source, ConfigurationRepository repo, ScheduleSynchronizer schedules) {
    return new Reconciler(source, repo, schedules);
  }

  @Bean
  RunService runs(
      DataSource ds,
      JdbcResults results,
      ConfigurationRepository repo,
      Authorization auth,
      WorkflowClient client) {
    return new RunService(ds, results, repo, auth, client);
  }

  @Bean(initMethod = "start", destroyMethod = "shutdown")
  WorkerFactory admissionWorker(WorkflowClient client, RunService runs) {
    var factory = WorkerFactory.newInstance(client);
    factory.newWorker("admission").registerActivitiesImplementations(runs);
    return factory;
  }
}
