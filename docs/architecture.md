# Architecture

How amex-agent is built, and why. Each section below ends in a page that holds its details. What
goes on the wire is in [a2a-contract.md](a2a-contract.md); how each part is
tested is in [testing.md](testing.md).

## Request flow

The architecture diagram in [the README](../README.md#architecture) walks a request through its
boxes. [architecture/request-flow.md](architecture/request-flow.md) is what runs inside the
replica: the classes a call passes through, the credentials, the threads and the library.

## Statements

One folder per person under the statements root, where a static page lets a person load and
delete their own files. On every lookup the agent reads the folder's PDFs and maps each to the
billing month it declares, so a file loaded while a task waits is found on `continue`. The parser
checks every section against its printed total.
[architecture/statements.md](architecture/statements.md)

## Categorizer

Merchants are normalized to a key, classified in batches by a language model that is given the
statement's sector label and the taxonomy's guides, and scored into `high`, `medium` or `low`
confidence; the e2e profile swaps in a keyword table.
[architecture/categorizer.md](architecture/categorizer.md)

## Scaling

The Docker stack runs several replicas of the agent behind an nginx edge, sharing their A2A tasks
through Redis, so a task paused for a missing statement can be continued through any replica. Two
concerns follow, each a GitHub label beside `scalability`: **scaling safety**, that it is safe to
run more than one replica without errors; and **scaling out**, that adding replicas serves more
callers without degrading any caller's experience.
[architecture/scaling.md](architecture/scaling.md)

## Configuration

Every runtime setting is an environment variable with a default in `application.yml`; secrets, the
provider and the statements folder have none, and a wrong required setting stops startup naming
it. [architecture/configuration.md](architecture/configuration.md)

## Codebase structure

Two halves under `com.erancha.amexagent`: the `a2a` and `auth` packages around the SDK, and the
`card` pipeline with its ports, application and infrastructure.
[architecture/codebase-structure.md](architecture/codebase-structure.md)
