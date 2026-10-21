package com.erancha.amexagent.card.infra;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;

/**
 * The app.llm configuration: APP_LLM_MODEL as provider:model. The provider half selects the
 * Spring AI ChatModel, the rest is the model named to that provider. Unbound under the e2e
 * profile, which categorizes with the keyword table and needs no model.
 */
@ConfigurationProperties("app.llm")
@Profile("!" + LlmConfiguration.E2E_PROFILE)
public record LlmProperties(String model) {
  static final String VARIABLE = "APP_LLM_MODEL";
  private static final List<String> PROVIDERS = List.of("anthropic", "openai");

  public LlmProperties {
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException(
          VARIABLE + " must be set to provider:model, e.g. anthropic:claude-haiku-5-5");
    }
    int colon = model.indexOf(':');
    if (colon <= 0 || colon == model.length() - 1) {
      throw new IllegalArgumentException(VARIABLE + "='" + model + "' must be provider:model");
    }
    String provider = model.substring(0, colon);
    if (!PROVIDERS.contains(provider)) {
      throw new IllegalArgumentException(VARIABLE + " provider '" + provider
          + "' is not one of " + String.join(", ", PROVIDERS));
    }
  }

  public String provider() {
    return model.substring(0, model.indexOf(':'));
  }

  /** The part after the first colon, so a model id may itself contain colons. */
  public String modelId() {
    return model.substring(model.indexOf(':') + 1);
  }
}
