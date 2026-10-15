package com.erancha.amexagent.card.infra;

import com.erancha.amexagent.card.api.Category;
import com.erancha.amexagent.card.api.MerchantCategory;
import com.erancha.amexagent.card.api.MerchantClassifier;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;

/**
 * Classifies a batch of merchants in one structured-output call. The model sees each category with
 * its guide and the raw strings and returns, per merchant, a readable display name, a category and
 * a confidence. A model that reasons first has its reasoning reported by Spring AI as a generation
 * ahead of the answer, so the answer is read from the last generation. A reply without answer text
 * (the reasoning can use up the output limit before the JSON starts) fails naming the finish
 * reason and the limit's property.
 */
public class SpringAiMerchantClassifier implements MerchantClassifier {
  private static final String SYSTEM = """
      You categorize credit-card merchants for an Israeli household budget. Merchant strings come
      from a card statement and may be Hebrew, English, truncated, or carry branch numbers.
      For each input return: rawMerchant (echoed exactly), merchant (a short readable display
      name, Latin letters when the brand is international, otherwise clean Hebrew), category
      (exactly one of the categories below), confidence (0.0 to 1.0, your certainty in the
      category).

      Categories:
      %s

      A category is what the money buys, not how it is paid: a membership, a standing order
      (הוראת קבע) or an installment plan goes where its goods or service belong, so a gym is
      health and a streaming service is entertainment. Judge the business, not a generic word in
      its name: "סופר" or "מרכז" also start the names of shops that are not supermarkets. When
      the business is unknown to you, answer "uncategorized" with a low confidence rather than a
      guess. Answer only with the JSON array, one element per input, in input order."""
      .formatted(categoryGuide());

  private final ChatClient chat;
  private final BeanOutputConverter<List<MerchantCategory>> converter =
      new BeanOutputConverter<>(new ParameterizedTypeReference<>() {});

  public SpringAiMerchantClassifier(ChatClient chat) {
    this.chat = chat;
  }

  private static String categoryGuide() {
    return Arrays.stream(Category.values())
        .map(c -> "- " + c.wireValue() + ": " + c.guide())
        .collect(Collectors.joining("\n"));
  }

  @Override
  public List<MerchantCategory> classify(List<String> rawMerchants) {
    List<Generation> generations = chat.prompt()
        .system(SYSTEM)
        .user("Merchants:\n" + String.join("\n", rawMerchants) + "\n" + converter.getFormat())
        .call()
        .chatResponse()
        .getResults();
    Generation answer = generations.get(generations.size() - 1);
    String text = answer.getOutput().getText();
    if (text == null || text.isBlank()) {
      throw new IllegalStateException("The model answered with no text (finish reason "
          + answer.getMetadata().getFinishReason() + ") for a batch of " + rawMerchants.size()
          + " merchants; if the reason is max_tokens, raise spring.ai.<provider>.chat.options"
          + ".max-tokens or lower APP_CATEGORIZER_BATCH_SIZE");
    }
    return converter.convert(text);
  }
}
