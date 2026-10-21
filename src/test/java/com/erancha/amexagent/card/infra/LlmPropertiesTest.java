package com.erancha.amexagent.card.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Verifies only provider:model with a real provider is accepted, naming the variable. */
class LlmPropertiesTest {
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"  "})
  void blank_namesVariableAndShape(String model) {
    assertThatThrownBy(() -> new LlmProperties(model))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("APP_LLM_MODEL must be set to provider:model");
  }

  @ParameterizedTest
  @ValueSource(strings = {"claude-haiku-5-5", ":claude-haiku-5-5", "anthropic:"})
  void withoutBothHalves_namesTheShape(String model) {
    assertThatThrownBy(() -> new LlmProperties(model))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("APP_LLM_MODEL='" + model + "' must be provider:model");
  }

  @ParameterizedTest
  @ValueSource(strings = {"fake", "gemini"})
  void unknownProvider_namesProviderAndTheRealOnes(String provider) {
    assertThatThrownBy(() -> new LlmProperties(provider + ":m"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("APP_LLM_MODEL provider '" + provider + "'")
        .hasMessageContaining("anthropic, openai");
  }

  @Test
  void providerAndModelId_splitAtTheFirstColon() {
    var llm = new LlmProperties("openai:org:gpt-5-mini");
    assertThat(llm.provider()).isEqualTo("openai");
    assertThat(llm.modelId()).isEqualTo("org:gpt-5-mini");
  }
}
