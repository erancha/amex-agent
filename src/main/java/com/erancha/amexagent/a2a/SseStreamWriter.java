package com.erancha.amexagent.a2a;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AResponse;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.util.sse.SseFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes a JSON-RPC response stream to the servlet response as Server-Sent Events. Servlet output
 * is synchronous I/O owned by the request thread, so that thread takes the subscription and
 * blocks until the stream ends, which ties the stream's lifetime to the request. Every frame is
 * flushed so the caller sees it as it happens. Any failure to write a frame (the caller has gone,
 * or the frame cannot be serialized) and an interrupt of the request thread all end the stream
 * the same way: the upstream subscription and the SDK's event consumer are cancelled, so the
 * agent's queue polling stops and nothing is written after the request thread returns.
 */
@Component
public class SseStreamWriter {
  private static final Logger log = LoggerFactory.getLogger(SseStreamWriter.class);
  private static final String STREAM_OPENED_COMMENT = ": SSE stream started\n\n";

  public void write(Flow.Publisher<? extends A2AResponse<?>> publisher,
      HttpServletResponse response, ServerCallContext context) {
    StreamSubscriber subscriber = new StreamSubscriber(response, context);
    publisher.subscribe(subscriber);
    try {
      subscriber.done.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      subscriber.abandon("request thread interrupted");
    }
  }

  /**
   * Writes each item as one SSE frame. Signals arrive serially from the publisher, but abandon
   * can also come from the request thread, so writes and abandon share the instance lock.
   */
  private static final class StreamSubscriber implements Flow.Subscriber<A2AResponse<?>> {
    private final HttpServletResponse response;
    private final ServerCallContext context;
    private final CountDownLatch done = new CountDownLatch(1);
    private Flow.Subscription subscription;
    private OutputStream out;
    private long nextEventId;
    private boolean abandoned;

    StreamSubscriber(HttpServletResponse response, ServerCallContext context) {
      this.response = response;
      this.context = context;
    }

    @Override
    public void onSubscribe(Flow.Subscription s) {
      synchronized (this) {
        subscription = s;
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        // Stops reverse proxies such as nginx from buffering the stream into one late burst.
        response.setHeader("X-Accel-Buffering", "no");
        try {
          out = response.getOutputStream();
          send(STREAM_OPENED_COMMENT);
        } catch (IOException | RuntimeException e) {
          abandon(e.toString());
          return;
        }
      }
      s.request(Long.MAX_VALUE);
    }

    @Override
    public synchronized void onNext(A2AResponse<?> item) {
      if (abandoned) {
        return;
      }
      try {
        send(SseFormatter.formatResponseAsSSE(item, nextEventId++));
      } catch (IOException | RuntimeException e) {
        abandon(e.toString());
      }
    }

    @Override
    public void onError(Throwable t) {
      log.warn("SSE stream failed", t);
      done.countDown();
    }

    @Override
    public void onComplete() {
      done.countDown();
    }

    private void send(String frame) throws IOException {
      out.write(frame.getBytes(StandardCharsets.UTF_8));
      out.flush();
    }

    synchronized void abandon(String reason) {
      if (abandoned) {
        return;
      }
      abandoned = true;
      log.warn("Abandoning SSE stream ({}); cancelling the event consumer", reason);
      // The request thread waits on done, so it is released even if a cancellation throws.
      try {
        context.invokeEventConsumerCancelCallback();
        // Null only when the request thread is interrupted before the publisher calls onSubscribe.
        if (subscription != null) {
          subscription.cancel();
        }
      } finally {
        done.countDown();
      }
    }
  }
}
