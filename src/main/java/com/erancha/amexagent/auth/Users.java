package com.erancha.amexagent.auth;

import org.jspecify.annotations.Nullable;

/**
 * Resolves the person behind a request from its Authorization header. A page request's token must
 * be issued to this agent's own client; a token a registered caller forwards must be issued to
 * that caller's client. With authentication off there is one person, ANONYMOUS, and no token is
 * read.
 */
public class Users {
  private static final String BEARER = "Bearer ";

  private final GoogleTokenVerifier verifier;
  private final @Nullable String pageClientId;
  private final boolean required;

  public Users(GoogleTokenVerifier verifier, @Nullable String pageClientId, boolean required) {
    if (required && (pageClientId == null || pageClientId.isBlank())) {
      throw new IllegalArgumentException(
          "APP_AUTH_CLIENT_ID must be set when APP_A2A_AUTH_REQUIRED is true");
    }
    this.verifier = verifier;
    this.pageClientId = pageClientId;
    this.required = required;
  }

  public boolean required() {
    return required;
  }

  /** The agent's own OAuth client id, which the statements page signs in with; null when off. */
  public @Nullable String pageClientId() {
    return required ? pageClientId : null;
  }

  public VerifiedUser onPage(@Nullable String authorization) {
    return required ? verifier.verify(token(authorization), pageClientId) : VerifiedUser.ANONYMOUS;
  }

  public VerifiedUser onBehalfOf(Caller caller, @Nullable String authorization) {
    return required
        ? verifier.verify(token(authorization), caller.clientId()) : VerifiedUser.ANONYMOUS;
  }

  private static String token(@Nullable String authorization) {
    if (authorization == null || !authorization.startsWith(BEARER)) {
      throw new InvalidTokenException("no Google ID token was sent");
    }
    return authorization.substring(BEARER.length());
  }
}
