package com.erancha.amexagent.card.infra;

import jakarta.validation.constraints.NotBlank;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;

/**
 * The app.llm configuration: the language model provider and its model, both mandatory. Unbound
 * under the e2e profile, which categorizes with the keyword table and needs no model.
 */
@ConfigurationProperties("app.llm")
@Validated
@Profile("!" + LlmConfiguration.E2E_PROFILE)
public record LlmProperties(@NotBlank String provider, String model) {
  private static final Set<String> PROVIDERS = Set.of("anthropic", "openai");

  public LlmProperties {
    if (!PROVIDERS.contains(provider)) {
      throw new IllegalArgumentException(
          "APP_LLM_PROVIDER must be anthropic or openai, got '" + provider + "'");
    }
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException(
          "APP_LLM_MODEL must be set for APP_LLM_PROVIDER=" + provider);
    }
  }
}
