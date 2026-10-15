package com.erancha.amexagent.card.infra;

import com.erancha.amexagent.card.api.Statement;
import java.nio.file.Path;

/** Reads one downloaded statement file of a single format into a Statement. */
public interface StatementParser {
  Statement parse(Path file);
}
