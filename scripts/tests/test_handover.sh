#!/usr/bin/env bash
# Verifies print_handover from scripts/common.sh, the lines register-caller.sh and start.sh end
# with: the agent name, the advertised URL and the page URL from the environment, and the secret
# text given, addressed to the audience given; a localhost advertised URL, the default when
# APP_AGENT_URL is unset, adds the warning that a caller in Docker cannot reach it.
set -euo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail() { echo "FAIL: $*" >&2; exit 1; }
handover() {  # advertised-url port audience secret-text
  (. "$SCRIPTS_DIR/common.sh"; APP_AGENT_URL="$1" SERVER_PORT="$2" print_handover "$3" "$4")
}

url=http://host.docker.internal:8020
out="$(handover "$url" 8020 "the caller's operator" s3cret-1)"
grep -q "^Hand these to the caller's operator:" <<<"$out" || fail "audience not named: $out"
grep -qE "name: +amex$" <<<"$out" || fail "agent name not printed: $out"
grep -qE "A2A URL: +$url$" <<<"$out" || fail "advertised URL not printed: $out"
grep -qE "statements page: +http://localhost:8020$" <<<"$out" || fail "page URL not printed: $out"
grep -qE "secret: +s3cret-1$" <<<"$out" || fail "secret text not printed: $out"
if grep -q 'set APP_AGENT_URL' <<<"$out"; then fail "warned on a reachable URL: $out"; fi

out="$(handover "" 8021 x y)"
grep -qE "A2A URL: +http://localhost:8021$" <<<"$out" || fail "unset URL not defaulted: $out"
grep -q 'set APP_AGENT_URL to http://host.docker.internal:8020' <<<"$out" \
  || fail "localhost URL not flagged: $out"

echo "test_handover: all checks passed"
