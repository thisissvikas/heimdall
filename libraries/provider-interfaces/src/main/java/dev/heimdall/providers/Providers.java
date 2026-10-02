package dev.heimdall.providers;

import dev.heimdall.contracts.Definitions.*;
import dev.heimdall.contracts.Execution.*;
import java.time.Instant;
import java.util.*;

public final class Providers {
  private Providers() {}

  public record Identity(String subject, Map<String, Object> claims) {}

  public interface Authorization {
    void require(
        Identity identity,
        String action,
        String scope,
        String environment,
        String location,
        Snapshot snapshot);
  }

  public interface Secrets {
    String resolve(String reference);
  }

  public interface State {
    <T> T load(String key, Class<T> type);

    void save(String key, Object value);

    /** Atomic claim used before issuing an external side effect. */
    boolean claim(String key);
  }

  public interface Artifacts {
    void put(String key, byte[] data);

    byte[] get(String key);
  }

  public interface Results {
    void create(
        String id,
        String subject,
        StartRun request,
        Snapshot snapshot,
        List<RegionalResult> expected);

    RunView get(String id);

    Page<RunView> list(Instant from, Instant to, String cursor, int limit);

    void attempt(Attempt attempt);

    List<Attempt> steps(String id);

    void ingest(ResultEvent event);

    void audit(String subject, String action, String resource);
  }

  public interface Notifications {
    void deliver(String deliveryId, String destination, byte[] body);
  }

  public interface Telemetry {
    void record(String monitor, String location, String status, long durationMs);
  }
}
