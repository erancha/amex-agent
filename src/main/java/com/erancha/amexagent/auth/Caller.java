package com.erancha.amexagent.auth;

/**
 * One registered A2A caller: the secret it presents on every request and the Google OAuth client
 * whose ID tokens it forwards, so a forwarded token's audience must equal that client id.
 */
public record Caller(String name, String sharedSecret, String clientId) {
  public Caller {
    requireText(name, "name");
    requireText(sharedSecret, "shared_secret");
    requireText(clientId, "client_id");
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
          "Every entry of app.a2a.allowed_callers needs a " + field);
    }
  }
}
