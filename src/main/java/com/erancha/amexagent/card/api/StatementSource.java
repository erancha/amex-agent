package com.erancha.amexagent.card.api;

import java.time.YearMonth;
import java.util.Optional;

/**
 * Where a person's statements come from; empty means no file of theirs covers that billing month
 * right now. The user id is the person's Google account id.
 */
public interface StatementSource {
  Optional<Statement> forMonth(String userId, YearMonth month);
}
