package com.erancha.amexagent.card.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.erancha.amexagent.card.api.Category;
import com.erancha.amexagent.card.api.Confidence;
import com.erancha.amexagent.card.api.Installment;
import com.erancha.amexagent.card.api.Statement;
import com.erancha.amexagent.card.api.Transaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies each row carries every contract field, nulls included, and rows go 25 per batch. */
class ArtifactMapperTest {
  private static Transaction tx(int i) {
    return Transaction.parsed("20193" + String.format("%04d", i), LocalDate.of(2026, 9, 28),
        LocalDate.of(2026, 10, 2), "סופר פארם בעמ 123", new BigDecimal("86.90"),
        new BigDecimal("29.99"), "USD", false, true, new Installment(3, 10))
        .withCategory("Super-Pharm", Category.HEALTH, Confidence.MEDIUM);
  }

  @Test
  void transaction_hasEverySpecField() {
    Map<String, Object> m = ArtifactMapper.transaction(tx(1));
    assertThat(m).containsOnlyKeys("id", "transactionDate", "chargeDate", "merchant", "rawMerchant",
        "amount", "originalAmount", "originalCurrency", "standingOrder", "abroad", "installment",
        "category", "confidence");
    assertThat(m).containsEntry("transactionDate", "2026-09-28").containsEntry("amount", 86.90)
        .containsEntry("category", "health").containsEntry("confidence", "medium")
        .containsEntry("originalCurrency", "USD")
        .containsEntry("installment", Map.of("number", 3, "total", 10));
  }

  @Test
  void transaction_nullablesAreExplicitNulls() {
    Transaction plain = Transaction.parsed("1", LocalDate.of(2026, 9, 1),
        LocalDate.of(2026, 10, 2), "x", new BigDecimal("1.00"), null, null, false, false, null);
    Map<String, Object> m = ArtifactMapper.transaction(plain);
    assertThat(m).containsKey("originalAmount").containsKey("installment");
    assertThat(m.get("originalAmount")).isNull();
    assertThat(m.get("installment")).isNull();
  }

  @Test
  void batches_splitAtTwentyFiveAndCarryHeader() {
    List<Transaction> rows = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      rows.add(tx(i));
    }
    Statement s = new Statement(YearMonth.of(2026, 10), "ILS", LocalDate.of(2026, 10, 2), rows);
    List<Map<String, Object>> batches = ArtifactMapper.batches(s);
    assertThat(batches).hasSize(3);
    assertThat(batches.get(0)).containsEntry("month", "2026-10").containsEntry("currency", "ILS");
    assertThat((List<?>) batches.get(0).get("transactions")).hasSize(25);
    assertThat((List<?>) batches.get(2).get("transactions")).hasSize(10);
  }

  @Test
  void batches_emptyStatement_isOneEmptyBatch() {
    Statement s = new Statement(YearMonth.of(2026, 10), "ILS", LocalDate.of(2026, 10, 2),
        List.of());
    assertThat(ArtifactMapper.batches(s)).hasSize(1);
    assertThat((List<?>) ArtifactMapper.batches(s).get(0).get("transactions")).isEmpty();
  }
}
