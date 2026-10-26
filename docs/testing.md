# Testing

Four layers, each proving something the others cannot. Unit tests pin the rules of parsing,
categorizing, sign-in and the transport. Integration tests run the real agent, alone and as two
replicas, through the a2a-java client. `validate.sh` runs the packaged jar through the Python
a2a-sdk client, a second implementation of the protocol. The official A2A conformance suite checks
the transport against the protocol itself. None needs a model API key, and none reaches Google: the
tests sign their own tokens with a key they generate.

**Contents:** [Running](#running) · [Unit](#unit) · [Integration](#integration) ·
[Client-side validation](#client-side-validation) · [Conformance](#conformance) · [CI](#ci)

## Running

| Command | Runs | Needs |
|---|---|---|
| `./scripts/test.sh unit` | Unit tests | Java 21, Maven |
| `./scripts/test.sh` (or `it`, `all`) | Unit and integration tests | Java 21, Maven, Docker for Redis |
| `./scripts/validate.sh` | The packaged jar, proved from the client side | Java 21, Maven, Python 3.11+, openssl |
| `./scripts/validate.sh --tck` | The above, then the A2A conformance suite | as above, plus network to fetch the suite |
| `for t in scripts/tests/test_*.sh; do bash "$t"; done` | The operator scripts' checks | Bash |

## Unit

Each test class mirrors the class it tests. What the unit layer pins, by area:

- **The PDF statement.** On the anonymized test statement, all 24 rows are read and add up to the
  printed totals. Altered text, a lost row, an impossible date, a cell across two columns, fails
  naming the problem rather than returning a shorter month.
- **Statement folders and the page.** Lookup by the month a file declares, whatever its name; two
  files for one month fail; each person sees only their own folder; an upload under any name, a
  path-like one included, lands only in the person's folder under its month's name; refused uploads
  write nothing.
- **Categorizing.** Each merchant is classified once, in batches, with its sector; scores become
  confidence levels at the two thresholds; any answer outside the taxonomy or the score range is
  `uncategorized` and `low`; the prompt carries every category's guide.
- **Sign-in.** Expired, foreign-audience, foreign-issuer, tampered and unsigned tokens are refused
  naming a cause a person can act on; a page token and a forwarded caller token are each checked
  against their own client id; only the exact caller secret admits a caller.
- **The task.** Against the library's in-memory queue: the states of a covered month, both pauses
  with their data parts, `continue` by the same person and refused for another, chunking at 25
  rows on one artifact, and cancel.
- **The transport.** Over real HTTP: the card, a stream as SSE frames, the stream closing at a
  pause, the JSON-RPC error codes, and a caller that leaves mid-stream releasing its thread.
- **Startup.** Each wrong required setting fails naming the variable, and each Spring profile boots
  with the classifier and filters it is meant to have.

## Integration

These boot the whole agent under the `e2e` profile with the test statement in one person's folder,
and call it as a registered caller would.

- **One agent** through the a2a-java client: a covered month completes with its 24 rows, a missing
  month pauses and asks again on `continue`, a call without the person's token pauses for sign-in,
  a wrong secret is HTTP 401, and a malformed month is an error naming it.
- **Two replicas** sharing one Redis and one statements folder: a month that pauses on the first
  agent completes when `continue` is sent to the second, and the first then reports it completed.
  This is the scaling guarantee a caller relies on.
- **The Redis task store** on its own: a task saved by one store is read in full by another, its
  pause included, and an unreachable Redis fails startup naming `APP_TASKS_REDIS_URL`.

## Client-side validation

`validate.sh` packages the jar, starts it on port 8021 with a throwaway signing key standing in for
Google and a caller registered by `register-caller.sh`, and runs a Python client on the a2a-sdk, a
second implementation of the protocol that the agent must agree with. It prints one `PASS` line per
check and stops at the first failure: the card's skill, streaming and two security schemes; a
covered month streaming its 24 categorized rows; a missing month pausing and asking again on
`continue`; cancel of a waiting and of a completed task; both sign-in failures pausing in
auth-required; a wrong secret answered with 401; and the statements API serving only the signed-in
person.

It then boots the jar once per real model provider with a placeholder key, which proves each
provider's runtime libraries ship in the jar. It refuses to start while one of its ports already
answers, so it never validates an agent left over from an interrupted run.

## Conformance

`./scripts/validate.sh --tck` runs the official
[A2A conformance suite (TCK)](https://github.com/a2aproject/a2a-tck) against a second agent on port
8022, over JSON-RPC, at the MUST level: the requirements mandatory for conformance. The suite sends
scripted messages and expects scripted answers no card agent would give, and sends no credentials,
so this agent runs under the `tck` profile, with auth off and the library's own conformance
executor in place of the card's. The suite therefore proves the transport; the integration tests
and `validate.sh` prove the card work.

Result across runs, a2a-tck commit `263b9cf`, `--transport jsonrpc --level must`:

| Measure | Result |
|---|---|
| Tests | 80-81 passed, 0 failed, 154-155 skipped |
| MUST requirements | 67 PASS, 0 FAIL |

One test moves between passed and skipped from run to run. It sends a blocking `SendMessage`,
which the library sometimes answers with `Timeout waiting for task <id> consumption` (see
[Library behavior](architecture/request-flow.md#library-behavior)); the suite records that error as
a skip. The script tracks the suite's default branch, so a later run may test a newer commit than
the one recorded here.

## CI

`.github/workflows/ci.yml` runs on every push and pull request: the unit and integration tests, the
operator scripts' checks, then `validate.sh`. CI calls the same scripts developers run, so the two
cannot drift. The conformance suite runs locally through `validate.sh --tck`.
