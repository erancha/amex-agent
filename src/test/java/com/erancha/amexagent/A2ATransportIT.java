package com.erancha.amexagent;

import static com.erancha.amexagent.card.infra.LlmConfiguration.E2E_PROFILE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erancha.amexagent.a2a.CallerSecretFilter;
import com.erancha.amexagent.auth.TestTokens;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfigBuilder;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
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
  private static final ClientCallContext PERSON = credentials(TestTokens.CALLER_SECRET,
      TestTokens.callerToken(TestTokens.SUB));

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

  private static ClientCallContext credentials(String secret, String idToken) {
    Map<String, String> headers = new HashMap<>();
    headers.put(CallerSecretFilter.HEADER, secret);
    if (idToken != null) {
      headers.put("Authorization", "Bearer " + idToken);
    }
    return new ClientCallContext(Map.of(), headers);
  }

  private static Client client() throws Exception {
    return Client.builder(A2A.getAgentCard(BASE_URL))
        .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfigBuilder())
        .clientConfig(new ClientConfig.Builder().setStreaming(true).build())
        .build();
  }

  private record Captured(List<ClientEvent> events, Throwable error) {}

  private static Captured send(Client client, Message message, ClientCallContext credentials)
      throws Exception {
    List<ClientEvent> events = new ArrayList<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    client.sendMessage(message, List.of((event, card) -> {
      events.add(event);
      if (event instanceof TaskUpdateEvent u
          && u.getUpdateEvent() instanceof TaskStatusUpdateEvent s && s.isFinalOrInterrupted()) {
        done.countDown();
      }
      if (event instanceof TaskEvent t && isFinalOrInterrupted(t.getTask().status().state())) {
        done.countDown();
      }
    }), t -> {
      error.set(t);
      done.countDown();
    }, credentials);
    assertThat(done.await(30, TimeUnit.SECONDS)).as("stream finished").isTrue();
    return new Captured(events, error.get());
  }

  private static boolean isFinalOrInterrupted(TaskState state) {
    return state.isFinal() || state.isInterrupted();
  }

  private static Message monthRequest(String month) {
    return Message.builder().role(Message.Role.ROLE_USER).messageId(UUID.randomUUID().toString())
        .parts(List.of(new DataPart(Map.of("month", month)))).build();
  }

  private static List<TaskStatusUpdateEvent> statuses(Captured c) {
    return updates(c, TaskStatusUpdateEvent.class);
  }

  private static <T> List<T> updates(Captured c, Class<T> type) {
    return c.events().stream().filter(TaskUpdateEvent.class::isInstance)
        .map(e -> ((TaskUpdateEvent) e).getUpdateEvent())
        .filter(type::isInstance).map(type::cast).toList();
  }

  private static TaskStatusUpdateEvent lastStatus(Captured c) {
    return statuses(c).getLast();
  }

  @Test
  void card_isServedWithoutAuth() throws Exception {
    AgentCard card = A2A.getAgentCard(BASE_URL);
    assertThat(card.skills().get(0).id()).isEqualTo("monthly-transactions");
    assertThat(card.supportedInterfaces().get(0).url()).isEqualTo(BASE_URL);
  }

  @Test
  void streamingTask_coveredMonth_completesWithOneArtifactOfAllRows() throws Exception {
    try (Client client = client()) {
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
    try (Client client = client()) {
      Captured first = send(client, monthRequest("2026-09"), PERSON);
      TaskStatusUpdateEvent waiting = lastStatus(first);
      assertThat(waiting.status().state()).isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
      @SuppressWarnings("unchecked")
      Map<String, Object> pause = (Map<String, Object>)
          ((DataPart) waiting.status().message().parts().get(1)).data();
      assertThat(pause).containsEntry("reason", "statement-missing")
          .containsEntry("user", TestTokens.SUB);

      Message resume = Message.builder().role(Message.Role.ROLE_USER).messageId("resume-1")
          .taskId(waiting.taskId()).contextId(waiting.contextId())
          .parts(List.of(new DataPart(Map.of("action", "continue")))).build();
      Captured second = send(client, resume, PERSON);
      assertThat(lastStatus(second).status().state())
          .isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);

      assertThat(client.cancelTask(new CancelTaskParams(waiting.taskId()), PERSON)
          .status().state()).isEqualTo(TaskState.TASK_STATE_CANCELED);
    }
  }

  @Test
  void wrongSecret_isRejectedWith401() throws Exception {
    try (Client client = client()) {
      Captured c = send(client, monthRequest("2026-10"),
          credentials("wrong", TestTokens.callerToken(TestTokens.SUB)));
      assertThat(c.error()).isNotNull();
      assertThat(c.error().getCause()).isInstanceOfSatisfying(A2AClientHTTPError.class,
          e -> assertThat(e.getCode()).isEqualTo(401));
    }
  }

  @Test
  void callWithoutThePersonsToken_endsInAuthRequired() throws Exception {
    try (Client client = client()) {
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
    try (Client client = client()) {
      Message bad = Message.builder().role(Message.Role.ROLE_USER).messageId("bad")
          .parts(List.of(new DataPart(Map.of("month", "09/2026")))).build();
      Captured c = send(client, bad, PERSON);
      assertThat(c.error()).isNotNull();
      assertThat(c.error().getMessage()).containsIgnoringCase("month");
    }
  }
}
