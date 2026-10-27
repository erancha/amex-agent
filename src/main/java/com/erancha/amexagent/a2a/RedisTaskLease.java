package com.erancha.amexagent.a2a;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;

/**
 * Task leases in Redis, shared by every replica given the same URL: a2a:lease:<taskId> holds the
 * owner and expires with the lease, so a stopped replica's leases lapse on their own.
 */
public final class RedisTaskLease implements TaskLease, AutoCloseable {
  private static final String KEY_PREFIX = "a2a:lease:";
  // KEYS: the lease. ARGV: owner, time to live in ms. 1 when the owner held the lease and
  // renewed it.
  private static final String RENEW = """
      if redis.call('GET', KEYS[1]) == ARGV[1] then
        return redis.call('PEXPIRE', KEYS[1], ARGV[2])
      end
      return 0
      """;
  // KEYS: the lease. ARGV: owner.
  private static final String RELEASE = """
      if redis.call('GET', KEYS[1]) == ARGV[1] then
        return redis.call('DEL', KEYS[1])
      end
      return 0
      """;

  private final TasksRedis connection;
  private final RedisCommands<String, String> redis;
  private final long ttlMillis;

  /** Connects immediately, so an unreachable Redis fails startup rather than the first task. */
  public RedisTaskLease(String redisUrl, Duration ttl) {
    connection = new TasksRedis(redisUrl);
    redis = connection.commands();
    ttlMillis = ttl.toMillis();
  }

  @Override
  public boolean acquire(String taskId, String owner) {
    return "OK".equals(redis.set(KEY_PREFIX + taskId, owner, SetArgs.Builder.nx().px(ttlMillis)));
  }

  @Override
  public boolean renew(String taskId, String owner) {
    Long renewed = redis.eval(RENEW, ScriptOutputType.INTEGER, new String[] {KEY_PREFIX + taskId},
        owner, Long.toString(ttlMillis));
    return renewed == 1;
  }

  @Override
  public void release(String taskId, String owner) {
    redis.eval(RELEASE, ScriptOutputType.INTEGER, new String[] {KEY_PREFIX + taskId}, owner);
  }

  @Override
  public String ownerOf(String taskId) {
    return redis.get(KEY_PREFIX + taskId);
  }

  @Override
  public void close() {
    connection.close();
  }
}
