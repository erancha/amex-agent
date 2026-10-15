package com.erancha.amexagent.card.api;

import java.util.List;

/** Classifies a batch of raw merchant strings; answers come back in the same order. */
public interface MerchantClassifier {
  List<MerchantCategory> classify(List<String> rawMerchants);
}
