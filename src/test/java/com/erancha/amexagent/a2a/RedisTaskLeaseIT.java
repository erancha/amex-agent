package com.erancha.amexagent.a2a;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** The lease rules on Redis. Needs Docker for the Redis container. */
class RedisTaskLeaseIT extends TaskLeaseContract {
  private static GenericContainer<?> redis;
  private final List<RedisTaskLease> opened = new ArrayList<>();

  @BeforeAll
  static void startRedis() {
    redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    redis.start();
    try (TasksRedis connection = new TasksRedis(url())) {
      connection.commands().flushall();
    }
  }

  @AfterAll
  static void stopRedis() {
    redis.stop();
  }

  @AfterEach
  void closeLeases() {
    opened.forEach(RedisTaskLease::close);
    try (TasksRedis connection = new TasksRedis(url())) {
      connection.commands().flushall();
    }
  }

  private static String url() {
    return "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
  }

  @Override
  TaskLease lease(Duration ttl) {
    RedisTaskLease lease = new RedisTaskLease(url(), ttl);
    opened.add(lease);
    return lease;
  }
}
