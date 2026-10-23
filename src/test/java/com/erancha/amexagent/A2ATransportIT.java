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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erancha.amexagent.A2ATestClient.Captured;
import com.erancha.amexagent.auth.TestTokens;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.spec.A2AClientHTTPError;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.CancelTaskParams;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Drives the running agent with the SDK's Java client over real HTTP as a registered caller acting
 * for one person: card fetch, a streaming task for the committed sample month to completion, a
 * missing month to input-required, its resume and cancel, cancel on a final task, a call without
 * the person's token to auth-required, and the two rejection paths (wrong secret, wrong params).
 * The e2e profile categorizes with the keyword table, so no API key is needed. The server runs on
 * a fixed port because the card advertises the URL the client must call.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
    "SERVER_PORT=" + A2ATransportIT.PORT, "APP_AGENT_URL=" + A2ATransportIT.BASE_URL})
@ActiveProfiles(E2E_PROFILE)
class A2ATransportIT {
  static final String PORT = "18020";
  static final String BASE_URL = "http://localhost:" + PORT;

  /** The person's statement sits in their own folder under the statements root. */
  @DynamicPropertySource
  static void statementsAndAuth(DynamicPropertyRegistry registry) throws IOException {
    Path root = Files.createTempDirectory("it-statements");
    Files.createDirectories(root.resolve(TestTokens.SUB));
    Files.copy(Path.of("src/test/resources/statements/2026-10.pdf"),
        root.resolve(TestTokens.SUB).resolve("2026-10.pdf"));
    registry.add("APP_STATEMENTS_DIR", root::toString);
    TestTokens.register(registry);
  }

  @Test
  void card_isServedWithoutAuth() throws Exception {
    AgentCard card = A2A.getAgentCard(BASE_URL);
    assertThat(card.skills().get(0).id()).isEqualTo("monthly-transactions");
    assertThat(card.supportedInterfaces().get(0).url()).isEqualTo(BASE_URL);
  }

  @Test
  void streamingTask_coveredMonth_completesWithOneArtifactOfAllRows() throws Exception {
    try (Client client = client(BASE_URL)) {
      Captured c = send(client, monthRequest("2026-10"), PERSON);
      assertThat(c.error()).isNull();
      List<TaskState> states = statuses(c).stream().map(s -> s.status().state()).toList();
      assertThat(states).contains(TaskState.TASK_STATE_WORKING, TaskState.TASK_STATE_COMPLETED);
      List<TaskArtifactUpdateEvent> artifacts = updates(c, TaskArtifactUpdateEvent.class);
      assertThat(artifacts).hasSize(1);
      Map<?, ?> data = (Map<?, ?>) ((DataPart) artifacts.get(0).artifact().parts().get(0)).data();
      assertThat((List<?>) data.get("transactions")).hasSize(24);

      String completedTaskId = lastStatus(c).taskId();
      assertThatThrownBy(() -> client.cancelTask(new CancelTaskParams(completedTaskId),
          PERSON)).hasMessageContaining("Task cannot be canceled");
    }
  }

  @Test
  void streamingTask_missingMonth_asksForInputThenAsksAgainOnContinueThenCancels()
      throws Exception {
    try (Client client = client(BASE_URL)) {
      Captured first = send(client, monthRequest("2026-09"), PERSON);
      TaskStatusUpdateEvent waiting = lastStatus(first);
      assertThat(waiting.status().state()).isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
      @SuppressWarnings("unchecked")
      Map<String, Object> pause = (Map<String, Object>)
          ((DataPart) waiting.status().message().parts().get(1)).data();
      assertThat(pause).containsEntry("reason", "statement-missing")
          .containsEntry("user", TestTokens.SUB);

      Captured second = send(client, continueRequest(waiting), PERSON);
      assertThat(lastStatus(second).status().state())
          .isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);

      assertThat(client.cancelTask(new CancelTaskParams(waiting.taskId()), PERSON)
          .status().state()).isEqualTo(TaskState.TASK_STATE_CANCELED);
    }
  }

  @Test
  void wrongSecret_isRejectedWith401() throws Exception {
    try (Client client = client(BASE_URL)) {
      Captured c = send(client, monthRequest("2026-10"),
          credentials("wrong", TestTokens.callerToken(TestTokens.SUB)));
      assertThat(c.error()).isNotNull();
      assertThat(c.error().getCause()).isInstanceOfSatisfying(A2AClientHTTPError.class,
          e -> assertThat(e.getCode()).isEqualTo(401));
    }
  }

  @Test
  void callWithoutThePersonsToken_endsInAuthRequired() throws Exception {
    try (Client client = client(BASE_URL)) {
      Captured c = send(client, monthRequest("2026-10"),
          credentials(TestTokens.CALLER_SECRET, null));
      assertThat(c.error()).isNull();
      TaskStatusUpdateEvent last = lastStatus(c);
      assertThat(last.status().state()).isEqualTo(TaskState.TASK_STATE_AUTH_REQUIRED);
      @SuppressWarnings("unchecked")
      Map<String, Object> pause = (Map<String, Object>)
          ((DataPart) last.status().message().parts().get(1)).data();
      assertThat(pause).containsEntry("reason", "sign-in-required")
          .containsEntry("month", "2026-10").containsEntry("user", "");
      assertThat(updates(c, TaskArtifactUpdateEvent.class)).isEmpty();
    }
  }

  @Test
  void wrongParams_isInvalidParamsError() throws Exception {
    try (Client client = client(BASE_URL)) {
      Message bad = Message.builder().role(Message.Role.ROLE_USER).messageId("bad")
          .parts(List.of(new DataPart(Map.of("month", "09/2026")))).build();
      Captured c = send(client, bad, PERSON);
      assertThat(c.error()).isNotNull();
      assertThat(c.error().getMessage()).containsIgnoringCase("month");
    }
  }
}
