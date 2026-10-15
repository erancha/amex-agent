# Testing

Four layers, each proving something the others cannot: unit tests pin the parsing, categorizing
and transport rules; the integration test drives the real agent with the a2a-java client;
`validate.sh` drives the packaged jar with the Python a2a-sdk client; the official A2A TCK checks
the transport against the protocol. None needs Docker or an API key.

**Contents:** [Running](#running) · [Unit](#unit) · [Integration](#integration) ·
[Client-side validation](#client-side-validation) · [Conformance](#conformance) · [CI](#ci)

## Running

| Command | Runs | Needs |
|---|---|---|
| `./scripts/test.sh unit` | Unit tests (`*Test`, `mvn test`) | Java 21, Maven |
| `./scripts/test.sh` (or `it`, `all`) | Unit and integration tests (`*IT`, `mvn verify`) | Java 21, Maven |
| `./scripts/validate.sh` | Packages the jar, starts it on port 8021 under the `e2e` profile with the test statement, runs the client checks, then boots the jar once per real provider with a placeholder key to prove the provider's runtime libraries ship in it | Java 21, Maven, Python 3.11+, network for the first venv install |
| `bash scripts/tests/test_load_env.sh` | Scripts: `.env` loading in `common.sh` (caller's environment wins, quotes stripped, malformed line rejected) | Bash |
| `./scripts/validate.sh --tck` | The above, then the A2A TCK against a second agent on port 8022 under the `tck` and `e2e` profiles | as above, plus network to clone the TCK |

`validate.sh` refuses to start when port 8021 or 8022 already answers a health check, so it never
validates a stale agent left over from an interrupted run. Agent logs land in
`target/validate-<port>.log`.

## Unit

Each class mirrors the main class it tests.

| Test | Pins |
|---|---|
| `PdfStatementParserTest` | On the anonymized statement: the charge date and billing month, all 24 rows adding up to the statement's total, distinct ids, a foreign purchase in shekels, an installment, a standing order, and a domestic row read from the second page. Altered text lines fail naming the problem: a missing row (the section total no longer matches, both sums named), an impossible date such as 31/02, a charge with no purchase date, a cell reaching across two columns, a statement that ends before a section's total line, no charge-date label, and no transaction section at all. |
| `HebrewMonthsTest` | Hebrew month name and year to a `YearMonth`; an unknown label fails naming it. |
| `StatementFolderTest` | Lookup by billing month; a file added after the first scan is found; a download-style name that disagrees with the content fails naming both months, in the PDF shape `2026-09.pdf` and in the export shape `1258_09_2026.pdf`; an informal name is indexed by content; two files for one month fail naming both; an impossible month in the name fails; a missing folder fails; an Excel `~$` lock file and other extensions are ignored; an upper-case `.PDF` is indexed; a file that is not a PDF fails naming it. |
| `StatementFolderTest` (loading) | Listing returns statement files only, sorted by name; an upload under any name is stored as the download name of its content's billing month and found; a path-like or misleading name (`../`, `..\`, absolute, nested, another month's name, empty) still lands only in the folder, under the download name; refused without writing anything: content without the PDF signature, a PDF that is not a statement, a month another file covers, and a folder blocked by an unreadable file. |
| `StatementFolderTest` (removing) | A listed file is deleted without being read, so a misnamed file that blocks the folder is removed and its month loads; a name that is not a listed statement (missing, another extension, `../` outside the folder, empty) is refused and deletes nothing. |
| `MerchantNormalizerTest` | Branch numbers, company forms and case are stripped; both gershayim spellings of `בע"מ` give one key. |
| `MerchantCategorizerTest` | A repeated merchant is classified once; many unseen merchants split into batches; model scores become `high`, `medium` and `low` at the two thresholds, and a `low` row keeps its category and display name; a category outside the taxonomy or a score outside `[0, 1]` becomes `uncategorized` with `low` confidence; a classifier answering the wrong count fails; reordered answers land on the merchants they echo; an answer echoing an unrequested merchant or no merchant leaves the requested one `uncategorized` with `low` confidence; an omitted display name falls back to the raw merchant and an omitted category is `uncategorized`; constructor bounds, thresholds out of order included. |
| `KeywordMerchantClassifierTest` | The `e2e` profile's keyword table knows every merchant of the test statement except its gym; known and unknown merchants get stable answers. |
| `SpringAiMerchantClassifierTest` | With a fake `ChatModel`: the prompt carries each category with its guide, and the merchants, and the structured answer parses; an answer with no text fails naming the finish reason and the output-limit property. |
| `LlmPropertiesTest` | `fake` or any other provider outside `anthropic` and `openai` fails naming `APP_LLM_PROVIDER`; a real provider without a model fails naming `APP_LLM_MODEL`. |
| `LlmConfigurationTest` | Booting the application: `APP_LLM_PROVIDER=fake` is rejected; `anthropic` without a key fails naming `ANTHROPIC_API_KEY`; the `e2e` profile boots on `KeywordMerchantClassifier` with no provider, model or key and binds no `LlmProperties`. The key check alone: `anthropic` and `openai` without a key fail naming `ANTHROPIC_API_KEY` or `OPENAI_API_KEY`; `anthropic` with a key passes; any other provider reaching it fails as unreachable. |
| `CategoryTest`, `TransactionTest` | Wire values equal the published taxonomy; unknown, blank or omitted model labels map to `uncategorized` and wire values round-trip; categorizing keeps every statement field. |
| `MonthRequestTest` | Data-part and text-part months are fetches; `{"action": "continue"}` is a continue; `2026-9`, `09/2026`, `2026-13` and a message without a usable part are `InvalidParams`. |
| `ArtifactMapperTest` | Every artifact field is present, confidence as its level name, nullables are explicit nulls; batches split at 25 and carry `month` and `currency`; an empty statement is one empty batch. |
| `TransactionsServiceTest` | A covered month returns categorized rows; an uncovered month is empty. |
| `TransactionsAgentExecutorTest` | Against the SDK's in-memory queue: submitted, working, artifacts, completed; input-required with reason, month and page; continue that completes or asks again; 60 rows as three appended chunks on one `artifactId` with `lastChunk` on the last; continue on a working task or without a statement-missing part is `InvalidParams`; cancel of a waiting task emits canceled, of a final task throws `TaskNotCancelable`. |
| `A2APropertiesTest` | A blank token with auth required fails naming the variable; a blank token with auth off passes. |
| `AgentCardConfigurationTest` | JSON-RPC interface, streaming and the skill; the bearer scheme only when auth is required. |
| `BearerTokenFilterTest` | The exact token passes; a missing, wrong, differently cased or non-bearer header is 401 with `WWW-Authenticate: Bearer`; the card, health, the statements page and its API are open. |
| `StatementsEndpointTest` | Over real HTTP with auth on and a temporary folder: the page and the API need no token; an upload lands in the folder byte for byte under its content's download name, whatever it was called, and is listed; a path-traversal name lands in the folder only; the same month twice and non-PDF content are 400 problem documents naming the cause; an upload over the multipart limit is 413; an upload refused for non-PDF content or its size writes nothing; a listed file is deleted with 204 and deleting an unlisted name is a 400 naming it. |
| `CallContextsTest` | The SDK call context carries the headers, tenant, requested extensions and protocol version with an unauthenticated user; tenant path trimming. |
| `SseStreamWriterTest` | Each event is one frame with `data:` and `id:`; a client gone mid-stream and a frame that cannot be serialized both cancel the subscription and the event consumer and release the request thread; a cancel callback that throws still releases it. |
| `A2AEndpointTest` | Over real HTTP with an echo executor: the public card, `SendStreamingMessage` as SSE and `GetTask` of the streamed task as one JSON document, the stream closing after input-required, a non-JSON content type as -32005, an unknown method as -32601 with the request id, malformed JSON as -32700. |
| `AmexAgentApplicationTest` | The `e2e` context wires the card pipeline, the keyword classifier and the bearer filter. |
| `TckProfileTest` | The `tck` profile wires `TckAgentExecutor`, has no bearer filter, and answers a TCK scenario unauthenticated. |

## Integration

`A2ATransportIT` boots the whole agent under the `e2e` profile with the committed statement on fixed
port 18020 (the card advertises the URL the client must call) and drives it with the a2a-java
client over JSON-RPC:

- the card is served without a token;
- a streaming task for 2026-10 completes with one artifact of 24 rows, and cancelling it
  afterwards is refused with `Task cannot be canceled`;
- a task for 2026-09 asks for input, asks again on `continue`, and cancels to
  `TASK_STATE_CANCELED`;
- a wrong token fails with HTTP 401;
- a malformed month (`09/2026`) fails with an error naming the month.

## Client-side validation

`scripts/validate/check_agent.py` uses the Python `a2a-sdk` client from a private venv
(`.validate-venv/`) against the packaged jar. It prints one `PASS` line per check and stops at the
first failure:

1. the card advertises `monthly-transactions`;
2. the card advertises streaming;
3. 2026-10 completes;
4. 2026-10 streams 24 transactions;
5. every transaction carries a category;
6. 2026-09 asks for input;
7. the reason is `statement-missing`;
8. `continue` without the file asks again;
9. cancelling the waiting task succeeds;
10. cancelling the completed task is refused;
11. a wrong token is 401.

## Conformance

`./scripts/validate.sh --tck` clones or fast-forwards
[a2aproject/a2a-tck](https://github.com/a2aproject/a2a-tck) into `.tck/`, installs it into
`.tck/.venv`, starts the agent under the `tck` and `e2e` Spring profiles and runs
`run_tck.py --transport jsonrpc --level must`. The script fails on any MUST failure.

The `tck` profile (`application-tck.yml`) turns auth off, because the TCK sends no credentials, and
replaces `TransactionsAgentExecutor` with `TckAgentExecutor`, a verbatim port of the SDK's own
conformance executor. The TCK sends scripted messages, keyed by message-id prefix, that expect
scripted answers no card executor would give. The TCK therefore proves the transport; the
integration test and `validate.sh` prove the business executor.

The TCK's levels follow RFC 2119: MUST requirements are mandatory for conformance, SHOULD and MAY
are recommended and optional. `--level must` runs only the MUST tests; the rest are skipped, as are
the tests for other transports (gRPC, HTTP+JSON). Reports land in `.tck/reports/`
(`compatibility.json`, `compatibility.html`, `tck_report.html`, `junitreport.xml`).

Result across runs, a2a-tck commit `263b9cf`, `--transport jsonrpc --level must`:

| Measure | Result |
|---|---|
| Tests | 80-81 passed, 0 failed, 154-155 skipped |
| MUST requirements | 67 PASS, 0 FAIL |

One test moves between passed and skipped from run to run. It sends a blocking `SendMessage`,
which the SDK's own race sometimes answers with `Timeout waiting for task <id> consumption` (see
[Library behavior](architecture.md#library-behavior)); the TCK records that error as a skip.

The script tracks the TCK's default branch, so a later run may test a newer commit than the one
recorded here.

## CI

`.github/workflows/ci.yml` runs on every push and pull request on `ubuntu-latest`: Java 21
(Temurin) runs `scripts/test.sh all`, then `scripts/tests/test_load_env.sh`, then Python 3.12
runs `scripts/validate.sh`. CI calls the
same scripts developers run, so the two cannot drift. The TCK is not part of CI; it runs locally
through `validate.sh --tck`.
