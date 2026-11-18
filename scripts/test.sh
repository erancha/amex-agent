#!/usr/bin/env bash
#
# Run the quality gate of amex-agent.
#
#   ./scripts/test.sh                # every target (default)
#   ./scripts/test.sh all            # every target (explicit)
#   ./scripts/test.sh unit           # the scripts' checks, then the Java unit tests (mvn test)
#   ./scripts/test.sh it             # the scripts' checks, then the unit and integration tests
#                                    # (mvn verify)
#
# Targets combine, so several may be given; all is it, since mvn verify runs the unit tests
# before the integration tests. The scripts' checks are the syntax check (bash -n) of every
# script and the hook, then the shell tests under scripts/tests/. No suite needs a model API key.
# Unit tests (*Test) need nothing but Java 21.
# The integration tests (*IT) boot the agent and drive it with the SDK's Java client; the Redis
# ones start Redis in a container, so they need Docker. tools/validate.sh adds the client-side
# proof of the packaged jar.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

usage() {
  echo "Usage: ./scripts/test.sh [all|unit|it]" >&2
  exit 2
}

# Targets combine; mvn verify covers mvn test, so it wins.
GOAL=""
for arg in "$@"; do
  case "$(printf '%s' "$arg" | tr '[:upper:]' '[:lower:]')" in
    -h|--help) print_help; exit 0 ;;
    all|it)    GOAL=verify ;;
    unit)      [ "$GOAL" = verify ] || GOAL=test ;;
    *) usage ;;
  esac
done
[ -n "$GOAL" ] || GOAL=verify

echo "==> Scripts"
echo "--> syntax (bash -n)"
for script in "$ROOT"/scripts/*.sh "$ROOT"/scripts/lib/*.sh "$ROOT"/scripts/tools/*.sh \
    "$ROOT"/scripts/tests/*.sh "$ROOT/.githooks/pre-commit"; do
  bash -n "$script"
done
echo "--> shell tests (scripts/tests/)"
for check in "$ROOT"/scripts/tests/test_*.sh; do bash "$check"; done

echo "==> Java"
ensure_java21
echo "--> mvn $GOAL"
( cd "$ROOT" && mvn -q -B clean "$GOAL" )

echo "All requested tests passed."
