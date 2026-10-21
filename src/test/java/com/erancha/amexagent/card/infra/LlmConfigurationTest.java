package com.erancha.amexagent.card.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erancha.amexagent.AmexAgentApplication;
import com.erancha.amexagent.card.api.MerchantClassifier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Verifies the classifier choice and its fail-fast checks: outside the e2e profile only a real
 * provider:model with its API key boots, and that value selects the provider's ChatModel; the e2e
 * profile boots on the keyword table with no model setting at all.
 */
class LlmConfigurationTest {
  // Command-line arguments outrank the developer's exported environment, so a real key or model
  // in the calling shell cannot mask a missing one here.
  private static ConfigurableApplicationContext boot(String... args) {
    String[] base = {"--APP_STATEMENTS_DIR=src/test/resources/statements",
        "--APP_A2A_AUTH_REQUIRED=false", "--ANTHROPIC_API_KEY=", "--OPENAI_API_KEY="};
    return new SpringApplicationBuilder(AmexAgentApplication.class)
        .web(WebApplicationType.NONE)
        .run(Stream.concat(Stream.of(base), Stream.of(args)).toArray(String[]::new));
  }

  @Test
  void boot_blankModel_failsNamingTheVariable() {
    assertThatThrownBy(() -> boot("--APP_LLM_MODEL="))
        .rootCause().hasMessageContaining("APP_LLM_MODEL must be set to provider:model");
  }

  // The split runs before any bean exists, so the refusal is the exception itself, not a cause.
  @Test
  void boot_fakeProvider_isRejectedBeforeBeansAreCreated() {
    assertThatThrownBy(() -> boot("--APP_LLM_MODEL=fake:m"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("APP_LLM_MODEL provider 'fake'");
  }

  @Test
  void boot_realProviderWithoutKey_failsNamingTheVariable() {
    assertThatThrownBy(() -> boot("--APP_LLM_MODEL=anthropic:m"))
        .rootCause().hasMessageContaining("ANTHROPIC_API_KEY");
  }

  @Test
  void boot_realProviderWithKey_selectsItsChatModel() {
    try (var context = boot("--APP_LLM_MODEL=anthropic:m", "--ANTHROPIC_API_KEY=k")) {
      var env = context.getEnvironment();
      assertThat(env.getProperty("spring.ai.model.chat")).isEqualTo("anthropic");
      assertThat(env.getProperty("spring.ai.anthropic.chat.options.model")).isEqualTo("m");
      assertThat(context.getBean(MerchantClassifier.class))
          .isInstanceOf(SpringAiMerchantClassifier.class);
    }
  }

  @Test
  void boot_e2eProfileWithoutModelSetting_usesKeywordTable() {
    try (var context = boot("--spring.profiles.active=" + LlmConfiguration.E2E_PROFILE,
        "--APP_LLM_MODEL=")) {
      assertThat(context.getBean(MerchantClassifier.class))
          .isInstanceOf(KeywordMerchantClassifier.class);
      assertThat(context.getBeanNamesForType(LlmProperties.class)).isEmpty();
      assertThat(context.getEnvironment().getProperty("spring.ai.model.chat")).isEqualTo("none");
    }
  }

  @Test
  void requireApiKey_providerOtherThanARealOne_failsAsUnreachable() {
    assertThatThrownBy(() -> LlmConfiguration.requireApiKey("fake", "", ""))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("fake");
  }

  @Test
  void requireApiKey_anthropicWithoutKey_failsNamingTheVariable() {
    assertThatThrownBy(() -> LlmConfiguration.requireApiKey("anthropic", "", "o"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("ANTHROPIC_API_KEY");
  }

  @Test
  void requireApiKey_openaiWithoutKey_failsNamingTheVariable() {
    assertThatThrownBy(() -> LlmConfiguration.requireApiKey("openai", "a", ""))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("OPENAI_API_KEY");
  }

  @Test
  void requireApiKey_anthropicWithKey_passes() {
    assertThatCode(() -> LlmConfiguration.requireApiKey("anthropic", "k", ""))
        .doesNotThrowAnyException();
  }
}
