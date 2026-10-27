package com.erancha.amexagent.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The sweep on Redis, one pass at a time: a working task found without a lease on two passes in a
 * row, unchanged in between, is failed; a leased task, or one that changed, is left working.
 * Needs Docker for the Redis container.
 */
class LeaseSweepIT {
  private static GenericContainer<?> redis;
  private RedisTaskStore store;
  private RedisTaskLease lease;
  private LeaseSweep sweep;

  @BeforeAll
  static void startRedis() {
    redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    redis.start();
  }

  @AfterAll
  static void stopRedis() {
    redis.stop();
  }

  @BeforeEach
  void open() {
    String url = "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
    try (TasksRedis connection = new TasksRedis(url)) {
      connection.commands().flushall();
    }
    store = new RedisTaskStore(url, Duration.ofMinutes(5));
    lease = new RedisTaskLease(url, Duration.ofMinutes(1));
    sweep = new LeaseSweep(store, lease);
  }

  @AfterEach
  void close() {
    store.close();
    lease.close();
  }

  private static Task working(String id) {
    return Task.builder().id(id).contextId("c-" + id)
        .status(new TaskStatus(TaskState.TASK_STATE_WORKING)).build();
  }

  @Test
  void aWorkingTaskWithoutALeaseOnTwoPasses_isFailedWithTheRetryText() {
    store.save(working("t1"), false);

    Map<String, String> unleased = sweep.sweep(Map.of());
    assertThat(store.get("t1").status().state()).isEqualTo(TaskState.TASK_STATE_WORKING);
    sweep.sweep(unleased);

    TaskStatus status = store.get("t1").status();
    assertThat(status.state()).isEqualTo(TaskState.TASK_STATE_FAILED);
    assertThat(((TextPart) status.message().parts().getFirst()).text())
        .isEqualTo("stopped while working on this task; send the request again");
  }

  @Test
  void aLeasedTask_isLeftWorking() {
    store.save(working("t1"), false);
    lease.acquire("t1", "replica/call");

    sweep.sweep(sweep.sweep(Map.of()));

    assertThat(store.get("t1").status().state()).isEqualTo(TaskState.TASK_STATE_WORKING);
  }

  @Test
  void aTaskThatChangedBetweenPasses_isLeftWorking() {
    store.save(working("t1"), false);

    Map<String, String> unleased = sweep.sweep(Map.of());
    store.save(Task.builder(working("t1")).metadata(Map.of("step", "2")).build(), false);
    sweep.sweep(unleased);

    assertThat(store.get("t1").status().state()).isEqualTo(TaskState.TASK_STATE_WORKING);
  }
}
