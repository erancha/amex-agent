package com.erancha.amexagent.card.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erancha.amexagent.card.api.Category;
import com.erancha.amexagent.card.api.Confidence;
import com.erancha.amexagent.card.api.MerchantCategory;
import com.erancha.amexagent.card.api.MerchantQuery;
import com.erancha.amexagent.card.api.Transaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies caching by normalized merchant, batching of unseen merchants, pairing answers by echoed
 * merchant, the statement sector reaching the classifier, omitted answer fields, and the
 * confidence levels.
 */
class MerchantCategorizerTest {
  private static Transaction tx(String id, String raw) {
    return tx(id, raw, null);
  }

  private static Transaction tx(String id, String raw, String sector) {
    return Transaction.parsed(id, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 2), raw,
        sector, new BigDecimal("10.00"), null, null, false, false, null);
  }

  @Test
  void categorize_repeatMerchants_askTheClassifierOnceEach() {
    RecordingClassifier classifier = new RecordingClassifier(Map.of(
        "סופר פארם בעמ 123",
        new MerchantCategory("סופר פארם בעמ 123", "Super-Pharm", "health", 0.9),
        "פז יהוד", new MerchantCategory("פז יהוד", "Paz", "transport", 0.8)));
    MerchantCategorizer categorizer = new MerchantCategorizer(classifier, 0.85, 0.6, 40);

    List<Transaction> out = categorizer.categorize(List.of(
        tx("1", "סופר פארם בעמ 123"), tx("2", "סופר פארם בעמ 456"), tx("3", "פז יהוד")));

    assertThat(classifier.batches).hasSize(1);
    assertThat(classifier.batches.get(0)).extracting(MerchantQuery::rawMerchant)
        .containsExactly("סופר פארם בעמ 123", "פז יהוד");
    assertThat(out).extracting(Transaction::category)
        .containsExactly(Category.HEALTH, Category.HEALTH, Category.TRANSPORT);
    assertThat(out.get(1).merchant()).isEqualTo("Super-Pharm");

    categorizer.categorize(List.of(tx("4", "פז יהוד 0421")));
    assertThat(classifier.batches).hasSize(1);
  }

  @Test
  void categorize_handsTheStatementSectorToTheClassifierBesideTheMerchant() {
    RecordingClassifier classifier = new RecordingClassifier(Map.of("דר יוסי משה מזרחי",
        new MerchantCategory("דר יוסי משה מזרחי", "Dr. Yossi Mizrahi", "health", 0.95)));

    new MerchantCategorizer(classifier, 0.85, 0.6, 40)
        .categorize(List.of(tx("1", "דר יוסי משה מזרחי", "שרות רפואי")));

    assertThat(classifier.batches.get(0))
        .containsExactly(new MerchantQuery("דר יוסי משה מזרחי", "שרות רפואי"));
  }

  @Test
  void categorize_manyUnseenMerchants_splitsIntoBatches() {
    Map<String, MerchantCategory> answers = new HashMap<>();
    List<Transaction> in = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      String raw = "merchant-" + i;
      answers.put(raw, new MerchantCategory(raw, raw, "other", 0.9));
      in.add(tx(String.valueOf(i), raw));
    }
    RecordingClassifier classifier = new RecordingClassifier(answers);
    new MerchantCategorizer(classifier, 0.85, 0.6, 2).categorize(in);
    assertThat(classifier.batches).extracting(List::size).containsExactly(2, 2, 1);
  }

  @Test
  void categorize_scores_becomeLevelsAndALowScoreKeepsItsGuess() {
    RecordingClassifier classifier = new RecordingClassifier(Map.of(
        "sure", new MerchantCategory("sure", "Sure", "groceries", 0.85),
        "fair", new MerchantCategory("fair", "Fair", "dining", 0.6),
        "mystery", new MerchantCategory("mystery", "Mystery Ltd", "dining", 0.3)));
    List<Transaction> out = new MerchantCategorizer(classifier, 0.85, 0.6, 40)
        .categorize(List.of(tx("1", "sure"), tx("2", "fair"), tx("3", "mystery")));
    assertThat(out).extracting(Transaction::confidence)
        .containsExactly(Confidence.HIGH, Confidence.MEDIUM, Confidence.LOW);
    assertThat(out.get(2).category()).isEqualTo(Category.DINING);
    assertThat(out.get(2).merchant()).isEqualTo("Mystery Ltd");
  }

  @Test
  void categorize_unknownCategoryFromModel_isUncategorized() {
    RecordingClassifier classifier = new RecordingClassifier(Map.of(
        "x", new MerchantCategory("x", "X", "pharmacy", 0.95),
        "y", new MerchantCategory("y", "Y", "transport", 1.7)));
    List<Transaction> out = new MerchantCategorizer(classifier, 0.85, 0.6, 40)
        .categorize(List.of(tx("1", "x"), tx("2", "y")));
    assertThat(out.get(0).category()).isEqualTo(Category.UNCATEGORIZED);
    assertThat(out.get(1).category()).isEqualTo(Category.UNCATEGORIZED);
    assertThat(out.get(1).confidence()).isEqualTo(Confidence.LOW);
  }

  @Test
  void constructor_rejectsBatchSizeBelowOne() {
    assertThatThrownBy(() -> new MerchantCategorizer(raws -> List.of(), 0.85, 0.6, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("batchSize must be at least 1, got 0");
  }

  @Test
  void constructor_rejectsThresholdsOutOfOrderOrOutsideUnitRange() {
    assertThatThrownBy(() -> new MerchantCategorizer(raws -> List.of(), 1.5, 0.6, 40))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("got medium 0.6 and high 1.5");
    assertThatThrownBy(() -> new MerchantCategorizer(raws -> List.of(), 0.85, -0.1, 40))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("0 <= medium <= high <= 1");
    assertThatThrownBy(() -> new MerchantCategorizer(raws -> List.of(), 0.5, 0.6, 40))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("got medium 0.6 and high 0.5");
  }

  @Test
  void categorize_classifierReturnsWrongCount_throws() {
    MerchantCategorizer categorizer = new MerchantCategorizer(raws -> List.of(), 0.85, 0.6, 40);
    assertThatThrownBy(() -> categorizer.categorize(List.of(tx("1", "a"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("1")
        .hasMessageContaining("0");
  }

  @Test
  void categorize_reorderedAnswers_landOnTheirEchoedMerchants() {
    MerchantCategorizer categorizer = new MerchantCategorizer(raws -> List.of(
        new MerchantCategory("b", "B", "transport", 0.9),
        new MerchantCategory("a", "A", "dining", 0.9)), 0.85, 0.6, 40);
    List<Transaction> out = categorizer.categorize(List.of(tx("1", "a"), tx("2", "b")));
    assertThat(out).extracting(Transaction::merchant).containsExactly("A", "B");
    assertThat(out).extracting(Transaction::category)
        .containsExactly(Category.DINING, Category.TRANSPORT);
  }

  @Test
  void categorize_answerEchoingUnrequestedMerchant_leavesRequestedOneUncategorized() {
    MerchantCategorizer categorizer = new MerchantCategorizer(raws -> List.of(
        new MerchantCategory("a", "A", "dining", 0.9),
        new MerchantCategory("typo", "B", "transport", 0.9)), 0.85, 0.6, 40);
    List<Transaction> out = categorizer.categorize(List.of(tx("1", "a"), tx("2", "b")));
    assertThat(out.get(0).category()).isEqualTo(Category.DINING);
    assertThat(out.get(1).category()).isEqualTo(Category.UNCATEGORIZED);
    assertThat(out.get(1).confidence()).isEqualTo(Confidence.LOW);
    assertThat(out.get(1).merchant()).isEqualTo("b");
  }

  @Test
  void categorize_omittedEcho_isUncategorizedWithoutThrowing() {
    MerchantCategorizer categorizer = new MerchantCategorizer(
        raws -> List.of(new MerchantCategory(null, "A", "dining", 0.9)), 0.85, 0.6, 40);
    Transaction out = categorizer.categorize(List.of(tx("1", "a"))).get(0);
    assertThat(out.category()).isEqualTo(Category.UNCATEGORIZED);
    assertThat(out.confidence()).isEqualTo(Confidence.LOW);
  }

  @Test
  void categorize_omittedMerchantAndCategory_fallBackToRawAndUncategorized() {
    RecordingClassifier classifier = new RecordingClassifier(Map.of(
        "a", new MerchantCategory("a", null, "dining", 0.9),
        "b", new MerchantCategory("b", "B", null, 0.9)));
    List<Transaction> out = new MerchantCategorizer(classifier, 0.85, 0.6, 40)
        .categorize(List.of(tx("1", "a"), tx("2", "b")));
    assertThat(out.get(0).merchant()).isEqualTo("a");
    assertThat(out.get(0).category()).isEqualTo(Category.DINING);
    assertThat(out.get(1).merchant()).isEqualTo("B");
    assertThat(out.get(1).category()).isEqualTo(Category.UNCATEGORIZED);
  }
}
