package com.erancha.amexagent.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erancha.amexagent.auth.Caller;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies misconfiguration fails at construction, naming what the operator must fix. */
class A2APropertiesTest {
  private static final Caller CALLER = new Caller("test-caller", "s1", "client-1");

  @Test
  void a2a_requiredWithoutCallers_namesTheCallersFile() {
    assertThatThrownBy(() -> new A2AProperties.A2a(new A2AProperties.Auth(true), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("APP_A2A_CALLERS_FILE").hasMessageContaining("allowed_callers");
  }

  @Test
  void a2a_notRequiredWithoutCallers_isValid() {
    assertThat(new A2AProperties.A2a(new A2AProperties.Auth(false), List.of()).allowedCallers())
        .isEmpty();
  }

  @Test
  void a2a_twoCallersWithOneSecret_isRefused() {
    Caller other = new Caller("other", "s1", "client-2");
    assertThatThrownBy(() -> new A2AProperties.A2a(new A2AProperties.Auth(true),
        List.of(CALLER, other)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("shared_secret");
  }

  @Test
  void caller_blankField_isRefusedNamingIt() {
    assertThatThrownBy(() -> new Caller("x", " ", "c")).hasMessageContaining("shared_secret");
    assertThatThrownBy(() -> new Caller("x", "s", null)).hasMessageContaining("client_id");
    assertThatThrownBy(() -> new Caller("", "s", "c")).hasMessageContaining("name");
  }
}
