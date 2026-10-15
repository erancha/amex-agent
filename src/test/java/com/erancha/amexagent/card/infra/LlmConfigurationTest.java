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
 * provider with its API key boots, and the e2e profile boots on the keyword table with no provider
 * settings at all.
 */
class LlmConfigurationTest {
  // Command-line arguments outrank the developer's exported environment, so a real key in the
  // calling shell cannot mask a missing one here.
  private static ConfigurableApplicationContext boot(String... args) {
    String[] base = {"--APP_STATEMENTS_DIR=src/test/resources/statements",
        "--APP_A2A_TOKEN=test-token", "--ANTHROPIC_API_KEY=", "--OPENAI_API_KEY="};
    return new SpringApplicationBuilder(AmexAgentApplication.class)
        .web(WebApplicationType.NONE)
        .run(Stream.concat(Stream.of(base), Stream.of(args)).toArray(String[]::new));
  }

  @Test
  void boot_fakeProvider_isRejected() {
    assertThatThrownBy(() -> boot("--APP_LLM_PROVIDER=fake", "--APP_LLM_MODEL=m"))
        .rootCause().hasMessageContaining("APP_LLM_PROVIDER must be anthropic or openai");
  }

  @Test
  void boot_realProviderWithoutKey_failsNamingTheVariable() {
    assertThatThrownBy(() -> boot("--APP_LLM_PROVIDER=anthropic", "--APP_LLM_MODEL=m"))
        .rootCause().hasMessageContaining("ANTHROPIC_API_KEY");
  }

  @Test
  void boot_e2eProfileWithoutProviderSettings_usesKeywordTable() {
    try (var context = boot("--spring.profiles.active=" + LlmConfiguration.E2E_PROFILE,
        "--APP_LLM_PROVIDER=", "--APP_LLM_MODEL=")) {
      assertThat(context.getBean(MerchantClassifier.class))
          .isInstanceOf(KeywordMerchantClassifier.class);
      assertThat(context.getBeanNamesForType(LlmProperties.class)).isEmpty();
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
