#!/usr/bin/env bash
#
# Create .env from .env.example and mint APP_A2A_TOKEN, the bearer token A2A clients must present.
# Refuses to clobber an existing .env unless --force is given.
#
#   ./scripts/init-env.sh                 # create .env with a fresh token
#   ./scripts/init-env.sh --force         # overwrite an existing .env
#   ./scripts/init-env.sh --token <t>     # reuse the token the home finance agent shares
#
# Then set APP_LLM_MODEL and the provider's API key (APP_LLM_PROVIDER defaults to anthropic); the
# agent refuses to start without them.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

FORCE=0
TOKEN=""
while [ $# -gt 0 ]; do
  case "$1" in
    --force)   FORCE=1 ;;
    --token)   TOKEN="${2:?--token needs a value}"; shift ;;
    -h|--help) print_help; exit 0 ;;
    *) echo "Usage: ./scripts/init-env.sh [--force] [--token <t>]" >&2; exit 2 ;;
  esac
  shift
done

# The sed replacement below relies on this alphabet (url-safe base64, never the '|' delimiter).
if [ -n "$TOKEN" ] && ! [[ "$TOKEN" =~ ^[A-Za-z0-9_-]+$ ]]; then
  echo "--token may contain only letters, digits, '_' and '-'." >&2
  exit 2
fi

ENV_FILE="$ROOT_DIR/.env"
if [ -f "$ENV_FILE" ] && [ "$FORCE" -eq 0 ]; then
  echo ".env already exists; pass --force to overwrite it." >&2
  exit 1
fi
[ -n "$TOKEN" ] || TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
# .env holds the token and provider keys, so only the owner may read it. umask covers a new
# file; chmod covers one that --force overwrites in place.
umask 077
sed -e "s|^APP_A2A_TOKEN=.*|APP_A2A_TOKEN=${TOKEN}|" "$ROOT_DIR/.env.example" > "$ENV_FILE"
chmod 600 "$ENV_FILE"
echo "Created .env with APP_A2A_TOKEN set. Set APP_LLM_MODEL and the provider's API key in it."
echo "Monthly statement PDFs go into statements/ (APP_STATEMENTS_DIR)."
