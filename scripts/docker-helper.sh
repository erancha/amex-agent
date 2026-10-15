#!/usr/bin/env bash
#
# Docker Compose helper for the agent: teardown, logs, status. Bringing it up is start.sh's job.
#
#   ./scripts/docker-helper.sh --stop             # stop and remove the container and network
#   ./scripts/docker-helper.sh --logs [-e]        # follow logs; -e shows only errors/exceptions
#   ./scripts/docker-helper.sh --ps               # container status
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

mode="${1:-}"
case "$mode" in
  -h|--help) print_help; exit 0 ;;
  "")        print_help; exit 1 ;;
esac
shift
require_docker
case "$mode" in
  --stop) compose down --remove-orphans ;;
  --logs)
    if [ "${1:-}" = "-e" ]; then compose logs -f --tail=200 | grep -Eai '(error|exception|fatal)'
    else compose logs -f --tail=200; fi ;;
  --ps)   compose ps ;;
  *) echo "Unknown command: $mode (expected --stop | --logs | --ps)" >&2; exit 1 ;;
esac
