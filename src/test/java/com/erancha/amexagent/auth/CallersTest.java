package com.erancha.amexagent.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies the secret selects exactly its caller. */
class CallersTest {
  private final Caller first = new Caller("first", "secret-one", "client-1");
  private final Caller second = new Caller("second", "secret-two", "client-2");
  private final Callers callers = new Callers(List.of(first, second));

  @Test
  void bySecret_exactSecret_findsItsCaller() {
    assertThat(callers.bySecret("secret-one")).contains(first);
    assertThat(callers.bySecret("secret-two")).contains(second);
  }

  @Test
  void bySecret_prefixSuffixOrOtherCase_findsNothing() {
    for (String wrong : new String[] {"secret-on", "secret-one ", "Secret-one", "", "nope"}) {
      assertThat(callers.bySecret(wrong)).as(wrong).isEmpty();
    }
  }
}
