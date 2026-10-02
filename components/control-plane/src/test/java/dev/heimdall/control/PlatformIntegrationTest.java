package dev.heimdall.control;

import static org.junit.jupiter.api.Assertions.*;

import dev.heimdall.contracts.*;
import dev.heimdall.contracts.Definitions.*;
import dev.heimdall.contracts.Execution.*;
import dev.heimdall.notifications.NotificationWorker;
import dev.heimdall.results.*;
import dev.heimdall.testing.Fixtures;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.*;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

@Tag("integration")
class PlatformIntegrationTest {
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine");
  static com.zaxxer.hikari.HikariDataSource ds;

  @BeforeAll
  static void start() {
    POSTGRES.start();
    ds = Database.connect(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), 8);
    Database.migrate(ds);
  }

  @AfterAll
  static void stop() {
    ds.close();
    POSTGRES.stop();
  }

  @Test
  void shedLockPreventsConcurrentScheduledExecutionAcrossApplicationInstances() throws Exception {
    var counter = new AtomicInteger();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var first = context(counter, entered, release);
        var second = context(counter, entered, release);
        var pool = Executors.newFixedThreadPool(2)) {
      var running = pool.submit(() -> first.getBean(LockedJob.class).run());
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      second.getBean(LockedJob.class).run();
      assertEquals(1, counter.get());
      release.countDown();
      running.get(5, TimeUnit.SECONDS);
      second.getBean(LockedJob.class).run();
      assertEquals(2, counter.get());
    } finally {
      release.countDown();
    }
  }

  AnnotationConfigApplicationContext context(
      AtomicInteger counter, CountDownLatch entered, CountDownLatch release) {
    var context = new AnnotationConfigApplicationContext();
    context.registerBean(
        DataSource.class, () -> ds, definition -> definition.setDestroyMethodName(""));
    context.registerBean(LockedJob.class, () -> new LockedJob(counter, entered, release));
    context.register(LockConfiguration.class);
    context.refresh();
    return context;
  }

  @Configuration(proxyBeanMethods = false)
  @EnableSchedulerLock(defaultLockAtMostFor = "PT1M")
  static class LockConfiguration {
    @Bean
    LockProvider lockProvider(DataSource source) {
      return new JdbcTemplateLockProvider(
          JdbcTemplateLockProvider.Configuration.builder()
              .withJdbcTemplate(new JdbcTemplate(source))
              .withTableName("control.shedlock")
              .usingDbTime()
              .build());
    }
  }

  public static class LockedJob {
    final AtomicInteger counter;
    final CountDownLatch entered, release;

    LockedJob(AtomicInteger counter, CountDownLatch entered, CountDownLatch release) {
      this.counter = counter;
      this.entered = entered;
      this.release = release;
    }

    @SchedulerLock(name = "test-shared-job", lockAtMostFor = "PT1M")
    public void run() {
      LockAssert.assertLocked();
      counter.incrementAndGet();
      entered.countDown();
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Test
  void invalidRevisionRetainsActiveSnapshotAndPartialSchedulesRetry() throws Exception {
    var repository = new ConfigurationRepository(ds);
    Path root = Path.of(System.getProperty("heimdall.root"));
    var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
    var updates = new AtomicInteger();
    var reconciler =
        new Reconciler(
            () -> new GitSource.Revision("valid", root),
            repository,
            snapshot -> {
              updates.incrementAndGet();
              if (fail.get()) throw new IllegalStateException("Temporal unavailable");
            });
    reconciler.reconcileOnce();
    assertEquals("valid", repository.status().activeConfigurationCommit());
    assertNull(repository.status().schedulesAppliedCommit());
    fail.set(false);
    reconciler.reconcileOnce();
    assertEquals("valid", repository.status().schedulesAppliedCommit());
    assertEquals(2, updates.get());
    var invalid =
        new Reconciler(
            () -> new GitSource.Revision("invalid", root.resolve("missing")),
            repository,
            snapshot -> fail("Must not synchronize invalid config"));
    invalid.reconcileOnce();
    assertEquals("valid", repository.active().commit());
    assertEquals("error", repository.status().status());
    repository.desired("new-generation");
    assertThrows(IllegalStateException.class, () -> repository.activate(repository.active()));
  }

  @Test
  void notificationRowsAreClaimedOnceAcrossWorkersAndRetriesKeepDeliveryId() throws Exception {
    var jdbc = new JdbcTemplate(ds);
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO control.notification_intents(id,destination,body) VALUES (?,'fixture','{}')",
        id);
    var calls = new ConcurrentLinkedQueue<String>();
    var first =
        new NotificationWorker(
            ds,
            (delivery, destination, body) -> {
              calls.add(delivery);
            });
    var second =
        new NotificationWorker(
            ds,
            (delivery, destination, body) -> {
              calls.add(delivery);
            });
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(first::processOne);
      var b = pool.submit(second::processOne);
      a.get();
      b.get();
    }
    assertEquals(List.of(id), List.copyOf(calls));
    String retry = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO control.notification_intents(id,destination,body) VALUES (?,'fixture','{}')",
        retry);
    new NotificationWorker(
            ds,
            (delivery, destination, body) -> {
              calls.add(delivery);
              throw new IllegalStateException("transport unavailable");
            })
        .processOne();
    jdbc.update("UPDATE control.notification_intents SET next_attempt=now() WHERE id=?", retry);
    first.processOne();
    assertEquals(2, Collections.frequency(List.copyOf(calls), retry));
    assertNotNull(
        jdbc.queryForObject(
            "SELECT delivered_at FROM control.notification_intents WHERE id=?",
            java.sql.Timestamp.class,
            retry));
  }

  @Test
  void retentionPreservesPartitionsNeededByActiveRuns() {
    var results = new JdbcResults(ds);
    var snapshot = Fixtures.input(Fixtures.monitor(List.of(), List.of())).snapshot();
    String id = UUID.randomUUID().toString();
    results.create(
        id,
        "dev-runner",
        new StartRun(List.of(Fixtures.MONITOR), "staging", List.of(), Map.of(), null),
        snapshot,
        List.of(new RegionalResult(Fixtures.MONITOR, "local", Status.queued, null, null, 0)));
    Instant old = Instant.now().minus(Duration.ofDays(20));
    results
        .jdbc()
        .update(
            "UPDATE control.runs SET created_at=? WHERE id=?", java.sql.Timestamp.from(old), id);
    results
        .jdbc()
        .queryForObject(
            "SELECT results.ensure_partitions(?)", Object.class, java.sql.Timestamp.from(old));
    String table =
        "results.events_%s"
            .formatted(
                old.atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE));
    new Retention(results).maintainAt(Instant.now());
    assertNotNull(results.jdbc().queryForObject("SELECT to_regclass(?)", String.class, table));
    results.jdbc().update("UPDATE control.runs SET status='passed' WHERE id=?", id);
    new Retention(results).maintainAt(Instant.now());
    assertNull(results.jdbc().queryForObject("SELECT to_regclass(?)", String.class, table));
  }
}
