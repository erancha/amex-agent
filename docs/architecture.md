# Architecture

This document details how amex-agent is built, behind the [README](../README.md)'s Architecture
section. Each section below ends in a page that holds its details. What goes on the wire is in
[a2a-contract.md](a2a-contract.md); how each part is tested is in [testing.md](testing.md); how
to run it is in [development.md](development.md); why each founding choice was made is in the
[decision records](adr/).

**Contents:** [The parts](#the-parts) · [Boundaries](#boundaries) · [Request flow](#request-flow) ·
[Statements](#statements) · [Categorizer](#categorizer) · [Scaling](#scaling) ·
[Configuration](#configuration) · [Deployment](#deployment) · [Storage](#storage) ·
[Codebase structure](#codebase-structure)

## The parts

```mermaid
flowchart LR
  subgraph CALLER[Caller  - another operator]
    C([A2A client, e.g. the home finance agent])
  end
  C -->|JSON-RPC + SSE<br/>person's ID token + caller secret| N
  subgraph AMX[amex-agent  - this repo]
    direction LR
    N["<b>nginx edge</b>"] --> W
    subgraph R[replicas]
      W["<b>Caller filter</b>"] --> E["<b>Spring MVC transport</b>"]
      E --> H["<b>a2a-java request handler</b>"]
      H --> X["<b>Executor</b>"]
      X --> P["<b>PDF parser</b>"]
      X --> M["<b>Merchant categorizer</b>"]
    end
    H <-->|tasks| T[("<b>Redis</b>")]
    P --> F["<b>Statements root</b><br/>shared volume, a folder per person"]
  end
  M -->|batches of unseen merchants<br/>via Spring AI| L
  L["<b>LLM (Language Model)</b><br/>categorizes merchants"]
  X -->|verify token| G["<b>Google public keys</b>"]
  classDef caller fill:#dbeafe,stroke:#1d4ed8,color:#111827
  classDef amx fill:#dcfce7,stroke:#15803d,color:#111827
  classDef goog fill:#fef3c7,stroke:#b45309,color:#111827
  classDef llm fill:#ede9fe,stroke:#6d28d9,color:#111827
  class C caller
  class N,W,E,H,X,T,F,P,M amx
  class G goog
  class L llm
  style CALLER fill:#eff6ff,stroke:#1d4ed8
  style AMX fill:#f0fdf4,stroke:#15803d
  style R fill:#dcfce7,stroke:#15803d
```

A request walks the diagram left to right:

- **A2A client**, the caller, reads amex-agent's Agent Card, checks it offers the skill, and asks
  for a billing month, for example `{"month": "2026-10"}`, sending its registered secret and the
  signed-in person's Google ID token, so amex-agent knows which caller asks and for whom.
- **nginx edge** spreads requests over the replicas; everything listed below runs inside the
  replica that got the request.
- **Caller filter** admits the call only when it carries a secret the agent issued to a registered
  caller.
- **Spring MVC transport**, hand-written and thin, reads the JSON-RPC call and streams the response
  back as SSE.
- **a2a-java request handler**, the official server library, turns the call into a task, kept in
  **Redis** so a task paused by one replica can be continued by any, and runs the executor for it.
- **Executor** is this repo's card work: verify the person's token against **Google public keys**,
  have the **PDF parser** read that month's statement from the person's own folder in the
  **statements root**, and have the **Merchant categorizer** label the merchants, asking the
  **LLM** (Anthropic's or OpenAI's) only about merchants this replica has not seen. The charges
  stream back along the same path in batches, each with a category such as `groceries` or
  `insurance`. When the statement is not loaded yet, the executor leaves the task open and asks for
  it; once the person has loaded the file, the caller says "continue" and the same task finishes.
  A token that has expired pauses the task the same way until the person signs in again.

The LLM only chooses categories; every amount and date is read from the PDF. A month comes back
whole or not at all: the parser checks its rows against the totals the statement prints and fails
the request on any difference.

## Boundaries

- **The contract is shared, not per agent.** Every card agent should offer the same A2A skill,
  `monthly-transactions`: a billing month in, rows out in one format (ISO dates, amounts in
  shekels, one category from a closed list), or a request for the missing statement. The caller
  owns that contract; [a2a-contract.md](a2a-contract.md) describes how amex-agent implements it.
- **Each card agent normalizes and categorizes its own card, so a caller needs nothing
  card-specific.** Everything Amex-specific stops here: the PDF layout, Hebrew labels,
  installments, purchases abroad. Rows leave amex-agent in the shared format, each in a category
  with the agent's confidence, `high`, `medium` or `low`, or `uncategorized` when it cannot tell.
  A caller can talk to any card agent that offers the skill, and owns only what spans cards: the
  person's own categories (which outrank any card agent's), the budget, the totals and the summary.

## Request flow

[architecture/request-flow.md](architecture/request-flow.md) is what runs inside a replica: how
the two credentials are checked, why the transport is hand-written, and the threading a
long-lived stream needs. The transport is hand-written because the Spring integration of a2a-java
refuses streaming requests
([ADR 0001](adr/0001-spring-boot-with-a-hand-written-a2a-transport.md)); a caller is known by
a secret the agent issued and the person by the Google token the caller forwards
([ADR 0004](adr/0004-registered-callers-forward-google-tokens.md)).

## Statements

One folder per person under the statements root, where a static page lets a person load and
delete their own files. On every lookup the agent reads the folder's PDFs and maps each to the
billing month it declares, so a file loaded while a task waits is found on `continue`. The parser
checks every section against its printed total. Statements are files a person loads, never
fetched from the card site ([ADR 0002](adr/0002-statements-are-pdfs-a-person-loads.md)).
[architecture/statements.md](architecture/statements.md)

## Categorizer

A language model categorizes each merchant once, in batches, guided by the sector label the
statement prints, and its score becomes `high`, `medium` or `low` confidence. Amounts and dates
never pass through the model, so a model error can mislabel a row but never change its sum
([ADR 0003](adr/0003-a-model-chooses-categories-only.md)).
[architecture/categorizer.md](architecture/categorizer.md)

## Scaling

The Docker stack runs several replicas of the agent behind an nginx edge, sharing their A2A tasks
through Redis, so a task paused for a missing statement can be continued through any replica. The
concerns that follow are each a GitHub label beside `scalability`: **scaling safety**, that it is
safe to run more than one replica without errors; and **scaling out**, that adding replicas serves
more callers without degrading any caller's experience. Redis holds the tasks because a paused task
must outlive the replica that paused it
([ADR 0005](adr/0005-replicas-share-tasks-through-redis.md)).
[architecture/scaling.md](architecture/scaling.md)

## Configuration

Every runtime setting is an environment variable, kept in three files under `scripts/lib/`: the
committed `stack-params.sh`, and the gitignored `params.sh` and `secrets.sh`. Secrets, the model
and the statements folder have no default, and a wrong required setting stops startup naming
it.
[architecture/configuration.md](architecture/configuration.md)

## Deployment

The stack runs under Docker Compose: Redis, two agent replicas and the nginx edge, the only
service with a published port. `./scripts/deploy.sh` runs it on this machine, with the edge on
the loopback interface at `EDGE_HOST_PORT` (8020) and the statements folder mounted into the
replicas. `./scripts/deploy.sh --ec2` runs the same stack on the EC2 instance of loan-simulator's
stack, as a guest behind a CloudFront distribution of its own
([ADR 0006](adr/0006-guest-on-loan-simulators-ec2-instance.md)).

- **The guest stack.** `scripts/template-ec2.yaml`, shared by every Docker Compose repo, opens
  the edge's port in the host's security group to CloudFront's servers alone and points the
  distribution at the instance. TLS ends at CloudFront; the hop to the instance is plain HTTP.
- **What differs on the instance** is in `docker-compose.ec2.yml`: images built for ARM, the edge
  published on the instance's address, the `statements` volume in place of this machine's folder,
  and services that start again with the instance.
- **Nothing of the deployer's machine is mounted.** The images are built there and loaded into
  the instance's Docker daemon over SSH, the edge's with `nginx/default.conf` built in, and
  Compose runs there against that daemon. The callers registry reaches each replica as a Compose
  secret, which Compose writes into the container through the Docker API, on both targets alike.
- **Addresses.** The Agent Card advertises the CloudFront address, which is also the statements
  page's. A caller on the same instance, such as home-finance-agent, fetches the card at
  `http://host.docker.internal:8020` and then calls the advertised address.

## Storage

- **Statements**: `APP_STATEMENTS_DIR` of `scripts/lib/stack-params.sh` (`./statements`, its
  content gitignored), one folder per person named by their Google account id, each file named
  `<YYYY>-<MM>.pdf` after its billing month. Every replica mounts the same folder; on the EC2
  instance the `statements` volume takes its place.
- **Tasks**: Redis, one key `a2a:task:<taskId>` per task holding its wire JSON, expiring
  `APP_TASKS_TTL` (30 days) after its last change, a set `a2a:tasks` of task ids for listing, and
  one `a2a:lease:<taskId>` key per running call. Redis writes its data to disk every second.
- **Callers**: `allowed-callers.yaml` (gitignored), handed to each replica as a Compose secret
  and read at startup.
- **Merchant categories**: each replica's memory, lost on restart.

## Codebase structure

Two halves: the packages that fit the a2a-java library into Spring and check who is calling, and
the card pipeline that turns a statement into categorized rows.
[architecture/codebase-structure.md](architecture/codebase-structure.md)
