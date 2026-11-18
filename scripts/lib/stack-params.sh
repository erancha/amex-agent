#!/usr/bin/env bash
# Settings of the Compose stack that are neither secret nor private, committed so every checkout
# runs the same stack. Sourced by scripts/common.sh, never executed; an agent started on the host
# reads them from its environment once the files are sourced (docs/development.md), so every
# setting is one plain export line with a quoted value, which may name a key set above it as
# ${KEY}. Secrets are in secrets.sh and this deployment's own settings in params.sh, both
# gitignored.
#
# Compose hands each container only the keys it reads, listed per service in docker-compose.yml.
# A commented-out line shows an optional key at its default; uncomment it to set the key. Every
# setting is read once, at startup.

# ── Ports ────────────────────────────────────────────────────────────────────────
# Read by docker-compose.yml only: the port the edge is published on, on this machine's loopback
# interface; the statements page and every A2A call arrive there. Inside the Compose network the
# edge and the replicas always listen on 8020. It must be free on the host.
export EDGE_HOST_PORT="8020"
# The port an agent started on the host listens on (default 8020); compose sets 8020 in the
# containers.
# export SERVER_PORT="8020"

# ── EC2 ──────────────────────────────────────────────────────────────────────────
# Read by scripts/lib/ec2.sh only, for deploy.sh --ec2. The stack's name on main; any other
# branch deploys EC2_STACK-fb.
export EC2_STACK="amex-agent"
# Used only when the stack is a host (EC2_HOST_STACK of params.sh blank): the instance's ARM
# type, sized for every stack it runs; a change stops and starts the same instance.
export EC2_INSTANCE_TYPE="t4g.small"
# The port CloudFront reaches this stack at on the instance; every stack on the instance has its
# own.
export EC2_ORIGIN_PORT="${EDGE_HOST_PORT}"

# ── Agent ────────────────────────────────────────────────────────────────────────
# The URL callers reach the agent at, published in the Agent Card: an A2A client calls this URL,
# not the one it fetched the card from. A caller running in Docker on this machine reaches the
# edge as host.docker.internal; deploy.sh --ec2 replaces it with the stack's CloudFront address.
export APP_AGENT_URL="http://host.docker.internal:${EDGE_HOST_PORT}"
# Root of the statement folders, one sub-folder per person named by their Google account id,
# holding the monthly statements downloaded from the card site (<YYYY>-<MM>.pdf). It starts
# empty; the statements page (/statements.html) loads PDFs into the signed-in person's folder.
# Compose mounts it into the replicas at /statements; on EC2 a named volume takes its place.
export APP_STATEMENTS_DIR="./statements"

# ── Access ───────────────────────────────────────────────────────────────────────
# Require credentials (default true): every A2A call carries the signed-in person's Google ID
# token as the bearer token and a registered caller's secret in X-Caller-Secret, and the
# statements API needs the person's token. The card, /actuator/health, the statements page and
# /api/auth/config are always open. tools/validate.sh --tck runs with it off; then there is one
# person, "anonymous", and no token or secret is read.
# export APP_A2A_AUTH_REQUIRED="true"
# The file registering the A2A callers, written by tools/register-caller.sh: for each caller its
# name, the secret it presents and the Google OAuth client id whose tokens it forwards. Compose
# hands its content to the replicas as a secret. While access is required, startup refuses an
# empty file, a caller with a blank field and two callers sharing a secret.
# export APP_A2A_CALLERS_FILE="./allowed-callers.yaml"
# Who issues the tokens and where its public keys are published. The defaults are Google's; both
# are read only when a token is verified.
# export APP_AUTH_ISSUER="https://accounts.google.com"
# export APP_AUTH_JWKS_URL="https://www.googleapis.com/oauth2/v3/certs"
# Tests only: a PEM public key that replaces APP_AUTH_JWKS_URL, so tests can sign their own
# tokens.
# export APP_AUTH_PUBLIC_KEY_FILE=""

# ── Categorization ───────────────────────────────────────────────────────────────
# The model's score (0 to 1) from which a row's confidence is reported as "high" and as
# "medium"; below the medium one it is "low".
# export APP_CATEGORIZER_HIGH_CONFIDENCE="0.85"
# export APP_CATEGORIZER_MEDIUM_CONFIDENCE="0.6"
# How many not-yet-seen merchants go to the model in one call.
# export APP_CATEGORIZER_BATCH_SIZE="40"

# ── Tasks ────────────────────────────────────────────────────────────────────────
# Redis for keeping the A2A tasks, so a task paused by one replica can be continued by any, even
# after a restart. When unset, tasks stay in the process's memory. Read only by an agent started
# on the host: compose points the replicas at the stack's own Redis.
# export APP_TASKS_REDIS_URL="redis://localhost:6379/0"
# How long Redis keeps a task after its last change.
# export APP_TASKS_TTL="30d"
# How long a task's lease lasts unless the replica working on it renews it. A task whose replica
# stopped is failed within about three times this.
# export APP_TASKS_LEASE="60s"

# ── Logging ──────────────────────────────────────────────────────────────────────
# The level for com.erancha and for the A2A SDK.
# export LOG_LEVEL="INFO"
# export A2A_LOG_LEVEL="WARN"
