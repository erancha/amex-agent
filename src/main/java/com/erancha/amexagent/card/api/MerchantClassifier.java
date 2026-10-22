package com.erancha.amexagent.card.api;

import java.util.List;

/**
 * Classifies a batch of merchants, each a raw statement string with the issuer's sector label when
 * the statement prints one; answers come back in the same order.
 */
public interface MerchantClassifier {
  List<MerchantCategory> classify(List<MerchantQuery> merchants);
}
