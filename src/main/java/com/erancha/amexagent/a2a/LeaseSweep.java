package com.erancha.amexagent.a2a;

import io.lettuce.core.RedisException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.a2aproject.sdk.server.tasks.TaskPersistenceException;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fails the tasks whose replica stopped while working on them, so the caller can send the request
 * again instead of waiting on a task nobody runs.
 *
 * A replica holds a task's lease for as long as it works on the task. A working task found
 * without a lease on two passes in a row, stored unchanged in between, was left by a replica that
 * stopped: the sweep stores it failed, with a text asking to send the request again. Two passes,
 * because a replica releases the lease a moment before the SDK stores the task's final state, and
 * one pass could fall in between. The failed task replaces the stored one only if that is still
 * unchanged, so of two replicas sweeping one task, one wins, and a task that moved on is left as
 * it is.
 *
 * Every replica runs the sweep, once at start and then once per lease time. A pass that cannot
 * reach Redis is logged and the next one tries again, so a Redis restart does not end the sweep;
 * any other error is logged and ends it.
 */
final class LeaseSweep implements AutoCloseable {
  static final String STOPPED = "stopped while working on this task; send the request again";
  private static final Logger log = LoggerFactory.getLogger(LeaseSweep.class);

  private final RedisTaskStore store;
  private final TaskLease lease;
  private final ScheduledExecutorService schedule = Executors.newSingleThreadScheduledExecutor(
      Thread.ofPlatform().name("lease-sweep").daemon(true).factory());
  // Key: id of a working task found without a lease on the last pass. Value: its stored JSON then.
  // Read and written only by the schedule's one thread.
  private Map<String, String> unleased = Map.of();

  LeaseSweep(RedisTaskStore store, TaskLease lease) {
    this.store = store;
    this.lease = lease;
  }

  void start(Duration interval) {
    schedule.scheduleWithFixedDelay(this::pass, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
  }

  /**
   * One pass: fails the working tasks found without a lease on the previous pass and stored
   * unchanged since, and returns those found without a lease now (task id to stored JSON).
   */
  Map<String, String> sweep(Map<String, String> unleasedBefore) {
    Map<String, String> unleasedNow = new HashMap<>();
    for (RedisTaskStore.StoredTask stored : store.workingTasks()) {
      String taskId = stored.task().id();
      if (lease.ownerOf(taskId) != null) {
        continue;
      }
      if (stored.json().equals(unleasedBefore.get(taskId))) {
        fail(stored);
      } else {
        unleasedNow.put(taskId, stored.json());
      }
    }
    return unleasedNow;
  }

  @Override
  public void close() {
    schedule.shutdownNow();
  }

  private void pass() {
    try {
      unleased = sweep(unleased);
    } catch (RedisException | TaskPersistenceException e) {
      log.error("The lease sweep could not reach Redis; it tries again next pass", e);
    } catch (RuntimeException e) {
      log.error("The lease sweep stopped; tasks left by a stopped replica stay working", e);
      throw e;
    }
  }

  private void fail(RedisTaskStore.StoredTask stored) {
    Task task = stored.task();
    Message why = Message.builder().role(Message.Role.ROLE_AGENT)
        .messageId(UUID.randomUUID().toString()).taskId(task.id()).contextId(task.contextId())
        .parts(List.of(new TextPart(STOPPED))).build();
    List<Message> history = new ArrayList<>(task.history());
    if (task.status().message() != null) {
      history.add(task.status().message());
    }
    Task failed = Task.builder(task).history(history)
        .status(new TaskStatus(TaskState.TASK_STATE_FAILED, why, null)).build();
    if (store.replaceIfUnchanged(task.id(), stored.json(), failed)) {
      log.warn("Task {} had no replica working on it; marked failed", task.id());
    } else {
      log.info("Task {} changed while being swept; left as it now is", task.id());
    }
  }
}
