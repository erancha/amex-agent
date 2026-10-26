# Configuration

Every runtime setting in `application.yml` is `${ENV:default}`; secrets, the provider and the
statements folder have no default. [.env.example](../../.env.example) explains each one. The rest is
fixed in the file: `app.agent.version` is the Maven project version stamped at build time, the
`spring.ai.model.*` switches other than `chat` keep the unused model types off,
`spring.ai.anthropic.chat.options.max-tokens` is 16384 (see [Providers](categorizer.md#providers)), the actuator
exposes `health` only, `spring.servlet.multipart.*` caps a statements-page upload at 5 MB, and
PDFBox's font warning logger is held at `ERROR`.

| Key | Env | Default | Meaning |
|---|---|---|---|
| `server.port` | `SERVER_PORT` | 8020 | Port the agent listens on. Under Docker only the host port; the container listens on 8020. |
| `app.agent.url` | `APP_AGENT_URL` | `http://localhost:<SERVER_PORT>` | URL published in the card and called by clients: the one handed to callers' operators (`http://host.docker.internal:8020` for a caller in Docker on this machine). |
| `app.statements.dir` | `APP_STATEMENTS_DIR` | none, required | Folder of downloaded statements. |
| `app.a2a.auth.required` | `APP_A2A_AUTH_REQUIRED` | true | False removes the filter, the token checks and the card's security schemes; everyone is then `anonymous`. |
| `app.a2a.allowed_callers` | file named by `APP_A2A_CALLERS_FILE` | `./allowed-callers.yaml` | The registered callers (`name`, `shared_secret`, `client_id`), imported through `spring.config.import`; at least one is required when auth is required. |
| `app.auth.client-id` | `APP_AUTH_CLIENT_ID` | none | This agent's own Google OAuth client id; the statements page signs in with it. Required when auth is required. |
| `app.auth.issuer` | `APP_AUTH_ISSUER` | `https://accounts.google.com` | Who issues the tokens. |
| `app.auth.jwks-url` | `APP_AUTH_JWKS_URL` | Google's JWKS URL | Where the issuer's public keys are fetched from and cached. |
| `app.auth.public-key-file` | `APP_AUTH_PUBLIC_KEY_FILE` | none | Test only: a PEM public key that replaces the JWKS fetch. |
| `app.tasks.redis-url` | `APP_TASKS_REDIS_URL` | none | The Redis that holds the A2A tasks; replicas given the same URL share their tasks. When unset, tasks stay in the process's memory. The Docker stack sets it. |
| `app.tasks.ttl` | `APP_TASKS_TTL` | `30d` | How long Redis keeps a task after its last change. |
| `app.llm.model` | `APP_LLM_MODEL` | none, required | `anthropic:<model>` or `openai:<model>`; the provider half selects the ChatModel. |
| `app.categorizer.high-confidence` | `APP_CATEGORIZER_HIGH_CONFIDENCE` | 0.85 | From this model score up, a row's confidence is `high`. |
| `app.categorizer.medium-confidence` | `APP_CATEGORIZER_MEDIUM_CONFIDENCE` | 0.6 | From this score up it is `medium`; below, `low`. |
| `app.categorizer.batch-size` | `APP_CATEGORIZER_BATCH_SIZE` | 40 | Unseen merchants per classifier call. |
| `app.llm.api-key` | `APP_LLM_API_KEY` | none, required | The API key of that provider; both `spring.ai.*.api-key` read it. |
| `logging.level.com.erancha` | `LOG_LEVEL` | INFO | Agent log level. |
| `logging.level.org.a2aproject.sdk` | `A2A_LOG_LEVEL` | WARN | SDK log level. |

Startup fails, naming the variable, when a required setting is wrong: the `A2AProperties` record
constructors reject an empty callers list, an entry with a blank field and two entries sharing a
secret while auth is required, `Users` rejects a blank `APP_AUTH_CLIENT_ID` while auth is required,
`LlmProperties` rejects an `APP_LLM_MODEL` that is blank, not `provider:model`, or names a provider
outside `anthropic | openai`, and `LlmConfiguration` rejects a blank `APP_LLM_API_KEY`.
`StatementFolders` refuses to start on a statements path that is not a directory, naming the path.

The `tck` Spring profile (`application-tck.yml`) turns auth off and swaps the executor; see
[testing.md](../testing.md#conformance). The `e2e` profile (`application-e2e.yml`) is for end-to-end
tests only: it replaces the language model with the keyword table, so it needs no provider, model or
key; see [Providers](categorizer.md#providers).
