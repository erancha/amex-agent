# Categorizer

How each row gets a category from the shared taxonomy and a confidence, `high`, `medium` or `low`.
Amounts and dates never pass through a model; only the category and the display name do.

## Merchants, not rows

A month repeats merchants, and a merchant's category does not change between rows, so the agent
categorizes merchants. Each row's merchant string is reduced to a key: lower case, spaces
collapsed, branch numbers and company forms such as `בע"מ` or `ltd` dropped. A key is decided once
per process and kept in memory; only keys this process has not seen go to the model, in batches of
`APP_CATEGORIZER_BATCH_SIZE`. One task's model calls finish before another's start, so two tasks
never pay twice for the same merchant.

The model scores each answer between 0 and 1. From `APP_CATEGORIZER_HIGH_CONFIDENCE` up the row is
`high`, from `APP_CATEGORIZER_MEDIUM_CONFIDENCE` up `medium`, below that `low`, and a `low` row
keeps the model's category as a guess the caller can confirm or correct. An answer outside the
taxonomy or the score range becomes `uncategorized` with `low` confidence; a model that answers a
different number of merchants than it was asked fails the task.

## The prompt

Each batch is one structured-output call with the taxonomy and the raw merchant strings, each beside
the sector label the statement prints for it, such as `[sector: שרות רפואי]`. The model answers per
merchant with a display name, a category and a score. The prompt is shaped by what goes wrong
without it:

- **The sector is the issuer's own hint** about the business, so the model leans on it where the
  merchant string says little, such as a doctor's name billed in installments.
- **A category is what the money buys, not how it is paid.** Each category carries a guide to the
  merchants it holds, so a gym paid by standing order is health and a streaming service
  entertainment.
- **No judging a shop by a generic word in its name**, and `uncategorized` rather than a guess.
- **The display name keeps the statement's language**, so a Hebrew merchant is never shown in
  English. It stays short when the brand explains itself (`Netflix`, `סלקום`) and keeps the words
  that say what the business does when it does not (`הראל ביטוח בריאות`).

## Providers

`APP_LLM_MODEL` is `anthropic:<model>` or `openai:<model>`, with `APP_LLM_API_KEY` that provider's
key; no setting runs the agent without a model. Both providers' Spring AI starters ship in the jar,
and the provider half of the setting decides, before Spring builds anything, which one is created,
so a run loads exactly one model client. Spring AI's other model types stay off, since the agent
only chats.

Claude may reason before it answers, and the reasoning counts against the call's output limit, so
the Anthropic limit is raised to 16384 tokens: Spring AI's 4096 default can run out on one batch of
a few dozen merchants. A reply that carries no answer fails naming the finish reason.

The `e2e` Spring profile replaces the model with a fixed keyword table, so tests run without a
model or key. The table knows every merchant of the test statement except its gym, so a test
review always has one merchant for a person to categorize.
