package com.erancha.amexagent.card.infra;

/**
 * Where each element of a card export lives: fixed cells for the header block and the text labels
 * that identify the transaction table, so a changed export fails naming the missing label.
 */
public record ExcelStatementLayout(
    String sheetName,
    String billingMonthCell,
    String cardCell,
    String chargeDateCell,
    String sectionHeader,
    String headerPurchaseDate,
    String headerMerchant,
    String headerAmount,
    String headerCurrency,
    String headerChargeAmount,
    String headerChargeCurrency,
    String headerVoucher,
    String headerDetail,
    String totalLabel,
    String detailStandingOrder,
    String detailAbroad,
    String detailInstallmentPrefix,
    String detailInstallmentOf) {

  /** The American Express Israel export (card issuer Isracard). */
  public static final ExcelStatementLayout AMEX_ISRAEL = new ExcelStatementLayout(
      "פירוט עסקאות", "C2", "A5", "H6", "עסקאות למועד חיוב",
      "תאריך רכישה", "שם בית עסק", "סכום עסקה", "מטבע עסקה", "סכום חיוב", "מטבע חיוב",
      "מס' שובר", "פירוט נוסף", "סה\"כ לחיוב החודש בכרטיס בש\"ח",
      "הוראת קבע", "בוצע בחו\"ל", "תשלום", "מתוך");
}
