package dev.heimdall.http;

import static org.junit.jupiter.api.Assertions.*;

import dev.heimdall.contracts.*;
import dev.heimdall.contracts.Definitions.*;
import dev.heimdall.contracts.Execution.*;
import dev.heimdall.providers.Providers.*;
import dev.heimdall.scripts.ScriptSandbox;
import dev.heimdall.testing.Fixtures;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;

@Tag("integration")
class HttpActivitiesIntegrationTest {
  MockWebServer server;
  MemoryState state;
  RecordingResults results;
  HttpActivities runner;

  @BeforeEach
  void setup() throws Exception {
    server = new MockWebServer();
    server.start();
    state = new MemoryState();
    results = new RecordingResults();
    runner =
        new HttpActivities(
            state,
            results,
            ref -> "very-secret",
            ScriptSandbox.local(Path.of(System.getProperty("heimdall.root"))));
  }

  @AfterEach
  void close() throws Exception {
    server.close();
  }

  RunInput input(List<Step> steps) {
    return Fixtures.input(
        Fixtures.snapshot(
            Fixtures.monitor(steps, List.of()), server.url("/").toString().replaceAll("/$", "")));
  }

  Step request(String id, String method, String extra) {
    return Fixtures.step(
        "{\"id\":\"%s\",\"type\":\"http\",\"request\":{\"method\":\"%s\",\"url\":\"${env.baseUrl}/jobs\",\"headers\":{\"Idempotency-Key\":\"${run.id}:${step.instanceId}\"}},\"assertions\":[{\"source\":\"status\",\"operator\":\"equals\",\"value\":200}]%s}"
            .formatted(id, method, extra));
  }

  StepCommand command(RunInput run, Step step, String instance, int attempt) {
    return new StepCommand(run, step, "main", instance, attempt);
  }

  @Test
  @Timeout(30)
  void heartbeatsContinueDuringSlowResultPersistence() {
    var step = request("read", "GET", "");
    server.enqueue(new MockResponse().setBody("{}"));
    var slowResults =
        new RecordingResults() {
          @Override
          public void attempt(Attempt attempt) {
            try {
              Thread.sleep(12000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(e);
            }
            super.attempt(attempt);
          }
        };
    var activity =
        new HttpActivities(
            state,
            slowResults,
            ref -> "unused",
            ScriptSandbox.local(Path.of(System.getProperty("heimdall.root"))));
    try (var env = io.temporal.testing.TestWorkflowEnvironment.newInstance()) {
      env.newWorker("workflow")
          .registerWorkflowImplementationTypes(dev.heimdall.workflow.ApiWorkflowImpl.class);
      env.newWorker("http-local").registerActivitiesImplementations(activity);
      env.newWorker("publication")
          .registerActivitiesImplementations(
              (dev.heimdall.workflow.PublicationActivities) event -> {});
      env.start();
      var stub =
          env.getWorkflowClient()
              .newWorkflowStub(
                  dev.heimdall.workflow.ApiWorkflow.class,
                  io.temporal.client.WorkflowOptions.newBuilder()
                      .setWorkflowId("slow-results")
                      .setTaskQueue("workflow")
                      .build());
      assertEquals(Status.passed, stub.execute(input(List.of(step))).status());
      assertEquals(1, server.getRequestCount());
    }
  }

  @Test
  void chainsExtractionEncodedUrlsAndTypedJsonBodies() throws Exception {
    var create =
        request("create", "POST", ",\"extract\":{\"id\":{\"source\":\"body\",\"path\":\"$.id\"}}");
    var check =
        Fixtures.step(
            "{\"id\":\"check\",\"type\":\"http\",\"request\":{\"method\":\"POST\",\"url\":\"${env.baseUrl}/jobs/${vars.id}\",\"json\":{\"id\":\"${vars.id}\"}}}");
    var run = input(List.of(create, check));
    server.enqueue(new MockResponse().setBody("{\"id\":\"a/b ?&\"}"));
    server.enqueue(new MockResponse().setBody("{}"));
    assertTrue(runner.execute(command(run, create, "create-1", 1)).success());
    assertTrue(runner.execute(command(run, check, "check-1", 1)).success());
    server.takeRequest();
    var request = server.takeRequest();
    assertEquals("/jobs/a%2Fb%20%3F%26", request.getPath());
    assertEquals("{\"id\":\"a/b ?&\"}", request.getBody().readUtf8());
    assertTrue(results.attempts.getFirst().timing().containsKey("dns"));
  }

  @Test
  void oauth401RefreshesBeforeAssertionsAndPreservesAllAttempts() throws Exception {
    var step = request("auth", "GET", "");
    var base = input(List.of(step));
    var auth =
        new Authentication(
            "oauth2",
            null,
            null,
            null,
            null,
            server.url("/token").toString(),
            "secret/data/payments/orders#client",
            "secret/data/payments/orders#secret",
            null,
            1);
    var s = base.snapshot();
    var monitor = s.monitors().get(Fixtures.MONITOR);
    var withAuth =
        new Monitor(
            monitor.type(),
            monitor.environments(),
            monitor.locations(),
            "oauth",
            monitor.timeout(),
            monitor.maxExecutions(),
            monitor.parameters(),
            monitor.steps(),
            monitor.cleanup(),
            null,
            null);
    var snapshot =
        new Snapshot(
            s.commit(),
            s.digest(),
            Map.of(Fixtures.MONITOR, withAuth),
            s.environments(),
            Map.of("payments/checkout/oauth", auth),
            s.scripts(),
            s.locations(),
            s.roles(),
            s.suites(),
            s.platform(),
            s.ciTrust(),
            s.alertPolicies());
    var run = Fixtures.input(snapshot);
    server.enqueue(new MockResponse().setBody("{\"access_token\":\"expired\"}"));
    server.enqueue(new MockResponse().setResponseCode(401));
    server.enqueue(new MockResponse().setBody("{\"access_token\":\"fresh\"}"));
    server.enqueue(new MockResponse().setBody("{}"));
    assertTrue(runner.execute(command(run, step, "auth-1", 1)).success());
    server.takeRequest();
    var first = server.takeRequest();
    server.takeRequest();
    var second = server.takeRequest();
    assertEquals(first.getHeader("Idempotency-Key"), second.getHeader("Idempotency-Key"));
    assertEquals("Bearer fresh", second.getHeader("Authorization"));
    assertEquals(2, results.attempts.size());
    assertEquals(401, results.attempts.getFirst().statusCode());
    assertTrue(results.attempts.getFirst().recovered());
    assertFalse(Json.write(results.attempts).contains("fresh"));
  }

  @Test
  void recordedCheckpointReplaysWithoutRepeatingRequestEvenIfResultWriteFails() {
    var step = request("create", "POST", "");
    var run = input(List.of(step));
    server.enqueue(new MockResponse().setBody("{}"));
    results.fail = true;
    assertThrows(IllegalStateException.class, () -> runner.execute(command(run, step, "one", 1)));
    results.fail = false;
    assertTrue(runner.execute(command(run, step, "one", 1)).success());
    assertEquals(1, server.getRequestCount());
  }

  @Test
  void workerFailureAfterClaimDoesNotResubmitAmbiguousPost() {
    var step = request("create", "POST", "");
    var run = input(List.of(step));
    state.claim("%s/request/one:1".formatted(run.key()));
    var outcome = runner.execute(command(run, step, "one", 1));
    assertTrue(outcome.terminalFailure());
    assertTrue(outcome.error().contains("Ambiguous"));
    assertEquals(0, server.getRequestCount());
  }

  @Test
  void hiddenNetworkRetriesAreDisabledAndRedirectsAreRejected() {
    var step = request("create", "POST", "");
    var run = input(List.of(step));
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    assertFalse(runner.execute(command(run, step, "disconnect", 1)).success());
    assertEquals(1, server.getRequestCount());
    server.enqueue(
        new MockResponse()
            .setResponseCode(302)
            .addHeader("Location", "http://169.254.169.254/latest"));
    assertFalse(runner.execute(command(run, step, "redirect", 1)).success());
    assertEquals(2, server.getRequestCount());
  }

  @Test
  void scriptsValidateCollectionsAndOutputsWithoutLeakingRawMessages() {
    var step =
        request(
            "items",
            "GET",
            ",\"postResponse\":{\"script\":\"scripts/check.js\",\"outputs\":[\"count\"]}");
    var base = input(List.of(step));
    var s = base.snapshot();
    var snapshot =
        new Snapshot(
            s.commit(),
            s.digest(),
            s.monitors(),
            s.environments(),
            s.authentication(),
            Map.of(
                "payments/checkout/scripts/check.js",
                "export default function({response,assert}) { assert(response.json().items.every(i=>i.id!=null),response.text()); return {count:2}; }"),
            s.locations(),
            s.roles(),
            s.suites(),
            s.platform(),
            s.ciTrust(),
            s.alertPolicies());
    server.enqueue(
        new MockResponse().setBody("{\"items\":[{\"id\":null}],\"secret\":\"raw-secret\"}"));
    assertFalse(runner.execute(command(Fixtures.input(snapshot), step, "script", 1)).success());
    assertFalse(Json.write(results.attempts).contains("raw-secret"));
  }

  @Test
  void privateLocationCannotEscapeApprovedHostsAndPublicBlocksMetadata() throws Exception {
    assertThrows(
        java.net.UnknownHostException.class,
        () -> NetworkPolicy.validate(java.net.InetAddress.getByName("127.0.0.1"), false));
    assertThrows(
        java.net.UnknownHostException.class,
        () -> NetworkPolicy.validate(java.net.InetAddress.getByName("169.254.169.254"), true));
    assertThrows(
        java.net.UnknownHostException.class,
        () -> NetworkPolicy.validate(java.net.InetAddress.getByName("fc00::1"), false));
    var policy =
        new NetworkPolicy(
            new Location(
                "private",
                List.of("approved.example"),
                List.of(),
                List.of("api"),
                true,
                1,
                "test"));
    assertThrows(java.net.UnknownHostException.class, () -> policy.lookup("localhost"));
  }

  static class MemoryState implements State {
    final Map<String, String> data = new ConcurrentHashMap<>();
    final Set<String> claims = ConcurrentHashMap.newKeySet();

    public <T> T load(String key, Class<T> type) {
      var value = data.get(key);
      return value == null ? null : Json.read(value, type);
    }

    public void save(String key, Object value) {
      data.put(key, Json.write(value));
    }

    public boolean claim(String key) {
      return claims.add(key);
    }
  }

  static class RecordingResults implements Results {
    final List<Attempt> attempts = new CopyOnWriteArrayList<>();
    boolean fail;

    public void attempt(Attempt a) {
      if (fail) throw new IllegalStateException("PostgreSQL unavailable");
      attempts.add(a);
    }

    public void create(
        String id, String subject, StartRun req, Snapshot snap, List<RegionalResult> expected) {
      throw new UnsupportedOperationException();
    }

    public RunView get(String id) {
      throw new UnsupportedOperationException();
    }

    public Page<RunView> list(Instant from, Instant to, String cursor, int limit) {
      throw new UnsupportedOperationException();
    }

    public List<Attempt> steps(String id) {
      return attempts;
    }

    public void ingest(ResultEvent event) {
      throw new UnsupportedOperationException();
    }

    public void audit(String subject, String action, String resource) {}
  }
}
