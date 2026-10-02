package dev.heimdall.control;

import dev.heimdall.configuration.Compiler;
import org.springframework.scheduling.annotation.Scheduled;

public class Reconciler {
  private final GitSource source;
  private final ConfigurationRepository repository;
  private final ScheduleSynchronizer schedules;

  public Reconciler(
      GitSource source, ConfigurationRepository repository, ScheduleSynchronizer schedules) {
    this.source = source;
    this.repository = repository;
    this.schedules = schedules;
  }

  @Scheduled(fixedDelayString = "${heimdall.reconcile-delay-ms:60000}")
  @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(
      name = "configuration-reconcile",
      lockAtMostFor = "PT10M",
      lockAtLeastFor = "PT1S")
  public synchronized void reconcile() {
    net.javacrumbs.shedlock.core.LockAssert.assertLocked();
    reconcileOnce();
  }

  synchronized void reconcileOnce() {
    GitSource.Revision revision = null;
    try {
      revision = source.head();
      var status = repository.status();
      if (revision.commit().equals(status.activeConfigurationCommit())
          && revision.commit().equals(status.schedulesAppliedCommit())) return;
      repository.desired(revision.commit());
      var snapshot = new Compiler().compile(revision.root(), revision.commit());
      repository.activate(snapshot);
      schedules.apply(snapshot);
      repository.applied(snapshot.commit());
    } catch (Exception e) {
      repository.failed(
          "Configuration fetch, validation or schedule synchronization failed: %s"
              .formatted(e.getClass().getSimpleName()));
    } finally {
      if (source instanceof GitSource.ProtectedBranch && revision != null)
        try {
          GitSource.ProtectedBranch.deleteTree(revision.root());
        } catch (Exception ignored) {
        }
    }
  }
}
