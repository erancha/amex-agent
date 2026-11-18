# Tools

Hands-on tools, run from any directory. Each prints its full usage with `--help`, except
`anonymize-statement.py`, whose docstring is its usage. None acts on the running stack, so none
takes `--ec2`.

- `register-caller.sh` — registers an A2A caller (its name, a secret minted and printed once, and
  the Google client id whose tokens it forwards) in the gitignored `allowed-callers.yaml`, or
  replaces its entry; both targets read the file at the next `deploy.sh [--ec2]`.
- `validate.sh` — proves the packaged jar from the client side as a registered caller, with
  `validate/check_agent.py` on the a2a-sdk; `--tck` adds the official A2A conformance suite.
- `anonymize-statement.py` — copies a statement PDF with the holder's private details replaced,
  to use as a test fixture.

Back to the [README](../../README.md).
