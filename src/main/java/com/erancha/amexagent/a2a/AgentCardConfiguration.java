package com.erancha.amexagent.a2a;

import com.erancha.amexagent.card.api.Category;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.HTTPAuthSecurityScheme;
import org.a2aproject.sdk.spec.SecurityRequirement;
import org.a2aproject.sdk.spec.SecurityScheme;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Builds the public Agent Card from app.agent.* and app.a2a.auth.*. */
@Configuration
public class AgentCardConfiguration {
  public static final String SKILL_ID = "monthly-transactions";
  public static final String SECURITY_SCHEME = "bearer";
  // The skill accepts and returns what the agent as a whole does, so both levels share these.
  private static final List<String> INPUT_MODES = List.of("application/json", "text/plain");
  private static final List<String> OUTPUT_MODES = List.of("application/json");

  @Bean
  public AgentCard agentCard(A2AProperties props) {
    AgentCard.Builder card = AgentCard.builder()
        .name("American Express Israel card agent")
        .description("Serves the transactions charged to an American Express Israel card in a "
            + "given billing month, categorized into a fixed taxonomy.")
        .version(props.agent().version())
        .supportedInterfaces(List.of(
            new AgentInterface(TransportProtocol.JSONRPC.asString(), props.agent().url())))
        .capabilities(AgentCapabilities.builder().streaming(true).pushNotifications(false).build())
        .defaultInputModes(INPUT_MODES)
        .defaultOutputModes(OUTPUT_MODES)
        .skills(List.of(AgentSkill.builder()
            .id(SKILL_ID)
            .name("Monthly card transactions")
            .description("Returns the card transactions charged in a given billing month, each "
                + "categorized into one of: " + String.join(", ", Category.wireValues()) + ". "
                + "Send a data part {\"month\": \"YYYY-MM\"}. When no statement covers the month "
                + "the task asks for input; reply {\"action\": \"continue\"} after downloading it.")
            .tags(List.of("finance", "credit-card", "transactions"))
            .examples(List.of("{\"month\": \"2026-10\"}"))
            .inputModes(INPUT_MODES)
            .outputModes(OUTPUT_MODES)
            .build()));
    if (props.a2a().auth().required()) {
      card.securitySchemes(Map.<String, SecurityScheme>of(SECURITY_SCHEME,
              HTTPAuthSecurityScheme.builder().scheme("bearer").description(
                  "Shared token issued by the agent operator").build()))
          .securityRequirements(List.of(
              new SecurityRequirement(Map.of(SECURITY_SCHEME, List.of()))));
    }
    return card.build();
  }
}
