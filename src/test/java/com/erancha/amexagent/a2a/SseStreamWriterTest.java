package com.erancha.amexagent.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageResponse;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.UnauthenticatedUser;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Verifies SSE framing, headers and completion, and that a client gone mid-stream or a frame
 * that cannot be serialized releases the request thread and cancels upstream, even when the
 * cancellation itself throws.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SseStreamWriterTest {
  private static final Duration SUBSCRIBE_DEADLINE = Duration.ofSeconds(5);

  private static SendStreamingMessageResponse response(String text) {
    Message m = Message.builder().role(Message.Role.ROLE_AGENT).messageId("a").parts(
        List.of(new TextPart(text))).build();
    return new SendStreamingMessageResponse("req-1", m);
  }

  private static ServerCallContext context() {
    return new ServerCallContext(UnauthenticatedUser.INSTANCE, Map.of(), Set.of(), "1.0");
  }

  /** Emits one item per request, records cancellation, and never completes on its own. */
  private static final class EndlessPublisher implements Flow.Publisher<A2AResponse<?>> {
    private final A2AResponse<?> item;
    final AtomicBoolean cancelled = new AtomicBoolean();

    EndlessPublisher(A2AResponse<?> item) {
      this.item = item;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super A2AResponse<?>> subscriber) {
      subscriber.onSubscribe(new Flow.Subscription() {
        @Override
        public void request(long n) {
          for (int i = 0; i < 3 && !cancelled.get(); i++) {
            subscriber.onNext(item);
          }
        }

        @Override
        public void cancel() {
          cancelled.set(true);
        }
      });
    }
  }

  /** A response whose output stream accepts the first writes and then fails as a gone client. */
  private static final class DisconnectingResponse extends MockHttpServletResponse {
    private final ByteArrayOutputStream accepted = new ByteArrayOutputStream();
    private final int successfulWrites;

    DisconnectingResponse(int successfulWrites) {
      this.successfulWrites = successfulWrites;
    }

    String accepted() {
      return accepted.toString(StandardCharsets.UTF_8);
    }

    @Override
    public ServletOutputStream getOutputStream() {
      return new ServletOutputStream() {
        private int writes;

        @Override
        public void write(int b) throws IOException {
          write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
          if (writes++ >= successfulWrites) {
            throw new IOException("client gone");
          }
          accepted.write(b, off, len);
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setWriteListener(WriteListener l) {}
      };
    }
  }

  @Test
  void write_twoEvents_framesEachWithDataAndIdAndEnds() throws Exception {
    SubmissionPublisher<A2AResponse<?>> publisher = new SubmissionPublisher<>();
    MockHttpServletResponse http = new MockHttpServletResponse();
    // SubmissionPublisher drops items submitted before anyone subscribes, so the producer waits
    // for the writer's subscription before submitting.
    Thread producer = new Thread(() -> {
      Instant deadline = Instant.now().plus(SUBSCRIBE_DEADLINE);
      while (publisher.getNumberOfSubscribers() == 0 && Instant.now().isBefore(deadline)) {
        Thread.onSpinWait();
      }
      publisher.submit(response("one"));
      publisher.submit(response("two"));
      publisher.close();
    });
    producer.start();
    new SseStreamWriter().write(publisher, http, context());
    producer.join();

    assertThat(http.getContentType()).startsWith("text/event-stream");
    assertThat(http.getHeader("Cache-Control")).isEqualTo("no-cache");
    assertThat(http.getHeader("X-Accel-Buffering")).isEqualTo("no");
    String body = http.getContentAsString();
    assertThat(body).contains("data: {").contains("\nid: 0\n\n").contains("\nid: 1\n\n");
    assertThat(body).contains("\"one\"").contains("\"two\"");
  }

  @Test
  void write_clientGoneMidStream_cancelsSubscriptionAndEventConsumer() {
    AtomicBoolean consumerCancelled = new AtomicBoolean();
    ServerCallContext ctx = context();
    ctx.setEventConsumerCancelCallback(() -> consumerCancelled.set(true));
    EndlessPublisher publisher = new EndlessPublisher(response("one"));
    // The opening comment and the first frame are written; the second frame fails.
    DisconnectingResponse http = new DisconnectingResponse(2);

    new SseStreamWriter().write(publisher, http, ctx);

    assertThat(http.accepted()).contains("\"one\"").contains("\nid: 0\n\n")
        .doesNotContain("\nid: 1\n\n");
    assertThat(publisher.cancelled).isTrue();
    assertThat(consumerCancelled).isTrue();
  }

  @Test
  void write_frameCannotBeSerialized_releasesThreadAndCancelsUpstream() {
    AtomicBoolean consumerCancelled = new AtomicBoolean();
    ServerCallContext ctx = context();
    ctx.setEventConsumerCancelCallback(() -> consumerCancelled.set(true));
    SendStreamingMessageResponse unserializable = mock(SendStreamingMessageResponse.class);
    when(unserializable.getResult()).thenThrow(new IllegalStateException("bad agent output"));
    EndlessPublisher publisher = new EndlessPublisher(unserializable);

    new SseStreamWriter().write(publisher, new MockHttpServletResponse(), ctx);

    assertThat(publisher.cancelled).isTrue();
    assertThat(consumerCancelled).isTrue();
  }

  @Test
  void write_cancelCallbackThrows_stillReleasesRequestThread() {
    ServerCallContext ctx = context();
    ctx.setEventConsumerCancelCallback(() -> {
      throw new IllegalStateException("consumer already closed");
    });
    // Delivers the item from another thread, as the SDK does, and drops what onNext throws.
    Flow.Publisher<A2AResponse<?>> publisher = subscriber -> subscriber.onSubscribe(
        new Flow.Subscription() {
          @Override
          public void request(long n) {
            Thread.ofVirtual().start(() -> {
              try {
                subscriber.onNext(response("one"));
              } catch (IllegalStateException expected) {
                // The writer rethrows the callback's failure after releasing the request thread.
              }
            });
          }

          @Override
          public void cancel() {}
        });

    new SseStreamWriter().write(publisher, new DisconnectingResponse(1), ctx);
  }
}
