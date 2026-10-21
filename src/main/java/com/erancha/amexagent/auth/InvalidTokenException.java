package com.erancha.amexagent.auth;

/** A Google ID token that cannot be accepted; the message says why, in words for a person. */
public class InvalidTokenException extends RuntimeException {
  public InvalidTokenException(String reason) {
    super(reason);
  }

  public InvalidTokenException(String reason, Throwable cause) {
    super(reason, cause);
  }
}
