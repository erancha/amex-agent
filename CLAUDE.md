# CLAUDE.md

A2A server for one skill, `monthly-transactions`, over American Express Israel PDF statements.
Spring Boot 4 + the official a2a-java 1.4 server library with a hand-written Spring MVC transport
(`a2a/`); the card pipeline is `card/` (api → application → infra). README.md tells the story;
docs/a2a-contract.md describes how this agent implements the skill's contract, which the caller's
repo owns. Do not change the artifact schema or taxonomy: they are the contract's, not this agent's.

## Commands (scripts/, each prints usage with --help)

- `init-env.sh` — create `.env` (`--force` to overwrite); then set `APP_AUTH_CLIENT_ID` and
  `APP_LLM_API_KEY`
- `register-caller.sh --name N --client-id C [--secret S] [--file F]` — register or replace an A2A
  caller in `allowed-callers.yaml` (gitignored); mints and prints the secret unless one is given
- `start.sh [--host]` — Docker (default: Redis, the compose file's replicas, nginx edge on :8020) or
  `mvn spring-boot:run` (one process, tasks in memory); stops before launching when access is
  required and no callers file exists
- `docker-helper.sh --stop [--volumes]|--logs [-e] [service...]|--ps`
- `test.sh [unit|it|all]` — `mvn test` / `mvn verify`; needs no API key, but the Redis integration
  tests need Docker
- `validate.sh [--tck]` — starts the packaged jar on :8021 against the sample under the `e2e`
  profile with a throwaway signing key (openssl) in place of Google and proves it from the client
  side as a registered caller, then boots it on :8023 once per real provider with a placeholder
  key; `--tck` runs the official A2A TCK against a second agent on :8022 under the `tck` and `e2e`
  Spring profiles (scenario executor, auth off)

## Notes

- A2A payloads are Gson + protobuf JsonFormat via the SDK (`JSONRPCUtils`, `JsonUtil`); never
  route them through Jackson. Controllers take and return `String`.
- `META-INF/services/org.a2aproject.sdk.server.TransportMetadata` must list the JSON-RPC
  provider or the SDK's card validation throws at the first card fetch.
- Hand-written JSON-RPC calls need the `A2A-Version: 1.0` header, or the server answers
  VERSION_NOT_SUPPORTED.
- A run needs `APP_LLM_MODEL` as `provider:model` (anthropic | openai) and `APP_LLM_API_KEY`.
  `LlmModelEnvironmentPostProcessor` (spring.factories) splits it before auto-configuration so
  `spring.ai.model.chat` is the provider and only one ChatModel loads; every non-chat Spring AI
  model type is `none` in application.yml. The keyword classifier exists only under the `e2e` Spring
  profile (validate.sh, the context tests, the IT and a caller's e2e), which binds no `app.llm` and
  sets `spring.ai.model.chat: none`.
- Access: every A2A call carries `Authorization: Bearer <person's Google ID token>` and
  `X-Caller-Secret`; `CallerSecretFilter` admits the caller, the executor verifies the token against
  that caller's registered `client_id` (`auth/`, nimbus-jose-jwt) and pauses the task in
  `TASK_STATE_AUTH_REQUIRED` (`sign-in-required`) when it cannot. Tests never reach Google:
  `auth/TestTokens` signs with its own key and `TestTokens.register` hands a Spring test the whole
  auth setup.
- `A2ATransportIT` runs on fixed port 18020 because the card advertises the URL the client calls.
- The replicas share their A2A tasks through `RedisTaskStore` when `APP_TASKS_REDIS_URL` is set,
  so any replica can continue a paused task; a working task runs in the replica streaming it, and
  the categorizer cache is per process. docs/architecture/scaling.md states what the replicas must
  guarantee; shortfalls are GitHub issues labelled `scaling-safety` or `scaling-out`.
- `statements/` serves `/statements.html`, `/api/auth/config` and `/api/statements`: sign in with
  the agent's own Google client, load a statement PDF into the person's folder under
  `APP_STATEMENTS_DIR` (`<root>/<sub>/`), list their files and delete one, nothing more. The API
  takes the person's token; page and config are open.
- The test statement is `src/test/resources/statements/2026-10.pdf`, a real statement anonymized
  by `scripts/anonymize-statement.py`. Every test runs on it.
