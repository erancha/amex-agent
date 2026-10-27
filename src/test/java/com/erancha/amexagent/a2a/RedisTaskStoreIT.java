package com.erancha.amexagent.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.server.tasks.TaskPersistenceException;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Two stores on one Redis stand in for two replicas: a task one store saves, the other reads back
 * in full and lists, and a deletion or expiry is seen by both. Needs Docker for the Redis
 * container.
 */
class RedisTaskStoreIT {
  private static GenericContainer<?> redis;
  private RedisTaskStore first;
  private RedisTaskStore second;

  @BeforeAll
  static void startRedis() {
    redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    redis.start();
  }

  @AfterAll
  static void stopRedis() {
    redis.stop();
  }

  static String redisUrl() {
    return "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
  }

  @BeforeEach
  void stores() {
    first = new RedisTaskStore(redisUrl(), Duration.ofMinutes(5));
    second = new RedisTaskStore(redisUrl(), Duration.ofMinutes(5));
    first.list(ListTasksParams.builder().build(), null).tasks()
        .forEach(task -> first.delete(task.id()));
  }

  @AfterEach
  void close() {
    first.close();
    second.close();
  }

  private static Task paused(String id, String contextId) {
    Message question = Message.builder().role(Message.Role.ROLE_AGENT).messageId("q-" + id)
        .taskId(id).contextId(contextId)
        .parts(List.of(new DataPart(Map.of("reason", "statement-missing", "month", "2026-10"))))
        .build();
    return Task.builder().id(id).contextId(contextId)
        .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, question, null)).build();
  }

  private static Task working(Task task) {
    return Task.builder(task).status(new TaskStatus(TaskState.TASK_STATE_WORKING)).build();
  }

  private static Task completed(Task task) {
    return Task.builder(task).status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).build();
  }

  @Test
  void aTaskSavedByOneReplica_isReadWholeByAnother() {
    Task task = paused("t1", "c1");
    first.save(task, false);

    Task read = second.get("t1");
    assertThat(read.contextId()).isEqualTo("c1");
    assertThat(read.status().state()).isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
    assertThat(((DataPart) read.status().message().parts().get(0)).data())
        .isEqualTo(Map.of("reason", "statement-missing", "month", "2026-10"));
    assertThat(second.isTaskActive("t1")).isTrue();
    assertThat(second.isTaskFinalized("t1")).isFalse();

    second.save(completed(read), false);
    assertThat(first.isTaskFinalized("t1")).isTrue();
    assertThat(first.isTaskActive("t1")).isFalse();
  }

  @Test
  void aSaveThatWouldChangeAFinalTask_isRefusedAndLeavesItAsItIs() {
    Task task = paused("t1", "c1");
    first.save(completed(task), false);

    assertThatThrownBy(() -> second.save(working(task), false))
        .isInstanceOf(TaskPersistenceException.class).hasMessageContaining("t1");
    assertThat(first.get("t1").status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
  }

  @Test
  void workingTasks_areListedWithTheTextTheyAreStoredAs() {
    first.save(working(paused("t1", "c1")), false);
    second.save(paused("t2", "c2"), false);

    List<RedisTaskStore.StoredTask> working = second.workingTasks();

    assertThat(working).extracting(stored -> stored.task().id()).containsExactly("t1");
    assertThat(working.getFirst().json()).isEqualTo(first.workingTasks().getFirst().json());
  }

  @Test
  void aReplacement_isStoredOnlyWhileTheTaskIsStoredAsExpected() {
    first.save(working(paused("t1", "c1")), false);
    String read = second.workingTasks().getFirst().json();
    Task failed = Task.builder(first.get("t1"))
        .status(new TaskStatus(TaskState.TASK_STATE_FAILED)).build();

    first.save(Task.builder(working(paused("t1", "c1"))).metadata(Map.of("step", "2")).build(),
        false);
    assertThat(second.replaceIfUnchanged("t1", read, failed)).isFalse();
    assertThat(first.get("t1").status().state()).isEqualTo(TaskState.TASK_STATE_WORKING);

    String reread = second.workingTasks().getFirst().json();
    assertThat(second.replaceIfUnchanged("t1", reread, failed)).isTrue();
    assertThat(first.get("t1").status().state()).isEqualTo(TaskState.TASK_STATE_FAILED);
  }

  @Test
  void anUnknownTask_isNullAndNeitherActiveNorFinalized() {
    assertThat(first.get("missing")).isNull();
    assertThat(first.isTaskActive("missing")).isFalse();
    assertThat(first.isTaskFinalized("missing")).isFalse();
  }

  @Test
  void listing_coversEveryReplicasTasksAndFiltersLikeTheSdk() {
    first.save(paused("t1", "c1"), false);
    second.save(paused("t2", "c2"), false);

    assertThat(first.list(ListTasksParams.builder().build(), null).tasks())
        .extracting(Task::id).containsExactlyInAnyOrder("t1", "t2");
    assertThat(second.list(ListTasksParams.builder().contextId("c1").build(), null).tasks())
        .extracting(Task::id).containsExactly("t1");
  }

  @Test
  void aDeletedTask_isGoneForEveryReplica() {
    first.save(paused("t1", "c1"), false);
    second.delete("t1");

    assertThat(first.get("t1")).isNull();
    assertThat(first.list(ListTasksParams.builder().build(), null).tasks()).isEmpty();
  }

  @Test
  void aTaskExpiresItsTtlAfterItsLastSave() throws InterruptedException {
    try (RedisTaskStore shortLived = new RedisTaskStore(redisUrl(), Duration.ofMillis(300))) {
      shortLived.save(paused("t1", "c1"), false);
      assertThat(first.get("t1")).isNotNull();
      Thread.sleep(600);
      assertThat(first.get("t1")).isNull();
      assertThat(first.list(ListTasksParams.builder().build(), null).tasks()).isEmpty();
    }
  }

  @Test
  void anUnreachableRedis_failsAtConstruction() {
    assertThatThrownBy(() -> new RedisTaskStore("redis://localhost:1", Duration.ofMinutes(1)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_TASKS_REDIS_URL");
  }
}
