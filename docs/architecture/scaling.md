# Scaling

The Docker stack runs the agent as several replicas behind an nginx edge, all on one Redis and one
statements folder, so a caller sees one agent whichever replica serves it. This page states what
the replicas must guarantee.

## Scaling safety

Several replicas of the agent must behave exactly as one would: a replica may start or stop, calls
may land on any replica in any order, and the caller must see the same result as from a single
process. In particular, a replica stopped while a task works must leave the caller a failed task it
can retry, and no task may stay `working` for want of its replica; and two continues sent at once
for one task must run it once.

What a request needs therefore crosses processes: a `SendStreamingMessage` call runs within the HTTP
response of the replica that received it; the person is a token verified on every request; every
replica mounts the same statements folder; and a paused task is kept in Redis, which writes it to
disk every second, so whichever replica receives the `continue` loads it, with the month and person
recorded when it paused. Without Redis, as in `start.sh --host` and the unit tests, tasks stay in
the process's memory. What is still open under this term is tracked in this repo's GitHub issues:
the `scalability` label with the `scaling-safety` label.

### The task lease

The A2A library has no notion of which replica runs a task, so the agent adds one. A lease,
`a2a:lease:<taskId>` in Redis, names the owner of one call: the replica and a call id. A new task's
run takes it before its work starts; a `continue` takes it in the endpoint, before the library
resumes the task, so of two continues sent at once the second is refused as already running. The
lease lasts `APP_TASKS_LEASE` (60 seconds by default). The run renews it every third of that and
releases it when the task pauses, completes or fails; a run that cannot renew it is stopped, which
fails its task. Without Redis the lease lives in the process's memory.

### The lease sweep

Every replica runs a sweep at startup and then once per lease time. A working task found without a
lease on two passes in a row, stored unchanged in between, was left by a replica that stopped: the
sweep stores it failed, with the text `stopped while working on this task; send the request
again`, so the caller can retry at once. Two passes, because a run releases its lease a moment
before the library stores the task's final state. The sweep replaces the task only if it is still
stored as the sweep read it, so a task that moved on in the meantime is left as it is. A pass that
cannot reach Redis is logged and the next pass tries again.

### Ended tasks never change

The task store refuses a save that would change a task already completed, failed, canceled or
rejected. A replica still writing a task after losing its lease therefore cannot undo the sweep's
failure, nor overwrite a task another call completed.

## Scaling out

Scaling out, as this page and the `scaling-out` label use the term, is scalability in the classic
sense: the agent serves more callers by adding replicas, and a caller's experience, the time a month
takes to fetch and categorize, stays what it is from a single replica. One thing must hold beyond
the shared statements folder and task store: a merchant one replica has categorized
([categorizer.md](categorizer.md)) must cost no other replica, and no later restart, a model call,
so that a month fetched through any replica is categorized as fast as through the one that saw its
merchants first. What is still open under this term is tracked in this repo's GitHub issues: the
`scalability` label with the `scaling-out` label.

## The edge service

An nginx service, `edge`, is the only one that publishes port 8020, and it spreads calls over the
replicas. It looks up the replicas' addresses again every 5 seconds, so a replica added by scaling
or recreated by a redeploy starts receiving calls without an edge restart. Its access log names the
replica that served each call (`docker-helper.sh --logs edge`). `start.sh --host` has no edge: the
one JVM serves the port itself.
