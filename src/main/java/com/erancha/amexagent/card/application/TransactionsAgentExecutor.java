package com.erancha.amexagent.card.application;

import com.erancha.amexagent.auth.InvalidTokenException;
import com.erancha.amexagent.auth.UserResolver;
import com.erancha.amexagent.auth.VerifiedUser;
import com.erancha.amexagent.card.api.Statement;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskNotCancelableError;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives one A2A task for the monthly-transactions skill: submitted, working, artifact batches,
 * completed; or a pause the client resumes with {"action": "continue"} on the same task. Two
 * pauses exist: input-required when no statement of the person covers the month, and
 * auth-required when the request carries no acceptable token for the person. Each pause's data
 * part names the reason, the month and the person (empty while nobody has been verified), so a
 * continue needs no server-side memory beyond the task: the month is read back from it, and a
 * continue by another person is answered with auth-required rather than another person's data.
 * The statement-missing data part also names the path of the page where a person loads the file.
 */
public class TransactionsAgentExecutor implements AgentExecutor {
  private static final Logger log = LoggerFactory.getLogger(TransactionsAgentExecutor.class);
  static final String REASON_STATEMENT_MISSING = "statement-missing";
  static final String REASON_SIGN_IN_REQUIRED = "sign-in-required";
  private static final Set<String> PAUSE_REASONS =
      Set.of(REASON_STATEMENT_MISSING, REASON_SIGN_IN_REQUIRED);
  private static final Set<TaskState> PAUSED_STATES =
      Set.of(TaskState.TASK_STATE_INPUT_REQUIRED, TaskState.TASK_STATE_AUTH_REQUIRED);

  /** What a paused task recorded: its month and the person it is for ("" before any sign-in). */
  private record Pause(YearMonth month, String user) {}

  private final TransactionsService service;
  private final String statementsPage;
  private final UserResolver users;

  public TransactionsAgentExecutor(TransactionsService service, String statementsPage,
      UserResolver users) {
    this.service = service;
    this.statementsPage = statementsPage;
    this.users = users;
  }

  @Override
  public void execute(RequestContext context, AgentEmitter emitter) throws A2AError {
    Message message = context.getMessage();
    if (message == null) {
      throw new InvalidParamsError("A message is required");
    }
    Pause paused = switch (MonthRequest.from(message)) {
      case MonthRequest.Fetch fetch -> new Pause(fetch.month(), "");
      case MonthRequest.Continue ignored -> pause(context.getTask());
    };
    YearMonth month = paused.month();
    if (context.getTask() == null) {
      emitter.submit();
    }
    VerifiedUser user;
    try {
      user = users.resolve(context);
    } catch (InvalidTokenException e) {
      log.info("Task {}: no acceptable token: {}", context.getTaskId(), e.getMessage());
      requireSignIn(emitter, paused, "amex-agent could not verify who you are ("
          + e.getMessage() + ").");
      return;
    }
    if (!paused.user().isEmpty() && !paused.user().equals(user.sub())) {
      log.info("Task {}: continued by another Google account than it was started for",
          context.getTaskId());
      requireSignIn(emitter, paused,
          "A different Google account signed in than the one this request was started for.");
      return;
    }
    emitter.startWork(emitter.newAgentMessage(
        List.of(new TextPart("Parsing statement for " + month)), null));
    Optional<Statement> statement = service.monthlyTransactions(user.sub(), month);
    if (statement.isEmpty()) {
      log.info("Task {}: no statement covers {}", context.getTaskId(), month);
      emitter.requiresInput(emitter.newAgentMessage(List.of(
          new TextPart("No statement covers " + month + "."),
          new DataPart(Map.of("reason", REASON_STATEMENT_MISSING, "month", month.toString(),
              "page", statementsPage, "user", user.sub()))),
          null));
      return;
    }
    List<Map<String, Object>> batches = ArtifactMapper.batches(statement.get());
    String artifactId = "transactions-" + context.getTaskId();
    for (int i = 0; i < batches.size(); i++) {
      emitter.addArtifact(List.<Part<?>>of(new DataPart(batches.get(i))), artifactId,
          "transactions", null, i > 0, i == batches.size() - 1);
    }
    log.info("Task {}: streamed {} transactions for {} in {} batch(es)", context.getTaskId(),
        statement.get().transactions().size(), month, batches.size());
    emitter.complete();
  }

  @Override
  public void cancel(RequestContext context, AgentEmitter emitter) throws A2AError {
    Task task = context.getTask();
    if (task != null && task.status().state().isFinal()) {
      throw new TaskNotCancelableError();
    }
    emitter.cancel();
  }

  private static void requireSignIn(AgentEmitter emitter, Pause paused, String why) {
    emitter.requiresAuth(emitter.newAgentMessage(List.of(
        new TextPart(why + " Sign in again and continue."),
        new DataPart(Map.of("reason", REASON_SIGN_IN_REQUIRED, "month", paused.month().toString(),
            "user", paused.user()))),
        null));
  }

  /**
   * The SDK moves a task's status message into its history as soon as the follow-up user message
   * arrives, so the pause message is looked up there, newest first.
   */
  private static Pause pause(Task task) {
    if (task == null || !PAUSED_STATES.contains(task.status().state())) {
      throw new InvalidParamsError("continue needs a task that is waiting for input or sign-in");
    }
    List<Message> candidates = new ArrayList<>(task.history());
    Collections.reverse(candidates);
    if (task.status().message() != null) {
      candidates.add(0, task.status().message());
    }
    for (Message candidate : candidates) {
      for (Part<?> part : candidate.parts()) {
        if (part instanceof DataPart data && data.data() instanceof Map<?, ?> map
            && map.get("reason") instanceof String reason && PAUSE_REASONS.contains(reason)) {
          Object user = map.get("user");
          if (user == null) {
            throw new IllegalStateException("A pause message names its person: " + map);
          }
          return new Pause(YearMonth.parse(String.valueOf(map.get("month"))), user.toString());
        }
      }
    }
    throw new InvalidParamsError("continue needs a task that is waiting for a statement or sign-in");
  }
}
