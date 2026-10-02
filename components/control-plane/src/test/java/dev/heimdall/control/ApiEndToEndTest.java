package dev.heimdall.control;

import static org.junit.jupiter.api.Assertions.*;

import dev.heimdall.contracts.*;
import dev.heimdall.contracts.Definitions.*;
import dev.heimdall.contracts.Execution.*;
import dev.heimdall.http.HttpActivities;
import dev.heimdall.providers.*;
import dev.heimdall.providers.Providers.*;
import dev.heimdall.results.*;
import dev.heimdall.scripts.ScriptSandbox;
import dev.heimdall.testing.Fixtures;
import dev.heimdall.workflow.*;
import io.temporal.client.*;
import io.temporal.testing.TestWorkflowEnvironment;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiEndToEndTest {
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17.6-alpine");
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");
  static com.zaxxer.hikari.HikariDataSource ds;
  static TestWorkflowEnvironment temporal;
  static JdbcResults results;
  static ConfigurationRepository configs;
  static DiagnosticArtifacts diagnostics;
  org.springframework.context.ConfigurableApplicationContext app;
  MockWebServer target;
  MockWebServer vault;
  ResultConsumer consumer;
  Thread consumerThread;
  KafkaPublication publisher;
  final HttpClient http = HttpClient.newHttpClient();
  String base;
  final AtomicInteger creates = new AtomicInteger(),
      deletes = new AtomicInteger(),
      polls = new AtomicInteger();

  @BeforeAll
  void setup() throws Exception {
    PG.start();
    KAFKA.start();
    ds = Database.connect(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(), 8);
    Database.migrate(ds);
    results = new JdbcResults(ds);
    var objects = new ConcurrentHashMap<String, byte[]>();
    diagnostics =
        new DiagnosticArtifacts(
            results,
            new Artifacts() {
              public void put(String key, byte[] bytes) {
                objects.put(key, bytes.clone());
              }

              public byte[] get(String key) {
                return objects.get(key).clone();
              }
            });
    configs = new ConfigurationRepository(ds);
    target = new MockWebServer();
    target.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) {
            String path = request.getPath();
            if (path.equals("/oauth/token")) {
              assertTrue(
                  request.getBody().readUtf8().contains("client_secret=fixture-client-secret"));
              return new MockResponse().setBody("{\"access_token\":\"fixture-token\"}");
            }
            if (!"Bearer fixture-token".equals(request.getHeader("Authorization")))
              return new MockResponse().setResponseCode(401);
            if (request.getMethod().equals("POST")) {
              assertEquals("fixture-probe-key", request.getHeader("X-Probe-Key"));
              if (request.getBody().readUtf8().contains("failure-diagnostics"))
                return new MockResponse()
                    .setResponseCode(500)
                    .setBody("{\"private\":\"sensitive-target-response\"}");
              creates.incrementAndGet();
              return new MockResponse().setResponseCode(202).setBody("{\"id\":\"job-1\"}");
            }
            if (request.getMethod().equals("DELETE")) {
              deletes.incrementAndGet();
              return new MockResponse().setResponseCode(204);
            }
            if (path.endsWith("/items"))
              return new MockResponse().setBody("{\"items\":[{\"requiredField\":\"present\"}]}");
            return new MockResponse()
                .setBody(
                    "{\"state\":\"%s\"}"
                        .formatted(polls.incrementAndGet() >= 2 ? "READY" : "PROCESSING"));
          }
        });
    target.start();
    vault = new MockWebServer();
    vault.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) {
            assertEquals("fixture-vault-token", request.getHeader("X-Vault-Token"));
            assertEquals("/v1/secret/data/payments/orders", request.getPath());
            return new MockResponse()
                .setBody(
                    "{\"data\":{\"data\":{\"clientId\":\"fixture-client\",\"clientSecret\":\"fixture-client-secret\",\"probeKey\":\"fixture-probe-key\"}}}");
          }
        });
    vault.start();
    var source =
        new dev.heimdall.configuration.Compiler()
            .compile(Path.of(System.getProperty("heimdall.root")), "e2e-approved");
    var envs = new LinkedHashMap<>(source.environments());
    envs.put(
        "payments/checkout/staging",
        new dev.heimdall.contracts.Definitions.Environment(
            Map.of("baseUrl", target.url("/").toString().replaceAll("/$", "")), false, null));
    var snapshot =
        new Snapshot(
            source.commit(),
            source.digest(),
            source.monitors(),
            envs,
            source.authentication(),
            source.scripts(),
            source.locations(),
            source.roles(),
            source.suites(),
            source.platform(),
            source.ciTrust(),
            source.alertPolicies());
    configs.desired(snapshot.commit());
    configs.activate(snapshot);
    configs.applied(snapshot.commit());
    temporal = TestWorkflowEnvironment.newInstance();
    temporal.newWorker("workflow").registerWorkflowImplementationTypes(ApiWorkflowImpl.class);
    temporal
        .newWorker("http-local")
        .registerActivitiesImplementations(
            new HttpActivities(
                new EncryptedState(ds, Base64.getEncoder().encodeToString(new byte[32])),
                results,
                new VaultSecrets(vault.url("/").toString(), "fixture-vault-token"),
                ScriptSandbox.local(Path.of(System.getProperty("heimdall.root"))),
                diagnostics));
    publisher = new KafkaPublication(KAFKA.getBootstrapServers());
    temporal.newWorker("publication").registerActivitiesImplementations(publisher);
    temporal.start();
    consumer = new ResultConsumer(KAFKA.getBootstrapServers(), "e2e-results", results);
    consumerThread = Thread.startVirtualThread(consumer);
    app =
        new SpringApplicationBuilder(TestApplication.class)
            .profiles("local")
            .properties(
                Map.of(
                    "server.port",
                    "0",
                    "spring.flyway.enabled",
                    "false",
                    "heimdall.local-auth-tokens",
                    "{\"runner-token\":\"dev-runner\",\"viewer-token\":\"dev-viewer\",\"admin-token\":\"dev-admin\"}",
                    "heimdall.webhook-secret",
                    "fixture-webhook-secret",
                    "heimdall.github-repository",
                    "example/heimdall"))
            .run(
                "--server.port=0",
                "--heimdall.webhook-secret=fixture-webhook-secret",
                "--heimdall.github-repository=example/heimdall");
    base =
        "http://localhost:%d"
            .formatted(((WebServerApplicationContext) app).getWebServer().getPort());
  }

  @AfterAll
  void teardown() throws Exception {
    if (app != null) app.close();
    if (consumer != null) consumer.close();
    if (consumerThread != null) consumerThread.join(5000);
    if (publisher != null) publisher.close();
    if (temporal != null) temporal.close();
    if (target != null) target.close();
    if (vault != null) vault.close();
    if (ds != null) ds.close();
    KAFKA.stop();
    PG.stop();
  }

  HttpResponse<String> request(String method, String path, String token, String body, String key)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json");
    if (token != null) builder.header("Authorization", "Bearer %s".formatted(token));
    if (key != null) builder.header("Idempotency-Key", key);
    return http.send(
        builder
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  String runBody() {
    return Json.write(
        new StartRun(
            List.of("payments/checkout/deployment"),
            "staging",
            List.of("local"),
            Map.of(),
            new Deployment("app-commit", "sha256:artifact", "example/app")));
  }

  @Test
  void apiToTemporalToHttpToKafkaToPostgresAndDeploymentGate() throws Exception {
    int createsBefore = creates.get(), deletesBefore = deletes.get();
    String key = UUID.randomUUID().toString();
    var response = request("POST", "/v1/runs", "runner-token", runBody(), key);
    assertEquals(202, response.statusCode(), response.body());
    String id = Json.MAPPER.readTree(response.body()).path("id").asText();
    var duplicate = request("POST", "/v1/runs", "runner-token", runBody(), key);
    assertEquals(id, Json.MAPPER.readTree(duplicate.body()).path("id").asText());
    String workflow = ExecutionIds.workflowId(id, Fixtures.MONITOR, "local");
    var result =
        temporal
            .getWorkflowClient()
            .newUntypedWorkflowStub(workflow)
            .getResult(RegionalResult.class);
    assertEquals(Status.passed, result.status(), result.error());
    var finalRun = awaitTerminal(id);
    assertEquals(Status.passed, finalRun.status());
    assertEquals("published", finalRun.publication());
    assertEquals("e2e-approved", finalRun.configCommit());
    assertEquals("app-commit", finalRun.deployment().commit());
    assertTrue(results.steps(id).size() >= 4);
    String publicSteps = Json.write(results.steps(id));
    String history = temporal.getWorkflowClient().fetchHistory(workflow).toJson(true);
    for (var secret :
        List.of(
            "fixture-token", "fixture-probe-key", "fixture-client-secret", "fixture-vault-token")) {
      assertFalse(publicSteps.contains(secret));
      assertFalse(history.contains(secret));
    }
    assertTrue(vault.getRequestCount() >= 3);
    assertEquals(1, creates.get() - createsBefore);
    assertEquals(1, deletes.get() - deletesBefore);
    assertEquals(
        200,
        request("GET", "/v1/runs/%s/steps".formatted(id), "viewer-token", null, null).statusCode());
    var mutation = request("POST", "/v1/monitors", "runner-token", "{}", null);
    assertEquals(404, mutation.statusCode());
  }

  @Test
  void failureArtifactsRequireSeparateAccessAndOmitSecretsAndRawBodies() throws Exception {
    var request =
        new StartRun(
            List.of(Fixtures.MONITOR),
            "staging",
            List.of("local"),
            Map.of("operation", "failure-diagnostics"),
            null);
    var admitted =
        request(
            "POST", "/v1/runs", "runner-token", Json.write(request), UUID.randomUUID().toString());
    assertEquals(202, admitted.statusCode());
    String id = Json.MAPPER.readTree(admitted.body()).path("id").asText();
    temporal
        .getWorkflowClient()
        .newUntypedWorkflowStub(ExecutionIds.workflowId(id, Fixtures.MONITOR, "local"))
        .getResult(RegionalResult.class);
    assertNotEquals(Status.passed, awaitTerminal(id).status());
    String path = "/v1/runs/%s/artifacts".formatted(id);
    assertEquals(403, request("GET", path, "viewer-token", null, null).statusCode());
    var artifacts = request("GET", path, "admin-token", null, null);
    assertEquals(200, artifacts.statusCode());
    var list = Json.MAPPER.readTree(artifacts.body());
    assertTrue(list.size() > 0);
    String artifact = list.get(0).path("id").asText();
    var content = request("GET", "%s/%s".formatted(path, artifact), "admin-token", null, null);
    assertEquals(200, content.statusCode());
    assertFalse(content.body().contains("sensitive-target-response"));
    assertFalse(content.body().contains("fixture-client-secret"));
    assertFalse(content.body().contains("fixture-probe-key"));
    assertEquals(id, Json.MAPPER.readTree(content.body()).path("runId").asText());
  }

  @Test
  void concurrentIdempotentSubmissionsCreateOneLogicalRunAcrossInstances() throws Exception {
    var service = app.getBean(RunService.class);
    var second = new RunService(ds, results, configs, new Rbac(), temporal.getWorkflowClient());
    var req = Json.read(runBody(), StartRun.class);
    var identity = new Identity("dev-runner", Map.of());
    String key = UUID.randomUUID().toString();
    try (var pool = Executors.newFixedThreadPool(4)) {
      var calls = new ArrayList<Future<String>>();
      for (int i = 0; i < 8; i++) {
        RunService instance = i % 2 == 0 ? service : second;
        calls.add(pool.submit(() -> instance.start(identity, req, key)));
      }
      var ids = new HashSet<String>();
      for (var f : calls) ids.add(f.get());
      assertEquals(1, ids.size());
      String id = ids.iterator().next();
      temporal
          .getWorkflowClient()
          .newUntypedWorkflowStub(ExecutionIds.workflowId(id, Fixtures.MONITOR, "local"))
          .getResult(RegionalResult.class);
      awaitTerminal(id);
    }
  }

  @Test
  void unauthorizedExecutionUnknownInputsAndUnavailableLocationsAreRejected() throws Exception {
    assertEquals(401, request("GET", "/v1/locations", null, null, null).statusCode());
    assertEquals(
        403, request("POST", "/v1/runs", "viewer-token", runBody(), "viewer").statusCode());
    assertEquals(
        400,
        request(
                "POST",
                "/v1/runs",
                "runner-token",
                "{\"references\":[\"payments/checkout/deployment\"],\"environment\":\"staging\",\"yaml\":\"unreviewed\"}",
                "yaml")
            .statusCode());
    var bad =
        new StartRun(List.of(Fixtures.MONITOR), "staging", List.of("unapproved"), Map.of(), null);
    assertEquals(
        400,
        request("POST", "/v1/runs", "runner-token", Json.write(bad), "bad-location").statusCode());
    assertEquals(
        403, request("GET", "/v1/status/reconciliation", "runner-token", null, null).statusCode());
    assertEquals(
        200, request("GET", "/v1/status/reconciliation", "admin-token", null, null).statusCode());
  }

  @Test
  void signedGithubPushNotificationsAreScopedAndDeduplicated() throws Exception {
    String delivery = UUID.randomUUID().toString();
    String body =
        "{\"repository\":{\"full_name\":\"example/heimdall\"},\"ref\":\"refs/heads/main\"}";
    var accepted = webhook(body, delivery, true);
    assertEquals(202, accepted.statusCode(), accepted.body());
    assertEquals(202, webhook(body, delivery, true).statusCode());
    assertEquals(
        1,
        results
            .jdbc()
            .queryForObject(
                "SELECT count(*) FROM control.webhook_deliveries WHERE id=?",
                Integer.class,
                delivery));
    assertEquals(403, webhook(body, UUID.randomUUID().toString(), false).statusCode());
    assertEquals(
        403,
        webhook(
                body.replace("example/heimdall", "attacker/heimdall"),
                UUID.randomUUID().toString(),
                true)
            .statusCode());
  }

  HttpResponse<String> webhook(String body, String delivery, boolean signed) throws Exception {
    var mac = javax.crypto.Mac.getInstance("HmacSHA256");
    mac.init(
        new javax.crypto.spec.SecretKeySpec(
            "fixture-webhook-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "HmacSHA256"));
    String signature =
        signed
            ? HexFormat.of()
                .formatHex(mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            : "0".repeat(64);
    return http.send(
        HttpRequest.newBuilder(URI.create("%s/v1/integrations/github/webhook".formatted(base)))
            .header("Content-Type", "application/json")
            .header("X-GitHub-Delivery", delivery)
            .header("X-GitHub-Event", "push")
            .header("X-Hub-Signature-256", "sha256=%s".formatted(signature))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void cancellationCommittedBeforeDispatchIsDeliveredAfterRecovery() throws Exception {
    String id = UUID.randomUUID().toString();
    var snapshot =
        Fixtures.snapshot(
            Fixtures.monitor(
                List.of(Fixtures.step("{\"id\":\"wait\",\"type\":\"wait\",\"duration\":\"20m\"}")),
                List.of()),
            "http://localhost");
    var request =
        new StartRun(List.of(Fixtures.MONITOR), "staging", List.of("local"), Map.of(), null);
    results.create(
        id,
        "dev-runner",
        request,
        snapshot,
        List.of(new RegionalResult(Fixtures.MONITOR, "local", Status.queued, null, null, 0)));
    var runs = app.getBean(RunService.class);
    runs.cancel(new Identity("dev-runner", Map.of()), id);
    assertTrue(
        results
            .jdbc()
            .queryForObject(
                "SELECT cancel_requested FROM control.runs WHERE id=?", Boolean.class, id));
    var recovered = new RunService(ds, results, configs, new Rbac(), temporal.getWorkflowClient());
    recovered.dispatch(id);
    var result =
        temporal
            .getWorkflowClient()
            .newUntypedWorkflowStub(ExecutionIds.workflowId(id, Fixtures.MONITOR, "local"))
            .getResult(RegionalResult.class);
    assertEquals(Status.cancelled, result.status());
    assertEquals("passed", result.cleanup());
    assertEquals(Status.cancelled, awaitTerminal(id).status());
  }

  RunView awaitTerminal(String id) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < end) {
      var run = results.get(id);
      if (run.status().terminal()) return run;
      Thread.sleep(100);
    }
    throw new AssertionError("Results were not published");
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import({Api.class, Security.class})
  static class TestApplication {
    @Bean
    DataSource dataSource() {
      return ds;
    }

    @Bean
    JdbcResults results() {
      return results;
    }

    @Bean
    DiagnosticArtifacts diagnostics() {
      return diagnostics;
    }

    @Bean
    ConfigurationRepository configs() {
      return configs;
    }

    @Bean
    Authorization authorization() {
      return new Rbac();
    }

    @Bean
    RunService runs() {
      return new RunService(ds, results, configs, new Rbac(), temporal.getWorkflowClient());
    }
  }
}
