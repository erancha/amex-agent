package com.erancha.amexagent.card.infra;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;

/**
 * Splits APP_LLM_MODEL into the two properties application.yml consumes: app.llm.provider, which
 * selects the Spring AI ChatModel, and app.llm.model-id, the model named to that provider. Runs
 * before auto-configuration, where the selection is read, so a bean cannot do this job. Adds
 * nothing when the variable is blank, which LlmProperties then reports at bind time, or under the
 * e2e profile, which reads no model setting at all.
 */
public class LlmModelEnvironmentPostProcessor implements EnvironmentPostProcessor {
  static final String PROVIDER_PROPERTY = "app.llm.provider";
  static final String MODEL_ID_PROPERTY = "app.llm.model-id";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment,
      SpringApplication application) {
    String value = environment.getProperty(LlmProperties.VARIABLE);
    if (value == null || value.isBlank()
        || environment.acceptsProfiles(Profiles.of(LlmConfiguration.E2E_PROFILE))) {
      return;
    }
    LlmProperties llm = new LlmProperties(value);
    environment.getPropertySources().addFirst(new MapPropertySource("llmModel",
        Map.of(PROVIDER_PROPERTY, llm.provider(), MODEL_ID_PROPERTY, llm.modelId())));
  }
}
