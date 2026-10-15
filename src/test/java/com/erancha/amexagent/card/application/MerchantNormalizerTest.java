package com.erancha.amexagent.card.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Verifies the cache key collapses branch numbers, company-form suffixes and spacing. */
class MerchantNormalizerTest {
  @Test
  void key_stripsBranchNumbersCompanyFormsAndCase() {
    assertThat(MerchantNormalizer.key("סופר פארם בעמ 123")).isEqualTo("סופר פארם");
    assertThat(MerchantNormalizer.key("  AMAZON   MKTPLACE ")).isEqualTo("amazon mktplace");
    assertThat(MerchantNormalizer.key("שופרסל דיל רמת גן")).isEqualTo("שופרסל דיל רמת גן");
    assertThat(MerchantNormalizer.key("פז יהוד 0421")).isEqualTo("פז יהוד");
  }

  @Test
  void key_treatsBothGershayimSpellingsAlike() {
    assertThat(MerchantNormalizer.key("סופר פארם בע\u05F4מ")).isEqualTo("סופר פארם");
    assertThat(MerchantNormalizer.key("סופר פארם בע\"מ")).isEqualTo("סופר פארם");
  }
}
