# Configuration

This page details the [Configuration](../architecture.md#configuration) section of the
architecture. Every runtime setting is an environment variable, read once at startup. Three files
under `scripts/lib/` hold them, and the scripts export them into their shell, from which Compose
passes each replica the keys `docker-compose.yml` lists:
[stack-params.sh](../../scripts/lib/stack-params.sh) (committed: ports, URLs and every optional
setting, commented out at its default), and the gitignored `params.sh` (the Google client id and
the model) and `secrets.sh` (the model's API key), each explained in its committed `.example.sh`
([params.example.sh](../../scripts/lib/params.example.sh),
[secrets.example.sh](../../scripts/lib/secrets.example.sh)). Each setting is explained in the
one file that sets it. The registered callers and their secrets are a file of their own,
`allowed-callers.yaml`, which Compose hands each replica as a secret.

On the EC2 target, `docker-compose.ec2.yml` sets `APP_AGENT_URL` to the stack's CloudFront
address, so the card advertises the address callers reach there; every other setting is the same
on both targets.

A wrong required setting stops startup naming the variable, so a misconfigured agent never serves a
single request. Secrets, the model and the statements folder have no default for the same reason: a
guessed value would let the agent start and fail later, far from the cause.

Two Spring profiles change the agent for tests only. `e2e` replaces the language model with a
keyword table, so it needs no model or key ([categorizer.md](categorizer.md#providers)). `tck` turns
auth off and runs the conformance suite's scenario executor
([testing.md](../testing.md#conformance)).
