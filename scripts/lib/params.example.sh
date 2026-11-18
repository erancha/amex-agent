#!/usr/bin/env bash
# Settings of this deployment that are its own and no secret. ./scripts/init-env.sh copies this
# file to lib/params.sh (gitignored); fill it in there. Every line is a plain export KEY="value":
# the scripts source the file, and an agent started on the host reads it from its environment.

# Required: this agent's own Google OAuth client id (a "Web application" client in its own Google
# Cloud project, with each address of the statements page as an authorized JavaScript origin).
# The statements page signs in with it, and tokens sent to that page must be issued to it. Fill
# in by hand (docs/sign-in.md).
export APP_AUTH_CLIENT_ID=""

# Required: provider:model of the chat model that categorizes merchants, for the provider
# anthropic or openai. Classifying merchants with a usable confidence score wants the Haiku or
# mini tier, not the smallest one: anthropic:claude-haiku-5-5 or openai:gpt-5-mini. The Spring
# profile e2e replaces the model with a keyword table and needs neither this nor the key.
export APP_LLM_MODEL="anthropic:claude-haiku-5-5"

# Optional: the address the README and NOTICE give for a commercial license. Tracked files may
# show this address; the pre-commit hook refuses any other address of this file.
export LICENSE_CONTACT=""
