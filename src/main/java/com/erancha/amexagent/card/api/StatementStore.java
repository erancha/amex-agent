package com.erancha.amexagent.card.api;

import java.util.List;

/**
 * One person's statement files, the ones the agent reads for them: listing them, loading one more
 * and removing one. The user id is the person's Google account id.
 */
public interface StatementStore {
  /** The person's statement files present now, sorted by file name. */
  List<StoredStatement> list(String userId);

  /**
   * Stores one statement under the card site's download name for the billing month its content
   * declares, after checking it is a readable statement and that no other file of the person
   * covers that month. The file name is the uploader's and only identifies the file in refusals.
   *
   * @throws StatementRejectedException naming the problem when any check fails
   */
  StoredStatement add(String userId, String fileName, byte[] content);

  /**
   * Deletes one of the files list() returns, without reading it, so a file that blocks the
   * person's folder can be removed.
   *
   * @throws StatementRejectedException when no listed statement has that file name
   */
  void remove(String userId, String fileName);
}
