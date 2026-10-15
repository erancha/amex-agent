package com.erancha.amexagent.card.api;

import java.time.YearMonth;
import java.util.Optional;

/** Where statements come from; empty means no file covers that billing month right now. */
public interface StatementSource {
  Optional<Statement> forMonth(YearMonth month);
}
