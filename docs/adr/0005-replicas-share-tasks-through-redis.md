# 0005. Replicas share their tasks through Redis

- Status: Accepted
- Date: 2026-10-24

## Context

amex-agent ran as one process with the a2a-java library's in-memory task store. A task paused for
a missing statement existed only in that process: a restart lost it, the caller's `continue` then
failed, and a second replica could not continue a task it had never seen. A person may take hours
to download a statement, so a paused task must outlive restarts and redeploys.

## Decision

- The Docker Compose stack runs two replicas of the agent behind an nginx edge, the only service
  that publishes port 8020.
- The replicas keep their A2A tasks in Redis (`APP_TASKS_REDIS_URL`), one key per task holding its
  wire JSON, expiring `APP_TASKS_TTL` (30 days) after its last change. Redis writes its data to an
  append-only file every second.
- Without the setting, as for an agent run on the host and the unit tests, tasks stay in the
  process's memory.
- Every replica mounts the same statements folder; a person is a token verified on each request,
  so no session lives in a replica.

## Consequences

- Whichever replica receives a `continue` finds the paused task, and a paused task survives a
  restart of the agent and of Redis.
- Several processes can touch one task, which the A2A library does not anticipate; what the
  replicas must guarantee is stated in `docs/architecture/scaling.md`.
- The merchant categories stay in each process's memory, so a replica pays the model for
  merchants another replica has already categorized.
