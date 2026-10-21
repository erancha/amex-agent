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
 * language model and refuses to start when that provider lacks its API key, so a misconfiguration
 * fails at boot rather than on the first task. The e2e profile, for end-to-end tests only,
 * categorizes with the keyword table and needs no provider, model or key.
 */
@Configuration
public class LlmConfiguration {
  public static final String E2E_PROFILE = "e2e";

  @Bean
  @Profile("!" + E2E_PROFILE)
  public MerchantClassifier merchantClassifier(LlmProperties llm,
      ObjectProvider<ChatClient.Builder> chatClientBuilder, Environment env) {
    requireApiKey(llm.provider(), env.getRequiredProperty("spring.ai.anthropic.api-key"),
        env.getRequiredProperty("spring.ai.openai.api-key"));
    return new SpringAiMerchantClassifier(chatClientBuilder.getObject().build());
  }

  @Bean
  @Profile(E2E_PROFILE)
  public MerchantClassifier keywordMerchantClassifier() {
    return new KeywordMerchantClassifier();
  }

  static void requireApiKey(String provider, String anthropicKey, String openaiKey) {
    switch (provider) {
      case "anthropic" -> requireKey(anthropicKey, "ANTHROPIC_API_KEY", provider);
      case "openai" -> requireKey(openaiKey, "OPENAI_API_KEY", provider);
      default -> throw new IllegalStateException(
          "Unreachable: LlmProperties admits only anthropic and openai, got provider " + provider);
    }
  }

  private static void requireKey(String key, String variable, String provider) {
    if (key.isBlank()) {
      throw new IllegalStateException(
          variable + " must be set for the " + provider + " provider of " + LlmProperties.VARIABLE);
    }
  }
}
