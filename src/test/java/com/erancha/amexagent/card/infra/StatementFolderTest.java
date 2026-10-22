package com.erancha.amexagent.card.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erancha.amexagent.card.api.StatementRejectedException;
import com.erancha.amexagent.card.api.StoredStatement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies month lookup over a folder of statement files, rescans, which files count as
 * statements, and the name-vs-content, duplicate-month and unreadable-file guards; then listing
 * the folder, loading an upload into it and removing a listed file, with every refusal leaving the
 * folder untouched.
 */
class StatementFolderTest {
  private static final Path PDF = PdfStatementParserTest.FIXTURE;
  private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
  private final StatementParser parser = new PdfStatementParser(PdfStatementLayout.AMEX_ISRAEL);

  @Test
  void forMonth_coveredMonth_returnsStatement(@TempDir Path dir) throws Exception {
    Files.copy(PDF, dir.resolve("2026-10.pdf"));
    StatementFolder folder = new StatementFolder(dir, parser);
    assertThat(folder.forMonth(OCTOBER)).get()
        .satisfies(s -> assertThat(s.transactions()).hasSize(24));
    assertThat(folder.forMonth(YearMonth.of(2026, 9))).isEmpty();
  }

  @Test
  void forMonth_fileAddedAfterFirstScan_isFound(@TempDir Path dir) throws Exception {
    StatementFolder folder = new StatementFolder(dir, parser);
    assertThat(folder.forMonth(OCTOBER)).isEmpty();
    Files.copy(PDF, dir.resolve("2026-10.pdf"));
    assertThat(folder.forMonth(OCTOBER)).isPresent();
  }

  @Test
  void scan_downloadNameAndContentDisagree_throws(@TempDir Path dir) throws Exception {
    Files.copy(PDF, dir.resolve("2026-09.pdf"));
    StatementFolder folder = new StatementFolder(dir, parser);
    assertThatThrownBy(() -> folder.forMonth(OCTOBER))
        .isInstanceOf(StatementFormatException.class)
        .hasMessageContaining("2026-09.pdf")
        .hasMessageContaining("2026-09")
        .hasMessageContaining("2026-10");
  }

  @Test
  void scan_informalName_indexedByContent(@TempDir Path dir) throws Exception {
    Files.copy(PDF, dir.resolve("statement (3).pdf"));
    assertThat(new StatementFolder(dir, parser).forMonth(OCTOBER)).isPresent();
  }

  @Test
  void scan_missingDirectory_isEmptyUntilTheFirstUploadCreatesIt(@TempDir Path dir)
      throws Exception {
    Path mine = dir.resolve("1000");
    StatementFolder folder = new StatementFolder(mine, parser);
    assertThat(folder.forMonth(OCTOBER)).isEmpty();
    assertThat(folder.list()).isEmpty();
    assertThat(mine).doesNotExist();
    folder.add("statement.pdf", Files.readAllBytes(PDF));
    assertThat(mine.resolve("2026-10.pdf")).exists();
    assertThat(folder.forMonth(OCTOBER)).isPresent();
  }

  @Test
  void scan_pathThatIsAFile_throwsNamingIt(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("statements");
    Files.writeString(file, "x");
    assertThatThrownBy(() -> new StatementFolder(file, parser))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("statements");
  }

  @Test
  void scan_twoFilesForOneMonth_throwsNamingBoth(@TempDir Path dir) throws Exception {
    Files.copy(PDF, dir.resolve("2026-10.pdf"));
    Files.copy(PDF, dir.resolve("statement (3).pdf"));
    StatementFolder folder = new StatementFolder(dir, parser);
    assertThatThrownBy(() -> folder.forMonth(OCTOBER))
        .isInstanceOf(StatementFormatException.class)
        .hasMessageContaining("2026-10.pdf")
        .hasMessageContaining("statement (3).pdf")
        .hasMessageContaining("Two files cover 2026-10");
  }

  @Test
  void scan_nameWithImpossibleMonth_throws(@TempDir Path dir) throws Exception {
    Files.copy(PDF, dir.resolve("2026-13.pdf"));
    StatementFolder folder = new StatementFolder(dir, parser);
    assertThatThrownBy(() -> folder.forMonth(OCTOBER))
        .isInstanceOf(StatementFormatException.class)
        .hasMessageContaining("2026-13.pdf");
  }

  @Test
  void scan_otherExtensions_areIgnored(@TempDir Path dir) throws Exception {
    Files.copy(PDF, dir.resolve("2026-10.pdf"));
    Files.writeString(dir.resolve("2026-09.xlsx"), "export");
    Files.writeString(dir.resolve("notes.txt"), "not a statement");
    assertThat(new StatementFolder(dir, parser).forMonth(OCTOBER)).isPresent();
  }

  @Test
  void scan_upperCaseExtension_isIndexed(@TempDir Path dir) throws Exception {
    Files.copy(PDF, dir.resolve("2026-10.PDF"));
    assertThat(new StatementFolder(dir, parser).forMonth(OCTOBER)).isPresent();
  }

  @Test
  void scan_fileThatIsNotAPdf_throwsNamingIt(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("bad.pdf"), "not a pdf");
    StatementFolder folder = new StatementFolder(dir, parser);
    assertThatThrownBy(() -> folder.forMonth(OCTOBER))
        .isInstanceOf(StatementFormatException.class)
        .hasMessageStartingWith("bad.pdf: ")
        .hasCauseInstanceOf(RuntimeException.class);
  }

  @Test
  void list_statementFilesOnly_sortedByName(@TempDir Path dir) throws Exception {
    Files.copy(PDF, dir.resolve("2026-10.pdf"));
    Files.writeString(dir.resolve("2026-09.xlsx"), "export");
    Files.writeString(dir.resolve("notes.txt"), "not a statement");
    assertThat(new StatementFolder(dir, parser).list())
        .extracting(StoredStatement::fileName)
        .containsExactly("2026-10.pdf");
  }

  @Test
  void add_anyName_isStoredUnderItsContentsDownloadNameAndFound(@TempDir Path dir)
      throws Exception {
    StatementFolder folder = new StatementFolder(dir, parser);
    StoredStatement stored = folder.add("Statement (3).PDF", Files.readAllBytes(PDF));
    assertThat(stored.fileName()).isEqualTo("2026-10.pdf");
    assertThat(stored.sizeBytes()).isEqualTo(Files.size(PDF));
    assertThat(folder.forMonth(OCTOBER)).isPresent();
    assertThat(dir.toFile().list()).containsExactly("2026-10.pdf");
  }

  @Test
  void add_pathLikeOrMisleadingNames_neverDecideWhereTheFileLands(@TempDir Path dir)
      throws Exception {
    byte[] pdf = Files.readAllBytes(PDF);
    String[] names = {"../x.pdf", "..\\x.pdf", "/tmp/x.pdf", "sub/x.pdf", "2026-09.pdf", ""};
    for (String name : names) {
      Path folderDir = Files.createDirectory(dir.resolve("f" + name.hashCode()));
      assertThat(new StatementFolder(folderDir, parser).add(name, pdf).fileName()).as(name)
          .isEqualTo("2026-10.pdf");
      assertThat(folderDir.toFile().list()).as(name).containsExactly("2026-10.pdf");
    }
    assertThat(dir.getParent().resolve("x.pdf")).doesNotExist();
  }

  @Test
  void add_contentThatIsNotAPdf_isRejected(@TempDir Path dir) throws Exception {
    StatementFolder folder = new StatementFolder(dir, parser);
    for (byte[] content : new byte[][] {new byte[0], "<html>".getBytes(StandardCharsets.UTF_8)}) {
      assertThatThrownBy(() -> folder.add("2026-10.pdf", content))
          .isInstanceOf(StatementRejectedException.class)
          .hasMessage("2026-10.pdf is not a PDF file");
    }
    assertThat(dir).isEmptyDirectory();
  }

  @Test
  void add_pdfThatIsNotAStatement_isRejectedAndNothingStays(@TempDir Path dir) throws Exception {
    StatementFolder folder = new StatementFolder(dir, parser);
    byte[] broken = "%PDF-1.7 truncated".getBytes(StandardCharsets.US_ASCII);
    assertThatThrownBy(() -> folder.add("2026-10.pdf", broken))
        .isInstanceOf(StatementRejectedException.class)
        .hasMessageStartingWith("2026-10.pdf is not a readable statement: ");
    assertThat(dir).isEmptyDirectory();
  }

  @Test
  void add_monthAlreadyCovered_isRejectedNamingTheFile(@TempDir Path dir) throws Exception {
    Files.copy(PDF, dir.resolve("statement (3).pdf"));
    StatementFolder folder = new StatementFolder(dir, parser);
    assertThatThrownBy(() -> folder.add("2026-10.pdf", Files.readAllBytes(PDF)))
        .isInstanceOf(StatementRejectedException.class)
        .hasMessage("statement (3).pdf already covers 2026-10; remove it first");
    assertThat(folder.list()).extracting(StoredStatement::fileName)
        .containsExactly("statement (3).pdf");
  }

  @Test
  void add_unreadableFileInFolder_isRejectedNamingIt(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("bad.pdf"), "not a pdf");
    StatementFolder folder = new StatementFolder(dir, parser);
    assertThatThrownBy(() -> folder.add("2026-10.pdf", Files.readAllBytes(PDF)))
        .isInstanceOf(StatementRejectedException.class)
        .hasMessageStartingWith("The statements folder must be fixed first: bad.pdf: ");
    assertThat(dir.resolve("2026-10.pdf")).doesNotExist();
  }

  @Test
  void remove_fileThatBlocksTheFolder_isDeletedSoTheMonthCanBeLoaded(@TempDir Path dir)
      throws Exception {
    Files.copy(PDF, dir.resolve("2026-09.pdf"));
    StatementFolder folder = new StatementFolder(dir, parser);
    folder.remove("2026-09.pdf");
    assertThat(dir).isEmptyDirectory();
    assertThat(folder.add("2026-10.pdf", Files.readAllBytes(PDF)).fileName())
        .isEqualTo("2026-10.pdf");
  }

  @Test
  void remove_nameThatIsNotAListedStatement_isRejected(@TempDir Path dir) throws Exception {
    Path folderDir = Files.createDirectory(dir.resolve("statements"));
    Files.copy(PDF, folderDir.resolve("2026-10.pdf"));
    Files.writeString(folderDir.resolve("notes.txt"), "not a statement");
    Files.copy(PDF, dir.resolve("outside.pdf"));
    StatementFolder folder = new StatementFolder(folderDir, parser);
    for (String name : new String[] {"2026-09.pdf", "notes.txt", "../outside.pdf", ""}) {
      assertThatThrownBy(() -> folder.remove(name)).as(name)
          .isInstanceOf(StatementRejectedException.class)
          .hasMessage(name + " is not a loaded statement");
    }
    assertThat(folderDir.toFile().list()).containsExactlyInAnyOrder("2026-10.pdf", "notes.txt");
    assertThat(dir.resolve("outside.pdf")).exists();
  }
}
