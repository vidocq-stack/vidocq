# Dev console: table views and a SQL editor in Mansart pools, and a table for rows

Date: 2026-10-01. The third piece of the dev console's code editor. The first
(`2026-09-30-devconsole-editor-json-design.md`, Vidocq/vidocq#173) gave the console its editor and the JSON language;
the second (`2026-09-30-devconsole-query-mode-design.md`, #174) the query mode, panel languages and JDQL in Mansart
Data.

## 1. Goal

The *Mansart pools* panel shows gauges and charts per pool, nothing of what the database holds. It gets, per pool,
the tables and their columns, the first rows of a table, and a SQL editor that reads in a transaction always rolled
back and writes on confirmation, rolled back unless committed. The query mode learns what SQL needs (aliases, joins,
several tables, quoted identifiers), and the console learns to show rows as a table, which JDQL's *Query* uses too.

- **In scope:**
  - `ActionResult.ROWS` in the console SPI, and its table in the page (Table / JSON switch, Copy as CSV);
  - the query mode's SQL options: aliases, several targets (`FROM`, `JOIN`, `UPDATE`, `DELETE FROM`, `INSERT INTO`),
    an identifier quote, the clauses and words SQL adds, ambiguous columns;
  - the *Mansart pools* panel's actions per pool: *Tables*, *Describe*, *Preview*, *Query*, *Execute*; its `sql`
    language per pool, from `DatabaseMetaData`;
  - JDQL's *Query* answering `ROWS`;
  - the documentation.
- **Out of scope:**
  - sub-queries and set operations (`UNION`) in completion and checks (they run; the editor checks the outer level);
  - reloading the vocabulary or the table lists without a dev reload;
  - editing a cell of a table in place;
  - stored procedures, `CALL`, several statements per call, transactions spanning calls;
  - any change to Mansart (the pools are reached through what the extension already exports to its `-dev` module).

## 2. A table of rows (console)

### 2.1 SPI

`PanelAction.ActionResult` gains the content type `ROWS = "application/x-rows+json"` and a factory
`ActionResult.rows(String summary, List<Column> columns, List<List<Object>> rows, boolean more)`, where
`record Column(String name, String type)`. The body it writes is
`{"columns": [{"name", "type"}…], "rows": [[…]…], "more": bool}`: each row has one value per column, a value is
`null`, a boolean, a number or a string (anything else is written with `String.valueOf`). The body stays within
`MAX_CONTENT`; rows that would pass it are left out and `more` is set. A name or a type is at most 200 characters.

### 2.2 The page

A result of type `ROWS` is drawn as a table:

- a header per column: its name, its type dimmed under it; the header stays visible while the rows scroll; the table
  scrolls horizontally inside the result;
- a `null` is shown `NULL`, dimmed, and is never confused with an empty string, shown as an empty cell;
- a value longer than 200 characters is cut with `…`, the whole value in the cell's `title`;
- under the rows, `more rows not shown` when `more` is true;
- the result's bar gains *Table* / *JSON* (the JSON viewer of today on the same body) and *Copy as CSV* (the rows as
  RFC 4180 CSV, `,` separated, header first, `NULL` written as an empty field);
- a `ROWS` body that is not of this shape is shown with the JSON viewer, as any other JSON.

The conversion to CSV and the check of the body's shape are pure functions of `editor-core.js`, so that GraalJS tests
them.

## 3. SQL in the query mode (`editor-core.js`)

The language JSON (query mode spec §2.2) gains optional dialect members; JDQL declares none of them and behaves as
today:

```json
"dialect": { …,
  "aliases": true,
  "targetAfter": ["FROM", "JOIN", "UPDATE", "INTO"],
  "identifierQuote": "\"",
  "clauses": ["SELECT", "FROM", "JOIN", "ON", "WHERE", "GROUP BY", "HAVING", "ORDER BY", "LIMIT", "OFFSET",
              "SET", "VALUES", "UPDATE", "DELETE FROM", "INSERT INTO"] }
```

- **Scope.** Every `targetAfter` word adds the name after it to the statement's scope (`DELETE FROM` through `FROM`,
  `INSERT INTO` through `INTO`, every `… JOIN` through `JOIN`). With `aliases`, a name that follows a target, or
  `AS` and a name, is that target's alias (`FROM tasks t`, `JOIN projects AS p`); a keyword is never an alias. The
  first target is still the statement's target for JDQL's rules; with several, a path's head is an alias, a target's
  name, or a column of a target in scope.
- **Quoted identifiers.** With `identifierQuote`, `"Order"` is a name (a doubled quote inside it is one quote), its
  text without the quotes is looked up; it is a token of kind `identifier`, `target`, `attribute` as a name would be.
  `isClosed` and the keystrokes take the language's quotes: a string's quote pairs and steps over as `'` does today,
  and so does the identifier quote. The JSON language keeps its own rule (a backslash escapes `"`).
- **Completion.** In a target position, the targets; after `alias.` or `table.`, that table's columns; in `SELECT`,
  `ON`, `WHERE`, `GROUP BY`, `HAVING`, `ORDER BY`, `SET`, the aliases and the targets in scope first, then the columns
  of every target in scope, each with its table in its detail (`String · column title · tasks`), then `self` when the
  dialect has one, the functions, the keywords. With `identifierQuote`, a name that is not a plain identifier
  (letters, digits and `_`, not starting with a digit) or that is a keyword of the dialect is inserted quoted
  (`"Order"`, `"due date"`).
- **Diagnostics.** As today (an unknown target; an unknown column of a known table or alias, the head or after a dot;
  a path through a column that is no reference; an open string; unbalanced parentheses; a name where a value goes is
  a literal), plus: an unknown alias before a dot (`x.title` with no `x` in scope) is an error, `unknown table or alias
  x`; a column named without its table that two targets in scope have is a **warning**, `title is in tasks and
  projects`. A target written as a qualified name (`public.tasks`) is looked up as written, then without its schema.
- **`parameters()`** keeps its rules; a path `t.price` is the attribute `price` of `t`'s table.
- **Formatting.** Each clause of `clauses` on a line of its own (`LEFT JOIN … ON` on one line), `AND`/`OR` indented;
  keywords in capitals; quoted identifiers, strings, numbers and names copied as written.

## 4. The Mansart pools panel

### 4.1 Actions

Per pool (`@Default`, then the named pools in name order), a group named after the pool holds five actions:

| Action | Arguments | Result |
|---|---|---|
| *Tables* | none | `ROWS`: schema, name, kind (`TABLE`, `VIEW`…), number of columns, of the pool's catalog and its user schemas (the system ones left out), in schema then name order; a `replay` column fills *Describe* and *Preview* with the table |
| *Describe* | `table`, one of the tables listed at boot | `ROWS`: name, SQL type with its size, nullable, default, primary key position, foreign key `table.column`; then, in the summary, its indexes (`idx_tasks_status (status)`) |
| *Preview* | `table`; `limit`, an integer from 1 to 1000, 100 by default | `ROWS`: the first rows, `SELECT * FROM <table, quoted as the database quotes it>` with `setMaxRows(limit)`, in a transaction rolled back |
| *Query* | `sql` (the query editor, `x-language: "sql"`), `params` (`x-parameters-of: "sql"`) | `ROWS`, at most 100 rows (the 101st sets `more`), in a transaction always rolled back |
| *Execute* | `sql`, `params`, `transaction` (`rollback` or `commit`) | the update count, or `ROWS` when the statement answers rows; confirmed first; rolled back unless `commit` |

*Execute*'s confirmation reads `Runs this SQL on <pool>. A DDL statement (CREATE, ALTER, DROP, TRUNCATE…) may be
committed by the database itself whatever is chosen.`

### 4.2 Running SQL

- A call takes a connection from the pool (`getConnection()`), sets `autoCommit` off, runs, then commits only when
  *Execute* says `commit`, rolls back otherwise (and always when the call throws), and gives the connection back with
  its `autoCommit` restored.
- `Statement.setQueryTimeout(30)`; a timeout is an error result, `the statement ran past 30 s and was cancelled`.
- Named parameters `:name` are replaced by `?` outside strings, quoted identifiers and comments, in order, and bound
  from `params` (a JSON object): a JSON string, number, boolean or `null` as `setObject`, an array as `setArray`
  where the driver supports it; a `:name` missing from `params` is refused before running, `missing parameter name`.
- *Query* runs only a statement whose first word (comments skipped) is `SELECT`, `WITH`, `VALUES`, `SHOW`, `EXPLAIN`
  or `TABLE`; anything else is refused before running, `Query only reads: use Execute`. A text holding more than one
  statement (a `;` outside strings, identifiers and comments, other than a final one) is refused by both,
  `one statement at a time`.
- An `SQLException` is an error result whose summary is the database's message (at most 200 characters) and whose
  details give its `SQLState` and vendor code; never the URL, the user or the password.
- The values of a row are written as JSON: numbers as numbers (a `BigDecimal` or a `long` past 2^53 as a string),
  booleans, strings, temporal values as ISO text, binary as `0x…` hex of at most 64 bytes followed by `…`, anything
  else with `toString()`.

### 4.3 The `sql` language

Each pool publishes `PanelLanguage("sql-<pool>")` — `sql-default` for `@Default` — read at boot from
`DatabaseMetaData`:

- `targets`: the tables and views of the pool's current schema by bare name, and those of the other user schemas by
  `schema.name`; a target's `detail` is `table` or `view`, with its schema;
- its `attributes`: the columns, each `type` from its `java.sql.Types` (`integer`, `number`, `string`, `boolean`;
  date and time types `string` with `format` `date`, `time` or `date-time`), `detail` `<SQL type> · column`, and for a
  column of a single-column foreign key, `target` the referenced table;
- `dialect`: SQL-92 keywords plus `getSQLKeywords()`, the common functions (`COUNT`, `SUM`, `AVG`, `MIN`, `MAX`,
  `UPPER`, `LOWER`, `LENGTH`, `TRIM`, `COALESCE`, `CAST`, `ABS`, `ROUND`, `SUBSTRING`, `NOW`, `CURRENT_DATE`…),
  `aliases: true`, `quote: "'"`, `identifierQuote` from `getIdentifierQuoteString()` (`"` when it is blank), the
  clauses of §3;
- past 1 MiB, written again without details, then without types, then the panel offers none and says why at WARNING,
  as JDQL's language does.

The *Query* and *Execute* `sql` property is `{"type": "string", "format": "textarea", "contentMediaType":
"text/x-query", "x-language": "sql-<pool>"}`; `params` has `"x-parameters-of": "sql"`. A pool whose metadata cannot
be read at boot gets no language (WARNING, without the URL) and its actions still work.

### 4.4 JDQL

Mansart Data's *Query* answers `ROWS`: the columns its JSON result has today, in order, their types from the
attributes (or `object` for an entity); *Export CSV* and *Update / Delete* are unchanged.

## 5. Tests

- **SPI:** `ActionResult.rows` writes the body, refuses a row of another width, a name past 200 characters, and cuts
  rows past `MAX_CONTENT` with `more`.
- **`editor-core.js` under GraalJS:** the body check and the CSV of a `ROWS` body (quotes, separators, line ends,
  `NULL`); with a SQL fixture: aliases (`t`, `AS p`), joins, every target in scope, `"Quoted"` names and doubled
  quotes, completion after `t.` and in an `ON`, an unknown alias, an ambiguous column as a warning, a qualified
  target, `parameters()` through an alias, formatting of a `LEFT JOIN … ON`; every existing JDQL test unchanged.
- **Console:** `PageTest` (the table, the switch, Copy as CSV, no markup sink); a malformed `ROWS` body shown as JSON.
- **Mansart pools `-dev`, on H2:** *Tables*, *Describe* (a primary key, a foreign key, an index), *Preview* and its
  limit; *Query* reading, refusing a write and a second statement, a `:param` bound, a missing one refused; *Execute*
  rolled back then committed, checked by reading the table again; a timeout; an `SQLException` without the URL; the
  `sql` language (targets, schema-qualified ones, types, a foreign key as a reference, keywords of the product, the
  identifier quote).
- **Mansart Data `-dev`:** *Query* answers `ROWS`.
- **In the browser**, on `lc4jcdi-on-vidocq/mcp-tasks-server` (PostgreSQL, ports 18093/18094): the `@Default` tab's
  *Tables*, *Describe* of the tasks table, *Preview*; the SQL editor's colours, completion of tables, an alias, a
  `JOIN … ON`, an unknown column; *Query* as a table, *Table*/*JSON*, *Copy as CSV*; *Execute* of an `UPDATE`
  rolled back then checked unchanged; JDQL's *Query* as a table.

## 6. Documentation

With the `[.tag-new]#NEW#` badge: the *Mansart pools* section of the extensions page (the tables, the SQL editor, its
transactions and limits), `dev-console.adoc` (the table of rows, the query mode's SQL options for a panel's author),
the Javadoc of `ActionResult.ROWS`, and an entry in `whats-new.adoc`.

## 7. Review focus

- A statement that is not what it looks like: a write hidden after a comment or in a `WITH … DELETE`, a `;` inside a
  string, a `:name` inside a string or a quoted identifier — *Query* never writes, the parameters are bound where
  they are.
- A database that answers slowly or not at all, or a pool exhausted: the action ends within its timeout, the
  connection goes back to the pool, the panel's other actions still answer.
- Values the JSON body cannot hold as such: a `NUMERIC(38)`, a `BLOB`, a timestamp with a zone, an array, a value of
  a type the driver invents — the table shows them, the body stays valid JSON.
- A schema with hundreds of tables, or names that need quoting (`"Order"`, a space, mixed case): the language stays
  within its limit or degrades, and completion inserts the name as the database needs it.
- JDQL's editor and checks behave exactly as before, with none of SQL's options in its dialect.
