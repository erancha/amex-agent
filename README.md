# amex-agent

[![CI](https://github.com/erancha/amex-agent/actions/workflows/ci.yml/badge.svg)](https://github.com/erancha/amex-agent/actions/workflows/ci.yml)

amex-agent is a service for one American Express Israel card. Another program asks it, over the
open [A2A (Agent2Agent) protocol](https://a2a-protocol.org), for one month's card charges and gets
them back categorized. The caller is typically an agent that reviews a household's monthly
spending across cards.

A2A calls every party in the conversation an agent, hence the name. Inside, amex-agent is a fixed
pipeline: read the month's statement, categorize its merchants, stream the rows back.

**Contents:** [Boundaries](#boundaries) · [How a request runs](#how-a-request-runs) ·
[Getting started](#getting-started) · [Scripts](#scripts) · [Testing](#testing) ·
[Architecture](#architecture) · [License](#license)

## Boundaries

- **The contract is shared, not per agent.** Every card agent offers the same A2A skill,
  `monthly-transactions`: a billing month in, rows out in one format (ISO dates, amounts in
  shekels, one category from a closed list), or a request for the missing statement. The caller
  owns that contract; [docs/a2a-contract.md](docs/a2a-contract.md) describes how amex-agent
  implements it.
- **Each card agent normalizes and categorizes its own card.** Everything Amex-specific stops
  here: the PDF layout, Hebrew labels, installments, purchases abroad. Rows leave amex-agent in
  the shared format, each in a category with the agent's confidence, `high`, `medium` or `low`,
  or `uncategorized` when it cannot tell.
- **A caller needs nothing card-specific.** It can talk to any card agent that offers the skill,
  and owns what spans cards: the person's own categories, which outrank any card agent's, the
  budget, the totals and the summary.

## How a request runs

1. A registered caller reads amex-agent's Agent Card and checks it offers the skill.
2. It asks for a billing month, for example `{"month": "2026-10"}`, sending its registered secret
   and the signed-in person's Google ID token, so amex-agent knows which caller asks and for whom.
3. amex-agent checks the token, then reads that month's statement PDF from the person's own folder
   under `statements/` and streams the charges back in batches, each with a category such as
   `groceries` or `insurance`.
4. If the PDF is not loaded yet, amex-agent keeps the task open and asks for it. Once the person
   has loaded the file, the caller says "continue" and the same task finishes. A token
   that has expired pauses the task the same way until the person signs in again.

Categories come from a language model (Anthropic or OpenAI). Everything else is read from the
statement, and each section's charges must add up to the total it prints, so a misread statement
fails instead of returning a short month.

## Getting started

Requires **Docker** with Compose 2.24+ for the default run, **Java 21** and **Maven** for tests,
host mode and `validate.sh`, and **Python 3.11+** for `validate.sh`.

1. **The env file.** `./scripts/init-env.sh` writes `.env`; every key in it is explained in
   [.env.example](.env.example). The next two steps fill in the two it needs. A caller calls the
   URL the card advertises, `APP_AGENT_URL`; for a caller running in Docker on this machine set
   it to `http://host.docker.internal:8020`, since `localhost` there is the caller's own
   container. The scripts treat `.env` as defaults: a variable already exported in the calling
   shell wins over the same key there.
2. **A Google OAuth client of its own**, set up as [docs/sign-in.md](docs/sign-in.md) describes;
   its client id goes into `.env` as `APP_AUTH_CLIENT_ID`.
3. **A language model key**, Anthropic's or OpenAI's, as `APP_LLM_API_KEY`.
   `APP_LLM_MODEL=provider:model` names the model, Anthropic's `claude-haiku-5-5` unless changed.
4. **Register a caller.** A program that calls the A2A skill is registered by its name and the
   Google client id of its own sign-in, whose tokens it forwards; its operator hands you that id.
   The agent refuses to start without a caller, and `start.sh` stops early to say so:

   ```bash
   ./scripts/register-caller.sh --name <caller> --client-id <the caller's client id>
   ```

   The registration prints the four values the caller's operator records: this agent's name,
   its A2A URL, its statements page URL and the secret, shown once. Hand them over. Registering
   another caller later takes a restart: the agent reads the file at startup.
5. **Start:**

   ```bash
   ./scripts/start.sh             # builds the image, starts amex-agent on http://localhost:8020
   ./scripts/validate.sh          # runs its own copy on :8021 and proves it as an A2A client would
   ```

   `start.sh` ends by repeating the three public values of the hand-over: this agent's name, the
   advertised URL and the statements page's URL.
6. **Statements.** Download each monthly statement PDF from the Amex site and load it on the
   statements page, **http://localhost:8020/statements.html**: sign in with your Google account,
   and the page lists the statements you loaded, deletes one on request and stores each upload
   under its billing month whatever the file is called, in a folder of your own under
   `statements/` (git ignores the folder). The site names a statement after its billing month, the
   month the card is charged: `2026-10.pdf` holds the September purchases charged on 2 October
   2026, and answers a request for `2026-10`. The page only loads files; everything about the
   month's spending happens in the caller.

Only the tests run without a model, through the `e2e` Spring profile, which categorizes with a
built-in keyword table.

## Scripts

All live in `scripts/`; run with `--help` for full usage.

| Script | Purpose |
|---|---|
| `init-env.sh` | Create `.env`; `--force` overwrites. |
| `register-caller.sh` | Register an A2A caller (name, secret, Google client id) in `allowed-callers.yaml`, or replace its entry. |
| `start.sh` | Run the agent in Docker, or `--host` as a local JVM. |
| `docker-helper.sh` | `--stop`, `--logs [-e]` or `--ps` for the Docker run. |
| `test.sh` | Run unit tests (`unit`) or unit and integration tests (`it`, `all`, the default). |
| `validate.sh` | Start amex-agent against its test statement with a throwaway signing key and prove it end to end as a registered A2A caller; `--tck` also runs the official A2A conformance suite. |
| `anonymize-statement.py` | Copy a statement PDF with the holder's private details replaced, to use as a test fixture. |

## Testing

```bash
./scripts/test.sh              # unit + integration, no Docker, no API key
```
What each layer covers, and the latest conformance result, is in [docs/testing.md](docs/testing.md).

## Architecture

```mermaid
flowchart LR
  C([A2A client]) -->|JSON-RPC + SSE<br/>person's ID token + caller secret| E[Spring MVC edge]
  E --> H[a2a-java request handler]
  H --> X[Executor]
  X -->|verify token| G[Google public keys]
  X --> F[Person's statement folder]
  F --> P[PDF parser]
  X --> M[Merchant categorizer]
  M -->|batches of unseen merchants| L[Spring AI model]
```

The edge is two Spring controllers around the official a2a-java server library, behind a filter
that admits registered callers. The card work is a fixed pipeline: verify who the month is for,
find their statement, parse it, categorize, stream. Details and decisions:
[docs/architecture.md](docs/architecture.md); what it puts on the wire:
[docs/a2a-contract.md](docs/a2a-contract.md).

**Stack:** Java 21, Spring Boot, Spring AI, a2a-java, nimbus-jose-jwt, Apache PDFBox, Docker.

## License

Released under the MIT License. See [LICENSE](LICENSE).

---
More projects by the author: [github.com/erancha](https://github.com/erancha)
