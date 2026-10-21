#!/usr/bin/env bash
# Verifies print_handover from scripts/common.sh, the lines start.sh ends with: the values the home
# finance agent's operator passes to add-card-agent.sh, named after the agent as this repo calls
# it, with the advertised URL and the page URL given, and the secret pointed at register-caller.sh.
set -euo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail() { echo "FAIL: $*" >&2; exit 1; }

url=http://host.docker.internal:8020 page=http://localhost:8020
out="$(. "$SCRIPTS_DIR/common.sh"; print_handover "$url" "$page")"
grep -q -- "--name amex --url $url --browser-url $page" <<<"$out" \
  || fail "registration values not printed as add-card-agent.sh takes them: $out"
grep -q 'register-caller.sh' <<<"$out" || fail "secret not pointed at register-caller.sh: $out"

echo "test_handover: all checks passed"
