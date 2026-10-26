# Configuration

Every runtime setting is an environment variable, read once at startup; `.env` supplies them to the
scripts and to Docker. [.env.example](../../.env.example) is the one place each setting is
explained: the required ones are uncommented, and every optional one is commented out at its
default.

A wrong required setting stops startup naming the variable, so a misconfigured agent never serves a
single request. Secrets, the model and the statements folder have no default for the same reason: a
guessed value would let the agent start and fail later, far from the cause.

Two Spring profiles change the agent for tests only. `e2e` replaces the language model with a
keyword table, so it needs no model or key ([categorizer.md](categorizer.md#providers)). `tck` turns
auth off and runs the conformance suite's scenario executor
([testing.md](../testing.md#conformance)).
