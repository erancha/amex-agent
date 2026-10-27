package com.erancha.amexagent.a2a;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.a2aproject.sdk.spec.TaskState;

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
 * A task in a final state is never changed: a save that would change one is refused, so a replica
 * still writing a task that LeaseSweep has failed, or that another call completed, cannot undo
 * that outcome. The lease (TaskLease) keeps two calls from running one task; this refusal
 * covers a run that lost its lease while still working.
 */
public final class RedisTaskStore implements TaskStore, TaskStateProvider, AutoCloseable {
  private static final String KEY_PREFIX = "a2a:task:";
  private static final String INDEX = "a2a:tasks";
  // KEYS: the task, the id index. ARGV: task JSON, time to live in ms, task id, then the names of
  // the final states. 0 when the stored task is final and the save would change it.
  private static final String SAVE = """
      local stored = redis.call('GET', KEYS[1])
      if stored and stored ~= ARGV[1] then
        local status = cjson.decode(stored)['status']
        for i = 4, #ARGV do
          if status and status['state'] == ARGV[i] then return 0 end
        end
      end
      redis.call('SADD', KEYS[2], ARGV[3])
      redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
      return 1
      """;
  // KEYS: the task. ARGV: the JSON the task must still be stored as, the replacement JSON, time to
  // live in ms. 1 when the task was replaced.
  private static final String REPLACE_IF_UNCHANGED = """
      if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
      redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
      return 1
      """;
  private static final String[] FINAL_STATES = Arrays.stream(TaskState.values())
      .filter(TaskState::isFinal).map(TaskState::name).toArray(String[]::new);

  private final TasksRedis connection;
  private final RedisCommands<String, String> redis;
  private final String ttlMillis;

  /** Connects immediately, so an unreachable Redis fails startup rather than the first task. */
  public RedisTaskStore(String redisUrl, Duration ttl) {
    connection = new TasksRedis(redisUrl);
    redis = connection.commands();
    ttlMillis = Long.toString(ttl.toMillis());
  }

  /**
   * Throws TaskPersistenceException when the stored task is final and this save would change it.
   */
  @Override
  public void save(Task task, boolean isReplicated) {
    String[] args = new String[3 + FINAL_STATES.length];
    args[0] = toJson(task);
    args[1] = ttlMillis;
    args[2] = task.id();
    System.arraycopy(FINAL_STATES, 0, args, 3, FINAL_STATES.length);
    Long saved;
    try {
      saved = redis.eval(SAVE, ScriptOutputType.INTEGER,
          new String[] {KEY_PREFIX + task.id(), INDEX}, args);
    } catch (RedisException e) {
      throw new TaskPersistenceException(task.id(), "Redis refused the task: " + e.getMessage(), e);
    }
    if (saved == 0) {
      throw new TaskPersistenceException(task.id(),
          "task " + task.id() + " already ended, and an ended task is never changed");
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
    readAll().forEach(stored -> snapshot.save(stored.task(), false));
    return snapshot.list(params, context);
  }

  /** A stored task with the exact text it is stored as. */
  record StoredTask(Task task, String json) {}

  /** Every stored task in the working state, as the lease sweep reads them. */
  List<StoredTask> workingTasks() {
    return readAll().stream()
        .filter(stored -> stored.task().status().state() == TaskState.TASK_STATE_WORKING)
        .toList();
  }

  /**
   * Stores the replacement only if the task is still stored exactly as expectedJson, so a task
   * that changed since it was read is left as it now is.
   */
  boolean replaceIfUnchanged(String taskId, String expectedJson, Task replacement) {
    try {
      Long replaced = redis.eval(REPLACE_IF_UNCHANGED, ScriptOutputType.INTEGER,
          new String[] {KEY_PREFIX + taskId}, expectedJson, toJson(replacement), ttlMillis);
      return replaced == 1;
    } catch (RedisException e) {
      throw new TaskPersistenceException(taskId, "Redis refused the task: " + e.getMessage(), e);
    }
  }

  /** Every stored task, removing from the index the ids whose task has expired. */
  private List<StoredTask> readAll() {
    List<StoredTask> all = new ArrayList<>();
    try {
      String[] ids = redis.smembers(INDEX).toArray(String[]::new);
      if (ids.length > 0) {
        List<KeyValue<String, String>> stored = redis.mget(keys(ids));
        for (int i = 0; i < ids.length; i++) {
          KeyValue<String, String> entry = stored.get(i);
          if (entry.hasValue()) {
            all.add(new StoredTask(fromJson(ids[i], entry.getValue()), entry.getValue()));
          } else {
            redis.srem(INDEX, ids[i]);
          }
        }
      }
    } catch (RedisException e) {
      throw new TaskPersistenceException("Redis could not list tasks: " + e.getMessage(), e);
    }
    return all;
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
