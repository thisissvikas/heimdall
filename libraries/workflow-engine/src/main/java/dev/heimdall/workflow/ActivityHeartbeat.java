package dev.heimdall.workflow;

import io.temporal.activity.*;
import io.temporal.client.ActivityCompletionException;
import java.util.concurrent.atomic.*;

/** Heartbeats the complete activity, including provider, script and persistence I/O. */
public final class ActivityHeartbeat implements AutoCloseable {
  private final AtomicBoolean active = new AtomicBoolean(true);
  private final AtomicReference<ActivityCompletionException> failure = new AtomicReference<>();
  private final Thread worker;

  private ActivityHeartbeat(ActivityExecutionContext context) {
    if (context == null) {
      worker = null;
      return;
    }
    Thread owner = Thread.currentThread();
    context.heartbeat(null);
    worker =
        Thread.startVirtualThread(
            () -> {
              while (active.get()) {
                try {
                  Thread.sleep(1000);
                  if (active.get()) context.heartbeat(null);
                } catch (InterruptedException e) {
                  return;
                } catch (ActivityCompletionException e) {
                  failure.set(e);
                  owner.interrupt();
                  return;
                }
              }
            });
  }

  public static ActivityHeartbeat start() {
    ActivityExecutionContext context;
    try {
      context = Activity.getExecutionContext();
    } catch (IllegalStateException directInvocation) {
      context = null;
    }
    return new ActivityHeartbeat(context);
  }

  public void check() {
    var error = failure.get();
    if (error != null) throw error;
  }

  @Override
  public void close() {
    active.set(false);
    if (worker != null) worker.interrupt();
  }
}
