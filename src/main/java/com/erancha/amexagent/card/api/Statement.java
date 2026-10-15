package com.erancha.amexagent.card.api;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * One statement file: the billing month it covers, the card currency, the charge date and its rows,
 * uncategorized as parsed and categorized once TransactionsService has run them through the
 * categorizer.
 */
public record Statement(YearMonth billingMonth, String currency, LocalDate chargeDate,
    List<Transaction> transactions) {}
