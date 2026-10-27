package com.erancha.amexagent.a2a;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Task leases for a single process, as in start.sh --host and the unit tests. A lease still lapses
 * after its time to live, so a continue the SDK refused before running it does not hold its task
 * for good.
 */
public final class InMemoryTaskLease implements TaskLease {
  private record Held(String owner, Instant expires) {}

  // Key: task id. Value: the owner holding its lease and when the lease lapses.
  private final Map<String, Held> leases = new ConcurrentHashMap<>();
  private final Duration ttl;
  private final Clock clock = Clock.systemUTC();

  public InMemoryTaskLease(Duration ttl) {
    this.ttl = ttl;
  }

  @Override
  public boolean acquire(String taskId, String owner) {
    AtomicBoolean taken = new AtomicBoolean();
    leases.compute(taskId, (id, held) -> {
      if (held != null && !lapsed(held)) {
        return held;
      }
      taken.set(true);
      return new Held(owner, clock.instant().plus(ttl));
    });
    return taken.get();
  }

  @Override
  public boolean renew(String taskId, String owner) {
    AtomicBoolean renewed = new AtomicBoolean();
    leases.computeIfPresent(taskId, (id, held) -> {
      if (!held.owner().equals(owner) || lapsed(held)) {
        return held;
      }
      renewed.set(true);
      return new Held(owner, clock.instant().plus(ttl));
    });
    return renewed.get();
  }

  @Override
  public void release(String taskId, String owner) {
    leases.computeIfPresent(taskId, (id, held) -> held.owner().equals(owner) ? null : held);
  }

  @Override
  public String ownerOf(String taskId) {
    Held held = leases.get(taskId);
    return held == null || lapsed(held) ? null : held.owner();
  }

  private boolean lapsed(Held held) {
    return !clock.instant().isBefore(held.expires());
  }
}
