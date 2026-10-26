# Statements

Where a person's statements live, how the card site's PDF is read, and the page that loads them.
The agent reads only statements a person loaded themselves; it never logs in to the card site.

## One folder per person

`APP_STATEMENTS_DIR` is a root with one folder per person, named by their Google account id and
created by their first upload. A folder's statements are indexed by the billing month their
content declares, not by their file names:

- Every lookup rescans the folder, so a file loaded while a task waits is found on `continue`. A
  file is parsed again only when it changes.
- Two files for one billing month fail the lookup, naming both: silently preferring one would hide
  a mismatch.
- A file named like a card-site download, `<YYYY>-<MM>.pdf`, whose name and content disagree fails,
  naming the file and both months.
- A PDF that fails to parse fails the lookup with a message that starts with its file name. Files
  that are not PDFs are ignored.

## Reading the PDF

The Amex Israel statement is a right-to-left Hebrew PDF with two sections of charges, purchases
abroad and domestic ones, each ending in a printed total. The parser reads the text with PDFBox,
assigns each text chunk to a column by where its right edge ends, since every column is
right-aligned, and reads per row the purchase date, merchant, the issuer's sector label, the amount
charged, a standing-order mark, an installment's `N of M`, and, for purchases abroad, the original
amount and currency. The statement's charge date gives the billing month.

A month comes back whole or not at all. The charges of each section must add up to its printed
total, so a row that is lost or misread fails the parse with both sums instead of quietly shrinking
the month; a chunk that reaches across two columns, or a charge without a purchase date, fails the
same way. The statement prints no voucher numbers, so a row's id is the charge date and the row's
position, `20261002-01`: stable for one file and unique across months.

The test statement, `src/test/resources/statements/2026-10.pdf` (24 rows), is a real statement with
the holder's private details replaced by `scripts/anonymize-statement.py`. The script redraws each
replaced word at the original width, so text extraction sees the same columns as in the real file;
the mapping of private words to stand-ins never enters the repo.

## The statements page

`/statements.html` is a single static page where a person signs in with Google, loads statement
PDFs into their own folder and deletes them. It does nothing else: reading, categorizing and
reviewing a month stay with the A2A skill and its callers. The page keeps the ID token in memory
only and sends it as the bearer token of every API call; a 401 brings the sign-in button back.

| Request | Answer |
|---|---|
| `GET /api/statements` | The person's statement files: `fileName`, `sizeBytes`, `modified`. |
| `POST /api/statements`, multipart part `file` | 201 with the stored file; 400 for a refused file, 413 over the 5 MB upload limit, each a problem document whose `detail` names the cause. |
| `DELETE /api/statements/{fileName}` | 204; 400 when no listed statement has that name. |
| any of the above without an acceptable token | 401 with `WWW-Authenticate: Bearer`. |

An upload is refused when it is not a PDF, does not parse as a statement, or is for a month another
file already covers; a statement already loaded is not replaced, so the person removes the old file
first. Every check runs before the file becomes visible, and the file is stored under the download
name of the billing month it declares, whatever it was called. So a lookup never sees a partial or
refused file, and an uploaded name can never place a file outside the folder. A delete does not
read the file, so a broken or duplicate file that blocks the folder can still be removed.

A task waiting for a missing statement names this page in its input-required data part, so a caller
can link the person straight to it; signing in there with the same Google account lands them in the
same folder the task reads.
