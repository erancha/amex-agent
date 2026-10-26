# Statements

Where a person's statements live, how the card site's PDF is read, and the page that loads them.

## Folders and layout

`APP_STATEMENTS_DIR` is a root with one folder per person, named by their Google account id;
`StatementFolders` hands out one `StatementFolder` per person, created by their first upload (a
lookup for a person without a folder is simply empty). `StatementFolder` indexes the files of one
folder by the billing month their content declares. A file with the `.pdf` extension (in any case)
is a statement, read by the `StatementParser` for the card site's PDF statement:

- Files with other extensions are skipped. A `.pdf` file that fails to parse fails the lookup
  with a message that starts with its file name.
- Every lookup rescans the folder, so a file downloaded while a task waits is found on `continue`.
  A file is re-parsed only when its modification time changes.
- Two files for one billing month fail the lookup, naming both files: silently preferring one
  would hide a mismatch.
- A file named like a card-site download, `<YYYY>-<MM>.pdf`, whose name and content disagree
  fails, naming the file and both months. Other names are indexed by content alone.

### PDF statement

`PdfTextLines` reads the PDF with PDFBox into lines of text chunks in reading order, each with the
horizontal extent of its non-blank glyphs. `PdfStatementParser` interprets those lines against
`PdfStatementLayout.AMEX_ISRAEL`:

| Element | How it is found |
|---|---|
| Charge date | the line labelled `פרוט פעולותיך לתאריך:`, `DD/MM/YY`; its month is the billing month |
| Sections | `רכישות בחו"ל` (purchases abroad) and `עסקות שחויבו / זוכו - בארץ` (domestic), each from its title line to its `סה"כ חיוב לתאריך` total line; a section continuing on the next page repeats its title there |
| Columns | every column is right-aligned, so a cell belongs to the column whose right edge it ends at, within 4 points; a chunk reaching across a column boundary fails the parse |
| Rows | lines with a `DD/MM/YY` purchase date in the date column; a charge without one fails |
| Domestic row | date, card presence (`ה.קבע` marks a standing order), merchant, sector (the issuer's label for the business, kept on the row for the categorizer), purchase amount, charge, and `תשלום N מתוך M` for an installment |
| Abroad row | date, merchant, the original amount with its currency symbol, conversion columns, and the charge in `₪`; the city line under the merchant is skipped; the section prints no sector |
| Margins | text left of x 130 or right of x 525 is decoration (side banners, page numbers) |

The charges of each section must add up to its printed total, so a row that is lost or misread
fails the parse with both sums instead of quietly shrinking the month. The statement prints no
voucher numbers, so a row's id is the charge date and the row's position, `20261002-01`: stable for
one file and unique across months.

The committed fixture, `src/test/resources/statements/2026-10.pdf` (billing month 2026-10, 24
rows), is a real statement with the holder's name, address, account and card number, the city and a
doctor's name replaced by `scripts/anonymize-statement.py`. The script redraws each replaced word
with its character spacing adjusted to the original width, so text extraction sees the same
columns as in the real file; the mapping of private words to stand-ins never enters the repo.

## The statements page

`GET /statements.html` is a single static page (plain HTML and JavaScript, no build step) where a
person signs in with Google, loads statement PDFs into their own folder under `APP_STATEMENTS_DIR`
and deletes them. It does nothing else: reading, categorizing and reviewing a month stay with the
A2A skill and its callers. The page reads `GET /api/auth/config` for the client id, loads Google's
sign-in script, keeps the ID token in memory only and sends it as the bearer token of every API
call; a 401 brings the sign-in button back. Once signed in, a header at the top names the person's
account, with a Sign out button that drops the token and returns the page to the sign-in button.
With auth off the page skips sign-in and works on the `anonymous` folder. Its API, each call with
`Authorization: Bearer <ID token>`:

| Request | Answer |
|---|---|
| `GET /api/statements` | The person's statement files (the files a lookup for them would read), sorted by name: `fileName`, `sizeBytes`, `modified`. |
| `POST /api/statements`, multipart part `file` | 201 with the stored file, or a problem document (`application/problem+json`) whose `detail` names the cause: 400 for a refused file, 413 over the `spring.servlet.multipart.max-file-size` limit, 500 when the folder cannot be read or written. |
| `DELETE /api/statements/{fileName}` | 204 once the file is deleted; 400 when no listed statement has that name, so a name never reaches outside the folder or a file that is not a statement. The file is not read first, so a file that blocks the folder (unreadable, misnamed, a second copy of a month) can be removed from the page. |
| any of the above without an acceptable token | 401 with a problem document and `WWW-Authenticate: Bearer`. |

`StatementFolder.add` refuses, naming the cause, a file that:

- does not start with the PDF signature `%PDF-`;
- does not parse as a statement;
- is for a month another file already covers, or arrives while an unreadable file blocks the folder.

The upload is written to a hidden `.upload-*.part` file in the folder, which no parser claims, and
renamed to the download name of the billing month its content declares, `<YYYY>-<MM>.pdf`, only
after every check passes. The uploaded file's own name only identifies it in refusals, so it can
never place a file outside the folder, and the stored name agrees with the content by construction.
A lookup never sees a partial or refused file. A statement for a month already loaded is not
replaced; remove the old file first.

The page and `/api/auth/config` are open; the API takes the person's own token, never a caller's
secret. In Docker the folder is mounted writable and the container runs as the host user who owns
it, so an uploaded file belongs to that user.

A task waiting for a missing statement names the page in its input-required data part (`page`,
the path on the agent's base URL), so a client can link a person straight to it; the person signs
in there with the same Google account and lands in the same folder the task reads.
`StatementsEndpoint.PAGE` holds that path for both the data part and the caller filter's
exemption, and `StatementsEndpoint.API` the API's path for its mapping and the same exemption.
