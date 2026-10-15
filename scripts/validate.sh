#!/usr/bin/env bash
#
# Prove the agent alone, without the home finance agent. Starts it on the host against the test
# statement under the e2e profile (keyword table, no model), then drives it from the client side:
#   card, a streaming task for 2026-10 (24 transactions, completed), a task for 2026-09
#   (input-required), continue (asks again), cancel rules, and a wrong token (401).
# Then boots the same jar once per real provider (anthropic, openai) with a placeholder key: the
# provider's HTTP client is built at startup, so a runtime library missing from the jar fails here
# rather than on the first real deployment.
#
#   ./scripts/validate.sh          # the client-side checks above
#   ./scripts/validate.sh --tck    # additionally run the official A2A TCK (MUST level, JSON-RPC)
#
# --tck clones or updates github.com/a2aproject/a2a-tck under .tck/, starts a second agent under
# the "tck" and "e2e" Spring profiles (TCK scenario executor, auth off, keyword table) and fails on
# any MUST failure.
# Needs Java 21, Python 3.11+ and network access for the first run.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

TCK=0
for arg in "$@"; do
  case "$arg" in
    --tck)     TCK=1 ;;
    -h|--help) print_help; exit 0 ;;
    *) echo "Usage: ./scripts/validate.sh [--tck]" >&2; exit 2 ;;
  esac
done

ensure_java21
VENV="$(ensure_validate_venv)"
PORT=8021
BASE="http://localhost:$PORT"
TOKEN="validate-$(date +%s)"
JAR="$ROOT_DIR/target/amex-agent.jar"

(cd "$ROOT_DIR" && mvn -q -B -DskipTests package)

pids=()
cleanup() { [ ${#pids[@]} -gt 0 ] && kill "${pids[@]}" 2>/dev/null || true; }
trap cleanup INT TERM EXIT

# A health answer before launch means another agent (often an orphan of an interrupted run)
# owns the port; checking against it would prove nothing about this build.
run_agent() {  # port token profiles [provider, unread under the e2e profile]
  local url="http://localhost:$1" log="$ROOT_DIR/target/validate-$1.log" provider="${4:-}"
  if is_healthy "$url"; then
    echo "port $1 already serves an agent; stop it first (lsof -ti tcp:$1 | xargs kill)" >&2
    exit 1
  fi
  SERVER_PORT="$1" APP_AGENT_URL="$url" APP_A2A_TOKEN="$2" APP_LLM_PROVIDER="$provider" \
  APP_LLM_MODEL=placeholder ANTHROPIC_API_KEY=placeholder OPENAI_API_KEY=placeholder \
  APP_STATEMENTS_DIR="$ROOT_DIR/src/test/resources/statements" SPRING_PROFILES_ACTIVE="$3" \
  LOG_LEVEL=WARN java -jar "$JAR" >"$log" 2>&1 &
  local pid=$!
  pids+=("$pid")
  wait_for_health "$url" "$pid" \
    || { echo "agent on $1 exited or did not become healthy; see $log" >&2; exit 1; }
}

run_agent "$PORT" "$TOKEN" e2e
echo "==> client-side checks against $BASE"
"$VENV/bin/python" "$SCRIPT_DIR/validate/check_agent.py" "$BASE" "$TOKEN" 2026-10 24 2026-09

PROVIDER_PORT=8023
for provider in anthropic openai; do
  run_agent "$PROVIDER_PORT" unused default "$provider"
  echo "==> $provider provider boots from the jar"
  kill "${pids[-1]}" && wait "${pids[-1]}" 2>/dev/null || true
  unset 'pids[-1]'
done

if [ "$TCK" -eq 1 ]; then
  TCK_DIR="$ROOT_DIR/.tck"
  if [ -d "$TCK_DIR/.git" ]; then (cd "$TCK_DIR" && git pull -q --ff-only)
  else git clone -q https://github.com/a2aproject/a2a-tck.git "$TCK_DIR"; fi
  [ -x "$TCK_DIR/.venv/bin/python" ] || python3 -m venv "$TCK_DIR/.venv"
  "$TCK_DIR/.venv/bin/pip" install --quiet --disable-pip-version-check -e "$TCK_DIR"
  TCK_PORT=8022
  run_agent "$TCK_PORT" unused tck,e2e
  echo "==> A2A TCK (MUST, jsonrpc) against http://localhost:$TCK_PORT"
  (cd "$TCK_DIR" && .venv/bin/python ./run_tck.py --sut-host "http://localhost:$TCK_PORT" \
    --transport jsonrpc --level must)
  echo "TCK reports: $TCK_DIR/reports/"
fi
echo "validate: OK"
