# Development & deployment

This document details the local setup, the tests and the scripts behind the
[README](../README.md)'s Getting started and Scripts sections; [Architecture](architecture.md)
describes what the scripts run. Each script's header comment is its full documentation, and
`--help` prints it.

**Contents:** [Working copy location](#working-copy-location) · [Configuration](#configuration) ·
[Backend tests](#backend-tests) · [Deployment scripts (scripts/)](#deployment-scripts-scripts) ·
[Registering a caller](#registering-a-caller) · [Statements](#statements) ·
[Running the agent on the host](#running-the-agent-on-the-host) · [Gotchas](#gotchas)

## Working copy location

Keep the checkout on the WSL ext4 filesystem (`~/projects/java/amex-agent`) rather than under
`/mnt/c`. WSL reaches Windows drives through a file-sharing protocol that makes every file open a
round trip to the Windows side, and the Maven builds and the test runs open thousands of files.

## Configuration

`./scripts/init-env.sh` creates the gitignored `secrets.sh`, `params.sh` and `aws-config.sh`
under `scripts/lib/` from [secrets.example.sh](../scripts/lib/secrets.example.sh),
[params.example.sh](../scripts/lib/params.example.sh) and
[aws-config.example.sh](../scripts/lib/aws-config.example.sh), keeping a file that already
exists; each setting's comment says what it holds and whether it is required. `deploy.sh` runs
it first on every run, and stops with a list of every setting it needs that is still blank.

- `secrets.sh` — `APP_LLM_API_KEY`, the Anthropic or OpenAI key of the model `params.sh` names.
- `params.sh` — this deployment's own settings that are no secret: `APP_AUTH_CLIENT_ID`, the
  agent's own Google OAuth client ([Sign-in](sign-in.md) sets it up), `APP_LLM_MODEL` as
  `provider:model` (Anthropic's `claude-haiku-5-5` as shipped), and the license contact.
- `aws-config.sh` — the access key pair and region of the AWS account `deploy.sh --ec2`
  deploys to; a machine that deploys only locally leaves it unfilled.

Every other setting is committed in [stack-params.sh](../scripts/lib/stack-params.sh): the edge's
port, the URL the Agent Card advertises, the statements folder, the EC2 stack's names and port,
and every optional setting, commented out at its default. The registered callers are a file of
their own, the gitignored `allowed-callers.yaml` that `tools/register-caller.sh` writes. An edit
to any of these files takes a `deploy.sh` run.

A script that loads a settings file first stops if git would track it, or a tracked file holds
one of its secrets or email addresses, the license contact of `params.sh` excepted. It also
sets `core.hooksPath` to `.githooks`, whose `pre-commit` hook makes the same check on every
commit's staged changes.

The scripts export the three files into their shell before calling Compose, so a bare
`docker compose` call has no settings. Compose hands each replica only the keys
`docker-compose.yml` lists for it, and sets the in-container values itself: port 8020, the
statements mount at `/statements`, the stack's Redis, and the callers registry as a secret.

## Backend tests

[`test.sh`](../scripts/test.sh) is the whole quality gate. It first runs `bash -n` on every
script and the pre-commit hook and the shell tests under `scripts/tests/`, then the unit tests
(`unit`, `mvn test`) or the unit and integration tests (`it`, `all` or no argument,
`mvn verify`). No test needs a model API key: the tests that boot the agent run under the `e2e`
Spring profile, which categorizes with a built-in keyword table, and every test signs its own
tokens rather than reach Google. The Redis integration tests start Redis in a container, so they
need Docker. The scripts find a Java 21 on their own when the host default is newer
(`ensure_java21` in `scripts/common.sh`).

[`tools/validate.sh`](../scripts/tools/validate.sh) runs the packaged jar on port 8021 and proves
it from the client side as a registered caller; `--tck` also runs the official A2A conformance
suite. It needs Python 3.11+ and openssl besides Java. The GitHub Actions workflow
`.github/workflows/ci.yml` runs `test.sh all` and then `tools/validate.sh`.
[Testing](testing.md) states what each layer covers and the latest conformance result.

## Deployment scripts (`scripts/`)

Docker runs the stack; `deploy.sh` and `docker-helper.sh` stop when it is not reachable.

- [`deploy.sh`](../scripts/deploy.sh) — builds the images, starts Redis, the two replicas and the
  nginx edge in Docker, and returns once the edge answers the agent's health check on
  http://localhost:8020. It stops early while no caller is registered. It ends with the values a
  caller's operator registers this agent with: its name, the advertised A2A URL and the
  statements page's URL.
- `deploy.sh --ec2` — deploys to an EC2 instance in the account of `aws-config.sh`, and needs the
  AWS CLI, `ssh` and `curl`. The stack is a host, with an instance of its own, while
  `EC2_HOST_STACK` of `params.sh` is blank. Set to another Docker Compose repo's host stack, it is a
  guest on that instance, which is deployed first from its repo; `aws-config.sh` then names that
  stack's account and region, since a guest's stacks import the host stack's exports
  ([ADR 0007](adr/0007-any-docker-repo-can-host-the-instance.md)).
  It creates or updates this repo's stack of [`template-ec2.yaml`](../scripts/template-ec2.yaml) (a
  port on the instance and a CloudFront
  distribution), builds the images here for ARM, loads them into the instance's Docker daemon over
  SSH and starts the stack there. It prints the CloudFront address, which the card advertises and
  which is the statements page's address, to hand to the callers' operators and to register with
  Google ([Sign-in](sign-in.md#setting-up-google)). The git branch selects the stack: `main` deploys
  `amex-agent`, any other branch `amex-agent-fb`, a guest on the host's `-fb` instance; an exported
  `STACK_NAME` wins. Every deploy, host or guest, also makes sure the instance has a 1 GB swap file
  (`prepare_ec2_host` in `lib/ec2.sh`), so a burst beyond the instance's 2 GB of memory slows the
  stacks instead of killing a container.
- [`docker-helper.sh`](../scripts/docker-helper.sh) — `--stop [--volumes]`,
  `--logs [-e] [service...]` or `--ps` for the stack; `--volumes` also drops the paused tasks
  held in the Redis volume, and on EC2 the statements volume. With `--ec2` as its first argument
  it acts on the instance, and `--ec2 --delete-stack` deletes the guest stack.
- [`test.sh`](../scripts/test.sh) — the quality gate, above.
- [`init-env.sh`](../scripts/init-env.sh) — creates the settings files, above; `--force`
  replaces existing ones, hand-filled keys included.
- `tools/` — hands-on tools: [`register-caller.sh`](../scripts/tools/register-caller.sh)
  (registers an A2A caller, below), [`validate.sh`](../scripts/tools/validate.sh) (the
  client-side proof and the conformance suite, above) and
  [`anonymize-statement.py`](../scripts/tools/anonymize-statement.py) (copies a statement PDF
  with the holder's private details replaced, to use as a test fixture).
  [`tools/README.md`](../scripts/tools/README.md) lists them.

`scripts/common.sh` holds what the scripts share: the repo paths, the help printer, the loader
of the settings files, the callers-file check, Compose access, the Java 21 lookup, the health
wait, the hand-over lines and the venv of `tools/validate.sh`. `lib/settings-guard.sh` keeps the
gitignored files' values out of git, and `lib/ec2.sh` holds the EC2 target: the stack's deploy,
the SSH connection with an EC2 Instance Connect key, and Compose pointed at the instance's Docker
daemon. Both, like `template-ec2.yaml` and `.githooks/pre-commit`, are byte-identical in every
Docker Compose repo.

`docker-helper.sh --ec2 --delete-stack` deletes the guest stack after asking for its name: its
port on the instance and its distribution. The containers and volumes stay on the instance until
`docker-helper.sh --ec2 --stop [--volumes]` removes them, so run that first.

The IAM user of `aws-config.sh` needs to create and delete the stack's resources
(CloudFormation, security group rules, CloudFront distributions), to update the host stack's SSH
rule, and `ec2-instance-connect:SendSSHPublicKey` for the SSH connection. No IAM user has these
by default; an administrator has them all.

## Registering a caller

A program that calls the A2A skill is registered by its name and the Google client id of its own
sign-in, whose tokens it forwards; its operator hands you that id. While access is required the
agent refuses to start without a caller, and `deploy.sh` stops early to say so:

```bash
./scripts/tools/register-caller.sh --name <caller> --client-id <the caller's client id>
```

The registration prints the values the caller's operator records: this agent's name, its A2A
URL, its statements page URL and the secret, shown once. Hand them over. The same registry
serves both targets, and a caller of the EC2 stack receives the addresses `deploy.sh --ec2`
prints. A caller calls the URL the card advertises, `APP_AGENT_URL`: on this machine
`http://host.docker.internal:8020`, which a caller running in Docker here reaches, since
`localhost` there is the caller's own container.

## Statements

Download each monthly statement PDF from the Amex site and load it on the statements page,
http://localhost:8020/statements.html on this machine or the CloudFront address on EC2: sign in
with your Google account, and the page lists the statements you loaded, deletes one on request
and stores each upload under its billing month whatever the file is called, in a folder of your
own. On this machine that folder is under `statements/`, which git ignores; on the EC2 instance
it is in the `statements` volume, and the page is the way to load it. The site names a statement
after its billing month, the month the card is charged: `2026-10.pdf` holds the September
purchases charged on 2 October 2026, and answers a request for `2026-10`. The page only loads
files; everything about the month's spending happens in the caller.

## Running the agent on the host

For fast restarts, run one JVM with `mvn spring-boot:run` instead of the stack, from the repo
root (the statements folder and the callers file are relative to it). Spring reads its settings
from the environment, so source the three settings files first. The JVM listens on `SERVER_PORT`
(8020) itself, so stop the stack before, and keeps its tasks in memory:

```bash
./scripts/docker-helper.sh --stop
(source scripts/common.sh && load_settings && ensure_java21 && mvn -q spring-boot:run)
```

## Gotchas

- The agent reads `allowed-callers.yaml` at startup, so registering or removing a caller takes a
  `deploy.sh` run.
- `A2ATransportIT` runs on the fixed port 18020, because the card advertises the URL the client
  calls.
- A hand-written JSON-RPC call needs the `A2A-Version: 1.0` header, or the server answers
  `VERSION_NOT_SUPPORTED`.
