# Workers' responsibilities and flows

This document details the work no request waits on, behind the parts that
[Architecture](architecture.md#the-parts) summarizes: the lease sweep that every `amex-agent`
replica runs beside the requests it serves. The guarantees the sweep carries are in
[Scaling](architecture/scaling.md#scaling-safety), the routes in [API endpoints](api.md).

## `LeaseSweep`

`LeaseSweep` in `a2a/LeaseSweep.java` starts with the replica, on a thread of its own beside the
requests, and only when the replicas share their tasks through Redis (`APP_TASKS_REDIS_URL`):
without Redis, tasks live and die with the one process.

Every replica runs a sweep at startup and then once per lease time. A working task found without a
lease on two passes in a row, stored unchanged in between, was left by a replica that stopped: the
sweep stores it failed, with the text `stopped while working on this task; send the request
again`, so the caller can retry at once. Two passes, because a run releases its lease a moment
before the library stores the task's final state. The sweep replaces the task only if it is still
stored as the sweep read it, so a task that moved on in the meantime is left as it is
([The task lease](architecture/scaling.md#the-task-lease)).

A pass that cannot reach Redis is logged and the next pass tries again.
