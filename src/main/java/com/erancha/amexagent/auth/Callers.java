package com.erancha.amexagent.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

/**
 * The registered A2A callers, looked up by the secret a request presents. Every entry is compared
 * in constant time and the scan never stops early, so response timing leaks neither which entry
 * matched nor how much of a secret was right.
 */
public class Callers {
  private final List<Caller> callers;

  public Callers(List<Caller> callers) {
    this.callers = List.copyOf(callers);
  }

  public Optional<Caller> bySecret(String presented) {
    byte[] candidate = presented.getBytes(StandardCharsets.UTF_8);
    Caller matched = null;
    for (Caller caller : callers) {
      if (MessageDigest.isEqual(caller.sharedSecret().getBytes(StandardCharsets.UTF_8),
          candidate)) {
        matched = caller;
      }
    }
    return Optional.ofNullable(matched);
  }
}
