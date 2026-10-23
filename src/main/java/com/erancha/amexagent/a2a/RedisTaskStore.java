package com.erancha.amexagent.a2a;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.List;
import org.a2aproject.sdk.grpc.utils.ProtoUtils.FromProto;
import org.a2aproject.sdk.grpc.utils.ProtoUtils.ToProto;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.TaskPersistenceException;
import org.a2aproject.sdk.server.tasks.TaskSerializationException;
import org.a2aproject.sdk.server.tasks.TaskStateProvider;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Task;

/**
 * Keeps A2A tasks in Redis, so every replica sees every task: whichever replica receives a
 * continue finds the task another replica paused, and a paused task survives a restart of the
 * process that paused it.
 *
 * Each task is one key holding the task's wire JSON, which expires a fixed time after the last
 * save. One set holds the task ids for listing; the next listing removes the id of a task whose
 * key has expired. Listing reuses the SDK's own filtering, ordering and paging: it loads the
 * stored tasks into a temporary InMemoryTaskStore and lets that answer. This reads every task,
 * which is fine for thousands of tasks but not for millions.
 *
 * The last save of a task wins, so two replicas running one task at once overwrite each other's
 * progress; docs/architecture.md describes when that can happen.
 */
public final class RedisTaskStore implements TaskStore, TaskStateProvider, AutoCloseable {
  private static final String KEY_PREFIX = "a2a:task:";
  private static final String INDEX = "a2a:tasks";

  private final RedisClient client;
  private final StatefulRedisConnection<String, String> connection;
  private final RedisCommands<String, String> redis;
  private final SetArgs expiry;

  /** Connects immediately, so an unreachable Redis fails startup rather than the first task. */
  public RedisTaskStore(String redisUrl, Duration ttl) {
    client = RedisClient.create(redisUrl);
    try {
      connection = client.connect();
    } catch (RedisException e) {
      client.shutdown();
      throw new IllegalStateException("Redis at APP_TASKS_REDIS_URL cannot be reached: "
          + e.getMessage(), e);
    }
    redis = connection.sync();
    expiry = SetArgs.Builder.px(ttl.toMillis());
  }

  @Override
  public void save(Task task, boolean isReplicated) {
    String json = toJson(task);
    try {
      // The id goes into the index before the task is written, so a listing never misses a stored
      // task. If the write then fails, the next listing skips the id and removes it.
      redis.sadd(INDEX, task.id());
      redis.set(KEY_PREFIX + task.id(), json, expiry);
    } catch (RedisException e) {
      throw new TaskPersistenceException(task.id(), "Redis refused the task: " + e.getMessage(), e);
    }
  }

  @Override
  public Task get(String taskId) {
    String json;
    try {
      json = redis.get(KEY_PREFIX + taskId);
    } catch (RedisException e) {
      throw new TaskPersistenceException(taskId, "Redis could not be read: " + e.getMessage(), e);
    }
    return json == null ? null : fromJson(taskId, json);
  }

  @Override
  public void delete(String taskId) {
    try {
      redis.del(KEY_PREFIX + taskId);
      redis.srem(INDEX, taskId);
    } catch (RedisException e) {
      throw new TaskPersistenceException(taskId, "Redis could not delete the task: "
          + e.getMessage(), e);
    }
  }

  @Override
  public ListTasksResult list(ListTasksParams params, ServerCallContext context) {
    InMemoryTaskStore snapshot = new InMemoryTaskStore();
    try {
      String[] ids = redis.smembers(INDEX).toArray(String[]::new);
      if (ids.length > 0) {
        List<KeyValue<String, String>> stored = redis.mget(keys(ids));
        for (int i = 0; i < ids.length; i++) {
          KeyValue<String, String> entry = stored.get(i);
          if (entry.hasValue()) {
            snapshot.save(fromJson(ids[i], entry.getValue()), false);
          } else {
            redis.srem(INDEX, ids[i]);
          }
        }
      }
    } catch (RedisException e) {
      throw new TaskPersistenceException("Redis could not list tasks: " + e.getMessage(), e);
    }
    return snapshot.list(params, context);
  }

  @Override
  public boolean isTaskActive(String taskId) {
    Task task = get(taskId);
    return task != null && !task.status().state().isFinal();
  }

  @Override
  public boolean isTaskFinalized(String taskId) {
    Task task = get(taskId);
    return task != null && task.status().state().isFinal();
  }

  @Override
  public void close() {
    connection.close();
    client.shutdown();
  }

  private static String[] keys(String[] ids) {
    String[] keys = new String[ids.length];
    for (int i = 0; i < ids.length; i++) {
      keys[i] = KEY_PREFIX + ids[i];
    }
    return keys;
  }

  private static String toJson(Task task) {
    try {
      return JsonFormat.printer().omittingInsignificantWhitespace().print(ToProto.task(task));
    } catch (InvalidProtocolBufferException e) {
      throw new TaskSerializationException(task.id(), "Task cannot be written as JSON", e);
    }
  }

  private static Task fromJson(String taskId, String json) {
    org.a2aproject.sdk.grpc.Task.Builder builder = org.a2aproject.sdk.grpc.Task.newBuilder();
    try {
      JsonFormat.parser().merge(json, builder);
    } catch (InvalidProtocolBufferException e) {
      throw new TaskSerializationException(taskId, "Stored task is not a task's JSON", e);
    }
    return FromProto.task(builder);
  }
}
