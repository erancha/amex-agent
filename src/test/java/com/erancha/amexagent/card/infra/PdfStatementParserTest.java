package com.erancha.amexagent.card.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erancha.amexagent.card.api.Installment;
import com.erancha.amexagent.card.api.Statement;
import com.erancha.amexagent.card.api.Transaction;
import com.erancha.amexagent.card.infra.PdfTextLines.Chunk;
import com.erancha.amexagent.card.infra.PdfTextLines.Line;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Verifies the parser reads every row kind of the anonymized statement and refuses a statement
 * whose rows, totals or columns do not line up, by altering the fixture's text lines.
 */
public class PdfStatementParserTest {
  public static final Path FIXTURE = Path.of("src/test/resources/statements/2026-10.pdf");
  private static final List<Line> LINES = PdfTextLines.read(FIXTURE);
  private final PdfStatementParser parser = new PdfStatementParser(PdfStatementLayout.AMEX_ISRAEL);

  @Test
  void parse_fixture_readsTheChargeDateAndEveryRow() {
    Statement s = parser.parse(FIXTURE);
    assertThat(s.billingMonth()).isEqualTo(YearMonth.of(2026, 10));
    assertThat(s.currency()).isEqualTo("ILS");
    assertThat(s.chargeDate()).isEqualTo(LocalDate.of(2026, 10, 2));
    assertThat(s.transactions()).hasSize(24);
    assertThat(s.transactions().stream().map(Transaction::amount)
        .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("10690.27");
    assertThat(s.transactions()).extracting(Transaction::id)
        .startsWith("20261002-01", "20261002-02").endsWith("20261002-24").doesNotHaveDuplicates();
  }

  @Test
  void parse_fixture_readsAForeignPurchaseInShekels() {
    Transaction t = parser.parse(FIXTURE).transactions().get(0);
    assertThat(t.rawMerchant()).isEqualTo("NETFLIX.COM 519479");
    assertThat(t.sector()).isNull();
    assertThat(t.transactionDate()).isEqualTo(LocalDate.of(2026, 8, 9));
    assertThat(t.chargeDate()).isEqualTo(LocalDate.of(2026, 10, 2));
    assertThat(t.amount()).isEqualByComparingTo("69.90");
    assertThat(t.abroad()).isTrue();
    assertThat(t.originalAmount()).isNull();
    assertThat(t.originalCurrency()).isNull();
    assertThat(t.standingOrder()).isFalse();
    assertThat(t.installment()).isNull();
  }

  @Test
  void parse_fixture_readsAnInstallment() {
    Transaction t = parser.parse(FIXTURE).transactions().get(2);
    assertThat(t.rawMerchant()).isEqualTo("דר יוסי משה מזרחי");
    assertThat(t.sector()).isEqualTo("שרות רפואי");
    assertThat(t.transactionDate()).isEqualTo(LocalDate.of(2026, 1, 12));
    assertThat(t.amount()).isEqualByComparingTo("1166.00");
    assertThat(t.installment()).isEqualTo(new Installment(9, 12));
    assertThat(t.abroad()).isFalse();
    assertThat(t.standingOrder()).isFalse();
  }

  @Test
  void parse_fixture_readsAStandingOrder() {
    Transaction t = parser.parse(FIXTURE).transactions().get(8);
    assertThat(t.rawMerchant()).isEqualTo("סלקום ישראל בע\"מ");
    assertThat(t.amount()).isEqualByComparingTo("46.21");
    assertThat(t.standingOrder()).isTrue();
    assertThat(t.installment()).isNull();
  }

  @Test
  void parse_fixture_continuesTheDomesticSectionOnTheNextPage() {
    Transaction t = parser.parse(FIXTURE).transactions().get(23);
    assertThat(t.rawMerchant()).isEqualTo("וים קלאבס בע\"מ - הו\"");
    assertThat(t.transactionDate()).isEqualTo(LocalDate.of(2026, 9, 28));
    assertThat(t.amount()).isEqualByComparingTo("307.78");
  }

  @Test
  void parse_rowMissing_failsOnTheSectionTotal() {
    var lines = LINES.stream().filter(l -> !l.text().contains("1,298.61")).toList();
    assertThatThrownBy(() -> parser.parse(lines, "short.pdf"))
        .isInstanceOf(StatementFormatException.class)
        .hasMessageContaining("short.pdf")
        .hasMessageContaining("10594.47")
        .hasMessageContaining("9295.86");
  }

  @Test
  void parse_impossibleDate_failsNamingTheLine() {
    var lines = withChunk("27/09/26", c -> new Chunk("31/02/26", c.left(), c.right()));
    assertThatThrownBy(() -> parser.parse(lines, "bad-date.pdf"))
        .isInstanceOf(StatementFormatException.class)
        .hasMessageContaining("bad-date.pdf: page 1")
        .hasMessageContaining("31/02/26")
        .hasCauseInstanceOf(DateTimeParseException.class);
  }

  @Test
  void parse_chargeWithoutDate_failsNamingTheLine() {
    var lines = withChunk("27/09/26", c -> new Chunk("", c.left(), c.right()));
    assertThatThrownBy(() -> parser.parse(lines, "no-date.pdf"))
        .isInstanceOf(StatementFormatException.class)
        .hasMessageContaining("235.00")
        .hasMessageContaining("no purchase date");
  }

  @Test
  void parse_cellSpanningTwoColumns_fails() {
    var lines = withChunk("1,166.00", c -> new Chunk("1,166.00 14,000.00", c.left(), 299f));
    assertThatThrownBy(() -> parser.parse(lines, "merged.pdf"))
        .isInstanceOf(StatementFormatException.class)
        .hasMessageContaining("spans the columns CHARGE and AMOUNT");
  }

  @Test
  void parse_statementEndsBeforeTheTotalLine_fails() {
    var lines = LINES.stream().takeWhile(l -> !l.text().contains("10,594.47")).toList();
    assertThatThrownBy(() -> parser.parse(lines, "open.pdf"))
        .isInstanceOf(StatementFormatException.class)
        .hasMessageContaining("has no total line");
  }

  @Test
  void parse_noChargeDateLabel_fails() {
    var lines = LINES.stream().filter(l -> !l.text().contains("פרוט פעולותיך")).toList();
    assertThatThrownBy(() -> parser.parse(lines, "no-date-label.pdf"))
        .isInstanceOf(StatementFormatException.class)
        .hasMessageContaining(PdfStatementLayout.AMEX_ISRAEL.chargeDateLabel());
  }

  @Test
  void parse_noTransactionSection_fails() {
    var lines = LINES.stream().filter(l -> l.page() == 1 && l.text().contains("פרוט פעולותיך"))
        .toList();
    assertThatThrownBy(() -> parser.parse(lines, "letter.pdf"))
        .isInstanceOf(StatementFormatException.class)
        .hasMessageContaining("no transaction section");
  }

  /** The fixture's lines with the one chunk reading exactly target replaced. */
  private static List<Line> withChunk(String target, UnaryOperator<Chunk> change) {
    List<Line> out = new ArrayList<>();
    for (Line line : LINES) {
      out.add(new Line(line.page(), line.chunks().stream()
          .map(c -> c.text().equals(target) ? change.apply(c) : c).toList()));
    }
    return out;
  }
}
