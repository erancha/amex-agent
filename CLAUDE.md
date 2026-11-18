# CLAUDE.md

A2A server for one skill, `monthly-transactions`, over American Express Israel PDF statements.
Spring Boot 4 with the official a2a-java 1.4 server library behind a hand-written Spring MVC
transport (`a2a/`); the card pipeline is `card/` (api → application → infra).
docs/architecture.md describes the layout; docs/a2a-contract.md describes how this agent
implements the skill's contract, which the caller's repo owns. Do not change the artifact schema
or taxonomy: they are the contract's, not this agent's.

## Operations scripts (scripts/)

Every script sources `scripts/common.sh`, which exports the three settings files under
`scripts/lib/` before it calls Compose: the committed `stack-params.sh`, and the gitignored
`params.sh` and `secrets.sh`, each beside an `.example.sh` that documents its keys. A bare
`docker compose` call therefore has no settings. `.githooks/pre-commit` refuses a commit that
stages one of the gitignored files, a value of `secrets.sh`, or an address of `params.sh` other
than the license contact (`PUBLISHED_SETTINGS` of `lib/settings-guard.sh`).

- Compose hands each replica only the keys `docker-compose.yml` lists, and fixes the
  in-container port (8020), statements mount and Redis URL itself. The gitignored
  `allowed-callers.yaml` reaches the replicas as the Compose secret `allowed-callers`, whose
  content `compose()` exports as `APP_A2A_CALLERS_YAML`; Compose writes it into the container
  through the Docker API, so no file is bind-mounted but the local statements folder.
- `--ec2` runs Compose here against the instance's Docker daemon over an SSH-forwarded socket, so a
  bind mount would name a path that does not exist there: `docker-compose.ec2.yml` swaps the
  statements folder for the `statements` volume, and the edge's image (`nginx/Dockerfile`) has
  `nginx/default.conf` built in. Every image this repo builds is tagged `:ec2` there, which is how
  `ec2_built_images` finds what to load. The stack hosts the instance or is a guest on another
  repo's (`EC2_HOST_STACK` of `params.sh`); `docker-compose.ec2.yml` sets `APP_AGENT_URL` to the
  stack's CloudFront address.
- `lib/ec2.sh`, `lib/settings-guard.sh`, `template-ec2.yaml` and `.githooks/pre-commit` are
  byte-identical copies of loan-simulator's; change them there and copy them back.
- Before any AWS work, read this account's inventory, the
  `aws-account-inventory-<account ID>.md` under `/mnt/c/Projects/-- docs --/` that lists the
  Docker Compose repos: it holds the account's state, its ground rules for credentials and
  changes, and the decisions made.
- A2A payloads are Gson + protobuf JsonFormat via the SDK (`JSONRPCUtils`, `JsonUtil`); never
  route them through Jackson. Controllers take and return `String`.
- `META-INF/services/org.a2aproject.sdk.server.TransportMetadata` must list the JSON-RPC
  provider or the SDK's card validation throws at the first card fetch.
- Hand-written JSON-RPC calls need the `A2A-Version: 1.0` header, or the server answers
  VERSION_NOT_SUPPORTED.
- A run needs `APP_LLM_MODEL` as `provider:model` (anthropic | openai) and `APP_LLM_API_KEY`.
  `LlmModelEnvironmentPostProcessor` (spring.factories) splits it before auto-configuration so
  `spring.ai.model.chat` is the provider and only one ChatModel loads; every non-chat Spring AI
  model type is `none` in application.yml. The keyword classifier exists only under the `e2e`
  Spring profile (tools/validate.sh, the context tests, the IT and a caller's e2e), which binds no
  `app.llm` and sets `spring.ai.model.chat: none`.
- Tests never reach Google: `auth/TestTokens` signs with its own key and `TestTokens.register`
  hands a Spring test the whole auth setup.
- `A2ATransportIT` runs on fixed port 18020 because the card advertises the URL the client calls.
- The replicas share their A2A tasks through `RedisTaskStore` when `APP_TASKS_REDIS_URL` is set; a
  working task runs in the replica streaming it, under a task lease (`TaskLease`, taken by
  `LeasedAgentExecutor`, and by `A2AEndpoint` for a continue) that `LeaseSweep` checks; the
  categorizer cache is per process. docs/architecture/scaling.md states what the replicas must
  guarantee; shortfalls are GitHub issues labelled `scaling-safety` or `scaling-out`.
- The test statement is `src/test/resources/statements/2026-10.pdf`, a real statement anonymized
  by `scripts/tools/anonymize-statement.py`. Every test runs on it.
- `test.sh [all|unit|it]` — the whole quality gate: `bash -n` on every script and the hook, the
  shell tests under `scripts/tests/`, then `mvn test` (`unit`) or `mvn verify`; no API key, but
  the Redis ITs need Docker.
- `init-env.sh [--force]` — creates each missing gitignored `scripts/lib/secrets.sh`,
  `params.sh` and `aws-config.sh` from its `.example.sh`; an existing file is kept unless
  `--force` is given.
- `deploy.sh` — runs `init-env.sh` first and stops on a list of every required setting still
  blank or a missing caller, then builds and runs Redis, two replicas and the edge on :8020 and
  returns once the agent answers; `--ec2` does it on an EC2 instance behind this
  repo's CloudFront distribution, in the account of the gitignored `lib/aws-config.sh`.
- `docker-helper.sh [--ec2]` — `--stop [--volumes]`, `--logs [-e] [service...]`, `--ps`;
  `--ec2 --delete-stack` deletes the guest stack.
- `lib/ec2.sh` — the EC2 target: the stack's deploy, SSH with an Instance Connect key, Compose
  against the instance's daemon. `lib/settings-guard.sh` — the checks behind the pre-commit
  hook.
- `tools/register-caller.sh` — registers or replaces an A2A caller in `allowed-callers.yaml`.
- `tools/validate.sh [--tck]` — proves the packaged jar from the client side; `--tck` adds the
  A2A TCK. `tools/anonymize-statement.py` — anonymizes a statement PDF for a test fixture.
- An agent on the host (`mvn spring-boot:run` with the settings files sourced) is documented in
  docs/development.md, not a script.

