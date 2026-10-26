# Architecture

How amex-agent is built, and why. Each section below ends in a page that holds its details. What
goes on the wire is in [a2a-contract.md](a2a-contract.md); how each part is tested is in
[testing.md](testing.md).

## Request flow

The architecture diagram in [the README](../README.md#architecture) walks a request through its
boxes. [architecture/request-flow.md](architecture/request-flow.md) is what runs inside a
replica: how the two credentials are checked, why the transport is hand-written, and the
threading a long-lived stream needs.

## Statements

One folder per person under the statements root, where a static page lets a person load and
delete their own files. On every lookup the agent reads the folder's PDFs and maps each to the
billing month it declares, so a file loaded while a task waits is found on `continue`. The parser
checks every section against its printed total.
[architecture/statements.md](architecture/statements.md)

## Categorizer

A language model categorizes each merchant once, in batches, guided by the sector label the
statement prints, and its score becomes `high`, `medium` or `low` confidence. Amounts and dates
never pass through the model.
[architecture/categorizer.md](architecture/categorizer.md)

## Scaling

The Docker stack runs several replicas of the agent behind an nginx edge, sharing their A2A tasks
through Redis, so a task paused for a missing statement can be continued through any replica. Two
concerns follow, each a GitHub label beside `scalability`: **scaling safety**, that it is safe to
run more than one replica without errors; and **scaling out**, that adding replicas serves more
callers without degrading any caller's experience.
[architecture/scaling.md](architecture/scaling.md)

## Configuration

Every runtime setting is an environment variable; secrets, the model and the statements folder have
no default, and a wrong required setting stops startup naming it.
[architecture/configuration.md](architecture/configuration.md)

## Codebase structure

Two halves: the packages that fit the a2a-java library into Spring and check who is calling, and
the card pipeline that turns a statement into categorized rows.
[architecture/codebase-structure.md](architecture/codebase-structure.md)
