package com.erancha.amexagent;

import static com.erancha.amexagent.card.infra.LlmConfiguration.E2E_PROFILE;
import static org.assertj.core.api.Assertions.assertThat;

import com.erancha.amexagent.tck.TckAgentExecutor;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/** Boots the tck profile: scenario executor wired in, no bearer filter, unauthenticated calls. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "APP_STATEMENTS_DIR=src/test/resources/statements"})
@ActiveProfiles({"tck", E2E_PROFILE})
class TckProfileTest {
  @LocalServerPort int port;
  @Autowired ApplicationContext context;

  @Test
  void tckProfile_usesScenarioExecutorWithoutAuth() {
    assertThat(context.getBean(AgentExecutor.class)).isInstanceOf(TckAgentExecutor.class);
    assertThat(context.containsBean("bearerTokenFilter")).isFalse();

    String body = RestClient.create("http://localhost:" + port).post().uri("/")
        .contentType(MediaType.APPLICATION_JSON)
        .header("A2A-Version", "1.0")
        .body("""
            {"jsonrpc":"2.0","id":1,"method":"SendStreamingMessage","params":{"message":{
              "role":"ROLE_USER","messageId":"tck-complete-task-1","parts":[{"text":"hi"}]}}}""")
        .retrieve().body(String.class);

    assertThat(body).contains("TASK_STATE_COMPLETED").contains("Hello from TCK");
  }
}
