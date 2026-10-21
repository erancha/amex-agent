package com.erancha.amexagent.card.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.erancha.amexagent.card.api.Category;
import com.erancha.amexagent.card.api.MerchantCategory;
import com.erancha.amexagent.card.api.Statement;
import com.erancha.amexagent.card.api.Transaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Verifies the service returns the categorized statement, or empty when no file covers it. */
class TransactionsServiceTest {
  private final Transaction row = Transaction.parsed("1", LocalDate.of(2026, 9, 1),
      LocalDate.of(2026, 10, 2), "פז יהוד", new BigDecimal("250.00"), null, null, false, false,
      null);
  private final Statement statement = new Statement(YearMonth.of(2026, 10), "ILS",
      LocalDate.of(2026, 10, 2), List.of(row));
  private final MerchantCategorizer categorizer = new MerchantCategorizer(new RecordingClassifier(
      Map.of("פז יהוד", new MerchantCategory("פז יהוד", "Paz", "transport", 0.9))), 0.85, 0.6, 40);

  @Test
  void monthlyTransactions_covered_returnsCategorizedRows() {
    TransactionsService service = new TransactionsService(
        (u, m) -> u.equals("1000") && m.equals(YearMonth.of(2026, 10))
            ? Optional.of(statement) : Optional.empty(),
        categorizer);
    Optional<Statement> out = service.monthlyTransactions("1000", YearMonth.of(2026, 10));
    assertThat(service.monthlyTransactions("2000", YearMonth.of(2026, 10)))
        .as("another person's month").isEmpty();
    assertThat(out).isPresent();
    assertThat(out.get().transactions().get(0).category()).isEqualTo(Category.TRANSPORT);
    assertThat(out.get().transactions().get(0).merchant()).isEqualTo("Paz");
  }

  @Test
  void monthlyTransactions_uncovered_isEmpty() {
    TransactionsService service =
        new TransactionsService((u, m) -> Optional.empty(), categorizer);
    assertThat(service.monthlyTransactions("1000", YearMonth.of(2026, 9))).isEmpty();
  }
}
