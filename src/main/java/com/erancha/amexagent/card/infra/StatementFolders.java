package com.erancha.amexagent.card.infra;

import com.erancha.amexagent.auth.UserIds;
import com.erancha.amexagent.card.api.Statement;
import com.erancha.amexagent.card.api.StatementSource;
import com.erancha.amexagent.card.api.StatementStore;
import com.erancha.amexagent.card.api.StoredStatement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One statement folder per person under a root directory, named by the person's Google account
 * id, so nobody's lookup or upload can touch another person's files. A folder is created by the
 * person's first upload; until then every lookup of theirs is empty. The root must exist at
 * startup.
 */
public class StatementFolders implements StatementSource, StatementStore {
  private final Path root;
  private final StatementParser parser;
  // Key: user id. Value: that person's folder, with its parse cache.
  private final Map<String, StatementFolder> folders = new HashMap<>();

  public StatementFolders(Path root, StatementParser parser) {
    if (!Files.isDirectory(root)) {
      throw new IllegalArgumentException("Statements folder is not a directory: " + root);
    }
    this.root = root;
    this.parser = parser;
  }

  public synchronized StatementFolder forUser(String userId) {
    return folders.computeIfAbsent(userId,
        id -> new StatementFolder(root.resolve(UserIds.folderName(id)), parser));
  }

  @Override
  public Optional<Statement> forMonth(String userId, YearMonth month) {
    return forUser(userId).forMonth(month);
  }

  @Override
  public List<StoredStatement> list(String userId) {
    return forUser(userId).list();
  }

  @Override
  public StoredStatement add(String userId, String fileName, byte[] content) {
    return forUser(userId).add(fileName, content);
  }

  @Override
  public void remove(String userId, String fileName) {
    forUser(userId).remove(fileName);
  }
}
