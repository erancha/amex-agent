package com.erancha.amexagent.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.erancha.amexagent.card.api.Category;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.HTTPAuthSecurityScheme;
import org.junit.jupiter.api.Test;

/** Verifies the card built from the properties: interface, streaming, the skill and its auth. */
class AgentCardConfigurationTest {
  private static A2AProperties props(boolean authRequired) {
    return new A2AProperties(
        new A2AProperties.Agent("http://cards.example:8020", "1.2.3"),
        new A2AProperties.Statements(java.nio.file.Path.of("/tmp/statements")),
        new A2AProperties.A2a(new A2AProperties.Auth(authRequired, "secret")),
        new A2AProperties.Categorizer(0.85, 0.6, 40));
  }

  @Test
  void agentCard_declaresJsonRpcInterfaceStreamingAndSkill() {
    AgentCard card = new AgentCardConfiguration().agentCard(props(true));
    assertThat(card.supportedInterfaces()).hasSize(1);
    assertThat(card.supportedInterfaces().get(0).url()).isEqualTo("http://cards.example:8020");
    assertThat(card.supportedInterfaces().get(0).protocolBinding()).isEqualTo("JSONRPC");
    assertThat(card.supportedInterfaces().get(0).protocolVersion()).isEqualTo("1.0");
    assertThat(card.capabilities().streaming()).isTrue();
    assertThat(card.capabilities().pushNotifications()).isFalse();
    assertThat(card.version()).isEqualTo("1.2.3");
    assertThat(card.skills()).hasSize(1);
    assertThat(card.skills().get(0).id()).isEqualTo(AgentCardConfiguration.SKILL_ID);
    assertThat(card.skills().get(0).description())
        .contains(String.join(", ", Category.wireValues()));
    assertThat(card.skills().get(0).inputModes()).containsExactly("application/json", "text/plain");
    assertThat(card.skills().get(0).outputModes()).containsExactly("application/json");
  }

  @Test
  void agentCard_authRequired_declaresBearerScheme() {
    AgentCard card = new AgentCardConfiguration().agentCard(props(true));
    assertThat(card.securitySchemes()).containsOnlyKeys(AgentCardConfiguration.SECURITY_SCHEME);
    assertThat(card.securitySchemes().get(AgentCardConfiguration.SECURITY_SCHEME))
        .isInstanceOf(HTTPAuthSecurityScheme.class);
    assertThat(card.securityRequirements()).hasSize(1);
    assertThat(card.securityRequirements().get(0).schemes())
        .containsKey(AgentCardConfiguration.SECURITY_SCHEME);
  }

  @Test
  void agentCard_authNotRequired_declaresNoSecurity() {
    AgentCard card = new AgentCardConfiguration().agentCard(props(false));
    assertThat(card.securitySchemes()).isNullOrEmpty();
    assertThat(card.securityRequirements()).isNullOrEmpty();
  }
}
