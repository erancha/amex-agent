package com.erancha.amexagent.card.application;

import com.erancha.amexagent.card.api.Category;
import com.erancha.amexagent.card.api.Confidence;
import com.erancha.amexagent.card.api.MerchantCategory;
import com.erancha.amexagent.card.api.MerchantClassifier;
import com.erancha.amexagent.card.api.MerchantQuery;
import com.erancha.amexagent.card.api.Transaction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assigns a display name, category and confidence to parsed transactions. Merchants are keyed by
 * MerchantNormalizer; unseen keys are classified in batches of batchSize through the classifier,
 * each sent as the raw string and statement sector of its first row. Each answer is paired with
 * its merchant by the echoed raw merchant. The model's score becomes HIGH from highConfidence up,
 * MEDIUM from mediumConfidence up, else LOW; a LOW answer keeps its category as the agent's best
 * guess. A merchant left without a matching answer, or an answer outside the taxonomy or the
 * [0,1] score range, is UNCATEGORIZED and LOW. categorize is synchronized so each merchant key is
 * classified once per process; concurrent callers serialize their model calls.
 */
public class MerchantCategorizer {
  private record Decision(String merchant, Category category, Confidence confidence) {}

  private static final Logger log = LoggerFactory.getLogger(MerchantCategorizer.class);

  private final MerchantClassifier classifier;
  private final double highConfidence;
  private final double mediumConfidence;
  private final int batchSize;
  // Key: normalized merchant. Value: the validated decision for it. Process-lifetime cache.
  private final Map<String, Decision> cache = new HashMap<>();

  public MerchantCategorizer(MerchantClassifier classifier, double highConfidence,
      double mediumConfidence, int batchSize) {
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be at least 1, got " + batchSize);
    }
    if (!(0.0 <= mediumConfidence && mediumConfidence <= highConfidence && highConfidence <= 1.0)) {
      throw new IllegalArgumentException("confidence thresholds must satisfy 0 <= medium <= high"
          + " <= 1, got medium " + mediumConfidence + " and high " + highConfidence);
    }
    this.classifier = classifier;
    this.highConfidence = highConfidence;
    this.mediumConfidence = mediumConfidence;
    this.batchSize = batchSize;
  }

  public synchronized List<Transaction> categorize(List<Transaction> parsed) {
    // Key: normalized merchant. Value: one example row's query to send to the classifier.
    Map<String, MerchantQuery> unseen = new LinkedHashMap<>();
    for (Transaction t : parsed) {
      String key = MerchantNormalizer.key(t.rawMerchant());
      if (!cache.containsKey(key)) {
        unseen.putIfAbsent(key, new MerchantQuery(t.rawMerchant(), t.sector()));
      }
    }
    List<MerchantQuery> queries = new ArrayList<>(unseen.values());
    for (int from = 0; from < queries.size(); from += batchSize) {
      List<MerchantQuery> batch = queries.subList(from, Math.min(from + batchSize, queries.size()));
      List<MerchantCategory> answers = classifier.classify(batch);
      if (answers.size() != batch.size()) {
        throw new IllegalStateException("Classifier answered " + answers.size()
            + " merchants for a batch of " + batch.size());
      }
      Map<String, MerchantCategory> byRaw = matchByEcho(batch, answers);
      for (MerchantQuery query : batch) {
        String raw = query.rawMerchant();
        cache.put(MerchantNormalizer.key(raw), decide(raw, byRaw.get(raw)));
      }
    }
    return parsed.stream().map(t -> {
      Decision d = cache.get(MerchantNormalizer.key(t.rawMerchant()));
      return t.withCategory(d.merchant(), d.category(), d.confidence());
    }).toList();
  }

  /**
   * Indexes answers by the raw merchant they echo. An echo that names no requested merchant, or one
   * already answered, is discarded with a warning.
   *
   * @return key: requested raw merchant; value: the first answer echoing it
   */
  private static Map<String, MerchantCategory> matchByEcho(List<MerchantQuery> batch,
      List<MerchantCategory> answers) {
    // HashSet, not Set.copyOf: an omitted echo arrives as null and must test as unrequested.
    Set<String> requested = new HashSet<>();
    for (MerchantQuery query : batch) {
      requested.add(query.rawMerchant());
    }
    Map<String, MerchantCategory> byRaw = new HashMap<>();
    for (MerchantCategory answer : answers) {
      if (!requested.contains(answer.rawMerchant())
          || byRaw.putIfAbsent(answer.rawMerchant(), answer) != null) {
        log.warn("Classifier answer echoes unrequested or repeated merchant '{}'; discarded",
            answer.rawMerchant());
      }
    }
    return byRaw;
  }

  private Decision decide(String raw, MerchantCategory answer) {
    if (answer == null) {
      log.warn("Classifier returned no answer for merchant '{}'; left uncategorized", raw);
      return new Decision(raw, Category.UNCATEGORIZED, Confidence.LOW);
    }
    // Spring AI maps an omitted JSON field to null; a missing or blank display name is a legal
    // answer and the raw merchant is shown instead.
    String merchant = answer.merchant() == null || answer.merchant().isBlank()
        ? raw : answer.merchant();
    double score = answer.confidence();
    Category category = Category.fromModelValue(answer.category());
    if (!(score >= 0.0 && score <= 1.0) || category == Category.UNCATEGORIZED) {
      return new Decision(merchant, Category.UNCATEGORIZED, Confidence.LOW);
    }
    return new Decision(merchant, category, level(score));
  }

  private Confidence level(double score) {
    if (score >= highConfidence) {
      return Confidence.HIGH;
    }
    return score >= mediumConfidence ? Confidence.MEDIUM : Confidence.LOW;
  }
}
