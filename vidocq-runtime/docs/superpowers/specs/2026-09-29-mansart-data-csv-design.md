# Dev console: CSV export and import for Mansart Data

Date: 2026-09-29. Sub-project 5 of the Mansart Data panel; the catalogue (Vidocq/vidocq#153), running a repository
method (#154) and the JDQL console (#155) are merged, live statistics were dropped.

## 1. Goal

In a `dev` launch, a developer exports the result of a JDQL query as a CSV file downloaded by the browser, and imports
a CSV file into an entity, rows saved in a transaction rolled back (a dry run, the default) or committed.

- **In scope:**
  - two actions in the *JDQL* tab (#155): *Export CSV* and *Import CSV*;
  - a new result content type `text/csv` in the console SPI, shown as text with a *Download* button;
  - a generic page rule: a `textarea` string property that declares `"contentMediaType": "text/csv"` gets a *Choose
    file* button that loads a local file into it.
- **Out of scope:**
  - files on the server: nothing is read from or written to the project's directory;
  - several data stores: the default `RepositoryRuntime` bean, as in #155;
  - joined attributes (left out at export, refused at import); any change to Mansart.

## 2. The console SPI and the page

- **`PanelAction.ActionResult`:** a third content type, `public static final String CSV = "text/csv"`, beside `TEXT`
  and `JSON`; the body cap (`MAX_CONTENT`, 256 KiB) is unchanged. The javadoc lists it.
- **A CSV result:** the page shows the body as plain text (as `TEXT`), plus a *Download* button in the result block.
  It saves the body as a `Blob` of type `text/csv;charset=utf-8`, named `<action id>-<yyyyMMdd-HHmmss>.csv` (the
  action id with every character outside `[A-Za-z0-9._-]` replaced by `-`). No request is sent.
- **Choose file:** in a flat-schema form, a string property with `"format": "textarea"` and `"contentMediaType":
  "text/csv"` gets a *Choose file* button next to its textarea (an `<input type="file" accept=".csv,text/csv">`). The
  chosen file is read with `FileReader.readAsText` (UTF-8) and replaces the textarea's value. A file larger than
  60 KiB is not read; the form shows `the file is larger than 60 KiB` under the field. The console's own 64 KiB
  request limit stays the final guard.
- `PageTest` pins both rules; `dev-console-panels.adoc` documents them.

## 3. *Export CSV* (`jdql.export`)

- **Argument** (one JSON argument): `{ "query": string (format textarea, required), "params": object (optional),
  "separator": "," or ";" (default ",") }`. No confirmation.
- **Run:** as *Query* (#155): a statement for which `JdqlExecutor.isWrite` is true is refused (`an UPDATE or DELETE:
  export a query`); the entity found by `JdqlExecutor.target`; the statement run in a transaction always rolled back.
- **CSV** (§5), by result kind:
  - `Entities`: the header is the entity's attribute names in model order, joined attributes left out; each row the
    values `EntityJson` gives, as text (§5);
  - `Rows`: the header is the projection's column names;
  - `Count`: one column `count`, one row; `Value`: one column `value`, one row.
- **No row cap**: every row the statement selects. A CSV longer than 256 KiB (UTF-8 bytes) is an `error` result,
  `larger than 256 KiB: narrow the query`, nothing else; a partial file is never given.
- **Result:** `contentType` `text/csv`, the CSV as body; summary `42 rows · 3.1 KiB in 12 ms` (`1 row`, `no row`:
  header only); *details* `{"entity", "query", "params", "separator"}`.

## 4. *Import CSV* (`jdql.import`)

- **Argument:** `{ "entity": string (enum: the catalogue's entities by simple name, fully qualified when two share
  it), "csv": string (format textarea, contentMediaType text/csv, required), "separator": "," or ";" (default ","),
  "transaction": "rollback" or "commit" (`commit` only without a `TransactionManager`, as #154) }`.
- **Confirmation:** `Saves these CSV rows against the database.`
- **Phase 1, reading** (nothing written, no transaction begun):
  - the first record is the header: each name an attribute of the entity's model, no duplicate; a joined attribute
    or an unknown name fails (`header: unknown attribute <name>; attributes: <names>`);
  - at least one row, at most 5000 (`more than 5000 rows: split the file`);
  - each row has as many fields as the header (`line <n>: 3 fields, the header has 4`);
  - each row builds an entity: the model's no-arg constructor, then each column set with its setter handle,
    converted by the attribute's Java type as `Scalars` does for #154 (a reference from its id); an attribute with no
    column keeps the constructor's value; a `null` into a primitive fails.
  - the first failure stops the import: `line <n>, <attribute>: <why>` (`<n>` the line of the text, the header being
    line 1).
- **Phase 2, saving:** every entity through `RepositoryRuntime.save(model, entity)`, in order, in one transaction run
  by `TransactionRunner` (#154): `rollback` rolls it back after the last save, `commit` commits it; without a
  `TransactionManager`, `commit` saves without one. An exception rolls everything back: `line <n>: <Class>:
  <message>`, masked and cut (`Failures`).
- **Result:** `contentType` `application/json`, body `{"entity": "<class>", "saved": 42, "transaction": "rollback"}`;
  summary `42 rows saved · rolled back` / `· committed`; *details* `{"entity", "rows", "separator", "transaction"}`
  (not the CSV).

## 5. The CSV format

Written and read by a small hand-written class in the `-dev` module (RFC 4180, no new dependency):

- fields separated by the chosen separator, records by `\r\n` when written; `\r\n`, `\n` and a last record without an
  end of line accepted when read; a UTF-8 BOM at the start ignored;
- a field containing the separator, a `"`, a `\r` or a `\n`, or empty text, is quoted, `"` doubled;
- **`null`** is an empty unquoted field; the empty string is `""`. Both are read back as written;
- values as text: numbers as `toString` (`BigDecimal.toPlainString`), booleans `true`/`false`, an enum by name,
  `java.time` in ISO, a `UUID` by `toString`, a referenced entity as its id; the same text is read back by §4;
- a quote left open at the end of the text fails: `line <n>: unterminated quoted field`.

## 6. History

The *JDQL* tab's `calls` table (#155) records both actions, `method` *Export CSV* / *Import CSV*. The `arguments` cell
is the JSON sent cut at 200 characters, and the replay cell empty past `MAX_REPLAY_CELL`, as today: an import's replay
is in practice empty (its CSV is too long), an export's replay fills the form.

## 7. Testing

- **Console:** `ActionResult` accepts `text/csv`; `PageTest` pins the *Download* button for a CSV result and *Choose
  file* for `contentMediaType` `text/csv`.
- **`-dev` module, unit:** the CSV writer and reader (quoting, both separators, `null` vs `""`, BOM, `\n` and `\r\n`,
  embedded new lines, unterminated quote, field count); the export per result kind, the 256 KiB refusal, a write
  refused; the import header errors, a conversion error with its line, the 5000-row cap, rollback, commit, an
  exception from `save` rolling back with its line; the history.
- **mansart-h2 example** (`DevConsoleSnapshotTest`): export `FROM Product` gives a header and rows; importing that CSV
  with the ids emptied in `rollback` leaves the count unchanged; in `commit` it adds the rows.
- **Manual check in Chrome** under `vidocq:dev` on `lc4jcdi-on-vidocq/mcp-tasks-server`: export tasks, *Download*;
  edit the file, *Choose file*, import in `rollback`, then in `commit`, checked with *Query*; a bad line.

## 8. Documentation

- `vidocq-runtime-extensions.adoc`: a `[#mansart-data-csv]` section [NEW]: export and import, the format, `null`
  versus `""`, rollback as a dry run, the limits (60 KiB file, 256 KiB export, 5000 rows).
- `dev-console-panels.adoc`: `text/csv` results and `contentMediaType` `text/csv`.
- `whats-new.adoc`: one entry.

## 9. Decisions taken with the user

- The CSV lives in the browser: pasted or chosen file for the import, a download for the export; no server files.
- An import saves each row (`save`, an upsert): re-importing an edited export updates rows; an empty id is generated.
- Both actions in the existing *JDQL* tab; an export is a JDQL query, a whole entity being `FROM <Entity>`.
- An export too large fails rather than being cut; an import is read entirely before anything is saved.
