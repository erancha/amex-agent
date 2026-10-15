package com.erancha.amexagent.a2a;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The app.* configuration tree apart from app.llm, which LlmProperties binds. Required keys fail
 * startup when absent.
 */
@ConfigurationProperties("app")
@Validated
public record A2AProperties(
    @NotNull Agent agent,
    @NotNull Statements statements,
    @NotNull A2a a2a,
    @NotNull Categorizer categorizer) {

  public record Agent(@NotBlank String url, @NotBlank String version) {}

  public record Statements(@NotNull Path dir) {}

  public record A2a(@NotNull Auth auth) {}

  /** The token is mandatory whenever authentication is required. */
  public record Auth(boolean required, String token) {
    public Auth {
      if (required && (token == null || token.isBlank())) {
        throw new IllegalArgumentException(
            "APP_A2A_TOKEN must be set when APP_A2A_AUTH_REQUIRED is true");
      }
    }
  }

  public record Categorizer(double highConfidence, double mediumConfidence, int batchSize) {}
}
