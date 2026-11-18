# 0004. Registered callers forward the person's Google token

- Status: Accepted
- Date: 2026-10-20

## Context

One bearer token, shared with a single partner, admitted A2A calls. It identified the caller but
said nothing about the person a call was for, and could register no second caller. One folder held
everyone's statements, so any admitted caller could read any person's months. The home finance
agent signs its people in with Google, and Google publishes the keys that sign its ID tokens, so
any service can verify one itself.

## Decision

- Callers are registered by the agent's operator in a gitignored `allowed-callers.yaml`
  (`register-caller.sh`): a name, a secret the agent mints, and the Google OAuth client id of the
  caller's own sign-in. A filter admits a call only with a registered secret in `X-Caller-Secret`,
  compared in constant time; any other answer is HTTP 401.
- Every call also carries the person's Google ID token, as the caller's page received it. The agent
  verifies it with nimbus-jose-jwt against Google's published keys: signature, issuer, expiry, and
  an audience equal to that caller's registered client id.
- The token's account id names the person, and their statements live in a folder of that name.
- A failed person check pauses the task in `TASK_STATE_AUTH_REQUIRED` rather than refusing the
  call, so the caller can have the person sign in again and continue.
- The statements page signs in with the agent's own Google client, and its API accepts only tokens
  issued to that client.

## Consequences

- Each person reads only their own statements, through any registered caller.
- Removing a caller's entry revokes it at the next restart.
- The agent trusts a token issued to a partner's client, which fits services under one owner; a
  separate company would rather exchange it for a token of its own.
- Tests never reach Google: they sign tokens with a key of their own.
