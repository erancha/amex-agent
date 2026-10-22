package com.erancha.amexagent.card.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies each person sees only their own folder, and that a user id never leaves the root. */
class StatementFoldersTest {
  private static final Path PDF = PdfStatementParserTest.FIXTURE;
  private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
  private final StatementParser parser = new PdfStatementParser(PdfStatementLayout.AMEX_ISRAEL);

  @Test
  void forMonth_twoPeopleWithTheSameMonth_seeOnlyTheirOwnFile(@TempDir Path root)
      throws Exception {
    StatementFolders folders = new StatementFolders(root, parser);
    folders.add("1000", "a.pdf", Files.readAllBytes(PDF));
    assertThat(root.resolve("1000/2026-10.pdf")).exists();
    assertThat(folders.forMonth("1000", OCTOBER)).isPresent();
    assertThat(folders.forMonth("2000", OCTOBER)).isEmpty();
    assertThat(folders.list("2000")).isEmpty();
    assertThat(root.resolve("2000")).as("a lookup creates no folder").doesNotExist();
    folders.add("2000", "b.pdf", Files.readAllBytes(PDF));
    assertThat(folders.list("1000")).hasSize(1);
    assertThat(folders.list("2000")).hasSize(1);
    folders.remove("2000", "2026-10.pdf");
    assertThat(folders.forMonth("1000", OCTOBER)).isPresent();
    assertThat(folders.forMonth("2000", OCTOBER)).isEmpty();
  }

  @Test
  void forUser_pathLikeId_isRefused(@TempDir Path root) {
    StatementFolders folders = new StatementFolders(root, parser);
    assertThatThrownBy(() -> folders.list("../1000")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> folders.forMonth("a/b", OCTOBER))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void root_missing_failsNamingIt() {
    assertThatThrownBy(() -> new StatementFolders(Path.of("/nonexistent/statements"), parser))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("/nonexistent/statements");
  }
}
