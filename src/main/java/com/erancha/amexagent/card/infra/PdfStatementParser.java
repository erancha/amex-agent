package com.erancha.amexagent.card.infra;

import com.erancha.amexagent.card.api.Installment;
import com.erancha.amexagent.card.api.Statement;
import com.erancha.amexagent.card.api.Transaction;
import com.erancha.amexagent.card.infra.PdfStatementLayout.Column;
import com.erancha.amexagent.card.infra.PdfStatementLayout.Section;
import com.erancha.amexagent.card.infra.PdfTextLines.Chunk;
import com.erancha.amexagent.card.infra.PdfTextLines.Line;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Reads a monthly statement PDF into a Statement. The charge date comes from its labelled line and
 * fixes the billing month. Each transaction section is read from its title line to its total line,
 * resuming under its title on the next page, with every cell placed in a column by its right edge.
 * The charges of a section must add up to its printed total, so a row lost or misread fails the
 * parse instead of quietly shrinking the month.
 *
 * The statement has no voucher numbers, so a row's id is the charge date and the row's position in
 * the statement: stable for one file, unique across months.
 */
public class PdfStatementParser implements StatementParser {
  // STRICT rejects impossible days such as 31/02 instead of clamping them to the month's end.
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("dd/MM/uu").withResolverStyle(ResolverStyle.STRICT);
  private static final Pattern DATE_TEXT = Pattern.compile("\\d{2}/\\d{2}/\\d{2}");
  private static final Pattern AMOUNT = Pattern.compile("-?\\d{1,3}(?:,\\d{3})*\\.\\d{2}");
  private static final Pattern ORIGINAL = Pattern.compile("(\\S+)\\s*(" + AMOUNT.pattern() + ")");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private final PdfStatementLayout layout;
  private final Pattern installment;

  /** A section whose title was seen and whose total line was not yet reached. */
  private static final class Open {
    final List<Transaction> rows = new ArrayList<>();
    BigDecimal charged = BigDecimal.ZERO;
  }

  public PdfStatementParser(PdfStatementLayout layout) {
    this.layout = layout;
    this.installment = Pattern.compile(Pattern.quote(layout.installmentPrefix()) + "\\s+(\\d+)\\s+"
        + Pattern.quote(layout.installmentOf()) + "\\s+(\\d+)");
  }

  @Override
  public Statement parse(Path file) {
    return parse(PdfTextLines.read(file), file.getFileName().toString());
  }

  Statement parse(List<Line> lines, String src) {
    LocalDate chargeDate = chargeDate(lines, src);
    List<Transaction> rows = new ArrayList<>();
    // Key: a section whose title was seen. Value: its rows until its total line closes it.
    Map<Section, Open> started = new LinkedHashMap<>();
    Set<Section> closed = new HashSet<>();
    Section current = null;
    int page = 0;
    int ordinal = 0;
    for (Line line : lines) {
      if (line.page() != page) {
        page = line.page();
        current = null;
      }
      Section titled = titledSection(line);
      if (titled != null) {
        if (closed.contains(titled)) {
          throw fail(src, line, "the section '" + titled.title() + "' reopens after its total");
        }
        started.computeIfAbsent(titled, s -> new Open());
        current = titled;
      } else if (current != null && squeezed(line.text()).contains(squeezed(layout.totalLabel()))) {
        Open open = started.remove(current);
        checkTotal(current, open, line, src);
        rows.addAll(open.rows);
        closed.add(current);
        current = null;
      } else if (current != null) {
        Map<Column, String> cells = rowCells(current, line, src);
        if (cells != null) {
          Open open = started.get(current);
          Transaction t = row(current, cells, chargeDate, ++ordinal, line, src);
          open.rows.add(t);
          open.charged = open.charged.add(t.amount());
        }
      }
    }
    if (!started.isEmpty()) {
      throw new StatementFormatException(src + ": the section '"
          + started.keySet().iterator().next().title() + "' has no total line");
    }
    if (closed.isEmpty()) {
      throw new StatementFormatException(src + ": no transaction section found");
    }
    return new Statement(YearMonth.from(chargeDate), StatementCurrency.CODE, chargeDate,
        List.copyOf(rows));
  }

  private LocalDate chargeDate(List<Line> lines, String src) {
    String label = squeezed(layout.chargeDateLabel());
    for (Line line : lines) {
      if (squeezed(line.text()).contains(label)) {
        Matcher m = DATE_TEXT.matcher(line.text());
        if (!m.find()) {
          throw fail(src, line, "the charge date line holds no DD/MM/YY date");
        }
        return date(m.group(), line, src);
      }
    }
    throw new StatementFormatException(
        src + ": charge date label '" + layout.chargeDateLabel() + "' not found");
  }

  private @Nullable Section titledSection(Line line) {
    String text = squeezed(line.text());
    for (Section s : List.of(layout.abroad(), layout.domestic())) {
      if (text.startsWith(squeezed(s.title()))) {
        return s;
      }
    }
    return null;
  }

  /**
   * The cells of a transaction row, joined per column; null for the section's other lines (column
   * headers, the city line under a foreign merchant). A row is a line with a purchase date in the
   * date column; a charge without one fails, since that is a row this parser cannot read.
   */
  private @Nullable Map<Column, String> rowCells(Section section, Line line, String src) {
    List<Chunk> table = line.chunks().stream()
        .filter(c -> c.right() >= layout.tableLeft() && c.left() <= layout.tableRight())
        .toList();
    boolean dated = table.stream().anyMatch(c -> DATE_TEXT.matcher(c.text()).matches()
        && near(c.right(), section.rightEdges().get(Column.DATE)));
    if (!dated) {
      if (table.stream().anyMatch(c -> AMOUNT.matcher(c.text()).matches()
          && near(c.right(), section.rightEdges().get(Column.CHARGE)))) {
        throw fail(src, line, "a charge with no purchase date in the date column");
      }
      return null;
    }
    List<Map.Entry<Column, Float>> columns = section.rightEdges().entrySet().stream()
        .sorted(Map.Entry.comparingByValue()).toList();
    Map<Column, List<Chunk>> placed = new EnumMap<>(Column.class);
    for (Chunk chunk : table) {
      int i = 0;
      while (i < columns.size()
          && columns.get(i).getValue() + layout.columnTolerance() < chunk.right()) {
        i++;
      }
      if (i == columns.size()) {
        throw fail(src, line, "'" + chunk.text() + "' ends right of the last column");
      }
      if (i > 0 && chunk.left() < columns.get(i - 1).getValue() - layout.columnTolerance()) {
        throw fail(src, line, "'" + chunk.text() + "' spans the columns "
            + columns.get(i - 1).getKey() + " and " + columns.get(i).getKey());
      }
      placed.computeIfAbsent(columns.get(i).getKey(), k -> new ArrayList<>()).add(chunk);
    }
    Map<Column, String> cells = new EnumMap<>(Column.class);
    // A wide gap can split one cell into several chunks; Hebrew reads right to left.
    placed.forEach((column, chunks) -> cells.put(column, String.join(" ", chunks.stream()
        .sorted(Comparator.comparingDouble(Chunk::right).reversed()).map(Chunk::text).toList())));
    return cells;
  }

  private Transaction row(Section section, Map<Column, String> cells, LocalDate chargeDate,
      int ordinal, Line line, String src) {
    String id = String.format("%s-%02d", chargeDate.format(DateTimeFormatter.BASIC_ISO_DATE),
        ordinal);
    LocalDate purchased = date(cells.get(Column.DATE), line, src);
    String merchant = required(cells, Column.MERCHANT, line, src);
    BigDecimal charge = amount(required(cells, Column.CHARGE, line, src), line, src);
    if (section == layout.abroad()) {
      String original = required(cells, Column.ORIGINAL, line, src);
      Matcher m = ORIGINAL.matcher(original);
      if (!m.matches()) {
        throw fail(src, line, "original amount '" + original + "' is not a symbol and an amount");
      }
      String currency = StatementCurrency.codeFor(m.group(1));
      if (currency == null) {
        throw fail(src, line, "unknown currency symbol '" + m.group(1) + "'");
      }
      boolean foreign = !StatementCurrency.CODE.equals(currency);
      return Transaction.parsed(id, purchased, chargeDate, merchant, null, charge,
          foreign ? amount(m.group(2), line, src) : null, foreign ? currency : null, false, true,
          null);
    }
    String sector = required(cells, Column.SECTOR, line, src);
    // A domestic row leaves the detail and presence cells empty when they do not apply.
    String detail = cells.getOrDefault(Column.DETAIL, "");
    String presence = cells.getOrDefault(Column.PRESENCE, "");
    return Transaction.parsed(id, purchased, chargeDate, merchant, sector, charge, null, null,
        presence.equals(layout.standingOrder()), false, installment(detail, line, src));
  }

  private @Nullable Installment installment(String detail, Line line, String src) {
    Matcher m = installment.matcher(detail);
    if (!m.find()) {
      return null;
    }
    try {
      return new Installment(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
    } catch (IllegalArgumentException e) {
      throw fail(src, line, "installment '" + m.group() + "' is invalid: " + e.getMessage());
    }
  }

  private void checkTotal(Section section, Open open, Line line, String src) {
    float chargeEdge = section.rightEdges().get(Column.CHARGE);
    Chunk total = line.chunks().stream()
        .filter(c -> AMOUNT.matcher(c.text()).matches() && near(c.right(), chargeEdge))
        .findFirst()
        .orElseThrow(() -> fail(src, line, "the total line has no amount in the charge column"));
    BigDecimal printed = amount(total.text(), line, src);
    if (printed.compareTo(open.charged) != 0) {
      throw fail(src, line, "the section '" + section.title() + "' prints a total of " + printed
          + " but its " + open.rows.size() + " rows charge " + open.charged);
    }
  }

  private String required(Map<Column, String> cells, Column column, Line line, String src) {
    String value = cells.get(column);
    if (value == null || value.isBlank()) {
      throw fail(src, line, "no " + column + " cell");
    }
    return value;
  }

  private LocalDate date(String text, Line line, String src) {
    try {
      return LocalDate.parse(text, DATE);
    } catch (DateTimeParseException e) {
      throw new StatementFormatException(src + ": page " + line.page() + ", line '" + line.text()
          + "': date '" + text + "' is not a valid DD/MM/YY", e);
    }
  }

  private BigDecimal amount(String text, Line line, String src) {
    if (!AMOUNT.matcher(text).matches()) {
      throw fail(src, line, "'" + text + "' is not an amount");
    }
    return new BigDecimal(text.replace(",", ""));
  }

  private boolean near(float x, float edge) {
    return Math.abs(x - edge) <= layout.columnTolerance();
  }

  private static String squeezed(String text) {
    return WHITESPACE.matcher(text).replaceAll("");
  }

  private static StatementFormatException fail(String src, Line line, String problem) {
    return new StatementFormatException(
        src + ": page " + line.page() + ", line '" + line.text() + "': " + problem);
  }
}
