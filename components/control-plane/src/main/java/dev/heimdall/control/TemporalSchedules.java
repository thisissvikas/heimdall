package dev.heimdall.control;

import static dev.heimdall.contracts.Definitions.duration;

import dev.heimdall.contracts.*;
import dev.heimdall.contracts.Definitions.*;
import dev.heimdall.workflow.ScheduledWorkflow;
import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import io.temporal.client.*;
import io.temporal.client.schedules.*;
import java.time.Duration;
import java.util.*;

public final class TemporalSchedules implements ScheduleSynchronizer {
  private final ScheduleClient client;

  public TemporalSchedules(ScheduleClient client) {
    this.client = client;
  }

  @Override
  public void apply(Snapshot snapshot) {
    var desired = new HashSet<String>();
    snapshot
        .monitors()
        .forEach(
            (id, m) -> {
              if (m.schedule() == null) return;
              for (var env : m.environments()) {
                var s = m.schedule();
                String scheduleId =
                    "heimdall-%s"
                        .formatted(Json.sha256("%s/%s".formatted(id, env)).substring(0, 32));
                desired.add(scheduleId);
                var spec =
                    ScheduleSpec.newBuilder()
                        .setIntervals(List.of(new ScheduleIntervalSpec(duration(s.every()))));
                if (s.timezone() != null) spec.setTimeZoneName(s.timezone());
                if (s.jitter() != null) spec.setJitter(duration(s.jitter()));
                var schedule =
                    io.temporal.client.schedules.Schedule.newBuilder()
                        .setSpec(spec.build())
                        .setAction(
                            ScheduleActionStartWorkflow.newBuilder()
                                .setWorkflowType(ScheduledWorkflow.class)
                                .setOptions(
                                    WorkflowOptions.newBuilder()
                                        .setTaskQueue("workflow")
                                        .setWorkflowId("scheduled-%s".formatted(scheduleId))
                                        .build())
                                .setArguments(
                                    id, env, ConfigurationRepository.generation(snapshot, id, env))
                                .build())
                        .setState(ScheduleState.newBuilder().setPaused(s.paused()).build())
                        .setPolicy(
                            SchedulePolicy.newBuilder()
                                .setOverlap(
                                    "bufferOne".equals(s.overlap())
                                        ? ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_BUFFER_ONE
                                        : ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP)
                                .setCatchupWindow(Duration.ofMinutes(1))
                                .build())
                        .build();
                try {
                  client.createSchedule(scheduleId, schedule, ScheduleOptions.newBuilder().build());
                } catch (ScheduleAlreadyRunningException e) {
                  client.getHandle(scheduleId).update(input -> new ScheduleUpdate(schedule));
                }
              }
            });
    try (var schedules = client.listSchedules()) {
      schedules
          .filter(
              s ->
                  s.getScheduleId().startsWith("heimdall-") && !desired.contains(s.getScheduleId()))
          .forEach(s -> client.getHandle(s.getScheduleId()).delete());
    }
  }
}
