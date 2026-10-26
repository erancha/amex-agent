# Codebase structure

The code under `src/main/java/com/erancha/amexagent/` has two halves: packages that fit the
a2a-java server library into Spring and check who is calling, and the card pipeline that turns a
statement into categorized rows. The A2A side knows nothing of PDFs or models, so the card work
can change without touching the protocol.

| Package | Responsibility |
|---|---|
| `a2a` | The A2A edge: the JSON-RPC endpoint and its SSE stream writer, the Agent Card, the caller-secret filter, the wiring of the SDK as Spring beans, and the Redis task store the replicas share. |
| `auth` | Who a request is for: Google ID token verification, the registered callers looked up by secret, and which client id a token must name, the page's own or the admitted caller's. |
| `card/api` | The domain: transactions, statements, the taxonomy and confidence levels, and the ports the pipeline depends on. |
| `card/application` | The skill itself: the executor that drives a task through its states and pauses, request parsing, the categorizer, and the shaping of rows into 25-row artifact chunks. |
| `card/infra` | The outside world: per-person statement folders, the Amex Israel PDF parser, and the two classifiers, the language model and the keyword table. |
| `statements` | The statements page and its API, where a person loads and deletes their own PDFs. |
| `tck` | The scenario executor the official A2A conformance suite expects, active only under the `tck` profile. |

`card/api` imports nothing else of the agent's; `card/application` and `card/infra` build on it.
The `a2a` package touches the pipeline only for the taxonomy the card lists, and the SDK reaches the
pipeline only through the executor bean.
