package com.erancha.amexagent.card.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

/** Verifies the only accepted request shapes: {"month": "YYYY-MM"}, a YYYY-MM text, or continue. */
class MonthRequestTest {
  private static Message message(Part<?>... parts) {
    return Message.builder().role(Message.Role.ROLE_USER).messageId("m1")
        .parts(List.of(parts)).build();
  }

  @Test
  void from_dataPartMonth_isFetch() {
    MonthRequest r = MonthRequest.from(message(new DataPart(Map.of("month", "2026-10"))));
    assertThat(r).isEqualTo(new MonthRequest.Fetch(YearMonth.of(2026, 10)));
  }

  @Test
  void from_textPartMonth_isFetch() {
    assertThat(MonthRequest.from(message(new TextPart("transactions for 2026-10 please"))))
        .isEqualTo(new MonthRequest.Fetch(YearMonth.of(2026, 10)));
  }

  @Test
  void from_continueAction_isContinue() {
    assertThat(MonthRequest.from(message(new DataPart(Map.of("action", "continue")))))
        .isEqualTo(new MonthRequest.Continue());
  }

  @Test
  void from_malformedMonths_rejected() {
    for (String bad : List.of("2026-9", "09/2026", "2026-13", "202610", "")) {
      assertThatThrownBy(() -> MonthRequest.from(message(new DataPart(Map.of("month", bad)))))
          .as(bad).isInstanceOf(InvalidParamsError.class);
    }
  }

  @Test
  void from_noUsablePart_rejected() {
    assertThatThrownBy(() -> MonthRequest.from(message(new TextPart("hello"))))
        .isInstanceOf(InvalidParamsError.class);
    assertThatThrownBy(() -> MonthRequest.from(message(new DataPart(Map.of("action", "skip")))))
        .isInstanceOf(InvalidParamsError.class);
  }
}
