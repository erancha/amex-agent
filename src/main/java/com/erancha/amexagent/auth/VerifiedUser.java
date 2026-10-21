package com.erancha.amexagent.auth;

import org.jspecify.annotations.Nullable;

/**
 * The person a verified Google ID token names. The subject is Google's stable account id and the
 * only value used as a key; email and name are for display and may be absent from the token.
 */
public record VerifiedUser(String sub, @Nullable String email, @Nullable String name) {
  /** The one user of an agent that runs with authentication off (the TCK profile). */
  public static final VerifiedUser ANONYMOUS = new VerifiedUser("anonymous", null, null);

  public VerifiedUser {
    if (sub == null || sub.isBlank()) {
      throw new IllegalArgumentException("A verified user needs a subject");
    }
  }
}
