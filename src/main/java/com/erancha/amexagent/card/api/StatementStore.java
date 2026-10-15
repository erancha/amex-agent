package com.erancha.amexagent.card.api;

import java.util.List;

/** The statement files the agent reads, loading one more of them and removing one. */
public interface StatementStore {
  /** The statement files present now, sorted by file name. */
  List<StoredStatement> list();

  /**
   * Stores one statement under the card site's download name for the billing month its content
   * declares, after checking it is a readable statement and that no other file covers that month.
   * The file name is the uploader's and only identifies the file in refusals.
   *
   * @throws StatementRejectedException naming the problem when any check fails
   */
  StoredStatement add(String fileName, byte[] content);

  /**
   * Deletes one of the files list() returns, without reading it, so a file that blocks the folder
   * can be removed.
   *
   * @throws StatementRejectedException when no listed statement has that file name
   */
  void remove(String fileName);
}
