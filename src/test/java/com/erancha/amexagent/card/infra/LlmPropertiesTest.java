package com.erancha.amexagent.card.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Verifies only a real provider with a model is accepted, naming the offending variable. */
class LlmPropertiesTest {
  @ParameterizedTest
  @ValueSource(strings = {"fake", "gemini"})
  void unknownProvider_namesProvider(String provider) {
    assertThatThrownBy(() -> new LlmProperties(provider, "m"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("APP_LLM_PROVIDER")
        .hasMessageContaining(provider);
  }

  @Test
  void realProviderWithBlankModel_namesModelVariable() {
    assertThatThrownBy(() -> new LlmProperties("anthropic", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("APP_LLM_MODEL");
  }

  @Test
  void realProviderWithModel_isValid() {
    assertThat(new LlmProperties("openai", "m")).isNotNull();
  }
}
