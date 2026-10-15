package com.erancha.amexagent.card.application;

import com.erancha.amexagent.card.api.Statement;
import com.erancha.amexagent.card.api.StatementSource;
import java.time.YearMonth;
import java.util.Optional;

/** The skill's one use case: the categorized statement of a billing month, if a file covers it. */
public class TransactionsService {
  private final StatementSource source;
  private final MerchantCategorizer categorizer;

  public TransactionsService(StatementSource source, MerchantCategorizer categorizer) {
    this.source = source;
    this.categorizer = categorizer;
  }

  public Optional<Statement> monthlyTransactions(YearMonth month) {
    return source.forMonth(month).map(s -> new Statement(s.billingMonth(), s.currency(),
        s.chargeDate(), categorizer.categorize(s.transactions())));
  }
}
