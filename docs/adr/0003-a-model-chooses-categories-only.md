# 0003. A language model chooses categories only, with the provider a setting

- Status: Accepted
- Date: 2026-10-15

## Context

Each row must leave the agent in one category of the caller's closed list. Statement merchant
strings are short, often Hebrew, and say little: a doctor's name, a chain's branch number. Keyword
rules cannot cover them, while a language model can recognize most businesses by name. A model
can also misread numbers, and a month's amounts must be exact. Anthropic and OpenAI both offer
models of the needed tier.

## Decision

- A language model, called through Spring AI, chooses each merchant's display name, category and a
  score from 0 to 1; the agent maps the score to `high`, `medium` or `low` confidence.
- Amounts, dates and every other field come from the PDF without a model call.
- The agent categorizes merchants, not rows: each normalized merchant goes to the model once per
  process, in batches.
- The provider, Anthropic or OpenAI, is a deploy setting beside the model's name; both providers
  ship in the jar, and the setting decides which one the agent calls. The agent refuses to start
  without its provider's key.
- Tests run under the `e2e` Spring profile, which replaces the model with a keyword table.

## Consequences

- A model mistake mislabels a row but never changes its sum; a `low` row keeps the model's guess
  for the caller to confirm or correct.
- Each process calls the model again for merchants it has not seen, after every restart and on
  every replica.
- Switching provider is a configuration change, not a code change.
- No test needs a model key.
