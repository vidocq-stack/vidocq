# Table views and a SQL editor in Mansart pools, and a table for rows — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The dev console learns to show a result of rows as a table (`ActionResult.ROWS`), its query mode learns SQL
(aliases, several tables, quoted names, comments), the *Mansart pools* panel gives each pool a tab of five actions —
*Tables*, *Describe*, *Preview*, *Query*, *Execute* — with a `sql-<pool>` language read from `DatabaseMetaData`, and
JDQL's *Query* answers rows too.

**Architecture:** The console SPI gains `ActionResult.ROWS`, `ActionResult.Column` and `ActionResult.rows(…)`, whose
package-private `RowsBody` writes a body that stays within 256 KiB. The pure `editor-core.js` gains `readRows` and
`rowsCsv` (GraalJS-tested in `RowsCoreTest`), and its query mode a SQL path, `readSql`, switched on by a dialect with
`"aliases": true` — JDQL keeps its own code path, untouched — with completion, diagnostics and formatting of their own
(`SqlLanguageTest`); `console.js` draws rows with `rowsBody`/`rowsTable`. The pools `-dev` module gains `SqlText`
(what a SQL text holds outside strings and comments), `SqlRunner` and `SqlValues` (one statement on a pooled
connection, its transaction, its values), `PoolMetadata` (what the boot reads of the tables), `PoolActions` (each
pool's tab) and `SqlLanguage`, all tested on H2 through the real pool extension. Mansart Data's `JdqlActions` answers
`ROWS` for entities and projections.

**Tech Stack:** Java 25, JDBC (`java.sql`), Maven 3.9, JUnit 5, H2 2.3.232 (test scope, already in the pools `-dev`
module), GraalJS `org.graalvm.polyglot` 25.4.4.1.1 (test scope, already in the console module), vanilla JavaScript
(ES2022 modules, DOM APIs only, no build, no library), CSS custom properties, AsciiDoc.

**Spec:** `docs/superpowers/specs/2026-10-01-devconsole-sql-design.md` — the binding authority; § numbers below refer
to it. The two previous pieces, whose code and conventions this plan extends, are
`docs/superpowers/plans/2026-09-30-devconsole-editor-json.md` and
`docs/superpowers/plans/2026-09-30-devconsole-query-mode.md`.

## Global Constraints

- **Paths.** `$VIDOCQ` is this repository's checkout, `$JAVA25` a Java 25 home (Temurin), `$LC4JCDI` a checkout
  of LC4JCDI-on-vidocq, `$SCRATCH` a scratch directory outside the repository.
- **Toolchain.** Java 25: every Maven command is `JAVA_HOME=$JAVA25 mvn -nsu …`,
  run from the repository root `$VIDOCQ`. Never `./mvnw` or `mvnw`.
- **Branch.** `feat/devconsole-sql`, already checked out. Do not switch branches. Never push.
- **Module paths** (commands spell them out in full; the steps name files with these prefixes):
  - SPI: `vidocq-runtime-devconsole-spi`; SPIM its main package directory
    `SPI/src/main/java/io/vidocq/runtime/spi/devconsole`, SPIT its test one `SPI/src/test/java/io/vidocq/runtime/spi/devconsole`
  - DC: `vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension`
  - DCT: `DC/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole`
  - PAGE: `DC/src/main/resources/META-INF/resources/devconsole` (`console.css`, `console.js`, `editor-core.js`,
    `editor.js`)
  - MD: `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev`
  - MDM: `MD/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev`
  - MDT: `MD/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev`
  - PD: `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-pool-extension-dev`
  - PDM: `PD/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/pool/dev`
  - PDT: `PD/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/pool/dev`
  - EX: `vidocq-runtime-examples/vidocq-runtime-mansart-h2-example`; EXT its test package directory
    `EX/src/test/java/io/vidocq/runtime/examples/mansart`
  - DOCS: `docs/en/modules/ROOT/pages`
- **The page loads nothing from another site; CSP stays `default-src 'self'`.** No `innerHTML`, `outerHTML`,
  `insertAdjacentHTML`, `document.write`, `eval(`, `new Function`, `setAttribute("style"`, `srcdoc`, `import(` in any
  script (PageTest checks every `.js`); every text through `textContent`, a text node or an attribute value; no `url(`
  and no `@import` in CSS; `localStorage` only inside a `try`, and only in `console.js` (`stored`/`store`).
- **`editor-core.js` never references `document`, `window`, `navigator`, `requestAnimationFrame`, `localStorage`,
  `globalThis` or any DOM API, and imports nothing** (PageTest checks it with comments stripped).
- **Imports:** `console.js` imports `./editor.js` and no other script; `editor.js` imports `./editor-core.js` only, and
  re-exports from it with `export { … } from "./editor-core.js";`; `index.html` still loads `console.js` only.
- **Theme:** no new colour variable. The table of rows and a SQL comment use the existing tokens (`--sunk`, `--grid`,
  `--rule`, `--ink`, `--muted`, `--faint`), which every theme block already defines.
- **Code style:** English; lines at most 120 characters in new code; the comment density of the file around;
  sentence-like test names (`aQueryRefusesAWriteAndASecondStatementBeforeAnythingRuns`). Every new Java file starts
  with the repository's license header, copied verbatim in this plan.
- **Keep verbatim** (PageTest pins them): `const REPLAY_COLUMN = "replay"`, `const MASKED = "***"`,
  `const FILTER_FROM = 10`, `"Exchange"`, `function fileChooser(target)` placed right before `jsonField`,
  `} else if (kind === "string" && definition.format === "textarea") {`, `input = el("textarea", "json-text");`,
  `input.rows = 4;`, `wrap.classList.add("wide");`,
  `if (definition.contentMediaType === "text/csv") chooser = fileChooser(input);`,
  `object[property] = fileText ? fileText() : input.value;`, `...choosers]) c.disabled = on;`,
  `Object.hasOwn(properties, name)`, `Object.hasOwn(object, property) ? object[property] : undefined`,
  `diagnostics = safely(() => language.diagnose(text, data), []);`,
  `export { jsonLanguage, queryLanguage, FORMAT_EXAMPLES };` in `editor.js`, and exactly three lines holding
  `= Object.create(null);` in `console.js`.
- **JDQL behaves as before:** every test of `QueryLanguageTest` and `EditorCoreTest` passes unchanged after each task;
  a dialect without `"aliases": true` takes the code path of today.
- **A pool's secrets are never shown:** no result, detail or log of the pools panel carries a pool's URL or password
  (`PoolConfig.toString()` prints the password: never log a `PoolConfig`).
- **No new dependency.** The pools `-dev` module gains `requires java.sql;` and nothing else; H2 stays test-only.
- **Ports:** only 18090-18099 for anything this plan starts (app 18093, console 18094); never bind 8080 or 8888. Check
  with `lsof` first. Never kill a process this plan did not start.
- **Commits:** every commit is `git commit -S -F <msgfile>` (GPG-signed, message from a file; never `-m`, never `-s`),
  a conventional prefix (`feat`, `fix`, `test`, `docs`), the message ending with exactly these three lines:
  ```
  Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
  ```
  Write the message into `.git/PLAN_COMMIT_MSG` (inside `.git`, never tracked) with a heredoc, commit, delete the file.
  If GPG signing fails, stop and ask the user; never commit unsigned.
- **Docs:** every new section carries a literal `[.tag-new]#NEW#` badge, and `whats-new.adoc` gets one new entry.
- **How a step edits a file.** "Create `X`:" writes the block as the whole file. "In `X`, replace: … with: …" replaces
  the first block, which occurs exactly once in the file at that point, with the second (use the Edit tool). Each task's
  replacements assume the tasks before it, and the replacements before it in the same step, are done.

## Rulings (where this plan settles what the spec leaves open)

1. **A replay that fills Describe and Preview (§4.1).** *Tables* has two columns named `replay`
   (`PanelSample.REPLAY_COLUMN`), one filling *Describe*, one *Preview*, each cell `<key>.describe {"table": "tasks"}`.
   The page reads a `replay` column of rows as it reads a sample table's (`replayButton`), names each button after
   the action it fills (`Describe`, `Preview`) and leaves the column's header blank. A table that was not listed at
   boot (created since) gets empty replay cells: *Describe* and *Preview* only take the boot's tables.
2. **The page needs the panel of a result** to find the action a replay names: `answerBody`, `resultOutput`,
   `inlineOutlet` and `resultBlock` take `panelId` too; PageTest's pins of their signatures change with them.
3. **Table or JSON is kept per result** in a `WeakMap` keyed by the result's fold-state map (`rowsViews`): a poll that
   redraws the same result keeps the view chosen; *Expand all* (which clears the fold state) does not reset it.
4. **CSV (§2.2).** `rowsCsv` writes every column, a `replay` one included (the body's data, as *JSON* shows it); an
   empty string is `""` and `NULL` an empty field, so that the two stay apart; every line ends with CRLF. `readRows`
   keeps an integer past 2^53 as the text the server wrote where the browser gives `JSON.parse` a value's source.
5. **The SQL switch (§3).** `"aliases": true` makes a dialect SQL's: besides aliases and several targets, `--` and
   `/* */` comments (tokens of kind `comment`, coloured `--faint`, never seen by the checks), the operators `::`, `||`
   and `%`, targets named at the outer level of parentheses only (`EXTRACT(YEAR FROM d)` names none), a sub-query,
   a `WITH name AS (…)` table, an alias of the select list, a cast's type and an unknown function left unchecked. JDQL's
   path, `read()` as today, never runs any of it.
6. **Three dialect members the spec does not list:** `"unquotedCase": "lower" | "upper"` (from
   `storesLowerCaseIdentifiers()` / `storesUpperCaseIdentifiers()`), without which neither a lookup of `TASKS` nor the
   quoting of a mixed-case `"Order"` can be right (§7: "completion inserts the name as the database needs it");
   `"self": null`, SQL having no self (a word the dialect leaves out takes JDQL's, `this`); and a target's
   `"schema"`, so that `sales.orders` is inserted as `sales.orders` and `"My Schema"."t"` with its quotes. Without
   `unquotedCase`, an unquoted SQL name is looked up as written, then ignoring case.
7. **A name where a value goes stays a literal (§3), unless it is a path from an alias or a table in scope:**
   `ON p.id = t.project_id` checks and colours both sides; `WHERE status = OPEN` leaves `OPEN` unchecked.
8. **A qualified target found neither as written nor without its schema is not checked**, nor are its columns, as
   JDQL's qualified names are not: a schema the language leaves out (`information_schema`) raises no error.
9. **SQL's messages:** `unknown target taks`, `unknown column titel of tasks`, `unknown column nope of tasks t or
   projects p` (a bare name, every target known), `unknown table or alias x`, `title is not a reference`, the warning
   `title is in tasks t and projects p` (a target named with its alias when it has one), and two errors the spec does
   not list: `unterminated identifier` and `unterminated comment`. JDQL's messages are unchanged.
10. **Completion (§3)** also works inside a quoted name being typed (`"Or|"` → `"Order"`), offering names only there,
    and after `schema.` where a target goes (`FROM sales.|` → `orders`). An alias item's detail is `alias of tasks`.
11. **Formatting (§3):** in SQL only the outer level's clauses start a line; a join's line starts with its `LEFT`,
    `INNER`… and holds its `ON`; `::` takes no space; a comment is copied, and what follows a `--` comment starts a
    line. Format refuses an unterminated quoted name or comment as it refuses a string.
12. **Keystrokes get the data:** `keystroke(language, text, start, end, key, data)` and the new export
    `pairsOf(language, data)` (a query's pairs: `()`, its quote's, its identifier quote's); `QUERY.pairs` stays
    `["()", "''"]`.
13. **SPI (§2.1):** `rows(summary, columns, List<? extends List<?>> rows, more)`; a column's type may be empty; columns
    that alone pass 256 KiB are refused; a `Number` whose text JSON cannot write (`NaN`) is written as text.
14. **Pool keys:** `default` for `@Default`; a named pool's name in lower case, every character but `[a-z0-9-]` made
    `-`, `p-` first when it starts with no letter, at most 30 characters, `-2`… on a clash. Action ids
    `<key>.tables` …, language `sql-<key>`, group the pool's label (cut to 40).
15. **Describe and Preview exist when the boot listed a table** (an argument of allowed values needs one); Preview's
    `limit` is a text field matching `|[1-9][0-9]{0,2}|1000`, empty meaning 100. Preview and Query read
    `setMaxRows(limit + 1)` rows, the last one only setting `more`.
16. **Query and the metadata reads also set the connection read-only** (restored after): PostgreSQL then refuses a
    write hidden in a `WITH … DELETE … RETURNING` before the rollback has to undo it.
17. **Failures (§4.2):** the line is the database's message on one line, the pool's URL, its password (four
    characters or more) and its user after the word `user` masked; the body is the whole message, masked;
    *Exchange* holds the statement, `params`, the transaction, `sqlState` and `vendorCode`. A statement cancelled
    (`SQLTimeoutException` or SQLState `57014`) says `the statement ran past 30 s and was cancelled`.
18. **Values (§4.2):** a `BigDecimal` as `toPlainString()`; a text cut past 10 000 characters (the page shows 200);
    a CLOB's first 10 000; an array as the JSON of its elements; a timestamp with a zone (by its JDBC type or a type
    name `timestamptz` / `… WITH TIME ZONE`) with its offset.
19. **Lines:** Query `3 rows in 12 ms` (`first 100 rows …`, `no row …`); Execute `2 rows · rolled back` or, with rows,
    `1 row in 4 ms · committed`; Tables `3 tables, 1 view`; Describe `6 columns · indexes: …` or `· no index`.
20. **The language (§4.3):** SQL-92's reserved words and ten words of SQL:2003 (`LIMIT`, `OFFSET`, `NULLS`,
    `RECURSIVE`, `RETURNING`, `ILIKE`, `OVER`, `PARTITION`, `LATERAL`, `FILTER`), then the product's; the functions
    without `CURRENT_DATE`, a keyword written without parentheses; an empty database still gets its language.
21. **JDQL (§4.4):** entities and projections answer `ROWS`; a count, an aggregate and *Update / Delete* stay JSON
    (they have no columns). A column's type is its attribute's JSON Schema format or type (`date`, `integer`); a
    projected column of entities is `object`, each written as its JSON text; a `BigDecimal` stays a JSON number, as
    today. The H2 example's `DevConsoleSnapshotTest`, which reads JDQL's body, reads rows.
22. **When the tables are read:** by the first `actions()` (or `languages()`) of a boot, one connection per pool;
    `start` and `stop` forget them. A pool that cannot be read logs one WARNING without its URL.
23. **The example's `checkpom`** complains about a codegen bundle of `vidocq-runtime-ravel-config-extension` that this
    plan does not touch; the example is verified with `-Dvidocq.checkpom.failOnMissing=false`.

## Review Focus

1. **A statement that is not what it looks like** — a write after a comment (`/* SELECT */ DELETE`), a `;` or a `:name`
   inside a string, a quoted identifier or a comment, a `WITH … DELETE … RETURNING`: *Query* never writes and binds
   what it binds where it is. Pinned by Task 8's `SqlTextTest` (all three readings),
   `aQueryRefusesAWriteAndASecondStatementBeforeAnythingRuns` and
   `aNamedParameterIsBoundWhereItIsAndAMissingOneIsRefused`; the PostgreSQL case by Task 13's check (a read-only
   transaction refuses it).
2. **A database that answers slowly, not at all, or a pool exhausted** — the action ends within its timeout, the
   connection goes back as it was, the panel still answers. Pinned by Task 8's
   `aStatementPastItsTimeoutIsCancelledAndSaysSo` and `theConnectionGoesBackToThePoolAsItWasWhateverTheCallDid`, and
   Task 9's `aPoolWhoseTablesCannotBeReadKeepsTablesQueryAndExecuteAndNeverShowsItsUrl`.
3. **Values the JSON body cannot hold as such** — `NUMERIC(38)`, binary, a timestamp with a zone, an array, a long past
   2^53, a `NaN`: the body stays valid JSON and the table shows them. Pinned by Task 1's
   `aValueThatIsNoJsonScalarIsWrittenAsItsText`, Task 8's `aValueJsonCannotHoldAsSuchIsWrittenAsText`, Task 2's
   `RowsCoreTest`.
4. **A schema of hundreds of tables, or names that need quoting** (`"Order"`, a space, mixed case, a keyword): the
   language degrades within its limit, completion inserts each name as the database needs it, a lookup folds as the
   database does. Pinned by Task 10's `aLanguagePastItsLimitIsWrittenWithoutDetailsThenWithoutTypesThenNotAtAll` and
   Tasks 4-5's `anUnquotedNameIsLookedUpAsTheDatabaseStoresIt` and
   `whereATargetGoesTheTargetsWrittenAsTheDatabaseNeedsThem`.
5. **JDQL's editor and checks unchanged** — none of SQL's options in its dialect. Pinned by `QueryLanguageTest` and
   `EditorCoreTest`, run unchanged in Tasks 4-7, and by Task 11 leaving *Update / Delete* and *Export CSV* as they are.

## File Structure

| File | Change | Responsibility |
|------|--------|----------------|
| `SPIM/PanelAction.java` | modify | `ActionResult.ROWS`, `Column`, `rows(…)` (Task 1) |
| `SPIM/RowsBody.java` | create | the body of rows, within 256 KiB (Task 1) |
| `SPIT/ActionResultTest.java` | modify | Task 1 |
| `PAGE/editor-core.js` | modify | `readRows`, `rowsCsv` (2); SQL tokens, scope, quotes, `pairsOf` (4); completion (5); diagnostics (6); formatting (7) |
| `DCT/RowsCoreTest.java` | create | GraalJS: the body check and the CSV (Task 2) |
| `PAGE/console.js`, `PAGE/editor.js`, `PAGE/console.css` | modify | the table of rows (3); keystrokes with data, `.ed-comment` (4) |
| `DCT/PageTest.java` | modify | Tasks 3, 4 |
| `DCT/SqlLanguageTest.java` | create | GraalJS: the SQL options of the query mode (Tasks 4-7) |
| `PD/src/main/java/module-info.java` | modify | `requires java.sql` (Task 8) |
| `PDM/Json.java`, `PDM/SqlText.java`, `PDM/SqlValues.java`, `PDM/SqlRunner.java` | create | Task 8 (`SqlRunner.read` in 9) |
| `PDT/TestPools.java`, `PDT/SqlTextTest.java`, `PDT/SqlRunnerTest.java` | create | Task 8 |
| `PDM/PoolMetadata.java`, `PDM/PoolActions.java`, `PDT/PoolActionsTest.java` | create | Task 9 |
| `PDM/PoolsLivePanel.java` | modify | `start`, `stop`, `actions()` (9); `languages()` (10) |
| `PDM/SqlLanguage.java`, `PDT/PoolLanguageTest.java` | create | Task 10 |
| `MDM/EntityJson.java`, `MDM/ResultJson.java`, `MDM/JdqlActions.java`, `MDT/JdqlActionsTest.java`, `EXT/DevConsoleSnapshotTest.java` | modify | Task 11 |
| `SPIM/PanelLanguage.java` (Javadoc), `DOCS/dev-console.adoc`, `DOCS/modules/vidocq-runtime-extensions.adoc`, `DOCS/whats-new.adoc` | modify | Task 12 |

**Testing honesty.** The pure parts have tests: the SPI's body (`ActionResultTest`), `editor-core.js` under GraalJS
(`RowsCoreTest`, `SqlLanguageTest`, and `QueryLanguageTest` / `EditorCoreTest` unchanged), the pools `-dev` module on
H2 through the real Mansart pool (`SqlTextTest`, `SqlRunnerTest`, `PoolActionsTest`, `PoolLanguageTest`), Mansart
Data's `JdqlActionsTest` and the H2 example's `DevConsoleSnapshotTest`. `console.js`'s table and `editor.js`'s
keystrokes have no JavaScript harness: `PageTest` pins their rules as text, and Task 13's browser check, on
PostgreSQL, is what proves they behave. **While planning, the page was not run in a browser and no PostgreSQL was
used** (starting a server was out of bounds for the planner): every item of Task 13 is a first run, and its failures
are fixed in the owning task's files.

**H2 in the tests** is told `DATABASE_TO_LOWER=TRUE`, so that it stores unquoted names in lower case as PostgreSQL
does; its messages may come in the JVM's language first, then in English: tests match the English part.

**The editor draws in `requestAnimationFrame`.** A Chrome tab driven by the MCP extension may report
`document.visibilityState === "hidden"` and not run animation frames until a screenshot is taken: in Task 13 take a
screenshot (or a zoom) before reading what the editor drew.

---
### Task 1: `ActionResult.ROWS`, its columns and its body, in the console SPI (§2.1)

**Files:**
- Modify: `SPIM/PanelAction.java` (the `ActionResult` record: its Javadoc, `ROWS`, the content-type check, `rows`,
  `Column`)
- Create: `SPIM/RowsBody.java`
- Modify: `SPIT/ActionResultTest.java`

**Interfaces:**
- Consumes: `PanelAction.ActionResult` (`MAX_CONTENT` 256 KiB, `TEXT`, `JSON`, `CSV`), `PanelSample.REPLAY_COLUMN`.
- Produces: `public static final String ActionResult.ROWS = "application/x-rows+json"`;
  `public static ActionResult rows(String summary, List<Column> columns, List<? extends List<?>> rows, boolean more)`;
  `public record ActionResult.Column(String name, String type)` with `public static final int MAX_NAME = 200`;
  package-private `RowsBody.write(List<Column>, List<? extends List<?>>, boolean)`. The body is
  `{"columns":[{"name":…,"type":…}…],"rows":[[…]…],"more":true|false}`, compact, at most 256 KiB.

- [ ] **Step 1: Write the failing tests**

In `SPIT/ActionResultTest.java`, replace:
```java
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import org.junit.jupiter.api.Test;
```
with:
```java
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.Column;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
```

In `SPIT/ActionResultTest.java`, replace:
```java
                () -> new ActionResult("ok", "text/csv;charset=utf-8", "a", false, null));
        assertEquals("a result's content type is text/plain, application/json or text/csv", refused.getMessage());
    }
```
with:
```java
                () -> new ActionResult("ok", "text/csv;charset=utf-8", "a", false, null));
        assertEquals("a result's content type is text/plain, application/json, text/csv or application/x-rows+json",
                refused.getMessage());
    }

    @Test
    void aRowsResultIsItsColumnsItsRowsAndWhetherThereAreMore() {
        ActionResult rows = ActionResult.rows("2 rows in 3 ms",
                List.of(new Column("id", "BIGINT"), new Column("title", "VARCHAR(200)"), new Column("done", "")),
                List.of(List.of(1, "it's \"done\"", true), Arrays.asList(9007199254740993L, "", null)), false);

        assertEquals("application/x-rows+json", ActionResult.ROWS);
        assertEquals(ActionResult.ROWS, rows.contentType());
        assertEquals("2 rows in 3 ms", rows.summary());
        assertFalse(rows.error());
        assertEquals("{\"columns\":[{\"name\":\"id\",\"type\":\"BIGINT\"},{\"name\":\"title\",\"type\":"
                + "\"VARCHAR(200)\"},{\"name\":\"done\",\"type\":\"\"}],\"rows\":[[1,\"it's \\\"done\\\"\",true],"
                + "[9007199254740993,\"\",null]],\"more\":false}", rows.body(), "a long is written whole");
        assertEquals("{\"columns\":[],\"rows\":[],\"more\":true}",
                ActionResult.rows(null, List.of(), List.of(), true).body(), "more as given");
    }

    @Test
    void aValueThatIsNoJsonScalarIsWrittenAsItsText() {
        String body = ActionResult.rows("1 row", List.of(new Column("a", ""), new Column("b", ""),
                        new Column("c", ""), new Column("d", ""), new Column("e", "")),
                List.of(List.of(LocalDate.of(2026, 10, 1), Double.NaN, new BigDecimal("1E+3"), 'x', "a\nb\u0001")),
                false).body();

        assertTrue(body.contains("[[\"2026-10-01\",\"NaN\",1E+3,\"x\",\"a\\nb\\u0001\"]]"), body);
    }

    @Test
    void aRowOfAnotherWidthOrANameOrATypePast200CharactersIsRefused() {
        List<Column> two = List.of(new Column("a", ""), new Column("b", ""));

        IllegalArgumentException width = assertThrows(IllegalArgumentException.class,
                () -> ActionResult.rows("x", two, List.of(List.of(1, 2), List.of(3)), false));
        assertEquals("row 1 has 1 values for 2 columns", width.getMessage());
        assertEquals("y".repeat(200), new Column("y".repeat(200), "t").name());
        assertThrows(IllegalArgumentException.class, () -> new Column("y".repeat(201), "t"));
        assertThrows(IllegalArgumentException.class, () -> new Column("y", "t".repeat(201)));
        assertThrows(NullPointerException.class, () -> new Column(null, "t"));
    }

    @Test
    void rowsPastTheBodyLimitAreLeftOutAndSaySo() {
        List<List<Object>> many = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            many.add(List.of("v".repeat(100)));
        }

        String body = ActionResult.rows("3000 rows", List.of(new Column("text", "")), many, false).body();

        assertTrue(body.length() <= ActionResult.MAX_CONTENT, "never truncated: " + body.length());
        assertTrue(body.length() + ",[\"\"]".length() + 100 > ActionResult.MAX_CONTENT, "as many rows as fit");
        assertTrue(body.endsWith("\"]],\"more\":true}"), body.substring(body.length() - 40));
        assertFalse(body.contains(ActionResult.TRUNCATED), "whole rows only, no marker");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -q -pl vidocq-runtime-devconsole-spi test 2>&1 | grep -E "ERROR.*(cannot find symbol|symbol:)|BUILD" | head -6
```
Expected: FAIL at `testCompile` — `ActionResultTest.java:[23,65] cannot find symbol` (the import of
`ActionResult.Column`), then more `cannot find symbol` lines.

- [ ] **Step 3: Write `ROWS`, `Column`, `rows` and `RowsBody`**

In `SPIM/PanelAction.java`, replace:
```java
     *                    {@code null} reads as {@code done}
     * @param contentType {@value #TEXT}, {@value #JSON}, which the page pretty-prints, or {@value #CSV}, which it
     *                    shows as text with a Download button; {@code null} when there is no body,
     *                    {@value #TEXT} when there is one and none was given
     * @param body        what to show, or {@code null}; at most {@value #MAX_CONTENT} characters, a longer one
```
with:
```java
     *                    {@code null} reads as {@code done}
     * @param contentType {@value #TEXT}, {@value #JSON}, which the page pretty-prints, {@value #CSV}, which it
     *                    shows as text with a Download button, or {@value #ROWS}, which it draws as a table;
     *                    {@code null} when there is no body, {@value #TEXT} when there is one and none was given
     * @param body        what to show, or {@code null}; at most {@value #MAX_CONTENT} characters, a longer one
```

In `SPIM/PanelAction.java`, replace:
```java
        public static final String CSV = "text/csv";
        /** What ends a body or details that was truncated. */
```
with:
```java
        public static final String CSV = "text/csv";
        /**
         * A table of rows, such as the answer of a SQL query: the page draws it as a table, a column's type dimmed
         * under its name, a {@code null} as a dimmed {@code NULL}, with a switch to its JSON viewer and Copy as CSV.
         * The body is {@code {"columns": [{"name": …, "type": …}…], "rows": [[…]…], "more": bool}}, each row one value
         * per column, a value {@code null}, a boolean, a number or a string; {@link #rows} writes it. A body of
         * another shape is shown as any other JSON. A column named {@link PanelSample#REPLAY_COLUMN replay} holds
         * replays, as a sample table's does: each is a button, named after its action, that fills that action's form.
         */
        public static final String ROWS = "application/x-rows+json";
        /** What ends a body or details that was truncated. */
```

In `SPIM/PanelAction.java`, replace:
```java
            if (contentType != null && !TEXT.equals(contentType) && !JSON.equals(contentType)
                    && !CSV.equals(contentType)) {
                throw new IllegalArgumentException("a result's content type is " + TEXT + ", " + JSON + " or " + CSV);
            }
```
with:
```java
            if (contentType != null && !TEXT.equals(contentType) && !JSON.equals(contentType)
                    && !CSV.equals(contentType) && !ROWS.equals(contentType)) {
                throw new IllegalArgumentException("a result's content type is " + TEXT + ", " + JSON + ", " + CSV
                        + " or " + ROWS);
            }
```

In `SPIM/PanelAction.java`, replace:
```java
            return new ActionResult(summary, null, null, false, null);
        }
```
with:
```java
            return new ActionResult(summary, null, null, false, null);
        }

        /**
         * A {@link #ROWS} result: a line, and a body of these columns and rows that stays within
         * {@value #MAX_CONTENT} characters, the rows that would pass it left out and {@code more} set.
         *
         * @param summary the line, such as {@code 3 rows in 12 ms}, or {@code null} for {@code done}
         * @param columns the columns, in order
         * @param rows    the rows, in order, each one value per column: {@code null}, a {@link Boolean}, a
         *                {@link Number} or a {@link String}; anything else, a number JSON cannot write such as
         *                {@code NaN} included, is written as its {@link String#valueOf}
         * @param more    whether there are rows past those given
         * @return the result
         * @throws IllegalArgumentException when a row has another number of values than there are columns, or when
         *                                  the columns alone pass {@value #MAX_CONTENT} characters
         */
        public static ActionResult rows(String summary, List<Column> columns, List<? extends List<?>> rows,
                                        boolean more) {
            return new ActionResult(summary, ROWS, RowsBody.write(columns, rows, more), false, null);
        }

        /**
         * A column of a {@link #ROWS} result.
         *
         * @param name its name, such as {@code title}, at most {@value #MAX_NAME} characters
         * @param type its type, shown dimmed under its name, such as {@code VARCHAR(200)}, at most
         *             {@value #MAX_NAME} characters; empty for none
         */
        public record Column(String name, String type) {

            /** The longest name or type of a column. */
            public static final int MAX_NAME = 200;

            public Column {
                Objects.requireNonNull(name, "name");
                Objects.requireNonNull(type, "type");
                if (name.length() > MAX_NAME || type.length() > MAX_NAME) {
                    throw new IllegalArgumentException("a column's name and type are at most " + MAX_NAME
                            + " characters");
                }
            }
        }
```

Create `SPIM/RowsBody.java`:

```java
/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.spi.devconsole;

import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.Column;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The body of an {@link ActionResult#ROWS} result, written by hand: this module depends on no JSON library. It stays
 * within {@link ActionResult#MAX_CONTENT} characters: the rows that would pass it are left out and {@code more} set,
 * so that the body is never truncated in the middle of its JSON.
 */
final class RowsBody {

    /** A number as JSON writes it: anything else a {@link Number} prints, such as {@code NaN}, is written as text. */
    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");
    /** The end of a body whose rows are not all there, the longer of the two ends. */
    private static final String MORE = "],\"more\":true}";
    /** The end of a body that holds every row. */
    private static final String ALL = "],\"more\":false}";

    private RowsBody() {}

    static String write(List<Column> columns, List<? extends List<?>> rows, boolean more) {
        Objects.requireNonNull(columns, "columns");
        Objects.requireNonNull(rows, "rows");
        int width = columns.size();
        for (int r = 0; r < rows.size(); r++) {
            int values = Objects.requireNonNull(rows.get(r), "row").size();
            if (values != width) {
                throw new IllegalArgumentException("row " + r + " has " + values + " values for " + width
                        + " columns");
            }
        }
        StringBuilder out = new StringBuilder("{\"columns\":[");
        for (int i = 0; i < width; i++) {
            Column column = Objects.requireNonNull(columns.get(i), "column");
            out.append(i == 0 ? "{\"name\":" : ",{\"name\":");
            quote(column.name(), out);
            out.append(",\"type\":");
            quote(column.type(), out);
            out.append('}');
        }
        out.append("],\"rows\":[");
        if (out.length() + MORE.length() > ActionResult.MAX_CONTENT) {
            throw new IllegalArgumentException("the columns of a result pass " + ActionResult.MAX_CONTENT
                    + " characters");
        }
        boolean cut = false;
        StringBuilder row = new StringBuilder();
        for (int r = 0; r < rows.size() && !cut; r++) {
            row.setLength(0);
            row.append(r == 0 ? "[" : ",[");
            List<?> values = rows.get(r);
            for (int i = 0; i < width; i++) {
                if (i > 0) {
                    row.append(',');
                }
                value(values.get(i), row);
            }
            row.append(']');
            cut = out.length() + row.length() + MORE.length() > ActionResult.MAX_CONTENT;
            if (!cut) {
                out.append(row);
            }
        }
        return out.append(cut || more ? MORE : ALL).toString();
    }

    private static void value(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case Boolean flag -> out.append(flag);
            case Number number when NUMBER.matcher(number.toString()).matches() -> out.append(number);
            default -> quote(String.valueOf(value), out);
        }
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass, and install the SPI for the modules after it**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-devconsole-spi install 2>&1 | grep -E "Tests run:|FAIL|BUILD" | tail -3
```
Expected: `Tests run: 71, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS` (`ActionResultTest`: 9).

- [ ] **Step 5: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(devconsole): an action may answer a table of rows

ActionResult.ROWS, application/x-rows+json, is a result the page draws as a table. ActionResult.rows(summary,
columns, rows, more) writes its body, {"columns": [{"name", "type"}...], "rows": [[...]...], "more": bool}, each
value null, a boolean, a number or a string, anything else as its text; the body stays within 256 KiB, the rows past
it left out and more set, so that it is never truncated in the middle of its JSON. A row of another width, a column
name or type past 200 characters, are refused.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-devconsole-spi/src/main/java/io/vidocq/runtime/spi/devconsole/PanelAction.java vidocq-runtime-devconsole-spi/src/main/java/io/vidocq/runtime/spi/devconsole/RowsBody.java vidocq-runtime-devconsole-spi/src/test/java/io/vidocq/runtime/spi/devconsole/ActionResultTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 2: A body of rows read, and written as CSV, in `editor-core.js` (§2.2)

**Files:**
- Modify: `PAGE/editor-core.js` (a `rows` section at the end of the file)
- Create: `DCT/RowsCoreTest.java`

**Interfaces:**
- Consumes: `editor-core.js`'s `isObject`.
- Produces: `export function readRows(text)` → `{ columns: [{ name, type }], rows: [[…]], more }` or `null`;
  `export function rowsCsv(rows)` → a string, every line ended by `\r\n`.

- [ ] **Step 1: Write the failing tests**

Create `DCT/RowsCoreTest.java`:

```java
/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.extensions.essentials.devconsole;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A result of rows in the editor's pure half, {@code editor-core.js}, run by GraalJS as the ES module the page loads
 * (SQL spec §2.2): the check of its body's shape, and its rows as CSV.
 */
class RowsCoreTest {

    private static final String BODY = "{\"columns\":[{\"name\":\"id\",\"type\":\"BIGINT\"},{\"name\":\"title\","
            + "\"type\":\"VARCHAR(200)\"},{\"name\":\"done\",\"type\":\"\"}],\"rows\":[[1,\"a, \\\"b\\\"\",true],"
            + "[2.5,\"\",null],[3,\"two\\r\\nlines\",false]],\"more\":true}";

    private static Context context;
    private static Value json;
    private static Value readRows;
    private static Value rowsCsv;

    @BeforeAll
    static void loadTheModule() {
        context = Context.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .option("js.esm-eval-returns-exports", "true")
                .build();
        Value exports = context.eval(Source.newBuilder("js", resource("editor-core.js"), "editor-core.mjs")
                .mimeType("application/javascript+module").buildLiteral());
        json = context.eval("js", "JSON");
        readRows = exports.getMember("readRows");
        rowsCsv = exports.getMember("rowsCsv");
    }

    @AfterAll
    static void closeTheEngine() {
        context.close();
    }

    private static String resource(String name) {
        String path = DevConsoleExtension.PAGE_RESOURCES + "/" + name;
        try (InputStream in = RowsCoreTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "the page has no " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What readRows makes of {@code text}, as JSON, or "null". */
    private static String read(String text) {
        return json.invokeMember("stringify", readRows.execute(text)).asString();
    }

    private static String csv(String text) {
        return rowsCsv.execute(readRows.execute(text)).asString();
    }

    @Test
    void aBodyOfRowsIsItsColumnsItsRowsAndItsMoreFlag() {
        assertEquals("{\"columns\":[{\"name\":\"id\",\"type\":\"BIGINT\"},{\"name\":\"title\",\"type\":"
                + "\"VARCHAR(200)\"},{\"name\":\"done\",\"type\":\"\"}],\"rows\":[[1,\"a, \\\"b\\\"\",true],"
                + "[2.5,\"\",null],[3,\"two\\r\\nlines\",false]],\"more\":true}", read(BODY));
        assertEquals("{\"columns\":[],\"rows\":[],\"more\":false}",
                read("{\"more\":false,\"rows\":[],\"columns\":[],\"other\":1}"), "another member is left out");
    }

    @Test
    void aBodyOfAnotherShapeIsNoRows() {
        for (String odd : new String[] {"", "[]", "nope", "{\"columns\":[],\"rows\":[]}",
                "{\"columns\":[],\"rows\":[],\"more\":\"yes\"}", "{\"columns\":{},\"rows\":[],\"more\":false}",
                "{\"columns\":[{\"name\":\"a\"}],\"rows\":[],\"more\":false}",
                "{\"columns\":[{\"name\":1,\"type\":\"\"}],\"rows\":[],\"more\":false}",
                "{\"columns\":[{\"name\":\"a\",\"type\":\"\"}],\"rows\":[[1,2]],\"more\":false}",
                "{\"columns\":[{\"name\":\"a\",\"type\":\"\"}],\"rows\":[[{\"x\":1}]],\"more\":false}",
                "{\"columns\":[{\"name\":\"a\",\"type\":\"\"}],\"rows\":[[[1]]],\"more\":false}",
                "{\"columns\":[{\"name\":\"a\",\"type\":\"\"}],\"rows\":[1],\"more\":false}"}) {
            assertEquals("null", read(odd), odd);
        }
    }

    @Test
    void theCsvIsTheHeaderThenEachRowEveryLineEndedByCrLf() {
        assertEquals("id,title,done\r\n1,\"a, \"\"b\"\"\",true\r\n2.5,\"\",\r\n3,\"two\r\nlines\",false\r\n",
                csv(BODY));
    }

    @Test
    void nullIsAnEmptyFieldAndAnEmptyStringTwoQuotes() {
        String csv = csv("{\"columns\":[{\"name\":\"a\",\"type\":\"\"},{\"name\":\"\",\"type\":\"\"}],"
                + "\"rows\":[[null,\"\"],[\"x\",null]],\"more\":false}");

        assertEquals("a,\"\"\r\n,\"\"\r\nx,\r\n", csv);
    }

    @Test
    void aFieldIsQuotedOnlyWhenItHoldsACommaAQuoteOrALineEnd() {
        String csv = csv("{\"columns\":[{\"name\":\"t\",\"type\":\"\"}],\"rows\":[[\" spaced \"],[\"semi;colon\"],"
                + "[\"line\\nend\"],[\"cr\\rend\"],[\"=1+1\"]],\"more\":false}");

        assertEquals("t\r\n spaced \r\nsemi;colon\r\n\"line\nend\"\r\n\"cr\rend\"\r\n=1+1\r\n", csv);
        assertTrue(csv.endsWith("\r\n"), "every line ends with CRLF, the last one too");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=RowsCoreTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run:|FAIL" | tail -3
```
Expected: FAIL — `Tests run: 5, Failures: 0, Errors: 5`: `readRows` and `rowsCsv` are no export of the module yet.

- [ ] **Step 3: Write `readRows` and `rowsCsv`**

In `PAGE/editor-core.js`, replace:
```js
  return QUERY;
}
```
with:
```js
  return QUERY;
}

// ------------------------------------------------------------------------------------------------ rows

/** Whether {@code v} is a value a row may hold: null, a boolean, a number or a string. */
const isCell = (v) => v === null || typeof v === "boolean" || typeof v === "number" || typeof v === "string";

/**
 * The body of a result of rows (SQL spec §2), { columns: [{ name, type }], rows, more }, read from its text; null when
 * it is not of that shape: columns a list of names and types, rows a list of lists of one value per column, each
 * null, a boolean, a number or a string, more a boolean. An integer past 2^53 is kept as the text the server wrote
 * where the engine gives a value's source, never rounded.
 */
export function readRows(text) {
  let body;
  try {
    body = JSON.parse(text, (key, value, context) => typeof value === "number" && !Number.isSafeInteger(value)
      && context && typeof context.source === "string" && /^-?\d+$/.test(context.source) ? context.source : value);
  } catch (unreadable) {
    return null;
  }
  if (!isObject(body) || !Array.isArray(body.columns) || !Array.isArray(body.rows)
    || typeof body.more !== "boolean") return null;
  const columns = body.columns;
  if (!columns.every((c) => isObject(c) && typeof c.name === "string" && typeof c.type === "string")) return null;
  if (!body.rows.every((row) => Array.isArray(row) && row.length === columns.length && row.every(isCell))) {
    return null;
  }
  return { columns: columns.map((c) => ({ name: c.name, type: c.type })), rows: body.rows, more: body.more };
}

/**
 * {@code rows}, a value of readRows, as RFC 4180 CSV: the names of the columns, then each row, every line ended by
 * CRLF; a field holding a comma, a quote or a line end quoted, its quotes doubled; null an empty field, an empty
 * string two quotes, so that the two stay apart; a number and a boolean as JSON writes them.
 */
export function rowsCsv(rows) {
  const field = (v) => {
    if (v === null) return "";
    const s = String(v);
    return s === "" || /[",\r\n]/.test(s) ? "\"" + s.replaceAll("\"", "\"\"") + "\"" : s;
  };
  return [rows.columns.map((c) => field(c.name)), ...rows.rows.map((row) => row.map(field))]
    .map((fields) => fields.join(",") + "\r\n").join("");
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run the command of Step 2.
Expected: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`.

`node --check` the module: `cp PAGE/editor-core.js $SCRATCH/core.mjs && node --check $SCRATCH/core.mjs` (the `PAGE`
prefix spelt out) prints nothing.

- [ ] **Step 5: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(devconsole): read a body of rows and write its rows as CSV

editor-core.js's readRows(text) checks the shape of an application/x-rows+json body - columns of a name and a type,
rows of one null, boolean, number or string per column, a more flag - and gives null for any other; rowsCsv(rows)
writes RFC 4180 CSV, the header first, CRLF line ends, a field with a comma, a quote or a line end quoted, NULL an
empty field and an empty string two quotes. Both pure, run by GraalJS in RowsCoreTest.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/editor-core.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/RowsCoreTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 3: The page draws rows as a table, with Table / JSON and Copy as CSV (§2.2)

**Files:**
- Modify: `PAGE/console.js` (its header comment, its import, `isRowsType`, `answerBody`, `MAX_CELL`, `rowsViews`,
  `rowsBody`, `rowsTable`, `resultOutput`, `inlineOutlet`, `resultBlock`, `groupTab`'s `showResult`, `panelView`)
- Modify: `PAGE/editor.js` (re-export `readRows`, `rowsCsv`), `PAGE/console.css` (the table of rows)
- Modify: `DCT/PageTest.java` (three pins follow the new signatures, the import pin, a new test)

**Interfaces:**
- Consumes: Task 2's `readRows`, `rowsCsv`; Task 1's content type `application/x-rows+json`; `replayButton(panelId,
  cell)`, `replayTarget(cell)`, `actionRows`, `actionKey`, `jsonViewer(text, nodes)`, `viewerTools(viewer)`.
- Produces: `function answerBody(answer, nodes, actionId, panelId)`, `function resultOutput(result, actionId,
  panelId)`, `function inlineOutlet(actionId, panelId)`, `function resultBlock(result, actionId, panelId)`,
  `function rowsBody(rows, text, nodes, panelId)` → `{ view, tools }`, `function rowsTable(rows, panelId)`; CSS
  classes `rows-view`, `rows-box`, `rows-scroll`, `rows` (the table), `rows-name`, `rows-type`, `rows-null`,
  `rows-more`, `rows-switch`.

- [ ] **Step 1: Write the failing tests**

In `DCT/PageTest.java`, replace:
```java
        assertTrue(script.contains("function actionRow(panelId, action, outlet)"), "where results show is the outlet's");
        assertTrue(script.contains("function inlineOutlet(actionId)"), "the panel's own bar keeps its look");
        assertTrue(script.contains("const started = performance.now();"), "the round trip the page measures");
```
with:
```java
        assertTrue(script.contains("function actionRow(panelId, action, outlet)"), "where results show is the outlet's");
        assertTrue(script.contains("function inlineOutlet(actionId, panelId)"), "the panel's own bar keeps its look");
        assertTrue(script.contains("const started = performance.now();"), "the round trip the page measures");
```

In `DCT/PageTest.java`, replace:
```java
                "a selected action a dev reload removed falls back to the group's first");
        assertTrue(script.contains("function resultBlock(result, actionId)"), "the result, apart from the form");
        assertTrue(script.contains("\"No call yet\""), "before the first call");
```
with:
```java
                "a selected action a dev reload removed falls back to the group's first");
        assertTrue(script.contains("function resultBlock(result, actionId, panelId)"),
                "the result, apart from the form");
        assertTrue(script.contains("\"No call yet\""), "before the first call");
```

In `DCT/PageTest.java`, replace:
```java
        assertTrue(script.contains(
                "import { createEditor, jsonLanguage, queryLanguage, FORMAT_EXAMPLES } from \"./editor.js\";"));
        assertEquals(1, Pattern.compile("<script").matcher(file("index.html")).results().count(),
```
with:
```java
        assertTrue(script.contains(
                "import { createEditor, jsonLanguage, queryLanguage, readRows, rowsCsv, FORMAT_EXAMPLES } "
                        + "from \"./editor.js\";"));
        assertEquals(1, Pattern.compile("<script").matcher(file("index.html")).results().count(),
```

In `DCT/PageTest.java`, replace:
```java
        assertFalse(download.contains("fetch("), "a download sends no request");
        assertEquals(2, Pattern.compile(Pattern.quote("answerBody(answer, result.nodes.body, actionId)"))
                .matcher(script).results().count(), "a group tab's result block and the panel's own bar");
    }
```
with:
```java
        assertFalse(download.contains("fetch("), "a download sends no request");
        assertEquals(2, Pattern.compile(Pattern.quote("answerBody(answer, result.nodes.body, actionId, panelId)"))
                .matcher(script).results().count(), "a group tab's result block and the panel's own bar");
    }

    @Test
    void aRowsResultIsATableWithATableJsonSwitchAndCopyAsCsv() {
        String script = file("console.js");
        String style = file("console.css");
        int start = script.indexOf("function rowsBody(");
        String rows = script.substring(start, script.indexOf("// ---", start));

        assertTrue(script.contains("const isRowsType = (type) => typeof type === \"string\""
                + " && type.startsWith(\"application/x-rows+json\");"), "PanelAction.ActionResult.ROWS");
        assertTrue(script.contains("const rows = readRows(answer.body);"), "its shape checked by editor-core.js");
        assertTrue(script.contains("return rows ? rowsBody(rows, answer.body, nodes, panelId) "
                + ": textOrJson(answer.body, true, nodes);"), "a body of another shape is shown as any other JSON");
        for (String tool : List.of("\"Table\"", "\"JSON\"", "\"Copy as CSV\"", "\"Clipboard refused\"")) {
            assertTrue(rows.contains(tool), "the result's bar: " + tool);
        }
        assertTrue(rows.contains("navigator.clipboard.writeText(rowsCsv(rows))"), "the rows as CSV, by editor-core.js");
        assertTrue(rows.contains("const viewer = mode === \"json\" ? jsonViewer(text, nodes) : null;"),
                "JSON is the viewer of today on the same body");
        assertTrue(rows.contains("rowsViews.set(nodes, mode);"), "a redraw of the same result keeps the view chosen");
        assertTrue(rows.contains("td.textContent = \"NULL\";") && rows.contains("td.className = \"rows-null\";"),
                "NULL, dimmed, never an empty string");
        assertTrue(script.contains("const MAX_CELL = 200;") && rows.contains("td.title = whole;"),
                "a long value cut, the whole of it in the cell's title");
        assertTrue(rows.contains("\"more rows not shown\""), "said under the rows");
        assertTrue(rows.contains("rows.columns[i].name === REPLAY_COLUMN ? replayButton(panelId, cell) : null"),
                "a replay column fills another action's form, as a sample table's does");
        assertFalse(rows.contains("fetch("), "a table, a switch and a copy send no request");
        assertTrue(rule(style, "table.rows th {").contains("position: sticky"), "the header stays in view");
        assertTrue(rule(style, ".rows-scroll {").contains("overflow: auto"), "the table scrolls inside the result");
        assertTrue(rule(style, "table.rows td.rows-null {").contains("var(--faint)"), "NULL dimmed");
        assertTrue(file("editor.js").contains("export { readRows, rowsCsv } from \"./editor-core.js\";"),
                "editor.js passes the pure half on to console.js, its only import");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=PageTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run:|FAIL" | tail -3
```
Expected: FAIL — `Tests run: 33, Failures: 4, Errors: 1`: the four pins of the new signatures and import
(`anActionPublishesEveryOutcomeToItsOutletWithTheWordingOfToday`,
`aGroupTabPicksOneActionAndShowsItsResultApartThenItsHistory`, `aCsvResultIsTextWithADownloadThatSendsNoRequest`,
`theConsoleImportsTheEditorAndNoOtherScript`), and the error of `aRowsResultIsATableWithATableJsonSwitchAndCopyAsCsv`
(no `rowsBody` to cut the script at).

- [ ] **Step 3: Write the table of rows**

In `PAGE/console.js`, replace:
```js
//   query's parameters, computed again as the query is typed; both send their text as typed.

import { createEditor, jsonLanguage, queryLanguage, FORMAT_EXAMPLES } from "./editor.js";
```
with:
```js
//   query's parameters, computed again as the query is typed; both send their text as typed.
// - An application/x-rows+json answer is a table of rows (rowsBody): a header per column, its type dimmed under its
//   name, NULL dimmed, a long value cut; Table / JSON switches to the JSON viewer of the same body, Copy as CSV copies
//   the rows. A cell of a column named "replay" is a button named after the action it fills. A body of another shape
//   is shown as any other JSON.

import { createEditor, jsonLanguage, queryLanguage, readRows, rowsCsv, FORMAT_EXAMPLES } from "./editor.js";
```

In `PAGE/console.js`, replace:
```js
/** The body of an answer: CSV as text with Download, JSON through the viewer, anything else as text. */
function answerBody(answer, nodes, actionId) {
  if (isCsvType(answer.contentType)) {
    return { view: el("pre", "result-body", answer.body), tools: downloadTools(answer.body, actionId) };
  }
  return textOrJson(answer.body, isJsonType(answer.contentType), nodes);
}
```
with:
```js
/** Whether a content type is a table of rows: PanelAction.ActionResult.ROWS. */
const isRowsType = (type) => typeof type === "string" && type.startsWith("application/x-rows+json");

/**
 * The body of an answer: CSV as text with Download, rows as a table, JSON through the viewer, anything else as text.
 * {@code panelId} is the panel whose actions a replay cell of rows fills.
 */
function answerBody(answer, nodes, actionId, panelId) {
  if (isCsvType(answer.contentType)) {
    return { view: el("pre", "result-body", answer.body), tools: downloadTools(answer.body, actionId) };
  }
  if (isRowsType(answer.contentType)) {
    const rows = readRows(answer.body);
    // a body of another shape is shown as any other JSON (SQL spec §2.2)
    return rows ? rowsBody(rows, answer.body, nodes, panelId) : textOrJson(answer.body, true, nodes);
  }
  return textOrJson(answer.body, isJsonType(answer.contentType), nodes);
}

/** Past this many characters a cell is cut with "…", its whole value in its title (SQL spec §2.2). */
const MAX_CELL = 200;
/** The view of each answer of rows, "table" or "json", by the fold state of its body: a redraw keeps the choice. */
const rowsViews = new WeakMap();

/**
 * A body of rows (SQL spec §2.2): the table, or the JSON viewer of the same body, with Table / JSON to switch and
 * Copy as CSV, which copies the rows as RFC 4180 CSV; a refused clipboard is said on the button, nothing more.
 */
function rowsBody(rows, text, nodes, panelId) {
  const view = el("div", "rows-view");
  const tools = el("span", "jv-tools");
  const switcher = el("span", "rows-switch");
  switcher.setAttribute("role", "group");
  switcher.setAttribute("aria-label", "Show the rows as");
  const asTable = el("button", null, "Table");
  const asJson = el("button", null, "JSON");
  const copy = el("button", null, "Copy as CSV");
  for (const b of [asTable, asJson, copy]) b.type = "button";
  const viewerSlot = el("span", "jv-tools");      // the JSON viewer's own tools, while it shows
  switcher.append(asTable, asJson);
  tools.append(switcher, copy, viewerSlot);
  const show = (mode) => {
    rowsViews.set(nodes, mode);
    asTable.setAttribute("aria-pressed", String(mode === "table"));
    asJson.setAttribute("aria-pressed", String(mode === "json"));
    const viewer = mode === "json" ? jsonViewer(text, nodes) : null;
    view.replaceChildren(viewer ? viewer.root : rowsTable(rows, panelId));
    viewerSlot.replaceChildren(...(viewer ? [viewerTools(viewer)] : []));
  };
  asTable.addEventListener("click", () => show("table"));
  asJson.addEventListener("click", () => show("json"));
  copy.addEventListener("click", async () => {
    let said;
    try {
      await navigator.clipboard.writeText(rowsCsv(rows));
      said = "Copied";
    } catch (refused) {
      said = "Clipboard refused";
    }
    copy.textContent = said;
    setTimeout(() => { copy.textContent = "Copy as CSV"; }, 2000);
  });
  show(rowsViews.get(nodes) === "json" ? "json" : "table");
  return { view, tools };
}

/**
 * The table of {@code rows}: a header per column, its type dimmed under its name, kept in view while the rows scroll;
 * NULL dimmed, an empty string an empty cell; a value past MAX_CELL characters cut with "…", the whole of it in the
 * cell's title; "more rows not shown" under them when there are more. A cell of a REPLAY_COLUMN column that replays an
 * action of the panel is a button named after that action; its header is left blank.
 */
function rowsTable(rows, panelId) {
  const box = el("div", "rows-box");
  const scroll = el("div", "rows-scroll");
  const table = el("table", "rows");
  const body = el("tbody");
  const buttons = new Set();               // the columns where a cell is a replay button
  for (const row of rows.rows) {
    const tr = el("tr");
    row.forEach((cell, i) => {
      const td = el("td");
      const button = rows.columns[i].name === REPLAY_COLUMN ? replayButton(panelId, cell) : null;
      if (button) {
        buttons.add(i);
        button.textContent = actionRows.get(actionKey(panelId, replayTarget(cell))).label;
        td.append(button);
      } else if (cell === null) {
        td.className = "rows-null";
        td.textContent = "NULL";
      } else {
        const whole = String(cell);
        if (whole.length > MAX_CELL) {
          const high = /[\uD800-\uDBFF]/.test(whole[MAX_CELL - 1]);      // never half a character
          td.textContent = whole.slice(0, high ? MAX_CELL - 1 : MAX_CELL) + "…";
          td.title = whole;
        } else {
          td.textContent = whole;
        }
        if (typeof cell === "number") td.className = "n";
      }
      tr.append(td);
    });
    body.append(tr);
  }
  const head = el("tr");
  rows.columns.forEach((column, i) => {
    const th = el("th");
    if (!buttons.has(i)) {
      th.append(el("span", "rows-name", column.name));
      if (column.type) th.append(el("span", "rows-type", column.type));
    }
    head.append(th);
  });
  const thead = el("thead");
  thead.append(head);
  table.append(thead, body);
  scroll.append(table);
  box.append(scroll);
  if (rows.more) box.append(el("p", "rows-more", "more rows not shown"));
  return box;
}
```

In `PAGE/console.js`, replace:
```js
/** What a result shows under its line in the panel's own bar: its body, with the viewer's tools, then its details. */
function resultOutput(result, actionId) {
  const answer = result.answer;
  const out = [];
  if (typeof answer.body === "string") {
    const body = answerBody(answer, result.nodes.body, actionId);
    if (body.tools) out.push(body.tools);
```
with:
```js
/** What a result shows under its line in the panel's own bar: its body, with the viewer's tools, then its details. */
function resultOutput(result, actionId, panelId) {
  const answer = result.answer;
  const out = [];
  if (typeof answer.body === "string") {
    const body = answerBody(answer, result.nodes.body, actionId, panelId);
    if (body.tools) out.push(body.tools);
```

In `PAGE/console.js`, replace:
```js
 */
function inlineOutlet(actionId) {
  const message = el("span", "msg");
```
with:
```js
 */
function inlineOutlet(actionId, panelId) {
  const message = el("span", "msg");
```

In `PAGE/console.js`, replace:
```js
      message.className = "msg " + STATE_CLASS.get(next.state);
      if (!sameAnswer) output.replaceChildren(...(next.answer ? resultOutput(next, actionId) : []));
    },
```
with:
```js
      message.className = "msg " + STATE_CLASS.get(next.state);
      if (!sameAnswer) output.replaceChildren(...(next.answer ? resultOutput(next, actionId, panelId) : []));
    },
```

In `PAGE/console.js`, replace:
```js
 */
function resultBlock(result, actionId) {
  const block = el("section", "result-block");
```
with:
```js
 */
function resultBlock(result, actionId, panelId) {
  const block = el("section", "result-block");
```

In `PAGE/console.js`, replace:
```js
  if (answer && typeof answer.body === "string") {
    const body = answerBody(answer, result.nodes.body, actionId);
    if (body.tools) head.append(body.tools);
```
with:
```js
  if (answer && typeof answer.body === "string") {
    const body = answerBody(answer, result.nodes.body, actionId, panelId);
    if (body.tools) head.append(body.tools);
```

In `PAGE/console.js`, replace:
```js
  }
  const showResult = () => resultSlot.replaceChildren(resultBlock(state.results.get(selected) || null, selected));
  function choose(id) {
```
with:
```js
  }
  const showResult = () => resultSlot.replaceChildren(resultBlock(state.results.get(selected) || null, selected,
    panelId));
  function choose(id) {
```

In `PAGE/console.js`, replace:
```js
    const group = groupName(action);
    const outlet = group === null ? inlineOutlet(action.id) : groupOutlet(state, action.id, (id) => {
      const tab = groupTabs.get(group);
```
with:
```js
    const group = groupName(action);
    const outlet = group === null ? inlineOutlet(action.id, panel.id) : groupOutlet(state, action.id, (id) => {
      const tab = groupTabs.get(group);
```

In `PAGE/editor.js`, replace:
```js
export { jsonLanguage, queryLanguage, FORMAT_EXAMPLES };
```
with:
```js
export { jsonLanguage, queryLanguage, FORMAT_EXAMPLES };
/** A result's rows, read from its body and written as CSV: console.js draws them, editor-core.js reads them. */
export { readRows, rowsCsv } from "./editor-core.js";
```

In `PAGE/console.css`, replace:
```css
.history { margin-top: 8px; }
```
with:
```css
.history { margin-top: 8px; }
/* A result of rows (ActionResult.ROWS): its header stays in view while the rows scroll, a NULL dimmed. */
.rows-box { margin: 6px 0 0; min-width: 0; }
.rows-scroll { max-height: 32em; overflow: auto; border: 1px solid var(--grid); border-radius: 6px; }
.result-block > .rows-view > .rows-box { margin: 0; }
.result-block > .rows-view > .rows-box > .rows-scroll { border: 0; border-radius: 0; }
.result-block > .rows-view > .jv { margin: 0; border-radius: 0; }
table.rows { border-collapse: separate; border-spacing: 0; font-family: var(--mono); font-size: 12px;
  white-space: nowrap; }
table.rows th { position: sticky; top: 0; z-index: 1; background: var(--sunk); text-align: left;
  vertical-align: bottom; padding: 5px 16px 5px 10px; border-bottom: 1px solid var(--rule); }
.rows-name { display: block; font-weight: 600; color: var(--ink); }
.rows-type { display: block; font-size: 11px; font-weight: 400; color: var(--faint); }
table.rows td { padding: 4px 16px 4px 10px; border-bottom: 1px solid var(--grid); color: var(--ink);
  font-variant-numeric: tabular-nums; }
table.rows td.n { text-align: right; }
table.rows td.rows-null { color: var(--faint); font-style: italic; }
.rows-more { margin: 0; padding: 6px 10px; font-size: 12px; color: var(--muted); }
.rows-switch { display: inline-flex; gap: 2px; }
.rows-switch button[aria-pressed="true"] { background: var(--sunk); font-weight: 600; }
```

- [ ] **Step 4: Run the tests to verify they pass, and the console's whole build**

Run the command of Step 2. Expected: `Tests run: 33, Failures: 0, Errors: 0, Skipped: 0`.

Then:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension install 2>&1 | grep -E "Tests run:|FAIL|BUILD" | grep -v " in io" | tail -3
```
Expected: `Tests run: 477, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

`node --check` both scripts: `cp PAGE/console.js $SCRATCH/console.mjs && node --check $SCRATCH/console.mjs` and the
same for `PAGE/editor.js`, both printing nothing.

- [ ] **Step 5: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(devconsole): a result of rows is a table, with Table / JSON and Copy as CSV

An application/x-rows+json answer is drawn as a table: each column's name, its type dimmed under it, the header kept
in view while the rows scroll, NULL dimmed, a value past 200 characters cut with its whole text in the cell's title,
"more rows not shown" when the panel sent the first rows only. Table / JSON switches to the JSON viewer of the same
body, kept across polls; Copy as CSV copies the rows. A cell of a column named replay is a button named after the
action it fills. A body of another shape is shown as any other JSON.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/console.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/editor.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/console.css vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/PageTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---
### Task 4: SQL in the query mode — scope, aliases, quoted names, comments, keystrokes (§3)

**Files:**
- Modify: `PAGE/editor-core.js` (its header comment, `isStringToken`, `isClosed`, `stringAround`, `pairsOf`,
  `keystroke`, `backspace`, `SQL_OPERATORS`, `isQuote`, `readVocabulary`, `lexQuery`, `valueTest`, `read`, the SQL
  helpers and `readSql`, `completeQuery`'s self, `QUERY`)
- Modify: `PAGE/editor.js` (its import, the keystroke handler), `PAGE/console.css` (`.ed-comment`)
- Create: `DCT/SqlLanguageTest.java`
- Modify: `DCT/PageTest.java`

**Interfaces:**
- Consumes: the query mode of `editor-core.js` (`vocabulary`, `read`, `isValue`'s rules, `COMPARISONS`, `NAME_PART`,
  `OPERATORS`, `isClosed`), `editor.js`'s keydown handler.
- Produces: the dialect members `aliases` (boolean), `identifierQuote` (one character or null), `unquotedCase`
  (`"lower"`, `"upper"` or null), `self` (`null` for none) and a target's `schema`, read by `readVocabulary` into
  `v.aliases`, `v.identifierQuote`, `v.unquotedCase`, `v.self`, `entry.schema`; `lexQuery(text, v)` →
  `{ tokens, comments }`, a quoted name `{ from, to, kind: "name", quoted: true }`, a comment `{ from, to, kind:
  "comment", open }`; `read(text, data)` → `{ v, tokens, comments, target, scope }`, `scope` one `{ entry, label,
  name, alias, written }` per target of a SQL statement, empty in JDQL; a SQL token also has `depth`, `sub`, and a
  name `position` (`target`, `alias`, `type`, `call`); helpers `folded(v, name)`, `nameOf(text, t, v)`,
  `keyOf(text, t, v)`, `sameName(v, a, b)`, `lookup(v, map, key, quoted)`, `scoped(v, scope, key)`,
  `readSql(text, v, tokens)`, `valueTest(text, tokens)`; `export function pairsOf(language, data)`;
  `keystroke(language, text, start, end, key, data)`; `QUERY.pairsOf(data)`; `QUERY.tokenize` gives comments, and
  `quoted: true` on a quoted name.

- [ ] **Step 1: Write the failing tests**

Create `DCT/SqlLanguageTest.java`:

```java
/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.extensions.essentials.devconsole;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The query mode's SQL options in the editor's pure half, {@code editor-core.js}, run by GraalJS as the ES module the
 * page loads (SQL spec §3, §5): aliases, several targets, quoted identifiers, comments, completion, diagnostics,
 * parameters and formatting, with a language as a Mansart pools panel publishes it for PostgreSQL. JDQL's behaviour,
 * which none of these options touches, is {@link QueryLanguageTest}'s.
 */
class SqlLanguageTest {

    /**
     * The SQL fixture: tasks whose project_id refers to projects, both with an id and a title; "Order", a mixed-case
     * name that is a keyword too, with a column "due date"; sales.orders, a table of another schema.
     */
    private static final String SQL = """
            {"mode": "query",
             "dialect": {
               "keywords": ["SELECT", "FROM", "WHERE", "JOIN", "LEFT", "INNER", "OUTER", "ON", "AS", "AND", "OR",
                            "NOT", "NULL", "IS", "IN", "LIKE", "BETWEEN", "GROUP", "BY", "HAVING", "ORDER", "ASC",
                            "DESC", "LIMIT", "OFFSET", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "WITH",
                            "DISTINCT", "CASE", "WHEN", "THEN", "ELSE", "END", "COUNT", "MAX", "TRUE", "FALSE",
                            "INTEGER"],
               "functions": ["COUNT", "MAX", "UPPER", "COALESCE"],
               "clauses": ["SELECT", "FROM", "JOIN", "ON", "WHERE", "GROUP BY", "HAVING", "ORDER BY", "LIMIT",
                           "OFFSET", "SET", "VALUES", "UPDATE", "DELETE FROM", "INSERT INTO"],
               "targetAfter": ["FROM", "JOIN", "UPDATE", "INTO"],
               "aliases": true, "self": null, "quote": "'", "identifierQuote": "\\"", "unquotedCase": "lower"},
             "targets": {
               "tasks": {"detail": "table · public", "attributes": {
                 "id": {"type": "integer", "detail": "int8 · column"},
                 "title": {"type": "string", "detail": "varchar(200) · column"},
                 "price": {"type": "number", "detail": "numeric(10,2) · column"},
                 "due_date": {"type": "string", "format": "date", "detail": "date · column"},
                 "project_id": {"type": "integer", "detail": "int8 · column", "target": "projects"}}},
               "projects": {"detail": "table · public", "attributes": {
                 "id": {"type": "integer", "detail": "int8 · column"},
                 "title": {"type": "string", "detail": "varchar(80) · column"},
                 "lead": {"type": "string", "detail": "varchar(80) · column"}}},
               "Order": {"detail": "table · public", "attributes": {
                 "id": {"type": "integer", "detail": "int4 · column"},
                 "due date": {"type": "string", "format": "date", "detail": "date · column"}}},
               "sales.orders": {"detail": "view · sales", "schema": "sales", "attributes": {
                 "id": {"type": "integer", "detail": "int4 · column"},
                 "total": {"type": "number", "detail": "numeric(38,0) · column"}}}}}
            """;

    private static Context context;
    private static Value json;
    private static Value language;
    private static Value keystroke;
    private static Value sql;

    @BeforeAll
    static void loadTheModule() {
        context = Context.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .option("js.esm-eval-returns-exports", "true")
                .build();
        Value exports = context.eval(Source.newBuilder("js", resource("editor-core.js"), "editor-core.mjs")
                .mimeType("application/javascript+module").buildLiteral());
        json = context.eval("js", "JSON");
        language = exports.getMember("queryLanguage").execute();
        keystroke = exports.getMember("keystroke");
        sql = json.invokeMember("parse", SQL);
    }

    @AfterAll
    static void closeTheEngine() {
        context.close();
    }

    private static String resource(String name) {
        String path = DevConsoleExtension.PAGE_RESOURCES + "/" + name;
        try (InputStream in = SqlLanguageTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "the page has no " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int number(Value object, String member) {
        return object.getMember(member).asInt();
    }

    /** The tokens of {@code text} read with the SQL fixture, each as kind:text, separated by spaces. */
    private static String tokens(String text) {
        Value tokens = language.invokeMember("tokenize", text, sql);
        List<String> out = new ArrayList<>();
        for (long i = 0; i < tokens.getArraySize(); i++) {
            Value t = tokens.getArrayElement(i);
            out.add(t.getMember("kind").asString() + ":" + text.substring(number(t, "from"), number(t, "to")));
        }
        return String.join(" ", out);
    }

    // ------------------------------------------------------------------------------------------------ tokens, scope

    @Test
    void anAliasNamesItsTableAfterItOrAfterAsAndAKeywordIsNeverOne() {
        assertEquals("keyword:SELECT target:t punct:. attribute:title punct:, target:p punct:. attribute:lead "
                + "keyword:FROM target:tasks target:t keyword:JOIN target:projects keyword:AS target:p keyword:ON "
                + "target:p punct:. attribute:id operator:= target:t punct:. attribute:project_id",
                tokens("SELECT t.title, p.lead FROM tasks t JOIN projects AS p ON p.id = t.project_id"),
                "a path from an alias is a column, where a value goes too");
        assertEquals("keyword:FROM target:tasks keyword:WHERE attribute:id operator:= number:1",
                tokens("FROM tasks WHERE id = 1"), "WHERE is no alias");
        assertEquals("keyword:SELECT target:tasks punct:. attribute:price keyword:FROM target:tasks",
                tokens("SELECT tasks.price FROM tasks"), "a table's own name heads a path too");
    }

    @Test
    void everyTargetAfterFromJoinUpdateOrIntoIsInScopeAndFromTakesAList() {
        assertEquals("keyword:SELECT attribute:lead punct:, attribute:price keyword:FROM target:tasks target:t "
                + "punct:, target:projects target:p", tokens("SELECT lead, price FROM tasks t, projects p"),
                "a bare column of either table");
        assertEquals("keyword:INSERT keyword:INTO target:tasks punct:( attribute:title punct:, attribute:price "
                + "punct:) keyword:VALUES punct:( string:'a' punct:, number:1 punct:)",
                tokens("INSERT INTO tasks (title, price) VALUES ('a', 1)"));
        assertEquals("keyword:UPDATE target:tasks keyword:SET attribute:title operator:= parameter::t",
                tokens("UPDATE tasks SET title = :t"));
        assertEquals("keyword:DELETE keyword:FROM target:tasks keyword:WHERE attribute:id operator:= number:1",
                tokens("DELETE FROM tasks WHERE id = 1"));
    }

    @Test
    void aQuotedIdentifierIsANameItsDoubledQuoteOneLookedUpAsWritten() {
        assertEquals("keyword:SELECT target:o punct:. attribute:\"due date\" punct:, attribute:\"id\" "
                + "keyword:FROM target:\"Order\" target:o",
                tokens("SELECT o.\"due date\", \"id\" FROM \"Order\" o"));
        assertEquals("keyword:SELECT identifier:\"a\"\"b\" keyword:FROM identifier:\"Tasks\"",
                tokens("SELECT \"a\"\"b\" FROM \"Tasks\""), "a quoted name is looked up as written: no Tasks");
        assertEquals("keyword:SELECT identifier:\"select\" keyword:FROM target:tasks",
                tokens("SELECT \"select\" FROM tasks"), "a quoted name is never a keyword");
        assertEquals("keyword:SELECT identifier:\"open", tokens("SELECT \"open"), "it runs to the end of its line");
    }

    @Test
    void anUnquotedNameIsLookedUpAsTheDatabaseStoresIt() {
        assertEquals("keyword:SELECT attribute:TITLE keyword:FROM target:TASKS", tokens("SELECT TITLE FROM TASKS"),
                "PostgreSQL folds an unquoted name to lower case");
        assertEquals("keyword:FROM keyword:Order", tokens("FROM Order"),
                "Order unquoted is the keyword, and the name order: the table is \"Order\"");
    }

    @Test
    void aQualifiedTargetIsLookedUpAsWrittenThenWithoutItsSchema() {
        assertEquals("keyword:SELECT target:s punct:. attribute:total keyword:FROM target:sales punct:. "
                + "target:orders target:s", tokens("SELECT s.total FROM sales.orders s"));
        assertEquals("keyword:FROM target:public punct:. target:tasks keyword:WHERE attribute:title",
                tokens("FROM public.tasks WHERE title"), "public.tasks is no target: tasks is");
        assertEquals("keyword:FROM identifier:other punct:. identifier:nothing keyword:WHERE identifier:x",
                tokens("FROM other.nothing WHERE x"));
    }

    @Test
    void aSubQueryATableExpressionAndAFunctionsFromAreLeftUnchecked() {
        assertEquals("keyword:SELECT attribute:title keyword:FROM target:tasks keyword:WHERE attribute:id keyword:IN "
                + "punct:( keyword:SELECT identifier:nope keyword:FROM identifier:nothing punct:)",
                tokens("SELECT title FROM tasks WHERE id IN (SELECT nope FROM nothing)"));
        assertEquals("keyword:WITH target:recent keyword:AS punct:( keyword:SELECT identifier:id keyword:FROM "
                + "identifier:tasks punct:) keyword:SELECT identifier:x keyword:FROM target:recent",
                tokens("WITH recent AS (SELECT id FROM tasks) SELECT x FROM recent"));
        assertEquals("keyword:SELECT identifier:extract punct:( identifier:year keyword:FROM attribute:due_date "
                + "punct:) keyword:FROM target:tasks",
                tokens("SELECT extract(year FROM due_date) FROM tasks"), "FROM in parentheses names no target");
    }

    @Test
    void aSelectAliasACastsTypeAndAnUnknownFunctionAreNames() {
        assertEquals("keyword:SELECT attribute:title keyword:AS identifier:t2 punct:, attribute:price identifier:p2 "
                + "keyword:FROM target:tasks keyword:ORDER keyword:BY identifier:t2",
                tokens("SELECT title AS t2, price p2 FROM tasks ORDER BY t2"));
        assertEquals("keyword:SELECT attribute:due_date operator::: identifier:text punct:, identifier:date_trunc "
                + "punct:( string:'day' punct:, attribute:due_date punct:) keyword:FROM target:tasks",
                tokens("SELECT due_date::text, date_trunc('day', due_date) FROM tasks"));
    }

    @Test
    void commentsAreTokensOfTheirOwnThatNothingElseSees() {
        assertEquals("keyword:SELECT comment:-- the title attribute:title keyword:FROM comment:/* all */ "
                + "target:tasks", tokens("SELECT -- the title\n title FROM /* all */ tasks"));
        assertEquals("keyword:SELECT number:1 comment:/* open", tokens("SELECT 1 /* open"));
        assertEquals("keyword:SELECT number:1 operator:- operator:- number:2", tokens("SELECT 1 - -2"),
                "a minus sign, then another, is no comment");
    }

    // ------------------------------------------------------------------------------------------------ parameters

    @Test
    void aParameterComparedWithAColumnThroughAnAliasTakesItsType() {
        assertEquals("{\"type\":\"object\",\"properties\":{\"min\":{\"type\":\"number\",\"description\":"
                + "\"compared with t.price (number)\"},\"d\":{\"type\":\"string\",\"format\":\"date\","
                + "\"description\":\"compared with t.due_date (date)\"}},\"required\":[\"min\",\"d\"],"
                + "\"additionalProperties\":false}",
                json.invokeMember("stringify", language.invokeMember("parameters",
                        "SELECT * FROM tasks t WHERE t.price > :min AND :d = t.due_date", sql)).asString());
    }

    // ------------------------------------------------------------------------------------------------ keystrokes

    /**
     * {@code marked} after {@code key} with {@code data}: one | is the caret, two | the ends of the selection; "null"
     * when the module lets the browser type the key.
     */
    private static String press(String marked, String key, Value data) {
        int start = marked.indexOf('|');
        int second = marked.indexOf('|', start + 1);
        String text = marked.replace("|", "");
        int end = second < 0 ? start : second - 1;
        Value edit = keystroke.execute(language, text, start, end, key, data);
        if (edit.isNull()) {
            return "null";
        }
        int from = number(edit, "from");
        String next = text.substring(0, from) + edit.getMember("insert").asString()
                + text.substring(number(edit, "to"));
        int caret = from + number(edit, "caret");
        Value anchor = edit.getMember("anchor");
        if (anchor == null || anchor.isNull()) {
            return next.substring(0, caret) + "|" + next.substring(caret);
        }
        int other = from + anchor.asInt();
        return next.substring(0, Math.min(other, caret)) + "|" + next.substring(Math.min(other, caret),
                Math.max(other, caret)) + "|" + next.substring(Math.max(other, caret));
    }

    @Test
    void theIdentifierQuotePairsAndStepsOverAsTheStringQuoteDoes() {
        assertEquals("[\"()\",\"''\",\"\\\"\\\"\"]",
                json.invokeMember("stringify", language.invokeMember("pairsOf", sql)).asString());
        assertEquals("SELECT \"|\"", press("SELECT |", "\"", sql));
        assertEquals("SELECT \"Order\"|", press("SELECT \"Order|\"", "\"", sql));
        assertEquals("\"|o|\"", press("|o|", "\"", sql));
        assertEquals("\"a\"\"|\"", press("\"a\"|", "\"", sql), "a doubled quote is typed as a pair, inside the name");
        assertEquals("null", press("'it|'", "\"", sql), "inside a string, the browser types it");
        assertEquals("|", press("\"|\"", "Backspace", sql));
        assertEquals("null", press("x = |", "\"", null), "JDQL's quote alone: \" is typed as it is");
    }
}
```

In `DCT/PageTest.java`, replace:
```java
        assertTrue(editor.contains("export { jsonLanguage, queryLanguage, FORMAT_EXAMPLES };"));
    }
```
with:
```java
        assertTrue(editor.contains("export { jsonLanguage, queryLanguage, FORMAT_EXAMPLES };"));
    }

    @Test
    void theKeystrokesOfAQueryFollowItsDataAndASqlCommentIsDimmed() {
        String editor = file("editor.js");

        assertTrue(editor.contains(
                "const paired = key.length === 1 && pairsOf(language, data).some((p) => p.includes(key));"),
                "a SQL dialect pairs its identifier quote too");
        assertTrue(editor.contains("textarea.selectionEnd,\n      key, data), null);"),
                "the keystrokes read the quotes of the query's data");
        assertTrue(rule(file("console.css"), ".ed-comment {").contains("var(--faint)"), "a SQL comment, dimmed");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest='SqlLanguageTest,PageTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run:|FAIL" | tail -3
```
Expected: FAIL — `Tests run: 44, Failures: 10, Errors: 1`: every new test, the ten of `SqlLanguageTest` (its
`pairsOf` test an error: the language has no such member yet) and `PageTest`'s
`theKeystrokesOfAQueryFollowItsDataAndASqlCommentIsDimmed`.

- [ ] **Step 3: Write SQL's tokens, scope and keystrokes**

In `PAGE/editor-core.js`, replace:
```js
//   of targets and their attributes. It colours, completes and checks names, never the grammar, which the server
//   judges; parameters() is the JSON Schema of the query's named parameters. Odd data reads as none.
// - keystroke() is the edit a key makes, or null to let the browser type it.
```
with:
```js
//   of targets and their attributes. It colours, completes and checks names, never the grammar, which the server
//   judges; parameters() is the JSON Schema of the query's named parameters. Odd data reads as none. A dialect with
//   aliases is SQL's (SQL spec §3): several targets, their aliases, quoted identifiers, comments, sub-queries left
//   unchecked; JDQL declares none of it.
// - keystroke() is the edit a key makes, or null to let the browser type it.
```

In `PAGE/editor-core.js`, replace:
```js
const isObject = (v) => v !== null && typeof v === "object" && !Array.isArray(v);
const isStringToken = (t) => t.kind === "string" || t.kind === "key";
```
with:
```js
const isObject = (v) => v !== null && typeof v === "object" && !Array.isArray(v);
/** Whether {@code t} is a string, a JSON key, or a quoted name of a query, whose quotes pair as a string's do. */
const isStringToken = (t) => t.kind === "string" || t.kind === "key" || t.quoted === true;
```

In `PAGE/editor-core.js`, replace:
```js
 * Whether the string token {@code t} ends with its closing quote, the quote it opens with: in JSON ("), an escaped
 * quote is no closing one; in a query (the dialect's quote, ' in JDQL), a doubled one is none.
 */
```
with:
```js
 * Whether the string token {@code t} ends with its closing quote, the quote it opens with: in JSON ("), an escaped
 * quote is no closing one; in a query (the dialect's quote, ' in JDQL, and a quoted name's), a doubled one is none.
 */
```

In `PAGE/editor-core.js`, replace:
```js
  if (t.to - t.from < 2 || text[t.to - 1] !== quote) return false;
  if (quote !== "\"") {
    let quotes = 0;
```
with:
```js
  if (t.to - t.from < 2 || text[t.to - 1] !== quote) return false;
  if (quote !== "\"" || t.quoted === true) {
    let quotes = 0;
```

In `PAGE/editor-core.js`, replace:
```js
/** The string token {@code offset} is inside of, its closing quote excluded, or null. */
function stringAround(language, text, offset) {
  for (const t of language.tokenize(text)) {
    if (t.from >= offset) return null;
```
with:
```js
/** The string token {@code offset} is inside of, its closing quote excluded, or null. */
function stringAround(language, text, offset, data) {
  for (const t of language.tokenize(text, data)) {
    if (t.from >= offset) return null;
```

In `PAGE/editor-core.js`, replace:
```js
/**
 * The edit {@code key} makes in {@code text}, whose selection is {@code selectionStart}-{@code selectionEnd} (spec
 * §3.5): { from, to, insert, caret }, caret an offset into insert as on a completion item, and anchor, when present,
 * the other end of the selection it leaves, an offset into insert too; an empty insert over an empty range only moves
 * the caret. null: the browser types the key itself. The keys: an opening or closing character of language.pairs,
 * "Enter", "Backspace", "Tab", "Shift+Tab".
 */
export function keystroke(language, text, selectionStart, selectionEnd, key) {
  const start = Math.min(selectionStart, selectionEnd);
```
with:
```js
/**
 * The pairs of {@code language} with {@code data}: a query's own, which its dialect's quotes make (SQL spec §3), or
 * its fixed pairs.
 */
export function pairsOf(language, data) {
  if (typeof language.pairsOf === "function") return language.pairsOf(data);
  return Array.isArray(language.pairs) ? language.pairs : [];
}

/**
 * The edit {@code key} makes in {@code text}, whose selection is {@code selectionStart}-{@code selectionEnd} (spec
 * §3.5): { from, to, insert, caret }, caret an offset into insert as on a completion item, and anchor, when present,
 * the other end of the selection it leaves, an offset into insert too; an empty insert over an empty range only moves
 * the caret. null: the browser types the key itself. The keys: an opening or closing character of the language's
 * pairs with {@code data} (pairsOf), "Enter", "Backspace", "Tab", "Shift+Tab".
 */
export function keystroke(language, text, selectionStart, selectionEnd, key, data) {
  const start = Math.min(selectionStart, selectionEnd);
```

In `PAGE/editor-core.js`, replace:
```js
  if (key === "Shift+Tab") return outdent(text, start, end);
  const pairs = Array.isArray(language.pairs) ? language.pairs : [];
  if (key === "Backspace") return start === end ? backspace(language, pairs, text, start) : null;
  const opening = pairs.find((pair) => pair[0] === key);
  const closing = pairs.find((pair) => pair[1] === key);
  if (!opening && !closing) return null;
  // a quote opens and closes its strings: " in JSON, ' in a query
  const quote = pairs.some((pair) => pair[0] === key && pair[1] === key);
```
with:
```js
  if (key === "Shift+Tab") return outdent(text, start, end);
  const pairs = pairsOf(language, data);
  if (key === "Backspace") return start === end ? backspace(language, pairs, text, start, data) : null;
  const opening = pairs.find((pair) => pair[0] === key);
  const closing = pairs.find((pair) => pair[1] === key);
  if (!opening && !closing) return null;
  // a quote opens and closes its strings: " in JSON, ' in a query, and " around a SQL query's quoted name
  const quote = pairs.some((pair) => pair[0] === key && pair[1] === key);
```

In `PAGE/editor-core.js`, replace:
```js
  }
  const string = stringAround(language, text, start);
  if (closing && text[start] === key
```
with:
```js
  }
  const string = stringAround(language, text, start, data);
  if (closing && text[start] === key
```

In `PAGE/editor-core.js`, replace:
```js
/** Backspace right between an empty pair of the language, an empty string's quotes included, deletes both. */
function backspace(language, pairs, text, offset) {
  if (offset === 0 || !pairs.includes(text.slice(offset - 1, offset + 1))) return null;
  if (text[offset] === text[offset - 1]) {
    const empty = language.tokenize(text).find((t) => t.from === offset - 1);
    if (!empty || !isStringToken(empty) || empty.to !== offset + 1) return null;
```
with:
```js
/** Backspace right between an empty pair of the language, an empty string's quotes included, deletes both. */
function backspace(language, pairs, text, offset, data) {
  if (offset === 0 || !pairs.includes(text.slice(offset - 1, offset + 1))) return null;
  if (text[offset] === text[offset - 1]) {
    const empty = language.tokenize(text, data).find((t) => t.from === offset - 1);
    if (!empty || !isStringToken(empty) || empty.to !== offset + 1) return null;
```

In `PAGE/editor-core.js`, replace:
```js
const OPERATORS = ["<=", ">=", "<>", "!=", "=", "<", ">", "+", "-", "*", "/"];
/** What starts a name and what continues it, as JDQL reads them: a letter or _, then digits too. */
```
with:
```js
const OPERATORS = ["<=", ">=", "<>", "!=", "=", "<", ">", "+", "-", "*", "/"];
/** SQL's, its concatenation and its modulo included; a cast, ::, is read before a parameter is. */
const SQL_OPERATORS = ["||", ...OPERATORS, "%"];
/** What starts a name and what continues it, as JDQL reads them: a letter or _, then digits too. */
```

In `PAGE/editor-core.js`, replace:
```js
 * capitals, and keywordList and functionList in the dialect's order), clauses (each a list of words, the longest
 * first), targetAfter (a Set), self, quote, and targets, a Map of each target's name to { name, detail, attributes },
 * attributes a Map of each name to { name, type, format, enum, detail, target }. Anything odd is left out; data that
 * is no object reads as the default dialect and no target.
 */
```
with:
```js
 * capitals, and keywordList and functionList in the dialect's order), clauses (each a list of words, the longest
 * first), targetAfter (a Set), self (null when the dialect says null), quote, and targets, a Map of each target's name
 * to { name, detail, schema, attributes }, attributes a Map of each name to { name, type, format, enum, detail,
 * target }. SQL's options (SQL spec §3): aliases, identifierQuote (null for none) and unquotedCase ("lower", "upper"
 * or null), how the database stores a name written without quotes. Anything odd is left out; data that is no object
 * reads as the default dialect and no target.
 */
```

In `PAGE/editor-core.js`, replace:
```js
function readVocabulary(data) {
```
with:
```js
/** Whether {@code q} may delimit a string or a name: one character that is no blank, no name, no punctuation. */
const isQuote = (q) => typeof q === "string" && q.length === 1 && !isBlank(q) && !NAME_PART.test(q)
  && !"(),.:?!".includes(q) && !OPERATORS.includes(q);

function readVocabulary(data) {
```

In `PAGE/editor-core.js`, replace:
```js
  const functions = wordsOf(d.functions, DIALECT.functions);
  const quote = typeof d.quote === "string" && d.quote.length === 1 && !isBlank(d.quote) && !NAME_PART.test(d.quote)
    && !"(),.:?!".includes(d.quote) && !OPERATORS.includes(d.quote) ? d.quote : DIALECT.quote;
  const self = typeof d.self === "string" && /^[\p{L}_][\p{L}\p{N}_]*$/u.test(d.self) ? d.self : DIALECT.self;
  const targets = new Map();
```
with:
```js
  const functions = wordsOf(d.functions, DIALECT.functions);
  const quote = isQuote(d.quote) ? d.quote : DIALECT.quote;
  const self = d.self === null ? null
    : typeof d.self === "string" && /^[\p{L}_][\p{L}\p{N}_]*$/u.test(d.self) ? d.self : DIALECT.self;
  const targets = new Map();
```

In `PAGE/editor-core.js`, replace:
```js
    }
    targets.set(name, { name, detail: typeof t.detail === "string" ? t.detail : "", attributes });
  }
  return { keywords: new Set(keywords), keywordList: keywords, functions: new Set(functions), functionList: functions,
    clauses: wordsOf(d.clauses, DIALECT.clauses).map((c) => c.split(/\s+/)).sort((a, b) => b.length - a.length),
    targetAfter: new Set(wordsOf(d.targetAfter, DIALECT.targetAfter)), self, quote, targets };
}
```
with:
```js
    }
    targets.set(name, { name, detail: typeof t.detail === "string" ? t.detail : "",
      schema: typeof t.schema === "string" ? t.schema : null, attributes });
  }
  return { keywords: new Set(keywords), keywordList: keywords, functions: new Set(functions), functionList: functions,
    clauses: wordsOf(d.clauses, DIALECT.clauses).map((c) => c.split(/\s+/)).sort((a, b) => b.length - a.length),
    targetAfter: new Set(wordsOf(d.targetAfter, DIALECT.targetAfter)), self, quote, targets,
    aliases: d.aliases === true, identifierQuote: isQuote(d.identifierQuote) && d.identifierQuote !== quote
      ? d.identifierQuote : null, unquotedCase: d.unquotedCase === "lower" || d.unquotedCase === "upper"
      ? d.unquotedCase : null };
}
```

In `PAGE/editor-core.js`, replace:
```js
/**
 * The raw tokens of a query: { from, to, kind }, kind name, string, number, parameter (:name, ?1), operator, punct
 * (( ) , .) or invalid (one character). A string runs from {@code quote} to the next one that is not doubled, or to
 * the end of its line when there is none; a number written into a name, 12ab, is invalid as a whole.
 */
function lexQuery(text, quote) {
  const tokens = [];
  const n = text.length;
```
with:
```js
/**
 * The raw tokens of a query read with the vocabulary {@code v}: { tokens, comments }, a token { from, to, kind }, kind
 * name, string, number, parameter (:name, ?1), operator, punct (( ) , .) or invalid (one character). A string runs
 * from the dialect's quote to the next one that is not doubled, or to the end of its line when there is none; so does
 * a quoted name, between two identifierQuote, a name marked quoted. A number written into a name, 12ab, is invalid as
 * a whole. In SQL (a dialect with aliases) a -- or /* comment is no token: comments holds it, { from, to, kind:
 * "comment", open }, open when a /* is never closed; ::, || and % are operators.
 */
function lexQuery(text, v) {
  const tokens = [];
  const comments = [];
  const sql = v.aliases;
  const n = text.length;
```

In `PAGE/editor-core.js`, replace:
```js
    let kind;
    if (c === quote) {
      kind = "string";
      while (j < n && text[j] !== "\n" && text[j] !== "\r") {
        if (text[j] === quote && text[j + 1] === quote) {
          j += 2;
        } else if (text[j++] === quote) {
          break;
```
with:
```js
    let kind;
    if (sql && ((c === "-" && text[j] === "-") || (c === "/" && text[j] === "*"))) {
      const end = c === "/" ? text.indexOf("*/", j + 1) : -1;
      if (c === "/") j = end < 0 ? n : end + 2;
      else while (j < n && text[j] !== "\n" && text[j] !== "\r") j++;
      comments.push({ from: i, to: j, kind: "comment", open: c === "/" && end < 0 });
      i = j;
      continue;
    }
    if (c === v.quote || c === v.identifierQuote) {
      kind = c === v.quote ? "string" : "name";
      while (j < n && text[j] !== "\n" && text[j] !== "\r") {
        if (text[j] === c && text[j + 1] === c) {
          j += 2;
        } else if (text[j++] === c) {
          break;
```

In `PAGE/editor-core.js`, replace:
```js
      while (j < n && NAME_PART.test(text[j])) j++;
    } else if ((c === ":" && j < n && NAME_PART.test(text[j])) || (c === "?" && isDigit(text[j]))) {
```
with:
```js
      while (j < n && NAME_PART.test(text[j])) j++;
    } else if (sql && c === ":" && text[j] === ":") {
      kind = "operator";
      j = i + 2;
    } else if ((c === ":" && j < n && NAME_PART.test(text[j])) || (c === "?" && isDigit(text[j]))) {
```

In `PAGE/editor-core.js`, replace:
```js
    } else {
      const operator = OPERATORS.find((o) => text.startsWith(o, i));
      kind = operator ? "operator" : "invalid";
```
with:
```js
    } else {
      const operator = (sql ? SQL_OPERATORS : OPERATORS).find((o) => text.startsWith(o, i));
      kind = operator ? "operator" : "invalid";
```

In `PAGE/editor-core.js`, replace:
```js
    }
    tokens.push({ from: i, to: j, kind });
    i = j;
  }
  return tokens;
}
```
with:
```js
    }
    tokens.push(c === v.identifierQuote ? { from: i, to: j, kind, quoted: true } : { from: i, to: j, kind });
    i = j;
  }
  return { tokens, comments };
}
```

In `PAGE/editor-core.js`, replace:
```js
/**
 * {@code text} read with the vocabulary of {@code data} (spec §3.1-§3.2): { v, tokens, target }. A token is { from,
 * to, kind }, kind keyword, function, target, attribute, identifier, string, number, parameter, operator, punct or
 * invalid; a word also has word, its capitals; a name of a path pathFrom, where the path starts, and attribute when
 * it resolves to one, or self when it is the dialect's self; a name that is wrong, problem, what is wrong. target is
 * the target named right after the first targetAfter word, wherever the caret is, or null when that name is unknown,
 * qualified (a.b.C, never checked) or missing. A path goes through the targets one step at a time: a cycle of
 * references costs one lookup per step of the text.
 */
```
with:
```js
/**
 * Whether token {@code k} of {@code tokens} is where a value goes: right of a comparison outside a SET, of LIKE, a
 * bound of BETWEEN, an element of an IN (…) list. A name there is a literal (spec §3.4).
 */
function valueTest(text, tokens) {
  const word = (t, w) => t !== undefined && t.kind === "keyword" && t.word === w;
  const punctIs = (t, c) => t !== undefined && t.kind === "punct" && text[t.from] === c;
  const inSet = (k) => {
    for (let i = k - 1; i >= 0; i--) {
      if (word(tokens[i], "SET") || word(tokens[i], "WHERE")) return word(tokens[i], "SET");
    }
    return false;
  };
  // The left operand of a comparison is a parameter or a literal (:min < price): the right one is the attribute.
  const valueOnTheLeft = (t) => t !== undefined
    && (t.kind === "parameter" || t.kind === "string" || t.kind === "number");
  const inList = (k) => {
    let depth = 0;
    for (let i = k - 1; i >= 0; i--) {
      if (punctIs(tokens[i], ")")) depth++;
      else if (punctIs(tokens[i], "(") && depth-- === 0) return word(tokens[i - 1], "IN");
    }
    return false;
  };
  return (k) => {
    const before = tokens[k - 1];
    if (before === undefined) return false;
    if (before.kind === "operator") {
      return COMPARISONS.has(text.slice(before.from, before.to)) && !valueOnTheLeft(tokens[k - 2]) && !inSet(k);
    }
    if (word(before, "LIKE") || word(before, "BETWEEN")) return true;
    if (word(before, "AND") && word(tokens[k - 3], "BETWEEN")) return true;
    return (punctIs(before, "(") || punctIs(before, ",")) && inList(k);
  };
}

/**
 * {@code text} read with the vocabulary of {@code data} (spec §3.1-§3.2): { v, tokens, comments, target, scope }. A
 * token is { from, to, kind }, kind keyword, function, target, attribute, identifier, string, number, parameter,
 * operator, punct or invalid; a word also has word, its capitals; a name of a path pathFrom, where the path starts,
 * and attribute when it resolves to one, or self when it is the dialect's self; a name that is wrong, problem, what is
 * wrong. target is the target named right after the first targetAfter word, wherever the caret is, or null when that
 * name is unknown, qualified (a.b.C, never checked) or missing. A path goes through the targets one step at a time: a
 * cycle of references costs one lookup per step of the text. comments are SQL's (lexQuery); SQL reads its targets and
 * paths with readSql, which gives scope, every target of the statement; JDQL's scope is empty.
 */
```

In `PAGE/editor-core.js`, replace:
```js
  const v = vocabulary(data);
  const tokens = lexQuery(text, v.quote);
  const self = v.self.toUpperCase();
  const isDot = (t) => t !== undefined && t.kind === "punct" && text[t.from] === ".";
  const joined = (a, b) => a !== undefined && b !== undefined && a.to === b.from;
  // A word next to a dot is a name of a path; else a function before "(", a keyword, or a name.
  tokens.forEach((t, k) => {
    if (t.kind !== "name") return;
    t.word = text.slice(t.from, t.to).toUpperCase();
```
with:
```js
  const v = vocabulary(data);
  const { tokens, comments } = lexQuery(text, v);
  const self = v.self === null ? null : v.self.toUpperCase();
  const isDot = (t) => t !== undefined && t.kind === "punct" && text[t.from] === ".";
  const joined = (a, b) => a !== undefined && b !== undefined && a.to === b.from;
  // A word next to a dot is a name of a path; else a function before "(", a keyword, or a name. A quoted name is a
  // name, whatever it spells.
  tokens.forEach((t, k) => {
    if (t.kind !== "name" || t.quoted) return;
    t.word = text.slice(t.from, t.to).toUpperCase();
```

In `PAGE/editor-core.js`, replace:
```js
  });
  // The name right after a targetAfter word is a target, known or not (a known one may be spelt as a keyword, such
```
with:
```js
  });
  if (v.aliases) {
    lastRead = { text, data, read: { v, tokens, comments, ...readSql(text, v, tokens) } };
    return lastRead.read;
  }
  // The name right after a targetAfter word is a target, known or not (a known one may be spelt as a keyword, such
```

In `PAGE/editor-core.js`, replace:
```js
  // A name where a value goes is a literal, such as an enum constant (pkg.Status.OPEN) or a bare word read as a
  // string: right of a comparison outside a SET, of LIKE, a bound of BETWEEN, an element of an IN (…) list.
  const word = (t, w) => t !== undefined && t.kind === "keyword" && t.word === w;
  const punctIs = (t, c) => t !== undefined && t.kind === "punct" && text[t.from] === c;
  const inSet = (k) => {
    for (let i = k - 1; i >= 0; i--) {
      if (word(tokens[i], "SET") || word(tokens[i], "WHERE")) return word(tokens[i], "SET");
    }
    return false;
  };
  // The left operand of a comparison is a parameter or a literal (:min < price): the right one is the attribute.
  const valueOnTheLeft = (t) => t !== undefined
    && (t.kind === "parameter" || t.kind === "string" || t.kind === "number");
  const inList = (k) => {
    let depth = 0;
    for (let i = k - 1; i >= 0; i--) {
      if (punctIs(tokens[i], ")")) depth++;
      else if (punctIs(tokens[i], "(") && depth-- === 0) return word(tokens[i - 1], "IN");
    }
    return false;
  };
  const isValue = (k) => {
    const before = tokens[k - 1];
    if (before === undefined) return false;
    if (before.kind === "operator") {
      return COMPARISONS.has(text.slice(before.from, before.to)) && !valueOnTheLeft(tokens[k - 2]) && !inSet(k);
    }
    if (word(before, "LIKE") || word(before, "BETWEEN")) return true;
    if (word(before, "AND") && word(tokens[k - 3], "BETWEEN")) return true;
    return (punctIs(before, "(") || punctIs(before, ",")) && inList(k);
  };
  // Every other name heads a path, name.name…, resolved from the target one step at a time; with no known target,
```
with:
```js
  // A name where a value goes is a literal, such as an enum constant (pkg.Status.OPEN) or a bare word read as a
  // string.
  const isValue = valueTest(text, tokens);
  // Every other name heads a path, name.name…, resolved from the target one step at a time; with no known target,
```

In `PAGE/editor-core.js`, replace:
```js
  });
  lastRead = { text, data, read: { v, tokens, target } };
  return lastRead.read;
}
```
with:
```js
  });
  lastRead = { text, data, read: { v, tokens, comments, target, scope: [] } };
  return lastRead.read;
}

/** A name written without quotes as the database stores it: in lower case or in capitals when the dialect says so. */
const folded = (v, name) => v.unquotedCase === "lower" ? name.toLowerCase()
  : v.unquotedCase === "upper" ? name.toUpperCase() : name;

/** The name a name token stands for: a quoted one's text between its quotes, a doubled quote read as one. */
function nameOf(text, t, v) {
  if (!t.quoted) return text.slice(t.from, t.to);
  const q = v.identifierQuote;
  return text.slice(t.from + 1, isClosed(text, t) ? t.to - 1 : t.to).replaceAll(q + q, q);
}

/** The key of a name token: a quoted name as written, an unquoted one as the database stores it. */
const keyOf = (text, t, v) => t.quoted ? nameOf(text, t, v) : folded(v, nameOf(text, t, v));

/** Whether two keys name the same thing: equal, or equal ignoring case when the dialect keeps names as written. */
const sameName = (v, a, b) => a === b || (v.unquotedCase === null && a.toLowerCase() === b.toLowerCase());

/**
 * The entry of {@code map}, targets or attributes, that {@code key} names: as it is, then, for an unquoted name when
 * the dialect does not say how it stores names, ignoring case (SQL spec §3); null when none does.
 */
function lookup(v, map, key, quoted) {
  const found = map.get(key);
  if (found !== undefined || quoted || v.unquotedCase !== null) return found || null;
  const lower = key.toLowerCase();
  for (const [name, entry] of map) if (name.toLowerCase() === lower) return entry;
  return null;
}

/** The target of {@code scope} that {@code key} names: by its alias first, then by its name; null when none does. */
const scoped = (v, scope, key) => scope.find((s) => s.alias !== null && sameName(v, s.alias, key))
  || scope.find((s) => sameName(v, s.name, key)) || null;

/**
 * The targets and the paths of a SQL query (SQL spec §3), its dialect having aliases: { target, scope }, scope one {
 * entry, label, name, alias, written } per target of the statement in the order of the text — its vocabulary entry
 * (null when it has none), its name as a message says it, the key of its name and of its alias (null for none), its
 * alias as written. Only the outer level names targets: a targetAfter word in parentheses (EXTRACT(YEAR FROM d))
 * names none, and what a sub-query holds stays unchecked. FROM takes a list, comma separated; a name after a target,
 * or AS and a name, aliases it, a keyword never; schema.name is looked up as written, then without its schema. A
 * path's head is an alias, a target's name or a column of a target in scope; a bare name is a column of any target in
 * scope; a name where a value goes is a literal, unless it is a path from an alias or a table. A common table
 * expression's name, an alias of the select list, a cast's type (::type) and an unknown function are not checked.
 * target is the first target's entry.
 */
function readSql(text, v, tokens) {
  const isDot = (t) => t !== undefined && t.kind === "punct" && text[t.from] === ".";
  const joined = (a, b) => a !== undefined && b !== undefined && a.to === b.from;
  const punctIs = (t, c) => t !== undefined && t.kind === "punct" && text[t.from] === c;
  const word = (t, w) => t !== undefined && t.kind === "keyword" && t.word === w;
  const isName = (t) => t !== undefined && t.kind === "name";
  const key = (t) => keyOf(text, t, v);
  // How deep in parentheses each token is, and whether it is inside a sub-query's.
  const open = [];
  tokens.forEach((t, k) => {
    if (punctIs(t, ")") && open.length) open.pop();
    t.depth = open.length;
    t.sub = open.length > 0 && open[open.length - 1];
    if (punctIs(t, "(")) open.push(t.sub || ["SELECT", "WITH", "VALUES"].some((w) => word(tokens[k + 1], w)));
  });
  // WITH name AS (…): a common table expression, a target whose columns are unknown.
  const ctes = [];
  tokens.forEach((t, k) => {
    if (isName(t) && t.depth === 0 && word(tokens[k + 1], "AS") && punctIs(tokens[k + 2], "(")) {
      t.kind = "target";
      t.position = "target";
      ctes.push(key(t));
    }
  });
  const scope = [];
  tokens.forEach((t, k) => {
    if (t.kind !== "keyword" || !v.targetAfter.has(t.word) || t.depth !== 0) return;
    for (let at = k + 1; ;) {
      const first = tokens[at];
      if (!isName(first) && !(first !== undefined && first.kind === "keyword"
        && lookup(v, v.targets, key(first), false) !== null)) return;
      let end = at;
      while (isDot(tokens[end + 1]) && joined(tokens[end], tokens[end + 1]) && isName(tokens[end + 2])
        && joined(tokens[end + 1], tokens[end + 2])) end += 2;
      const keys = [];
      for (let i = at; i <= end; i += 2) keys.push(key(tokens[i]));
      const last = tokens[end];
      let entry = lookup(v, v.targets, keys.join("."), at === end && first.quoted === true);
      if (entry === null && end > at) entry = lookup(v, v.targets, keys[keys.length - 1], last.quoted === true);
      const cte = at === end && ctes.some((c) => sameName(v, c, keys[0]));
      for (let i = at; i <= end; i += 2) {
        tokens[i].kind = entry !== null || cte ? "target" : "identifier";
        tokens[i].position = "target";
      }
      let next = end + 1;
      let alias = null;
      if (word(tokens[next], "AS") && isName(tokens[next + 1])) {
        alias = tokens[next + 1];
        next += 2;
      } else if (isName(tokens[next])) {
        alias = tokens[next];
        next += 1;
      }
      if (alias !== null) {
        alias.kind = "target";
        alias.position = "alias";
      }
      scope.push({ entry, label: entry !== null ? entry.name : text.slice(first.from, last.to),
        name: keys[keys.length - 1], alias: alias === null ? null : key(alias),
        written: alias === null ? null : nameOf(text, alias, v) });
      if (t.word !== "FROM" || !punctIs(tokens[next], ",")) return;
      at = next + 1;
    }
  });
  // An alias of the select list (AS name, or a name right after an expression), a cast's type and an unknown
  // function's name: names, never checked; the select list's aliases may be named again (ORDER BY n).
  const ends = (t) => t !== undefined && (["name", "string", "number", "parameter"].includes(t.kind)
    || punctIs(t, ")") || ["END", "NULL", "TRUE", "FALSE"].some((w) => word(t, w)));
  const outputs = [];
  tokens.forEach((t, k) => {
    const before = tokens[k - 1];
    if (!isName(t) || t.position !== undefined || (isDot(before) && joined(before, t))
      || (isDot(tokens[k + 1]) && joined(t, tokens[k + 1]))) return;
    if (word(before, "AS") || (ends(before) && !t.sub)) {
      t.kind = "identifier";
      t.position = "alias";
      outputs.push(key(t));
    } else if (before !== undefined && before.kind === "operator" && text.slice(before.from, before.to) === "::") {
      t.kind = "identifier";
      t.position = "type";
    } else if (punctIs(tokens[k + 1], "(")) {
      t.kind = "identifier";
      t.position = "call";
    }
  });
  // Every other name heads a path, from an alias or a table of the scope, or from a column of its targets.
  const isValue = valueTest(text, tokens);
  tokens.forEach((head, k) => {
    if (!isName(head) || head.position !== undefined || (isDot(tokens[k - 1]) && joined(tokens[k - 1], head))) return;
    const dotted = isDot(tokens[k + 1]) && joined(head, tokens[k + 1]);
    const owner = dotted ? scoped(v, scope, key(head)) : null;
    if (head.sub || (head.quoted && !isClosed(text, head)) || (owner === null && isValue(k))) {
      for (let at = k; tokens[at] !== undefined && (at === k || isName(tokens[at])); at += 2) {
        tokens[at].kind = "identifier";
        if (!isDot(tokens[at + 1]) || !joined(tokens[at], tokens[at + 1])) break;
      }
      return;
    }
    head.pathFrom = head.from;
    let entry = null;
    if (owner !== null) {
      head.kind = "target";
      entry = owner.entry;
    } else {
      const owners = scope.filter((s) => s.entry !== null
        && lookup(v, s.entry.attributes, key(head), head.quoted === true) !== null);
      if (owners.length) {
        head.kind = "attribute";
        head.attribute = lookup(v, owners[0].entry.attributes, key(head), head.quoted === true);
      } else {
        head.kind = !dotted && scoped(v, scope, key(head)) !== null ? "target" : "identifier";
      }
    }
    for (let at = k, t = head; ;) {
      const dot = tokens[at + 1];
      if (!isDot(dot) || !joined(t, dot)) break;
      const after = tokens[at + 2];
      const more = isName(after) && joined(dot, after);
      if (t !== head || owner === null) {
        entry = t.attribute !== undefined && t.attribute.target !== null
          ? v.targets.get(t.attribute.target) || null : null;
      }
      if (!more) break;
      at += 2;
      t = after;
      t.pathFrom = head.from;
      const attribute = entry === null ? null : lookup(v, entry.attributes, key(t), t.quoted === true);
      t.kind = attribute !== null ? "attribute" : "identifier";
      if (attribute !== null) t.attribute = attribute;
    }
  });
  return { target: scope.length ? scope[0].entry : null, scope };
}
```

In `PAGE/editor-core.js`, replace:
```js
      items = [...attributeItems(target),
        { insert: v.self, label: v.self, detail: "the " + target.name + " itself", kind: "keyword" },
        ...v.functionList.map((f) => call ? { insert: f, label: f, detail: "function", kind: "function" }
          : { insert: f + "()", label: f, detail: "function", kind: "function", caret: f.length + 1 }),
        ...keywords.filter((k) => !v.functions.has(k.label) && k.label !== v.self.toUpperCase())];
    } else {
```
with:
```js
      items = [...attributeItems(target),
        ...(v.self === null ? [] : [{ insert: v.self, label: v.self, detail: "the " + target.name + " itself",
          kind: "keyword" }]),
        ...v.functionList.map((f) => call ? { insert: f, label: f, detail: "function", kind: "function" }
          : { insert: f + "()", label: f, detail: "function", kind: "function", caret: f.length + 1 }),
        ...keywords.filter((k) => !v.functions.has(k.label) && (v.self === null || k.label !== v.self.toUpperCase()))];
    } else {
```

In `PAGE/editor-core.js`, replace:
```js
  id: "query",
  tokenize: (text, data) => read(text, data).tokens.map(({ from, to, kind }) => ({ from, to, kind })),
  diagnose: diagnoseQuery,
```
with:
```js
  id: "query",
  tokenize(text, data) {
    const { tokens, comments } = read(text, data);
    const all = comments.length ? [...tokens, ...comments].sort((a, b) => a.from - b.from) : tokens;
    return all.map(({ from, to, kind, quoted }) => quoted ? { from, to, kind, quoted } : { from, to, kind });
  },
  diagnose: diagnoseQuery,
```

In `PAGE/editor-core.js`, replace:
```js
  pairs: Object.freeze(["()", "''"]),
});
```
with:
```js
  pairs: Object.freeze(["()", "''"]),
  /** The pairs with {@code data}: parentheses, the dialect's quote, and its identifier quote when it has one. */
  pairsOf(data) {
    const v = vocabulary(data);
    return ["()", v.quote + v.quote, ...(v.identifierQuote === null ? [] : [v.identifierQuote + v.identifierQuote])];
  },
});
```

In `PAGE/editor.js`, replace:
```js
import { jsonLanguage, queryLanguage, keystroke, isShortcut, FORMAT_EXAMPLES } from "./editor-core.js";
```
with:
```js
import { jsonLanguage, queryLanguage, keystroke, isShortcut, pairsOf, FORMAT_EXAMPLES } from "./editor-core.js";
```

In `PAGE/editor.js`, replace:
```js
    if (tab && leaving) return;
    const paired = key.length === 1 && Array.isArray(language.pairs) && language.pairs.some((p) => p.includes(key));
    if (!KEYS.has(key) && !paired) return;
    const change = safely(() => keystroke(language, textarea.value, textarea.selectionStart, textarea.selectionEnd,
      key), null);
    if (change === null) {
```
with:
```js
    if (tab && leaving) return;
    // a query's pairs depend on its data: a SQL dialect pairs its identifier quote too
    const paired = key.length === 1 && pairsOf(language, data).some((p) => p.includes(key));
    if (!KEYS.has(key) && !paired) return;
    const change = safely(() => keystroke(language, textarea.value, textarea.selectionStart, textarea.selectionEnd,
      key, data), null);
    if (change === null) {
```

In `PAGE/console.css`, replace:
```css
.ed-operator { color: var(--json-punct); }
.ed-text::placeholder { color: var(--faint); }
```
with:
```css
.ed-operator { color: var(--json-punct); }
/* A SQL comment: dimmed, its width untouched. */
.ed-comment { color: var(--faint); }
.ed-text::placeholder { color: var(--faint); }
```

- [ ] **Step 4: Run the tests to verify they pass, JDQL's and JSON's unchanged**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest='SqlLanguageTest,PageTest,QueryLanguageTest,EditorCoreTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run:|FAIL" | tail -5
```
Expected: `Tests run: 10` (`SqlLanguageTest`), `Tests run: 34` (`PageTest`), `Tests run: 37` (`QueryLanguageTest`),
`Tests run: 50` (`EditorCoreTest`), each `Failures: 0, Errors: 0`, then `Tests run: 131, Failures: 0, Errors: 0`.

`cp PAGE/editor-core.js $SCRATCH/core.mjs && node --check $SCRATCH/core.mjs` and the same for `PAGE/editor.js` print
nothing.

- [ ] **Step 5: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(devconsole): the query mode reads SQL's tables, aliases and quoted names

A dialect with "aliases": true is SQL's: every table after FROM, JOIN, UPDATE or INTO at the outer level is in the
statement's scope, a FROM list included; a name after a table, or AS and a name, aliases it; a path starts from an
alias, a table or a column of a table in scope, a bare column is looked up in all of them. "identifierQuote" makes
"Order" a name, a doubled quote one, looked up as written; "unquotedCase" says how the database stores a name
written without quotes. -- and /* */ comments are tokens of their own; ::, || and % are operators; a sub-query, a
WITH's table, an alias of the select list, a cast's type and an unknown function are left unchecked. The keystrokes
take the data: the identifier quote pairs and steps over as the string quote does. JDQL's path is unchanged.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/editor-core.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/editor.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/console.css vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/SqlLanguageTest.java vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/PageTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 5: SQL's completion — tables, an alias's columns, every column in scope, quoted as needed (§3)

**Files:**
- Modify: `PAGE/editor-core.js` (`EXPRESSION_CLAUSES`, `completeQuery`, and after it `written`, `writtenTarget`,
  `columnItems`, `sqlItems`)
- Modify: `DCT/SqlLanguageTest.java` (a completion section)

**Interfaces:**
- Consumes: Task 4's `read` (`scope`, `comments`, `v.aliases`, `v.identifierQuote`, `v.unquotedCase`, token `depth`,
  `position`, `attribute`), `folded`, `keyOf`, `scoped`; `clauseBefore`.
- Produces: `written(v, name)` (a name quoted when it is no plain name, a keyword, or not stored as written),
  `writtenTarget(v, target)`, `columnItems(v, entry, table)`, `sqlItems(text, v, tokens, scope, target, p, from, to)`;
  `EXPRESSION_CLAUSES` gains `ON`, `GROUP BY`, `HAVING`.

- [ ] **Step 1: Write the failing tests**

In `DCT/SqlLanguageTest.java`, replace:
```java
    // ------------------------------------------------------------------------------------------------ parameters
```
with:
```java
    // ------------------------------------------------------------------------------------------------ completion

    /** The functions of the fixture as they are inserted, a | where the caret lands. */
    private static final String FUNCTIONS = "COUNT(|) MAX(|) UPPER(|) COALESCE(|)";
    /** Every keyword of the fixture but its functions, in its order: what follows them in a clause. */
    private static final String OTHER_KEYWORDS = "SELECT FROM WHERE JOIN LEFT INNER OUTER ON AS AND OR NOT NULL IS "
            + "IN LIKE BETWEEN GROUP BY HAVING ORDER ASC DESC LIMIT OFFSET INSERT INTO VALUES UPDATE SET DELETE WITH "
            + "DISTINCT CASE WHEN THEN ELSE END TRUE FALSE INTEGER";

    private static Value completion(String marked) {
        int caret = marked.indexOf('|');
        return language.invokeMember("complete", marked.substring(0, caret) + marked.substring(caret + 1), caret,
                sql);
    }

    /** "from-to", then each item's insert, a | where the caret lands inside it; "null" when there is none. */
    private static String complete(String marked) {
        Value found = completion(marked);
        if (found.isNull()) {
            return "null";
        }
        List<String> out = new ArrayList<>(List.of(number(found, "from") + "-" + number(found, "to")));
        Value items = found.getMember("items");
        for (long i = 0; i < items.getArraySize(); i++) {
            Value item = items.getArrayElement(i);
            String insert = item.getMember("insert").asString();
            Value caret = item.getMember("caret");
            out.add(caret == null || caret.isNull() ? insert
                    : insert.substring(0, caret.asInt()) + "|" + insert.substring(caret.asInt()));
        }
        return String.join(" ", out);
    }

    /** The first {@code count} items of that completion as label: detail, one per line. */
    private static String details(String marked, int count) {
        Value items = completion(marked).getMember("items");
        List<String> out = new ArrayList<>();
        for (long i = 0; i < Math.min(count, items.getArraySize()); i++) {
            Value item = items.getArrayElement(i);
            out.add(item.getMember("label").asString() + ": " + item.getMember("detail").asString());
        }
        return String.join("\n", out);
    }

    @Test
    void whereATargetGoesTheTargetsWrittenAsTheDatabaseNeedsThem() {
        assertEquals("14-14 tasks projects \"Order\" sales.orders", complete("SELECT * FROM |"));
        assertEquals("tasks: table · public\nprojects: table · public\nOrder: table · public\n"
                + "sales.orders: view · sales", details("SELECT * FROM |", 4));
        assertEquals("27-29 projects", complete("SELECT * FROM tasks t JOIN pr|"));
        assertEquals("23-23 tasks projects \"Order\" sales.orders", complete("SELECT * FROM tasks t, |"),
                "after a comma of the FROM list");
        assertEquals("14-18 \"Order\"", complete("SELECT * FROM \"Or|\""), "a quoted name being typed, whole");
        assertEquals("20-20 orders", complete("SELECT * FROM sales.|"), "after a schema, its tables");
    }

    @Test
    void afterAnAliasOrATableAndADotThatTablesColumns() {
        assertEquals("9-9 id title price due_date project_id", complete("SELECT t.| FROM tasks t"),
                "the table is written after the caret");
        assertEquals("id: int8 · column\ntitle: varchar(200) · column", details("SELECT t.| FROM tasks t", 2));
        assertEquals("9-9 id \"due date\"", complete("SELECT o.| FROM \"Order\" o"), "a name with a space, quoted");
        assertEquals("13-15 title", complete("SELECT tasks.ti| FROM tasks"));
        assertEquals("null", complete("SELECT x.| FROM tasks t"), "no x in scope");
    }

    @Test
    void inAnOnTheAliasesTheTargetsThenEveryColumnWithItsTableThenFunctionsAndKeywords() {
        assertEquals("41-41 t p tasks projects id title price due_date project_id id title lead " + FUNCTIONS + " "
                + OTHER_KEYWORDS, complete("SELECT * FROM tasks t JOIN projects p ON |"));
        assertEquals("t: alias of tasks\np: alias of projects\ntasks: table · public\nprojects: table · public\n"
                + "id: int8 · column · tasks\ntitle: varchar(200) · column · tasks",
                details("SELECT * FROM tasks t JOIN projects p ON |", 6));
        assertEquals("7-7 tasks id title price due_date project_id " + FUNCTIONS + " " + OTHER_KEYWORDS,
                complete("SELECT | FROM tasks"), "no self in a dialect that has none");
        assertEquals("30-32 \"due date\"", complete("SELECT * FROM \"Order\" o WHERE du|"));
        assertEquals("7-9 tasks id title price due_date project_id", complete("SELECT \"|\" FROM tasks"),
                "in quotes, the names only");
        assertEquals("null", complete("SELECT * FROM tasks -- ti|"), "nothing in a comment");
    }

    @Test
    void aQueryBeingTypedCompletesAtEveryCaretAndNeverThrows() {
        for (String typed : new String[] {"", "S", "SELECT * FROM ", "SELECT t. FROM tasks t", "SELECT \"",
                "SELECT * FROM tasks t JOIN projects p ON p.", "FROM .", "FROM tasks t, ", "SELECT /* x",
                "WITH r AS (SELECT", "SELECT a::", "SELECT * FROM (SELECT * FROM tasks) x WHERE x."}) {
            for (int caret = 0; caret <= typed.length(); caret++) {
                completion(typed.substring(0, caret) + "|" + typed.substring(caret));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ parameters
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=SqlLanguageTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run:|FAIL" | tail -3
```
Expected: FAIL — `Tests run: 14, Failures: 3, Errors: 0`: the three completion tests that expect items;
`aQueryBeingTypedCompletesAtEveryCaretAndNeverThrows` passes already (nothing threw before either).

- [ ] **Step 3: Write SQL's completion**

In `PAGE/editor-core.js`, replace:
```js
/** The clauses whose expressions name the target's attributes (spec §3.3). */
const EXPRESSION_CLAUSES = new Set(["SELECT", "WHERE", "ORDER BY", "SET"]);
/** The kinds of a token that is a word being typed. */
```
with:
```js
/** The clauses whose expressions name the target's attributes (spec §3.3), SQL's ON, GROUP BY and HAVING too. */
const EXPRESSION_CLAUSES = new Set(["SELECT", "WHERE", "ORDER BY", "SET", "ON", "GROUP BY", "HAVING"]);
/** The kinds of a token that is a word being typed. */
```

In `PAGE/editor-core.js`, replace:
```js
function completeQuery(text, caret, data) {
  const { v, tokens, target } = read(text, data);
  const current = tokens.find((t) => t.from < caret && caret <= t.to) || null;
  const word = current !== null && WORDS.has(current.kind) ? current : null;
  if (current !== null && word === null && current.kind !== "punct" && current.kind !== "operator") return null;
  const from = word !== null ? word.from : caret;
  const to = word !== null ? word.to : caret;
  let p = -1;
```
with:
```js
function completeQuery(text, caret, data) {
  const { v, tokens, comments, target, scope } = read(text, data);
  if (comments.some((c) => c.from < caret && (caret < c.to || (caret === c.to && (c.open || text[c.from] === "-"))))) {
    return null;
  }
  const current = tokens.find((t) => t.from < caret && caret <= t.to) || null;
  const word = current !== null && WORDS.has(current.kind) ? current : null;
  if (current !== null && word === null && current.kind !== "punct" && current.kind !== "operator") return null;
  // a quoted name being typed is replaced whole once it is closed, up to the caret while it is not
  const quoted = word !== null && word.quoted === true;
  const from = word !== null ? word.from : caret;
  const to = word !== null && (!quoted || isClosed(text, word)) ? word.to : caret;
  let p = -1;
```

In `PAGE/editor-core.js`, replace:
```js
  let items;
  if (previous !== undefined && previous.kind === "punct" && text[previous.from] === "." && previous.to === from) {
    const owner = tokens[p - 1];
```
with:
```js
  let items;
  if (v.aliases) {
    // in quotes, a name is typed: no keyword, no function
    items = sqlItems(text, v, tokens, scope, target, p, from, to)
      .filter((item) => !quoted || item.kind === "target" || item.kind === "attribute");
  } else if (previous !== undefined && previous.kind === "punct" && text[previous.from] === "."
    && previous.to === from) {
    const owner = tokens[p - 1];
```

In `PAGE/editor-core.js`, replace:
```js
  }
  const prefix = text.slice(from, caret).toLowerCase();
  items = items.filter((item) => item.label.toLowerCase().startsWith(prefix));
  return items.length ? { from, to, items } : null;
}
```
with:
```js
  }
  const prefix = text.slice(quoted ? from + 1 : from, caret).toLowerCase();
  items = items.filter((item) => item.label.toLowerCase().startsWith(prefix));
  return items.length ? { from, to, items } : null;
}

/**
 * {@code name} as a SQL query writes it (SQL spec §3): between the dialect's identifier quotes, a quote inside it
 * doubled, when it is no plain name (letters, digits and _, not first a digit), when it is a keyword, or when the
 * database would store it otherwise; as it is when none of these holds, or when the dialect quotes no name.
 */
function written(v, name) {
  const q = v.identifierQuote;
  if (q === null || (/^[\p{L}_][\p{L}\p{N}_]*$/u.test(name) && !v.keywords.has(name.toUpperCase())
    && folded(v, name) === name)) return name;
  return q + name.replaceAll(q, q + q) + q;
}

/** A target as a SQL query writes it: a table of another schema as schema.name, each part as written() says. */
const writtenTarget = (v, t) => t.schema !== null && t.name.startsWith(t.schema + ".")
  ? written(v, t.schema) + "." + written(v, t.name.slice(t.schema.length + 1)) : written(v, t.name);

/** The completion items of the columns of {@code entry}, each as SQL writes it, its table after its detail if any. */
const columnItems = (v, entry, table) => [...entry.attributes.values()].map((a) => ({ insert: written(v, a.name),
  label: a.name, detail: table === null ? a.detail : (a.detail ? a.detail + " · " : "") + table, kind: "attribute" }));

/**
 * The completion items of a SQL query (SQL spec §3), the word being typed at {@code from}-{@code to}, after token
 * {@code p}: after "alias." or "table." that table's columns, after "schema." where a target goes that schema's
 * tables; where a target goes the targets; in a SELECT, ON, WHERE, GROUP BY, HAVING, ORDER BY or SET clause with a
 * known target in scope, the aliases and the targets in scope, the columns of each with its table in their detail,
 * self when the dialect has one, the functions and the other keywords; anywhere else the keywords. Each name is
 * inserted as the database needs it.
 */
function sqlItems(text, v, tokens, scope, target, p, from, to) {
  const previous = tokens[p];
  const punctIs = (t, c) => t !== undefined && t.kind === "punct" && text[t.from] === c;
  const keywords = v.keywordList.map((k) => ({ insert: k, label: k, detail: "keyword", kind: "keyword" }));
  if (punctIs(previous, ".") && previous.to === from) {
    const owner = tokens[p - 1];
    if (owner === undefined || owner.to !== previous.from) return [];
    if (owner.position === "target") {
      const schema = keyOf(text, owner, v) + ".";
      return [...v.targets.values()].filter((t) => t.name.startsWith(schema)).map((t) => ({
        insert: written(v, t.name.slice(schema.length)), label: t.name.slice(schema.length), detail: t.detail,
        kind: "target" }));
    }
    const s = owner.attribute === undefined ? scoped(v, scope, keyOf(text, owner, v)) : null;
    const entry = s !== null ? s.entry : owner.attribute !== undefined && owner.attribute.target !== null
      ? v.targets.get(owner.attribute.target) || null : null;
    return entry === null ? [] : columnItems(v, entry, null);
  }
  const outer = previous !== undefined && previous.depth === 0;
  if (outer && ((previous.kind === "keyword" && v.targetAfter.has(previous.word))
    || (punctIs(previous, ",") && clauseBefore(v, tokens, p + 1) === "FROM"))) {
    return [...v.targets.values()].map((t) => ({ insert: writtenTarget(v, t), label: t.name, detail: t.detail,
      kind: "target" }));
  }
  const known = scope.filter((s) => s.entry !== null);
  if (!known.length || !EXPRESSION_CLAUSES.has(clauseBefore(v, tokens, p + 1))) return keywords;
  const call = text[to] === "(";
  const aliases = scope.filter((s) => s.written !== null).map((s) => ({ insert: written(v, s.written),
    label: s.written, detail: "alias of " + s.label, kind: "target" }));
  const targets = new Map();
  for (const s of scope) {
    if (!targets.has(s.label)) {
      targets.set(s.label, { insert: s.entry !== null ? writtenTarget(v, s.entry) : s.label, label: s.label,
        detail: s.entry !== null ? s.entry.detail : "", kind: "target" });
    }
  }
  const entries = [...new Set(known.map((s) => s.entry))];
  return [...aliases, ...targets.values(), ...entries.flatMap((entry) => columnItems(v, entry, entry.name)),
    ...(v.self === null || target === null ? [] : [{ insert: v.self, label: v.self,
      detail: "the " + target.name + " itself", kind: "keyword" }]),
    ...v.functionList.map((f) => call ? { insert: f, label: f, detail: "function", kind: "function" }
      : { insert: f + "()", label: f, detail: "function", kind: "function", caret: f.length + 1 }),
    ...keywords.filter((k) => !v.functions.has(k.label) && (v.self === null || k.label !== v.self.toUpperCase()))];
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest='SqlLanguageTest,QueryLanguageTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run:|FAIL" | tail -3
```
Expected: `Tests run: 14` (`SqlLanguageTest`) and `Tests run: 37` (`QueryLanguageTest`), each `Failures: 0, Errors:
0`, then `Tests run: 51, Failures: 0, Errors: 0`. `node --check` of `editor-core.js`, as in Task 4, prints nothing.

- [ ] **Step 5: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(devconsole): complete SQL's tables, an alias's columns and every column in scope

In SQL, Ctrl+Space offers the tables after FROM, JOIN, UPDATE, INTO or a comma of the FROM list, a schema's tables
after "schema."; an alias's or a table's columns after "t." or "tasks."; in SELECT, ON, WHERE, GROUP BY, HAVING,
ORDER BY and SET, the aliases and the tables in scope, then the columns of each with its table in their detail, the
functions and the keywords. A name that is no plain name, a keyword, or not stored as written is inserted quoted;
a quoted name being typed is completed too, and nothing is offered in a comment.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/editor-core.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/SqlLanguageTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 6: SQL's diagnostics — unknown alias, ambiguous column, qualified targets (§3)

**Files:**
- Modify: `PAGE/editor-core.js` (`sentence`, `scopeName`, the problems and warnings of `readSql`, `diagnoseQuery`)
- Modify: `DCT/SqlLanguageTest.java` (a diagnostics section)

**Interfaces:**
- Consumes: Task 4's `readSql`, `nameOf`, `keyOf`, `sameName`, `lookup`, `scoped`.
- Produces: a token's `warning` (a string), shown by `diagnoseQuery` with `severity: "warning"`;
  `sentence(names, word)`, `scopeName(s)`; the errors `unknown target …`, `unknown column … of …`,
  `unknown table or alias …`, `… is not a reference`, `unterminated identifier`, `unterminated comment`.

- [ ] **Step 1: Write the failing tests**

In `DCT/SqlLanguageTest.java`, replace:
```java
    // ------------------------------------------------------------------------------------------------ parameters
```
with:
```java
    // ------------------------------------------------------------------------------------------------ diagnostics

    /** The diagnostics of {@code text} read with the SQL fixture, one per line: severity from-to message. */
    private static String diagnose(String text) {
        Value found = language.invokeMember("diagnose", text, sql);
        List<String> out = new ArrayList<>();
        for (long i = 0; i < found.getArraySize(); i++) {
            Value d = found.getArrayElement(i);
            out.add(d.getMember("severity").asString() + " " + number(d, "from") + "-" + number(d, "to") + " "
                    + d.getMember("message").asString());
        }
        return String.join("\n", out);
    }

    @Test
    void anUnknownTargetAliasOrColumnIsAnError() {
        assertEquals("error 14-18 unknown target taks", diagnose("SELECT * FROM taks"));
        assertEquals("error 9-14 unknown column titel of tasks", diagnose("SELECT t.titel FROM tasks t"));
        assertEquals("error 7-11 unknown column nope of tasks t or projects p",
                diagnose("SELECT nope FROM tasks t JOIN projects p ON p.id = t.project_id"));
        assertEquals("error 7-8 unknown table or alias x", diagnose("SELECT x.title FROM tasks t"),
                "and nothing after it checked");
        assertEquals("error 15-16 title is not a reference", diagnose("SELECT t.title.x FROM tasks t"));
    }

    @Test
    void aColumnThatTwoTargetsHaveNamedAloneIsAWarning() {
        assertEquals("warning 7-12 title is in tasks t and projects p",
                diagnose("SELECT title FROM tasks t JOIN projects p ON p.id = t.project_id"));
        assertEquals("", diagnose("SELECT t.title, lead FROM tasks t JOIN projects p ON p.id = t.project_id"),
                "named with its alias, or a column of one table only");
    }

    @Test
    void aQualifiedTargetIsCheckedOnlyWhenItIsFound() {
        assertEquals("error 9-13 unknown column nope of sales.orders", diagnose("SELECT s.nope FROM sales.orders s"));
        assertEquals("", diagnose("SELECT title FROM public.tasks"), "found without its schema");
        assertEquals("", diagnose("SELECT nope FROM other.nothing"), "never checked, nor its columns");
    }

    @Test
    void whatTheEditorCannotKnowIsNeverAnError() {
        assertEquals("", diagnose("SELECT * FROM tasks WHERE id IN (SELECT nope FROM nothing)"), "a sub-query");
        assertEquals("", diagnose("WITH recent AS (SELECT id FROM tasks) SELECT x FROM recent"),
                "a common table expression");
        assertEquals("", diagnose("SELECT title AS t2, price p2 FROM tasks ORDER BY t2, p2"),
                "the select list's aliases");
        assertEquals("", diagnose("SELECT due_date::text, date_trunc('day', due_date) FROM tasks"),
                "a cast's type, an unknown function");
        assertEquals("", diagnose("SELECT * FROM tasks WHERE title = OPEN"), "a name where a value goes, a literal");
        assertEquals("", diagnose("SELECT COUNT(*) FROM tasks t, projects p WHERE t.project_id = p.id"));
    }

    @Test
    void anUnterminatedQuotedNameOrCommentIsAnError() {
        assertEquals("error 7-13 unterminated identifier", diagnose("SELECT \"Total\nFROM tasks"));
        assertEquals("error 9-14 unterminated comment", diagnose("SELECT 1 /* x\n"));
        assertEquals("", diagnose("SELECT 1 -- it's a \"comment (\nFROM tasks"), "a comment holds no token");
    }

    @Test
    void aQueryBeingTypedIsCheckedForItsNamesOnlyAndNeverThrows() {
        assertEquals("", diagnose("SELECT * FROM "));
        assertEquals("", diagnose("SELECT t. FROM tasks t"), "a column being typed");
        for (String typed : new String[] {"", "S", "SELECT \"", "FROM .", "FROM tasks t, ", "SELECT /* x",
                "WITH r AS (SELECT", "SELECT a::", "SELECT * FROM tasks t JOIN projects p ON p.", "UPDATE \"",
                "INSERT INTO tasks (", "SELECT * FROM (SELECT * FROM tasks) x WHERE x."}) {
            diagnose(typed);
        }
    }

    // ------------------------------------------------------------------------------------------------ parameters
```

- [ ] **Step 2: Run the tests to verify they fail**

Run the command of Task 5's Step 2.
Expected: FAIL — `Tests run: 20, Failures: 4, Errors: 0`: `anUnknownTargetAliasOrColumnIsAnError`,
`aColumnThatTwoTargetsHaveNamedAloneIsAWarning`, `aQualifiedTargetIsCheckedOnlyWhenItIsFound` and
`anUnterminatedQuotedNameOrCommentIsAnError`; the two tests that expect no diagnostic pass already.

- [ ] **Step 3: Write SQL's diagnostics**

In `PAGE/editor-core.js`, replace:
```js
/** The target of {@code scope} that {@code key} names: by its alias first, then by its name; null when none does. */
```
with:
```js
/** {@code names} as a sentence: "a", "a and b", "a, b and c", with {@code word} for "and". */
const sentence = (names, word) => names.length < 2 ? names.join("")
  : names.slice(0, -1).join(", ") + " " + word + " " + names[names.length - 1];

/** A target of a scope as a message names it: its table, and its alias when it has one, "tasks t". */
const scopeName = (s) => s.written === null ? s.label : s.label + " " + s.written;

/** The target of {@code scope} that {@code key} names: by its alias first, then by its name; null when none does. */
```

In `PAGE/editor-core.js`, replace:
```js
      }
      let next = end + 1;
```
with:
```js
      }
      // a qualified name that is not found is never checked, as JDQL's
      if (entry === null && !cte && at === end && v.targets.size) last.problem = "unknown target " + keys[0];
      let next = end + 1;
```

In `PAGE/editor-core.js`, replace:
```js
  });
  // Every other name heads a path, from an alias or a table of the scope, or from a column of its targets.
  const isValue = valueTest(text, tokens);
  tokens.forEach((head, k) => {
```
with:
```js
  });
  // Every other name heads a path, from an alias or a table of the scope, or from a column of its targets. A bare
  // name that no target has is an error only when every target in scope is known with its columns.
  const isValue = valueTest(text, tokens);
  const known = scope.length > 0 && scope.every((s) => s.entry !== null && s.entry.attributes.size > 0);
  tokens.forEach((head, k) => {
```

In `PAGE/editor-core.js`, replace:
```js
        head.attribute = lookup(v, owners[0].entry.attributes, key(head), head.quoted === true);
      } else {
        head.kind = !dotted && scoped(v, scope, key(head)) !== null ? "target" : "identifier";
      }
```
with:
```js
        head.attribute = lookup(v, owners[0].entry.attributes, key(head), head.quoted === true);
        if (owners.length > 1) {
          head.warning = nameOf(text, head, v) + " is in " + sentence(owners.map(scopeName), "and");
        }
      } else if (!dotted && scoped(v, scope, key(head)) !== null) {
        head.kind = "target";
      } else {
        head.kind = "identifier";
        if (dotted && v.targets.size) {
          head.problem = "unknown table or alias " + nameOf(text, head, v);
        } else if (!dotted && known && !outputs.some((o) => sameName(v, o, key(head)))) {
          head.problem = "unknown column " + nameOf(text, head, v) + " of " + sentence(scope.map(scopeName), "or");
        }
      }
```

In `PAGE/editor-core.js`, replace:
```js
      if (t !== head || owner === null) {
        entry = t.attribute !== undefined && t.attribute.target !== null
```
with:
```js
      if (t !== head || owner === null) {
        if (t.attribute !== undefined && t.attribute.target === null) {
          (more ? after : dot).problem = nameOf(text, t, v) + " is not a reference";
        }
        entry = t.attribute !== undefined && t.attribute.target !== null
```

In `PAGE/editor-core.js`, replace:
```js
      t.kind = attribute !== null ? "attribute" : "identifier";
      if (attribute !== null) t.attribute = attribute;
    }
```
with:
```js
      t.kind = attribute !== null ? "attribute" : "identifier";
      if (attribute !== null) {
        t.attribute = attribute;
      } else if (entry !== null && entry.attributes.size) {
        t.problem = "unknown column " + nameOf(text, t, v) + " of " + entry.name;
      }
    }
```

In `PAGE/editor-core.js`, replace:
```js
 * parenthesis never closed or closing none. Without a vocabulary, or a known target, no attribute is checked; the
 * grammar never is: the server judges it when the query runs.
 */
```
with:
```js
 * parenthesis never closed or closing none. Without a vocabulary, or a known target, no attribute is checked; the
 * grammar never is: the server judges it when the query runs. SQL adds (SQL spec §3) an unknown table or alias before
 * a dot, an unterminated quoted name or comment, and a warning for a column two targets in scope have, named alone.
 */
```

In `PAGE/editor-core.js`, replace:
```js
  const open = [];
  for (const t of read(text, data).tokens) {
    if (t.problem !== undefined) error(t, t.problem);
    if (t.kind === "string" && !isClosed(text, t)) error(t, "unterminated string");
    if (t.kind === "punct" && text[t.from] === "(") open.push(t);
```
with:
```js
  const open = [];
  const { tokens, comments } = read(text, data);
  for (const c of comments) if (c.open) error(c, "unterminated comment");
  for (const t of tokens) {
    if (t.problem !== undefined) error(t, t.problem);
    if (t.warning !== undefined) out.push({ from: t.from, to: t.to, severity: "warning", message: t.warning });
    if (t.kind === "string" && !isClosed(text, t)) error(t, "unterminated string");
    if (t.quoted && !isClosed(text, t)) error(t, "unterminated identifier");
    if (t.kind === "punct" && text[t.from] === "(") open.push(t);
```

- [ ] **Step 4: Run the tests to verify they pass**

Run the command of Task 5's Step 4.
Expected: `Tests run: 20` (`SqlLanguageTest`) and `Tests run: 37` (`QueryLanguageTest`), each `Failures: 0, Errors:
0`, then `Tests run: 57, Failures: 0, Errors: 0`. `node --check` of `editor-core.js` prints nothing.

- [ ] **Step 5: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(devconsole): check SQL's tables, aliases and columns as it is typed

An unknown table, an unknown column of a table or an alias, a bare column that no table in scope has (when every
one is known), an unknown table or alias before a dot, a path through a column that is no reference, an
unterminated quoted name or comment are errors; a column that two tables in scope have, named alone, is a warning,
"title is in tasks t and projects p". A table written with its schema is looked up as written, then without it, and
left unchecked when neither is known; a sub-query, a WITH's table and an alias of the select list never are.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/editor-core.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/SqlLanguageTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 7: SQL's formatting — a join with its ON, comments, casts, sub-queries (§3)

**Files:**
- Modify: `PAGE/editor-core.js` (`JOIN_WORDS`, `formatQuery`)
- Modify: `DCT/SqlLanguageTest.java` (a formatting section)

**Interfaces:**
- Consumes: Task 4's `read` (`comments`, token `depth`, `quoted`), `clauseAt`, `lineOf`, `INDENT`.
- Produces: `formatQuery` as before for JDQL; for SQL the rules of Ruling 11, and the refusals
  `line N: unterminated identifier`, `line N: unterminated comment`.

- [ ] **Step 1: Write the failing tests**

In `DCT/SqlLanguageTest.java`, replace:
```java
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
```
with:
```java
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
```

In `DCT/SqlLanguageTest.java`, replace:
```java
import static org.junit.jupiter.api.Assertions.assertNotNull;
```
with:
```java
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
```

In `DCT/SqlLanguageTest.java`, replace:
```java
    // ------------------------------------------------------------------------------------------------ keystrokes
```
with:
```java
    // ------------------------------------------------------------------------------------------------ formatting

    private static String format(String text) {
        return language.invokeMember("format", text, sql).asString();
    }

    @Test
    void formatPutsEachClauseOnALineOfItsOwnAJoinWithItsOn() {
        assertEquals("""
                SELECT t.title, p.lead
                FROM tasks t
                LEFT JOIN projects p ON p.id = t.project_id
                  AND p.lead LIKE 'a%'
                JOIN "Order" o ON o.id = t.id
                WHERE t.price > :min
                GROUP BY t.title, p.lead
                HAVING COUNT(*) > 1
                ORDER BY t.title
                LIMIT 10
                OFFSET 20""", format("select t.title, p.lead from tasks t left join projects p on p.id = t.project_id "
                + "and p.lead like 'a%' join \"Order\" o on o.id = t.id where t.price > :min group by t.title, p.lead "
                + "having count(*) > 1 order by t.title limit 10 offset 20"));
        assertEquals("INSERT INTO tasks (title, price)\nVALUES ('a', 1.50)",
                format("insert into tasks(title,price) values('a',1.50)"));
    }

    @Test
    void formatCopiesQuotedNamesCastsAndCommentsAndLeavesASubQueryOnItsLine() {
        assertEquals("""
                SELECT "due date"::text -- the date
                FROM "Order" o /* every one */
                WHERE o.id IN (SELECT id FROM tasks WHERE price > 1)""",
                format("select \"due date\" :: text -- the date\nfrom \"Order\" o /* every one */ where o.id in "
                        + "(select id from tasks where price>1)"));
        assertEquals("SELECT 1 -- one\n, 2", format("select 1 -- one\n, 2"), "after a -- comment, a new line");
    }

    @Test
    void formattingAFormattedSqlQueryChangesNothing() {
        String once = format("select t.title from tasks t inner join projects p on p.id = t.project_id where "
                + "t.title = 'a' or t.price between 1 and 2");

        assertEquals("SELECT t.title\nFROM tasks t\nINNER JOIN projects p ON p.id = t.project_id\n"
                + "WHERE t.title = 'a'\n  OR t.price BETWEEN 1 AND 2", once);
        assertEquals(once, format(once));
    }

    @Test
    void anUnterminatedQuotedNameOrCommentIsNotFormattedAndSaysWhere() {
        PolyglotException name = assertThrows(PolyglotException.class, () -> format("SELECT 1\nFROM \"tasks"));
        assertEquals("Error: line 2: unterminated identifier", name.getMessage());
        PolyglotException comment = assertThrows(PolyglotException.class, () -> format("SELECT 1 /* x"));
        assertEquals("Error: line 1: unterminated comment", comment.getMessage());
    }

    // ------------------------------------------------------------------------------------------------ keystrokes
```

- [ ] **Step 2: Run the tests to verify they fail**

Run the command of Task 5's Step 2.
Expected: FAIL — `Tests run: 24, Failures: 4, Errors: 0`: the four formatting tests.

- [ ] **Step 3: Write SQL's formatting**

In `PAGE/editor-core.js`, replace:
```js
// ------------------------------------------------------------------------------------------------ query: formatting

/**
 * {@code text} with its keywords and functions in capitals (self as the dialect writes it), each clause on a line of
```
with:
```js
// ------------------------------------------------------------------------------------------------ query: formatting

/** The words that may come before SQL's JOIN, the first of which starts the join's line. */
const JOIN_WORDS = new Set(["LEFT", "RIGHT", "INNER", "OUTER", "FULL", "CROSS", "NATURAL"]);

/**
 * {@code text} with its keywords and functions in capitals (self as the dialect writes it), each clause on a line of
```

In `PAGE/editor-core.js`, replace:
```js
 * tokens but around a dot, inside parentheses, before a comma and after a sign; strings, numbers, parameters and
 * names copied as written. Throws, saying where, on an unterminated string.
 */
function formatQuery(text, data) {
  const { v, tokens } = read(text, data);
  const open = tokens.find((t) => t.kind === "string" && !isClosed(text, t));
  if (open !== undefined) throw new Error("line " + lineOf(text, open.from) + ": unterminated string");
  let out = "";
```
with:
```js
 * tokens but around a dot, inside parentheses, before a comma and after a sign; strings, numbers, parameters and
 * names copied as written. In SQL (SQL spec §3) only the outer level's clauses start a line, a join's line starts
 * with its LEFT, INNER… and holds its ON, a cast's :: takes no space, quoted names and comments are copied as
 * written, and what follows a -- comment starts a line. Throws, saying where, on an unterminated string, quoted name
 * or comment.
 */
function formatQuery(text, data) {
  const { v, tokens, comments } = read(text, data);
  const open = tokens.find((t) => t.kind === "string" && !isClosed(text, t));
  if (open !== undefined) throw new Error("line " + lineOf(text, open.from) + ": unterminated string");
  const name = tokens.find((t) => t.quoted && !isClosed(text, t));
  if (name !== undefined) throw new Error("line " + lineOf(text, name.from) + ": unterminated identifier");
  const comment = comments.find((c) => c.open);
  if (comment !== undefined) throw new Error("line " + lineOf(text, comment.from) + ": unterminated comment");
  const stream = comments.length ? [...tokens, ...comments].sort((a, b) => a.from - b.from) : tokens;
  const joinAt = (k) => {
    for (let i = k; stream[i] !== undefined && stream[i].kind === "keyword"; i++) {
      if (stream[i].word === "JOIN") return true;
      if (!JOIN_WORDS.has(stream[i].word)) return false;
    }
    return false;
  };
  let out = "";
```

In `PAGE/editor-core.js`, replace:
```js
  let call = false;           // the previous token is a function: its "(" follows it
  for (let k = 0; k < tokens.length; k++) {
    const t = tokens[k];
    const c = t.kind === "punct" || t.kind === "operator" ? text.slice(t.from, t.to) : null;
    const keyword = t.kind === "keyword" ? t.word : null;
    const clause = keyword !== null ? clauseAt(v, tokens, k) : null;
    let piece = t.self ? v.self : keyword !== null || t.kind === "function" ? t.word : text.slice(t.from, t.to);
    let separator = glue || c === "," || c === ")" || c === "." || (c === "(" && call) ? "" : " ";
    if (clause !== null) {
      piece = clause.join(" ");
      k += clause.length - 1;
      separator = "\n";
      between = false;
    } else if (keyword === "OR" || (keyword === "AND" && !between)) {
```
with:
```js
  let call = false;           // the previous token is a function: its "(" follows it
  let joining = false;        // a LEFT, INNER… started a line that waits for its JOIN
  let afterLine = false;      // the previous piece is a -- comment: the next one starts a line
  for (let k = 0; k < stream.length; k++) {
    const t = stream[k];
    if (t.kind === "comment") {
      out += (out === "" ? "" : afterLine ? "\n" : " ") + text.slice(t.from, t.to);
      afterLine = text[t.from] === "-";
      glue = false;
      call = false;
      continue;
    }
    const c = t.kind === "punct" || t.kind === "operator" ? text.slice(t.from, t.to) : null;
    const keyword = t.kind === "keyword" ? t.word : null;
    const clause = keyword !== null && (!v.aliases || t.depth === 0) ? clauseAt(v, stream, k) : null;
    let piece = t.self ? v.self : keyword !== null || t.kind === "function" ? t.word : text.slice(t.from, t.to);
    let separator = glue || c === "," || c === ")" || c === "." || c === "::" || (c === "(" && call) ? "" : " ";
    if (clause !== null) {
      piece = clause.join(" ");
      k += clause.length - 1;
      separator = joining || (v.aliases && piece === "ON") ? " " : "\n";
      between = false;
      joining = false;
    } else if (v.aliases && keyword !== null && JOIN_WORDS.has(keyword) && !joining && t.depth === 0 && joinAt(k)) {
      separator = "\n";
      joining = true;
    } else if (keyword === "OR" || (keyword === "AND" && !between)) {
```

In `PAGE/editor-core.js`, replace:
```js
    }
    const sign = (c === "-" || c === "+") && (out === "" || ["operator", "keyword"].includes(tokens[k - 1].kind)
      || (tokens[k - 1].kind === "punct" && "(,".includes(text[tokens[k - 1].from])));
    glue = c === "(" || c === "." || sign;
    call = t.kind === "function";
```
with:
```js
    }
    if (afterLine && !separator.startsWith("\n")) separator = "\n";
    afterLine = false;
    const sign = (c === "-" || c === "+") && (out === "" || ["operator", "keyword"].includes(stream[k - 1].kind)
      || (stream[k - 1].kind === "punct" && "(,".includes(text[stream[k - 1].from])));
    glue = c === "(" || c === "." || c === "::" || sign;
    call = t.kind === "function";
```

- [ ] **Step 4: Run the tests to verify they pass, and the console's whole build**

Run the command of Task 5's Step 4. Expected: `Tests run: 24` (`SqlLanguageTest`) and `Tests run: 37`
(`QueryLanguageTest`), then `Tests run: 61, Failures: 0, Errors: 0`. Then:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension install 2>&1 | grep -E "Tests run:|FAIL|BUILD" | grep -v " in io" | tail -3
```
Expected: `Tests run: 502, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`. `node --check` of
`editor-core.js` prints nothing.

- [ ] **Step 5: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(devconsole): format SQL, a join with its ON on one line

In SQL, Format starts each clause of the outer level on a line of its own, a join's line with its LEFT, INNER...
and its ON on it, AND and OR indented; :: takes no space; quoted names, strings and comments are copied as written,
what follows a -- comment starting a line; a sub-query stays on its line. An unterminated quoted name or comment is
not formatted and the line under the editor says where. JDQL is formatted as before.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/editor-core.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/SqlLanguageTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---
### Task 8: Running SQL on a pool — one statement, its transaction, its parameters, its values (§4.2)

**Files:**
- Modify: `PD/src/main/java/module-info.java` (`requires java.sql`)
- Create: `PDM/Json.java` (Mansart Data's reader and writer, its own copy: the two `-dev` modules share no code),
  `PDM/SqlText.java`, `PDM/SqlValues.java`, `PDM/SqlRunner.java`
- Create: `PDT/TestPools.java`, `PDT/SqlTextTest.java`, `PDT/SqlRunnerTest.java`

**Interfaces:**
- Consumes: Task 1's `ActionResult.rows`, `ActionResult.Column`, `ActionResult.TEXT`; `MansartPoolExtension`
  (`configure`, `beforeStart`, `onStop`), `MansartPoolsLive.pools()` → `Pool(label, MansartDataSource pool)`,
  `MansartDataSource` (a `javax.sql.DataSource`, `config()` → `PoolConfig(jdbcUrl, username, password, …)`,
  `snapshot().active()`).
- Produces: `Json.parse(String)`, `Json.write(Object)`; `SqlText.READS`, `SqlText.firstWord(String sql, char
  identifierQuote)`, `SqlText.severalStatements(String, char)`, `SqlText.named(String, char)` →
  `record Named(String sql, List<String> names)`; `SqlValues.of(ResultSet, int column, int type, String typeName)`;
  `SqlRunner(DataSource pool, char identifierQuote, int timeoutSeconds, String url, String user, String password)`
  with `ActionResult query(String sql, Map<String, Object> params)`, `ActionResult execute(String sql, Map<String,
  Object> params, boolean commit)`, `ActionResult preview(String sql, int limit)`, `String masked(String text)`,
  `static String count(long n, boolean more)`, and the constants `MAX_ROWS = 100`, `TIMEOUT_SECONDS = 30`,
  `ROLLBACK = "rollback"`, `COMMIT = "commit"`; test fixture `TestPools.boot(String...)`, `TestPools.withTables()`,
  `TestPools.h2(String)`, `TestPools.SCHEMA`, `pool(label)`, `run(label, statements…)`, `close()`.

- [ ] **Step 1: Write the failing tests**

Create `PDT/TestPools.java`:

```java
/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev;

import io.vidocq.mansart.pool.core.MansartDataSource;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.MansartPoolExtension;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.live.MansartPoolsLive;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Mansart pool extension booted on in-memory H2 databases, as Vidocq boots it before the container starts: its
 * pools open and published to {@link MansartPoolsLive}. H2 is told to store unquoted names in lower case, as
 * PostgreSQL does. {@link #close} stops the extension, which clears the holder.
 */
final class TestPools implements AutoCloseable {

    /** The tables of the SQL spec's tests: tasks referring to projects, a view, and a table of another schema. */
    static final String[] SCHEMA = {
        "CREATE TABLE projects (id BIGINT PRIMARY KEY, name VARCHAR(80) NOT NULL)",
        "CREATE TABLE tasks (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, title VARCHAR(200) NOT NULL, "
                + "price NUMERIC(10,2), due DATE, done BOOLEAN DEFAULT FALSE NOT NULL, "
                + "project_id BIGINT REFERENCES projects (id))",
        "CREATE INDEX idx_tasks_title ON tasks (title)",
        "CREATE VIEW open_tasks AS SELECT id, title FROM tasks WHERE NOT done",
        "CREATE SCHEMA sales",
        "CREATE TABLE sales.orders (id INT PRIMARY KEY, \"Total\" NUMERIC(38,0))",
        "INSERT INTO projects VALUES (1, 'vidocq'), (2, 'mansart')",
        "INSERT INTO tasks (title, price, due, project_id) VALUES ('first', 12.50, DATE '2026-10-01', 1), "
                + "('second', NULL, NULL, 2), ('third', 3, DATE '2026-10-03', 1)"};

    private final MansartPoolExtension extension = new MansartPoolExtension();

    private TestPools() {}

    /** An in-memory H2 database of its own, kept while the JVM runs, its unquoted names in lower case. */
    static String h2(String name) {
        return "jdbc:h2:mem:sql-" + name + "-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
    }

    /** Configures the extension with these keys and values and opens its pools. */
    static TestPools boot(String... keysAndValues) {
        Map<String, String> data = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            data.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        TestPools pools = new TestPools();
        pools.extension.configure(new VidocqConfiguration() {
            @Override
            public Optional<String> property(String key) {
                return Optional.ofNullable(data.get(key));
            }

            @Override
            public Iterable<String> propertyNames() {
                return data.keySet();
            }
        });
        pools.extension.beforeStart(new VaubanContainerBuilder());
        return pools;
    }

    /** One {@code @Default} pool of the SQL spec's tables. */
    static TestPools withTables() {
        TestPools pools = boot("vidocq.pool.url", h2("default"), "vidocq.pool.maxSize", "4");
        pools.run("@Default", SCHEMA);
        return pools;
    }

    /** The open pool labelled {@code label}, read from the holder the panel reads. */
    MansartDataSource pool(String label) {
        return MansartPoolsLive.pools().stream().filter(p -> p.label().equals(label)).findFirst().orElseThrow()
                .pool();
    }

    /** Runs {@code statements} on pool {@code label}, committed. */
    void run(String label, String... statements) {
        try (Connection connection = pool(label).getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        } catch (SQLException failed) {
            throw new IllegalStateException(failed);
        }
    }

    @Override
    public void close() {
        extension.onStop();
    }
}
```

Create `PDT/SqlTextTest.java`:

```java
/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the pools panel reads of a SQL text before it runs it (SQL spec §4.2), never in a string or a comment. */
class SqlTextTest {

    @Test
    void theFirstWordSkipsBlanksCommentsAndOpeningParentheses() {
        assertEquals("SELECT", SqlText.firstWord("  select 1", '"'));
        assertEquals("DELETE", SqlText.firstWord("/* SELECT */ -- SELECT\n delete from tasks", '"'),
                "a write hidden after comments");
        assertEquals("SELECT", SqlText.firstWord("(SELECT 1) UNION (SELECT 2)", '"'));
        assertEquals("WITH", SqlText.firstWord("WITH x AS (DELETE FROM tasks RETURNING *) SELECT * FROM x", '"'));
        assertEquals("", SqlText.firstWord("'SELECT'", '"'), "a string is no word");
        assertEquals("", SqlText.firstWord("-- only a comment", '"'));
    }

    @Test
    void aSemicolonInAStringAnIdentifierOrACommentOrAtTheEndIsNoSecondStatement() {
        assertFalse(SqlText.severalStatements("SELECT ';' AS \"a;b\" -- ;\n FROM t /* ; */;  -- done", '"'));
        assertFalse(SqlText.severalStatements("SELECT 'it''s; fine'", '"'), "a doubled quote stays in the string");
        assertTrue(SqlText.severalStatements("SELECT 1; DELETE FROM tasks", '"'));
        assertTrue(SqlText.severalStatements("SELECT 1;;", '"'));
        assertTrue(SqlText.severalStatements("SELECT `a;` FROM t; DROP TABLE t", '`'), "MySQL's quote");
        assertFalse(SqlText.severalStatements("SELECT `a;` FROM t", '`'));
    }

    @Test
    void aNamedParameterBecomesAQuestionMarkInTheCodeOnly() {
        SqlText.Named named = SqlText.named("SELECT :a, ':b', \"c:d\", x::text, :_e1 -- :f\n/* :g */ WHERE y = :a",
                '"');

        assertEquals("SELECT ?, ':b', \"c:d\", x::text, ? -- :f\n/* :g */ WHERE y = ?", named.sql());
        assertEquals(List.of("a", "_e1", "a"), named.names(), "one name per question mark, in order");
        assertEquals(List.of(), SqlText.named("SELECT 1 :: int, :1", '"').names(), "no cast, no number is a name");
    }
}
```

Create `PDT/SqlRunnerTest.java`:

```java
/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev;

import io.vidocq.mansart.pool.core.MansartDataSource;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL run on a pool (SQL spec §4.2), on H2 through the real Mansart pool: what Query and Execute answer and refuse,
 * how their parameters are bound, their transactions, their timeout, and what an SQLException shows.
 */
class SqlRunnerTest {

    private final TestPools pools = TestPools.withTables();
    private final MansartDataSource pool = pools.pool("@Default");
    private final SqlRunner runner = runner(SqlRunner.TIMEOUT_SECONDS);

    @AfterEach
    void stop() {
        pools.close();
    }

    private SqlRunner runner(int timeoutSeconds) {
        return new SqlRunner(pool, '"', timeoutSeconds, pool.config().jdbcUrl(), pool.config().username(),
                pool.config().password());
    }

    private static Map<String, Object> params(Object... namesAndValues) {
        Map<String, Object> params = new HashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            params.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return params;
    }

    /** The rows of a result's body, the text between "rows": and "more". */
    private static String rows(ActionResult result) {
        String body = result.body();
        return body.substring(body.indexOf("\"rows\":") + 7, body.lastIndexOf(",\"more\":"));
    }

    private String title(long id) {
        return rows(runner.query("SELECT title FROM tasks WHERE id = " + id, Map.of()));
    }

    @Test
    void aQueryAnswersItsRowsAsATable() {
        ActionResult result = runner.query("SELECT id, title, price, due FROM tasks ORDER BY id", Map.of());

        assertFalse(result.error(), result.summary());
        assertEquals(ActionResult.ROWS, result.contentType());
        assertTrue(result.summary().matches("3 rows in \\d+ ms"), result.summary());
        assertEquals("{\"columns\":[{\"name\":\"id\",\"type\":\"BIGINT\"},{\"name\":\"title\",\"type\":"
                + "\"CHARACTER VARYING\"},{\"name\":\"price\",\"type\":\"NUMERIC\"},{\"name\":\"due\",\"type\":"
                + "\"DATE\"}],\"rows\":[[1,\"first\",\"12.50\",\"2026-10-01\"],[2,\"second\",null,null],"
                + "[3,\"third\",\"3.00\",\"2026-10-03\"]],\"more\":false}", result.body());
        assertEquals("{\"sql\":\"SELECT id, title, price, due FROM tasks ORDER BY id\",\"params\":{},"
                + "\"transaction\":\"read-only\"}", result.details());
    }

    @Test
    void aQueryShowsAHundredRowsTheNextOneSayingThereAreMore() {
        ActionResult result = runner.query("SELECT * FROM SYSTEM_RANGE(1, 150)", Map.of());

        assertTrue(result.summary().matches("first 100 rows in \\d+ ms"), result.summary());
        assertTrue(result.body().endsWith("[99],[100]],\"more\":true}"), result.body());
    }

    @Test
    void aQueryRefusesAWriteAndASecondStatementBeforeAnythingRuns() {
        for (String write : new String[] {"DELETE FROM tasks", "/* SELECT */ -- SELECT\n DELETE FROM tasks",
                "UPDATE tasks SET title = 'x'"}) {
            ActionResult refused = runner.query(write, Map.of());
            assertTrue(refused.error(), write);
            assertEquals("Query only reads: use Execute", refused.summary(), write);
            assertNull(refused.body());
        }
        assertEquals("one statement at a time", runner.query("SELECT 1; DELETE FROM tasks", Map.of()).summary());
        assertEquals("one statement at a time",
                runner.execute("UPDATE tasks SET title = 'x'; DELETE FROM tasks", Map.of(), true).summary());
        assertEquals("[[\";\"]]", rows(runner.query("SELECT ';' AS semicolon;", Map.of())), "a last ; is fine");
        assertEquals("[[3]]", rows(runner.query("SELECT COUNT(*) FROM tasks", Map.of())), "nothing ran");
    }

    @Test
    void aNamedParameterIsBoundWhereItIsAndAMissingOneIsRefused() {
        assertEquals("[[\"second\"]]", rows(runner.query("SELECT title FROM tasks WHERE id = :id "
                + "AND title <> ':id' AND \"title\" IS NOT NULL -- :nope", params("id", new BigDecimal("2")))));
        assertEquals("[[\"first\"],[\"third\"]]", rows(runner.query("SELECT title FROM tasks WHERE id = ANY(:ids) "
                + "ORDER BY id", params("ids", List.of(new BigDecimal("1"), new BigDecimal("3"))))), "an array");
        assertEquals("[[\"second\"]]", rows(runner.query("SELECT title FROM tasks WHERE price IS NOT DISTINCT FROM :p "
                + "AND done = :done", params("p", null, "done", false))), "null and a boolean");

        ActionResult missing = runner.query("SELECT title FROM tasks WHERE id = :id OR title = :title",
                params("id", new BigDecimal("2")));
        assertTrue(missing.error());
        assertEquals("missing parameter title", missing.summary());
    }

    @Test
    void anExecuteIsRolledBackUnlessItIsCommittedAsReadingTheTableAgainShows() {
        ActionResult rolledBack = runner.execute("UPDATE tasks SET title = :t WHERE id = 1", params("t", "changed"),
                false);
        assertEquals("1 row · rolled back", rolledBack.summary());
        assertNull(rolledBack.body(), "a count, no rows");
        assertTrue(rolledBack.details().endsWith("\"transaction\":\"rollback\"}"), rolledBack.details());
        assertEquals("[[\"first\"]]", title(1));

        assertEquals("1 row · committed",
                runner.execute("UPDATE tasks SET title = :t WHERE id = 1", params("t", "changed"), true).summary());
        assertEquals("[[\"changed\"]]", title(1));
        assertEquals("no row · committed", runner.execute("DELETE FROM tasks WHERE id = 99", Map.of(), true)
                .summary());

        ActionResult rows = runner.execute("SELECT COUNT(*) AS n FROM tasks", Map.of(), false);
        assertEquals(ActionResult.ROWS, rows.contentType(), "a statement that answers rows");
        assertTrue(rows.summary().matches("1 row in \\d+ ms · rolled back"), rows.summary());
    }

    @Test
    void theConnectionGoesBackToThePoolAsItWasWhateverTheCallDid() throws SQLException {
        runner.query("SELECT * FROM tasks", Map.of());
        runner.query("SELECT nope FROM tasks", Map.of());
        runner.execute("UPDATE tasks SET title = 'x'", Map.of(), false);
        runner.execute("INSERT INTO tasks (id, title) VALUES (1, 'duplicate')", Map.of(), true);

        assertEquals(0, pool.snapshot().active(), "every connection given back");
        try (Connection connection = pool.getConnection()) {
            assertTrue(connection.getAutoCommit());
            assertFalse(connection.isReadOnly());
        }
        assertEquals("[[\"first\"]]", title(1));
    }

    @Test
    void aStatementPastItsTimeoutIsCancelledAndSaysSo() {
        long start = System.nanoTime();

        ActionResult result = runner(1).query("SELECT COUNT(*) FROM SYSTEM_RANGE(1, 100000000000) "
                + "WHERE MOD(\"X\", 7) = 8", Map.of());

        assertTrue(result.error());
        assertEquals("the statement ran past 1 s and was cancelled", result.summary());
        assertTrue((System.nanoTime() - start) / 1_000_000_000 < 20, "it ended within its timeout");
        assertEquals(0, pool.snapshot().active());
    }

    @Test
    void anSqlExceptionIsTheDatabasesMessageWithItsStateAndCodeNeverTheUrlTheUserOrThePassword() {
        ActionResult unknown = runner.query("SELECT nope FROM tasks", Map.of());

        assertTrue(unknown.error());
        assertTrue(unknown.summary().contains("Column \"nope\" not found"), unknown.summary());
        assertEquals(ActionResult.TEXT, unknown.contentType());
        assertTrue(unknown.details().endsWith("\"sqlState\":\"42S22\",\"vendorCode\":42122}"), unknown.details());

        String url = "jdbc:h2:tcp://db.example/tasks;USER=tasks";
        DataSource failing = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, arguments) -> {
                    throw new SQLException("cannot open " + url + ": password authentication failed for user "
                            + "\"tasks\" with hunter22", "28P01", 0);
                });
        ActionResult refused = new SqlRunner(failing, '"', 30, url, "tasks", "hunter22").query("SELECT 1", Map.of());

        assertEquals("cannot open ***: password authentication failed for user \"***\" with ***", refused.summary());
        assertFalse(refused.body().contains("hunter22") || refused.body().contains("db.example"), refused.body());
        assertFalse(refused.details().contains("hunter22"), refused.details());
    }

    @Test
    void aValueJsonCannotHoldAsSuchIsWrittenAsText() {
        String sql = "SELECT CAST('12345678901234567890123456789012345678' AS NUMERIC(38)) AS big, "
                + "CAST(9007199254740993 AS BIGINT) AS long, X'0102' AS bin, "
                + "CAST(REPEAT('ab', 50) AS BINARY VARYING(100)) AS longer, "
                + "TIMESTAMP WITH TIME ZONE '2026-10-01 12:00:00+02:00' AS zoned, "
                + "TIMESTAMP '2026-10-01 12:00:30' AS local, ARRAY[1, 2] AS list, TRUE AS yes, NULL AS nothing, "
                + "CAST(1.5 AS DOUBLE PRECISION) AS ratio";

        String row = rows(runner.query(sql, Map.of()));

        List<String> values = Arrays.asList(row.substring(2, row.length() - 2).split(","));
        assertEquals("\"12345678901234567890123456789012345678\"", values.get(0), "a NUMERIC(38), whole");
        assertEquals("\"9007199254740993\"", values.get(1), "a long past 2^53");
        assertEquals("\"0x0102\"", values.get(2));
        assertTrue(values.get(3).matches("\"0x(6162){32}…\""), values.get(3));
        assertEquals("\"2026-10-01T12:00+02:00\"", values.get(4), "with its offset");
        assertEquals("\"2026-10-01T12:00:30\"", values.get(5));
        assertEquals("\"[1", values.get(6));
        assertEquals("2]\"", values.get(7), "an array as the JSON of its elements");
        assertEquals("true", values.get(8));
        assertEquals("null", values.get(9));
        assertEquals("1.5", values.get(10));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -q -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-pool-extension-dev test 2>&1 | grep -E "ERROR.*(cannot find symbol|symbol:)|BUILD" | head -6
```
Expected: FAIL at `testCompile` — `SqlRunnerTest.java:[50,19] cannot find symbol`, `symbol:   class SqlRunner` (the
lines name the files by their whole path).

- [ ] **Step 3: Write `SqlText`, `SqlValues`, `SqlRunner` and the module's `java.sql`**

In `PD/src/main/java/module-info.java`, replace:
```java
    requires io.vidocq.mansart.pool.core;
```
with:
```java
    requires io.vidocq.mansart.pool.core;
    requires java.sql;        // the SQL the panel runs on a pool, and the metadata of its tables
```

Create `PDM/Json.java`:

```java
/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The little JSON the pools panel needs, without a library (SQL spec §4), as Mansart Data's panel has its own:
 * {@link #parse} reads a statement argument into a tree of {@link Map} (members in order), {@link List},
 * {@link String}, {@link BigDecimal} (its text kept), {@link Boolean} and {@code null}; {@link #write} writes such a
 * tree, compact: a language, a replay, the details of a call. The console already checked that an argument is one
 * JSON object nesting at most 64 levels; the reader checks again, so that it never trusts its caller.
 */
final class Json {

    /** How deep a value may nest: the console's own limit. */
    static final int MAX_DEPTH = 64;

    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    /**
     * The tree of {@code text}.
     *
     * @throws IllegalArgumentException when it is not one JSON value, or nests deeper than {@value #MAX_DEPTH}
     */
    static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("no JSON");
        }
        Json reader = new Json(text);
        reader.blanks();
        Object value = reader.value(0);
        reader.blanks();
        if (reader.at != text.length()) {
            throw reader.error();
        }
        return value;
    }

    /**
     * {@code value} as compact JSON: the types of the class comment, any other number as its text, anything else
     * quoted.
     */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private Object value(int depth) {
        if (depth >= MAX_DEPTH) {
            throw new IllegalArgumentException("JSON nests deeper than " + MAX_DEPTH + " levels");
        }
        if (at >= text.length()) {
            throw error();
        }
        return switch (text.charAt(at)) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> members = new LinkedHashMap<>();
        at++;
        blanks();
        if (next('}')) {
            return members;
        }
        do {
            blanks();
            if (at >= text.length() || text.charAt(at) != '"') {
                throw error();
            }
            String name = string();
            blanks();
            expect(':');
            blanks();
            members.put(name, value(depth + 1));
            blanks();
        } while (next(','));
        expect('}');
        return members;
    }

    private List<Object> array(int depth) {
        List<Object> elements = new ArrayList<>();
        at++;
        blanks();
        if (next(']')) {
            return elements;
        }
        do {
            blanks();
            elements.add(value(depth + 1));
            blanks();
        } while (next(','));
        expect(']');
        return elements;
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= text.length()) {
                throw error();
            }
            char c = text.charAt(at++);
            if (c == '"') {
                return out.toString();
            }
            if (c < 0x20) {
                throw error();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (at >= text.length()) {
                throw error();
            }
            char escaped = text.charAt(at++);
            switch (escaped) {
                case '"', '\\', '/' -> out.append(escaped);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> out.append(unicode());
                default -> throw error();
            }
        }
    }

    private char unicode() {
        if (at + 4 > text.length()) {
            throw error();
        }
        int code = 0;
        for (int i = 0; i < 4; i++) {
            char digit = text.charAt(at + i);
            if (!HexFormat.isHexDigit(digit)) {
                throw error();
            }
            code = code * 16 + HexFormat.fromHexDigit(digit);
        }
        at += 4;
        return (char) code;
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, at)) {
            throw error();
        }
        at += word.length();
        return value;
    }

    private BigDecimal number() {
        int start = at;
        while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
        String number = text.substring(start, at);
        if (!NUMBER.matcher(number).matches()) {
            at = start;
            throw error();
        }
        try {
            return new BigDecimal(number);
        } catch (NumberFormatException exponentTooLarge) {
            at = start;
            throw error();
        }
    }

    private void blanks() {
        while (at < text.length() && " \t\n\r".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
    }

    private boolean next(char c) {
        if (at < text.length() && text.charAt(at) == c) {
            at++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!next(c)) {
            throw error();
        }
    }

    private IllegalArgumentException error() {
        return new IllegalArgumentException("not valid JSON at character " + (at + 1));
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case String text -> quote(text, out);
            case Boolean flag -> out.append(flag);
            case Double d when d.isNaN() || d.isInfinite() -> quote(d.toString(), out);
            case Float f when f.isNaN() || f.isInfinite() -> quote(f.toString(), out);
            case Number number -> out.append(number);
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> member : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    quote(String.valueOf(member.getKey()), out);
                    out.append(':');
                    write(member.getValue(), out);
                }
                out.append('}');
            }
            case Collection<?> elements -> {
                out.append('[');
                boolean first = true;
                for (Object element : elements) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    write(element, out);
                }
                out.append(']');
            }
            default -> quote(value.toString(), out);
        }
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
```

Create `PDM/SqlText.java`:

```java
/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the pools panel reads of a SQL text before it runs it (SQL spec §4.2), without parsing it: its first word,
 * whether it holds more than one statement, and its named parameters. Each is read in the text's code only, outside
 * strings ({@code '…'}, a doubled quote inside), quoted identifiers (between the database's identifier quotes, doubled
 * inside) and comments ({@code --} to the end of the line, and between {@code /*} and its end), which may run over
 * several lines; an unterminated one runs to the end of the text.
 */
final class SqlText {

    /** The first words of a statement that only reads: what Query runs. */
    static final Set<String> READS = Set.of("SELECT", "WITH", "VALUES", "SHOW", "EXPLAIN", "TABLE");

    /**
     * A text ready to run.
     *
     * @param sql   the text, each named parameter {@code :name} replaced by {@code ?}
     * @param names the parameters' names, one per {@code ?}, in the order of the text
     */
    record Named(String sql, List<String> names) {}

    private SqlText() {}

    /**
     * The first word of {@code sql} in capitals, blanks, comments and opening parentheses skipped; empty when the code
     * starts with no letter.
     */
    static String firstWord(String sql, char identifierQuote) {
        boolean[] code = code(sql, identifierQuote);
        int i = 0;
        while (i < sql.length() && (!code[i] || Character.isWhitespace(sql.charAt(i)) || sql.charAt(i) == '(')) {
            i++;
        }
        int end = i;
        while (end < sql.length() && code[end] && Character.isLetter(sql.charAt(end))) {
            end++;
        }
        return sql.substring(i, end).toUpperCase(Locale.ROOT);
    }

    /** Whether {@code sql} holds more than one statement: a {@code ;} of its code that is not the last of it. */
    static boolean severalStatements(String sql, char identifierQuote) {
        boolean[] code = code(sql, identifierQuote);
        int last = sql.length() - 1;
        while (last >= 0 && (!code[last] || Character.isWhitespace(sql.charAt(last)))) {
            last--;
        }
        for (int i = 0; i < last; i++) {
            if (code[i] && sql.charAt(i) == ';') {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code sql} with each named parameter of its code, {@code :name} (a letter or {@code _}, then letters, digits
     * and {@code _}), replaced by {@code ?}; a cast, {@code ::type}, is none.
     */
    static Named named(String sql, char identifierQuote) {
        boolean[] code = code(sql, identifierQuote);
        StringBuilder out = new StringBuilder(sql.length());
        List<String> names = new ArrayList<>();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            boolean parameter = code[i] && c == ':' && i + 1 < sql.length() && code[i + 1]
                    && (Character.isLetter(sql.charAt(i + 1)) || sql.charAt(i + 1) == '_')
                    && (i == 0 || sql.charAt(i - 1) != ':');
            if (!parameter) {
                out.append(c);
                i++;
                continue;
            }
            int end = i + 1;
            while (end < sql.length() && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_')) {
                end++;
            }
            names.add(sql.substring(i + 1, end));
            out.append('?');
            i = end;
        }
        return new Named(out.toString(), List.copyOf(names));
    }

    /** Which characters of {@code sql} are code: neither in a string, nor in a quoted identifier, nor in a comment. */
    private static boolean[] code(String sql, char identifierQuote) {
        int n = sql.length();
        boolean[] code = new boolean[n];
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : 0;
            if (c == '\'' || c == identifierQuote) {
                i++;
                while (i < n && (sql.charAt(i) != c || (i + 1 < n && sql.charAt(i + 1) == c))) {
                    i += sql.charAt(i) == c ? 2 : 1;
                }
                i++;
            } else if (c == '-' && next == '-') {
                while (i < n && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else {
                code[i++] = true;
            }
        }
        return code;
    }
}
```

Create `PDM/SqlValues.java`:

```java
/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * A value of a row as the body of a result of rows holds it (SQL spec §4.2): a number as a number, a
 * {@link BigDecimal} or an integer past 2^53 as its text, so that JSON never rounds it; a boolean; a string, cut past
 * {@value #MAX_TEXT} characters; a date, a time or a timestamp as ISO text, with its offset when it has a zone; binary
 * as {@code 0x…} hex of its first {@value #MAX_BYTES} bytes, {@code …} after when there are more; an array as the
 * JSON of its elements; anything else, a value of a type the driver invents included, as its {@code toString()}.
 */
final class SqlValues {

    /** The largest integer a JSON number holds exactly: 2^53. */
    static final long MAX_EXACT = 1L << 53;
    /** How many bytes of a binary value are shown. */
    static final int MAX_BYTES = 64;
    /** How many characters of a text are kept: the page shows 200, and the rest in the cell's title. */
    static final int MAX_TEXT = 10_000;

    private SqlValues() {}

    /** The value of {@code column} of the current row, {@code type} and {@code typeName} its SQL type. */
    static Object of(ResultSet results, int column, int type, String typeName) throws SQLException {
        Object value = results.getObject(column);
        return switch (value) {
            case null -> null;
            case Boolean flag -> flag;
            case Integer number -> number;
            case Short number -> number;
            case Byte number -> number;
            case Long number -> number >= -MAX_EXACT && number <= MAX_EXACT ? number : number.toString();
            case BigInteger number -> number.bitLength() <= 53 ? number : number.toString();
            case BigDecimal number -> number.toPlainString();
            case Double number -> number;
            case Float number -> number;
            case String text -> cut(text);
            case Character c -> c.toString();
            case java.sql.Timestamp timestamp -> zoned(type, typeName) ? offset(results, column, timestamp)
                    : timestamp.toLocalDateTime().toString();
            case java.sql.Date date -> date.toLocalDate().toString();
            case java.sql.Time time -> time.toLocalTime().toString();
            case TemporalAccessor temporal -> temporal.toString();
            case byte[] bytes -> hex(bytes, bytes.length);
            case Blob blob -> hex(blob.getBytes(1, (int) Math.min(blob.length(), MAX_BYTES)), blob.length());
            case Clob clob -> clob.length() > MAX_TEXT ? clob.getSubString(1, MAX_TEXT) + "…"
                    : clob.getSubString(1, (int) clob.length());
            case SQLXML xml -> cut(xml.getString());
            case Array array -> elements(array);
            default -> cut(value.toString());
        };
    }

    /** {@code text}, cut past {@value #MAX_TEXT} characters with {@code …}. */
    private static String cut(String text) {
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "…" : text;
    }

    /** Whether a timestamp column has a zone: its JDBC type says so, or its name does (PostgreSQL's timestamptz). */
    private static boolean zoned(int type, String typeName) {
        String name = typeName == null ? "" : typeName.toUpperCase(Locale.ROOT);
        return type == Types.TIMESTAMP_WITH_TIMEZONE || name.equals("TIMESTAMPTZ") || name.contains("WITH TIME ZONE");
    }

    /** A timestamp with a zone as ISO text with its offset, or as an instant when the driver gives no offset. */
    private static String offset(ResultSet results, int column, java.sql.Timestamp timestamp) {
        try {
            OffsetDateTime value = results.getObject(column, OffsetDateTime.class);
            return value == null ? timestamp.toInstant().toString() : value.toString();
        } catch (SQLException | RuntimeException noOffset) {
            return timestamp.toInstant().toString();
        }
    }

    /** {@code 0x} and the hex of {@code bytes}, at most {@value #MAX_BYTES} of {@code length}, {@code …} after. */
    private static String hex(byte[] bytes, long length) {
        int shown = Math.min(bytes.length, MAX_BYTES);
        return "0x" + HexFormat.of().formatHex(bytes, 0, shown) + (length > shown ? "…" : "");
    }

    /** The elements of an array as JSON text, each a number, a boolean, a string or {@code null}. */
    private static String elements(Array array) throws SQLException {
        Object raw = array.getArray();
        try {
            array.free();
        } catch (SQLException unsupported) {
            // a driver that keeps nothing to free
        }
        if (!(raw instanceof Object[] values)) {
            return cut(String.valueOf(raw));
        }
        List<Object> out = new ArrayList<>(values.length);
        for (Object element : values) {
            out.add(element == null || element instanceof Number || element instanceof Boolean
                    || element instanceof String ? element : element.toString());
        }
        return cut(Json.write(out));
    }
}
```

Create `PDM/SqlRunner.java`:

```java
/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev;

import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.Column;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Runs SQL on one pool for the pools panel (SQL spec §4.2). A call takes a connection from the pool, turns its
 * autoCommit off, runs one statement, commits only when asked and rolls back otherwise, always when the call throws,
 * then gives the connection back with its autoCommit and its read-only flag as they were. What only reads runs on a
 * read-only connection too. A statement is cancelled past its timeout. A named parameter {@code :name} is bound from
 * {@code params}: a string, a number, a boolean or {@code null} with {@code setObject}, a list as an array where the
 * driver makes one. A refusal comes before anything runs. An {@link SQLException} is an error result whose line is
 * the database's message, the pool's URL, password and user masked, and whose details give its SQLState and vendor
 * code.
 */
final class SqlRunner {

    /** How many rows a query shows; one more is read, to say that there are more. */
    static final int MAX_ROWS = 100;
    /** How long a statement may run, in seconds, before it is cancelled. */
    static final int TIMEOUT_SECONDS = 30;
    /** The transaction Execute takes by default: rolled back. */
    static final String ROLLBACK = "rollback";
    /** The transaction Execute takes when asked: committed. */
    static final String COMMIT = "commit";
    /** The longest name or type of a column a result shows. */
    private static final int MAX_NAME = ActionResult.Column.MAX_NAME;
    /** The SQLState of a statement cancelled, as PostgreSQL and H2 say it. */
    private static final String CANCELLED = "57014";

    private final DataSource pool;
    private final char identifierQuote;
    private final int timeoutSeconds;
    private final String url;
    private final String user;
    private final String password;

    /**
     * @param pool            the pool the calls take a connection from
     * @param identifierQuote the database's identifier quote, which {@link SqlText} reads
     * @param timeoutSeconds  how long a statement may run, {@value #TIMEOUT_SECONDS} outside tests
     * @param url             the pool's JDBC URL, masked in a message; never shown
     * @param user            its user, masked in a message after the word user; may be {@code null}
     * @param password        its password, masked in a message when it is four characters or more; may be
     *                        {@code null}
     */
    SqlRunner(DataSource pool, char identifierQuote, int timeoutSeconds, String url, String user, String password) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.identifierQuote = identifierQuote;
        this.timeoutSeconds = timeoutSeconds;
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /**
     * Runs a statement that only reads, whose first word is one of {@link SqlText#READS}, in a transaction always
     * rolled back: at most {@value #MAX_ROWS} rows, the next one setting {@code more}.
     */
    ActionResult query(String sql, Map<String, Object> params) {
        return run(sql, params, true, false, MAX_ROWS);
    }

    /**
     * Runs any one statement in a transaction rolled back unless {@code commit}: the rows it answers, as
     * {@link #query} shows them, or the number of rows it changed.
     */
    ActionResult execute(String sql, Map<String, Object> params, boolean commit) {
        return run(sql, params, false, commit, MAX_ROWS);
    }

    /** Runs {@code sql}, a {@code SELECT} the panel built, as a query: its first {@code limit} rows. */
    ActionResult preview(String sql, int limit) {
        return run(sql, Map.of(), true, false, limit);
    }

    /** What a call answers: the rows, or the number of rows changed; {@code columns} is {@code null} for a count. */
    private record Answer(List<Column> columns, List<List<Object>> rows, boolean more, int count) {}

    private ActionResult run(String sql, Map<String, Object> params, boolean reads, boolean commit, int limit) {
        long start = System.nanoTime();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sql", sql);
        details.put("params", params);
        details.put("transaction", reads ? "read-only" : commit ? COMMIT : ROLLBACK);
        String refusal = sql.isBlank() ? "sql: missing"
                : SqlText.severalStatements(sql, identifierQuote) ? "one statement at a time"
                : reads && !SqlText.READS.contains(SqlText.firstWord(sql, identifierQuote))
                ? "Query only reads: use Execute" : null;
        SqlText.Named named = SqlText.named(sql, identifierQuote);
        for (String name : named.names()) {
            if (refusal == null && !params.containsKey(name)) {
                refusal = "missing parameter " + name;
            }
        }
        if (refusal != null) {
            return new ActionResult(refusal, null, null, true, Json.write(details));
        }
        try (Connection connection = pool.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            boolean readOnly = connection.isReadOnly();
            try {
                if (reads) {
                    readOnly(connection, true);
                }
                connection.setAutoCommit(false);
                Answer answer = statement(connection, named, params, limit);
                if (commit) {
                    connection.commit();
                } else {
                    connection.rollback();
                }
                return result(answer, reads ? null : commit ? "committed" : "rolled back", start, details);
            } catch (SQLException | RuntimeException failed) {
                quietly(connection::rollback);
                throw failed;
            } finally {
                quietly(() -> connection.setAutoCommit(autoCommit));
                if (reads) {
                    readOnly(connection, readOnly);
                }
            }
        } catch (SQLException failed) {
            return failure(failed, details);
        }
    }

    private Answer statement(Connection connection, SqlText.Named named, Map<String, Object> params, int limit)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(named.sql())) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(limit + 1);
            bind(connection, statement, named.names(), params);
            if (!statement.execute()) {
                return new Answer(null, List.of(), false, statement.getUpdateCount());
            }
            try (ResultSet results = statement.getResultSet()) {
                return rows(results, limit);
            }
        }
    }

    /** The first {@code limit} rows of {@code results}, and whether it has more. */
    private static Answer rows(ResultSet results, int limit) throws SQLException {
        ResultSetMetaData meta = results.getMetaData();
        int width = meta.getColumnCount();
        List<Column> columns = new ArrayList<>(width);
        for (int i = 1; i <= width; i++) {
            columns.add(new Column(cut(meta.getColumnLabel(i)), cut(meta.getColumnTypeName(i))));
        }
        List<List<Object>> rows = new ArrayList<>();
        while (results.next()) {
            if (rows.size() == limit) {
                return new Answer(columns, rows, true, -1);
            }
            List<Object> row = new ArrayList<>(width);
            for (int i = 1; i <= width; i++) {
                row.add(SqlValues.of(results, i, meta.getColumnType(i), meta.getColumnTypeName(i)));
            }
            rows.add(row);
        }
        return new Answer(columns, rows, false, -1);
    }

    private static ActionResult result(Answer answer, String state, long start, Map<String, Object> details) {
        String after = state == null ? "" : " · " + state;
        if (answer.columns() == null) {
            return new ActionResult(count(answer.count(), false) + after, null, null, false, Json.write(details));
        }
        ActionResult rows = ActionResult.rows(count(answer.rows().size(), answer.more()) + " in "
                + (System.nanoTime() - start) / 1_000_000 + " ms" + after, answer.columns(), answer.rows(),
                answer.more());
        return new ActionResult(rows.summary(), rows.contentType(), rows.body(), false, Json.write(details));
    }

    /** {@code no row}, {@code 1 row}, {@code N rows}, or {@code first N rows} when there are more. */
    static String count(long n, boolean more) {
        return more ? "first " + n + " rows" : n == 0 ? "no row" : n == 1 ? "1 row" : n + " rows";
    }

    /** Binds each {@code ?} of the statement to the parameter of that name. */
    private static void bind(Connection connection, PreparedStatement statement, List<String> names,
                             Map<String, Object> params) throws SQLException {
        for (int i = 0; i < names.size(); i++) {
            Object value = params.get(names.get(i));
            if (value instanceof List<?> list) {
                Object[] elements = list.stream().map(SqlRunner::scalar).toArray();
                try {
                    statement.setArray(i + 1, connection.createArrayOf(arrayType(elements), elements));
                } catch (SQLFeatureNotSupportedException noArray) {
                    statement.setObject(i + 1, elements);
                }
            } else {
                statement.setObject(i + 1, scalar(value));
            }
        }
    }

    /** A JSON value as it is bound: an integer that a long holds as a {@code Long}, any other number as it is. */
    private static Object scalar(Object value) {
        if (value instanceof BigDecimal number) {
            try {
                return number.longValueExact();
            } catch (ArithmeticException notALong) {
                return number;
            }
        }
        return value;
    }

    /** The SQL type of an array of these elements: BIGINT, NUMERIC, BOOLEAN, or VARCHAR for any other. */
    private static String arrayType(Object[] elements) {
        List<Object> values = Arrays.stream(elements).filter(Objects::nonNull).toList();
        if (!values.isEmpty() && values.stream().allMatch(Long.class::isInstance)) {
            return "BIGINT";
        }
        if (!values.isEmpty() && values.stream().allMatch(Number.class::isInstance)) {
            return "NUMERIC";
        }
        return !values.isEmpty() && values.stream().allMatch(Boolean.class::isInstance) ? "BOOLEAN" : "VARCHAR";
    }

    /** An error result: the database's message masked, or the cancellation of a statement past its timeout. */
    private ActionResult failure(SQLException failed, Map<String, Object> details) {
        boolean cancelled = failed instanceof SQLTimeoutException || CANCELLED.equals(failed.getSQLState());
        String message = cancelled ? "the statement ran past " + timeoutSeconds + " s and was cancelled"
                : masked(String.valueOf(failed.getMessage()));
        details.put("sqlState", failed.getSQLState());
        details.put("vendorCode", failed.getErrorCode());
        String line = message.replaceAll("\\s+", " ").strip();
        return new ActionResult(line.isEmpty() ? failed.getClass().getSimpleName() : line, ActionResult.TEXT, message,
                true, Json.write(details));
    }

    /** {@code text} with the pool's URL, its password and its user after the word user replaced by {@code ***}. */
    String masked(String text) {
        String out = text;
        if (url != null && !url.isBlank()) {
            out = out.replace(url, "***");
        }
        if (password != null && password.length() >= 4) {
            out = out.replace(password, "***");
        }
        if (user != null && !user.isBlank()) {
            out = Pattern.compile("(?i)(user\\s+\"?)" + Pattern.quote(user) + "(?![\\w])").matcher(out)
                    .replaceAll("$1***");
        }
        return out;
    }

    private static String cut(String name) {
        String text = name == null ? "" : name;
        return text.length() > MAX_NAME ? text.substring(0, MAX_NAME - 1) + "…" : text;
    }

    /** Sets the read-only flag of {@code connection}; a driver that refuses it is left as it is. */
    private static void readOnly(Connection connection, boolean on) {
        quietly(() -> connection.setReadOnly(on));
    }

    private interface Step {
        void run() throws SQLException;
    }

    private static void quietly(Step step) {
        try {
            step.run();
        } catch (SQLException | RuntimeException ignored) {
            // the connection goes back to the pool all the same, which validates it
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-pool-extension-dev test 2>&1 | grep -E "Tests run:|FAIL|BUILD" | tail -6
```
Expected: `SqlRunnerTest` 9, `SqlTextTest` 3, `PoolsLivePanelTest` 5, then `Tests run: 17, Failures: 0, Errors: 0,
Skipped: 0` and `BUILD SUCCESS` (the timeout test takes about a second).

- [ ] **Step 5: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(mansart-pool): run one SQL statement on a pool, in a transaction

SqlRunner takes a connection from the pool, turns autoCommit off, runs one statement within 30 seconds, commits only
when asked and rolls back otherwise, always on a failure, then gives the connection back as it was. Query runs what
only reads (SELECT, WITH, VALUES, SHOW, EXPLAIN, TABLE) on a read-only connection, always rolled back, 100 rows at
most. SqlText reads the first word, a second statement and the :name parameters outside strings, quoted identifiers
and comments; a parameter is bound from params, a list as an array. SqlValues writes a NUMERIC or a long past 2^53 as
text, a timestamp with its offset, binary as 0x hex. An SQLException is the database's message, the pool's URL,
password and user masked, its SQLState and vendor code under Exchange.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
P=vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-pool-extension-dev; M=$P/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/pool/dev; T=$P/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/pool/dev
git add $P/src/main/java/module-info.java $M/Json.java $M/SqlText.java $M/SqlValues.java $M/SqlRunner.java $T/TestPools.java $T/SqlTextTest.java $T/SqlRunnerTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 9: Each pool's tab — Tables, Describe, Preview, Query, Execute (§4.1)

**Files:**
- Modify: `PDM/SqlRunner.java` (`Reading`, `read`)
- Create: `PDM/PoolMetadata.java`, `PDM/PoolActions.java`
- Modify: `PDM/PoolsLivePanel.java` (`start`, `stop`, `actions()`, `pools()`)
- Create: `PDT/PoolActionsTest.java`

**Interfaces:**
- Consumes: Task 8's `SqlRunner`, `Json`, `TestPools`; `PanelAction(id, label, confirmation, arguments, call, group,
  description)`, `PanelAction.Argument` (`oneOf`, `matching`, `json`, the allowed-values constructor),
  `PanelAction.MAX_GROUP`, `PanelAction.MAX_VALUE_LENGTH`, `PanelSample.REPLAY_COLUMN`.
- Produces: `SqlRunner.read(Reading)` with `@FunctionalInterface interface Reading { ActionResult read(Connection)
  throws SQLException; }`; `record PoolMetadata(String catalog, String schema, char identifierQuote, String
  unquotedCase, List<String> keywords, List<Table> tables)` with `record Table(String schema, String name, String
  kind, List<Column> columns)` (`view()`), `record Column(String name, int jdbcType, String sqlType, String target)`,
  `String shown(Table)`, `String quoted(Table)`, `static PoolMetadata read(Connection, boolean references)`,
  `static String sqlType(String typeName, int jdbcType, int size, int digits)`; `PoolActions(String label, String
  key, SqlRunner runner, PoolMetadata metadata)` with `static List<PoolActions> of(List<MansartPoolsLive.Pool>)`,
  `static String key(String label, Set<String> used)`, `label()`, `key()`, `metadata()`, `languageId()` →
  `sql-<key>`, `actions()`, `confirmation()`, `schema()`, `tableNames()`, `static Map<String, Object> params(Object)`;
  the action ids `<key>.tables`, `<key>.describe`, `<key>.preview`, `<key>.query`, `<key>.execute`, the arguments
  `table`, `limit`, `statement` (`sql`, `params`), `transaction`.

- [ ] **Step 1: Write the failing tests**

Create `PDT/PoolActionsTest.java`:

```java
/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev;

import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tab of actions of each pool (SQL spec §4.1), on H2 through the real Mansart pool and the panel the console
 * reads: what each pool offers, Tables and its replays, Describe, Preview, and the statement Query and Execute take.
 */
class PoolActionsTest {

    private final PoolsLivePanel panel = new PoolsLivePanel();
    private TestPools pools;

    @AfterEach
    void stop() {
        if (pools != null) {
            pools.close();
        }
    }

    private void bootWithTables() {
        pools = TestPools.boot("vidocq.pool.url", TestPools.h2("default"), "vidocq.pool.reports.url",
                TestPools.h2("reports"));
        pools.run("@Default", TestPools.SCHEMA);
    }

    private PanelAction action(String id) {
        return panel.actions().stream().filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no action " + id));
    }

    private ActionResult call(String id, String... namesAndValues) {
        Map<String, String> given = new HashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            given.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return action(id).call().apply(given);
    }

    /** The rows of a result's body, the text between "rows": and "more". */
    private static String rows(ActionResult result) {
        String body = result.body();
        return body.substring(body.indexOf("\"rows\":") + 7, body.lastIndexOf(",\"more\":"));
    }

    @Test
    void eachPoolHasATabOfItsActionsTheDefaultOneFirst() {
        bootWithTables();

        List<PanelAction> actions = panel.actions();

        assertEquals(List.of("default.tables", "default.describe", "default.preview", "default.query",
                "default.execute", "reports.tables", "reports.query", "reports.execute"),
                actions.stream().map(PanelAction::id).toList(), "a pool with no table has no Describe, no Preview");
        assertEquals(List.of("@Default", "@Default", "@Default", "@Default", "@Default", "reports", "reports",
                "reports"), actions.stream().map(PanelAction::group).toList());
        assertEquals(List.of("Tables", "Describe", "Preview", "Query", "Execute"),
                actions.subList(0, 5).stream().map(PanelAction::label).toList());
        assertEquals(List.of("open_tasks", "projects", "tasks", "sales.orders"),
                action("default.describe").arguments().getFirst().allowedValues(), "schema then name order");
        assertEquals("Runs this SQL on @Default. A DDL statement (CREATE, ALTER, DROP, TRUNCATE…) may be committed by "
                + "the database itself whatever is chosen.", action("default.execute").confirmation());
        assertNull(action("default.query").confirmation(), "a query runs on the first click");
        assertEquals(List.of("rollback", "commit"), action("default.execute").arguments().get(1).allowedValues());
        String schema = action("default.query").arguments().getFirst().schema();
        assertEquals(schema, action("default.execute").arguments().getFirst().schema());
        assertEquals("{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\",\"format\":\"textarea\","
                + "\"contentMediaType\":\"text/x-query\",\"x-language\":\"sql-default\",\"description\":"
                + "\"SELECT … FROM … WHERE … = :name\"},\"params\":{\"type\":\"string\",\"format\":\"textarea\","
                + "\"x-parameters-of\":\"sql\",\"description\":\"the named parameters, a JSON object: "
                + "{\\\"name\\\": 3}\"}},\"required\":[\"sql\"]}", schema);
        assertTrue(action("reports.query").arguments().getFirst().schema().contains("\"x-language\":\"sql-reports\""));
    }

    @Test
    void aPoolsKeyIsItsNameMadeAnActionKey() {
        Set<String> used = new HashSet<>();

        assertEquals("default", PoolActions.key("@Default", used));
        assertEquals("audit-log", PoolActions.key("Audit_Log", used));
        assertEquals("p-1st", PoolActions.key("1st", used));
        assertEquals("default-2", PoolActions.key("default", used), "the @Default pool's key is taken");
        assertEquals("a".repeat(30), PoolActions.key("a".repeat(45), used));
        assertEquals("a".repeat(28) + "-2", PoolActions.key("A".repeat(45), used));
    }

    @Test
    void tablesListsTheTablesAndViewsEachWithItsDescribeAndPreview() {
        bootWithTables();

        ActionResult tables = call("default.tables");

        assertFalse(tables.error(), tables.summary());
        assertEquals(ActionResult.ROWS, tables.contentType());
        assertEquals("3 tables, 1 view", tables.summary());
        assertTrue(tables.body().startsWith("{\"columns\":[{\"name\":\"schema\",\"type\":\"\"},{\"name\":\"name\","
                + "\"type\":\"\"},{\"name\":\"kind\",\"type\":\"\"},{\"name\":\"columns\",\"type\":\"\"},"
                + "{\"name\":\"replay\",\"type\":\"\"},{\"name\":\"replay\",\"type\":\"\"}]"), tables.body());
        assertEquals("[[\"public\",\"open_tasks\",\"VIEW\",2,\"default.describe {\\\"table\\\":\\\"open_tasks\\\"}\","
                + "\"default.preview {\\\"table\\\":\\\"open_tasks\\\"}\"],[\"public\",\"projects\",\"BASE TABLE\",2,"
                + "\"default.describe {\\\"table\\\":\\\"projects\\\"}\",\"default.preview {\\\"table\\\":"
                + "\\\"projects\\\"}\"],[\"public\",\"tasks\",\"BASE TABLE\",6,\"default.describe {\\\"table\\\":"
                + "\\\"tasks\\\"}\",\"default.preview {\\\"table\\\":\\\"tasks\\\"}\"],[\"sales\",\"orders\","
                + "\"BASE TABLE\",2,\"default.describe {\\\"table\\\":\\\"sales.orders\\\"}\",\"default.preview "
                + "{\\\"table\\\":\\\"sales.orders\\\"}\"]]", rows(tables));
    }

    @Test
    void aTableCreatedAfterTheBootIsListedWithoutReplays() {
        bootWithTables();
        panel.actions();
        pools.run("@Default", "CREATE TABLE later (id INT)");

        assertTrue(rows(call("default.tables")).contains("[\"public\",\"later\",\"BASE TABLE\",1,\"\",\"\"]"),
                "Describe and Preview offer the tables of the boot only");
    }

    @Test
    void describeGivesEachColumnItsTypeAndKeysAndItsIndexesInTheSummary() {
        bootWithTables();

        ActionResult tasks = call("default.describe", "table", "tasks");

        assertFalse(tasks.error(), tasks.summary());
        assertEquals("[[\"id\",\"BIGINT\",false,null,1,null],[\"title\",\"CHARACTER VARYING(200)\",false,null,null,"
                + "null],[\"price\",\"NUMERIC(10,2)\",true,null,null,null],[\"due\",\"DATE\",true,null,null,null],"
                + "[\"done\",\"BOOLEAN\",false,\"FALSE\",null,null],[\"project_id\",\"BIGINT\",true,null,null,"
                + "\"projects.id\"]]", rows(tasks));
        assertTrue(tasks.summary().startsWith("6 columns · indexes: "), tasks.summary());
        assertTrue(tasks.summary().contains("idx_tasks_title (title)"), tasks.summary());
        assertEquals("[[\"id\",\"INTEGER\",false,null,1,null],[\"Total\",\"NUMERIC(38)\",true,null,null,null]]",
                rows(call("default.describe", "table", "sales.orders")), "a table of another schema");
    }

    @Test
    void previewReadsTheFirstRowsOfATableUpToItsLimit() {
        bootWithTables();

        ActionResult two = call("default.preview", "table", "tasks", "limit", "2");

        assertTrue(two.summary().matches("first 2 rows in \\d+ ms"), two.summary());
        assertTrue(rows(two).startsWith("[[1,\"first\",\"12.50\",\"2026-10-01\",false,1],[2,\"second\""),
                rows(two));
        assertTrue(call("default.preview", "table", "tasks", "limit", "").summary().matches("3 rows in \\d+ ms"),
                "100 rows when the limit is empty");
        assertEquals("[]", rows(call("default.preview", "table", "sales.orders", "limit", "")),
                "quoted as the database quotes it");
        assertFalse(action("default.preview").arguments().get(1).accepts("1001"));
        assertFalse(action("default.preview").arguments().get(1).accepts("0"));
        assertTrue(action("default.preview").arguments().get(1).accepts("1000"));
    }

    @Test
    void queryAndExecuteRunTheStatementTheyAreGiven() {
        bootWithTables();

        assertEquals("[[\"second\"]]", rows(call("default.query", "statement",
                "{\"sql\":\" SELECT title FROM tasks WHERE id = :id \",\"params\":\"{\\\"id\\\": 2}\"}")));
        assertEquals("[[\"first\"]]", rows(call("default.query", "statement",
                "{\"sql\":\"SELECT title FROM tasks WHERE id = :id\",\"params\":{\"id\":1}}")), "params as an object");
        assertEquals("1 row · committed", call("default.execute", "statement",
                "{\"sql\":\"UPDATE tasks SET title = 'changed' WHERE id = 1\"}", "transaction", "commit").summary());
        assertEquals("1 row · rolled back", call("default.execute", "statement",
                "{\"sql\":\"DELETE FROM tasks WHERE id = 1\"}", "transaction", "rollback").summary());
        assertEquals("[[\"changed\"]]", rows(call("default.query", "statement",
                "{\"sql\":\"SELECT title FROM tasks WHERE id = 1\"}")));

        assertEquals("statement: not valid JSON at character 2", call("default.query", "statement", "{x").summary());
        assertEquals("query: unknown argument", call("default.query", "statement", "{\"query\":\"SELECT 1\"}")
                .summary());
        assertEquals("sql: missing", call("default.query", "statement", "{\"sql\":\" \"}").summary());
        assertEquals("params.id: not a value or a list of values", call("default.query", "statement",
                "{\"sql\":\"SELECT :id\",\"params\":{\"id\":{\"a\":1}}}").summary());
    }

    @Test
    void aPoolWhoseTablesCannotBeReadKeepsTablesQueryAndExecuteAndNeverShowsItsUrl() {
        String url = "jdbc:h2:tcp://127.0.0.1:1/nothing;USER=sa";
        pools = TestPools.boot("vidocq.pool.url", url, "vidocq.pool.acquireTimeout", "PT1S");

        assertEquals(List.of("default.tables", "default.query", "default.execute"),
                panel.actions().stream().map(PanelAction::id).toList());
        ActionResult tables = call("default.tables");
        assertTrue(tables.error());
        assertFalse(tables.summary().contains("127.0.0.1:1/nothing") || tables.body().contains("127.0.0.1:1/nothing"),
                tables.summary());
        assertEquals(0, pools.pool("@Default").snapshot().active());
    }

    @Test
    void aDevReloadReadsTheNewBootsPools() {
        bootWithTables();
        assertEquals(8, panel.actions().size());
        pools.close();

        pools = TestPools.boot("vidocq.pool.url", TestPools.h2("next"));
        panel.start(null);

        assertEquals(List.of("default.tables", "default.query", "default.execute"),
                panel.actions().stream().map(PanelAction::id).toList(), "the new boot's empty database");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run the command of Task 8's Step 2.
Expected: FAIL at `testCompile` — `PoolActionsTest.java:[112,33] cannot find symbol` and the lines after it: no
`PoolActions` yet.

- [ ] **Step 3: Write the tables' metadata, the actions, and the panel's**

In `PDM/SqlRunner.java`, replace:
```java
        return run(sql, Map.of(), true, false, limit);
    }
```
with:
```java
        return run(sql, Map.of(), true, false, limit);
    }

    /** What the panel reads of a connection, such as its tables' metadata. */
    @FunctionalInterface
    interface Reading {
        ActionResult read(Connection connection) throws SQLException;
    }

    /**
     * What {@code reading} reads on a connection of the pool, read-only, given back to the pool as it was; an error
     * result, masked as a statement's is, when it throws an {@link SQLException}.
     */
    ActionResult read(Reading reading) {
        try (Connection connection = pool.getConnection()) {
            boolean readOnly = connection.isReadOnly();
            readOnly(connection, true);
            try {
                return reading.read(connection);
            } finally {
                readOnly(connection, readOnly);
            }
        } catch (SQLException failed) {
            return failure(failed, new LinkedHashMap<>());
        }
    }
```

Create `PDM/PoolMetadata.java`:

```java
/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What the pools panel reads of a database through {@link DatabaseMetaData} (SQL spec §4): the tables and views of its
 * catalog's user schemas, the system ones left out, in schema then name order, with their columns; how it quotes and
 * stores a name; the words its product adds to SQL's.
 *
 * @param catalog         the connection's catalog, or {@code null}
 * @param schema          the connection's current schema, whose tables are named bare, or {@code null}
 * @param identifierQuote the quote around an identifier, {@code "} when the database says none
 * @param unquotedCase    how it stores a name written without quotes, {@code lower}, {@code upper}, or {@code null}
 *                        when it keeps it as written
 * @param keywords        the words of {@link DatabaseMetaData#getSQLKeywords()}, in capitals
 * @param tables          the tables and views
 */
record PoolMetadata(String catalog, String schema, char identifierQuote, String unquotedCase, List<String> keywords,
                    List<Table> tables) {

    /** The schemas of a database's own catalog, in lower case: never listed. */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of("information_schema", "pg_catalog", "pg_toast", "sys",
            "mysql", "performance_schema");

    /**
     * A table or a view.
     *
     * @param schema  its schema, or {@code null}
     * @param name    its name, as the database stores it
     * @param kind    its type, as the database says it: {@code TABLE}, {@code BASE TABLE}, {@code VIEW}…
     * @param columns its columns, in order
     */
    record Table(String schema, String name, String kind, List<Column> columns) {

        /** Whether it is a view, which a language's detail says. */
        boolean view() {
            return kind.toUpperCase(Locale.ROOT).contains("VIEW");
        }
    }

    /**
     * A column of a table.
     *
     * @param name     its name, as the database stores it
     * @param jdbcType its {@link Types} code
     * @param sqlType  its SQL type with its size, such as {@code VARCHAR(200)} or {@code NUMERIC(10,2)}
     * @param target   for a column whose single-column foreign key refers to a table of the list, that table's
     *                 {@link #shown} name; {@code null} otherwise
     */
    record Column(String name, int jdbcType, String sqlType, String target) {}

    /** The name of {@code table} for the panel: bare in the current schema, {@code schema.name} in another. */
    String shown(Table table) {
        return table.schema() == null || table.schema().equals(schema) ? table.name()
                : table.schema() + "." + table.name();
    }

    /** {@code table} as a statement names it: its schema and its name each between the identifier quotes. */
    String quoted(Table table) {
        return (table.schema() == null ? "" : quote(table.schema()) + ".") + quote(table.name());
    }

    private String quote(String name) {
        String q = String.valueOf(identifierQuote);
        return q + name.replace(q, q + q) + q;
    }

    /**
     * Reads the metadata of {@code connection}'s database.
     *
     * @param references whether to read each table's foreign keys too, one more query per table: the boot does,
     *                   for its language; Tables does not
     */
    static PoolMetadata read(Connection connection, boolean references) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String catalog = connection.getCatalog();
        String schema = currentSchema(connection);
        String quote = meta.getIdentifierQuoteString();
        char identifierQuote = quote == null || quote.isBlank() ? '"' : quote.charAt(0);
        String unquotedCase = meta.storesLowerCaseIdentifiers() ? "lower"
                : meta.storesUpperCaseIdentifiers() ? "upper" : null;
        List<String> keywords = meta.getSQLKeywords() == null ? List.of()
                : Arrays.stream(meta.getSQLKeywords().split(",")).map(String::strip).filter(w -> !w.isEmpty())
                .map(w -> w.toUpperCase(Locale.ROOT)).distinct().toList();
        Map<String, Table> tables = new LinkedHashMap<>();
        try (ResultSet found = meta.getTables(catalog, null, "%", null)) {
            while (found.next()) {
                String tableSchema = found.getString("TABLE_SCHEM");
                String kind = Objects.requireNonNullElse(found.getString("TABLE_TYPE"), "");
                if (listed(tableSchema, kind)) {
                    String name = found.getString("TABLE_NAME");
                    tables.put(key(tableSchema, name), new Table(tableSchema, name, kind, new ArrayList<>()));
                }
            }
        }
        for (String each : tables.values().stream().map(Table::schema).distinct().toList()) {
            try (ResultSet found = meta.getColumns(catalog, each, "%", "%")) {
                while (found.next()) {
                    Table table = tables.get(key(found.getString("TABLE_SCHEM"), found.getString("TABLE_NAME")));
                    if (table != null) {
                        table.columns().add(new Column(found.getString("COLUMN_NAME"), found.getInt("DATA_TYPE"),
                                sqlType(found.getString("TYPE_NAME"), found.getInt("DATA_TYPE"),
                                        found.getInt("COLUMN_SIZE"), found.getInt("DECIMAL_DIGITS")), null));
                    }
                }
            }
        }
        PoolMetadata read = new PoolMetadata(catalog, schema, identifierQuote, unquotedCase, keywords,
                tables.values().stream().sorted(Comparator.comparing((Table t) -> Objects.requireNonNullElse(
                        t.schema(), "")).thenComparing(Table::name)).toList());
        return references ? read.withReferences(meta) : read;
    }

    /** This metadata, each column of a single-column foreign key to a listed table given its {@code target}. */
    private PoolMetadata withReferences(DatabaseMetaData meta) throws SQLException {
        Map<String, Table> byKey = new LinkedHashMap<>();
        tables.forEach(table -> byKey.put(key(table.schema(), table.name()), table));
        List<Table> out = new ArrayList<>(tables.size());
        for (Table table : tables) {
            Map<String, List<String[]>> keys = new LinkedHashMap<>();
            try (ResultSet found = meta.getImportedKeys(catalog, table.schema(), table.name())) {
                while (found.next()) {
                    String constraint = Objects.requireNonNullElse(found.getString("FK_NAME"),
                            found.getString("FKCOLUMN_NAME"));
                    keys.computeIfAbsent(constraint, k -> new ArrayList<>()).add(new String[] {
                        found.getString("FKCOLUMN_NAME"), key(found.getString("PKTABLE_SCHEM"),
                                found.getString("PKTABLE_NAME"))});
                }
            }
            Map<String, String> targets = new LinkedHashMap<>();
            for (List<String[]> columns : keys.values()) {
                Table referenced = columns.size() == 1 ? byKey.get(columns.getFirst()[1]) : null;
                if (referenced != null) {
                    targets.put(columns.getFirst()[0], shown(referenced));
                }
            }
            out.add(new Table(table.schema(), table.name(), table.kind(), table.columns().stream()
                    .map(c -> new Column(c.name(), c.jdbcType(), c.sqlType(), targets.get(c.name()))).toList()));
        }
        return new PoolMetadata(catalog, schema, identifierQuote, unquotedCase, keywords, List.copyOf(out));
    }

    /**
     * A SQL type with its size: {@code VARCHAR(200)}, {@code NUMERIC(10,2)}; as the database names it when it says a
     * size already, has none, or one past what a column is declared with ({@code text}, an unbounded
     * {@code CHARACTER VARYING}).
     */
    static String sqlType(String typeName, int jdbcType, int size, int digits) {
        String name = Objects.requireNonNullElse(typeName, "");
        if (name.contains("(") || size <= 0) {
            return name;
        }
        return switch (jdbcType) {
            case Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR, Types.BINARY, Types.VARBINARY ->
                    size < 1_000_000 ? name + "(" + size + ")" : name;
            case Types.NUMERIC, Types.DECIMAL -> size < 1000 ? name + "(" + size + (digits > 0 ? "," + digits : "")
                    + ")" : name;
            default -> name;
        };
    }

    /** The current schema, or {@code null} when the driver does not say. */
    private static String currentSchema(Connection connection) {
        try {
            return connection.getSchema();
        } catch (SQLException | AbstractMethodError unsupported) {
            return null;
        }
    }

    /** Whether a table of {@code schema} and of type {@code kind} is listed: a table or a view of a user schema. */
    private static boolean listed(String schema, String kind) {
        String type = kind.toUpperCase(Locale.ROOT);
        boolean tableOrView = (type.contains("TABLE") || type.contains("VIEW")) && !type.contains("SYSTEM")
                && !type.contains("TEMPORARY") && !type.contains("INDEX");
        String lower = schema == null ? "" : schema.toLowerCase(Locale.ROOT);
        return tableOrView && !SYSTEM_SCHEMAS.contains(lower) && !lower.startsWith("pg_temp")
                && !lower.startsWith("pg_toast");
    }

    private static String key(String schema, String name) {
        return Objects.requireNonNullElse(schema, "") + "\u0000" + name;
    }
}
```

Create `PDM/PoolActions.java`:

```java
/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev;

import io.vidocq.mansart.pool.PoolConfig;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev.PoolMetadata.Table;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.live.MansartPoolsLive;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.Column;
import io.vidocq.runtime.spi.devconsole.PanelAction.Argument;
import io.vidocq.runtime.spi.devconsole.PanelSample;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The actions of one pool in the pools panel (SQL spec §4.1), in a tab named after the pool: <i>Tables</i>,
 * <i>Describe</i> and <i>Preview</i> of a table listed at boot, <i>Query</i>, which only reads, and <i>Execute</i>,
 * asked first and rolled back unless committed. Their SQL runs through the pool's {@link SqlRunner}; what the boot
 * read of its tables, {@link PoolMetadata}, gives the tables Describe and Preview offer, how Preview quotes one, and
 * the panel's {@code sql} language. A pool whose tables could not be read at boot has no Describe and no Preview, and
 * says why at WARNING, never with its URL.
 */
final class PoolActions {

    /** The label of the unnamed pool, the {@code @Default} one, whose key is {@code default}. */
    static final String DEFAULT_LABEL = "@Default";
    static final String TABLES = "tables";
    static final String DESCRIBE = "describe";
    static final String PREVIEW = "preview";
    static final String QUERY = "query";
    static final String EXECUTE = "execute";
    static final String STATEMENT = "statement";
    static final String TABLE = "table";
    static final String LIMIT = "limit";
    static final String TRANSACTION = "transaction";
    /** How many rows Preview shows when no limit is given. */
    static final int DEFAULT_LIMIT = 100;
    /** The longest key, so that {@code <key>.describe} stays within the 40 characters of an action id. */
    static final int MAX_KEY = 30;
    /** The columns of Tables: a table's schema, name, kind and number of columns, then the replays of it. */
    static final List<Column> TABLE_COLUMNS = List.of(new Column("schema", ""), new Column("name", ""),
            new Column("kind", ""), new Column("columns", ""), new Column(PanelSample.REPLAY_COLUMN, ""),
            new Column(PanelSample.REPLAY_COLUMN, ""));
    /** The columns of Describe. */
    static final List<Column> COLUMN_COLUMNS = List.of(new Column("name", ""), new Column("type", ""),
            new Column("nullable", ""), new Column("default", ""), new Column("primary key", ""),
            new Column("foreign key", ""));

    private static final String SQL_MEMBER = "sql";
    private static final String PARAMS_MEMBER = "params";
    private static final System.Logger LOG = System.getLogger(PoolActions.class.getName());

    private final String label;
    private final String key;
    private final SqlRunner runner;
    private final PoolMetadata metadata;

    /**
     * @param label    the pool's label, {@value #DEFAULT_LABEL} or its name: the title of its tab
     * @param key      its key, which its actions' ids and its language's id start with
     * @param runner   runs its SQL
     * @param metadata what the boot read of its tables, or {@code null} when it could not
     */
    PoolActions(String label, String key, SqlRunner runner, PoolMetadata metadata) {
        this.label = Objects.requireNonNull(label, "label");
        this.key = Objects.requireNonNull(key, "key");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.metadata = metadata;
    }

    /**
     * The actions of each pool of {@code pools}, in their order: {@value #DEFAULT_LABEL} first, then the named ones in
     * name order, as the runtime extension publishes them. Each pool's tables are read now, once per boot.
     */
    static List<PoolActions> of(List<MansartPoolsLive.Pool> pools) {
        Set<String> used = new HashSet<>();
        List<PoolActions> out = new ArrayList<>();
        for (MansartPoolsLive.Pool pool : pools) {
            PoolConfig config = pool.pool().config();
            SqlRunner reader = new SqlRunner(pool.pool(), '"', SqlRunner.TIMEOUT_SECONDS, config.jdbcUrl(),
                    config.username(), config.password());
            PoolMetadata metadata = null;
            try (Connection connection = pool.pool().getConnection()) {
                metadata = PoolMetadata.read(connection, true);
            } catch (SQLException | RuntimeException failed) {
                String why = failed instanceof SQLException ? reader.masked(String.valueOf(failed.getMessage()))
                        .replaceAll("\\s+", " ") : failed.getClass().getName();
                LOG.log(System.Logger.Level.WARNING, "Mansart pools: the tables of pool '" + pool.label()
                        + "' could not be read, it has no Describe, no Preview and no SQL language: " + why);
            }
            SqlRunner runner = metadata == null ? reader : new SqlRunner(pool.pool(), metadata.identifierQuote(),
                    SqlRunner.TIMEOUT_SECONDS, config.jdbcUrl(), config.username(), config.password());
            out.add(new PoolActions(pool.label(), key(pool.label(), used), runner, metadata));
        }
        return List.copyOf(out);
    }

    /**
     * The key of a pool: {@code default} for {@value #DEFAULT_LABEL}; its name in lower case otherwise, every
     * character but a letter, a digit or {@code -} made {@code -}, {@code p-} first when it starts with no letter, at
     * most {@value #MAX_KEY} characters, {@code -2}, {@code -3}… when {@code used} already holds it. Adds it to
     * {@code used}.
     */
    static String key(String label, Set<String> used) {
        String base = label.equals(DEFAULT_LABEL) ? "default"
                : label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        if (base.isEmpty() || base.charAt(0) < 'a' || base.charAt(0) > 'z') {
            base = "p-" + base;
        }
        base = base.substring(0, Math.min(base.length(), MAX_KEY));
        String key = base;
        for (int n = 2; !used.add(key); n++) {
            String suffix = "-" + n;
            key = base.substring(0, Math.min(base.length(), MAX_KEY - suffix.length())) + suffix;
        }
        return key;
    }

    String label() {
        return label;
    }

    String key() {
        return key;
    }

    /** What the boot read of the pool's tables, or {@code null} when it could not. */
    PoolMetadata metadata() {
        return metadata;
    }

    /** The id of the pool's language, which its SQL editors name: {@code sql-<key>}. */
    String languageId() {
        return "sql-" + key;
    }

    /** Tables, Describe and Preview when the boot listed a table, Query, Execute: the pool's tab. */
    List<PanelAction> actions() {
        String group = label.length() > PanelAction.MAX_GROUP ? label.substring(0, PanelAction.MAX_GROUP) : label;
        List<PanelAction> actions = new ArrayList<>();
        actions.add(new PanelAction(key + "." + TABLES, "Tables", null, List.of(), given -> tables(), group,
                "The tables and views of the pool's schemas, the system ones left out. Describe and Preview fill "
                        + "their form with one of them."));
        List<String> names = tableNames();
        if (!names.isEmpty()) {
            Argument table = new Argument(TABLE, "Table", names, null, null);
            actions.add(new PanelAction(key + "." + DESCRIBE, "Describe", null, List.of(table),
                    given -> describe(given.get(TABLE)), group, "The columns of a table listed at boot: its SQL "
                            + "type, nullable, its default, its place in the primary key, the column a foreign key "
                            + "refers to; its indexes in the summary."));
            actions.add(new PanelAction(key + "." + PREVIEW, "Preview", null, List.of(table,
                    Argument.matching(LIMIT, "Rows (1 to 1000, 100 when empty)", "|[1-9][0-9]{0,2}|1000")),
                    given -> preview(given.get(TABLE), given.get(LIMIT)), group,
                    "The first rows of a table, read in a transaction rolled back."));
        }
        Argument statement = Argument.json(STATEMENT, "Statement", schema());
        actions.add(new PanelAction(key + "." + QUERY, "Query", null, List.of(statement), given -> call(false, given),
                group, "A SQL query: SELECT, WITH, VALUES, SHOW, EXPLAIN or TABLE, run in a transaction always "
                        + "rolled back; at most " + SqlRunner.MAX_ROWS + " rows are shown.\nA named parameter, :name, "
                        + "takes the member name of params."));
        actions.add(new PanelAction(key + "." + EXECUTE, "Execute", confirmation(), List.of(statement,
                Argument.oneOf(TRANSACTION, "Transaction", SqlRunner.ROLLBACK, SqlRunner.COMMIT)),
                given -> call(true, given), group, "Any one SQL statement, run in a transaction rolled back unless "
                        + "commit is asked: the rows it answers, or how many rows it changed.\nA named parameter, "
                        + ":name, takes the member name of params."));
        return actions;
    }

    /** The question Execute asks before it runs. */
    String confirmation() {
        return "Runs this SQL on " + label + ". A DDL statement (CREATE, ALTER, DROP, TRUNCATE…) may be committed by "
                + "the database itself whatever is chosen.";
    }

    /**
     * The JSON Schema of the {@value #STATEMENT} argument: {@code sql}, which the page shows in its query editor with
     * the pool's language, and {@code params}, in its JSON editor whose schema follows the query.
     */
    String schema() {
        return Json.write(ordered("type", "object", "properties", ordered(
                SQL_MEMBER, ordered("type", "string", "format", "textarea", "contentMediaType", "text/x-query",
                        "x-language", languageId(), "description", "SELECT … FROM … WHERE … = :name"),
                PARAMS_MEMBER, ordered("type", "string", "format", "textarea", "x-parameters-of", SQL_MEMBER,
                        "description", "the named parameters, a JSON object: {\"name\": 3}")),
                "required", List.of(SQL_MEMBER)));
    }

    /** The names Describe and Preview offer: the boot's tables, bare in the current schema; none without them. */
    List<String> tableNames() {
        if (metadata == null) {
            return List.of();
        }
        return metadata.tables().stream().map(metadata::shown)
                .filter(name -> !name.isBlank() && name.length() <= PanelAction.MAX_VALUE_LENGTH).distinct().toList();
    }

    private ActionResult tables() {
        Set<String> known = Set.copyOf(tableNames());
        return runner.read(connection -> {
            PoolMetadata now = PoolMetadata.read(connection, false);
            List<List<Object>> rows = new ArrayList<>();
            int views = 0;
            for (Table table : now.tables()) {
                String shown = now.shown(table);
                boolean replay = known.contains(shown);
                rows.add(Arrays.asList(table.schema(), table.name(), table.kind(), table.columns().size(),
                        replay ? replay(DESCRIBE, shown) : "", replay ? replay(PREVIEW, shown) : ""));
                views += table.view() ? 1 : 0;
            }
            int tables = rows.size() - views;
            return ActionResult.rows(plural(tables, "table") + (views == 0 ? "" : ", " + plural(views, "view")),
                    TABLE_COLUMNS, rows, false);
        });
    }

    /** {@code <key>.<action> {"table": "<name>"}}: a replay that fills that action's form with the table. */
    private String replay(String action, String table) {
        return key + "." + action + " " + Json.write(Map.of(TABLE, table));
    }

    private ActionResult describe(String shown) {
        Table table = table(shown);
        if (table == null) {
            return refused("unknown table " + shown);
        }
        return runner.read(connection -> {
            DatabaseMetaData meta = connection.getMetaData();
            Map<String, Short> primary = new LinkedHashMap<>();
            try (ResultSet found = meta.getPrimaryKeys(metadata.catalog(), table.schema(), table.name())) {
                while (found.next()) {
                    primary.put(found.getString("COLUMN_NAME"), found.getShort("KEY_SEQ"));
                }
            }
            Map<String, String> foreign = new LinkedHashMap<>();
            try (ResultSet found = meta.getImportedKeys(metadata.catalog(), table.schema(), table.name())) {
                while (found.next()) {
                    foreign.put(found.getString("FKCOLUMN_NAME"), found.getString("PKTABLE_NAME") + "."
                            + found.getString("PKCOLUMN_NAME"));
                }
            }
            List<List<Object>> rows = new ArrayList<>();
            String escape = meta.getSearchStringEscape();
            try (ResultSet found = meta.getColumns(metadata.catalog(), pattern(table.schema(), escape),
                    pattern(table.name(), escape), "%")) {
                while (found.next()) {
                    if (!Objects.equals(found.getString("TABLE_SCHEM"), table.schema())
                            || !table.name().equals(found.getString("TABLE_NAME"))) {
                        continue;
                    }
                    String name = found.getString("COLUMN_NAME");
                    String nullable = found.getString("IS_NULLABLE");
                    rows.add(Arrays.asList(name, PoolMetadata.sqlType(found.getString("TYPE_NAME"),
                                    found.getInt("DATA_TYPE"), found.getInt("COLUMN_SIZE"),
                                    found.getInt("DECIMAL_DIGITS")),
                            "YES".equals(nullable) ? Boolean.TRUE : "NO".equals(nullable) ? Boolean.FALSE : null,
                            found.getString("COLUMN_DEF"), primary.get(name), foreign.get(name)));
                }
            }
            List<String> indexes = indexes(meta, table);
            return ActionResult.rows(plural(rows.size(), "column") + " · " + (indexes.isEmpty() ? "no index"
                    : "indexes: " + String.join(", ", indexes)), COLUMN_COLUMNS, rows, false);
        });
    }

    /** The indexes of {@code table}, each {@code name (column, column)}, its columns in their order. */
    private List<String> indexes(DatabaseMetaData meta, Table table) throws SQLException {
        Map<String, TreeMap<Short, String>> byName = new LinkedHashMap<>();
        try (ResultSet found = meta.getIndexInfo(metadata.catalog(), table.schema(), table.name(), false, true)) {
            while (found.next()) {
                String index = found.getString("INDEX_NAME");
                if (index != null && found.getShort("TYPE") != DatabaseMetaData.tableIndexStatistic) {
                    byName.computeIfAbsent(index, name -> new TreeMap<>()).put(found.getShort("ORDINAL_POSITION"),
                            Objects.requireNonNullElse(found.getString("COLUMN_NAME"), "?"));
                }
            }
        }
        return byName.entrySet().stream()
                .map(index -> index.getKey() + " (" + String.join(", ", index.getValue().values()) + ")").toList();
    }

    private ActionResult preview(String shown, String limit) {
        Table table = table(shown);
        if (table == null) {
            return refused("unknown table " + shown);
        }
        int rows = limit == null || limit.isEmpty() ? DEFAULT_LIMIT : Integer.parseInt(limit);
        return runner.preview("SELECT * FROM " + metadata.quoted(table), rows);
    }

    /** The table of the boot's list whose shown name is {@code shown}, or {@code null}. */
    private Table table(String shown) {
        if (metadata == null) {
            return null;
        }
        return metadata.tables().stream().filter(t -> metadata.shown(t).equals(shown)).findFirst().orElse(null);
    }

    /** Runs the statement of a call: Query, or Execute with the transaction asked. */
    private ActionResult call(boolean write, Map<String, String> given) {
        Object parsed;
        try {
            parsed = Json.parse(given.getOrDefault(STATEMENT, "{}"));
        } catch (IllegalArgumentException unreadable) {
            return refused(STATEMENT + ": " + unreadable.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> members)) {
            return refused(STATEMENT + ": not a JSON object");
        }
        for (Object name : members.keySet()) {
            if (!SQL_MEMBER.equals(name) && !PARAMS_MEMBER.equals(name)) {
                return refused(name + ": unknown argument");
            }
        }
        if (!(members.get(SQL_MEMBER) instanceof String sql) || sql.isBlank()) {
            return refused(SQL_MEMBER + ": missing");
        }
        Map<String, Object> params;
        try {
            params = params(members.get(PARAMS_MEMBER));
        } catch (IllegalArgumentException refused) {
            return refused(refused.getMessage());
        }
        return write ? runner.execute(sql.strip(), params, SqlRunner.COMMIT.equals(given.get(TRANSACTION)))
                : runner.query(sql.strip(), params);
    }

    /** {@code params}: absent, blank, a JSON object or the text of one, each member a value or a list of values. */
    static Map<String, Object> params(Object given) {
        Object value = given;
        if (value instanceof String text) {
            if (text.isBlank()) {
                return Map.of();
            }
            try {
                value = Json.parse(text);
            } catch (IllegalArgumentException unreadable) {
                throw new IllegalArgumentException(PARAMS_MEMBER + ": " + unreadable.getMessage());
            }
        }
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> members)) {
            throw new IllegalArgumentException(PARAMS_MEMBER + ": not a JSON object");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> member : members.entrySet()) {
            Object parameter = member.getValue();
            if (parameter instanceof Map<?, ?> || parameter instanceof List<?> list
                    && list.stream().anyMatch(element -> element instanceof Map<?, ?> || element instanceof List<?>)) {
                throw new IllegalArgumentException(PARAMS_MEMBER + "." + member.getKey()
                        + ": not a value or a list of values");
            }
            out.put(String.valueOf(member.getKey()), parameter);
        }
        return out;
    }

    private static ActionResult refused(String why) {
        return new ActionResult(why, null, null, true, null);
    }

    /** {@code name} as a metadata pattern matches it alone: its {@code _} and {@code %} escaped. */
    private static String pattern(String name, String escape) {
        if (name == null || escape == null || escape.isEmpty()) {
            return name;
        }
        return name.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }

    private static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    /** A JSON object of these names and values, in this order. */
    private static Map<String, Object> ordered(Object... namesAndValues) {
        Map<String, Object> object = new LinkedHashMap<>();
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            object.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return object;
    }
}
```

In `PDM/PoolsLivePanel.java`, replace:
```java
import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.live.MansartPoolsLive;
import io.vidocq.runtime.spi.devconsole.Chart;
import io.vidocq.runtime.spi.devconsole.LivePanel;
import io.vidocq.runtime.spi.devconsole.PanelSample;
```
with:
```java
import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.live.MansartPoolsLive;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.devconsole.Chart;
import io.vidocq.runtime.spi.devconsole.LivePanel;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelSample;
```

In `PDM/PoolsLivePanel.java`, replace:
```java
 * this panel only reads what the extension already holds.
 */
```
with:
```java
 * this panel only reads what the extension already holds.
 *
 * <p>In a dev launch, each pool also gets a tab of actions (SQL spec §4, see {@link PoolActions}): its tables, the
 * columns and first rows of one, and SQL run in a transaction. What they need of the pool's tables is read once per
 * boot, by the first {@link #actions()}, and forgotten by {@link #start} and {@link #stop}.
 */
```

In `PDM/PoolsLivePanel.java`, replace:
```java
    /** Created by the service loader. */
```
with:
```java
    /** The pools of this boot and what was read of their tables; {@code null} until the first call needs them. */
    private volatile List<PoolActions> pools;

    /** Created by the service loader. */
```

In `PDM/PoolsLivePanel.java`, replace:
```java
        return "mansart-pool";
    }
```
with:
```java
        return "mansart-pool";
    }

    /** Forgets the previous boot's pools: a dev reload reads its own. */
    @Override
    public void start(ExtensionContext context) {
        pools = null;
    }

    @Override
    public void stop() {
        pools = null;
    }

    /** Each pool's tab of actions, {@code @Default} first, then the named pools in name order. */
    @Override
    public List<PanelAction> actions() {
        return pools().stream().flatMap(pool -> pool.actions().stream()).toList();
    }

    /** The pools of this boot, their tables read now on the first call. */
    private synchronized List<PoolActions> pools() {
        List<PoolActions> read = pools;
        if (read == null) {
            read = PoolActions.of(MansartPoolsLive.pools());
            pools = read;
        }
        return read;
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run the command of Task 8's Step 4.
Expected: `PoolActionsTest` 9, then `Tests run: 26, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`. The log
shows one WARNING (in the JVM's language), `Mansart pools: the tables of pool '@Default' could not be read, it has no
Describe, no Preview and no SQL language: failed to open new connection: …Connection refused: 127.0.0.1:1…` — the
test of an unreachable pool, its URL nowhere in it.

- [ ] **Step 5: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(mansart-pool): a tab per pool, its tables, a table's columns and rows, and SQL

In a dev launch the Mansart pools panel gives each pool a tab, @Default first, then the named pools in name order:
Tables lists the tables and views of its schemas, the system ones left out, each with a Describe and a Preview
replay; Describe gives a table's columns, SQL types, nullability, defaults, primary and foreign keys, and its
indexes in the line; Preview its first rows, 100 unless asked from 1 to 1000; Query and Execute run the statement
typed, Execute asked first and rolled back unless committed. What the tabs need of the tables is read once per boot,
by the first actions(); a pool that cannot be read keeps Tables, Query and Execute and says why without its URL.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
P=vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-pool-extension-dev; M=$P/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/pool/dev; T=$P/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/pool/dev
git add $M/SqlRunner.java $M/PoolMetadata.java $M/PoolActions.java $M/PoolsLivePanel.java $T/PoolActionsTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 10: The `sql-<pool>` language, from `DatabaseMetaData` (§4.3)

**Files:**
- Create: `PDM/SqlLanguage.java`
- Modify: `PDM/PoolsLivePanel.java` (`languages()`, its logger, its Javadoc)
- Create: `PDT/PoolLanguageTest.java`

**Interfaces:**
- Consumes: Task 9's `PoolActions` (`of`, `metadata()`, `label()`, `languageId()`), `PoolMetadata` (`tables()`,
  `shown`, `identifierQuote()`, `unquotedCase()`, `keywords()`, `Column.target()`); `PanelLanguage(id, json)`,
  `PanelLanguage.MAX_JSON`; Task 8's `Json`.
- Produces: `SqlLanguage.KEYWORDS`, `FUNCTIONS`, `CLAUSES`, `TARGET_AFTER`; `static String json(String label,
  PoolMetadata metadata)` and `json(String label, PoolMetadata metadata, int limit)`, throwing
  `IllegalStateException` past the limit at its leanest; `PoolsLivePanel.languages()`.

- [ ] **Step 1: Write the failing tests**

Create `PDT/PoolLanguageTest.java`:

```java
/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev;

import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.live.MansartPoolsLive;
import io.vidocq.runtime.spi.devconsole.PanelLanguage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code sql-<pool>} language of each pool (SQL spec §4.3), read at boot from H2's {@code DatabaseMetaData}
 * through the real Mansart pool: its targets, their columns and types, a foreign key as a reference, the product's
 * keywords, the identifier quote, and its size.
 */
class PoolLanguageTest {

    private final PoolsLivePanel panel = new PoolsLivePanel();
    private TestPools pools;

    @AfterEach
    void stop() {
        if (pools != null) {
            pools.close();
        }
    }

    private Map<?, ?> language(String id) {
        PanelLanguage language = panel.languages().stream().filter(l -> l.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no language " + id));
        return (Map<?, ?>) Json.parse(language.json());
    }

    private static Map<?, ?> member(Map<?, ?> object, String... path) {
        Map<?, ?> at = object;
        for (String name : path) {
            at = (Map<?, ?>) at.get(name);
        }
        return at;
    }

    @Test
    void eachPoolWhoseTablesWereReadHasItsLanguage() {
        pools = TestPools.boot("vidocq.pool.url", TestPools.h2("default"), "vidocq.pool.reports.url",
                TestPools.h2("reports"));
        pools.run("@Default", TestPools.SCHEMA);

        assertEquals(List.of("sql-default", "sql-reports"), panel.languages().stream().map(PanelLanguage::id).toList(),
                "an empty database has a language too: its keywords");
        assertEquals(Map.of(), member(language("sql-reports"), "targets"));
    }

    @Test
    void itsTargetsAreTheTablesWithTheirColumnsTypesAndReferences() {
        pools = TestPools.withTables();

        Map<?, ?> targets = member(language("sql-default"), "targets");

        assertEquals(List.of("open_tasks", "projects", "tasks", "sales.orders"), List.copyOf(targets.keySet()),
                "the current schema's bare, another's with its schema");
        assertEquals("view · public", member(targets, "open_tasks").get("detail"));
        assertEquals("table · public", member(targets, "tasks").get("detail"));
        assertNull(member(targets, "tasks").get("schema"));
        assertEquals("sales", member(targets, "sales.orders").get("schema"));
        Map<?, ?> tasks = member(targets, "tasks", "attributes");
        assertEquals(List.of("id", "title", "price", "due", "done", "project_id"), List.copyOf(tasks.keySet()));
        assertEquals(Map.of("type", "integer", "detail", "BIGINT · column"), member(tasks, "id"));
        assertEquals(Map.of("type", "string", "detail", "CHARACTER VARYING(200) · column"), member(tasks, "title"));
        assertEquals(Map.of("type", "number", "detail", "NUMERIC(10,2) · column"), member(tasks, "price"));
        assertEquals(Map.of("type", "string", "format", "date", "detail", "DATE · column"), member(tasks, "due"));
        assertEquals(Map.of("type", "boolean", "detail", "BOOLEAN · column"), member(tasks, "done"));
        assertEquals(Map.of("type", "integer", "detail", "BIGINT · column", "target", "projects"),
                member(tasks, "project_id"), "a single-column foreign key refers to its table");
        assertEquals(Map.of("type", "number", "detail", "NUMERIC(38) · column"),
                member(targets, "sales.orders", "attributes", "Total"), "a name as the database stores it");
    }

    @Test
    void itsDialectIsSqlsWithTheProductsKeywordsAndTheDatabasesQuote() {
        pools = TestPools.withTables();

        Map<?, ?> dialect = member(language("sql-default"), "dialect");

        List<?> keywords = (List<?>) dialect.get("keywords");
        assertTrue(keywords.containsAll(List.of("SELECT", "JOIN", "LIMIT", "NULLS")), "SQL-92's, and SQL:2003's");
        assertTrue(keywords.contains("QUALIFY"), "one of H2's own: " + keywords);
        assertEquals(keywords.size(), keywords.stream().distinct().count(), "each once");
        assertEquals(SqlLanguage.FUNCTIONS, dialect.get("functions"));
        assertEquals(List.of("SELECT", "FROM", "JOIN", "ON", "WHERE", "GROUP BY", "HAVING", "ORDER BY", "LIMIT",
                "OFFSET", "SET", "VALUES", "UPDATE", "DELETE FROM", "INSERT INTO"), dialect.get("clauses"));
        assertEquals(List.of("FROM", "JOIN", "UPDATE", "INTO"), dialect.get("targetAfter"));
        assertEquals(Boolean.TRUE, dialect.get("aliases"));
        assertTrue(dialect.containsKey("self") && dialect.get("self") == null, "no self");
        assertEquals("'", dialect.get("quote"));
        assertEquals("\"", dialect.get("identifierQuote"));
        assertEquals("lower", dialect.get("unquotedCase"), "H2 told to store names as PostgreSQL does");
    }

    @Test
    void aLanguagePastItsLimitIsWrittenWithoutDetailsThenWithoutTypesThenNotAtAll() {
        pools = TestPools.withTables();
        PoolMetadata metadata = PoolActions.of(MansartPoolsLive.pools()).getFirst().metadata();
        String full = SqlLanguage.json("@Default", metadata);

        String lean = SqlLanguage.json("@Default", metadata, full.length() - 1);
        assertFalse(lean.contains("\"detail\""), lean);
        assertTrue(lean.contains("\"type\":\"integer\""), "the types stay while they fit");
        String leaner = SqlLanguage.json("@Default", metadata, lean.length() - 1);
        assertFalse(leaner.contains("\"type\":\"integer\"") || leaner.contains("\"format\""), leaner);
        assertTrue(leaner.contains("\"project_id\":{\"target\":\"projects\"}"), "every column and reference stays");
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> SqlLanguage.json("@Default", metadata, 100));
        assertTrue(refused.getMessage().startsWith("the SQL language of 4 tables is "), refused.getMessage());
    }

    @Test
    void aPoolWhoseTablesCannotBeReadHasNoLanguage() {
        pools = TestPools.boot("vidocq.pool.url", "jdbc:h2:tcp://127.0.0.1:1/nothing", "vidocq.pool.acquireTimeout",
                "PT1S");

        assertEquals(List.of(), panel.languages());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run the command of Task 8's Step 2.
Expected: FAIL at `testCompile` — `PoolLanguageTest.java:[113,22] cannot find symbol` and the lines after it: no
`SqlLanguage` yet.

- [ ] **Step 3: Write the language**

Create `PDM/SqlLanguage.java`:

```java
/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev;

import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev.PoolMetadata.Column;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev.PoolMetadata.Table;
import io.vidocq.runtime.spi.devconsole.PanelLanguage;

import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code sql-<pool>} language of a pool (SQL spec §4.3), which the page's SQL editors of its Query and Execute
 * fetch once per boot: SQL's dialect — SQL-92's keywords and the product's, the common functions, SQL's clauses,
 * aliases, the database's identifier quote and how it stores a name written without quotes — and one target per table
 * or view the boot listed, named bare in the current schema and {@code schema.name} in another, with its columns. A
 * column's type is its JSON Schema type, from its {@link Types} code; a column of a single-column foreign key names
 * the table it refers to as its {@code target}. Built from what the boot read: no query.
 */
final class SqlLanguage {

    /** SQL-92's reserved words, and the few of SQL:2003 a query of today writes, which are no column. */
    static final List<String> KEYWORDS = List.of("ABSOLUTE", "ACTION", "ADD", "ALL", "ALLOCATE", "ALTER", "AND",
            "ANY", "ARE", "AS", "ASC", "ASSERTION", "AT", "AUTHORIZATION", "AVG", "BEGIN", "BETWEEN", "BIT",
            "BIT_LENGTH", "BOTH", "BY", "CASCADE", "CASCADED", "CASE", "CAST", "CATALOG", "CHAR", "CHARACTER",
            "CHAR_LENGTH", "CHARACTER_LENGTH", "CHECK", "CLOSE", "COALESCE", "COLLATE", "COLLATION", "COLUMN",
            "COMMIT", "CONNECT", "CONNECTION", "CONSTRAINT", "CONSTRAINTS", "CONTINUE", "CONVERT", "CORRESPONDING",
            "COUNT", "CREATE", "CROSS", "CURRENT", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP",
            "CURRENT_USER", "CURSOR", "DATE", "DAY", "DEALLOCATE", "DEC", "DECIMAL", "DECLARE", "DEFAULT",
            "DEFERRABLE", "DEFERRED", "DELETE", "DESC", "DESCRIBE", "DESCRIPTOR", "DIAGNOSTICS", "DISCONNECT",
            "DISTINCT", "DOMAIN", "DOUBLE", "DROP", "ELSE", "END", "ESCAPE", "EXCEPT", "EXCEPTION", "EXEC",
            "EXECUTE", "EXISTS", "EXTERNAL", "EXTRACT", "FALSE", "FETCH", "FIRST", "FLOAT", "FOR", "FOREIGN",
            "FOUND", "FROM", "FULL", "GET", "GLOBAL", "GO", "GOTO", "GRANT", "GROUP", "HAVING", "HOUR", "IDENTITY",
            "IMMEDIATE", "IN", "INDICATOR", "INITIALLY", "INNER", "INPUT", "INSENSITIVE", "INSERT", "INT", "INTEGER",
            "INTERSECT", "INTERVAL", "INTO", "IS", "ISOLATION", "JOIN", "KEY", "LANGUAGE", "LAST", "LEADING", "LEFT",
            "LEVEL", "LIKE", "LOCAL", "LOWER", "MATCH", "MAX", "MIN", "MINUTE", "MODULE", "MONTH", "NAMES",
            "NATIONAL", "NATURAL", "NCHAR", "NEXT", "NO", "NOT", "NULL", "NULLIF", "NUMERIC", "OCTET_LENGTH", "OF",
            "ON", "ONLY", "OPEN", "OPTION", "OR", "ORDER", "OUTER", "OUTPUT", "OVERLAPS", "PAD", "PARTIAL",
            "POSITION", "PRECISION", "PREPARE", "PRESERVE", "PRIMARY", "PRIOR", "PRIVILEGES", "PROCEDURE", "PUBLIC",
            "READ", "REAL", "REFERENCES", "RELATIVE", "RESTRICT", "REVOKE", "RIGHT", "ROLLBACK", "ROWS", "SCHEMA",
            "SCROLL", "SECOND", "SECTION", "SELECT", "SESSION", "SESSION_USER", "SET", "SIZE", "SMALLINT", "SOME",
            "SPACE", "SQL", "SQLCODE", "SQLERROR", "SQLSTATE", "SUBSTRING", "SUM", "SYSTEM_USER", "TABLE",
            "TEMPORARY", "THEN", "TIME", "TIMESTAMP", "TIMEZONE_HOUR", "TIMEZONE_MINUTE", "TO", "TRAILING",
            "TRANSACTION", "TRANSLATE", "TRANSLATION", "TRIM", "TRUE", "UNION", "UNIQUE", "UNKNOWN", "UPDATE",
            "UPPER", "USAGE", "USER", "USING", "VALUE", "VALUES", "VARCHAR", "VARYING", "VIEW", "WHEN", "WHENEVER",
            "WHERE", "WITH", "WORK", "WRITE", "YEAR", "ZONE", "LIMIT", "OFFSET", "NULLS", "RECURSIVE", "RETURNING",
            "ILIKE", "OVER", "PARTITION", "LATERAL", "FILTER");
    /** The functions a query of every database calls, each followed by {@code (}. */
    static final List<String> FUNCTIONS = List.of("COUNT", "SUM", "AVG", "MIN", "MAX", "UPPER", "LOWER", "LENGTH",
            "TRIM", "COALESCE", "NULLIF", "CAST", "ABS", "ROUND", "SUBSTRING", "CONCAT", "REPLACE", "EXTRACT", "NOW");
    /** The words that start a clause (SQL spec §3). */
    static final List<String> CLAUSES = List.of("SELECT", "FROM", "JOIN", "ON", "WHERE", "GROUP BY", "HAVING",
            "ORDER BY", "LIMIT", "OFFSET", "SET", "VALUES", "UPDATE", "DELETE FROM", "INSERT INTO");
    /** The words after which a table is named. */
    static final List<String> TARGET_AFTER = List.of("FROM", "JOIN", "UPDATE", "INTO");

    private static final System.Logger LOG = System.getLogger(SqlLanguage.class.getName());

    private SqlLanguage() {}

    /**
     * The language's JSON, {@code {"mode": "query", "dialect": {…}, "targets": {…}}}, within the
     * {@value PanelLanguage#MAX_JSON} characters of a panel language: past them, written again without the
     * {@code detail}s, then without the {@code type}s and {@code format}s too (every table and column stays), saying
     * so; past them still, refused.
     *
     * @param label    the pool's label, which a log names
     * @param metadata what the boot read of its tables
     * @throws IllegalStateException when even the leanest language is past the limit, naming its size
     */
    static String json(String label, PoolMetadata metadata) {
        return json(label, metadata, PanelLanguage.MAX_JSON);
    }

    /** {@link #json(String, PoolMetadata)} within {@code limit} characters. */
    static String json(String label, PoolMetadata metadata, int limit) {
        Map<String, Object> dialect = dialect(metadata);
        Map<String, Object> targets = targets(metadata);
        String json = write(dialect, targets);
        if (json.length() <= limit) {
            return json;
        }
        int full = json.length();
        for (List<String> dropped : List.of(List.of("detail"), List.of("type", "format"))) {
            drop(targets, dropped);
            json = write(dialect, targets);
            if (json.length() <= limit) {
                LOG.log(System.Logger.Level.INFO, "Mansart pools: the SQL language of pool '" + label + "', "
                        + targets.size() + " tables, is " + full + " characters, past the " + limit
                        + " a panel language may hold: written without " + (dropped.size() == 1 ? "details"
                        : "details or types"));
                return json;
            }
        }
        throw new IllegalStateException("the SQL language of " + targets.size() + " tables is " + json.length()
                + " characters even without details or types, past the " + limit + " a panel language may hold");
    }

    private static String write(Map<String, Object> dialect, Map<String, Object> targets) {
        Map<String, Object> language = new LinkedHashMap<>();
        language.put("mode", "query");
        language.put("dialect", dialect);
        language.put("targets", targets);
        return Json.write(language);
    }

    /** SQL's dialect for this database: its keywords, its quote, how it stores a name; no self. */
    private static Map<String, Object> dialect(PoolMetadata metadata) {
        Set<String> keywords = new LinkedHashSet<>(KEYWORDS);
        keywords.addAll(metadata.keywords());
        Map<String, Object> dialect = new LinkedHashMap<>();
        dialect.put("keywords", List.copyOf(keywords));
        dialect.put("functions", FUNCTIONS);
        dialect.put("clauses", CLAUSES);
        dialect.put("targetAfter", TARGET_AFTER);
        dialect.put("aliases", true);
        dialect.put("self", null);
        dialect.put("quote", "'");
        dialect.put("identifierQuote", String.valueOf(metadata.identifierQuote()));
        if (metadata.unquotedCase() != null) {
            dialect.put("unquotedCase", metadata.unquotedCase());
        }
        return dialect;
    }

    private static Map<String, Object> targets(PoolMetadata metadata) {
        Map<String, Object> targets = new LinkedHashMap<>();
        for (Table table : metadata.tables()) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            for (Column column : table.columns()) {
                Map<String, Object> attribute = new LinkedHashMap<>();
                type(column.jdbcType(), attribute);
                attribute.put("detail", column.sqlType() + " · column");
                if (column.target() != null) {
                    attribute.put("target", column.target());
                }
                attributes.put(column.name(), attribute);
            }
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("detail", (table.view() ? "view" : "table")
                    + (table.schema() == null ? "" : " · " + table.schema()));
            String shown = metadata.shown(table);
            if (!shown.equals(table.name())) {
                target.put("schema", table.schema());
            }
            target.put("attributes", attributes);
            targets.put(shown, target);
        }
        return targets;
    }

    /** Puts the JSON Schema {@code type}, and {@code format} for a date or a time, of a {@link Types} code. */
    private static void type(int jdbcType, Map<String, Object> attribute) {
        switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> attribute.put("type", "integer");
            case Types.REAL, Types.FLOAT, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> attribute.put("type", "number");
            case Types.BIT, Types.BOOLEAN -> attribute.put("type", "boolean");
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                 Types.CLOB, Types.NCLOB -> attribute.put("type", "string");
            case Types.DATE -> format(attribute, "date");
            case Types.TIME, Types.TIME_WITH_TIMEZONE -> format(attribute, "time");
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> format(attribute, "date-time");
            default -> {
                // a type JSON Schema has no word for: the column keeps its detail only
            }
        }
    }

    private static void format(Map<String, Object> attribute, String format) {
        attribute.put("type", "string");
        attribute.put("format", format);
    }

    /** Removes {@code keys} from every target and every attribute of {@code targets}. */
    private static void drop(Map<String, Object> targets, List<String> keys) {
        for (Object target : targets.values()) {
            Map<?, ?> entry = (Map<?, ?>) target;
            entry.keySet().removeAll(keys);
            for (Object attribute : ((Map<?, ?>) entry.get("attributes")).values()) {
                ((Map<?, ?>) attribute).keySet().removeAll(keys);
            }
        }
    }
}
```

In `PDM/PoolsLivePanel.java`, replace:
```java
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelSample;
```
with:
```java
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelLanguage;
import io.vidocq.runtime.spi.devconsole.PanelSample;
```

In `PDM/PoolsLivePanel.java`, replace:
```java
import java.util.List;
```
with:
```java
import java.util.ArrayList;
import java.util.List;
```

In `PDM/PoolsLivePanel.java`, replace:
```java
 * columns and first rows of one, and SQL run in a transaction. What they need of the pool's tables is read once per
 * boot, by the first {@link #actions()}, and forgotten by {@link #start} and {@link #stop}.
 */
```
with:
```java
 * columns and first rows of one, and SQL run in a transaction. What they need of the pool's tables is read once per
 * boot, by the first {@link #actions()}, and forgotten by {@link #start} and {@link #stop}. Each pool whose tables
 * were read offers its SQL editors the {@code sql-<pool>} language of them (see {@link SqlLanguage}).
 */
```

In `PDM/PoolsLivePanel.java`, replace:
```java
            new Chart("throughput", "Throughput", List.of(Series.rate("borrows"), Series.rate("timeouts"))));
```
with:
```java
            new Chart("throughput", "Throughput", List.of(Series.rate("borrows"), Series.rate("timeouts"))));

    private static final System.Logger LOG = System.getLogger(PoolsLivePanel.class.getName());
```

In `PDM/PoolsLivePanel.java`, replace:
```java
        return pools().stream().flatMap(pool -> pool.actions().stream()).toList();
    }
```
with:
```java
        return pools().stream().flatMap(pool -> pool.actions().stream()).toList();
    }

    /**
     * The {@code sql-<pool>} language of each pool whose tables the boot read, from what it read; none for a pool
     * whose language is past the size a panel language may hold even at its leanest, which a WARNING says.
     */
    @Override
    public List<PanelLanguage> languages() {
        List<PanelLanguage> languages = new ArrayList<>();
        for (PoolActions pool : pools()) {
            if (pool.metadata() == null) {
                continue;
            }
            try {
                languages.add(new PanelLanguage(pool.languageId(), SqlLanguage.json(pool.label(), pool.metadata())));
            } catch (IllegalStateException tooBig) {
                // the page says "no vocabulary: not offered", and its editor colours the words it knows alone
                LOG.log(System.Logger.Level.WARNING, "Mansart pools: no SQL language for pool '" + pool.label()
                        + "': " + tooBig.getMessage());
            }
        }
        return languages;
    }
```

- [ ] **Step 4: Run the tests to verify they pass, and install the module for the test application**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-pool-extension-dev install 2>&1 | grep -E "Tests run:|FAIL|BUILD" | tail -7
```
Expected: `PoolLanguageTest` 5, then `Tests run: 31, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(mansart-pool): each pool's SQL editor knows its tables and columns

Each pool whose tables the boot read offers the language sql-<pool>, sql-default for @Default: SQL-92's keywords and
the product's getSQLKeywords(), the common functions, SQL's clauses, aliases, the database's identifier quote and
how it stores an unquoted name; one target per table or view, bare in the current schema and schema.name in another,
each column with its JSON type, its SQL type, and the table a single-column foreign key refers to. Past 1 MiB it is
written without details, then without types; past it still, none, which a WARNING says.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
P=vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-pool-extension-dev; M=$P/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/pool/dev; T=$P/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/pool/dev
git add $M/SqlLanguage.java $M/PoolsLivePanel.java $T/PoolLanguageTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---
### Task 11: JDQL's *Query* answers a table of rows (§4.4)

**Files:**
- Modify: `MDM/EntityJson.java` (`types`), `MDM/ResultJson.java` (two `rows`), `MDM/JdqlActions.java` (`run`,
  `shown`)
- Modify: `MDT/JdqlActionsTest.java`
- Modify: `EXT/DevConsoleSnapshotTest.java` (the consumer that reads JDQL's body)

**Interfaces:**
- Consumes: Task 1's `ActionResult.rows`, `ActionResult.ROWS`, `ActionResult.Column`; `EntityJson` (`toJson`,
  `isEntity`, `model`), `Scalars.schema`, `ResultJson` (`Result(body, what, rows)`, `MAX_ROWS`, `count`, `node`),
  `JdqlResult` (`Entities`, `Rows`, `Count`, `Value`).
- Produces: `Map<String, String> EntityJson.types(Class<?> entity)`; `static ResultJson.Result rows(List<?> found,
  EntityModel<?> model, EntityJson entities)` and `rows(List<String> columns, List<Object[]> rows, EntityModel<?>
  model, EntityJson entities)`, each `Result` with `rows == true`;
  `static ResultJson.Result JdqlActions.shown(JdqlResult result, EntityModel<?> model, EntityJson entities)`. *Query*'s content type is `ROWS` when `value.rows()`, `JSON`
  otherwise; *Update / Delete* and *Export CSV* unchanged.

- [ ] **Step 1: Write the failing tests**

In `MDT/JdqlActionsTest.java`, replace:
```java
        assertTrue(result.summary().matches("2 rows in \\d+ ms"), result.summary());
        assertEquals(ActionResult.JSON, result.contentType());
        assertTrue(result.body().startsWith("[{\"id\":1,\"name\":\"bolt\",\"stock\":3,\"level\":\"LOW\""),
                result.body());
        assertEquals(List.of("FROM Gizmo WHERE stock > :min"), runner.queries, "the statement, stripped");
```
with:
```java
        assertTrue(result.summary().matches("2 rows in \\d+ ms"), result.summary());
        assertEquals(ActionResult.ROWS, result.contentType(), "a table of rows (SQL spec §4.4)");
        assertEquals("{\"columns\":[{\"name\":\"id\",\"type\":\"integer\"},{\"name\":\"name\",\"type\":"
                + "\"string\"},{\"name\":\"stock\",\"type\":\"integer\"},{\"name\":\"level\",\"type\":\"string\"},"
                + "{\"name\":\"due\",\"type\":\"date\"},{\"name\":\"price\",\"type\":\"number\"}],\"rows\":"
                + "[[1,\"bolt\",3,\"LOW\",\"2026-10-01\",2.50],[2,\"bolt\",3,\"LOW\",\"2026-10-01\",2.50]],"
                + "\"more\":false}", result.body(), "the columns its JSON had, typed from the attributes");
        assertEquals(List.of("FROM Gizmo WHERE stock > :min"), runner.queries, "the statement, stripped");
```

In `MDT/JdqlActionsTest.java`, replace:
```java
        assertTrue(rows.summary().matches("1 row in \\d+ ms"), rows.summary());
        assertEquals("[{\"name\":\"bolt\",\"stock\":3}]", rows.body());
```
with:
```java
        assertTrue(rows.summary().matches("1 row in \\d+ ms"), rows.summary());
        assertEquals(ActionResult.ROWS, rows.contentType());
        assertEquals("{\"columns\":[{\"name\":\"name\",\"type\":\"string\"},{\"name\":\"stock\",\"type\":"
                + "\"integer\"}],\"rows\":[[\"bolt\",3]],\"more\":false}", rows.body());
```

In `MDT/JdqlActionsTest.java`, replace:
```java
        assertEquals("42", count.body());
```
with:
```java
        assertEquals("42", count.body());
        assertEquals(ActionResult.JSON, count.contentType(), "a count has no column: JSON, as before");
```

In `MDT/JdqlActionsTest.java`, replace:
```java
        assertTrue(none.matches("no row in \\d+ ms"), none);
    }
```
with:
```java
        assertTrue(none.matches("no row in \\d+ ms"), none);
    }

    @Test
    void aProjectionOfAnEntityIsAnObjectColumnOfItsJsonAndAWriteStaysJson() {
        RepositoryActions actions = build();
        runner.answer = new JdqlResult.Rows(List.of("gizmo", "label", "other"),
                List.<Object[]>of(new Object[] {bolt(7), "a", null}));

        ActionResult rows = query(actions, "{\"query\":\"SELECT gizmo, label FROM Part\"}");

        assertEquals("{\"columns\":[{\"name\":\"gizmo\",\"type\":\"object\"},{\"name\":\"label\",\"type\":"
                + "\"string\"},{\"name\":\"other\",\"type\":\"\"}],\"rows\":[[\"{\\\"id\\\":7,\\\"name\\\":"
                + "\\\"bolt\\\",\\\"stock\\\":3,\\\"level\\\":\\\"LOW\\\",\\\"due\\\":\\\"2026-10-01\\\","
                + "\\\"price\\\":2.50}\",\"a\",null]],\"more\":false}", rows.body(), "an entity as its JSON text");
        runner.answer = new JdqlResult.Count(4);
        assertEquals(ActionResult.JSON, run(actions, JdqlActions.WRITE, "{\"query\":\"UPDATE Gizmo SET stock = 0\"}",
                null).contentType(), "Update / Delete is unchanged");
    }
```

In `MDT/JdqlActionsTest.java`, replace:
```java
        assertTrue(result.summary().matches("first 100 rows in \\d+ ms"), result.summary());
        assertEquals(100, ((List<?>) Json.parse(result.body())).size());
    }
```
with:
```java
        assertTrue(result.summary().matches("first 100 rows in \\d+ ms"), result.summary());
        Map<?, ?> body = (Map<?, ?>) Json.parse(result.body());
        assertEquals(100, ((List<?>) body.get("rows")).size());
        assertEquals(Boolean.TRUE, body.get("more"), "the 101st says there are more");
    }
```

In `EXT/DevConsoleSnapshotTest.java`, replace:
```java
        assertTrue(((String) dear.get("result")).matches("2 rows in \\d+ ms"), "result: " + dear);
        assertEquals(List.of("Cappuccino", "Latte"),
```
with:
```java
        assertTrue(((String) dear.get("result")).matches("2 rows in \\d+ ms"), "result: " + dear);
        assertEquals("application/x-rows+json", dear.get("contentType"), "a table of rows");
        assertEquals(List.of("Cappuccino", "Latte"),
```

In `EXT/DevConsoleSnapshotTest.java`, replace:
```java
    private static List<?> rows(String text) throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            return (List<?>) jsonb.fromJson(text, Object.class);
        }
```
with:
```java
    /** The rows of a body of rows, each a map of its columns' names to its values. */
    private static List<Map<String, Object>> rows(String text) throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            Map<?, ?> body = (Map<?, ?>) jsonb.fromJson(text, Object.class);
            List<?> columns = (List<?>) body.get("columns");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object row : (List<?>) body.get("rows")) {
                Map<String, Object> named = new LinkedHashMap<>();
                for (int i = 0; i < columns.size(); i++) {
                    named.put((String) ((Map<?, ?>) columns.get(i)).get("name"), ((List<?>) row).get(i));
                }
                rows.add(named);
            }
            return rows;
        }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev test -Dtest=JdqlActionsTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run:|FAIL" | tail -3
```
Expected: FAIL — `Tests run: 19, Failures: 3, Errors: 1`: *Query*'s content type is still `application/json`
(`aQueryRunsOnTheEntityItNamesWithItsParametersInATransactionRolledBack`, `eachResultKindHasItsSummary`,
`aProjectionOfAnEntityIsAnObjectColumnOfItsJsonAndAWriteStaysJson`), and `aQueryOfManyRowsShowsTheFirstHundred` reads
a list where it expects an object.

- [ ] **Step 3: Write the rows of JDQL's *Query***

In `MDM/EntityJson.java`, replace:
```java
    /** The id of {@code entity}, an instance of an entity: what a reference to it is written as. */
```
with:
```java
    /**
     * The type a table of rows shows for each name {@link #names} gives {@code entity}'s instances, in that order (SQL
     * spec §4.4): the JSON Schema type of the attribute's Java type, or its format for a date, a time or a uuid; for a
     * reference, its id's; empty when it has none.
     *
     * @throws RuntimeException when its model cannot be read, as {@code EntityModels.of} throws it
     */
    Map<String, String> types(Class<?> entity) {
        Map<String, String> types = new LinkedHashMap<>();
        for (Attribute<?, ?> attribute : model(entity).attributes()) {
            if (attribute == null || attribute instanceof JoinedAttribute<?, ?> || attribute.getter() == null) {
                continue;
            }
            Class<?> type = attribute.javaType();
            if (attribute instanceof ReferenceAttribute<?, ?> reference) {
                try {
                    type = model(reference.javaType()).id().javaType();
                } catch (RuntimeException | LinkageError unreadable) {
                    type = null;
                }
            }
            Map<String, Object> schema = type == null ? null : Scalars.schema(type);
            types.put(attribute.name(), schema == null ? "" : String.valueOf(schema.getOrDefault("format",
                    schema.get("type"))));
        }
        return types;
    }

    /** The id of {@code entity}, an instance of an entity: what a reference to it is written as. */
```

In `MDM/ResultJson.java`, replace:
```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;
```
with:
```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.Column;
```

In `MDM/ResultJson.java`, replace:
```java
    /** {@code value} as JSON, as an element of a list is written: an entity as an object, a scalar as itself. */
```
with:
```java
    /**
     * Entities a JDQL query found as a table of rows (SQL spec §4.4): one column per name its objects have, in model
     * order, typed from the attributes ({@link EntityJson#types}); at most {@value #MAX_ROWS} rows, the next one
     * setting {@code more}.
     *
     * @param model the model of the entity the query names
     */
    static Result rows(List<?> found, EntityModel<?> model, EntityJson entities) {
        boolean more = found.size() > MAX_ROWS;
        Map<String, String> types = entities.types(model.entityClass());
        List<List<Object>> rows = new ArrayList<>();
        for (Object entity : more ? found.subList(0, MAX_ROWS) : found) {
            Map<String, Object> json = entities.toJson(entity);
            rows.add(types.keySet().stream().map(json::get).toList());
        }
        List<Column> columns = types.entrySet().stream().map(type -> new Column(type.getKey(), type.getValue()))
                .toList();
        return new Result(ActionResult.rows(null, columns, rows, more).body(),
                more ? "first " + MAX_ROWS + " rows" : count(rows.size()), true);
    }

    /**
     * The rows of a JDQL projection as a table of rows (SQL spec §4.4): its columns in order, each typed as the
     * attribute of that name of {@code model}, or {@code object} when it holds entities; an entity, a list, written as
     * its JSON text; at most {@value #MAX_ROWS} rows, the next one setting {@code more}.
     */
    static Result rows(List<String> columns, List<Object[]> rows, EntityModel<?> model, EntityJson entities) {
        boolean more = rows.size() > MAX_ROWS;
        List<Object[]> kept = more ? rows.subList(0, MAX_ROWS) : rows;
        Map<String, String> types = entities.types(model.entityClass());
        List<Column> shown = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            int at = i;
            boolean holdsEntities = kept.stream().map(row -> at < row.length ? row[at] : null)
                    .anyMatch(value -> value != null && entities.isEntity(value.getClass()));
            shown.add(new Column(columns.get(i), holdsEntities ? "object" : types.getOrDefault(columns.get(i), "")));
        }
        List<List<Object>> out = new ArrayList<>();
        for (Object[] row : kept) {
            List<Object> values = new ArrayList<>(columns.size());
            for (int i = 0; i < columns.size(); i++) {
                Object node = node(i < row.length ? row[i] : null, entities, 1);
                values.add(node instanceof Map<?, ?> || node instanceof List<?> ? Json.write(node) : node);
            }
            out.add(values);
        }
        return new Result(ActionResult.rows(null, shown, out, more).body(),
                more ? "first " + MAX_ROWS + " rows" : count(out.size()), true);
    }

    /** {@code value} as JSON, as an element of a list is written: an entity as an object, a scalar as itself. */
```

In `MDM/JdqlActions.java`, replace:
```java
        String runs = write ? mode : transactions.available() ? TransactionRunner.ROLLBACK : null;
        TransactionRunner.Outcome<ResultJson.Result> outcome = transactions.run(runs,
                () -> answer(runner.run(statement.query(), statement.params(), model, runtime,
                        ResultJson.MAX_ROWS + 1), write, json));
        if (outcome.failure() != null) {
```
with:
```java
        String runs = write ? mode : transactions.available() ? TransactionRunner.ROLLBACK : null;
        TransactionRunner.Outcome<ResultJson.Result> outcome = transactions.run(runs, () -> {
            JdqlResult result = runner.run(statement.query(), statement.params(), model, runtime,
                    ResultJson.MAX_ROWS + 1);
            return write ? answer(result, true, json) : shown(result, model, json);
        });
        if (outcome.failure() != null) {
```

In `MDM/JdqlActions.java`, replace:
```java
                + (!write || outcome.state() == null ? "" : " · " + outcome.state());
        return new PanelAction.ActionResult(summary, PanelAction.ActionResult.JSON, value.body(), false, details);
    }
```
with:
```java
                + (!write || outcome.state() == null ? "" : " · " + outcome.state());
        // a query's entities and rows are a table of rows (SQL spec §4.4); a count, a value and a write are JSON
        return new PanelAction.ActionResult(summary, !write && value.rows() ? PanelAction.ActionResult.ROWS
                : PanelAction.ActionResult.JSON, value.body(), false, details);
    }

    /**
     * What a query shows (SQL spec §4.4): its entities and the rows of a projection as a table of rows, at most
     * {@value ResultJson#MAX_ROWS} of them; a count or an aggregate as {@link #answer} writes it.
     *
     * @param model the model of the entity the query names, whose attributes type the columns
     */
    static ResultJson.Result shown(JdqlResult result, EntityModel<?> model, EntityJson entities) {
        return switch (result) {
            case JdqlResult.Entities found -> ResultJson.rows(found.entities(), model, entities);
            case JdqlResult.Rows rows -> ResultJson.rows(rows.columns(), rows.rows(), model, entities);
            default -> answer(result, false, entities);
        };
    }
```

- [ ] **Step 4: Run the tests to verify they pass, the module's whole build, and the consumer**

Run the command of Step 2. Expected: `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0`. Then:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev install 2>&1 | grep -E "Tests run:|FAIL|BUILD" | grep -v " in io" | tail -3
```
Expected: `Tests run: 167, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`. Then the consumer, whose
`DevConsoleSnapshotTest` runs *Query* through the console:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu clean verify -pl vidocq-runtime-examples/vidocq-runtime-mansart-h2-example -Dvidocq.checkpom.failOnMissing=false 2>&1 | grep -E "Tests run:|FAIL|BUILD" | tail -4
```
(`timeout: 600000`.) Expected: `DevConsoleSnapshotTest` 10, `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0` and
`BUILD SUCCESS` (Ruling 23 for the flag).

- [ ] **Step 5: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(mansart-data): JDQL's Query answers a table of rows

The JDQL tab's Query answers application/x-rows+json for entities and projections: the columns its JSON had, in
order, each typed from the entity's attribute (integer, string, date...), a projected column of entities typed object
and written as their JSON; at most 100 rows, the next one setting more. A count and an aggregate, which have no
column, and Update / Delete stay JSON; Export CSV is unchanged. The H2 example reads the rows.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
P=vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev; M=$P/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev; T=$P/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev
git add $M/EntityJson.java $M/ResultJson.java $M/JdqlActions.java $T/JdqlActionsTest.java vidocq-runtime-examples/vidocq-runtime-mansart-h2-example/src/test/java/io/vidocq/runtime/examples/mansart/DevConsoleSnapshotTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 12: Documentation (§6)

**Files:**
- Modify: `SPIM/PanelLanguage.java` (its Javadoc: SQL's options)
- Modify: `DOCS/dev-console.adoc` (the *Mansart pools* row of the panels' table, a section `#rows-table`, a
  subsection `#query-sql`, SQL's options in `#query-language`)
- Modify: `DOCS/modules/vidocq-runtime-extensions.adoc` (a subsection `#mansart-pool-sql`, JDQL's *Results*)
- Modify: `DOCS/whats-new.adoc` (one entry, before *Dev service containers are named `vidocq-dev-…`*)

**Interfaces:**
- Consumes: the behaviour of Tasks 1-11, as their tests pin it; `ActionResult.ROWS`'s Javadoc was written in Task 1.
- Produces: the anchors `rows-table`, `query-sql` (in `dev-console.adoc`) and `mansart-pool-sql` (in
  `modules/vidocq-runtime-extensions.adoc`), each with a `[.tag-new]#NEW#` badge, and the cross-references between
  them.

- [ ] **Step 1: Write the Javadoc and the pages**

In `SPIM/PanelLanguage.java`, replace:
```java
 *
 * @param id   identifies the language among those of its panel, stable across boots; it follows the rule of
```
with:
```java
 *
 * <p><b>SQL's options.</b> A dialect with {@code "aliases": true} is SQL's: every target after a {@code targetAfter}
 * word is in the statement's scope ({@code FROM} a list of them), a name after a target, or {@code AS} and a name, is
 * its alias, and a path starts from an alias, a target or a column of a target in scope; what a sub-query holds is not
 * checked; {@code --} and {@code /*} comments are skipped. {@code "identifierQuote": """} makes a quoted name a
 * name, looked up as written; {@code "unquotedCase"}, {@code lower} or {@code upper}, says how the database stores a
 * name written without quotes, so that completion quotes a name that needs it; {@code "self": null} says there is no
 * self. A target of another schema, keyed {@code schema.name}, says {@code "schema"}. JDQL declares none of these.
 *
 * @param id   identifies the language among those of its panel, stable across boots; it follows the rule of
```

In `DOCS/dev-console.adoc`, replace:
```asciidoc
| *Mansart pools*
| One card per pool of `vidocq-runtime-mansart-pool-extension`: its size, timeouts and checks, its URL without credentials in a dev launch, the active and idle connections out of its maximum, the borrowers waiting, borrows, timeouts and leaks, and the mean borrow time. See xref:modules/vidocq-runtime-extensions.adoc#mansart-pool-panel[the Mansart pool section].
```
with:
```asciidoc
| *Mansart pools*
| One card per pool of `vidocq-runtime-mansart-pool-extension`: its size, timeouts and checks, its URL without credentials in a dev launch, the active and idle connections out of its maximum, the borrowers waiting, borrows, timeouts and leaks, and the mean borrow time. In a `dev` launch, a tab per pool [.tag-new]#NEW# lists its tables, describes one, shows its first rows and runs the SQL you type, read-only or in a transaction rolled back unless committed. See xref:modules/vidocq-runtime-extensions.adoc#mansart-pool-panel[the Mansart pool section] and xref:modules/vidocq-runtime-extensions.adoc#mansart-pool-sql[Tables and a SQL editor].
```

In `DOCS/dev-console.adoc`, replace:
```asciidoc
[#json-editor]
```
with:
```asciidoc
[#rows-table]
== A table of rows [.tag-new]#NEW#

A result of rows — what *Query* answers in a pool's tab or in the *JDQL* tab of *Mansart Data*, a table's columns,
its first rows — is drawn as a table. Each column's header holds its name and, dimmed under it, its type; the header
stays in view while the rows scroll, and a wide table scrolls sideways inside the result. A `NULL` is written `NULL`,
dimmed, and is never mistaken for an empty string, which is an empty cell. A value longer than 200 characters is cut
with `…`, and the whole of it shows when the pointer rests on the cell. When the panel sent only the first rows,
`more rows not shown` says so under them.

The result's bar adds *Table* and *JSON*, which shows the same body in the <<json-viewer,JSON viewer>>, and *Copy as
CSV*, which copies the rows as RFC 4180 CSV: the header first, `,` between fields, a field with a comma, a quote or a
line end quoted, each line ended by CRLF, `NULL` an empty field and an empty string `""`. The page remembers which of
the two it shows while it polls. A cell of a column named `replay` is a button named after the action it fills, as
*Tables* offers *Describe* and *Preview* for each table; nothing is sent until you submit.

A panel answers rows with `PanelAction.ActionResult.rows(summary, columns, rows, more)`: `columns` are
`ActionResult.Column(name, type)` records, a name and a type of 200 characters at most; each row holds one value per
column, `null`, a `Boolean`, a `Number` or a `String`, anything else written as its `toString()`. The body,
`{"columns": [{"name", "type"}…], "rows": [[…]…], "more": bool}` of type `application/x-rows+json`
(`ActionResult.ROWS`), stays within the 256 KiB of a result: the rows past it are left out and `more` set. A body of
another shape is shown as any other JSON.

[#json-editor]
```

In `DOCS/dev-console.adoc`, replace:
```asciidoc
[#query-language]
```
with:
```asciidoc
[#query-sql]
=== SQL [.tag-new]#NEW#

The `sql` of a pool's *Query* and *Execute* (xref:modules/vidocq-runtime-extensions.adoc#mansart-pool-sql[Tables and a
SQL editor]) is typed in the same editor, with what its panel publishes of the pool's tables, and reads SQL as SQL is
written:

* **Several tables.** Every table after `FROM`, `JOIN`, `UPDATE`, `INSERT INTO` or `DELETE FROM` is in the
statement's scope, and so is each table of a `FROM` list; a name after a table, or `AS` and a name, is its alias
(`FROM tasks t`, `JOIN projects AS p`), a keyword never. A table of another schema is written `sales.orders`.
* **Quoted names.** `"Order"`, `"due date"` are names, a doubled `""` inside one quote; the editor looks them up as
written, and a name written without quotes as the database stores it: in lower case for PostgreSQL, in capitals for
H2. `"` pairs and steps over as `'` does. `--` and `/* … */` comments are dimmed and never checked.
* **Completion.** After `FROM` or `JOIN`, the tables; after `t.` or `tasks.`, that table's columns; in `SELECT`, `ON`,
`WHERE`, `GROUP BY`, `HAVING`, `ORDER BY` and `SET`, the aliases and the tables in scope, then the columns of each,
their table in their detail (`varchar(200) · column · tasks`), the functions and the keywords. A name that needs quotes
— a keyword, a space, a capital the database would not keep — is inserted with them.
* **Diagnostics.** An unknown table, an unknown column of a table or an alias, and an unknown alias before a dot
(`unknown table or alias x`) are errors; a column named alone that two tables in scope have is a warning, `title is in
tasks t and projects p`. A table written with its schema is looked up as written, then without it, and not checked
when neither is known. What a sub-query, a `WITH`'s table, an alias of the select list, a cast's type (`::text`) or a
function the editor does not know holds is never checked: the database judges it when the statement runs.
* **Format.** Each clause on a line of its own, a `LEFT JOIN … ON` on one line, `AND` and `OR` indented, the keywords
in capitals, quoted names, strings and comments as written, a sub-query on the line it is on.

A `:name` in the statement is a parameter, as in JDQL: `params` follows it, and a parameter compared with `t.price`
takes the type of `price` in `t`'s table.

[#query-language]
```

In `DOCS/dev-console.adoc`, replace:
```asciidoc
refers to another entity of the language. Data the editor does not understand makes it know less, never fail.
```
with:
```asciidoc
refers to another entity of the language. Data the editor does not understand makes it know less, never fail.

*SQL's options* [.tag-new]#NEW#. `aliases`, `identifierQuote` and `unquotedCase` make a dialect SQL's (<<query-sql>>),
with SQL's own words; JDQL declares none of them:

[source,json]
----
"dialect": { "keywords": […], "functions": […], "self": null, "quote": "'",
  "aliases": true,
  "targetAfter": ["FROM", "JOIN", "UPDATE", "INTO"],
  "identifierQuote": "\"",
  "unquotedCase": "lower",
  "clauses": ["SELECT", "FROM", "JOIN", "ON", "WHERE", "GROUP BY", "HAVING", "ORDER BY", "LIMIT", "OFFSET", "SET",
              "VALUES", "UPDATE", "DELETE FROM", "INSERT INTO"] }
----

`aliases` puts every target after a `targetAfter` word in scope, with its alias, skips `--` and `/*` comments, and
leaves a sub-query unchecked; `identifierQuote` makes a quoted name a name; `unquotedCase`, `lower` or `upper`, says how
the database stores a name written without quotes, and is left out when it keeps it as written; `"self": null` says
there is none. A target of another schema is keyed `schema.name` and says its `"schema"`, so that completion quotes each
part as needed.
```

In `DOCS/modules/vidocq-runtime-extensions.adoc`, replace:
```asciidoc
[#mansart-pool-001]
```
with:
```asciidoc
[#mansart-pool-sql]
=== Tables and a SQL editor [.tag-new]#NEW#

Under `mvn vidocq:dev`, each pool also has a tab of the *Mansart pools* panel, named after it — `@Default` first, then the named pools in name order — whose list offers five actions. They answer in the dev console's xref:dev-console.adoc#rows-table[table of rows].

* *Tables* lists the tables and views of the pool's catalog and its schemas, the system ones (`information_schema`, `pg_catalog`…) left out, in schema then name order: schema, name, kind (`TABLE`, `BASE TABLE`, `VIEW`…) and number of columns, with a *Describe* and a *Preview* button that fill those forms with the table. The line says `12 tables, 2 views`.
* *Describe* takes one of the tables listed at boot: each column's name, SQL type with its size (`varchar(200)`, `numeric(10,2)`), whether it is nullable, its default, its place in the primary key, and the `table.column` a foreign key refers to; the line names its indexes, `9 columns · indexes: tasks_pkey (id), tasks_status_due_idx (status, due_date)`.
* *Preview* shows the first rows of one, 100 unless you say from 1 to 1000: `SELECT * FROM "public"."tasks"`, the table quoted as the database quotes a name, read in a transaction rolled back.
* *Query* runs a statement that only reads — its first word, comments skipped, is `SELECT`, `WITH`, `VALUES`, `SHOW`, `EXPLAIN` or `TABLE`, anything else refused before it runs: `Query only reads: use Execute` — on a read-only connection, in a transaction always rolled back. It shows 100 rows at most; the line says `3 rows in 12 ms`, or `first 100 rows in 40 ms` when there are more.
* *Execute* runs any one statement, asked first (`Runs this SQL on @Default. A DDL statement (CREATE, ALTER, DROP, TRUNCATE…) may be committed by the database itself whatever is chosen.`), in a transaction that its *Transaction* list says to roll back, the default, or to commit; it is always rolled back when the statement fails. It answers the rows the statement returns, an `UPDATE … RETURNING` included, or how many it changed: `3 rows · rolled back`. PostgreSQL rolls a `CREATE TABLE` back; most databases commit a DDL statement whatever is asked.

The form has two fields: `sql`, the statement, in the dev console's query editor with the pool's language (xref:dev-console.adoc#query-sql[SQL]), and `params`, its named parameters as a JSON object, whose schema follows the statement. A `:name` outside a string, a quoted name and a comment is bound with the member `name` of `params`, as `setObject`: a string, a number (an integer as a `long`), a boolean or `null`, and a list as a SQL array where the driver makes one (`WHERE id = ANY(:ids)`); a cast such as `due::text` is no parameter. A parameter with no member is refused before anything runs, `missing parameter id`, and so is a text of two statements, `one statement at a time`; a last `;` is fine.

[source,text]
----
SELECT t.title, p.name FROM tasks t JOIN projects p ON p.id = t.project_id WHERE t.status = :status
                                                                            params: {"status": "OPEN"}
UPDATE tasks SET priority = 'HIGH' WHERE id = :id RETURNING id, priority      params: {"id": 3}
----

*Values.* A number is shown as a number, but a `numeric` and an integer past 2^53 as their text, so that nothing is rounded; a date, a time or a timestamp as ISO text, with its offset when it has a zone (`2026-10-01T12:00+02:00`); binary as `0x` and the hex of its first 64 bytes, `…` after when there are more; an array as the JSON of its elements; a text past 10 000 characters cut with `…`; anything else, a `json`, an `interval`, as the driver writes it.

*Failures.* A statement runs at most 30 seconds, then is cancelled: `the statement ran past 30 s and was cancelled`. A failing statement shows the database's message, on one line, with the pool's URL, its password and its user after the word `user` masked; *Exchange* holds the statement, its parameters, the transaction, the `SQLState` and the vendor code. A pool with no connection to lend answers once its `acquireTimeout` has passed. Every call takes a connection from the pool and gives it back as it was, its auto-commit and read-only flag restored, whatever happened.

*The `sql` language.* What the editor knows of the pool is read once per boot from `DatabaseMetaData` and offered as the language `sql-<pool>` — `sql-default` for `@Default`, a named pool's name in lower case: SQL-92's keywords and the product's (`getSQLKeywords()`), the common functions, the database's identifier quote and how it stores a name written without quotes; each table and view, bare in the current schema and `schema.name` in another, with its columns, their JSON type and `<SQL type> · column`, and a column of a single-column foreign key leading to the table it refers to. Past 1 MiB it is written without the details, then without the types; past it still, the pool has no language, which a WARNING says, and the editor colours the keywords it knows alone. A pool whose tables cannot be read at boot gets no language, no *Describe* and no *Preview*, which a WARNING says without its URL; *Tables*, *Query* and *Execute* still work. A table created later is listed by *Tables* but known to the editor, *Describe* and *Preview* after the next dev reload only.

[#mansart-pool-001]
```

In `DOCS/modules/vidocq-runtime-extensions.adoc`, replace:
```asciidoc
*Results.* Through the page's JSON viewer: the entities as objects, as a repository method returns them (<<mansart-data-run-method>>); a projection as one object per row, `{"title": "Release notes", "priority": "HIGH"}`; at most 100 rows, the line saying `3 rows in 12 ms` or `first 100 rows in 40 ms`. A count is its number, `42`; an aggregate its value, `null` when there is no row; a write the rows it changed, `4 rows · rolled back`. *Exchange* holds the entity, the statement, its parameters and the transaction asked. A statement that does not parse, or fails, shows the class of the exception and its message, cut after 500 characters and with a `user:password@` masked.
```
with:
```asciidoc
*Results.* The entities as rows of a table [.tag-new]#NEW#, one column per attribute as a repository method returns them (<<mansart-data-run-method>>), each typed from its attribute (`integer`, `string`, `date`…); a projection's columns, a column of entities typed `object` and each written as its JSON; at most 100 rows, the line saying `3 rows in 12 ms` or `first 100 rows in 40 ms` (xref:dev-console.adoc#rows-table[A table of rows], with *JSON* and *Copy as CSV*). A count is its number, `42`; an aggregate its value, `null` when there is no row; a write the rows it changed, `4 rows · rolled back`. *Exchange* holds the entity, the statement, its parameters and the transaction asked. A statement that does not parse, or fails, shows the class of the exception and its message, cut after 500 characters and with a `user:password@` masked.
```

In `DOCS/whats-new.adoc`, replace:
```asciidoc
* **A query editor for JDQL in the dev console** [.tag-new]#NEW# — the statement of the *Mansart Data* panel's *JDQL* tab, in *Query*, *Update / Delete* and *Export CSV*, is typed in the dev console's code editor in a new query mode: keywords, functions, the entity, its attributes, strings, numbers and parameters each coloured; `Ctrl+Space` completes the entities after `FROM`, their attributes with their Java type and column in `SELECT`, `WHERE`, `ORDER BY` and `SET` — `FROM` written after the caret included — and a reference's attributes after `project.`; an unknown entity or attribute, a path through an attribute that is no reference, an unterminated string or an unbalanced parenthesis is underlined before anything runs; *Format* puts each clause on its line. Its `params` become a JSON editor whose schema follows the statement as you type: `WHERE status = :status` offers `status` with the values of its enum and flags it missing. A panel publishes such a vocabulary with `DevConsolePanel.languages()` (or `LivePanel`'s), `PanelLanguage` records the page fetches once at `GET /api/language/<panel>/<id>` in a `dev` launch, and names it from a `json` argument's schema with `"contentMediaType": "text/x-query"`, `"x-language"` and `"x-parameters-of"`. xref:dev-console.adoc#query-editor[Query editor], xref:modules/vidocq-runtime-extensions.adoc#mansart-data-jdql[A JDQL console for Mansart Data].
* **Dev service containers are named `vidocq-dev-…`** [.tag-new]#NEW# — the PostgreSQL and Keycloak dev services used to leave their containers to Docker's random names; each is now `<prefix><application>-<service>[-<datasource>]-<suffix>`, such as `vidocq-dev-mcp-tasks-server-postgres-3f9a2c01`, the prefix `vidocq-dev-` unless `vidocq.dev.container-prefix` says otherwise. The suffix is random, or for a reused container a digest of the application and the configuration, so reuse keeps finding it. Three labels, `io.vidocq.dev`, `io.vidocq.dev.app` and `io.vidocq.dev.service`, let `docker ps --filter label=io.vidocq.dev` list them. A provider of its own names its containers the same way with `DevContainers.name` and `DevContainers.labels`, from the SPI. xref:dev-services.adoc#container-names[Container names and labels].
```
with:
```asciidoc
* **A query editor for JDQL in the dev console** [.tag-new]#NEW# — the statement of the *Mansart Data* panel's *JDQL* tab, in *Query*, *Update / Delete* and *Export CSV*, is typed in the dev console's code editor in a new query mode: keywords, functions, the entity, its attributes, strings, numbers and parameters each coloured; `Ctrl+Space` completes the entities after `FROM`, their attributes with their Java type and column in `SELECT`, `WHERE`, `ORDER BY` and `SET` — `FROM` written after the caret included — and a reference's attributes after `project.`; an unknown entity or attribute, a path through an attribute that is no reference, an unterminated string or an unbalanced parenthesis is underlined before anything runs; *Format* puts each clause on its line. Its `params` become a JSON editor whose schema follows the statement as you type: `WHERE status = :status` offers `status` with the values of its enum and flags it missing. A panel publishes such a vocabulary with `DevConsolePanel.languages()` (or `LivePanel`'s), `PanelLanguage` records the page fetches once at `GET /api/language/<panel>/<id>` in a `dev` launch, and names it from a `json` argument's schema with `"contentMediaType": "text/x-query"`, `"x-language"` and `"x-parameters-of"`. xref:dev-console.adoc#query-editor[Query editor], xref:modules/vidocq-runtime-extensions.adoc#mansart-data-jdql[A JDQL console for Mansart Data].
* **Tables and a SQL editor for each Mansart pool, and a table of rows in the dev console** [.tag-new]#NEW# — under `mvn vidocq:dev`, the *Mansart pools* panel gives each pool a tab: *Tables* lists its tables and views with their columns' number, *Describe* a table's columns, types, keys and indexes, *Preview* its first rows, *Query* runs a statement that only reads in a transaction always rolled back, and *Execute* any one statement, asked first and rolled back unless committed; 30 seconds at most, `:name` parameters bound from `params`, the pool's URL and password never shown. The SQL is typed in the query editor, which now reads SQL: aliases, joins, several tables, quoted names, comments, the columns of every table in scope completed with their table, an unknown alias flagged and an ambiguous column warned, `LEFT JOIN … ON` formatted on one line; its vocabulary, the language `sql-<pool>`, is read from `DatabaseMetaData` at boot. A result of rows is now a table — the type of each column under its name, `NULL` dimmed, *Table* / *JSON* and *Copy as CSV* — which the *JDQL* tab's *Query* answers too; a panel answers one with `PanelAction.ActionResult.rows(…)`. xref:modules/vidocq-runtime-extensions.adoc#mansart-pool-sql[Tables and a SQL editor], xref:dev-console.adoc#rows-table[A table of rows], xref:dev-console.adoc#query-sql[SQL].
* **Dev service containers are named `vidocq-dev-…`** [.tag-new]#NEW# — the PostgreSQL and Keycloak dev services used to leave their containers to Docker's random names; each is now `<prefix><application>-<service>[-<datasource>]-<suffix>`, such as `vidocq-dev-mcp-tasks-server-postgres-3f9a2c01`, the prefix `vidocq-dev-` unless `vidocq.dev.container-prefix` says otherwise. The suffix is random, or for a reused container a digest of the application and the configuration, so reuse keeps finding it. Three labels, `io.vidocq.dev`, `io.vidocq.dev.app` and `io.vidocq.dev.service`, let `docker ps --filter label=io.vidocq.dev` list them. A provider of its own names its containers the same way with `DevContainers.name` and `DevContainers.labels`, from the SPI. xref:dev-services.adoc#container-names[Container names and labels].
```

- [ ] **Step 2: Check the badges, the anchors and the SPI's build**

```bash
cd $VIDOCQ/docs/en/modules/ROOT/pages && grep -c "^=== Tables and a SQL editor \[.tag-new\]#NEW#$" modules/vidocq-runtime-extensions.adoc; grep -cE "^== A table of rows \[.tag-new\]#NEW#$|^=== SQL \[.tag-new\]#NEW#$" dev-console.adoc; grep -c "Tables and a SQL editor for each Mansart pool" whats-new.adoc; grep -o "xref:[a-z/.-]*#\(rows-table\|query-sql\|mansart-pool-sql\)" whats-new.adoc dev-console.adoc modules/vidocq-runtime-extensions.adoc | sort -u | wc -l
```
Expected: `1`, `2`, `1`, then `6` (each anchor is cross-referenced from another page). Then:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-devconsole-spi install 2>&1 | grep -E "Tests run:|BUILD" | tail -2
```
Expected: `Tests run: 71, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
docs(devconsole): a table of rows, SQL in the query editor, and each pool's tab

dev-console.adoc gains A table of rows, the query editor's SQL and SQL's options for a panel's author; the Mansart
pools section gains Tables and a SQL editor - the five actions, their transactions, parameters, values, failures and
the sql-<pool> language; JDQL's Query is said to answer rows; PanelLanguage's Javadoc gives SQL's options; and
whats-new.adoc an entry.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-devconsole-spi/src/main/java/io/vidocq/runtime/spi/devconsole/PanelLanguage.java docs/en/modules/ROOT/pages/dev-console.adoc docs/en/modules/ROOT/pages/modules/vidocq-runtime-extensions.adoc docs/en/modules/ROOT/pages/whats-new.adoc && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---
### Task 13: Verification — builds, a consumer, and the pools' tab and the table of rows in Chrome (§5)

**Files:** none changed, unless a check fails (then fix in the owning task's files, re-run its tests, and commit with a
`fix(devconsole): …`, `fix(mansart-pool): …` or `fix(mansart-data): …` message following the Global Constraints).

**Interfaces:**
- Consumes: everything above; the test application `$LC4JCDI/mcp-tasks-server` (PostgreSQL through the Vidocq dev
  service: Docker must run). Its Flyway scripts (`src/main/resources/db/migration/V1__create_tasks.sql`,
  `V2__seed_tasks.sql`) make, in the schema `public`: `tasks` — `id` (bigint identity, primary key), `title`
  (varchar 200), `description`, `project`, `status` (`OPEN`/`DONE`), `priority`, `due_date` (date), `created_at`,
  `updated_at`, `completed_at` (timestamptz), indexes `tasks_status_due_idx (status, due_date)` and
  `tasks_project_idx (project)`, 8 rows; `task_events` — `id`, `task_id`, `type`, `occurred_at`, `detail`, index
  `task_events_task_idx (task_id, id)`, no foreign key; and Flyway's `flyway_schema_history`. The entities of Mansart
  Data are `Task` and `TaskEvent`. The helper `$SCRATCH/dev-run.sh` (`dev-run.sh <project dir> <log>`: `vidocq:dev`
  of that project, the application on 18093, the console on 18094, no debugger, no continuous testing).
- Produces: nothing; a report.

- [ ] **Step 1: The five modules' whole builds, installed for the test application**

```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu install -pl vidocq-runtime-devconsole-spi,vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension,vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev,vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-pool-extension-dev 2>&1 | grep -E "Tests run:.*Fail|FAIL|BUILD" | grep -v " in io" | tail -6
```
(`timeout: 600000`.) Expected: `Tests run: 71`, `Tests run: 502`, `Tests run: 31`, `Tests run: 167`, each
`Failures: 0, Errors: 0`, and `BUILD SUCCESS`.

- [ ] **Step 2: A consumer, and the page's scripts**

```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu clean verify -pl vidocq-runtime-examples/vidocq-runtime-mansart-h2-example -Dvidocq.checkpom.failOnMissing=false 2>&1 | grep -E "Tests run:|FAIL|BUILD" | tail -4
```
(`timeout: 600000`.) Expected: `BUILD SUCCESS`, no failure.

```bash
cd $VIDOCQ && P=vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole && for f in console editor editor-core; do cp $P/$f.js $SCRATCH/check-$f.mjs && node --check $SCRATCH/check-$f.mjs && echo "$f parses"; done; git diff HEAD~12 -U0 -- '*.java' '*.js' '*.css' | grep '^+' | grep -v '^+++' | awk 'length > 121' | head -3
```
Expected: `console parses`, `editor parses`, `editor-core parses`, and no line after them (no added line of code
past 120 characters; `awk` counts the `+`). If `awk` counts bytes on this machine, a line holding `…` or `·` may show
while it is within 120 characters: count it with `python3 -c 'import sys; print(len(sys.argv[1]))' '<line>'`.

- [ ] **Step 3: Free ports, the test application's state, the helper**

```bash
lsof -nP -iTCP:18093 -sTCP:LISTEN; lsof -nP -iTCP:18094 -sTCP:LISTEN; echo checked
```
Expected: only `checked`. If a port is taken, STOP and ask the user (never kill what this plan did not start).

```bash
git -C $LC4JCDI status --short > $SCRATCH/lc4jcdi-status-before.txt; ls -l $SCRATCH/dev-run.sh
```
If `ls` says the helper does not exist, create it with the Write tool, exactly:
```bash
#!/usr/bin/env bash
# dev-run.sh <project dir> <log>: vidocq:dev of that project, the application on 18093, the console on 18094,
# no debugger, no continuous testing. Runs in the foreground: start it in the background and stop it with TaskStop.
set -u
cd "$1" || exit 1
export JAVA_HOME=$JAVA25
export PATH="$JAVA_HOME/bin:$PATH"
exec mvn -nsu vidocq:dev -Dvidocq.chappe.listener.default.host=127.0.0.1 \
  -Dvidocq.chappe.listener.default.port=18093 -Dvidocq.devconsole.port=18094 \
  -Dvidocq.dev.debug=false -Dvidocq.dev.continuousTesting=false > "$2" 2>&1
```
(`$JAVA25` in the file is the Java 25 home written out.)

- [ ] **Step 4: Start `vidocq:dev`**

Run with the Bash tool and `run_in_background: true` (the only background Maven run of this plan):
```bash
bash $SCRATCH/dev-run.sh $LC4JCDI/mcp-tasks-server $SCRATCH/mcp-tasks-dev.log
```
Wait with the Monitor tool on an until-loop (never a foreground `sleep`):
`until grep -qE "Vidocq dev console: http://127.0.0.1:18094/|BUILD FAILURE|Exception in thread" $SCRATCH/mcp-tasks-dev.log; do sleep 2; done`.
Then `grep -E "Dev tools:|Mansart pools|BUILD FAILURE" <that log> | head` — expected: the console,
`vidocq-runtime-mansart-pool-extension-dev` and `vidocq-runtime-mansart-data-extension-dev` among the dev tools, no
failure, and no WARNING `Mansart pools: the tables of pool …`.

- [ ] **Step 5: The pools' tab and the table of rows in Chrome (§5) — a manual check, no code**

Load the tools in one call: `ToolSearch("select:mcp__claude-in-chrome__tabs_context_mcp,mcp__claude-in-chrome__navigate,mcp__claude-in-chrome__computer,mcp__claude-in-chrome__read_page,mcp__claude-in-chrome__tabs_create_mcp,mcp__claude-in-chrome__tabs_close_mcp,mcp__claude-in-chrome__javascript_tool,mcp__claude-in-chrome__find,mcp__claude-in-chrome__form_input,mcp__claude-in-chrome__read_console_messages,mcp__claude-in-chrome__read_network_requests")`.
Remember: the editor draws in an animation frame, which a hidden MCP tab only runs when a screenshot is taken; take a
screenshot or a zoom before reading what it drew. On macOS the undo key is `cmd+z`. Tick each item, and screenshot
the ones marked (S).

1. `tabs_context_mcp`, `tabs_create_mcp`, `navigate` to `http://127.0.0.1:18094/`. Run with `javascript_tool`:
   `Object.defineProperty(document, 'hidden', {value: false, configurable: true}); Object.defineProperty(document, 'visibilityState', {value: 'visible', configurable: true}); document.dispatchEvent(new Event('visibilitychange')); 'visible'`.
   `read_console_messages` with pattern `error|Error`: nothing from `console.js`, `editor.js` or `editor-core.js`.
2. *Mansart pools*: sub-tabs *Monitoring* then `@Default`. In `@Default`, the combo lists *Tables*, *Describe*,
   *Preview*, *Query*, *Execute*. `read_network_requests` with pattern `api/language`: none yet.
3. *Tables* → run: `3 tables`; a table of `schema`, `name`, `kind`, `columns`: `public` · `flyway_schema_history` ·
   `TABLE` · 10, `public` · `task_events` · `TABLE` · 5, `public` · `tasks` · `TABLE` · 10, each row ending with a
   *Describe* and a *Preview* button, those two columns' headers blank; the numbers right-aligned. (S)
4. *Describe* on the `tasks` row: the form switches to *Describe*, `tasks` selected, nothing sent. Run it: 10 rows —
   `id` · `int8` · `false` · `NULL` (dimmed) · `1` · `NULL`; `title` · `varchar(200)` · `false`; `due_date` · `date` ·
   `true`; `created_at` · `timestamptz`…; the line `10 columns · indexes: tasks_pkey (id), tasks_project_idx
   (project), tasks_status_due_idx (status, due_date)`. (S)
5. *Preview* on the `tasks` row, `Rows` set to `3`: `first 3 rows in … ms`; `completed_at` `NULL` dimmed on an open
   task, `created_at` an ISO time with its offset (`…Z` or `…+00:00`), `description` cut with `…` when it is past 200
   characters (none of the seed's is: hover a cell, no title then). The header stays in view while the box scrolls
   (shrink the window or the result to see it).
6. In the bar: *JSON* shows the same body in the JSON viewer, its *Expand all* / *Collapse all* / *Copy* next to the
   switch; *Table* comes back. Leave *JSON* on, wait two polls (two seconds): still *JSON*. *Copy as CSV*: the button
   says `Copied` (or `Clipboard refused`); with `javascript_tool`
   `navigator.clipboard.readText().then((t) => t.split('\r\n')[0])` reads the header line `id,title,description,…`
   when the browser lets the page read it.
7. *Query*: `sql` is the query editor, `params` a JSON editor holding `{}`. `read_network_requests` with pattern
   `api/language`: one request, `/api/language/mansart-pool/sql-default`, `200`. Type
   `select t.title, e.type from tasks t join task_events e on e.task_id = t.id where t.status = :status`: keywords,
   `tasks` and `task_events` and the aliases `t` and `e` in the target colour, the columns in the attribute colour —
   `e.task_id` and `t.id` too, though right of `=` —, `:status` a parameter; `params` flags the missing `status`. (S)
8. Clear it; type `select * from ` and press `ctrl+space`: `flyway_schema_history`, `task_events`, `tasks`, each
   `table · public`. Accept `tasks`, type ` t join task_events e on ` and `ctrl+space`: `t` (`alias of tasks`), `e`,
   `tasks`, `task_events`, then the columns of `tasks` (`int8 · column · tasks`…), then those of `task_events`, the
   functions, the keywords. Type `t.` and `ctrl+space`: the ten columns of `tasks` only. (S)
9. `select t.titel from tasks t`: `titel` underlined red, tooltip `unknown column titel of tasks`; `select x.title from
   tasks t`: `x` underlined, `unknown table or alias x`; `select id from tasks t join task_events e on e.task_id =
   t.id`: `id` underlined orange, `id is in tasks t and task_events e`, the line under the editor `1 warning`.
10. `select t.title, t.status from tasks t where t.status = :status` with `params` `{"status": "OPEN"}` → *Query*: a
    table of `title` (`varchar`) and `status`, `… rows in … ms`. In the editor, `"` typed after `select ` gives `""`,
    the caret inside, and the list offers no keyword; `--` starts a comment, dimmed.
11. A write in *Query*: `delete from tasks` → `Query only reads: use Execute`, nothing sent to the database (the line is
    red at once). `WITH d AS (DELETE FROM tasks WHERE id = 1 RETURNING id) SELECT * FROM d` → an error whose line reads
    `ERROR: cannot execute DELETE in a read-only transaction` (Ruling 16); then `select count(*) from tasks` → `8`.
    `select 1; select 2` → `one statement at a time`.
12. *Execute*: `update tasks set title = 'changed' where id = 1`, *Transaction* `rollback` → the inline confirmation
    `Runs this SQL on @Default. A DDL statement (CREATE, ALTER, DROP, TRUNCATE…) may be committed by the database
    itself whatever is chosen.`; *Confirm* → `1 row · rolled back`. *Query* `select title from tasks where id = 1`:
    the title is the seed's, unchanged. *Exchange* holds `sql`, `params`, `"transaction": "rollback"`.
13. *Format*: type `select t.title, e.type from tasks t left join task_events e on e.task_id = t.id where
    t.status='OPEN' and e.type is not null order by t.title`, press *Format*:
    ```
    SELECT t.title, e.type
    FROM tasks t
    LEFT JOIN task_events e ON e.task_id = t.id
    WHERE t.status = 'OPEN'
      AND e.type IS NOT NULL
    ORDER BY t.title
    ```
    `cmd+z` restores the text. (S)
14. *Mansart Data* → *JDQL* → *Query*, `FROM Task ORDER BY id`: a table, its columns `id` (`integer`), `title`
    (`string`), …, `status` (`string`), `dueDate` (`date`), `createdAt` (`date-time`), `NULL` dimmed where
    `completedAt` is empty, `8 rows in … ms`; *Table* / *JSON* and *Copy as CSV* there too. `SELECT COUNT(this) FROM
    Task` still answers `8` as JSON. (S)
15. Run with `javascript_tool` `document.documentElement.dataset.theme = "dark"; 'dark'`: the table, its dimmed types
    and `NULL`, and the SQL comment are readable (S); then `delete document.documentElement.dataset.theme; 'auto'`.
16. `tabs_close_mcp` on the tab this step opened.

- [ ] **Step 6: Stop what this plan started**

Stop the background task of Step 4 with `TaskStop` (its id), then re-run the `lsof` command of Step 3: both ports free.
```bash
git -C $LC4JCDI status --short | diff - $SCRATCH/lc4jcdi-status-before.txt && echo "test app untouched"
```
Expected: `test app untouched`.

- [ ] **Step 7: Final state**

```bash
git -C $VIDOCQ status --short
```
```bash
git -C $VIDOCQ log --oneline -13
```
Expected: the tree clean (but for files that were untracked before this plan), this plan's twelve commits on
`feat/devconsole-sql` after `docs(spec): table views and a SQL editor in Mansart pools, and a table for rows`. Nothing
pushed. Report: the branch to push, the screenshots, and any item of Step 5 that did not behave as written.

---
## Self-review (done while writing)

- **Spec coverage.** §1 in scope: `ROWS` and its table (Tasks 1-3), the query mode's SQL options (4-7), the pools'
  five actions and their language (8-10), JDQL's *Query* (11), the docs (12); out of scope respected (sub-queries
  and `UNION` run and are left unchecked, no reload of the tables without a dev reload — Tables reads them again but
  Describe, Preview and the language keep the boot's —, no cell edited in place, one statement per call, no change to
  Mansart). §2.1 (1; Ruling 13). §2.2 every bullet (2, 3; Rulings 1-4). §3: scope, aliases, several targets, the
  first target still JDQL's (4; Rulings 5, 6); quoted identifiers and the keystrokes' quotes (4; Ruling 12);
  completion (5; Ruling 10); diagnostics, the unknown alias, the ambiguous warning, qualified targets (6; Rulings 7-9);
  `parameters()` through an alias (4); formatting (7; Ruling 11). §4.1 the table and the confirmation (9; Rulings 1,
  14, 15). §4.2 every bullet (8; Rulings 16-19). §4.3 (10; Ruling 20), the `sql` and `params` properties (9). §4.4
  (11; Ruling 21). §5: SPI (1), GraalJS (2, 4-7), PageTest (3, 4), pools on H2 (8-10), Mansart Data (11), browser
  (13). §6 (12). §7: the Review Focus.
- **Placeholders.** None: every code step has its code, every run step its command and its Expected line.
- **Types and names.** `ActionResult.ROWS`, `Column(name, type)`, `rows(summary, columns, rows, more)` (1) are what
  `SqlRunner`, `PoolActions` (8, 9) and `ResultJson.rows` (11) call; `readRows`/`rowsCsv` (2) are what `rowsBody`
  imports through `editor.js`'s `export … from` (3); `read()`'s `scope`, `comments`, token `depth`/`position`/`quoted`
  (4) are what `sqlItems` (5), the diagnostics (6) and `formatQuery` (7) read; `PoolActions.languageId()` (9) is the
  `x-language` of the `sql` property (9) and the id `PoolsLivePanel.languages()` publishes (10); `PoolMetadata`
  (9) is what `SqlLanguage.json` reads (10); `SqlRunner.ROLLBACK`/`COMMIT` (8) are *Execute*'s allowed values (9).
- **Verified while planning.** The code blocks of this very plan were applied, task by task, by a script that reads
  its `Create`/`In …, replace` steps, to the files of `HEAD` (the five modules' `target/` removed first, so that no
  stale class hid a RED). Every RED and GREEN ran with the plan's commands on Java 25 and ended as its Expected line
  says: Task 1 `cannot find symbol`, then 71; Task 2 5 errors, then 5; Task 3 4 failures and 1 error, then 33 and
  477; Task 4 10 failures and 1 error, then 131; Task 5 3 failures, then 51; Task 6 4 failures, then 57; Task 7 4
  failures, then 61 and 502; Task 8 `cannot find symbol`, then 17; Task 9 likewise, then 26; Task 10 likewise, then
  31; Task 11 3 failures and 1 error, then 19, 167 and the example's 11; Task 12's checks `1`, `2`, `1`, `6` and 71.
  The 33 files the plan leaves were byte-identical to the planner's own final state; `node --check` accepted
  `console.js`, `editor.js` and `editor-core.js`; no new line of code passes 120 characters; the H2 example's
  `DevConsoleSnapshotTest` passed (10). The page itself was not run in a browser and no PostgreSQL was started (Task
  13 is their first run). The repository was then restored to `HEAD`.
- **Note for the executor.** If a `target/` of a module holds classes or page files built from this plan's code
  before the task that adds them (an interrupted run), a RED may not show: run that RED with `clean test`. Task 1
  installs the SPI and Tasks 3 and 7 the console, as the modules after them compile against what is installed.
