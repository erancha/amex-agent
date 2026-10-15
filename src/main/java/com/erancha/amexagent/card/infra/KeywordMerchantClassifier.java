package com.erancha.amexagent.card.infra;

import com.erancha.amexagent.card.api.MerchantCategory;
import com.erancha.amexagent.card.api.MerchantClassifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The e2e profile's classifier: a fixed keyword table so end-to-end tests run without a language
 * model or API key. Known merchants answer with confidence 1.0, everything else is uncategorized
 * at 0.0. The table covers the merchants of the committed test statement except its gym, so a
 * test review always has one merchant for the human to categorize.
 */
public class KeywordMerchantClassifier implements MerchantClassifier {
  private record Known(String displayName, String category) {}

  // Key: lower-cased substring of the raw merchant. Value: the answer for it. Insertion order
  // is the match priority, so the first matching entry always wins.
  private static final Map<String, Known> KNOWN = new LinkedHashMap<>();

  static {
    KNOWN.put("netflix", new Known("Netflix", "entertainment"));
    KNOWN.put("apple tv", new Known("Apple TV", "entertainment"));
    KNOWN.put("yes", new Known("yes", "entertainment"));
    KNOWN.put("שטראוס מים", new Known("Strauss Water", "utilities"));
    KNOWN.put("ביטוח", new Known("Insurance", "insurance"));
    KNOWN.put("ישיר", new Known("Direct Insurance", "insurance"));
    KNOWN.put("דר יוסי", new Known("Dr. Yossi", "health"));
    KNOWN.put("כללית", new Known("Clalit", "health"));
    KNOWN.put("מוצרי חשמל", new Known("Electrical goods", "shopping"));
    KNOWN.put("שיראז", new Known("Shiraz Electric", "shopping"));
    KNOWN.put("סופר אבי", new Known("Super Avi", "shopping"));
    KNOWN.put("אייס", new Known("ACE", "housing"));
    KNOWN.put("ארנונה", new Known("Property tax", "housing"));
    KNOWN.put("סלקום", new Known("Cellcom", "utilities"));
    KNOWN.put("בזק", new Known("Bezeq", "utilities"));
    KNOWN.put("מניב", new Known("Water corporation", "utilities"));
    KNOWN.put("דרך ארץ", new Known("Derech Eretz toll road", "transport"));
    KNOWN.put("משרד התחבורה", new Known("Ministry of Transport", "transport"));
  }

  @Override
  public List<MerchantCategory> classify(List<String> rawMerchants) {
    return rawMerchants.stream().map(raw -> {
      String lower = raw.toLowerCase(Locale.ROOT);
      return KNOWN.entrySet().stream()
          .filter(e -> lower.contains(e.getKey()))
          .findFirst()
          .map(e -> new MerchantCategory(
              raw, e.getValue().displayName(), e.getValue().category(), 1.0))
          .orElse(new MerchantCategory(raw, raw, "uncategorized", 0.0));
    }).toList();
  }
}
