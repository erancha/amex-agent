package com.erancha.amexagent.card.application;

import java.time.DateTimeException;
import java.time.YearMonth;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;

/** What a client asked for: a billing month, or to continue a task that waited for a statement. */
public sealed interface MonthRequest permits MonthRequest.Fetch, MonthRequest.Continue {
  record Fetch(YearMonth month) implements MonthRequest {}

  record Continue() implements MonthRequest {}

  Pattern MONTH = Pattern.compile("\\b(\\d{4})-(\\d{2})\\b");

  static MonthRequest from(Message message) {
    for (Part<?> part : message.parts()) {
      if (part instanceof DataPart data && data.data() instanceof Map<?, ?> map) {
        if ("continue".equals(map.get("action"))) {
          return new Continue();
        }
        if (map.containsKey("month")) {
          return new Fetch(month(String.valueOf(map.get("month"))));
        }
        throw new InvalidParamsError(
            "Data part must be {\"month\": \"YYYY-MM\"} or {\"action\": \"continue\"}");
      }
      if (part instanceof TextPart text) {
        Matcher m = MONTH.matcher(text.text());
        if (m.find()) {
          return new Fetch(month(m.group()));
        }
      }
    }
    throw new InvalidParamsError("Send a data part {\"month\": \"YYYY-MM\"}");
  }

  private static YearMonth month(String value) {
    Matcher m = MONTH.matcher(value);
    if (!m.matches()) {
      throw invalidMonth(value);
    }
    try {
      return YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
    } catch (DateTimeException e) {
      throw invalidMonth(value);
    }
  }

  private static InvalidParamsError invalidMonth(String value) {
    return new InvalidParamsError("month must be YYYY-MM, got '" + value + "'");
  }
}
