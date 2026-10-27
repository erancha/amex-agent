package com.erancha.amexagent.a2a;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the agent executor under the task's lease: taken before the work starts, renewed every
 * third of its time to live while the work runs, and released when the run returns, whether the
 * task completed, paused or failed. A run that loses its lease is interrupted, which fails its
 * task: no replica takes over a working task, and the lease sweep fails one whose lease lapsed.
 *
 * A continue arrives holding the lease A2AEndpoint took for it, under the owner the call context
 * names. The SDK may start the run after that lease lapsed, so the run takes it again if nobody
 * holds it. When another call holds it by then, the run returns without any event and leaves the
 * task to that call; throwing instead would make the SDK fail the task the other call runs.
 */
final class LeasedAgentExecutor implements AgentExecutor {
  private static final Logger log = LoggerFactory.getLogger(LeasedAgentExecutor.class);

  private final AgentExecutor delegate;
  private final TaskLease lease;
  private final long renewEveryMillis;
  private final ScheduledExecutorService renewals;

  LeasedAgentExecutor(AgentExecutor delegate, TaskLease lease, Duration leaseTime,
      ScheduledExecutorService renewals) {
    this.delegate = delegate;
    this.lease = lease;
    this.renewEveryMillis = leaseTime.toMillis() / 3;
    this.renewals = renewals;
  }

  @Override
  public void execute(RequestContext context, AgentEmitter emitter) throws A2AError {
    String taskId = context.getTaskId();
    String owner = (String) context.getCallContext().getState().get(CallContexts.LEASE_OWNER_KEY);
    if (context.getTask() == null) {
      if (!lease.acquire(taskId, owner)) {
        throw new IllegalStateException("The SDK gives a new task a fresh id, yet task " + taskId
            + " is leased");
      }
    } else if (!lease.renew(taskId, owner) && !lease.acquire(taskId, owner)) {
      log.warn("Task {} is run by another call; this continue leaves it as it is", taskId);
      return;
    }
    Thread worker = Thread.currentThread();
    ScheduledFuture<?> renewal = renewals.scheduleAtFixedRate(
        () -> renewOrInterrupt(taskId, owner, worker),
        renewEveryMillis, renewEveryMillis, TimeUnit.MILLISECONDS);
    try {
      delegate.execute(context, emitter);
    } finally {
      renewal.cancel(false);
      lease.release(taskId, owner);
    }
  }

  @Override
  public void cancel(RequestContext context, AgentEmitter emitter) throws A2AError {
    delegate.cancel(context, emitter);
  }

  /**
   * A renewal that fails, or that cannot reach the lease's store, interrupts the worker and ends
   * the schedule by throwing: either way the run can no longer vouch that it alone works on the
   * task.
   */
  private void renewOrInterrupt(String taskId, String owner, Thread worker) {
    try {
      if (lease.renew(taskId, owner)) {
        return;
      }
      log.error("Task {}: lost its lease while working; stopping its run", taskId);
    } catch (RuntimeException e) {
      log.error("Task {}: the lease could not be renewed; stopping its run", taskId, e);
    }
    worker.interrupt();
    throw new IllegalStateException("lost the lease on task " + taskId);
  }
}
