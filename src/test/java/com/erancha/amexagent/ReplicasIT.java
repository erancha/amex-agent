package com.erancha.amexagent;

import static com.erancha.amexagent.A2ATestClient.PERSON;
import static com.erancha.amexagent.A2ATestClient.client;
import static com.erancha.amexagent.A2ATestClient.continueRequest;
import static com.erancha.amexagent.A2ATestClient.credentials;
import static com.erancha.amexagent.A2ATestClient.lastStatus;
import static com.erancha.amexagent.A2ATestClient.monthRequest;
import static com.erancha.amexagent.A2ATestClient.send;
import static com.erancha.amexagent.A2ATestClient.statuses;
import static com.erancha.amexagent.A2ATestClient.updates;
import static com.erancha.amexagent.card.infra.LlmConfiguration.E2E_PROFILE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.erancha.amexagent.A2ATestClient.Captured;
import com.erancha.amexagent.a2a.RedisTaskStore;
import com.erancha.amexagent.auth.TestTokens;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Two complete agents sharing one Redis and one statements folder, standing in for two replicas
 * behind the edge. A continue sent through either agent completes a task the other paused; two
 * continues sent at once through both run the task once; and a task left working by a stopped
 * replica is failed by the lease sweep. Each test acts for a person of its own, so one test's
 * statement does not answer another's request. Each agent advertises its own URL, so the client
 * calls each one directly. The lease lasts one second, so a sweep pass runs every second. Needs
 * Docker for the Redis container.
 */
class ReplicasIT {
  private static final int FIRST_PORT = 18030;
  private static final int SECOND_PORT = 18031;
  private static final Duration LEASE = Duration.ofSeconds(1);
  private static GenericContainer<?> redis;
  private static Path statements;
  private static ConfigurableApplicationContext first;
  private static ConfigurableApplicationContext second;

  @BeforeAll
  static void start() throws Exception {
    redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    redis.start();
    statements = Files.createTempDirectory("replicas-statements");
    first = boot(FIRST_PORT);
    second = boot(SECOND_PORT);
  }

  @AfterAll
  static void stop() {
    first.close();
    second.close();
    redis.stop();
  }

  /**
   * The settings go in as command-line arguments because those take precedence over any
   * environment variables the developer has exported.
   */
  private static ConfigurableApplicationContext boot(int port) {
    List<String> args = new ArrayList<>();
    TestTokens.properties().forEach((key, value) -> args.add("--" + key + "=" + value));
    args.add("--SERVER_PORT=" + port);
    args.add("--APP_AGENT_URL=" + url(port));
    args.add("--APP_STATEMENTS_DIR=" + statements);
    args.add("--APP_TASKS_REDIS_URL=" + redisUrl());
    args.add("--APP_TASKS_LEASE=" + LEASE.toSeconds() + "s");
    return new SpringApplicationBuilder(AmexAgentApplication.class).profiles(E2E_PROFILE)
        .run(args.toArray(String[]::new));
  }

  private static String redisUrl() {
    return "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
  }

  /** The test caller acting for the given person. */
  private static ClientCallContext person(String sub) {
    return credentials(TestTokens.CALLER_SECRET, TestTokens.callerToken(sub));
  }

  private static void loadStatement(String sub) throws Exception {
    Path folder = Files.createDirectories(statements.resolve(sub));
    Files.copy(Path.of("src/test/resources/statements/2026-10.pdf"),
        folder.resolve("2026-10.pdf"), StandardCopyOption.REPLACE_EXISTING);
  }

  /** The rows of every artifact of the task, across all its batches. */
  private static int rows(Task task) {
    int rows = 0;
    for (Artifact artifact : task.artifacts()) {
      for (Part<?> part : artifact.parts()) {
        rows += ((List<?>) ((Map<?, ?>) ((DataPart) part).data()).get("transactions")).size();
      }
    }
    return rows;
  }

  private static String url(int port) {
    return "http://localhost:" + port;
  }

  @Test
  void aContinueThroughTheOtherReplica_completesTheTaskTheFirstPaused() throws Exception {
    try (Client viaFirst = client(url(FIRST_PORT)); Client viaSecond = client(url(SECOND_PORT))) {
      TaskStatusUpdateEvent waiting = lastStatus(send(viaFirst, monthRequest("2026-10"), PERSON));
      assertThat(waiting.status().state()).isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);

      loadStatement(TestTokens.SUB);
      Captured continued = send(viaSecond, continueRequest(waiting), PERSON);

      assertThat(continued.error()).isNull();
      assertThat(lastStatus(continued).status().state())
          .isEqualTo(TaskState.TASK_STATE_COMPLETED);
      List<TaskArtifactUpdateEvent> artifacts = updates(continued, TaskArtifactUpdateEvent.class);
      Map<?, ?> rows = (Map<?, ?>) ((DataPart) artifacts.get(0).artifact().parts().get(0)).data();
      assertThat((List<?>) rows.get("transactions")).hasSize(24);
      assertThat(viaFirst.getTask(new TaskQueryParams(waiting.taskId()), PERSON).status().state())
          .isEqualTo(TaskState.TASK_STATE_COMPLETED);
    }
  }

  @Test
  void twoContinuesSentAtOnceThroughBothReplicas_runTheTaskOnce() throws Exception {
    String sub = "continues-at-once";
    try (Client viaFirst = client(url(FIRST_PORT)); Client viaSecond = client(url(SECOND_PORT))) {
      TaskStatusUpdateEvent waiting =
          lastStatus(send(viaFirst, monthRequest("2026-10"), person(sub)));
      assertThat(waiting.status().state()).isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
      loadStatement(sub);

      CountDownLatch go = new CountDownLatch(1);
      List<Callable<Captured>> continues = List.of(viaFirst, viaSecond).stream()
          .<Callable<Captured>>map(via -> () -> {
            go.await();
            return send(via, continueRequest(waiting), person(sub));
          }).toList();
      List<Captured> outcomes = new ArrayList<>();
      try (ExecutorService senders = Executors.newFixedThreadPool(2)) {
        var futures = continues.stream().map(senders::submit).toList();
        go.countDown();
        for (var future : futures) {
          outcomes.add(future.get());
        }
      }

      assertThat(outcomes).filteredOn(c -> c.error() == null && !statuses(c).isEmpty()
          && lastStatus(c).status().state() == TaskState.TASK_STATE_COMPLETED).hasSize(1);
      Task task = viaFirst.getTask(new TaskQueryParams(waiting.taskId()), person(sub));
      assertThat(task.status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
      assertThat(rows(task)).isEqualTo(24);
    }
  }

  @Test
  void aTaskLeftWorkingByAStoppedReplica_isFailedAndTheMonthIsFetchedAgain() throws Exception {
    String sub = "stopped-replica";
    loadStatement(sub);
    try (RedisTaskStore stoppedReplica = new RedisTaskStore(redisUrl(), Duration.ofMinutes(5))) {
      stoppedReplica.save(Task.builder().id("left-working").contextId("c-left")
          .status(new TaskStatus(TaskState.TASK_STATE_WORKING)).build(), false);
    }
    try (Client viaFirst = client(url(FIRST_PORT)); Client viaSecond = client(url(SECOND_PORT))) {
      await().atMost(LEASE.multipliedBy(10)).until(() -> viaSecond
          .getTask(new TaskQueryParams("left-working"), person(sub)).status().state()
          == TaskState.TASK_STATE_FAILED);
      TaskStatus failed =
          viaFirst.getTask(new TaskQueryParams("left-working"), person(sub)).status();
      assertThat(((TextPart) failed.message().parts().getFirst()).text())
          .isEqualTo("stopped while working on this task; send the request again");

      Captured again = send(viaSecond, monthRequest("2026-10"), person(sub));
      assertThat(lastStatus(again).status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
    }
  }
}
