package com.erancha.amexagent.card.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erancha.amexagent.card.api.Category;
import com.erancha.amexagent.card.api.MerchantCategory;
import com.erancha.amexagent.card.api.MerchantQuery;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Verifies the prompt carries every merchant with its sector and every category guide; the answer
 * maps to records.
 */
class SpringAiMerchantClassifierTest {
  private static final List<MerchantQuery> MERCHANTS = List.of(
      new MerchantQuery("סופר פארם בעמ 123", null), new MerchantQuery("פז יהוד", "דלק"));
  private static final String ANSWER = """
      [{"rawMerchant":"סופר פארם בעמ 123","merchant":"Super-Pharm","category":"health",
        "confidence":0.92},
       {"rawMerchant":"פז יהוד","merchant":"Paz","category":"transport","confidence":0.88}]""";

  /** Answers every call with the given response and records the prompt text it was sent. */
  private static final class FakeChatModel implements ChatModel {
    final AtomicReference<String> seenPrompt = new AtomicReference<>();
    private final ChatResponse response;

    FakeChatModel(ChatResponse response) {
      this.response = response;
    }

    static FakeChatModel answering(String text) {
      return new FakeChatModel(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      seenPrompt.set(prompt.getContents());
      return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
      throw new UnsupportedOperationException();
    }
  }

  @Test
  void classify_sendsTaxonomyAndMerchants_parsesStructuredAnswer() {
    FakeChatModel fake = FakeChatModel.answering(ANSWER);
    SpringAiMerchantClassifier classifier = new SpringAiMerchantClassifier(ChatClient.create(fake));

    List<MerchantCategory> out = classifier.classify(MERCHANTS);

    assertThat(fake.seenPrompt.get())
        .contains("Merchants:\nסופר פארם בעמ 123\nפז יהוד [sector: דלק]\n")
        .contains("- health: " + Category.HEALTH.guide()).contains("uncategorized");
    assertThat(out).containsExactly(
        new MerchantCategory("סופר פארם בעמ 123", "Super-Pharm", "health", 0.92),
        new MerchantCategory("פז יהוד", "Paz", "transport", 0.88));
  }

  @Test
  void classify_thinkingBlockBeforeAnswer_parsesTheAnswer() {
    var thoughtThenAnswered = new ChatResponse(List.of(
        new Generation(new AssistantMessage("")), new Generation(new AssistantMessage(ANSWER))));
    var classifier =
        new SpringAiMerchantClassifier(ChatClient.create(new FakeChatModel(thoughtThenAnswered)));

    assertThat(classifier.classify(MERCHANTS)).hasSize(2);
  }

  @Test
  void classify_noAnswerText_failsNamingTheFinishReason() {
    var truncated = new ChatResponse(List.of(new Generation(new AssistantMessage(""),
        ChatGenerationMetadata.builder().finishReason("max_tokens").build())));
    var classifier = new SpringAiMerchantClassifier(ChatClient.create(new FakeChatModel(truncated)));

    assertThatThrownBy(() -> classifier.classify(MERCHANTS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("max_tokens")
        .hasMessageContaining("max-tokens")
        .hasMessageContaining("2 merchants");
  }
}
