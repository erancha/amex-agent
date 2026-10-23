package com.erancha.amexagent.a2a;

import com.erancha.amexagent.auth.Caller;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The app.* configuration tree apart from app.llm (LlmProperties) and app.auth (AuthProperties).
 * Required keys fail startup when absent.
 */
@ConfigurationProperties("app")
@Validated
public record A2AProperties(
    @NotNull Agent agent,
    @NotNull Statements statements,
    @NotNull A2a a2a,
    @NotNull Categorizer categorizer,
    @NotNull Tasks tasks) {

  public record Agent(@NotBlank String url, @NotBlank String version) {}

  public record Statements(@NotNull Path dir) {}

  /**
   * With authentication required, at least one caller must be registered, each with every field,
   * and no two callers may share a secret, since the secret alone selects the caller.
   */
  public record A2a(@NotNull Auth auth, @DefaultValue List<Caller> allowedCallers) {
    public A2a {
      if (auth.required() && allowedCallers.isEmpty()) {
        throw new IllegalArgumentException("No A2A caller is registered: the file named by "
            + "APP_A2A_CALLERS_FILE (default ./allowed-callers.yaml) must list at least one "
            + "entry under app.a2a.allowed_callers while APP_A2A_AUTH_REQUIRED is true");
      }
      Set<String> secrets = new HashSet<>();
      for (Caller caller : allowedCallers) {
        if (!secrets.add(caller.sharedSecret())) {
          throw new IllegalArgumentException("Two entries of app.a2a.allowed_callers share a "
              + "shared_secret; the secret alone identifies the caller (" + caller.name() + ")");
        }
      }
    }
  }

  public record Auth(boolean required) {}

  /**
   * Where A2A tasks are kept. A blank redisUrl keeps them in this process's memory; a Redis URL
   * keeps them in that Redis, where every replica given the same URL shares them. Redis deletes a
   * task ttl after its last change.
   */
  public record Tasks(@DefaultValue("") String redisUrl, @NotNull Duration ttl) {}

  public record Categorizer(double highConfidence, double mediumConfidence, int batchSize) {}
}
