package com.erancha.amexagent.card.infra;

import com.erancha.amexagent.card.api.Statement;
import com.erancha.amexagent.card.api.StatementRejectedException;
import com.erancha.amexagent.card.api.StatementSource;
import com.erancha.amexagent.card.api.StatementStore;
import com.erancha.amexagent.card.api.StoredStatement;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.DateTimeException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Indexes the statement files in one directory by billing month. A file whose extension (any case)
 * has a parser is a statement, except Excel's ~$ lock files; a file that fails to parse fails the
 * lookup naming it. Every lookup rescans the directory so a file dropped in while a task waits for
 * input is found on the next request; a file is re-parsed only when its modification time changes.
 *
 * Loading a statement stages the upload under a name no parser claims, parses it, and renames it
 * to the download name of the billing month its content declares, whatever the upload was called,
 * only once it is known to be a readable statement for a month no other file covers, so a lookup
 * never sees a half-written or unreadable upload. Removing accepts only a name list() returns, so
 * the caller's name never reaches outside the folder or a file that is not a statement.
 */
public class StatementFolder implements StatementSource, StatementStore {
  private static final Logger log = LoggerFactory.getLogger(StatementFolder.class);
  // The card site's download names: <YYYY>-<MM>.pdf for a statement, <last4>_<MM>_<YYYY>.xlsx for
  // an export. Either shape is checked against the content whatever its extension.
  private static final List<Pattern> DOWNLOAD_NAMES = List.of(
      Pattern.compile("^(?<year>\\d{4})-(?<month>\\d{2})\\.[^.]+$"),
      Pattern.compile("^\\d{4}_(?<month>\\d{2})_(?<year>\\d{4})\\.[^.]+$"));
  private static final String PDF = ".pdf";
  private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);
  // Excel holds an open workbook with a hidden <~$name> owner file beside it.
  private static final String LOCK_FILE_PREFIX = "~$";

  private record Parsed(FileTime modified, Statement statement) {}

  private record Indexed(Path file, Statement statement) {}

  private final Path dir;
  // Key: lower-case file extension with its dot. Value: the parser for that format.
  private final Map<String, StatementParser> parsers;
  // Key: file path. Value: the parse of that file at the recorded modification time.
  private final Map<Path, Parsed> cache = new HashMap<>();

  public StatementFolder(Path dir, Map<String, StatementParser> parsers) {
    if (!Files.isDirectory(dir)) {
      throw new IllegalArgumentException("Statements folder is not a directory: " + dir);
    }
    this.dir = dir;
    this.parsers = Map.copyOf(parsers);
  }

  @Override
  public synchronized Optional<Statement> forMonth(YearMonth month) {
    return Optional.ofNullable(index().get(month)).map(Indexed::statement);
  }

  @Override
  public synchronized List<StoredStatement> list() {
    List<StoredStatement> stored = new ArrayList<>();
    try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, this::isStatement)) {
      for (Path file : files) {
        stored.add(stored(file));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    stored.sort(Comparator.comparing(StoredStatement::fileName));
    return stored;
  }

  @Override
  public synchronized StoredStatement add(String fileName, byte[] content) {
    if (!startsWith(content, PDF_SIGNATURE)) {
      throw new StatementRejectedException(fileName + " is not a PDF file");
    }
    Path staged = null;
    try {
      staged = Files.createTempFile(dir, ".upload-", ".part");
      Files.write(staged, content);
      YearMonth month = uploadedStatement(fileName, staged).billingMonth();
      Path target = dir.resolve(month + PDF);
      Indexed covering = indexForUpload().get(month);
      if (covering != null) {
        throw new StatementRejectedException(
            covering.file().getFileName() + " already covers " + month + "; remove it first");
      }
      Files.move(staged, target);
      log.info("Loaded {} as billing month {}", target.getFileName(), month);
      return stored(target);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot load " + fileName + " into " + dir + ": "
          + e.getMessage(), e);
    } finally {
      deleteStaged(staged);
    }
  }

  @Override
  public synchronized void remove(String fileName) {
    if (list().stream().noneMatch(s -> s.fileName().equals(fileName))) {
      throw new StatementRejectedException(fileName + " is not a loaded statement");
    }
    Path file = dir.resolve(fileName);
    try {
      Files.delete(file);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot remove " + fileName + " from " + dir + ": "
          + e.getMessage(), e);
    }
    cache.remove(file);
    log.info("Removed {}", fileName);
  }

  private Map<YearMonth, Indexed> index() {
    Map<YearMonth, Indexed> byMonth = new HashMap<>();
    try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, this::isStatement)) {
      for (Path file : files) {
        Statement statement = parsed(file).statement();
        Indexed other = byMonth.put(statement.billingMonth(), new Indexed(file, statement));
        if (other != null) {
          throw new StatementFormatException("Two files cover " + statement.billingMonth()
              + ": " + other.file().getFileName() + " and " + file.getFileName() + "; keep one");
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    cache.keySet().removeIf(path -> !Files.exists(path));
    return byMonth;
  }

  /** An unreadable file already in the folder blocks every lookup, so it blocks loading too. */
  private Map<YearMonth, Indexed> indexForUpload() {
    try {
      return index();
    } catch (StatementFormatException e) {
      throw new StatementRejectedException(
          "The statements folder must be fixed first: " + e.getMessage(), e);
    }
  }

  private Statement uploadedStatement(String fileName, Path staged) {
    try {
      return parsers.get(PDF).parse(staged);
    } catch (RuntimeException e) {
      throw new StatementRejectedException(
          fileName + " is not a readable statement: " + e.getMessage(), e);
    }
  }

  private static boolean startsWith(byte[] content, byte[] prefix) {
    return content.length >= prefix.length
        && Arrays.equals(content, 0, prefix.length, prefix, 0, prefix.length);
  }

  private static StoredStatement stored(Path file) throws IOException {
    return new StoredStatement(file.getFileName().toString(), Files.size(file),
        Files.getLastModifiedTime(file).toInstant());
  }

  private static void deleteStaged(@Nullable Path staged) {
    if (staged == null) {
      return;
    }
    try {
      Files.deleteIfExists(staged);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot remove the staged upload " + staged, e);
    }
  }

  private boolean isStatement(Path file) {
    return parserFor(file) != null && !file.getFileName().toString().startsWith(LOCK_FILE_PREFIX);
  }

  private @Nullable StatementParser parserFor(Path file) {
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    int dot = name.lastIndexOf('.');
    return dot < 0 ? null : parsers.get(name.substring(dot));
  }

  private Parsed parsed(Path file) throws IOException {
    FileTime modified = Files.getLastModifiedTime(file);
    Parsed cached = cache.get(file);
    if (cached != null && cached.modified().equals(modified)) {
      return cached;
    }
    Statement statement;
    try {
      statement = parserFor(file).parse(file);
    } catch (StatementFormatException e) {
      throw e;
    } catch (RuntimeException e) {
      // POI, PDFBox and HebrewMonths fail without naming the file; the operator needs to know it.
      throw new StatementFormatException(file.getFileName() + ": " + e.getMessage(), e);
    }
    checkNameAgreesWithContent(file, statement.billingMonth());
    log.info("Indexed {} as billing month {} ({} transactions)", file.getFileName(),
        statement.billingMonth(), statement.transactions().size());
    Parsed fresh = new Parsed(modified, statement);
    cache.put(file, fresh);
    return fresh;
  }

  private static void checkNameAgreesWithContent(Path file, YearMonth content) {
    Matcher m = DOWNLOAD_NAMES.stream()
        .map(p -> p.matcher(file.getFileName().toString()))
        .filter(Matcher::matches)
        .findFirst()
        .orElse(null);
    if (m == null) {
      return;
    }
    YearMonth named;
    try {
      named = YearMonth.of(Integer.parseInt(m.group("year")), Integer.parseInt(m.group("month")));
    } catch (DateTimeException e) {
      throw new StatementFormatException(
          file.getFileName() + " does not hold a valid month in its name: " + e.getMessage(), e);
    }
    if (!named.equals(content)) {
      throw new StatementFormatException(file.getFileName() + " is named for " + named
          + " but its content is the " + content + " statement");
    }
  }
}
