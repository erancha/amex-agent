package com.erancha.amexagent.card.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * One charged card transaction. Fields up to installment come from the statement; sector is the
 * issuer's own label for the merchant's line of business, null where the statement prints none
 * (the abroad section, the Excel export). Merchant, category and confidence come from the
 * categorizer and start as raw / UNCATEGORIZED / LOW.
 */
public record Transaction(
    String id,
    LocalDate transactionDate,
    LocalDate chargeDate,
    String merchant,
    String rawMerchant,
    @Nullable String sector,
    BigDecimal amount,
    @Nullable BigDecimal originalAmount,
    @Nullable String originalCurrency,
    boolean standingOrder,
    boolean abroad,
    @Nullable Installment installment,
    Category category,
    Confidence confidence) {

  public static Transaction parsed(String id, LocalDate transactionDate, LocalDate chargeDate,
      String rawMerchant, @Nullable String sector, BigDecimal amount,
      @Nullable BigDecimal originalAmount, @Nullable String originalCurrency,
      boolean standingOrder, boolean abroad, @Nullable Installment installment) {
    return new Transaction(id, transactionDate, chargeDate, rawMerchant, rawMerchant, sector,
        amount, originalAmount, originalCurrency, standingOrder, abroad, installment,
        Category.UNCATEGORIZED, Confidence.LOW);
  }

  public Transaction withCategory(String displayMerchant, Category category,
      Confidence confidence) {
    return new Transaction(id, transactionDate, chargeDate, displayMerchant, rawMerchant, sector,
        amount, originalAmount, originalCurrency, standingOrder, abroad, installment, category,
        confidence);
  }
}
