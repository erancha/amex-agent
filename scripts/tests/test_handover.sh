#!/usr/bin/env bash
# Verifies print_handover from scripts/common.sh, the lines tools/register-caller.sh and deploy.sh
# end with: the agent name, the advertised URL from the environment, and the page URL and secret
# text given, addressed to the audience given; a localhost advertised URL adds the warning that a
# caller in Docker cannot reach it.
set -euo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fail() { echo "FAIL: $*" >&2; exit 1; }
handover() {  # advertised-url audience secret-text page-url
  (. "$SCRIPTS_DIR/common.sh"; APP_AGENT_URL="$1" EDGE_HOST_PORT=8020 print_handover "$2" "$3" "$4")
}

url=http://host.docker.internal:8020
out="$(handover "$url" "the caller's operator" s3cret-1 http://localhost:8020)"
grep -q "^Hand these to the caller's operator:" <<<"$out" || fail "audience not named: $out"
grep -qE "name: +amex$" <<<"$out" || fail "agent name not printed: $out"
grep -qE "A2A URL: +$url$" <<<"$out" || fail "advertised URL not printed: $out"
grep -qE "statements page: +http://localhost:8020$" <<<"$out" || fail "page URL not printed: $out"
grep -qE "secret: +s3cret-1$" <<<"$out" || fail "secret text not printed: $out"
if grep -q 'set APP_AGENT_URL' <<<"$out"; then fail "warned on a reachable URL: $out"; fi

out="$(handover https://example.cloudfront.net x y https://example.cloudfront.net)"
grep -qE "statements page: +https://example.cloudfront.net$" <<<"$out" \
  || fail "EC2 page URL not printed: $out"

out="$(handover http://localhost:8021 x y http://localhost:8021)"
grep -q 'set APP_AGENT_URL to http://host.docker.internal:8020 in stack-params.sh' <<<"$out" \
  || fail "localhost URL not flagged: $out"

echo "test_handover: all checks passed"
