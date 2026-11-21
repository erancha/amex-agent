package com.erancha.amexagent.card.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.erancha.amexagent.card.infra.ModelCosts.Billed;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;

/**
 * Verifies each call's tokens are billed at their own rate for both providers, and the logged line
 * has the shape scripts/lib/docker_helper.py sums.
 */
class ModelCostsTest {
  @Test
  void anthropicPromptTokensLeaveTheCacheOut() {
    var usage = new DefaultUsage(200, 50, 250, null, 1000L, 300L);

    assertThat(ModelCosts.billed("anthropic", usage)).isEqualTo(new Billed(200, 50, 1000, 300));
  }

  @Test
  void openAiPromptTokensHoldTheCachedPrefix() {
    var usage = new DefaultUsage(1000, 50, 1050, null, 800L, null);

    assertThat(ModelCosts.billed("openai", usage)).isEqualTo(new Billed(200, 50, 800, 0));
  }

  @Test
  void eachTokenIsBilledAtItsModelsListedPrice() {
    double cost = ModelCosts.cost("anthropic:claude-haiku-5-5", new Billed(200, 50, 1000, 300));

    assertThat(cost).isCloseTo((200 * 0.10 + 50 * 0.50 + 1000 * 0.01 + 300 * 0.125) / 1e6,
        within(1e-12));
  }

  @Test
  void theLineIsTheJsonObjectTheCostsReportReads() {
    var billed = new Billed(200, 50, 800, 0);

    String line = ModelCosts.line("categorize", "openai:gpt-5-mini", billed, 1.234);

    assertThat(line).isEqualTo("{\"event\": \"model_call\", \"kind\": \"categorize\", "
        + "\"model\": \"openai:gpt-5-mini\", \"input_tokens\": 200, \"output_tokens\": 50, "
        + "\"cache_read_tokens\": 800, \"cache_write_tokens\": 0, \"seconds\": 1.23, "
        + "\"cost\": " + ModelCosts.cost("openai:gpt-5-mini", billed) + "}");
  }

  @Test
  void aModelWithoutAListedPriceIsRefused() {
    assertThatThrownBy(() -> ModelCosts.requirePrice("anthropic:claude-unlisted"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("PRICE_PER_MILLION");
  }
}
