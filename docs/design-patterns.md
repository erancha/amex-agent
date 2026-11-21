# Design patterns

This document details the shapes the code repeats behind
[Architecture](architecture.md#codebase-structure): how the packages depend on each other and
what each holds.

## Layers whose imports point inwards

The code under `src/main/java/com/erancha/amexagent/` has two halves: packages that fit the
a2a-java server library into Spring and check who is calling, and the card pipeline that turns a
statement into categorized rows, in three layers under `card/`. The A2A side knows nothing of PDFs
or models, so the card work can change without touching the protocol.

- `a2a` — the A2A edge: the JSON-RPC endpoint and its SSE stream writer, the Agent Card, the
  caller-secret filter, the wiring of the SDK as Spring beans, and the Redis task store the
  replicas share.
- `auth` — who a request is for: Google ID token verification, the registered callers looked up
  by secret, and which client id a token must name, the page's own or the admitted caller's.
- `card/api` — the domain: transactions, statements, the taxonomy and confidence levels, and the
  ports the pipeline depends on.
- `card/application` — the skill itself: the executor that drives a task through its states and
  pauses, request parsing, the categorizer, and the shaping of rows into 25-row artifact chunks.
- `card/infra` — the outside world: per-person statement folders, the Amex Israel PDF parser, and
  the two classifiers, the language model and the keyword table.
- `statements` — the statements page and its API, where a person loads and deletes their own PDFs.
- `tck` — the scenario executor the official A2A conformance suite expects, active only under the
  `tck` profile.

`card/api` imports nothing else of the agent's; `card/application` and `card/infra` build on it.
The `a2a` package touches the pipeline only for the taxonomy the card lists, and the SDK reaches the
pipeline only through the executor bean.
