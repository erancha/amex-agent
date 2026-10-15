package com.erancha.amexagent.card.api;

import java.util.Locale;

/**
 * How sure the agent is of a row's category, published instead of the model's score so the score
 * and its scale stay inside the agent. Wire values are the lower-case names.
 */
public enum Confidence {
  HIGH, MEDIUM, LOW;

  public String wireValue() {
    return name().toLowerCase(Locale.ROOT);
  }
}
