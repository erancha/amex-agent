#!/usr/bin/env bash
#
# Create .env from .env.example. Refuses to clobber an existing .env unless --force is given.
#
#   ./scripts/init-env.sh                 # create .env
#   ./scripts/init-env.sh --force         # overwrite an existing .env
#
# Then set APP_AUTH_CLIENT_ID (the agent's own Google OAuth client) and the API key of
# APP_LLM_MODEL's provider (anthropic:claude-haiku-5-5 unless changed); the agent refuses to start
# without them. Register each A2A caller with ./scripts/register-caller.sh.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

FORCE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --force)   FORCE=1 ;;
    -h|--help) print_help; exit 0 ;;
    *) echo "Usage: ./scripts/init-env.sh [--force]" >&2; exit 2 ;;
  esac
  shift
done

ENV_FILE="$ROOT_DIR/.env"
if [ -f "$ENV_FILE" ] && [ "$FORCE" -eq 0 ]; then
  echo ".env already exists; pass --force to overwrite it." >&2
  exit 1
fi
# .env holds the provider keys, so only the owner may read it. umask covers a new file; chmod
# covers one that --force overwrites in place.
umask 077
cp "$ROOT_DIR/.env.example" "$ENV_FILE"
chmod 600 "$ENV_FILE"
echo "Created .env. Set APP_AUTH_CLIENT_ID and the API key of APP_LLM_MODEL's provider in it."
echo "Register the home finance agent (or any caller): ./scripts/register-caller.sh --help"
