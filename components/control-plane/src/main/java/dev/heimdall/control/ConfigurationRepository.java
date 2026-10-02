package dev.heimdall.control;

import dev.heimdall.contracts.*;
import dev.heimdall.contracts.Definitions.*;
import dev.heimdall.contracts.Execution.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class ConfigurationRepository {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  public ConfigurationRepository(DataSource ds) {
    jdbc = new JdbcTemplate(ds);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
  }

  public Snapshot active() {
    var rows =
        jdbc.query(
            "SELECT bundle FROM control.snapshots WHERE commit=(SELECT active FROM control.reconciliation WHERE id=1)",
            (rs, n) -> Json.read(rs.getString(1), Snapshot.class));
    if (rows.isEmpty()) throw new IllegalStateException("No approved configuration is active");
    return rows.getFirst();
  }

  public void desired(String commit) {
    jdbc.update(
        "UPDATE control.reconciliation SET desired=?,status='validating',error=NULL WHERE id=1",
        commit);
  }

  public void activate(Snapshot snapshot) {
    tx.executeWithoutResult(
        s -> {
          var desired =
              jdbc.queryForObject(
                  "SELECT desired FROM control.reconciliation WHERE id=1 FOR UPDATE", String.class);
          if (!snapshot.commit().equals(desired))
            throw new IllegalStateException("Stale reconciliation generation");
          jdbc.update(
              "INSERT INTO control.snapshots(commit,digest,bundle) VALUES (?,?,?::jsonb) ON CONFLICT DO NOTHING",
              snapshot.commit(),
              snapshot.digest(),
              Json.write(snapshot));
          jdbc.update(
              "UPDATE control.reconciliation SET active=?,status='applying_schedules',error=NULL WHERE id=1",
              snapshot.commit());
        });
  }

  public void applied(String commit) {
    jdbc.update(
        "UPDATE control.reconciliation SET schedules=?,status='applied',error=NULL,last_success=now() WHERE id=1 AND active=?",
        commit,
        commit);
  }

  public void failed(String error) {
    jdbc.update("UPDATE control.reconciliation SET status='error',error=? WHERE id=1", error);
  }

  public SyncStatus status() {
    return jdbc.queryForObject(
        "SELECT * FROM control.reconciliation WHERE id=1",
        (rs, n) ->
            new SyncStatus(
                rs.getString("desired"),
                rs.getString("active"),
                rs.getString("schedules"),
                rs.getString("status"),
                rs.getString("error"),
                rs.getTimestamp("last_success") == null
                    ? null
                    : rs.getTimestamp("last_success").toInstant()));
  }

  public static String generation(Snapshot snapshot, String monitor, String environment) {
    var m = snapshot.monitors().get(monitor);
    var scripts = new TreeMap<String, String>();
    collectScripts(m.steps(), Definitions.appScope(monitor), snapshot, scripts);
    collectScripts(m.cleanup(), Definitions.appScope(monitor), snapshot, scripts);
    var locations = new TreeMap<String, Location>();
    for (var name : m.locations()) locations.put(name, snapshot.locations().get(name));
    return Json.sha256(
        Json.write(
            Arrays.asList(
                m,
                snapshot
                    .environments()
                    .get("%s/%s".formatted(Definitions.appScope(monitor), environment)),
                m.authRef() == null
                    ? null
                    : snapshot
                        .authentication()
                        .get("%s/%s".formatted(Definitions.appScope(monitor), m.authRef())),
                scripts,
                locations)));
  }

  private static void collectScripts(
      List<Step> steps, String app, Snapshot snapshot, Map<String, String> out) {
    for (var step : steps) {
      if (step.postResponse() != null) {
        String key = "%s/%s".formatted(app, step.postResponse().script());
        out.put(key, snapshot.scripts().get(key));
      }
      for (var branch : step.branches()) collectScripts(branch.steps(), app, snapshot, out);
    }
  }
}
