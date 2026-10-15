package com.erancha.amexagent.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Verifies misconfiguration fails at construction, naming the offending variable. */
class A2APropertiesTest {
  @Test
  void auth_requiredWithBlankToken_namesVariable() {
    assertThatThrownBy(() -> new A2AProperties.Auth(true, " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("APP_A2A_TOKEN");
    assertThatThrownBy(() -> new A2AProperties.Auth(true, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("APP_A2A_TOKEN");
  }

  @Test
  void auth_notRequiredWithBlankToken_isValid() {
    assertThat(new A2AProperties.Auth(false, "")).isNotNull();
  }
}
