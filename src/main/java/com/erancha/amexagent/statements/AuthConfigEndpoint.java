package com.erancha.amexagent.statements;

import com.erancha.amexagent.auth.Users;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells the statements page which Google OAuth client to sign in with. The client id is public;
 * a null value means the agent runs with authentication off and the page asks for no sign-in.
 */
@RestController
public class AuthConfigEndpoint {
  public static final String PATH = "/api/auth/config";

  public record AuthConfig(@Nullable String clientId) {}

  private final Users users;

  public AuthConfigEndpoint(Users users) {
    this.users = users;
  }

  @GetMapping(PATH)
  public AuthConfig config() {
    return new AuthConfig(users.pageClientId());
  }
}
