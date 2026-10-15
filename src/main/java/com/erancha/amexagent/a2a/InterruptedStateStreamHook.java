package com.erancha.amexagent.a2a;

import org.a2aproject.sdk.server.events.StreamCloseHandle;
import org.a2aproject.sdk.server.events.TaskStreamLifecycleHook;
import org.a2aproject.sdk.spec.Event;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;

/**
 * Ends a task's open streams once the task stops to wait for the client (input-required or
 * auth-required), as the protocol requires of interrupted states.
 *
 * Each stream belongs to one HTTP request, and that request's thread stays blocked writing it
 * until the stream ends. A task that waits for a person could hold that thread indefinitely, so
 * the stream is closed instead; events already queued are still delivered first. The task itself
 * stays alive: the client resumes it with a new message, or calls SubscribeToTask to keep
 * listening.
 */
class InterruptedStateStreamHook implements TaskStreamLifecycleHook {

  @Override
  public void onSubscribe(String taskId, StreamCloseHandle handle) {}

  @Override
  public void onUnsubscribe(String taskId, StreamCloseHandle handle) {}

  @Override
  public void onEvent(String taskId, Event event, StreamCloseHandle handle) {
    if (event instanceof TaskStatusUpdateEvent update && update.status().state().isInterrupted()) {
      handle.closeStreams();
    }
  }
}
