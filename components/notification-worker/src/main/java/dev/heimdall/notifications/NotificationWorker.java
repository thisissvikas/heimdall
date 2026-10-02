package dev.heimdall.notifications;

import dev.heimdall.contracts.Json;
import dev.heimdall.providers.Providers.Notifications;
import dev.heimdall.results.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class NotificationWorker {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Notifications transport;

  public NotificationWorker(DataSource ds, Notifications transport) {
    jdbc = new JdbcTemplate(ds);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    this.transport = transport;
  }

  public boolean processOne() {
    return Boolean.TRUE.equals(
        tx.execute(
            s -> {
              var rows =
                  jdbc.queryForList(
                      "SELECT * FROM control.notification_intents WHERE delivered_at IS NULL AND next_attempt<=now() ORDER BY next_attempt FOR UPDATE SKIP LOCKED LIMIT 1");
              if (rows.isEmpty()) return false;
              var row = rows.getFirst();
              String id = (String) row.get("id");
              try {
                transport.deliver(
                    id,
                    (String) row.get("destination"),
                    row.get("body").toString().getBytes(StandardCharsets.UTF_8));
                jdbc.update(
                    "UPDATE control.notification_intents SET delivered_at=now(),attempts=attempts+1 WHERE id=?",
                    id);
              } catch (Exception e) {
                int attempts = ((Number) row.get("attempts")).intValue() + 1;
                long backoff = Math.min(3600, 1L << Math.min(attempts, 12));
                jdbc.update(
                    "UPDATE control.notification_intents SET attempts=?,next_attempt=now()+(? * interval '1 second') WHERE id=?",
                    attempts,
                    backoff,
                    id);
              }
              return true;
            }));
  }

  @SuppressWarnings("unchecked")
  public static void main(String[] args) throws Exception {
    try (var ds = Database.connect()) {
      var destinations =
          (Map<String, String>)
              (Map<?, ?>)
                  Json.read(
                      System.getenv().getOrDefault("NOTIFICATION_DESTINATIONS", "{}"), Map.class);
      var transport =
          new SignedWebhooks(
              destinations, Base64.getDecoder().decode(System.getenv("NOTIFICATION_KEY")), false);
      var worker = new NotificationWorker(ds, transport);
      while (!Thread.currentThread().isInterrupted()) {
        if (!worker.processOne()) Thread.sleep(1000);
      }
    }
  }
}
