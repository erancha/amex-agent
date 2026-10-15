package com.erancha.amexagent.card.api;

/** A statement offered for loading was refused; the message says why, naming the file. */
public class StatementRejectedException extends RuntimeException {
  public StatementRejectedException(String message) {
    super(message);
  }

  public StatementRejectedException(String message, Throwable cause) {
    super(message, cause);
  }
}
