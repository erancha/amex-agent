package com.erancha.amexagent.card.infra;

import java.util.Map;

/**
 * Where each element of a card statement PDF lives: the labels that mark the charge date, the
 * transaction sections and their total lines, and the right edge of every table column in page
 * points. Every column of the right-to-left tables is right-aligned, so a cell is placed by where
 * its text ends; a changed statement fails naming the line that no longer fits.
 *
 * @param columnTolerance how far, in points, a cell's right edge may stray from its column's
 *     right edge
 * @param tableLeft text ending left of this x is margin decoration, not table content
 * @param tableRight text starting right of this x is margin decoration, not table content
 */
public record PdfStatementLayout(
    String chargeDateLabel,
    String totalLabel,
    Section abroad,
    Section domestic,
    String standingOrder,
    String installmentPrefix,
    String installmentOf,
    float columnTolerance,
    float tableLeft,
    float tableRight) {

  /** The table columns either section may print; each section uses a subset. */
  public enum Column {
    DATE, KIND, MERCHANT, PRESENCE, SECTOR, AMOUNT, ORIGINAL, USD, CONVERSION_DATE, RATE, FEE,
    CHARGE, DETAIL
  }

  /**
   * One transaction table: the title line that opens it on every page it spans, and the right edge
   * of each of its columns.
   */
  public record Section(String title, Map<Column, Float> rightEdges) {}

  /** The American Express Israel monthly statement (card issuer Isracard). */
  public static final PdfStatementLayout AMEX_ISRAEL = new PdfStatementLayout(
      "פרוט פעולותיך לתאריך:", "סה\"כ חיוב לתאריך",
      new Section("רכישות בחו\"ל", Map.of(
          Column.CHARGE, 176f, Column.FEE, 215f, Column.RATE, 242f, Column.CONVERSION_DATE, 283f,
          Column.USD, 326f, Column.ORIGINAL, 371f, Column.MERCHANT, 473f, Column.KIND, 482f,
          Column.DATE, 519f)),
      new Section("עסקות שחויבו / זוכו - בארץ", Map.of(
          Column.DETAIL, 213f, Column.CHARGE, 257f, Column.AMOUNT, 299f, Column.SECTOR, 350f,
          Column.MERCHANT, 441f, Column.PRESENCE, 475f, Column.DATE, 519f)),
      "ה.קבע", "תשלום", "מתוך", 4f, 130f, 525f);
}
