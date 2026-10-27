package com.erancha.amexagent.a2a;

import java.util.UUID;

/**
 * Names the owner of the leases one call takes: this replica, then the call. A call releasing its
 * lease therefore never drops one that a later call on the same replica holds.
 */
public final class LeaseOwners {
  private final String replica = UUID.randomUUID().toString();

  String newCall() {
    return replica + "/" + UUID.randomUUID();
  }
}
