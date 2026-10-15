package com.erancha.amexagent.card.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Verifies a parsed transaction starts uncategorized and categorization is a pure copy-with. */
class TransactionTest {
  @Test
  void parsed_thenCategorized_keepsExportFieldsAndSetsModelFields() {
    Transaction parsed = Transaction.parsed("201939841", LocalDate.of(2026, 9, 5),
        LocalDate.of(2026, 10, 2), "סופר פארם בעמ 123", new BigDecimal("86.90"), null, null,
        false, false, null);
    assertThat(parsed.merchant()).isEqualTo("סופר פארם בעמ 123");
    assertThat(parsed.category()).isEqualTo(Category.UNCATEGORIZED);
    assertThat(parsed.confidence()).isEqualTo(Confidence.LOW);

    Transaction categorized = parsed.withCategory("Super-Pharm", Category.HEALTH, Confidence.HIGH);
    assertThat(categorized.id()).isEqualTo("201939841");
    assertThat(categorized.merchant()).isEqualTo("Super-Pharm");
    assertThat(categorized.rawMerchant()).isEqualTo("סופר פארם בעמ 123");
    assertThat(categorized.category()).isEqualTo(Category.HEALTH);
    assertThat(categorized.confidence()).isEqualTo(Confidence.HIGH);
  }
}
