package com.erancha.amexagent.a2a;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

/** One connection to the Redis that APP_TASKS_REDIS_URL names, where the replicas share tasks. */
final class TasksRedis implements AutoCloseable {
  private final RedisClient client;
  private final StatefulRedisConnection<String, String> connection;

  /** Connects immediately, so an unreachable Redis fails startup rather than the first task. */
  TasksRedis(String redisUrl) {
    client = RedisClient.create(redisUrl);
    try {
      connection = client.connect();
    } catch (RedisException e) {
      client.shutdown();
      throw new IllegalStateException("Redis at APP_TASKS_REDIS_URL cannot be reached: "
          + e.getMessage(), e);
    }
  }

  /** Thread-safe: every caller on this connection may use the same commands. */
  RedisCommands<String, String> commands() {
    return connection.sync();
  }

  @Override
  public void close() {
    connection.close();
    client.shutdown();
  }
}
