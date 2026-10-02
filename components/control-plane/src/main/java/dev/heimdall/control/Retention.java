package dev.heimdall.control;

import dev.heimdall.results.JdbcResults;
import java.sql.Timestamp;
import java.time.*;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;

public class Retention {
  private final JdbcResults results;

  public Retention(JdbcResults results) {
    this.results = results;
  }

  @Scheduled(cron = "0 0 * * * *", zone = "UTC")
  @SchedulerLock(name = "history-retention", lockAtMostFor = "PT20M", lockAtLeastFor = "PT1M")
  public void maintain() {
    LockAssert.assertLocked();
    maintainAt(Instant.now());
  }

  void maintainAt(Instant now) {
    var jdbc = results.jdbc();
    jdbc.queryForObject(
        "SELECT results.ensure_partitions(?)",
        Object.class,
        Timestamp.from(now.plus(Duration.ofDays(1))));
    var tables =
        jdbc.query(
            "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid=i.inhrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='results'",
            (rs, n) -> rs.getString(1));
    for (var table : tables) {
      if (!table.matches("(events|attempts)_[0-9]{8}")) continue;
      var day =
          LocalDate.parse(
              table.substring(table.length() - 8),
              java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
      if (!day.isBefore(now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(7))) continue;
      int active =
          jdbc.queryForObject(
              "SELECT count(*) FROM control.runs WHERE status IN ('queued','running','waiting') AND created_at<?",
              Integer.class,
              Timestamp.from(day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
      if (active == 0) jdbc.execute("DROP TABLE results.%s".formatted(table));
    }
    jdbc.update(
        "DELETE FROM results.aggregates WHERE day<?",
        now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(90));
    jdbc.update(
        "DELETE FROM control.audit WHERE timestamp<?",
        Timestamp.from(now.minus(Duration.ofDays(365))));
    jdbc.update(
        "DELETE FROM control.webhook_deliveries WHERE received_at<?",
        Timestamp.from(now.minus(Duration.ofDays(30))));
  }
}
