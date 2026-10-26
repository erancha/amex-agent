# Categorizer

`MerchantCategorizer` keys every row by `MerchantNormalizer.key(rawMerchant)`: trimmed, whitespace
collapsed, lower-cased, trailing branch numbers and company-form words (`בעמ`, `בע"מ` with either
gershayim spelling, `ltd`, `inc`, `llc`) removed. Keys are cached for the process lifetime. Unseen
keys go to the classifier in batches of `APP_CATEGORIZER_BATCH_SIZE`, each as the raw string and
statement sector of its first row; `categorize` is synchronized, so each key is classified once per
process and concurrent tasks serialize their model calls. The model's score in `[0, 1]` becomes the
row's confidence, `high`, `medium` or `low`, by the two thresholds above; a `low` row keeps the
model's category as a guess. An answer outside the taxonomy or the score range becomes
`uncategorized` with `low` confidence; a classifier that answers a different number of merchants
than it was asked fails the task.

## Providers

| `APP_LLM_MODEL` | Classifier |
|---|---|
| `anthropic:<model>` | `SpringAiMerchantClassifier` over the Anthropic `ChatModel` |
| `openai:<model>` | `SpringAiMerchantClassifier` over the OpenAI `ChatModel` |

Every run needs one of them with `APP_LLM_API_KEY`, that provider's key; no setting runs the agent
without a language model. The `e2e` Spring profile alone swaps in `KeywordMerchantClassifier`, a
fixed keyword table, so end-to-end tests run without a model or key. Under it `LlmProperties` is not
bound, so `APP_LLM_MODEL` and `APP_LLM_API_KEY` may be unset, and `application-e2e.yml` sets
`spring.ai.model.chat` to `none`.

Both Spring AI starters are on the classpath. `spring.ai.model.chat` is the provider half of
`APP_LLM_MODEL`, which `LlmModelEnvironmentPostProcessor` splits into `app.llm.provider` and
`app.llm.model-id` before auto-configuration runs (a bean would come too late for that), so only the
selected provider's `ChatModel` auto-configures and it is given the model half. Every non-chat model
type (embedding, image, moderation, audio speech and transcription) is set to `none`: the agent uses
chat only, and the OpenAI starter would otherwise create models that need libraries absent from the
runtime classpath.

`SpringAiMerchantClassifier` sends the taxonomy and one batch of raw merchant strings, each beside
the sector label the statement prints for it (`[sector: שרות רפואי]`), in a single
structured-output call and reads back, per merchant, a display name, a category and a confidence.
The sector is the issuer's own hint about what the business does, and the prompt tells the model
to lean on it where the merchant string alone says little, such as a doctor's name billed in
installments. Each category carries a guide in `Category` (the kinds of merchants it holds), and
the prompt says a category is what the money buys, not how it is paid: a gym paid by standing
order is health, a streaming service entertainment. It also warns against judging a shop by a
generic word in its name and asks for `uncategorized` over a guess. A display name keeps the
language the statement prints the merchant in, so a Hebrew merchant is never shown translated to
English. It stays short when the brand explains itself (`Netflix`, `סלקום`) and keeps the words that
say what the business does when it does not (`הראל ביטוח בריאות`), so a row reads as more than a
bare brand.
Claude may reason before it answers. Spring AI then reports the reasoning as a generation of its
own ahead of the answer, so the classifier reads the last generation, never the first, and
converts its text itself. The reasoning tokens also count against the call's output limit, so
`spring.ai.anthropic.chat.options.max-tokens` is 16384 rather than Spring AI's 4096 default, which
one batch of a few dozen merchants can exhaust. A reply without answer text fails naming the
finish reason and the limit's property instead of handing an empty string to the JSON reader.
The keyword table answers known merchants with confidence 1.0 and everything else as
`uncategorized` at 0.0. It covers the test statement's merchants except its gym, so a test review
always has one merchant for a person to categorize. `validate.sh`, the integration test and a
caller's own end-to-end tests run under the `e2e` profile to use it.
