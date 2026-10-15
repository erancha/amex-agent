package com.erancha.amexagent.card.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Verifies the published category list and the lenient mapping from model output to it. */
class CategoryTest {
  @Test
  void wireValues_matchPublishedTaxonomy() {
    assertThat(Category.wireValues()).containsExactly("groceries", "dining", "transport",
        "utilities", "housing", "health", "insurance", "education", "shopping", "entertainment",
        "travel", "gifts", "other", "uncategorized");
  }

  @Test
  void fromModelValue_unknownOrBlank_isUncategorized() {
    assertThat(Category.fromModelValue("Pharmacy")).isEqualTo(Category.UNCATEGORIZED);
    assertThat(Category.fromModelValue("")).isEqualTo(Category.UNCATEGORIZED);
    assertThat(Category.fromModelValue(" Health ")).isEqualTo(Category.HEALTH);
  }

  @Test
  void fromModelValue_omittedLabel_isUncategorized() {
    assertThat(Category.fromModelValue(null)).isEqualTo(Category.UNCATEGORIZED);
  }

  @Test
  void fromModelValue_wireValue_roundTrips() {
    assertThat(Category.fromModelValue(Category.OTHER.wireValue())).isEqualTo(Category.OTHER);
    assertThat(Category.fromModelValue("uncategorized")).isEqualTo(Category.UNCATEGORIZED);
  }
}
