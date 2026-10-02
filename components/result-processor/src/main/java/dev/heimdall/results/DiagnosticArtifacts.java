package dev.heimdall.results;

import dev.heimdall.contracts.*;
import dev.heimdall.contracts.Execution.Attempt;
import dev.heimdall.providers.Providers.Artifacts;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class DiagnosticArtifacts {
  private final JdbcResults results;
  private final Artifacts storage;

  public DiagnosticArtifacts(JdbcResults results, Artifacts storage) {
    this.results = results;
    this.storage = storage;
  }

  public void record(Attempt attempt) {
    if (attempt.success()) return;
    String id = Json.sha256(attempt.id());
    String key = "failure/%s/%s".formatted(attempt.runId(), id);
    // Diagnostics deliberately exclude raw headers, bodies, tokens and extracted variables.
    byte[] data = Json.write(attempt).getBytes(StandardCharsets.UTF_8);
    storage.put(key, data);
    results
        .jdbc()
        .update(
            "INSERT INTO control.artifacts(id,run_id,monitor,location,object_key,created_at,size_bytes) VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
            id,
            attempt.runId(),
            attempt.monitorRef(),
            attempt.location(),
            key,
            java.sql.Timestamp.from(attempt.startedAt()),
            data.length);
  }

  public List<Map<String, Object>> list(String run) {
    return results
        .jdbc()
        .queryForList(
            "SELECT id,created_at,size_bytes FROM control.artifacts WHERE run_id=? ORDER BY created_at LIMIT 1000",
            run);
  }

  public byte[] get(String run, String id) {
    var rows =
        results
            .jdbc()
            .query(
                "SELECT object_key FROM control.artifacts WHERE run_id=? AND id=?",
                (rs, n) -> rs.getString(1),
                run,
                id);
    if (rows.isEmpty()) throw new NoSuchElementException("Artifact not found");
    return storage.get(rows.getFirst());
  }
}
