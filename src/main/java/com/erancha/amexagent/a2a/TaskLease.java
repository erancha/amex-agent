package com.erancha.amexagent.a2a;

/**
 * Records which call is running a task, so no two calls run one task at once and a task left by
 * a stopped replica can be recognised. Only one owner holds a task's lease at a time; the lease
 * lapses unless its owner renews it within the lease's time to live.
 */
public interface TaskLease {
  /** Takes the lease when nobody holds it; false when another owner does. */
  boolean acquire(String taskId, String owner);

  /** Starts the time to live over; false when the owner no longer holds the lease. */
  boolean renew(String taskId, String owner);

  /** Gives the lease up; a lease another owner holds is left as it is. */
  void release(String taskId, String owner);

  /** The owner holding the lease, or null when nobody does. */
  String ownerOf(String taskId);
}
