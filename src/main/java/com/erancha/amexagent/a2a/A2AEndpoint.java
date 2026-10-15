package com.erancha.amexagent.a2a;

import com.google.gson.JsonParseException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Flow;
import org.a2aproject.sdk.grpc.utils.JSONRPCUtils;
import org.a2aproject.sdk.jsonrpc.common.json.IdJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.InvalidParamsJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.MethodNotFoundJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AErrorResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2ARequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CreateTaskPushNotificationConfigRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.DeleteTaskPushNotificationConfigRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetExtendedAgentCardRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskPushNotificationConfigRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTaskPushNotificationConfigsRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SubscribeToTaskRequest;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.TaskOperation;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.ContentTypeNotSupportedError;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.InvalidRequestError;
import org.a2aproject.sdk.spec.JSONParseError;
import org.a2aproject.sdk.spec.MethodNotFoundError;
import org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys;
import org.a2aproject.sdk.transport.jsonrpc.handler.JSONRPCHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The JSON-RPC edge: one POST route for every A2A method. The SDK parses the body, the request is
 * dispatched to the matching JSONRPCHandler method, and the outcome is written either as one JSON
 * document or, for the two streaming methods, as Server-Sent Events. A POST that is not JSON gets
 * the protocol's content-type error. JSON-RPC errors travel with HTTP 200, as the protocol
 * requires. Bodies stay strings end to end because the SDK serializes with Gson and protobuf
 * JsonFormat, not Jackson.
 */
@RestController
public class A2AEndpoint {
  private static final Logger log = LoggerFactory.getLogger(A2AEndpoint.class);
  private final JSONRPCHandler handler;
  private final SseStreamWriter sse;

  public A2AEndpoint(JSONRPCHandler handler, SseStreamWriter sse) {
    this.handler = handler;
    this.sse = sse;
  }

  /** Exactly one of the two fields is set: a single JSON document or an SSE event stream. */
  private record Outcome(
      A2AResponse<?> single, Flow.Publisher<? extends A2AResponse<?>> stream) {
    static Outcome single(A2AResponse<?> response) {
      return new Outcome(response, null);
    }

    static Outcome stream(Flow.Publisher<? extends A2AResponse<?>> publisher) {
      return new Outcome(null, publisher);
    }
  }

  @PostMapping(path = "/", consumes = MediaType.APPLICATION_JSON_VALUE)
  public void handle(@RequestBody String body, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    ServerCallContext context = CallContexts.from(request);
    Outcome outcome = process(body, request, context);
    if (outcome.stream() != null) {
      sse.write(outcome.stream(), response, context);
      return;
    }
    writeJson(response, JsonRpcResponses.serialize(outcome.single()));
  }

  /**
   * Catches every POST the JSON route does not accept because of its Content-Type. The body is
   * not read, so the error carries no request id.
   */
  @PostMapping(path = "/")
  public void rejectContentType(HttpServletResponse response) throws IOException {
    writeJson(response, JSONRPCUtils.toJsonRPCErrorResponse(null,
        new ContentTypeNotSupportedError(null, "Content-Type must be application/json", null)));
  }

  private static void writeJson(HttpServletResponse response, String json) throws IOException {
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write(json);
  }

  private Outcome process(String body, HttpServletRequest request, ServerCallContext context) {
    A2ARequest<?> parsed;
    try {
      parsed = JSONRPCUtils.parseRequestBody(body, CallContexts.tenant(request.getRequestURI()));
    } catch (A2AError e) {
      return Outcome.single(new A2AErrorResponse(e));
    } catch (InvalidParamsJsonMappingException e) {
      return Outcome.single(
          new A2AErrorResponse(e.getId(), new InvalidParamsError(null, e.getMessage(), null)));
    } catch (MethodNotFoundJsonMappingException e) {
      return Outcome.single(
          new A2AErrorResponse(e.getId(), new MethodNotFoundError(null, e.getMessage(), null)));
    } catch (IdJsonMappingException e) {
      return Outcome.single(
          new A2AErrorResponse(e.getId(), new InvalidRequestError(null, e.getMessage(), null)));
    } catch (JsonMappingException e) {
      return Outcome.single(
          new A2AErrorResponse(new InvalidRequestError(null, e.getMessage(), null)));
    } catch (JsonParseException | JsonProcessingException e) {
      return Outcome.single(new A2AErrorResponse(new JSONParseError(e.getMessage())));
    } catch (RuntimeException e) {
      return internalError(null, e);
    }
    context.getState().put(JSONRPCContextKeys.METHOD_NAME_KEY, parsed.getMethod());
    try {
      return dispatch(parsed, context);
    } catch (A2AError e) {
      return Outcome.single(new A2AErrorResponse(parsed.getId(), e));
    } catch (RuntimeException e) {
      return internalError(parsed.getId(), e);
    }
  }

  /** Logs the cause server-side; the caller sees only a generic message, never internals. */
  private static Outcome internalError(Object id, RuntimeException e) {
    log.error("Internal error while processing a JSON-RPC request", e);
    return Outcome.single(new A2AErrorResponse(id, new InternalError("Internal error")));
  }

  private Outcome dispatch(A2ARequest<?> request, ServerCallContext context) {
    return switch (request) {
      case GetTaskRequest r -> Outcome.single(handler.onGetTask(r, context));
      case CancelTaskRequest r -> Outcome.single(handler.onCancelTask(r, context));
      case ListTasksRequest r -> Outcome.single(handler.onListTasks(r, context));
      case SendMessageRequest r -> Outcome.single(handler.onMessageSend(r, context));
      case CreateTaskPushNotificationConfigRequest r ->
          Outcome.single(handler.setPushNotificationConfig(r, context));
      case GetTaskPushNotificationConfigRequest r ->
          Outcome.single(handler.getPushNotificationConfig(r, context));
      case ListTaskPushNotificationConfigsRequest r ->
          Outcome.single(handler.listPushNotificationConfigs(r, context));
      case DeleteTaskPushNotificationConfigRequest r ->
          Outcome.single(handler.deletePushNotificationConfig(r, context));
      case GetExtendedAgentCardRequest r ->
          Outcome.single(handler.onGetExtendedCardRequest(r, context));
      // SDK authorization is off; authorizeTaskAccess still runs the task-exists and terminal-state
      // checks before the stream opens, so those errors return as one JSON document.
      case SendStreamingMessageRequest r -> {
        handler.authorizeTaskAccess(
            r.getParams().message().taskId(), context, TaskOperation.MESSAGE_SEND_STREAM);
        yield Outcome.stream(handler.onMessageSendStream(r, context));
      }
      case SubscribeToTaskRequest r -> {
        handler.authorizeTaskAccess(r.getParams().id(), context, TaskOperation.SUBSCRIBE_TO_TASK);
        yield Outcome.stream(handler.onSubscribeToTask(r, context));
      }
    };
  }
}
