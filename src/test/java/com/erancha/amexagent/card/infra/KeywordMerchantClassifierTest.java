package com.erancha.amexagent.card.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.erancha.amexagent.card.api.MerchantCategory;
import com.erancha.amexagent.card.api.MerchantQuery;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies the no-key classifier is deterministic and knows all statement merchants but one. */
class KeywordMerchantClassifierTest {
  private final KeywordMerchantClassifier classifier = new KeywordMerchantClassifier();

  @Test
  void classify_testStatementMerchants_allKnownButTheGym() {
    List<MerchantQuery> merchants = new PdfStatementParser(PdfStatementLayout.AMEX_ISRAEL)
        .parse(PdfStatementParserTest.FIXTURE).transactions().stream()
        .map(t -> new MerchantQuery(t.rawMerchant(), t.sector())).distinct().toList();

    List<MerchantCategory> out = classifier.classify(merchants);

    assertThat(out).filteredOn(m -> m.category().equals("uncategorized"))
        .extracting(MerchantCategory::rawMerchant).containsExactly("וים קלאבס בע\"מ - הו\"");
    assertThat(out).extracting(MerchantCategory::confidence).allMatch(c -> c == 1.0 || c == 0.0);
  }

  @Test
  void classify_knownAndUnknownMerchants_haveStableAnswers() {
    List<MerchantCategory> out = classifier.classify(List.of(
        new MerchantQuery("הראל-ביטוח בריאות", "ביטוח"),
        new MerchantQuery("NETFLIX.COM 519479", null), new MerchantQuery("something new", null)));
    assertThat(out).extracting(MerchantCategory::category)
        .containsExactly("insurance", "entertainment", "uncategorized");
    assertThat(out.get(1).merchant()).isEqualTo("Netflix");
    assertThat(out.get(2).merchant()).isEqualTo("something new");
    assertThat(out.get(2).confidence()).isZero();
  }
}
