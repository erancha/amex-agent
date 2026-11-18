#!/usr/bin/env bash
# Secrets of the Compose stack. ./scripts/init-env.sh copies this file to lib/secrets.sh
# (gitignored); fill it in there and never commit it. Every line is a plain export KEY="value":
# the scripts source the file, and an agent started on the host reads it from its environment.
# The A2A callers' secrets are not here: tools/register-caller.sh keeps them in the gitignored
# allowed-callers.yaml.

# Required: the API key of the provider APP_LLM_MODEL of params.sh names. Fill in by hand.
export APP_LLM_API_KEY=""
