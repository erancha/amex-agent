package com.erancha.amexagent.card.application;

import com.erancha.amexagent.card.api.Statement;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskNotCancelableError;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives one A2A task for the monthly-transactions skill: submitted, working, artifact batches,
 * completed; or input-required when no statement covers the month, resumable with
 * {"action": "continue"} on the same task. The month of a waiting task is carried in the
 * input-required message's data part (found in the task history once the user replies), so a
 * continue needs no server-side memory beyond the task. The same data part names the path of the
 * page where a person loads the missing statement.
 */
public class TransactionsAgentExecutor implements AgentExecutor {
  private static final Logger log = LoggerFactory.getLogger(TransactionsAgentExecutor.class);
  static final String REASON_STATEMENT_MISSING = "statement-missing";

  private final TransactionsService service;
  private final String statementsPage;

  public TransactionsAgentExecutor(TransactionsService service, String statementsPage) {
    this.service = service;
    this.statementsPage = statementsPage;
  }

  @Override
  public void execute(RequestContext context, AgentEmitter emitter) throws A2AError {
    Message message = context.getMessage();
    if (message == null) {
      throw new InvalidParamsError("A message is required");
    }
    YearMonth month = switch (MonthRequest.from(message)) {
      case MonthRequest.Fetch fetch -> fetch.month();
      case MonthRequest.Continue ignored -> waitingMonth(context.getTask());
    };
    if (context.getTask() == null) {
      emitter.submit();
    }
    emitter.startWork(emitter.newAgentMessage(
        List.of(new TextPart("Parsing statement for " + month)), null));
    Optional<Statement> statement = service.monthlyTransactions(month);
    if (statement.isEmpty()) {
      log.info("Task {}: no statement covers {}", context.getTaskId(), month);
      emitter.requiresInput(emitter.newAgentMessage(List.of(
          new TextPart("No statement covers " + month + ". Load it on the statements page and "
              + "continue."),
          new DataPart(Map.of("reason", REASON_STATEMENT_MISSING, "month", month.toString(),
              "page", statementsPage))),
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

  /**
   * The SDK moves a task's status message into its history as soon as the follow-up user message
   * arrives, so the input-required message is looked up there, newest first.
   */
  private static YearMonth waitingMonth(Task task) {
    if (task == null || task.status().state() != TaskState.TASK_STATE_INPUT_REQUIRED) {
      throw new InvalidParamsError("continue needs a task that is waiting for input");
    }
    List<Message> candidates = new ArrayList<>(task.history());
    Collections.reverse(candidates);
    if (task.status().message() != null) {
      candidates.add(0, task.status().message());
    }
    for (Message candidate : candidates) {
      for (Part<?> part : candidate.parts()) {
        if (part instanceof DataPart data && data.data() instanceof Map<?, ?> map
            && REASON_STATEMENT_MISSING.equals(map.get("reason"))) {
          return YearMonth.parse(String.valueOf(map.get("month")));
        }
      }
    }
    throw new InvalidParamsError("continue needs a task that is waiting for a statement");
  }
}
