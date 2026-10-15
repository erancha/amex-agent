package com.erancha.amexagent.card.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/** Verifies the export header's Hebrew month label maps to a YearMonth and nothing else does. */
class HebrewMonthsTest {
  @Test
  void parse_hebrewMonthAndYear_returnsYearMonth() {
    assertThat(HebrewMonths.parse("אוקטובר 2026")).isEqualTo(YearMonth.of(2026, 10));
    assertThat(HebrewMonths.parse(" ינואר 2027 ")).isEqualTo(YearMonth.of(2027, 1));
  }

  @Test
  void parse_unknownLabel_throwsNamingTheLabel() {
    assertThatThrownBy(() -> HebrewMonths.parse("October 2026"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("October 2026");
  }
}
