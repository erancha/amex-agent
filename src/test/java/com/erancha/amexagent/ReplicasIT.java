package com.erancha.amexagent;

import static com.erancha.amexagent.A2ATestClient.PERSON;
import static com.erancha.amexagent.A2ATestClient.client;
import static com.erancha.amexagent.A2ATestClient.continueRequest;
import static com.erancha.amexagent.A2ATestClient.lastStatus;
import static com.erancha.amexagent.A2ATestClient.monthRequest;
import static com.erancha.amexagent.A2ATestClient.send;
import static com.erancha.amexagent.A2ATestClient.updates;
import static com.erancha.amexagent.card.infra.LlmConfiguration.E2E_PROFILE;
import static org.assertj.core.api.Assertions.assertThat;

import com.erancha.amexagent.A2ATestClient.Captured;
import com.erancha.amexagent.auth.TestTokens;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Two complete agents sharing one Redis and one statements folder, standing in for two replicas
 * behind the edge. A month requested through the first agent pauses because its statement is
 * missing; after the statement is loaded, a continue sent through the second agent completes the
 * task with every row. Each agent advertises its own URL, so the client calls each one directly.
 * Needs Docker for the Redis container.
 */
class ReplicasIT {
  private static final int FIRST_PORT = 18030;
  private static final int SECOND_PORT = 18031;
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
    args.add("--APP_TASKS_REDIS_URL=redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
    return new SpringApplicationBuilder(AmexAgentApplication.class).profiles(E2E_PROFILE)
        .run(args.toArray(String[]::new));
  }

  private static String url(int port) {
    return "http://localhost:" + port;
  }

  @Test
  void aContinueThroughTheOtherReplica_completesTheTaskTheFirstPaused() throws Exception {
    try (Client viaFirst = client(url(FIRST_PORT)); Client viaSecond = client(url(SECOND_PORT))) {
      TaskStatusUpdateEvent waiting = lastStatus(send(viaFirst, monthRequest("2026-10"), PERSON));
      assertThat(waiting.status().state()).isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);

      Path folder = Files.createDirectories(statements.resolve(TestTokens.SUB));
      Files.copy(Path.of("src/test/resources/statements/2026-10.pdf"),
          folder.resolve("2026-10.pdf"));
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
}
