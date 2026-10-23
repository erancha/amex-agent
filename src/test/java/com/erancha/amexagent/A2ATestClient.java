package com.erancha.amexagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.erancha.amexagent.a2a.CallerSecretFilter;
import com.erancha.amexagent.auth.TestTokens;
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
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;

/**
 * Drives a running agent with the SDK's Java client over JSON-RPC streaming, as the registered
 * test caller acting for one person. It sends month requests and continues, and records each one's
 * events until the task ends or pauses.
 */
final class A2ATestClient {
  static final ClientCallContext PERSON = credentials(TestTokens.CALLER_SECRET,
      TestTokens.callerToken(TestTokens.SUB));

  private A2ATestClient() {}

  static ClientCallContext credentials(String secret, String idToken) {
    Map<String, String> headers = new HashMap<>();
    headers.put(CallerSecretFilter.HEADER, secret);
    if (idToken != null) {
      headers.put("Authorization", "Bearer " + idToken);
    }
    return new ClientCallContext(Map.of(), headers);
  }

  static Client client(String baseUrl) throws Exception {
    return Client.builder(A2A.getAgentCard(baseUrl))
        .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfigBuilder())
        .clientConfig(new ClientConfig.Builder().setStreaming(true).build())
        .build();
  }

  record Captured(List<ClientEvent> events, Throwable error) {}

  static Captured send(Client client, Message message, ClientCallContext credentials)
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

  static Message monthRequest(String month) {
    return Message.builder().role(Message.Role.ROLE_USER).messageId(UUID.randomUUID().toString())
        .parts(List.of(new DataPart(Map.of("month", month)))).build();
  }

  static Message continueRequest(TaskStatusUpdateEvent waiting) {
    return Message.builder().role(Message.Role.ROLE_USER).messageId(UUID.randomUUID().toString())
        .taskId(waiting.taskId()).contextId(waiting.contextId())
        .parts(List.of(new DataPart(Map.of("action", "continue")))).build();
  }

  static List<TaskStatusUpdateEvent> statuses(Captured c) {
    return updates(c, TaskStatusUpdateEvent.class);
  }

  static <T> List<T> updates(Captured c, Class<T> type) {
    return c.events().stream().filter(TaskUpdateEvent.class::isInstance)
        .map(e -> ((TaskUpdateEvent) e).getUpdateEvent())
        .filter(type::isInstance).map(type::cast).toList();
  }

  static TaskStatusUpdateEvent lastStatus(Captured c) {
    return statuses(c).getLast();
  }
}
