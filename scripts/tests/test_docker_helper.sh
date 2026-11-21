#!/usr/bin/env bash
# Verifies docker-helper.sh refuses what it does not support before it loads a settings file or
# reaches Docker: an unknown command, an unknown --stop option, any argument after --ps or
# --delete-stack, --delete-stack without --ec2, and --costs with anything but --since <age>. Its
# usage offers --stop --volumes. lib/docker_helper.py, behind --costs, sums the agent's model_call
# lines as Spring logs them.
set -euo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail() { echo "FAIL: $*" >&2; exit 1; }

refused() {  # expected-text args...
  local expected="$1" out
  shift
  out="$(bash "$SCRIPTS_DIR/docker-helper.sh" "$@" 2>&1)" && fail "accepted: $*"
  grep -q "$expected" <<<"$out" || fail "'$*' refused without naming the cause: $out"
}

refused "Unknown --stop option 'extra'" --stop extra
refused "Unknown --stop option 'extra'" --stop --volumes extra
refused "takes no arguments (got: extra)" --ps extra
refused "Unknown command: --bogus" --bogus
refused "Unknown --stop option 'extra'" --ec2 --stop extra
refused "needs --ec2 before it" --delete-stack
refused "takes no arguments (got: extra)" --ec2 --delete-stack extra
refused "costs takes only --since <age>" --costs extra
refused "costs takes only --since <age>" --costs --since
grep -q -- '--stop \[--volumes\]' "$SCRIPTS_DIR/docker-helper.sh" \
  || fail "usage does not offer --volumes"

spring_line='2026-11-21T08:15:00.000000000Z 2026-11-21T10:15:00.000+02:00  INFO 1 --- [amex-agent]'
spring_line+=' [task-1] model_call : {"event": "model_call", "kind": "categorize", "model":'
spring_line+=' "anthropic:claude-haiku-5-5", "input_tokens": 1692, "output_tokens": 82,'
spring_line+=' "cache_read_tokens": 0, "cache_write_tokens": 0, "seconds": 4.24, "cost": 2.102E-4}'
total="$(python3 "$SCRIPTS_DIR/lib/docker_helper.py" <<<"$spring_line" | tail -1)"
expected="total                  1        1692           0           0          82    0.0002"
[ "$total" = "$expected" ] \
  || fail "the costs table does not sum a Spring model_call line: $total"

echo "test_docker_helper: all checks passed"
