package com.erancha.amexagent.a2a;

import com.google.protobuf.Empty;
import com.google.protobuf.MessageOrBuilder;
import org.a2aproject.sdk.grpc.utils.JSONRPCUtils;
import org.a2aproject.sdk.grpc.utils.ProtoUtils.ToProto;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AErrorResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CreateTaskPushNotificationConfigResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.DeleteTaskPushNotificationConfigResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetExtendedAgentCardResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskPushNotificationConfigResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTaskPushNotificationConfigsResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageResponse;

/**
 * Serializes a non-streaming JSON-RPC response to its wire JSON. The SDK keeps the equivalent
 * logic private inside SseFormatter, so this mirrors it for the single-document path: errors in
 * the JSON-RPC error envelope, results converted to their protobuf form and printed by JsonFormat.
 */
final class JsonRpcResponses {
  private JsonRpcResponses() {}

  static String serialize(A2AResponse<?> response) {
    if (response.getError() != null) {
      return JSONRPCUtils.toJsonRPCErrorResponse(response.getId(), response.getError());
    }
    return JSONRPCUtils.toJsonRPCResultResponse(response.getId(), toProto(response));
  }

  private static MessageOrBuilder toProto(A2AResponse<?> response) {
    return switch (response) {
      case GetTaskResponse r -> ToProto.task(r.getResult());
      case CancelTaskResponse r -> ToProto.task(r.getResult());
      case SendMessageResponse r -> ToProto.taskOrMessage(r.getResult());
      case ListTasksResponse r -> ToProto.listTasksResult(r.getResult());
      case CreateTaskPushNotificationConfigResponse r ->
          ToProto.createTaskPushNotificationConfigResponse(r.getResult());
      case GetTaskPushNotificationConfigResponse r ->
          ToProto.getTaskPushNotificationConfigResponse(r.getResult());
      case ListTaskPushNotificationConfigsResponse r ->
          ToProto.listTaskPushNotificationConfigsResponse(r.getResult());
      case DeleteTaskPushNotificationConfigResponse r -> Empty.getDefaultInstance();
      case GetExtendedAgentCardResponse r -> ToProto.getExtendedCardResponse(r.getResult());
      case SendStreamingMessageResponse r -> ToProto.taskOrMessageStream(r.getResult());
      case A2AErrorResponse r ->
          throw new IllegalStateException("An error response always carries its error");
    };
  }
}
