package com.erancha.amexagent.card.infra;

import com.erancha.amexagent.card.api.MerchantClassifier;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Chooses the classifier. Every run outside the e2e profile categorizes with the configured
 * language model and refuses to start without its API key, so a misconfiguration fails at boot
 * rather than on the first task. The e2e profile, for end-to-end tests only, categorizes with
 * the keyword table and needs no provider, model or key.
 */
@Configuration
public class LlmConfiguration {
  public static final String E2E_PROFILE = "e2e";
  static final String API_KEY_VARIABLE = "APP_LLM_API_KEY";
  /** application.yml hands this one value to every provider's api-key. */
  static final String API_KEY_PROPERTY = "app.llm.api-key";

  @Bean
  @Profile("!" + E2E_PROFILE)
  public MerchantClassifier merchantClassifier(LlmProperties llm,
      ObjectProvider<ChatClient.Builder> chatClientBuilder, Environment env) {
    requireApiKey(env.getRequiredProperty(API_KEY_PROPERTY), llm.provider());
    return new SpringAiMerchantClassifier(chatClientBuilder.getObject().build());
  }

  @Bean
  @Profile(E2E_PROFILE)
  public MerchantClassifier keywordMerchantClassifier() {
    return new KeywordMerchantClassifier();
  }

  static void requireApiKey(String key, String provider) {
    if (key.isBlank()) {
      throw new IllegalStateException(API_KEY_VARIABLE + " must be set to the " + provider
          + " API key, the provider " + LlmProperties.VARIABLE + " names");
    }
  }
}
