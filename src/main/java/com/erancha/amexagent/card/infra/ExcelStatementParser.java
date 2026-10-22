package com.erancha.amexagent.card.infra;

import com.erancha.amexagent.card.api.Installment;
import com.erancha.amexagent.card.api.Statement;
import com.erancha.amexagent.card.api.Transaction;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jspecify.annotations.Nullable;

/**
 * Reads one card export into a Statement. The header block is read from fixed cells; the table is
 * located by its header labels and read until the total row; the trailer is ignored.
 */
public class ExcelStatementParser implements StatementParser {
  // STRICT rejects impossible days such as 31.02 instead of clamping them to the month's end.
  private static final DateTimeFormatter PURCHASE_DATE =
      DateTimeFormatter.ofPattern("dd.MM.uu").withResolverStyle(ResolverStyle.STRICT);
  private static final Pattern VOUCHER = Pattern.compile("\\d{9}");
  private static final Pattern CHARGE_DATE = Pattern.compile("(\\d{2})\\.(\\d{2})");
  private final ExcelStatementLayout layout;
  private final DataFormatter formatter = new DataFormatter();

  public ExcelStatementParser(ExcelStatementLayout layout) {
    this.layout = layout;
  }

  @Override
  public Statement parse(Path file) {
    try (InputStream in = Files.newInputStream(file)) {
      return parse(in, file.getFileName().toString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public Statement parse(InputStream in, String sourceName) {
    try (Workbook wb = new XSSFWorkbook(in)) {
      Sheet sheet = wb.getSheet(layout.sheetName());
      if (sheet == null) {
        throw new StatementFormatException(
            sourceName + ": no sheet named '" + layout.sheetName() + "'");
      }
      YearMonth billingMonth =
          HebrewMonths.parse(text(sheet, layout.billingMonthCell(), sourceName));
      LocalDate chargeDate =
          chargeDate(text(sheet, layout.chargeDateCell(), sourceName), billingMonth, sourceName);
      int headerRow = findRow(sheet, layout.headerMerchant(), sourceName);
      Map<String, Integer> columns = columns(sheet.getRow(headerRow), sourceName);
      int totalRow = findTotalRow(sheet, headerRow, sourceName);
      List<Transaction> rows = new ArrayList<>();
      for (int r = headerRow + 1; r < totalRow; r++) {
        Row row = sheet.getRow(r);
        if (row != null) {
          rows.add(transaction(row, columns, chargeDate, sourceName));
        }
      }
      return new Statement(billingMonth, StatementCurrency.CODE, chargeDate, List.copyOf(rows));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Transaction transaction(
      Row row, Map<String, Integer> col, LocalDate chargeDate, String src) {
    Cell detailCell = row.getCell(col.get(layout.headerDetail()));
    String detail = cellText(detailCell);
    String purchaseCurrency = currency(row.getCell(col.get(layout.headerCurrency())), row, src);
    BigDecimal purchaseAmount = amount(row.getCell(col.get(layout.headerAmount())), src);
    BigDecimal chargeAmount = amount(row.getCell(col.get(layout.headerChargeAmount())), src);
    requireStatementCurrency(row.getCell(col.get(layout.headerChargeCurrency())), row, src);
    boolean foreign = !StatementCurrency.CODE.equals(purchaseCurrency);
    return Transaction.parsed(
        voucher(row.getCell(col.get(layout.headerVoucher())), row, src),
        purchaseDate(row.getCell(col.get(layout.headerPurchaseDate())), row, src),
        chargeDate,
        cellText(row, col.get(layout.headerMerchant())),
        null,
        chargeAmount,
        foreign ? purchaseAmount : null,
        foreign ? purchaseCurrency : null,
        detail.contains(layout.detailStandingOrder()),
        detail.contains(layout.detailAbroad()),
        installment(detail, detailCell, src));
  }

  private String voucher(@Nullable Cell cell, Row row, String src) {
    String value = cellText(cell);
    if (!VOUCHER.matcher(value).matches()) {
      throw new StatementFormatException(src + ": voucher '" + value + "' at "
          + address(cell, row, layout.headerVoucher()) + " is not 9 digits");
    }
    return value;
  }

  private LocalDate purchaseDate(@Nullable Cell cell, Row row, String src) {
    String value = cellText(cell);
    try {
      return LocalDate.parse(value, PURCHASE_DATE);
    } catch (DateTimeParseException e) {
      throw new StatementFormatException(src + ": purchase date '" + value + "' at "
          + address(cell, row, layout.headerPurchaseDate()) + " is not a valid DD.MM.YY", e);
    }
  }

  private @Nullable Installment installment(String detail, @Nullable Cell cell, String src) {
    Pattern p = Pattern.compile(Pattern.quote(layout.detailInstallmentPrefix()) + "\\s+(\\d+)\\s+"
        + Pattern.quote(layout.detailInstallmentOf()) + "\\s+(\\d+)");
    Matcher m = p.matcher(detail);
    if (!m.find()) {
      return null;
    }
    try {
      return new Installment(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
    } catch (IllegalArgumentException e) {
      throw new StatementFormatException(src + ": installment '" + m.group() + "' at "
          + cell.getAddress().formatAsString() + " is invalid: " + e.getMessage(), e);
    }
  }

  private int findRow(Sheet sheet, String label, String src) {
    for (Row row : sheet) {
      for (Cell cell : row) {
        if (label.equals(cellText(cell))) {
          return row.getRowNum();
        }
      }
    }
    throw new StatementFormatException(src + ": header label '" + label + "' not found");
  }

  private int findTotalRow(Sheet sheet, int headerRow, String src) {
    for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
      Row row = sheet.getRow(r);
      if (row != null && layout.totalLabel().equals(cellText(row, 1))) {
        return r;
      }
    }
    throw new StatementFormatException(
        src + ": total row '" + layout.totalLabel() + "' not found");
  }

  private Map<String, Integer> columns(Row header, String src) {
    Map<String, Integer> byLabel = new HashMap<>();
    for (Cell cell : header) {
      byLabel.put(cellText(cell), cell.getColumnIndex());
    }
    for (String required : List.of(layout.headerPurchaseDate(), layout.headerMerchant(),
        layout.headerAmount(), layout.headerCurrency(), layout.headerChargeAmount(),
        layout.headerChargeCurrency(), layout.headerVoucher(), layout.headerDetail())) {
      if (!byLabel.containsKey(required)) {
        throw new StatementFormatException(src + ": header label '" + required + "' not found");
      }
    }
    return byLabel;
  }

  private LocalDate chargeDate(String cellValue, YearMonth billingMonth, String src) {
    Matcher m = CHARGE_DATE.matcher(cellValue);
    if (!m.find()) {
      throw new StatementFormatException(
          src + ": charge date '" + cellValue + "' at " + layout.chargeDateCell()
              + " has no DD.MM");
    }
    try {
      return LocalDate.of(
          billingMonth.getYear(), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
    } catch (DateTimeException e) {
      throw new StatementFormatException(src + ": charge date '" + cellValue + "' at "
          + layout.chargeDateCell() + " is not a valid DD.MM", e);
    }
  }

  private String currency(@Nullable Cell cell, Row row, String src) {
    String symbol = cellText(cell);
    String code = StatementCurrency.codeFor(symbol);
    if (code == null) {
      throw new StatementFormatException(src + ": unknown currency symbol '" + symbol + "' at "
          + address(cell, row, layout.headerCurrency()));
    }
    return code;
  }

  private void requireStatementCurrency(@Nullable Cell cell, Row row, String src) {
    String symbol = cellText(cell);
    if (!StatementCurrency.SYMBOL.equals(symbol)) {
      throw new StatementFormatException(src + ": charge currency '" + symbol + "' at "
          + address(cell, row, layout.headerChargeCurrency()) + " is not "
          + StatementCurrency.SYMBOL + " (" + StatementCurrency.CODE + ")");
    }
  }

  private BigDecimal amount(@Nullable Cell cell, String src) {
    if (cell == null || cell.getCellType() != CellType.NUMERIC) {
      throw new StatementFormatException(src + ": expected a numeric amount at "
          + (cell == null ? "a missing cell" : cell.getAddress().formatAsString()));
    }
    return BigDecimal.valueOf(cell.getNumericCellValue()).setScale(2, RoundingMode.HALF_UP);
  }

  private String text(Sheet sheet, String ref, String src) {
    CellReference cr = new CellReference(ref);
    Row row = sheet.getRow(cr.getRow());
    Cell cell = row == null ? null : row.getCell(cr.getCol());
    if (cell == null) {
      throw new StatementFormatException(src + ": expected a value in cell " + ref);
    }
    return cellText(cell);
  }

  private String cellText(Row row, int column) {
    return cellText(row.getCell(column));
  }

  private String cellText(@Nullable Cell cell) {
    if (cell == null) {
      return "";
    }
    // The card site's exporter writes an empty cell as an inline string with no text element, for
    // which POI's formatter answers null rather than "".
    String value = formatter.formatCellValue(cell);
    return value == null ? "" : value.trim();
  }

  private String address(@Nullable Cell cell, Row row, String columnLabel) {
    return cell == null
        ? "a missing cell in sheet row " + (row.getRowNum() + 1) + " (" + columnLabel + ")"
        : cell.getAddress().formatAsString();
  }
}
