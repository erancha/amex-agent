package com.erancha.amexagent.card.infra;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The card's own currency and the purchase-currency symbols both statement formats print. */
final class StatementCurrency {
  static final String CODE = "ILS";
  static final String SYMBOL = "₪";
  private static final Map<String, String> BY_SYMBOL =
      Map.of(SYMBOL, CODE, "$", "USD", "€", "EUR", "£", "GBP");

  private StatementCurrency() {}

  /** The ISO code for a printed symbol, or null when the symbol is not one the card site uses. */
  static @Nullable String codeFor(String symbol) {
    return BY_SYMBOL.get(symbol);
  }
}
