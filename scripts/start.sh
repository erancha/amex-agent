#!/usr/bin/env bash
#
# Run amex-agent. Docker is the default; --host runs it as a local JVM for fast restarts
# and debugger attach. Either way the agent answers on http://localhost:${SERVER_PORT:-8020} and,
# once /actuator/health is green, prints its Agent Card URL and the values the home finance
# agent's operator registers it with: its name, APP_AGENT_URL and the page URL.
#
#   ./scripts/start.sh            # build the image and run it with the statements folder mounted
#   ./scripts/start.sh --host     # mvn spring-boot:run, attached to this terminal (Ctrl-C stops it)
#
# Requires .env (./scripts/init-env.sh). Stop or inspect the Docker run with docker-helper.sh.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

HOST=0
for arg in "$@"; do
  case "$arg" in
    --host)    HOST=1 ;;
    -h|--help) print_help; exit 0 ;;
    *) echo "Usage: ./scripts/start.sh [--host]" >&2; exit 2 ;;
  esac
done

require_env
load_env
PORT="${SERVER_PORT:-8020}"
BASE="http://localhost:$PORT"

print_card_url() {
  echo "amex-agent -> $BASE   card: $BASE/.well-known/agent-card.json"
  print_handover "$APP_AGENT_URL" "$BASE"
}

if [ "$HOST" -eq 0 ]; then
  require_docker
  compose up -d --build
  wait_for_health "$BASE" \
    || { echo "agent did not become healthy; see ./scripts/docker-helper.sh --logs" >&2; exit 1; }
  print_card_url
  echo "stop or tail logs:  ./scripts/docker-helper.sh --stop | --logs"
  exit 0
fi

ensure_java21
echo "Starting amex-agent on $BASE (statements: $APP_STATEMENTS_DIR)"
(cd "$ROOT_DIR" && exec mvn -q spring-boot:run) &
MVN_PID=$!
trap 'kill "$MVN_PID" 2>/dev/null' INT TERM
wait_for_health "$BASE" "$MVN_PID" \
  || { echo "agent did not become healthy; see the mvn output above" >&2;
       kill "$MVN_PID" 2>/dev/null; exit 1; }
print_card_url
wait "$MVN_PID"
