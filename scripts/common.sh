#!/usr/bin/env bash
# Shared helpers for the amex-agent scripts: repo paths, help text, the settings files under
# scripts/lib/, the callers registry, compose access, the Java 21 lookup, the hand-over lines a
# caller's operator records, and the venv of the Python client-side checks.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS="$ROOT/scripts/tools"
# The stack's settings: stack-params.sh is committed; params.sh (this deployment's own settings)
# and secrets.sh are gitignored and created by init-env.sh from their .example.sh files.
STACK_PARAMS_FILE="$ROOT/scripts/lib/stack-params.sh"
PARAMS_FILE="$ROOT/scripts/lib/params.sh"
SECRETS_FILE="$ROOT/scripts/lib/secrets.sh"
# The deploying user's AWS credentials and region, read only for the EC2 target.
AWS_CONFIG_FILE="$ROOT/scripts/lib/aws-config.sh"
# The Compose files of the target: docker-compose.yml alone on this machine; use_ec2 of
# lib/ec2.sh adds the instance's override.
COMPOSE_FILES=("$ROOT/docker-compose.yml")
# Print the calling script's header comment block as --help text (single source of usage docs).
print_help() { awk 'NR==1 {next} /^#/ {sub(/^# ?/, ""); print; next} {exit}' "$0"; }

# shellcheck source=lib/settings-guard.sh
source "$ROOT/scripts/lib/settings-guard.sh"
# shellcheck source=lib/ec2.sh
source "$ROOT/scripts/lib/ec2.sh"

# Exports every setting of the three files into this shell, which is where docker compose takes
# the ${...} values and each service's keys from. Stops when a gitignored file is missing, when
# git tracks it, or when a tracked file holds one of its secrets or addresses.
load_settings() {
  # shellcheck source=lib/stack-params.sh
  source "$STACK_PARAMS_FILE"
  source_checked_file "$PARAMS_FILE" assert_addresses_untracked
  source_checked_file "$SECRETS_FILE" assert_secrets_untracked
}

# Adds each listed setting that is blank or still its example's <placeholder> to
# MISSING_SETTINGS, with the file under scripts/lib/ that sets it, so one run names them all.
MISSING_SETTINGS=()
collect_missing_settings() {  # file key...
  local file="$1" key
  shift
  for key in "$@"; do
    [[ -n "${!key:-}" && "${!key}" != "<"*">" ]] || MISSING_SETTINGS+=("$key ($file)")
  done
}

# Stops when collect_missing_settings found any, listing every one of them.
require_collected_settings() {
  [ "${#MISSING_SETTINGS[@]}" -eq 0 ] && return
  echo "Fill in these settings under scripts/lib/, then run the script again:" >&2
  printf '  %s\n' "${MISSING_SETTINGS[@]}" >&2
  exit 1
}

require_docker() {
  docker info >/dev/null 2>&1 \
    || { echo "Docker is not reachable; start it and retry." >&2; exit 1; }
}

# The registry of A2A callers that tools/register-caller.sh writes and the agent reads at
# startup: APP_A2A_CALLERS_FILE, relative to the repo root, or allowed-callers.yaml there.
callers_file() {
  local file="${APP_A2A_CALLERS_FILE:-allowed-callers.yaml}"
  [[ "$file" = /* ]] || file="$ROOT/$file"
  printf '%s' "$file"
}

# With access required the agent refuses to start without a registered caller, so the file is
# checked before any launch.
require_callers_file() {
  [ "${APP_A2A_AUTH_REQUIRED:-true}" = false ] && return 0
  [ -f "$(callers_file)" ] || { echo "No A2A caller is registered ($(callers_file) is missing);" \
    "register one first: ./scripts/tools/register-caller.sh --help" >&2; exit 1; }
}

# Compose reads the ${...} values and the keys each service lists in its environment from this
# shell, so the settings are loaded first. The callers registry reaches the replicas as the
# allowed-callers secret, whose content Compose takes from APP_A2A_CALLERS_YAML and writes into
# each container through the Docker API, so it reaches the EC2 instance's daemon as well; a
# command that starts no container runs without the file. The containers run as this user, so
# files they write into the statements folder are this user's.
compose() {
  load_settings
  local callers
  callers="$(callers_file)"
  if [ -f "$callers" ]; then export APP_A2A_CALLERS_YAML="$(<"$callers")"; fi
  # docker-compose.ec2.yml advertises the stack's CloudFront address, set by lib/ec2.sh.
  if [[ " ${COMPOSE_FILES[*]} " == *" $EC2_COMPOSE_FILE "* ]]; then export EC2_URL; fi
  local file
  local -a files=()
  for file in "${COMPOSE_FILES[@]}"; do files+=(-f "$file"); done
  (cd "$ROOT" && HOST_UID="$(id -u)" HOST_GID="$(id -g)" docker compose "${files[@]}" "$@")
}

# Waits up to about a minute until the URL answers with a success status.
wait_for_http() {  # url
  curl -fs --retry 60 --retry-all-errors --retry-delay 1 --max-time 2 -o /dev/null "$1"
}

# The project targets Java 21; the host default may be newer. Find a 21 by discovery.
is_java21() { [[ -x "$1/bin/java" ]] && "$1/bin/java" -version 2>&1 | grep -q 'version "21'; }

ensure_java21() {
  if [[ -n "${JAVA_HOME:-}" ]] && is_java21 "$JAVA_HOME"; then
    return 0
  fi
  local home
  for home in /usr/lib/jvm/java-21-openjdk-* /usr/lib/jvm/temurin-21-* /usr/lib/jvm/jdk-21*; do
    if is_java21 "$home"; then
      export JAVA_HOME="${home}" PATH="${home}/bin:${PATH}"
      return 0
    fi
  done
  echo "Java 21 is required but was not found" \
    "(sudo apt-get install -y openjdk-21-jdk-headless)." >&2
  exit 1
}

is_healthy() { curl -fs --max-time 2 -o /dev/null "$1/actuator/health"; }  # base-url

# Polls the health endpoint until it answers or 60 seconds pass; fails as soon as the given
# process exits, so a dead launch is reported at once instead of after the full wait.
wait_for_health() {  # base-url pid
  local attempt
  for attempt in $(seq 60); do
    is_healthy "$1" && return 0
    kill -0 "$2" 2>/dev/null || return 1
    sleep 1
  done
  return 1
}

# The name a caller's operator registers this agent under.
AGENT_NAME=amex

# The four values a caller's operator registers this agent with: tools/register-caller.sh prints
# them with the secret it minted, and deploy.sh repeats them once the agent is up, with the
# target's own addresses. A localhost card reaches only a caller on this host outside Docker, so
# that case is flagged while the value can change.
print_handover() {  # audience secret-text page-url
  echo "Hand these to $1:"
  echo "  name:            $AGENT_NAME"
  echo "  A2A URL:         $APP_AGENT_URL"
  echo "  statements page: $3"
  echo "  secret:          $2"
  if [[ "$APP_AGENT_URL" == http://localhost* ]]; then
    echo "A caller running in Docker on this machine cannot reach localhost; set APP_AGENT_URL" \
      "to http://host.docker.internal:$EDGE_HOST_PORT in stack-params.sh before handing the URL" \
      "over."
  fi
}

# A private venv for the Python-based checks (the a2a-sdk client), created on demand.
# Callers capture stdout as the venv path, so tool output goes to stderr and a failed setup exits
# explicitly (errexit does not reach into a command substitution).
ensure_validate_venv() {
  local venv="$ROOT/.validate-venv"
  if [ ! -x "$venv/bin/python" ]; then
    python3 -m venv "$venv" >&2 || exit 1
  fi
  if ! "$venv/bin/python" -c "import a2a, jwt, cryptography" 2>/dev/null; then
    "$venv/bin/pip" install --quiet --disable-pip-version-check "a2a-sdk>=1.2.2,<2" \
      "PyJWT[crypto]>=2.10" >&2 || exit 1
  fi
  echo "$venv"
}
