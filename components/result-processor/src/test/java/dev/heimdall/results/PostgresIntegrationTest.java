package dev.heimdall.results;

import static org.junit.jupiter.api.Assertions.*;

import dev.heimdall.contracts.*;
import dev.heimdall.contracts.Execution.*;
import dev.heimdall.testing.Fixtures;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;

@Tag("integration")
class PostgresIntegrationTest {
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine");
  static com.zaxxer.hikari.HikariDataSource ds;
  JdbcResults results;

  @BeforeAll
  static void start() {
    POSTGRES.start();
    ds = Database.connect(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), 8);
    Database.migrate(ds);
  }

  @AfterAll
  static void stop() {
    if (ds != null) ds.close();
    POSTGRES.stop();
  }

  @BeforeEach
  void setup() {
    results = new JdbcResults(ds);
  }

  String create() {
    String id = UUID.randomUUID().toString();
    var snapshot = Fixtures.input(Fixtures.monitor(List.of(), List.of())).snapshot();
    results.create(
        id,
        "dev-runner",
        new StartRun(List.of(Fixtures.MONITOR), "staging", List.of("local"), Map.of(), null),
        snapshot,
        List.of(new RegionalResult(Fixtures.MONITOR, "local", Status.queued, null, null, 0)));
    return id;
  }

  @Test
  void concurrentDuplicateIngestionDoesNotInflateAggregatesAndTerminalStateNeverRegresses()
      throws Exception {
    String id = create();
    Instant stamp = Instant.parse("2026-09-30T23:59:59.999Z");
    var terminal =
        new ResultEvent(
            "%s:done".formatted(id),
            id,
            Fixtures.MONITOR,
            "local",
            3,
            stamp,
            Status.passed,
            "passed",
            null,
            "api");
    long before =
        results
            .jdbc()
            .queryForObject("SELECT COALESCE(sum(passed),0) FROM results.aggregates", Long.class);
    try (var pool = Executors.newFixedThreadPool(4)) {
      var futures = new ArrayList<Future<?>>();
      for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> results.ingest(terminal)));
      for (var f : futures) f.get();
    }
    results.ingest(
        new ResultEvent(
            "%s:older".formatted(id),
            id,
            Fixtures.MONITOR,
            "local",
            2,
            stamp,
            Status.running,
            null,
            null,
            "api"));
    results.ingest(
        new ResultEvent(
            "%s:newer".formatted(id),
            id,
            Fixtures.MONITOR,
            "local",
            4,
            stamp,
            Status.failed,
            null,
            null,
            "api"));
    assertEquals(Status.passed, results.get(id).status());
    assertEquals(
        before + 1,
        results
            .jdbc()
            .queryForObject("SELECT COALESCE(sum(passed),0) FROM results.aggregates", Long.class));
    assertEquals(
        3,
        results
            .jdbc()
            .queryForObject(
                "SELECT count(*) FROM results.events WHERE run_id=?", Integer.class, id));
  }

  @Test
  void stateEncryptionAuthenticatesKeysAndClaimsAreAtomicAcrossInstances() throws Exception {
    var key = Base64.getEncoder().encodeToString(new byte[32]);
    var first = new EncryptedState(ds, key);
    var second = new EncryptedState(ds, key);
    String stateKey = UUID.randomUUID().toString();
    first.save(stateKey, Map.of("token", "super-sensitive"));
    assertEquals("super-sensitive", second.load(stateKey, Map.class).get("token"));
    byte[] cipher =
        results
            .jdbc()
            .queryForObject("SELECT cipher FROM control.state WHERE key=?", byte[].class, stateKey);
    assertFalse(
        new String(cipher, java.nio.charset.StandardCharsets.UTF_8).contains("super-sensitive"));
    assertTrue(first.claim(stateKey));
    assertFalse(second.claim(stateKey));
    cipher[cipher.length - 1] ^= 1;
    results.jdbc().update("UPDATE control.state SET cipher=? WHERE key=?", cipher, stateKey);
    assertThrows(IllegalStateException.class, () -> first.load(stateKey, Map.class));
  }

  @Test
  void paginationHasNoDuplicatesAndRequiresBoundedQueries() {
    create();
    create();
    create();
    Instant to = Instant.now().plusSeconds(1), from = to.minusSeconds(60);
    var first = results.list(from, to, null, 2);
    assertEquals(2, first.items().size());
    assertNotNull(first.nextCursor());
    var next = results.list(from, to, first.nextCursor(), 2);
    var ids = first.items().stream().map(RunView::id).toList();
    assertTrue(next.items().stream().noneMatch(r -> ids.contains(r.id())));
    assertThrows(
        IllegalArgumentException.class,
        () -> results.list(from.minus(Duration.ofDays(31)), to, null, 2));
    assertThrows(IllegalArgumentException.class, () -> results.list(from, to, "invalid", 2));
  }

  @Test
  void attemptReplayUsesOriginalUtcPartitionAndDeduplicates() {
    String id = create();
    var attempt =
        new Attempt(
            "%s:attempt".formatted(id),
            id,
            Fixtures.MONITOR,
            "local",
            "step",
            1,
            Instant.parse("2026-09-30T23:59:59.999Z"),
            200,
            "target",
            true,
            Map.of("total", 12L),
            List.of(),
            null,
            false);
    results.attempt(attempt);
    results.attempt(attempt);
    assertEquals(1, results.steps(id).size());
    String table =
        results
            .jdbc()
            .queryForObject(
                "SELECT tableoid::regclass::text FROM results.step_attempts WHERE id=?",
                String.class,
                attempt.id());
    assertEquals("results.attempts_20260930", table);
  }
}
