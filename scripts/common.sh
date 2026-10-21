#!/usr/bin/env bash
# Shared helpers for the amex-agent scripts: repo root, compose wrapper, the callers-file checks,
# .env loading, Java 21 discovery, the health wait every launcher uses and the handover lines
# start.sh prints for a caller's operator.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/docker-compose.yml"

# Print the calling script's header comment block as --help text (single source of usage docs).
print_help() { awk 'NR==1 {next} /^#/ {sub(/^# ?/, ""); print; next} {exit}' "$0"; }

# The container runs as the caller so files it writes into the statements mount are theirs.
compose() {
  (cd "$ROOT_DIR" && HOST_UID="$(id -u)" HOST_GID="$(id -g)" \
    docker compose -f "$COMPOSE_FILE" "$@")
}

require_docker() {
  docker info >/dev/null 2>&1 \
    || { echo "Docker is not reachable; start it and retry." >&2; exit 1; }
}

# Where register-caller.sh writes the callers and the agent reads them unless APP_A2A_CALLERS_FILE
# names another file.
DEFAULT_CALLERS_FILE="$ROOT_DIR/allowed-callers.yaml"

# Docker turns a missing mount source into an empty root-owned directory. Its parent is the
# repo, so the owner may remove it: an empty one goes quietly, a directory holding anything is
# refused by name rather than guessed about.
clear_callers_dir() {  # path
  [ -d "$1" ] || return 0
  if [ -n "$(ls -A "$1")" ]; then
    echo "$1 is a directory with content where the callers file belongs; move it away first." >&2
    exit 1
  fi
  rmdir "$1"
  echo "Removed the empty directory $1 that a Docker start without a callers file left."
}

# With access required the agent refuses to start without a registered caller, so the file is
# checked before any launch.
require_callers_file() {
  [ "${APP_A2A_AUTH_REQUIRED:-true}" = false ] && return 0
  local file="${APP_A2A_CALLERS_FILE:-$DEFAULT_CALLERS_FILE}"
  [[ "$file" = /* ]] || file="$ROOT_DIR/$file"
  clear_callers_dir "$file"
  [ -f "$file" ] || { echo "No A2A caller registered ($file is missing); register one first:" \
    " ./scripts/register-caller.sh --help" >&2; exit 1; }
}

require_env() {
  [ -f "$ROOT_DIR/.env" ] \
    || { echo ".env not found; create it first:  ./scripts/init-env.sh" >&2; exit 1; }
}

# Export the keys of .env as defaults: a variable the caller already exported keeps its value.
# Accepts blank lines, # comments and KEY=value (one matching pair of quotes is stripped); any
# other line is rejected rather than executed.
load_env() {
  local line key value lineno=0
  while IFS= read -r line || [[ -n "$line" ]]; do
    lineno=$((lineno + 1))
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    if [[ ! "$line" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]]; then
      echo "$ROOT_DIR/.env:$lineno: expected KEY=value, got: $line" >&2
      exit 1
    fi
    key="${line%%=*}"
    value="${line#*=}"
    if [[ "$value" =~ ^\"(.*)\"$ || "$value" =~ ^\'(.*)\'$ ]]; then
      value="${BASH_REMATCH[1]}"
    fi
    if [[ -z "${!key+x}" ]]; then
      export "$key=$value"
    fi
  done < "$ROOT_DIR/.env"
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

# The name a caller's operator registers this agent under.
AGENT_NAME=amex

# The URLs the hand-over names, with application.yml's defaults when .env leaves them unset, so
# the printed values are the running agent's.
page_url() { echo "http://localhost:${SERVER_PORT:-8020}"; }
advertised_url() { echo "${APP_AGENT_URL:-$(page_url)}"; }

# The four values a caller's operator registers this agent with. register-caller.sh prints them
# with the secret it minted; start.sh repeats them once the agent is up. A localhost card reaches
# only a caller on this host outside Docker, so that case is flagged while the value can change.
print_handover() {  # audience secret-text
  local url
  url="$(advertised_url)"
  echo "Hand these to $1:"
  echo "  name:            $AGENT_NAME"
  echo "  A2A URL:         $url"
  echo "  statements page: $(page_url)"
  echo "  secret:          $2"
  if [[ "$url" == http://localhost* ]]; then
    echo "A caller running in Docker on this machine cannot reach localhost; set APP_AGENT_URL" \
      "to http://host.docker.internal:8020 in .env before handing the URL over."
  fi
}

# Poll the health endpoint until it answers or 60 seconds pass. Given a pid, fail as soon as that
# process exits, so a dead launch is reported at once instead of after the full wait.
wait_for_health() {  # base-url [pid]
  local attempt
  for attempt in $(seq 60); do
    is_healthy "$1" && return 0
    if [ -n "${2:-}" ] && ! kill -0 "$2" 2>/dev/null; then
      return 1
    fi
    sleep 1
  done
  return 1
}

# A private venv for the Python-based checks (the a2a-sdk client), created on demand.
# Callers capture stdout as the venv path, so tool output goes to stderr and a failed setup exits
# explicitly (errexit does not reach into a command substitution).
ensure_validate_venv() {
  local venv="$ROOT_DIR/.validate-venv"
  if [ ! -x "$venv/bin/python" ]; then
    python3 -m venv "$venv" >&2 || exit 1
  fi
  if ! "$venv/bin/python" -c "import a2a, jwt, cryptography" 2>/dev/null; then
    "$venv/bin/pip" install --quiet --disable-pip-version-check "a2a-sdk>=1.2.2,<2" \
      "PyJWT[crypto]>=2.10" >&2 || exit 1
  fi
  echo "$venv"
}
