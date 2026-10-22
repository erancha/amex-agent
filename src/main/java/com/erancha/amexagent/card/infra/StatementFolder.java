package com.erancha.amexagent.card.infra;

import com.erancha.amexagent.card.api.Statement;
import com.erancha.amexagent.card.api.StatementRejectedException;
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
 * Indexes the statement files in one directory by billing month. A file with the .pdf extension
 * (any case) is a statement; a file that fails to parse fails the lookup naming it. Every lookup
 * rescans the directory so a file dropped in while a task waits for input is found on the next
 * request; a file is re-parsed only when its modification time changes.
 * The directory is one person's and may not exist yet: it holds nothing until the first upload
 * creates it.
 *
 * Loading a statement stages the upload under a name no lookup treats as a statement, parses it,
 * and renames it to the download name of the billing month its content declares, whatever the
 * upload was called, only once it is known to be a readable statement for a month no other file
 * covers, so a lookup never sees a half-written or unreadable upload. Removing accepts only a name
 * list() returns, so the caller's name never reaches outside the folder or a file that is not a
 * statement.
 */
public class StatementFolder {
  private static final Logger log = LoggerFactory.getLogger(StatementFolder.class);
  // The card site's download name, <YYYY>-<MM>.pdf, is checked against the content.
  private static final Pattern DOWNLOAD_NAME =
      Pattern.compile("^(?<year>\\d{4})-(?<month>\\d{2})\\.[^.]+$");
  private static final String PDF = ".pdf";
  private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);

  private record Parsed(FileTime modified, Statement statement) {}

  private record Indexed(Path file, Statement statement) {}

  private final Path dir;
  private final StatementParser parser;
  // Key: file path. Value: the parse of that file at the recorded modification time.
  private final Map<Path, Parsed> cache = new HashMap<>();

  public StatementFolder(Path dir, StatementParser parser) {
    if (Files.exists(dir) && !Files.isDirectory(dir)) {
      throw new IllegalArgumentException("Statements folder is not a directory: " + dir);
    }
    this.dir = dir;
    this.parser = parser;
  }

  public synchronized Optional<Statement> forMonth(YearMonth month) {
    return Optional.ofNullable(index().get(month)).map(Indexed::statement);
  }

  public synchronized List<StoredStatement> list() {
    List<StoredStatement> stored = new ArrayList<>();
    if (!Files.isDirectory(dir)) {
      return stored;
    }
    try (DirectoryStream<Path> files =
        Files.newDirectoryStream(dir, StatementFolder::isStatement)) {
      for (Path file : files) {
        stored.add(stored(file));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    stored.sort(Comparator.comparing(StoredStatement::fileName));
    return stored;
  }

  public synchronized StoredStatement add(String fileName, byte[] content) {
    if (!startsWith(content, PDF_SIGNATURE)) {
      throw new StatementRejectedException(fileName + " is not a PDF file");
    }
    Path staged = null;
    try {
      Files.createDirectories(dir);
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
    if (!Files.isDirectory(dir)) {
      return byMonth;
    }
    try (DirectoryStream<Path> files =
        Files.newDirectoryStream(dir, StatementFolder::isStatement)) {
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
      return parser.parse(staged);
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

  private static boolean isStatement(Path file) {
    return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(PDF);
  }

  private Parsed parsed(Path file) throws IOException {
    FileTime modified = Files.getLastModifiedTime(file);
    Parsed cached = cache.get(file);
    if (cached != null && cached.modified().equals(modified)) {
      return cached;
    }
    Statement statement;
    try {
      statement = parser.parse(file);
    } catch (StatementFormatException e) {
      throw e;
    } catch (RuntimeException e) {
      // PDFBox fails without naming the file; the operator needs to know it.
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
    Matcher m = DOWNLOAD_NAME.matcher(file.getFileName().toString());
    if (!m.matches()) {
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
