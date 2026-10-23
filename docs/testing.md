# Testing

Four layers, each proving something the others cannot: unit tests pin the parsing, categorizing
and transport rules; the integration test drives the real agent with the a2a-java client;
`validate.sh` drives the packaged jar with the Python a2a-sdk client; the official A2A TCK checks
the transport against the protocol. Beside them, the Bash checks under `scripts/tests/` pin what
the operator scripts print and refuse. None needs an API key. The two Redis integration tests
need Docker, because Testcontainers starts their Redis in a container.

**Contents:** [Running](#running) · [Unit](#unit) · [Integration](#integration) ·
[Client-side validation](#client-side-validation) · [Conformance](#conformance) · [CI](#ci)

## Running

| Command | Runs | Needs |
|---|---|---|
| `./scripts/test.sh unit` | Unit tests (`*Test`, `mvn test`) | Java 21, Maven |
| `./scripts/test.sh` (or `it`, `all`) | Unit and integration tests (`*IT`, `mvn verify`) | Java 21, Maven, Docker |
| `./scripts/validate.sh` | Packages the jar, starts it on port 8021 under the `e2e` profile with the test statement in one person's folder and a throwaway signing key standing in for Google, runs the client checks as a registered caller, then boots the jar once per real provider with a placeholder key to prove the provider's runtime libraries ship in it | Java 21, Maven, Python 3.11+, openssl, network for the first venv install |
| `bash scripts/tests/test_load_env.sh` | Scripts: `.env` loading in `common.sh` (caller's environment wins, quotes stripped, malformed line rejected) | Bash |
| `bash scripts/tests/test_register_caller.sh` | Scripts: `register-caller.sh` mints and prints a secret unless given one, with the agent's name and URLs the caller's operator records, replaces an entry by name keeping the others, writes the file owner-readable, replaces an empty directory at the path (what a Docker start without the file leaves) and refuses one with content | Bash |
| `bash scripts/tests/test_require_callers_file.sh` | Scripts: `require_callers_file` in `common.sh` stops `start.sh` before any launch when no callers file exists, removes an empty directory standing in its place and refuses one with content, and passes with the file present (default or relative `APP_A2A_CALLERS_FILE`) or with access not required | Bash |
| `bash scripts/tests/test_handover.sh` | Scripts: `print_handover` in `common.sh` prints, to the audience given, the agent name, the advertised URL and page URL from the environment and the secret text given, defaults an unset advertised URL to the page URL, and warns on a localhost advertised URL that a caller in Docker cannot reach it | Bash |
| `./scripts/validate.sh --tck` | The above, then the A2A TCK against a second agent on port 8022 under the `tck` and `e2e` profiles | as above, plus network to clone the TCK |

`validate.sh` refuses to start when a port it needs (8021, 8023, or 8022 with `--tck`) already
answers a health check, so it never validates a stale agent left over from an interrupted run. Agent
logs land in `target/validate-<port>.log`.

## Unit

Each class mirrors the main class it tests.

| Test | Pins |
|---|---|
| `PdfStatementParserTest` | On the anonymized statement: the charge date and billing month, all 24 rows adding up to the statement's total, distinct ids, a foreign purchase in shekels with no sector, an installment with its sector, a standing order, and a domestic row read from the second page. Altered text lines fail naming the problem: a missing row (the section total does not match, both sums named), an impossible date such as 31/02, a charge with no purchase date, a cell reaching across two columns, a statement that ends before a section's total line, no charge-date label, and no transaction section at all. |
| `StatementFolderTest` | Lookup by billing month; a file added after the first scan is found; a download-style name (`2026-09.pdf`) that disagrees with the content fails naming both months; an informal name is indexed by content; two files for one month fail naming both; an impossible month in the name fails; a folder that does not exist yet is empty until the first upload creates it, while a path that is a file fails naming it; other extensions are ignored; an upper-case `.PDF` is indexed; a file that is not a PDF fails naming it. |
| `StatementFoldersTest` | Two people with the same month see only their own file; a lookup for a person without a folder is empty and creates none; a path-like user id is refused; a missing root fails naming it. |
| `StatementFolderTest` (loading) | Listing returns statement files only, sorted by name; an upload under any name is stored as the download name of its content's billing month and found; a path-like or misleading name (`../`, `..\`, absolute, nested, another month's name, empty) still lands only in the folder, under the download name; refused without writing anything: content without the PDF signature, a PDF that is not a statement, a month another file covers, and a folder blocked by an unreadable file. |
| `StatementFolderTest` (removing) | A listed file is deleted without being read, so a misnamed file that blocks the folder is removed and its month loads; a name that is not a listed statement (missing, another extension, `../` outside the folder, empty) is refused and deletes nothing. |
| `MerchantNormalizerTest` | Branch numbers, company forms and case are stripped; both gershayim spellings of `בע"מ` give one key. |
| `MerchantCategorizerTest` | A repeated merchant is classified once; the statement sector reaches the classifier beside the raw merchant; many unseen merchants split into batches; model scores become `high`, `medium` and `low` at the two thresholds, and a `low` row keeps its category and display name; a category outside the taxonomy or a score outside `[0, 1]` becomes `uncategorized` with `low` confidence; a classifier answering the wrong count fails; reordered answers land on the merchants they echo; an answer echoing an unrequested merchant or no merchant leaves the requested one `uncategorized` with `low` confidence; an omitted display name falls back to the raw merchant and an omitted category is `uncategorized`; constructor bounds, thresholds out of order included. |
| `KeywordMerchantClassifierTest` | The `e2e` profile's keyword table knows every merchant of the test statement except its gym; known and unknown merchants get stable answers. |
| `SpringAiMerchantClassifierTest` | With a fake `ChatModel`: the prompt carries each category with its guide, and the merchants, each with its sector in brackets when it has one, and the structured answer parses; an answer with no text fails naming the finish reason and the output-limit property. |
| `LlmPropertiesTest` | A blank `APP_LLM_MODEL`, one without both halves of `provider:model`, and one naming a provider outside `anthropic` and `openai` each fail naming the variable and what is wrong; the split is at the first colon, so a model id may contain colons. |
| `LlmConfigurationTest` | Booting the application: a blank `APP_LLM_MODEL` fails naming it; `fake:m` is refused before any bean exists; `anthropic:m` without a key fails naming `APP_LLM_API_KEY`, and with one it selects the Anthropic `ChatModel` with model `m` and hands it the key; the `e2e` profile boots on `KeywordMerchantClassifier` with no model setting, binds no `LlmProperties` and selects no `ChatModel`. The key check alone: a blank key fails naming `APP_LLM_API_KEY` and the provider; a set one passes. |
| `CategoryTest`, `TransactionTest` | Wire values equal the published taxonomy; unknown, blank or omitted model labels map to `uncategorized` and wire values round-trip; categorizing keeps every statement field, the sector included. |
| `MonthRequestTest` | Data-part and text-part months are fetches; `{"action": "continue"}` is a continue; `2026-9`, `09/2026`, `2026-13` and a message without a usable part are `InvalidParams`. |
| `ArtifactMapperTest` | Every artifact field is present, confidence as its level name, nullables are explicit nulls; batches split at 25 and carry `month` and `currency`; an empty statement is one empty batch. |
| `TransactionsServiceTest` | A person's covered month returns categorized rows; another person's, or an uncovered month, is empty. |
| `TransactionsAgentExecutorTest` | Against the SDK's in-memory queue: submitted, working, artifacts, completed; input-required with reason, month, page and person; auth-required with the sign-in-required reason, month and an empty person when the token is unacceptable, serving nothing; continue after auth-required with a fresh token completes; continue by another Google account is auth-required naming the mismatch and serves nothing; another person's month is missing for them; continue that completes or asks again; 60 rows as three appended chunks on one `artifactId` with `lastChunk` on the last; continue on a working task or without a pause part is `InvalidParams`; cancel of a waiting task emits canceled, of a final task throws `TaskNotCancelable`. |
| `GoogleTokenVerifierTest` | A validly signed token yields sub, email and name (the last two may be absent); expired, another audience, another issuer, a tampered signature, an unsigned or HMAC token and garbage are refused, each naming a cause a person can act on; Google's two issuer spellings are both accepted; a PEM file that is not a key fails naming the file. |
| `UsersTest` | A page token is checked against the agent's own client, a forwarded token against the caller's, and each refuses the other; a missing or non-bearer header is refused; with auth off everyone is `anonymous` and no client id is published; auth on without a client id fails naming `APP_AUTH_CLIENT_ID`. |
| `CallersTest`, `UserIdsTest` | The exact secret selects its caller and a prefix, suffix or other case selects nothing; plain ids are folder names while empty, dotted, path-like or over-long ids are refused. |
| `A2APropertiesTest` | Auth required without a caller fails naming the callers file; auth off needs none; two callers with one secret are refused; a caller with a blank field fails naming it. |
| `AgentCardConfigurationTest` | JSON-RPC interface, streaming and the skill; with auth required, the `google` OpenID Connect scheme and the `caller` API key scheme in `X-Caller-Secret`, required together; nothing with auth off. |
| `CallerSecretFilterTest` | A registered secret passes with the caller attached to the request; a missing, wrong or differently cased secret, or the secret sent as the bearer token instead, is 401 with `WWW-Authenticate: X-Caller-Secret`; the card, health, the statements page, its API and the auth config are open. |
| `StatementsEndpointTest` | Over real HTTP with auth on, a temporary root and a test signing key: the page and `/api/auth/config` are open; the API without a token, with a non-bearer header, with a token for another client or with garbage is 401 with a problem document; an upload lands in the person's folder byte for byte under its content's download name, whatever it was called, is listed to them and not to another person; a path-traversal name lands in the person's folder only; the same month twice and non-PDF content are 400 problem documents naming the cause; an upload over the multipart limit is 413; an upload refused for non-PDF content or its size creates nothing; a listed file is deleted with 204 and deleting an unlisted name is a 400 naming it. |
| `CallContextsTest` | The SDK call context carries the headers (looked up without regard to case), the admitted caller, tenant, requested extensions and protocol version with an unauthenticated SDK user; no caller key without a caller; tenant path trimming. |
| `SseStreamWriterTest` | Each event is one frame with `data:` and `id:`; a client gone mid-stream and a frame that cannot be serialized both cancel the subscription and the event consumer and release the request thread; a cancel callback that throws still releases it. |
| `A2AEndpointTest` | Over real HTTP with an echo executor: the public card, `SendStreamingMessage` as SSE and `GetTask` of the streamed task as one JSON document, the stream closing after input-required, a non-JSON content type as -32005, an unknown method as -32601 with the request id, malformed JSON as -32700. |
| `AmexAgentApplicationTest` | The `e2e` context wires the card pipeline, the keyword classifier and the caller filter. |
| `TckProfileTest` | The `tck` profile wires `TckAgentExecutor`, has no caller filter, and answers a TCK scenario unauthenticated. |

## Integration

`A2ATransportIT` boots the whole agent under the `e2e` profile on fixed port 18020 (the card
advertises the URL the client must call), with the committed statement in one person's folder and
a test signing key standing in for Google, and drives it with the a2a-java client over JSON-RPC as
a registered caller forwarding that person's token:

- the card is served without credentials;
- a streaming task for 2026-10 completes with one artifact of 24 rows, and cancelling it
  afterwards is refused with `Task cannot be canceled`;
- a task for 2026-09 asks for input naming the person, asks again on `continue`, and cancels to
  `TASK_STATE_CANCELED`;
- a call without the person's token ends in `TASK_STATE_AUTH_REQUIRED` with the
  `sign-in-required` reason and no artifact;
- a wrong secret fails with HTTP 401;
- a malformed month (`09/2026`) fails with an error naming the month.

`ReplicasIT` starts two complete agents (ports 18030 and 18031) that share one Redis container
and one statements folder, standing in for two replicas behind the edge. A request for a month
sent to the first agent pauses because the statement is missing. Once the statement is loaded, a
`continue` sent to the second agent completes the task with all 24 rows, and the first agent then
reports the task as completed. If each agent kept its tasks in its own memory, that `continue`
would fail with `Task not found`.

`RedisTaskStoreIT` runs two `RedisTaskStore`s against one Redis container, standing in for two
replicas. A paused task saved by one store is read in full by the other, including the data
recorded with the pause, and when the second store saves it as completed, the first sees that.
An unknown task is absent and counts as neither active nor finalized. A listing includes both
stores' tasks and filters by context the way the SDK does. A deleted or expired task is gone for
both stores. A Redis that cannot be reached fails the store's construction with a message naming
`APP_TASKS_REDIS_URL`.

## Client-side validation

`scripts/validate/check_agent.py` uses the Python `a2a-sdk` client and PyJWT from a private venv
(`.validate-venv/`) against the packaged jar. `validate.sh` generates an RSA key pair for the run
with openssl, hands the agent the public half and a callers file written by `register-caller.sh`,
and the script signs the person's tokens with the private half. It prints one `PASS` line per
check and stops at the first failure:

1. the card advertises `monthly-transactions`;
2. the card advertises streaming;
3. the card declares the person's token as an OpenID Connect scheme;
4. the card declares the caller's secret as an API key in `X-Caller-Secret`;
5. 2026-10 completes;
6. 2026-10 streams 24 transactions;
7. every transaction carries a category;
8. 2026-09 asks for input;
9. the reason is `statement-missing`;
10. the request names the person it is for;
11. `continue` without the file asks again;
12. cancelling the waiting task succeeds;
13. cancelling the completed task is refused;
14. a call without the person's token ends in auth-required with `sign-in-required`;
15. a call with a token issued to another client ends the same way;
16. a wrong secret is 401;
17. the auth config names the page's client;
18. the statements API refuses a call without a token;
19. the statements API lists the person's own statement.

## Conformance

`./scripts/validate.sh --tck` clones or fast-forwards
[a2aproject/a2a-tck](https://github.com/a2aproject/a2a-tck) into `.tck/`, installs it into
`.tck/.venv`, starts the agent under the `tck` and `e2e` Spring profiles and runs
`run_tck.py --transport jsonrpc --level must`. The script fails on any MUST failure.

The `tck` profile (`application-tck.yml`) turns auth off, because the TCK sends no credentials (no
caller is then registered, no token read, and every request is for `anonymous`), and replaces
`TransactionsAgentExecutor` with `TckAgentExecutor`, a verbatim port of the SDK's own conformance
executor. The TCK sends scripted messages, keyed by message-id prefix, that expect
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
(Temurin) runs `scripts/test.sh all`, then every `scripts/tests/test_*.sh`, then Python 3.12
runs `scripts/validate.sh`. CI calls the
same scripts developers run, so the two cannot drift. The TCK is not part of CI; it runs locally
through `validate.sh --tck`.
