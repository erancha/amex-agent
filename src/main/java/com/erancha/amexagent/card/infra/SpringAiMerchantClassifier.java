package com.erancha.amexagent.card.infra;

import com.erancha.amexagent.card.api.Category;
import com.erancha.amexagent.card.api.MerchantCategory;
import com.erancha.amexagent.card.api.MerchantClassifier;
import com.erancha.amexagent.card.api.MerchantQuery;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;

/**
 * Classifies a batch of merchants in one structured-output call. The model sees each category with
 * its guide and the raw strings, each beside the issuer's sector label when the statement prints
 * one, and returns, per merchant, a readable display name, a category and a confidence. A model
 * that reasons first has its reasoning reported by Spring AI as a generation ahead of the answer,
 * so the answer is read from the last generation. A reply without answer text (the reasoning can
 * use up the output limit before the JSON starts) fails naming the finish reason and the limit's
 * property. Each answered call is logged as a "categorize" model call, a reply without answer
 * text included, since the provider bills it.
 */
public class SpringAiMerchantClassifier implements MerchantClassifier {
  private static final String SYSTEM = """
      You categorize credit-card merchants for an Israeli household budget. Each input line is a
      merchant string as the card statement prints it (Hebrew, English, truncated, or carrying
      branch numbers), followed, when the statement prints one, by the issuer's own sector label
      for that business in brackets: [sector: ...]. The sector is the issuer's hint about what the
      business does; lean on it when the merchant string alone says little, as with a person's
      name billed under a medical sector.
      For each input return: rawMerchant (the merchant string echoed exactly, without the
      bracketed sector), merchant (a readable display name in the language the statement prints
      it in: a Hebrew string stays Hebrew and an English one English, never translated or
      transliterated, even for a well-known Israeli brand; a self-explaining brand stays short,
      "Netflix" or "סלקום", while a brand that alone does not say what the business does keeps
      the words that do, "הראל ביטוח בריאות"), category (exactly one of the categories below),
      confidence (0.0 to 1.0, your certainty in the category).

      Categories:
      %s

      A category is what the money buys, not how it is paid: a membership, a standing order
      (הוראת קבע) or an installment plan goes where its goods or service belong, so a gym is
      health and a streaming service is entertainment. Judge the business, not a generic word in
      its name: "סופר" or "מרכז" also start the names of shops that are not supermarkets. When
      the business is unknown to you, answer "uncategorized" with a low confidence rather than a
      guess. Answer only with the JSON array, one element per input, in input order."""
      .formatted(categoryGuide());

  private static final String KIND = "categorize";

  private final ChatClient chat;
  private final String model;
  private final String provider;
  private final BeanOutputConverter<List<MerchantCategory>> converter =
      new BeanOutputConverter<>(new ParameterizedTypeReference<>() {});

  /** model: the APP_LLM_MODEL provider:model the client calls, one ModelCosts lists. */
  public SpringAiMerchantClassifier(ChatClient chat, String model) {
    ModelCosts.requirePrice(model);
    this.chat = chat;
    this.model = model;
    this.provider = model.substring(0, model.indexOf(':'));
  }

  private static String categoryGuide() {
    return Arrays.stream(Category.values())
        .map(c -> "- " + c.wireValue() + ": " + c.guide())
        .collect(Collectors.joining("\n"));
  }

  @Override
  public List<MerchantCategory> classify(List<MerchantQuery> merchants) {
    String lines = merchants.stream().map(SpringAiMerchantClassifier::inputLine)
        .collect(Collectors.joining("\n"));
    long started = System.nanoTime();
    ChatResponse response = chat.prompt()
        .system(SYSTEM)
        .user("Merchants:\n" + lines + "\n" + converter.getFormat())
        .call()
        .chatResponse();
    ModelCosts.log(KIND, model, ModelCosts.billed(provider, response.getMetadata().getUsage()),
        (System.nanoTime() - started) / 1e9);
    List<Generation> generations = response.getResults();
    Generation answer = generations.get(generations.size() - 1);
    String text = answer.getOutput().getText();
    if (text == null || text.isBlank()) {
      throw new IllegalStateException("The model answered with no text (finish reason "
          + answer.getMetadata().getFinishReason() + ") for a batch of " + merchants.size()
          + " merchants; if the reason is max_tokens, raise spring.ai.<provider>.chat.options"
          + ".max-tokens or lower APP_CATEGORIZER_BATCH_SIZE");
    }
    return converter.convert(text);
  }

  private static String inputLine(MerchantQuery merchant) {
    if (merchant.sector() == null) {
      return merchant.rawMerchant();
    }
    return merchant.rawMerchant() + " [sector: " + merchant.sector() + "]";
  }
}
