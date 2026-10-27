package com.erancha.amexagent.a2a;

import java.time.Duration;

class InMemoryTaskLeaseTest extends TaskLeaseContract {
  @Override
  TaskLease lease(Duration ttl) {
    return new InMemoryTaskLease(ttl);
  }
}
