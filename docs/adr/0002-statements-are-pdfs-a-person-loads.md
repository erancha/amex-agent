# 0002. Statements are PDFs a person loads, read from a folder

- Status: Accepted
- Date: 2026-10-15

## Context

American Express Israel offers no API for a cardholder's charges. Its site lets the holder
download each monthly statement, as a PDF that carries every charge, the issuer's sector label for
each merchant and a printed total per section. Logging in to the site on the holder's behalf would
mean keeping their card-site password and breaking whenever the site's sign-in changes. A month's
amounts must be exact, since the caller budgets with them.

## Decision

- The agent reads only statement files the person downloaded themselves and placed in a statements
  folder on disk; it never logs in to the card site.
- A static page of the agent loads and deletes those files, so the person needs no file access to
  the machine.
- Files are indexed by the billing month their content declares, rescanned on every lookup, so a
  file loaded while a task waits is found on `continue`.
- Apache PDFBox reads the PDF, and each section's charges must add up to its printed total, or the
  month fails.
- No database: the files are the record.

## Consequences

- The agent holds no card-site credentials, and a month exists only once its person loads it, so
  a request can pause until they do.
- A change to the card site's PDF layout fails the parse loudly instead of shrinking a month.
- Every replica must mount the same folder.
- Backing up statements is backing up a folder.
