# A2A contract: amex-agent

This file is the source of truth; the home finance agent's repo links here. It describes what the
agent puts on the wire: the Agent Card, the request and the events of one task, the artifact
schema, the category taxonomy, authentication, and the errors a client can receive.

**Contents:** [Transport](#transport) · [Agent Card](#agent-card) · [Request](#request) ·
[Task lifecycle](#task-lifecycle) · [Pauses and resume](#pauses-and-resume) ·
[Cancel](#cancel) · [Artifact schema](#artifact-schema) · [Taxonomy](#taxonomy) ·
[Authentication](#authentication) · [Errors](#errors)

## Transport

- A2A protocol version `1.0`, JSON-RPC 2.0 binding, one endpoint: `POST /` at the agent's base URL
  (`http://localhost:8020` by default).
- Every JSON-RPC request carries `Content-Type: application/json` and `A2A-Version: 1.0`. Without
  the version header the server treats the request as an older protocol version and answers
  `VERSION_NOT_SUPPORTED` (-32009).
- `SendStreamingMessage` and `SubscribeToTask` answer with `text/event-stream`: one SSE frame per
  event, each `data:` line holding a JSON-RPC response with the same `id` as the request. Every
  other method answers with one JSON document. On a streaming method, an error raised by the agent
  (an invalid month, a failed parse) arrives as a JSON-RPC error frame on the stream, after which
  the stream ends.
- Every JSON-RPC error travels with HTTP 200. The only non-200 answer on `POST /` is the 401 of the
  caller-secret check.
- Task states appear on the wire by their protocol enum names: `TASK_STATE_SUBMITTED`,
  `TASK_STATE_WORKING`, `TASK_STATE_INPUT_REQUIRED`, `TASK_STATE_AUTH_REQUIRED`,
  `TASK_STATE_COMPLETED`, `TASK_STATE_CANCELED`, `TASK_STATE_FAILED`.

## Agent Card

`GET /.well-known/agent-card.json`, unauthenticated, `Cache-Control: max-age=3600, public`.

| Field | Value |
|---|---|
| `name` | `American Express Israel card agent` |
| `supportedInterfaces` | one entry: `protocolBinding: "JSONRPC"`, `url` = `APP_AGENT_URL`, `protocolVersion: "1.0"` |
| `capabilities` | `streaming: true`, `pushNotifications: false` |
| `defaultInputModes` / `defaultOutputModes` | `application/json`, `text/plain` / `application/json` |
| `securitySchemes` | `google`: OpenID Connect scheme, `openIdConnectUrl` `https://accounts.google.com/.well-known/openid-configuration`, the signed-in person's Google ID token sent as the bearer token; `caller`: API key scheme, `location: "header"`, `name: "X-Caller-Secret"`, the caller's registered secret. Present only when `APP_A2A_AUTH_REQUIRED=true` |
| `securityRequirements` | one requirement naming both `google` and `caller`: every call carries both; present only when `APP_A2A_AUTH_REQUIRED=true` |

One skill:

| Field | Value |
|---|---|
| `id` | `monthly-transactions` |
| `name` | `Monthly card transactions` |
| `description` | Names the full [taxonomy](#taxonomy) and the request and resume shapes |
| `inputModes` / `outputModes` | `application/json`, `text/plain` / `application/json` |
| `tags` | `finance`, `credit-card`, `transactions` |
| `examples` | `{"month": "2026-10"}` |

A client calls the `url` in `supportedInterfaces`, not the address it fetched the card from, so
`APP_AGENT_URL` must be the address clients reach.

## Request

`SendStreamingMessage` with a user message whose first usable part names a billing month:

```http
POST / HTTP/1.1
Content-Type: application/json
A2A-Version: 1.0
Authorization: Bearer <the signed-in person's Google ID token>
X-Caller-Secret: <the caller's registered secret>

{"jsonrpc": "2.0", "id": 1, "method": "SendStreamingMessage",
 "params": {"message": {"messageId": "<client-chosen id>", "role": "ROLE_USER",
                        "parts": [{"data": {"month": "2026-10"}}]}}}
```

- A data part must be `{"month": "YYYY-MM"}` or `{"action": "continue"}`; any other data part is
  rejected.
- A text part containing a month in the `YYYY-MM` form is accepted as well
  (`"transactions for 2026-10"`).
- A month in any other shape (`2026-9`, `09/2026`, `2026-13`) is rejected with `InvalidParams`
  (-32602), never read as a nearby month. A message with no usable part is rejected the same way.

`month` is the **billing month**: the month the card is charged, as printed on the statement.

The blocking `SendMessage` method accepts the same message and returns the task in one document,
but it intermittently answers `InternalError` (-32603) `Timeout waiting for task <id>
consumption` for a task that did complete: the SDK's blocking wait races its own task storage
(see [Library behavior](architecture.md#library-behavior)). Clients use
`SendStreamingMessage`; a client that already sent `SendMessage` and got that error can read the
finished task with `GetTask`.

## Task lifecycle

A covered month produces these events, in order, on one stream:

1. `statusUpdate` `TASK_STATE_SUBMITTED`.
2. `statusUpdate` `TASK_STATE_WORKING` with an agent message holding the text part
   `Parsing statement for 2026-10`.
3. One or more `artifactUpdate` events, each carrying one data part (see
   [Artifact schema](#artifact-schema)) with up to 25 transactions. All chunks of a task share one
   `artifactId` (`transactions-<taskId>`) and the name `transactions`; `append` is false on the
   first chunk and true after it, and `lastChunk` is true on the final one. A statement with no rows
   still produces one chunk with an empty `transactions` list.
4. `statusUpdate` `TASK_STATE_COMPLETED`. The server then ends the stream.

One frame of the stream, abridged:

```text
data: {"jsonrpc":"2.0","id":1,"result":{"statusUpdate":{"taskId":"8cd6…","contextId":"45b9…",
       "status":{"state":"TASK_STATE_WORKING","message":{"role":"ROLE_AGENT",
       "parts":[{"text":"Parsing statement for 2026-10"}], …}}}}}
id: 1
```

Status updates carry no `final` flag. A client decides from the state: `TASK_STATE_COMPLETED`,
`TASK_STATE_CANCELED` and `TASK_STATE_FAILED` are terminal; `TASK_STATE_INPUT_REQUIRED` and
`TASK_STATE_AUTH_REQUIRED` are interrupted states, so the task stays alive, but the agent ends the
stream right after either event.

When parsing or categorizing fails (a malformed statement file, two files for one month, a model
provider error), the stream ends with a JSON-RPC error frame, `InternalError` (-32603), whose
message reads `Agent execution failed: <cause>`, and the task is left in `TASK_STATE_FAILED`.

## Pauses and resume

A task pauses in one of two interrupted states. Each pause's status message holds a text part for
the person and one data part that names the reason, the month and the person the task is for, so
the task itself remembers everything a resume needs.

**Missing statement.** When no statement file of the person covers the month, the task goes from
`TASK_STATE_WORKING` to `TASK_STATE_INPUT_REQUIRED`:

```json
{"role": "ROLE_AGENT", "parts": [
  {"text": "No statement covers 2026-09. Load it on the statements page and continue."},
  {"data": {"reason": "statement-missing", "month": "2026-09", "page": "/statements.html",
            "user": "103582367281946"}}
]}
```

- `reason`: `statement-missing`.
- `month`: the billing month, `YYYY-MM`, that no statement covers.
- `page`: optional. The path, on the agent's own base URL, of a page where a person loads the
  missing statement; a client shows it as a link. This agent always sends `/statements.html`; an
  agent with no such page omits the field.
- `user`: the Google account id (`sub`) of the person, from the token the caller forwarded.

**Sign-in required.** When the request carries no acceptable token for the person (none, expired,
badly signed, or issued to a client other than the caller's registered one), the task goes to
`TASK_STATE_AUTH_REQUIRED` right after `TASK_STATE_SUBMITTED`, before any work:

```json
{"role": "ROLE_AGENT", "parts": [
  {"text": "amex-agent could not verify who you are (the token has expired). Sign in again and continue."},
  {"data": {"reason": "sign-in-required", "month": "2026-09", "user": ""}}
]}
```

- `reason`: `sign-in-required`.
- `month`: the billing month the request was for.
- `user`: the person the task was already bound to, or `""` when no person has been verified yet.

The same answer, with a text naming the cause, is given to a `continue` whose token names another
Google account than the one the task was started for; the task serves only its own person.

After either pause the agent ends the stream, so a waiting task holds no connection. A client that
wants to keep listening calls `SubscribeToTask` with the task id; that stream opens with the task
snapshot and stays open until the task changes.

To resume, the client sends a new message on the same `taskId` and `contextId`, with the
person's current token and the caller's secret as on every call:

```json
{"jsonrpc": "2.0", "id": 2, "method": "SendStreamingMessage",
 "params": {"message": {"messageId": "<new id>", "role": "ROLE_USER",
                        "taskId": "<waiting task id>", "contextId": "<its context id>",
                        "parts": [{"data": {"action": "continue"}}]}}}
```

- The task must be in `TASK_STATE_INPUT_REQUIRED` or `TASK_STATE_AUTH_REQUIRED`; a `continue` for
  a task in any other state, or without a task, is rejected with `InvalidParams` (-32602).
- The agent reads the month and the person back from the task's own pause message (the current
  status message first, then the history newest first), so the client does not repeat the month.
- The agent verifies the token, then rescans the person's folder. The stream shows
  `TASK_STATE_WORKING` and then either the artifact chunks and `TASK_STATE_COMPLETED`, or
  `TASK_STATE_INPUT_REQUIRED` again with the same message; a token problem shows
  `TASK_STATE_AUTH_REQUIRED` instead.
- A message on a task that is already terminal is refused by the server with
  `UnsupportedOperation` (-32004).

## Cancel

`CancelTask` with `{"id": "<taskId>"}`:

- A task paused in `TASK_STATE_INPUT_REQUIRED` or `TASK_STATE_AUTH_REQUIRED` moves to
  `TASK_STATE_CANCELED`; the result is the task.
- A terminal task (`TASK_STATE_COMPLETED`, `TASK_STATE_CANCELED`, `TASK_STATE_FAILED`) is refused
  with `TaskNotCancelable` (-32002), message
  `Task cannot be canceled - current state: TASK_STATE_COMPLETED` (or the state it is in).

## Artifact schema

Each artifact chunk carries one data part:

```json
{
  "month": "2026-10",
  "currency": "ILS",
  "transactions": [
    {
      "id": "20261002-09",
      "transactionDate": "2026-09-04",
      "chargeDate": "2026-10-02",
      "merchant": "Cellcom",
      "rawMerchant": "סלקום ישראל בע\"מ",
      "amount": 46.21,
      "originalAmount": null,
      "originalCurrency": null,
      "standingOrder": true,
      "abroad": false,
      "installment": null,
      "category": "utilities",
      "confidence": "high"
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `month` | Billing month of the statement, `YYYY-MM`. |
| `currency` | Card currency, `ILS`. |
| `id` | Stable across runs, so a month fetched twice can be deduplicated: the charge date and the row's position for a PDF statement (`20261002-09`), the voucher number for an Excel export. |
| `transactionDate` | Purchase date, `YYYY-MM-DD`; usually in the month before the billing month. |
| `chargeDate` | Date the card is charged, `YYYY-MM-DD`. |
| `merchant` | Readable display name chosen by the categorizer; the raw string when it has none. |
| `rawMerchant` | The merchant string exactly as the statement prints it. |
| `amount` | Charged amount in the card currency. |
| `originalAmount`, `originalCurrency` | Purchase amount and ISO currency code (`USD`, `EUR` or `GBP`), set only when the purchase currency differs from the card currency; otherwise `null`. |
| `standingOrder` | The statement marks the row as a standing order. |
| `abroad` | The statement lists the purchase as made abroad. |
| `installment` | `{"number": 3, "total": 10}` for installment 3 of 10, otherwise `null`. |
| `category` | One value of the [taxonomy](#taxonomy). |
| `confidence` | How sure the agent is of `category`: `high`, `medium` or `low`. |

Every field comes from the statement without a model call except `merchant`, `category` and
`confidence`. The model's score stays inside the agent: from `APP_CATEGORIZER_HIGH_CONFIDENCE`
(default 0.85) up it is reported as `high`, from `APP_CATEGORIZER_MEDIUM_CONFIDENCE` (default 0.6)
up as `medium`, below that as `low`. A `low` row keeps the agent's best guess as its category, so
the caller can confirm or correct it. A model answer outside the taxonomy or the score range is
`"category": "uncategorized"` with `low` confidence.

The data part travels as a protobuf `Struct`, so the key order of an object is not preserved and
every number is a JSON double: `installment` arrives as `{"number": 3.0, "total": 10.0}`. Read
fields by name and parse numbers as numbers.

## Taxonomy

Fixed, and listed in the skill description:

`groceries`, `dining`, `transport`, `utilities`, `housing`, `health`, `insurance`, `education`,
`shopping`, `entertainment`, `travel`, `gifts`, `other`, `uncategorized`.

## Authentication

Every A2A call carries two credentials, and the card says so (see [Agent Card](#agent-card)):

- `X-Caller-Secret: <secret>` proves which registered caller sends the request. Callers are
  registered by the agent's operator in `allowed-callers.yaml` (`scripts/register-caller.sh`), each
  with a name, the secret it presents and the Google OAuth client id whose tokens it forwards. The
  secret is compared in constant time against every entry; a missing or unknown secret gets HTTP
  401 with `WWW-Authenticate: X-Caller-Secret` and an empty body, before any JSON-RPC handling.
- `Authorization: Bearer <Google ID token>` says which person the call is for: the token the
  person received when signing in to the caller's own page with Google, forwarded as received. The
  agent verifies it itself: RS256 signature against Google's published keys, issuer
  `https://accounts.google.com` (or `accounts.google.com`), expiry, and audience equal to the
  caller's registered client id. The token's `sub` is the person's id: their statements live in
  the folder of that name. A token that fails any check does not refuse the request; the task
  pauses in `TASK_STATE_AUTH_REQUIRED` (see [Pauses and resume](#pauses-and-resume)) so the caller
  can have the person sign in again and continue the same task.

The statements page's own API, `/api/statements`, takes the person's token alone, issued to this
agent's own client (`APP_AUTH_CLIENT_ID`); without an acceptable one it answers 401 with a problem
document. `GET /.well-known/agent-card.json`, `GET /actuator/health`, `/statements.html` and
`GET /api/auth/config` are open.

With `APP_A2A_AUTH_REQUIRED=false` neither credential is read, the card declares no security
scheme, and every request is for the one person `anonymous`. The conformance suite runs this way
because it sends no credentials.

A note on trust: amex-agent honors a Google token that was issued to a partner's client. That is
what two services trusting the same identity provider do under one roof; an agent operated by a
separate company would more likely issue tokens of its own after verifying the person (OAuth 2.0
token exchange) and advertise an OAuth 2.0 scheme instead.

## Errors

| Code | SDK error | When |
|---|---|---|
| -32700 | `JSONParseError` | The body is not valid JSON. |
| -32600 | `InvalidRequestError` | Valid JSON that is not a well-formed JSON-RPC request. |
| -32601 | `MethodNotFoundError` | An unknown method name. |
| -32602 | `InvalidParamsError` | A malformed month, an unusable message, or `continue` on a task that is not paused. |
| -32603 | `InternalError` | The agent failed while working on the task, or an unexpected server error. |
| -32001 | `TaskNotFoundError` | No task has the given id. |
| -32002 | `TaskNotCancelableError` | `CancelTask` on a terminal task. |
| -32004 | `UnsupportedOperationError` | A message on a terminal task. |
| -32005 | `ContentTypeNotSupportedError` | `POST /` with a content type other than `application/json`; the error carries no request id. |
| -32009 | `VersionNotSupportedError` | The `A2A-Version: 1.0` header is missing or names another version. |

Each error body is a JSON-RPC error object with `code`, `message` and a `data` list holding a
`google.rpc.ErrorInfo` whose `reason` names the error (for example `INVALID_PARAMS`).
