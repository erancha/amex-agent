package com.erancha.amexagent.card.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erancha.amexagent.auth.InvalidTokenException;
import com.erancha.amexagent.auth.UserResolver;
import com.erancha.amexagent.auth.VerifiedUser;
import com.erancha.amexagent.card.api.MerchantCategory;
import com.erancha.amexagent.card.api.Statement;
import com.erancha.amexagent.card.api.Transaction;
import com.erancha.amexagent.card.infra.PdfStatementLayout;
import com.erancha.amexagent.card.infra.PdfStatementParser;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.events.EventQueue;
import org.a2aproject.sdk.server.events.EventQueueClosedException;
import org.a2aproject.sdk.server.events.EventQueueItem;
import org.a2aproject.sdk.server.events.InMemoryQueueManager;
import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Event;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskNotCancelableError;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

/**
 * Verifies the A2A lifecycle the executor drives: submitted/working/artifacts/completed for a
 * covered month, input-required for a missing one, auth-required for an unverified person,
 * continue on the same task by the same person only, cancel rules, and InvalidParams for anything
 * else. Uses the SDK's in-memory queue, with its event-bus processor running, to capture the
 * emitted events.
 */
class TransactionsAgentExecutorTest {
  private static final String USER = "1000";
  private static final String OTHER_USER = "2000";
  private static final int POLL_MILLIS = 50;
  private static final Duration SETTLE_DEADLINE = Duration.ofSeconds(5);
  private static final Statement OCTOBER =
      new PdfStatementParser(PdfStatementLayout.AMEX_ISRAEL)
          .parse(Path.of("src/test/resources/statements/2026-10.pdf"));

  private final InMemoryTaskStore store = new InMemoryTaskStore();
  private final MainEventBus bus = new MainEventBus();
  private final InMemoryQueueManager queues = new InMemoryQueueManager(store, bus);

  TransactionsAgentExecutorTest() {
    new MainEventBusProcessor(bus, store, (event, task) -> { }, queues).start();
  }

  /** The person every request is for; null stands for a request without an acceptable token. */
  private final AtomicReference<String> signedIn = new AtomicReference<>(USER);

  /** Statements of USER alone; another person's folder is always empty. */
  private TransactionsAgentExecutor executor(Map<YearMonth, Statement> covered) {
    MerchantCategorizer categorizer = new MerchantCategorizer(
        merchants -> merchants.stream()
            .map(m -> new MerchantCategory(m.rawMerchant(), m.rawMerchant(), "other", 0.9))
            .toList(),
        0.85, 0.6, 40);
    UserResolver users = context -> {
      String sub = signedIn.get();
      if (sub == null) {
        throw new InvalidTokenException("the token has expired");
      }
      return new VerifiedUser(sub, null, null);
    };
    return new TransactionsAgentExecutor(new TransactionsService(
        (u, m) -> u.equals(USER) ? Optional.ofNullable(covered.get(m)) : Optional.empty(),
        categorizer), "/statements.html", users);
  }

  private static Message user(Map<String, Object> data, String taskId, String contextId) {
    return Message.builder().role(Message.Role.ROLE_USER).messageId("m-" + data)
        .parts(List.of(new DataPart(data))).taskId(taskId).contextId(contextId).build();
  }

  /**
   * The SDK has already moved the pause message into the history by continue time, after the
   * person's own request, whose data part names no reason.
   */
  private static Task waiting(List<Event> firstRun) {
    TaskStatusUpdateEvent last = (TaskStatusUpdateEvent) firstRun.get(firstRun.size() - 1);
    return Task.builder().id("t1").contextId("c1")
        .status(new TaskStatus(last.status().state(), null, null))
        .history(List.of(user(Map.of("month", "2026-10"), "t1", "c1"), last.status().message()))
        .artifacts(List.of()).build();
  }

  private static TaskStatusUpdateEvent last(List<Event> events) {
    return (TaskStatusUpdateEvent) events.get(events.size() - 1);
  }

  private static Map<String, Object> pauseData(List<Event> events) {
    return asMap((DataPart) last(events).status().message().parts().stream()
        .filter(DataPart.class::isInstance).findFirst().orElseThrow());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(DataPart part) {
    return (Map<String, Object>) part.data();
  }

  private static List<TaskState> states(List<Event> events) {
    return events.stream().filter(TaskStatusUpdateEvent.class::isInstance)
        .map(e -> ((TaskStatusUpdateEvent) e).status().state()).toList();
  }

  private List<Event> run(TransactionsAgentExecutor executor, Message message, Task existing)
      throws EventQueueClosedException {
    RequestContext context = new RequestContext.Builder()
        .setParams(new MessageSendParams(message, null, null, null))
        .setTaskId(existing == null ? "t1" : existing.id())
        .setContextId(existing == null ? "c1" : existing.contextId())
        .setTask(existing)
        .build();
    EventQueue queue = queues.createOrTap(context.getTaskId());
    executor.execute(context, new AgentEmitter(context, queue));
    return drainUntilSettled(queue);
  }

  private static List<Event> drainUntilSettled(EventQueue queue) throws EventQueueClosedException {
    List<Event> events = new ArrayList<>();
    Instant deadline = Instant.now().plus(SETTLE_DEADLINE);
    while (Instant.now().isBefore(deadline)) {
      EventQueueItem item = queue.dequeueEventItem(POLL_MILLIS);
      if (item == null) {
        continue;
      }
      events.add(item.getEvent());
      if (item.getEvent() instanceof TaskStatusUpdateEvent update
          && update.isFinalOrInterrupted()) {
        return events;
      }
    }
    throw new AssertionError("Task did not settle within " + SETTLE_DEADLINE + "; got " + events);
  }

  @Test
  void execute_coveredMonth_streamsSubmittedWorkingArtifactsCompleted() throws Exception {
    List<Event> events = run(executor(Map.of(YearMonth.of(2026, 10), OCTOBER)),
        user(Map.of("month", "2026-10"), null, null), null);

    TaskStatusUpdateEvent working = (TaskStatusUpdateEvent) events.get(1);
    assertThat(((TextPart) working.status().message().parts().get(0)).text())
        .isEqualTo("Parsing statement for 2026-10");
    assertThat(states(events)).containsExactly(TaskState.TASK_STATE_SUBMITTED,
        TaskState.TASK_STATE_WORKING, TaskState.TASK_STATE_COMPLETED);
    List<TaskArtifactUpdateEvent> artifacts = events.stream()
        .filter(TaskArtifactUpdateEvent.class::isInstance)
        .map(TaskArtifactUpdateEvent.class::cast).toList();
    assertThat(artifacts).hasSize(1);
    assertThat(artifacts.get(0).artifact().artifactId()).isEqualTo("transactions-t1");
    assertThat(artifacts.get(0).append()).isFalse();
    assertThat(artifacts.get(0).lastChunk()).isTrue();
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>)
        ((DataPart) artifacts.get(0).artifact().parts().get(0)).data();
    assertThat(data).containsEntry("month", "2026-10");
    assertThat((List<?>) data.get("transactions")).hasSize(24);
  }

  @Test
  void execute_missingMonth_asksForInputWithReasonMonthAndPage() throws Exception {
    List<Event> events =
        run(executor(Map.of()), user(Map.of("month", "2026-09"), null, null), null);
    TaskStatusUpdateEvent last = (TaskStatusUpdateEvent) events.get(events.size() - 1);
    assertThat(last.status().state()).isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
    assertThat(last.isFinalOrInterrupted()).isTrue();
    Message m = last.status().message();
    assertThat(m.parts()).anySatisfy(p -> assertThat(p).isInstanceOf(TextPart.class));
    DataPart data = (DataPart) m.parts().stream().filter(DataPart.class::isInstance)
        .findFirst().orElseThrow();
    assertThat(asMap(data)).containsEntry("reason", "statement-missing")
        .containsEntry("month", "2026-09").containsEntry("page", "/statements.html")
        .containsEntry("user", USER);
  }

  @Test
  void execute_withoutAnAcceptableToken_asksForSignInNamingTheReasonAndMonth() throws Exception {
    signedIn.set(null);
    List<Event> events = run(executor(Map.of(YearMonth.of(2026, 10), OCTOBER)),
        user(Map.of("month", "2026-10"), null, null), null);
    assertThat(states(events)).containsExactly(TaskState.TASK_STATE_SUBMITTED,
        TaskState.TASK_STATE_AUTH_REQUIRED);
    assertThat(last(events).isFinalOrInterrupted()).isTrue();
    assertThat(((TextPart) last(events).status().message().parts().get(0)).text())
        .contains("the token has expired").contains("Sign in again and continue");
    assertThat(pauseData(events)).containsEntry("reason", "sign-in-required")
        .containsEntry("month", "2026-10").containsEntry("user", "");
    assertThat(events).noneMatch(TaskArtifactUpdateEvent.class::isInstance);
  }

  @Test
  void execute_continueAfterSignInRequired_withAFreshToken_completes() throws Exception {
    TransactionsAgentExecutor executor = executor(Map.of(YearMonth.of(2026, 10), OCTOBER));
    signedIn.set(null);
    Task task = waiting(run(executor, user(Map.of("month", "2026-10"), null, null), null));
    assertThat(task.status().state()).isEqualTo(TaskState.TASK_STATE_AUTH_REQUIRED);

    signedIn.set(USER);
    List<Event> second = run(executor, user(Map.of("action", "continue"), "t1", "c1"), task);
    assertThat(states(second)).containsExactly(TaskState.TASK_STATE_WORKING,
        TaskState.TASK_STATE_COMPLETED);
  }

  @Test
  void execute_continueByAnotherPerson_asksForSignInAndServesNothing() throws Exception {
    Map<YearMonth, Statement> folder = new HashMap<>();
    TransactionsAgentExecutor executor = executor(folder);
    Task task = waiting(run(executor, user(Map.of("month", "2026-10"), null, null), null));
    folder.put(YearMonth.of(2026, 10), OCTOBER);

    signedIn.set(OTHER_USER);
    List<Event> second = run(executor, user(Map.of("action", "continue"), "t1", "c1"), task);
    assertThat(states(second)).containsExactly(TaskState.TASK_STATE_AUTH_REQUIRED);
    assertThat(((TextPart) last(second).status().message().parts().get(0)).text())
        .contains("different Google account");
    assertThat(pauseData(second)).containsEntry("reason", "sign-in-required")
        .containsEntry("month", "2026-10").containsEntry("user", USER);
    assertThat(second).noneMatch(TaskArtifactUpdateEvent.class::isInstance);
  }

  @Test
  void execute_anotherPersonsMonth_isMissingForThem() throws Exception {
    signedIn.set(OTHER_USER);
    List<Event> events = run(executor(Map.of(YearMonth.of(2026, 10), OCTOBER)),
        user(Map.of("month", "2026-10"), null, null), null);
    assertThat(last(events).status().state()).isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
    assertThat(pauseData(events)).containsEntry("user", OTHER_USER);
  }

  @Test
  void execute_continueAfterInputRequired_rescansAndCompletes() throws Exception {
    Map<YearMonth, Statement> folder = new HashMap<>();
    TransactionsAgentExecutor executor = executor(folder);
    Task task = waiting(run(executor, user(Map.of("month", "2026-10"), null, null), null));

    folder.put(YearMonth.of(2026, 10), OCTOBER);
    List<Event> second = run(executor, user(Map.of("action", "continue"), "t1", "c1"), task);
    assertThat(states(second)).containsExactly(TaskState.TASK_STATE_WORKING,
        TaskState.TASK_STATE_COMPLETED);
    assertThat(second).anyMatch(TaskArtifactUpdateEvent.class::isInstance);
  }

  @Test
  void execute_continueStillMissing_asksAgain() throws Exception {
    TransactionsAgentExecutor executor = executor(Map.of());
    Task task = waiting(run(executor, user(Map.of("month", "2026-10"), null, null), null));
    List<Event> second = run(executor, user(Map.of("action", "continue"), "t1", "c1"), task);
    TaskStatusUpdateEvent last = (TaskStatusUpdateEvent) second.get(second.size() - 1);
    assertThat(last.status().state()).isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
  }

  @Test
  void execute_invalidRequest_throwsInvalidParams() {
    assertThatThrownBy(() -> run(executor(Map.of()),
        Message.builder().role(Message.Role.ROLE_USER).messageId("m")
            .parts(List.of(new TextPart("hi"))).build(), null))
        .isInstanceOf(InvalidParamsError.class);
  }

  @Test
  void cancel_finalTask_throwsNotCancelable() {
    Task done = Task.builder().id("t9").contextId("c9")
        .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED, null, null))
        .history(List.of()).artifacts(List.of()).build();
    RequestContext context = new RequestContext.Builder().setTaskId("t9").setContextId("c9")
        .setTask(done).build();
    assertThatThrownBy(() -> executor(Map.of()).cancel(context,
        new AgentEmitter(context, queues.createOrTap("t9"))))
        .isInstanceOf(TaskNotCancelableError.class);
  }

  @Test
  void execute_sixtyTransactions_streamsThreeAppendedBatchesOnOneArtifact() throws Exception {
    List<Transaction> rows = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      rows.add(Transaction.parsed("id" + i, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 2),
          "merchant " + i, null, BigDecimal.TEN, null, null, false, false, null));
    }
    Statement big = new Statement(YearMonth.of(2026, 10), "ILS", LocalDate.of(2026, 10, 2), rows);
    List<Event> events = run(executor(Map.of(YearMonth.of(2026, 10), big)),
        user(Map.of("month", "2026-10"), null, null), null);

    List<TaskArtifactUpdateEvent> artifacts = events.stream()
        .filter(TaskArtifactUpdateEvent.class::isInstance)
        .map(TaskArtifactUpdateEvent.class::cast).toList();
    assertThat(artifacts).extracting(TaskArtifactUpdateEvent::append)
        .containsExactly(false, true, true);
    assertThat(artifacts).extracting(TaskArtifactUpdateEvent::lastChunk)
        .containsExactly(false, false, true);
    assertThat(artifacts).extracting(a -> a.artifact().artifactId())
        .containsOnly("transactions-t1");
    assertThat(artifacts).extracting(a -> ((List<?>) asMap(
        (DataPart) a.artifact().parts().get(0)).get("transactions")).size())
        .containsExactly(25, 25, 10);
  }

  @Test
  void execute_continueOnWorkingTaskWithOldMissingMessage_throwsInvalidParams()
      throws Exception {
    Task input = waiting(run(executor(Map.of()),
        user(Map.of("month", "2026-10"), null, null), null));
    Task working = Task.builder(input)
        .status(new TaskStatus(TaskState.TASK_STATE_WORKING, null, null)).build();
    assertThatThrownBy(() -> run(executor(Map.of()),
        user(Map.of("action", "continue"), "t1", "c1"), working))
        .isInstanceOf(InvalidParamsError.class);
  }

  @Test
  void execute_continueWithoutStatementMissingPart_throwsInvalidParams() {
    Task task = Task.builder().id("t1").contextId("c1")
        .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED,
            Message.builder().role(Message.Role.ROLE_AGENT).messageId("a")
                .parts(List.of(new TextPart("hello"))).build(), null))
        .history(List.of()).artifacts(List.of()).build();
    assertThatThrownBy(() -> run(executor(Map.of()),
        user(Map.of("action", "continue"), "t1", "c1"), task))
        .isInstanceOf(InvalidParamsError.class);
  }

  @Test
  void cancel_nonFinalTask_emitsCanceled() throws Exception {
    Task working = Task.builder().id("t8").contextId("c8")
        .status(new TaskStatus(TaskState.TASK_STATE_WORKING, null, null))
        .history(List.of()).artifacts(List.of()).build();
    RequestContext context = new RequestContext.Builder().setTaskId("t8").setContextId("c8")
        .setTask(working).build();
    EventQueue queue = queues.createOrTap("t8");
    executor(Map.of()).cancel(context, new AgentEmitter(context, queue));
    assertThat(states(drainUntilSettled(queue))).containsExactly(TaskState.TASK_STATE_CANCELED);
  }
}
