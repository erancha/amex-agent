package com.erancha.amexagent.card.application;

import com.erancha.amexagent.card.api.MerchantCategory;
import com.erancha.amexagent.card.api.MerchantClassifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fake classifier: scripted answers by raw merchant, records every batch it is asked. */
final class RecordingClassifier implements MerchantClassifier {
  final List<List<String>> batches = new ArrayList<>();
  private final Map<String, MerchantCategory> answers;

  RecordingClassifier(Map<String, MerchantCategory> answers) {
    this.answers = answers;
  }

  @Override
  public List<MerchantCategory> classify(List<String> rawMerchants) {
    batches.add(List.copyOf(rawMerchants));
    return rawMerchants.stream().map(raw -> {
      MerchantCategory a = answers.get(raw);
      if (a == null) {
        throw new AssertionError("unscripted merchant " + raw);
      }
      return a;
    }).toList();
  }
}
