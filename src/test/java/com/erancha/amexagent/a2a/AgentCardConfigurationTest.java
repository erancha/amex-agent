package com.erancha.amexagent.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.erancha.amexagent.auth.Caller;
import com.erancha.amexagent.card.api.Category;
import java.util.List;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.APIKeySecurityScheme;
import org.a2aproject.sdk.spec.OpenIdConnectSecurityScheme;
import org.junit.jupiter.api.Test;

/** Verifies the card built from the properties: interface, streaming, the skill and its auth. */
class AgentCardConfigurationTest {
  private static A2AProperties props(boolean authRequired) {
    return new A2AProperties(
        new A2AProperties.Agent("http://cards.example:8020", "1.2.3"),
        new A2AProperties.Statements(java.nio.file.Path.of("/tmp/statements")),
        new A2AProperties.A2a(new A2AProperties.Auth(authRequired),
            List.of(new Caller("test-caller", "secret", "client-1"))),
        new A2AProperties.Categorizer(0.85, 0.6, 40),
        new A2AProperties.Tasks("", java.time.Duration.ofDays(30)));
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
  void agentCard_authRequired_requiresTheUsersTokenAndTheCallersSecretTogether() {
    AgentCard card = new AgentCardConfiguration().agentCard(props(true));
    assertThat(card.securitySchemes()).containsOnlyKeys("google", "caller");
    assertThat(card.securitySchemes().get("google"))
        .isInstanceOfSatisfying(OpenIdConnectSecurityScheme.class, s -> assertThat(
            s.openIdConnectUrl())
            .isEqualTo("https://accounts.google.com/.well-known/openid-configuration"));
    assertThat(card.securitySchemes().get("caller"))
        .isInstanceOfSatisfying(APIKeySecurityScheme.class, s -> {
          assertThat(s.location()).isEqualTo(APIKeySecurityScheme.Location.HEADER);
          assertThat(s.name()).isEqualTo("X-Caller-Secret");
        });
    assertThat(card.securityRequirements()).hasSize(1);
    assertThat(card.securityRequirements().get(0).schemes()).containsOnlyKeys("google", "caller");
  }

  @Test
  void agentCard_authNotRequired_declaresNoSecurity() {
    AgentCard card = new AgentCardConfiguration().agentCard(props(false));
    assertThat(card.securitySchemes()).isNullOrEmpty();
    assertThat(card.securityRequirements()).isNullOrEmpty();
  }
}
