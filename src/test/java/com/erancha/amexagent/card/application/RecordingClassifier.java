package com.erancha.amexagent.card.application;

import com.erancha.amexagent.card.api.MerchantCategory;
import com.erancha.amexagent.card.api.MerchantClassifier;
import com.erancha.amexagent.card.api.MerchantQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fake classifier: scripted answers by raw merchant, records every batch it is asked. */
final class RecordingClassifier implements MerchantClassifier {
  final List<List<MerchantQuery>> batches = new ArrayList<>();
  private final Map<String, MerchantCategory> answers;

  RecordingClassifier(Map<String, MerchantCategory> answers) {
    this.answers = answers;
  }

  @Override
  public List<MerchantCategory> classify(List<MerchantQuery> merchants) {
    batches.add(List.copyOf(merchants));
    return merchants.stream().map(q -> {
      MerchantCategory a = answers.get(q.rawMerchant());
      if (a == null) {
        throw new AssertionError("unscripted merchant " + q.rawMerchant());
      }
      return a;
    }).toList();
  }
}
