package com.erancha.amexagent.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.auth.UnauthenticatedUser;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The wrapper around the agent executor, on an in-memory lease: a run holds its task's lease while
 * it works and gives it up after, a continue runs only while it can hold the lease, and a run that
 * loses the lease is interrupted.
 */
class LeasedAgentExecutorTest {
  private static final String TASK = "t1";
  private static final String OWNER = "replica/call";

  private final ScheduledExecutorService renewals = Executors.newSingleThreadScheduledExecutor();

  @AfterEach
  void stopRenewals() {
    renewals.shutdownNow();
  }

  private static RequestContext context(Task task) {
    Map<String, Object> state = new HashMap<>();
    state.put(CallContexts.LEASE_OWNER_KEY, OWNER);
    ServerCallContext call = new ServerCallContext(UnauthenticatedUser.INSTANCE, state, Set.of(),
        null);
    return new RequestContext.Builder().setTaskId(TASK).setContextId("c1").setTask(task)
        .setServerCallContext(call).build();
  }

  private static Task paused() {
    return Task.builder().id(TASK).contextId("c1")
        .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED)).build();
  }

  private LeasedAgentExecutor leased(AgentExecutor delegate, TaskLease lease, Duration time) {
    return new LeasedAgentExecutor(delegate, lease, time, renewals);
  }

  @Test
  void aRun_holdsItsTasksLeaseWhileItWorksAndReleasesItAfter() {
    TaskLease lease = new InMemoryTaskLease(Duration.ofMinutes(1));
    AtomicReference<String> holderWhileWorking = new AtomicReference<>();
    AgentExecutor work = (context, emitter) -> holderWhileWorking.set(lease.ownerOf(TASK));

    leased(work, lease, Duration.ofMinutes(1)).execute(context(null), null);

    assertThat(holderWhileWorking.get()).isEqualTo(OWNER);
    assertThat(lease.ownerOf(TASK)).isNull();
  }

  @Test
  void aContinueWhoseLeaseAnotherCallHolds_leavesTheTaskToThatCall() {
    TaskLease lease = new InMemoryTaskLease(Duration.ofMinutes(1));
    lease.acquire(TASK, "another");
    AtomicBoolean ran = new AtomicBoolean();

    leased((context, emitter) -> ran.set(true), lease, Duration.ofMinutes(1))
        .execute(context(paused()), null);

    assertThat(ran).isFalse();
    assertThat(lease.ownerOf(TASK)).isEqualTo("another");
  }

  @Test
  void aContinueWhoseLeaseLapsedBeforeItStarted_takesItAgainAndRuns() {
    TaskLease lease = new InMemoryTaskLease(Duration.ofMinutes(1));
    AtomicReference<String> holderWhileWorking = new AtomicReference<>();
    AgentExecutor work = (context, emitter) -> holderWhileWorking.set(lease.ownerOf(TASK));

    leased(work, lease, Duration.ofMinutes(1)).execute(context(paused()), null);

    assertThat(holderWhileWorking.get()).isEqualTo(OWNER);
  }

  @Test
  void aRunThatLosesItsLease_isInterruptedAndLeavesTheNewHoldersLease() {
    Duration time = Duration.ofMillis(300);
    TaskLease lease = new InMemoryTaskLease(time);
    AgentExecutor work = (context, emitter) -> {
      lease.release(TASK, OWNER);
      lease.acquire(TASK, "another");
      try {
        Thread.sleep(Duration.ofSeconds(10));
      } catch (InterruptedException e) {
        throw new IllegalStateException("interrupted", e);
      }
    };

    assertThatThrownBy(() -> leased(work, lease, time).execute(context(null), null))
        .hasMessage("interrupted");
    assertThat(lease.ownerOf(TASK)).isEqualTo("another");
  }
}
