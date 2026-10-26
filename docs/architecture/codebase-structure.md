# Codebase structure

Packages under `src/main/java/com/erancha/amexagent/`:

| Package | Classes | Responsibility |
|---|---|---|
| `a2a` | `A2AEndpoint` | `POST /` for every JSON-RPC method: parses the body with the SDK's `JSONRPCUtils`, dispatches over the SDK's sealed request types with an exhaustive `switch`, writes one JSON document or hands a stream to `SseStreamWriter`. A non-JSON content type gets `ContentTypeNotSupportedError` (-32005). |
| `a2a` | `SseStreamWriter` | Subscribes to the handler's event publisher on the request thread, writes and flushes one SSE frame per event, and releases the thread on any failure. |
| `a2a` | `AgentCardEndpoint`, `AgentCardConfiguration` | Serve the public card at `/.well-known/agent-card.json`, built from `app.agent.*` and `app.a2a.auth.*`; with auth on it requires the person's Google ID token (OpenID Connect scheme) and the caller's secret (API key scheme) together. |
| `a2a` | `A2AServerConfiguration` | Wires the SDK as Spring beans: the task store (the SDK's in-memory one, or `RedisTaskStore` when `APP_TASKS_REDIS_URL` is set), queue manager, main event bus and its processor, the two a2a thread pools, the caller-secret filter, the user resolver and the `JSONRPCHandler`. |
| `a2a` | `RedisTaskStore` | Implements the SDK's `TaskStore` on Redis. Each task is one key holding the task's wire JSON, which expires `APP_TASKS_TTL` after the last save. A set of task ids supports listing; the SDK's own in-memory store does the listing's filtering and paging. |
| `a2a` | `InterruptedStateStreamHook` | A `TaskStreamLifecycleHook` that closes a task's open streams once the task enters an interrupted state such as input-required. |
| `a2a` | `CallerSecretFilter` | Servlet filter admitting a request whose `X-Caller-Secret` names a registered caller, attached to the request for the executor; registered only when auth is required. |
| `a2a` | `CallContexts`, `CallContextUsers`, `JsonRpcResponses` | Build the SDK's per-request context from the servlet request (headers, admitted caller, tenant); resolve the person of an A2A request from that context; serialize non-streaming responses to wire JSON. |
| `auth` | `GoogleTokenVerifier`, `VerifiedUser`, `InvalidTokenException` | Verify a Google ID token (signature against the issuer's keys, issuer, expiry, audience) into the person it names, or a refusal in words for that person. |
| `auth` | `Users`, `UserResolver`, `Caller`, `Callers`, `UserIds` | Which client id a request's token is checked against (the page's own, or the admitted caller's), the registered callers looked up by secret in constant time, and the rule that admits a Google account id as a folder name. |
| `auth` | `AuthProperties`, `AuthConfiguration` | The `app.auth.*` settings and the beans built from them and from the callers list. |
| `a2a` | `SpringJsonRpcTransportMetadata` | ServiceLoader provider (`META-INF/services/org.a2aproject.sdk.server.TransportMetadata`) so the SDK's card validation accepts the JSONRPC interface. |
| `a2a` | `A2AProperties` | The `app.*` configuration tree apart from `app.llm` and `app.auth`; its record constructors reject invalid required settings at startup. |
| `card/api` | `Transaction`, `Installment`, `Statement`, `Category`, `Confidence`, `MerchantCategory`, `StatementSource`, `MerchantClassifier` | Domain records, the taxonomy, and the two ports the pipeline depends on; every statement port takes the person's id. |
| `card/application` | `TransactionsAgentExecutor` | The SDK `AgentExecutor` for the skill: names the person from the forwarded token, then drives submitted, working, artifact chunks, completed, or one of the two pauses (input-required, auth-required) and the `continue` resume by the same person. |
| `card/application` | `MonthRequest`, `TransactionsService`, `ArtifactMapper` | Validate the request, run the use case, shape transactions into 25-row artifact data parts. |
| `card/application` | `MerchantCategorizer`, `MerchantNormalizer` | Cached, batched categorization keyed by a normalized merchant string. |
| `card/infra` | `StatementFolders`, `StatementFolder`, `StatementParser`, `PdfTextLines`, `PdfStatementParser`, `PdfStatementLayout`, `StatementCurrency`, `StatementFormatException` | One folder per person under the statements root; index a folder's statement files by billing month, parse the Amex Israel PDF statement, and load a checked upload into the person's folder. |
| `card/infra` | `SpringAiMerchantClassifier`, `KeywordMerchantClassifier`, `LlmConfiguration`, `LlmProperties`, `LlmModelEnvironmentPostProcessor` | The two classifier implementations, the choice between them by Spring profile, the `app.llm` settings, and the split of `APP_LLM_MODEL` into provider and model before auto-configuration. |
| `card/api` | `StatementStore`, `StoredStatement`, `StatementRejectedException` | The port the statements page loads and deletes files through, and its refusal. |
| `card` | `CardConfiguration` | Assembles the pipeline; its executor bean is inactive under the `tck` profile. `StatementFolders` is both the `StatementSource` and the `StatementStore`. |
| `statements` | `StatementsEndpoint`, `AuthConfigEndpoint`, `static/statements.html` | The statements page and its API: sign in with Google, list the person's loaded files, load one more, delete one; `/api/auth/config` tells the page which Google client to sign in with. |
| `tck` | `TckAgentExecutor` | The conformance suite's scenario executor, active only under the `tck` profile. |
