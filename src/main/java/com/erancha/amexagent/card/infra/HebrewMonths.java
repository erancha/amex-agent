package com.erancha.amexagent.card.infra;

import java.time.YearMonth;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Hebrew month names as the card export prints them, e.g. "אוקטובר 2026". */
public final class HebrewMonths {
  private static final Map<String, Integer> BY_NAME = Map.ofEntries(
      Map.entry("ינואר", 1), Map.entry("פברואר", 2), Map.entry("מרץ", 3), Map.entry("אפריל", 4),
      Map.entry("מאי", 5), Map.entry("יוני", 6), Map.entry("יולי", 7), Map.entry("אוגוסט", 8),
      Map.entry("ספטמבר", 9), Map.entry("אוקטובר", 10), Map.entry("נובמבר", 11),
      Map.entry("דצמבר", 12));
  private static final Pattern LABEL = Pattern.compile("^\\s*(\\S+)\\s+(\\d{4})\\s*$");

  private HebrewMonths() {}

  public static YearMonth parse(String label) {
    Matcher m = LABEL.matcher(label);
    Integer month = m.matches() ? BY_NAME.get(m.group(1)) : null;
    if (month == null) {
      throw new IllegalArgumentException("Not a Hebrew billing-month label: '" + label + "'");
    }
    return YearMonth.of(Integer.parseInt(m.group(2)), month);
  }
}
