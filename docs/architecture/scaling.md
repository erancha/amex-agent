# Scaling

This document details the [Scaling](../architecture.md#scaling) that Architecture summarizes.
The Docker stack runs the agent as several replicas behind an nginx edge, all on one Redis and one
statements folder, so a caller sees one agent whichever replica serves it. This page states what
the replicas guarantee and the mechanisms that carry each guarantee, under two concerns: **scaling
safety**, that it is safe to run more than one replica without errors; and **scaling out**, that
adding replicas serves more callers without degrading any caller's experience.

## Scaling safety

Several replicas of the agent behave exactly as one would: a replica may start or stop, calls may
land on any replica in any order, and the caller sees the same result as from a single process. In
particular, a replica stopped while a task works leaves the caller a failed task it can retry, and
no task stays `working` for want of its replica; and two continues sent at once for one task run it
once. Every replica's [lease sweep](../workers.md#leasesweep) fails the
tasks a stopped replica left working.

What a request needs therefore crosses processes: a `SendStreamingMessage` call runs within the HTTP
response of the replica that received it; the person is a token verified on every request; every
replica mounts the same statements folder; and a paused task is kept in Redis, which writes it to
disk every second, so whichever replica receives the `continue` loads it, with the month and person
recorded when it paused. Without Redis, as in an agent started on the host and the unit tests,
tasks stay in the process's memory.

### The task lease

The A2A library has no notion of which replica runs a task, so the agent adds one. A lease,
`a2a:lease:<taskId>` in Redis, names the owner of one call: the replica and a call id. A new task's
run takes it before its work starts; a `continue` takes it in the endpoint, before the library
resumes the task, so of two continues sent at once the second is refused as already running. The
lease lasts `APP_TASKS_LEASE` (60 seconds by default). The run renews it every third of that and
releases it when the task pauses, completes or fails; a run that cannot renew it is stopped, which
fails its task. Without Redis the lease lives in the process's memory.

### Ended tasks never change

The task store refuses a save that would change a task already completed, failed, canceled or
rejected. A replica still writing a task after losing its lease therefore cannot undo the sweep's
failure, nor overwrite a task another call completed.

## Scaling out

Scaling out, as this page uses the term, is scalability in the classic sense: the agent serves
more callers by adding replicas, and a caller's experience, the time a month takes to fetch and
categorize, stays what it is from a single replica. The statements folder and the task store are
shared; the merchant categories are not. Each replica keeps the categories it has decided in its
own memory ([Merchant categories](../data-model.md#merchant-categories),
[categorizer.md](categorizer.md)), so a merchant one replica has categorized costs another replica,
or the same one after a restart, a model call of its own.

## The edge service

An nginx service, `edge`, is the only one that publishes a port (`EDGE_HOST_PORT`, 8020), and it
spreads calls over the replicas. It looks up the replicas' addresses again every 5 seconds, so a
replica added by scaling or recreated by a redeploy starts receiving calls without an edge
restart. Its access log names the replica that served each call (`docker-helper.sh --logs edge`).
An agent started on the host has no edge: the one JVM serves the port itself.
