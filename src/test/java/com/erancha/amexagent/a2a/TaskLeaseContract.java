package com.erancha.amexagent.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The rules every TaskLease keeps; each implementation's test supplies a fresh lease. */
abstract class TaskLeaseContract {
  private static final Duration TTL = Duration.ofMillis(500);

  /** A lease with the given time to live, holding no task. */
  abstract TaskLease lease(Duration ttl);

  @Test
  void aLeaseHeldByOneOwner_isRefusedToAnother() {
    TaskLease lease = lease(Duration.ofMinutes(1));

    assertThat(lease.acquire("t1", "a")).isTrue();
    assertThat(lease.acquire("t1", "b")).isFalse();
    assertThat(lease.ownerOf("t1")).isEqualTo("a");
    assertThat(lease.ownerOf("t2")).isNull();
  }

  @Test
  void onlyTheOwner_renewsOrReleasesTheLease() {
    TaskLease lease = lease(Duration.ofMinutes(1));
    lease.acquire("t1", "a");

    assertThat(lease.renew("t1", "b")).isFalse();
    lease.release("t1", "b");
    assertThat(lease.ownerOf("t1")).isEqualTo("a");

    assertThat(lease.renew("t1", "a")).isTrue();
    lease.release("t1", "a");
    assertThat(lease.ownerOf("t1")).isNull();
    assertThat(lease.renew("t1", "a")).isFalse();
  }

  @Test
  void aLeaseNotRenewed_lapsesAndCanBeTakenAgain() {
    TaskLease lease = lease(TTL);
    lease.acquire("t1", "a");

    await().atMost(Duration.ofSeconds(5)).until(() -> lease.ownerOf("t1") == null);
    assertThat(lease.renew("t1", "a")).isFalse();
    assertThat(lease.acquire("t1", "b")).isTrue();
  }
}
