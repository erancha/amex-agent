package com.erancha.amexagent.card.infra;

/**
 * A statement file cannot be read as its layout describes; the message names the file and, when
 * known, the cell, line or label at fault.
 */
public class StatementFormatException extends RuntimeException {
  public StatementFormatException(String message) {
    super(message);
  }

  public StatementFormatException(String message, Throwable cause) {
    super(message, cause);
  }
}
