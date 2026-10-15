# CLAUDE.md

A2A server for one skill, `monthly-transactions`, over American Express Israel PDF statements.
Spring Boot 4 + the official a2a-java 1.4 server library with a hand-written Spring MVC transport
(`a2a/`); the card pipeline is `card/` (api → application → infra). README.md tells the story;
docs/a2a-contract.md is the wire contract the home finance agent's repo depends on. Do not change
the artifact schema or taxonomy without updating that file and the home finance agent.

## Commands (scripts/, each prints usage with --help)

- `init-env.sh` — create `.env`, mint `APP_A2A_TOKEN` (`--token` to share one, `--force` to
  overwrite)
- `start.sh [--host]` — Docker (default) or `mvn spring-boot:run`; agent on :8020
- `docker-helper.sh --stop|--logs [-e]|--ps`
- `test.sh [unit|it|all]` — `mvn test` / `mvn verify`; no Docker or API key needed
- `validate.sh [--tck]` — starts the packaged jar on :8021 against the sample under the `e2e`
  profile and proves it from the client side, then boots it on :8023 once per real provider with a
  placeholder key; `--tck` runs the official A2A TCK against a
  second agent on :8022 under the `tck` and `e2e` Spring profiles (scenario executor, auth off)

## Notes

- A2A payloads are Gson + protobuf JsonFormat via the SDK (`JSONRPCUtils`, `JsonUtil`); never
  route them through Jackson. Controllers take and return `String`.
- `META-INF/services/org.a2aproject.sdk.server.TransportMetadata` must list the JSON-RPC
  provider or the SDK's card validation throws at the first card fetch.
- Hand-written JSON-RPC calls need the `A2A-Version: 1.0` header, or the server answers
  VERSION_NOT_SUPPORTED.
- A run needs a real provider (`APP_LLM_PROVIDER` anthropic | openai), model and key. The keyword
  classifier exists only under the `e2e` Spring profile (validate.sh, the context tests, the IT and
  the home finance agent's e2e), which binds no `app.llm` and sets `spring.ai.model.chat: none`.
  Otherwise `spring.ai.model.chat` mirrors the provider so only one ChatModel loads; every non-chat
  Spring AI model type is `none` in application.yml.
- `A2ATransportIT` runs on fixed port 18020 because the card advertises the URL the client calls.
- `statements/` serves `/statements.html` and `/api/statements`: load a statement PDF into
  `APP_STATEMENTS_DIR`, list the files there and delete one, nothing more. Page and API need no
  token.
- The test statement is `src/test/resources/statements/2026-10.pdf`, a real statement anonymized
  by `scripts/anonymize-statement.py`. Every test runs on it; the Excel statement reader has no
  tests.
