#!/usr/bin/env bash
#
# Run the test suites.
#
#   ./scripts/test.sh          # unit + integration (mvn verify)
#   ./scripts/test.sh unit     # unit only (mvn test)
#   ./scripts/test.sh it       # unit + integration (mvn verify)
#   ./scripts/test.sh all      # same as no argument
#
# Unit tests (*Test) need nothing but Java 21. The integration tests (*IT) boot the agent on port
# 18020 and drive it with the SDK's Java client; no Docker, no API key.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

case "${1:-all}" in
  -h|--help) print_help; exit 0 ;;
  unit)      GOAL=test ;;
  it|all)    GOAL=verify ;;
  *) echo "Usage: ./scripts/test.sh [unit|it|all]" >&2; exit 2 ;;
esac
ensure_java21
cd "$ROOT_DIR" && mvn -q -B clean "$GOAL"
echo "All requested tests passed."
