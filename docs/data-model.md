# Data model

This document details the storage that [Architecture](architecture.md#storage) summarizes: every
Redis key the replicas share, the statements root, the callers registry and the merchant
categories each replica keeps.

**Contents:** [Redis](#redis) · [The statements root](#the-statements-root) ·
[The callers registry](#the-callers-registry) · [Merchant categories](#merchant-categories)

## Redis

Redis is shared by the replicas: one key `a2a:task:<taskId>` per task holding its wire JSON,
expiring `APP_TASKS_TTL` (30 days) after its last change, a set `a2a:tasks` of task ids for
listing, and one `a2a:lease:<taskId>` key per running call. Redis writes its data to disk every
second, to the `amex_redis` volume. How the task store and the lease use these keys is in
[Scaling](architecture/scaling.md#scaling-safety).

## The statements root

`APP_STATEMENTS_DIR` of `scripts/lib/stack-params.sh` (`./statements`, its content gitignored),
mounted into every replica, or the `statements` volume on the EC2 instance: one folder per person
named by their Google account id, each file named `<YYYY>-<MM>.pdf` after its billing month
([One folder per person](architecture/statements.md#one-folder-per-person)).

## The callers registry

`allowed-callers.yaml`, the gitignored callers file `tools/register-caller.sh` writes, handed to
each replica as a Compose secret and read at startup.

## Merchant categories

The category decided for each merchant is kept in each replica's memory and lost on restart
([Merchants, not rows](architecture/categorizer.md#merchants-not-rows)).
