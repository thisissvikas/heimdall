package dev.heimdall.control;

import dev.heimdall.contracts.Definitions.Snapshot;

public interface ScheduleSynchronizer {
  void apply(Snapshot snapshot);
}
