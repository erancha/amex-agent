package com.erancha.amexagent.card.application;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Collapses a raw statement merchant string to the key the categorizer caches by. */
public final class MerchantNormalizer {
  private static final Pattern TRAILING_NUMBERS = Pattern.compile("(\\s+\\d+)+$");
  private static final Pattern SPACES = Pattern.compile("\\s+");
  private static final Set<String> COMPANY_FORMS =
      Set.of("בעמ", "בע\"מ", "בע\u05F4מ", "ltd", "ltd.", "inc", "inc.", "llc");

  private MerchantNormalizer() {}

  public static String key(String raw) {
    String s = SPACES.matcher(raw.trim()).replaceAll(" ").toLowerCase(Locale.ROOT);
    s = TRAILING_NUMBERS.matcher(s).replaceAll("");
    String[] words = s.split(" ");
    StringBuilder out = new StringBuilder();
    for (String w : words) {
      if (COMPANY_FORMS.contains(w)) {
        continue;
      }
      if (!out.isEmpty()) {
        out.append(' ');
      }
      out.append(w);
    }
    return TRAILING_NUMBERS.matcher(out.toString()).replaceAll("");
  }
}
