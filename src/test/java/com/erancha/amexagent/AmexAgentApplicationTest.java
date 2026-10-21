package com.erancha.amexagent;

import static com.erancha.amexagent.card.infra.LlmConfiguration.E2E_PROFILE;
import static org.assertj.core.api.Assertions.assertThat;

import com.erancha.amexagent.auth.TestTokens;
import com.erancha.amexagent.card.api.MerchantClassifier;
import com.erancha.amexagent.card.application.TransactionsAgentExecutor;
import com.erancha.amexagent.card.infra.KeywordMerchantClassifier;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Verifies the context boots under the e2e profile and wires the real card pipeline. */
@SpringBootTest(properties = {"APP_STATEMENTS_DIR=src/test/resources/statements"})
@ActiveProfiles(E2E_PROFILE)
class AmexAgentApplicationTest {
  @DynamicPropertySource
  static void auth(DynamicPropertyRegistry registry) {
    TestTokens.register(registry);
  }

  @Autowired
  ApplicationContext context;

  @Test
  void contextLoads() {}

  @Test
  void wiresTheCardPipeline() {
    assertThat(context.getBean(AgentExecutor.class)).isInstanceOf(TransactionsAgentExecutor.class);
    assertThat(context.getBean(MerchantClassifier.class))
        .isInstanceOf(KeywordMerchantClassifier.class);
    assertThat(context.containsBean("callerSecretFilter")).isTrue();
  }
}
