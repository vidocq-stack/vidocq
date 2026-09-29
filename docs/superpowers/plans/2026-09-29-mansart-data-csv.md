# CSV export and import for Mansart Data in the dev console — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Apply the fenced code blocks of a step in order; a block that starts with `package` is a whole new file (prepend the license header of the Global Constraints); an edit is shown as the exact text to find, then the text that replaces it.

**Goal:** In a `dev` launch, let a developer export the result of a JDQL query as a CSV file the browser downloads,
and import a CSV file into an entity, its rows saved in a transaction rolled back (a dry run, the default) or
committed — two actions of the Mansart Data panel's *JDQL* tab.

**Architecture:**
- **Console SPI** (`vidocq-runtime-devconsole-spi`): `PanelAction.ActionResult.CSV = "text/csv"`, a third body type.
  The console extension passes a result's content type through untouched (`ConsoleActions` only serialises it), so
  nothing else changes server side.
- **Page** (`vidocq-runtime-devconsole-extension`): a `text/csv` body is shown as text with a *Download* button (a
  `Blob`, no request); a flat-form textarea whose schema says `"contentMediaType": "text/csv"` gets a *Choose file*
  input that reads a local file of at most 60 KiB into it.
- **`-dev` module:** `Csv` (a hand-written RFC 4180 reader and writer, UTF-8 byte counting); `CsvActions` (the
  actions `jdql.export` and `jdql.import`), which reuses `JdqlActions`' entity lookup, model and
  `RepositoryRuntime` resolution (made package-private), runs an export through the existing `JdqlRunner` seam and
  saves through a new `EntitySaver` seam (`EntitySaver.MANSART` calls `RepositoryRuntime.save`). `EntityJson` gains
  the export header (`names`), a reference's id (`idOf`), the import columns (`columns`) and a row builder
  (`textRows`); `Scalars.fromText` converts a field's text as the page's JSON would be. `RepositoryActions.build`
  gains a 9-argument overload that appends the CSV actions to the JDQL tab; `CatalogueLivePanel` passes
  `EntitySaver.MANSART`.
- **Database test:** the mansart-h2 example's `DevConsoleSnapshotTest` exports and re-imports the products.

**Tech Stack:** Java 25, JPMS, Maven 3.9, Jakarta Data 1.0.1, Mansart Data 0.4.0-SNAPSHOT (`JdqlExecutor`,
`JdqlResult`, `RepositoryRuntime.save`, `EntityModels.of`; read only), Jakarta CDI 4.1, Jakarta Transactions 2.0.1
(optional), the Vidocq dev console SPI and page (vanilla JS), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-09-29-mansart-data-csv-design.md` (binding). Section numbers below refer to
it. Read it first, then this plan's *Rulings*. Context: the JDQL console
(`docs/superpowers/specs/2026-09-29-mansart-data-jdql-console-design.md`,
`docs/superpowers/plans/2026-09-29-mansart-data-jdql-console.md`), merged as #155.

## Global Constraints

- **Repository and branch.** Vidocq: `/Users/yblazart/projects/perso/vidocq/vidocq`, branch `feat/mansart-data-csv`
  (checked out; never switch it). **No Mansart change** (spec §1): Mansart is only read, from `~/.m2`
  (`io.vidocq.mansart:*:0.4.0-SNAPSHOT`). The checkout `/Users/yblazart/projects/perso/vidocq/mansart` is never
  touched.
- **Test app** (read only, except the rows of the manual check, which it deletes again):
  `/Users/yblazart/projects/perso/vidocq-tools/lc4jcdi-on-vidocq/mcp-tasks-server`. Never touch the uncommitted
  `.run/*.xml` files of its parent repo `lc4jcdi-on-vidocq`.
- **Maven.** `mvn -nsu` only (never `./mvnw` or `mvnw`), Java 25: every Maven command is prefixed with
  `JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem` (the shell's default Java is 21). Module-scoped:
  `mvn -nsu -q -pl <module> test -Dtest=<Class>`. A module whose jar another module compiles against is installed
  (`mvn -nsu install -pl <module>`) before that other module is built with `-pl`. After a change of the `-dev`
  module, an example is built with `mvn -nsu clean verify` (its generated sources are stale otherwise). Give long
  builds the Bash tool's `timeout: 600000`. If a hook redirects a Maven call to the context-mode `ctx_execute`
  shell, run it there and print only the tail. No shell variables in the commands: every path is spelt out.
  Maven is never put in the background, except Task 9's `vidocq:dev`, through the existing script.
- **Paths** (all checked while planning; the commands spell them out):
  - `SPI` = `vidocq-runtime-devconsole-spi`
  - `CON` = `vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension`
    (page `CON/src/main/resources/META-INF/resources/devconsole/`, `PageTest` in
    `CON/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/`)
  - `DEV` = `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev`
    (sources `DEV/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/`, tests in the same
    package under `DEV/src/test/java/...`; `.../dev/` below means that package directory)
  - `EX` = `vidocq-runtime-examples/vidocq-runtime-mansart-h2-example`, `PET` = `vidocq-runtime-examples/vidocq-runtime-petstore-example`
  - docs: `docs/en/modules/ROOT/pages/` (`modules/vidocq-runtime-extensions.adoc`, `dev-console-panels.adoc`,
    `whats-new.adoc`)
- **Scratch directory** (logs, files of the manual check):
  `/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad`.
- **Ports:** only 18090-18099, checked free with `lsof -nP -iTCP:<port> -sTCP:LISTEN` first; never 8080 or 8888.
  Never kill a process this plan did not start.
- **Commits.** Write the message with the Write tool to
  `/Users/yblazart/projects/perso/vidocq/vidocq/.git/PLAN_COMMIT_MSG`, then
  `git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG` (never `-m`, never `-s`), a conventional
  message. Stage explicit paths only (never `git add -A` / `git add .`). Every message ends with exactly:
  ```
  Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
  ```
  **Never push.**
- **Code style.** English; 120 columns; Javadoc density like the surrounding files. The repository has no Spotless,
  Checkstyle or `-Werror` (checked in the root, `vidocq-runtime-extensions` and the DEV poms): there is no
  formatting command to run; follow the surrounding style. Every **new** `.java` file starts with this license
  header, verbatim (the one every file of the DEV module carries); the code blocks of this plan start at `package`
  and omit it: prepend it.

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
```
- **Dependencies.** None new: the CSV reader and writer are hand-written (spec §5). The DEV module's
  `module-info.java` does not change (`io.vidocq.mansart.data.core` comes through the Mansart Data extension's
  `requires transitive`).
- **Limits, verbatim from the spec:** body cap `MAX_CONTENT` 256 KiB unchanged; *Choose file* refuses a file larger
  than 60 KiB (`the file is larger than 60 KiB`); the console's 64 KiB request limit stays; an export longer than
  256 KiB in **UTF-8 bytes** is refused (`larger than 256 KiB: narrow the query`); an import reads at most 5000 rows
  (`more than 5000 rows: split the file`); the download is named `<action id>-<yyyyMMdd-HHmmss>.csv`, the id's
  characters outside `[A-Za-z0-9._-]` replaced by `-`, saved as a `Blob` of type `text/csv;charset=utf-8`.

## Rulings on the spec

1. **`transaction` is a separate string argument** of `jdql.import` (`rollback`, `commit`; `commit` only without a
   `TransactionManager`), as in `jdql.write` (#155), not a member of the json argument that §4 lists: the page then
   offers it as a list of the modes the runner has. The json arguments are named `statement` (export: `query`,
   `params`, `separator`, label *Statement*) and `file` (import: `entity`, `csv`, `separator`, label *File*).
   Absent, `transaction` is the runner's first mode (`rollback` when there is a manager).
2. **Where the code lives.** A sibling class `CsvActions` in the JDQL tab (spec §3/§4 name the ids `jdql.export` and
   `jdql.import`), reusing `JdqlActions`' entity lookup, model, runtime bean and `params` parsing, which become
   package-private (`JdqlActions.Refused` too). Saving goes through a new seam, `EntitySaver`, so that the tests need
   no database, as `JdqlRunner` does for queries. The tab's actions are, in order, *Query*, *Update / Delete*,
   *Export CSV*, *Import CSV*; the CSV pair counts against the panel's 128 actions (the repositories get 124).
3. **The header** (§4). A name the model has but an import cannot set — no setter, or a type `Scalars` does not
   convert, such as a `java.time.Year` — is refused as `header: <name> cannot be imported; attributes: <columns>`;
   a joined attribute or an unknown name as the spec says, `header: unknown attribute <name>; attributes:
   <columns>` (`<columns>` the settable attributes in model order, comma-separated). An empty name:
   `header: column <k> has no name`; a name twice: `header: <name> twice`. Names are compared exactly (no trimming).
4. **Other import refusals.** Blank `csv`: `csv: missing`; a header and no row: `no row: the file has a header
   only`; the entity by the catalogue's name (`entity.name()`, the enum of the form), else `unknown entity <name>;
   entities: <names>`; a field count `line <n>: 1 field, the header has 4` (singular for one), `line <n>: 3 fields,
   the header has 4`. A conversion `line <n>, <attribute>: <why>` with `Scalars`' own `<why>` (`not a number`, `not an
   integer`, `no constant URGENT in Level`, `null is not allowed for int`, `not an ISO date`…).
5. **Reading a field's text** (§4, §5): a number from `new BigDecimal(text)` (so `2,50` is `not a number`, `" 3"` is
   `not an integer`: fields are never trimmed), a boolean from exactly `true` or `false`, everything else as the
   string the page's JSON would carry; then `Scalars.fromJson`, the same conversion as #154.
6. **The CSV reader** (§5). An empty line is a record of one `null` field (RFC 4180); the one line end at the very
   end of the text only closes the last record. A lone `\r` is text. A `"` inside an unquoted field is text. Text
   after a closing quote other than a separator or a line end: `line <n>: text after a closing quote`. An
   unterminated quote is reported at the line where it opened. A record's line is the line of the text it starts on,
   line ends inside quoted fields counted.
7. **Export values** (§3, §5): an entity row is `EntityJson.toJson`'s values in model order (a reference as its id,
   an unsupported type as its `toString()`); a projection's or aggregate's cell that is an entity of the catalogue is
   its id, any other value `Scalars.toJson`'s; then `BigDecimal.toPlainString()` or `toString()`. `Count` is one
   column `count`, `Value` one column `value` (`null` an empty field). The export runs as *Query* does: in a
   transaction always rolled back when there is a manager, in none otherwise.
8. **Summaries.** Export: `<no row | 1 row | N rows> · <size> in <ms> ms`, `<size>` = `N B` under 1024 bytes, else
   one decimal `N.N KiB` (`Locale.ROOT`). Import: `<1 row | N rows> saved · rolled back` / `· committed`; a save that
   throws: `line <n>: <Class>: <message>` masked and cut (`Failures.text`), the `text/plain` body the same text.
9. **Without a `TransactionManager`** a `commit` import saves each row as it goes (Mansart autocommits): a failing
   save cannot roll the earlier rows back, so the summary adds ` · 1 row before it stays committed` /
   ` · N rows before it stay committed`.
10. **Details** (§3, §4): export `{"entity", "query", "params", "separator"}`, import `{"entity", "rows",
    "separator", "transaction"}` (`rows` the rows read, `null` before reading), `null` for what a refusal stopped
    before.
11. **The page.** *Download* is offered in both outlets (a group tab's result block and the panel's own bar), so
    `resultBlock` and `inlineOutlet`/`resultOutput` take the action id. The *Choose file* input is disabled while the
    form is sent, as the other fields are. A file read replaces the textarea's value (the browser normalises its line
    ends to `\n`, which the reader accepts).
12. **Manual check** (§7): the spec asks for an import in `commit` on the test app, whose H2 database is a file: the
    check marks its rows (`… (csv import)` in their title), counts, and deletes exactly those rows again in `commit`,
    ending with the count it started from.

## Review Focus

- **A quoted field holding line ends, then an error on a later row:** the error names the line of the *text* the
  bad row starts on, not its record index (`line 4, gizmo: not an integer` after a two-line label on line 2). →
  `CsvTest.aRecordsLineIsWhereItStartsLineEndsInQuotedFieldsCounted` (Task 3),
  `CsvActionsTest.aConversionErrorNamesTheLineOfTheTextAndTheAttribute` (Task 5).
- **An entity attribute of a type `Scalars` does not convert** (a `java.time.Year`): exported as its text, refused
  at import in the header with the attributes that can be imported, nothing saved. →
  `CsvActionsTest.aReferenceIsItsIdAJoinedAttributeIsLeftOutAnUnsupportedTypeIsItsText` (Task 4),
  `CsvActionsTest.theHeaderNamesAttributesThatCanBeSetOnceEach` (Task 5).
- **The id on upsert with generated ids:** an empty id field reaches `save` as `null` (an insert, id generated), a
  given id as itself; re-importing an export with its ids emptied adds rows. →
  `CsvActionsTest.eachRowIsAnEntitySavedInOrderInOneTransactionRolledBackByDefault` (Task 5),
  `DevConsoleSnapshotTest.exportsAndImportsCsvFromTheConsole` (Task 7).
- **The 256 KiB limit counted in UTF-8 bytes, not characters:** 1400 rows of accented names (~161 K characters,
  ~301 KB) are refused although `ActionResult` would hold them; the same rows in ASCII pass. →
  `CsvActionsTest.theLimitIs256KiBOfUtf8NotOfCharacters` (Task 4), `CsvTest.theWriterRefusesARecordThatWouldPassItsLimitInUtf8Bytes` (Task 3).
- **`;` as separator with decimal commas:** an export with `;` keeps `2.50` unquoted and quotes a text holding `;`;
  an import of `2,50` is refused with its line and attribute rather than read as 250 or 2. →
  `CsvActionsTest.aProjectionIsItsColumnsAndASemicolonKeepsDecimalPointsUnquoted` (Task 4),
  `CsvActionsTest.aConversionErrorNamesTheLineOfTheTextAndTheAttribute` (Task 5),
  `ScalarsFromTextTest.aCsvFieldThatDoesNotConvertIsRefusedNamingItsColumn` (Task 5).

---

## File Structure

| File | Responsibility |
|---|---|
| `SPI/.../PanelAction.java` (modify), `ActionResultTest.java` (modify) | `ActionResult.CSV`, accepted as a content type |
| `CON/.../console.js`, `console.css` (modify), `PageTest.java` (modify) | *Download* for `text/csv`; *Choose file* for `contentMediaType: text/csv` |
| `DEV/.../Csv.java` (new), `CsvTest.java` (new) | RFC 4180 reader and writer, UTF-8 length |
| `DEV/.../JdqlActions.java` (modify) | lookups shared with `CsvActions`: `parse`, `params`, `entity`, `named`, `model`, `runtime`, `names`, `Refused` |
| `DEV/.../EntityJson.java` (modify), `EntityJsonTest.java` (modify) | `names`, `idOf` (export); `columns`, `textRows` (import) |
| `DEV/.../Scalars.java` (modify), `ScalarsFromTextTest.java` (new) | `fromText`: a CSV field's text converted |
| `DEV/.../EntitySaver.java` (new) | the seam: saves one entity; `MANSART` = `RepositoryRuntime.save` |
| `DEV/.../CsvActions.java` (new), `CsvActionsTest.java` (new) | `jdql.export`, `jdql.import`: arguments, refusals, run, CSV, save, history |
| `DEV/.../RunFixtures.java` (modify) | `Slot`, an entity with a `Year` (a type the console cannot convert) |
| `DEV/.../RepositoryActions.java` (modify) | 9-argument `build` with an `EntitySaver`: the CSV actions after the JDQL ones |
| `DEV/.../CatalogueLivePanel.java` (modify), `CatalogueLivePanelTest.java` (modify) | passes `EntitySaver.MANSART` |
| `EX/.../DevConsoleSnapshotTest.java` (modify) | export then import against H2 |
| docs (modify) | `[#mansart-data-csv]`, `dev-console-panels.adoc`, `whats-new.adoc` |

**Deviation from the suggested decomposition** (six tasks): the wiring into the panel (Task 6) is its own task so that
Tasks 4 and 5 test `CsvActions` directly without churning the panel's action counts twice; the documentation of the
extension (Task 8) and the final verification with the manual Chrome check (Task 9) are separate tasks, as in #155.

---

### Task 1: The console SPI — a `text/csv` result (spec §2)

**Files:**
- Modify: `vidocq-runtime-devconsole-spi/src/main/java/io/vidocq/runtime/spi/devconsole/PanelAction.java:295-323`
- Test: `vidocq-runtime-devconsole-spi/src/test/java/io/vidocq/runtime/spi/devconsole/ActionResultTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `public static final String PanelAction.ActionResult.CSV = "text/csv"`; the record accepts it as
  `contentType`; the refusal message `a result's content type is text/plain, application/json or text/csv`. The SPI
  jar installed in `~/.m2`.

- [ ] **Step 1: Write the failing test**

In `ActionResultTest.java`, replace exactly:
```java
    @Test
    void aBodyOrDetailsPastTheLimitIsTruncatedAndSaysSo() {
```
with:
```java
    @Test
    void aBodyMayBeCsvWhichThePageOffersToDownload() {
        assertEquals("text/csv", ActionResult.CSV);

        ActionResult csv = new ActionResult("2 rows", ActionResult.CSV, "a,b\r\n1,2\r\n", false, null);

        assertEquals("text/csv", csv.contentType());
        assertEquals("a,b\r\n1,2\r\n", csv.body());
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new ActionResult("ok", "text/csv;charset=utf-8", "a", false, null));
        assertEquals("a result's content type is text/plain, application/json or text/csv", refused.getMessage());
    }

    @Test
    void aBodyOrDetailsPastTheLimitIsTruncatedAndSaysSo() {
```

- [ ] **Step 2: Run it to see it fail**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu -q -pl vidocq-runtime-devconsole-spi test -Dtest=ActionResultTest 2>&1 | tail -20
```
Expected: `COMPILATION ERROR`, `cannot find symbol` … `CSV`.

- [ ] **Step 3: The constant and its validation**

In `PanelAction.java`, replace exactly:
```java
         * @param contentType {@value #TEXT} or {@value #JSON}, which the page pretty-prints; {@code null} when there is
         *                    no body, {@value #TEXT} when there is one and none was given
```
with:
```java
         * @param contentType {@value #TEXT}, {@value #JSON}, which the page pretty-prints, or {@value #CSV}, which it
         *                    shows as text with a Download button; {@code null} when there is no body,
         *                    {@value #TEXT} when there is one and none was given
```
Then replace exactly:
```java
        /** A body the page pretty-prints. */
        public static final String JSON = "application/json";
```
with:
```java
        /** A body the page pretty-prints. */
        public static final String JSON = "application/json";
        /**
         * A CSV file: the page shows it as text with a Download button that saves it in the browser as
         * {@code <action id>-<yyyyMMdd-HHmmss>.csv}, without a request. The body cap is the same.
         */
        public static final String CSV = "text/csv";
```
Then replace exactly:
```java
            if (contentType != null && !TEXT.equals(contentType) && !JSON.equals(contentType)) {
                throw new IllegalArgumentException("a result's content type is " + TEXT + " or " + JSON);
            }
```
with:
```java
            if (contentType != null && !TEXT.equals(contentType) && !JSON.equals(contentType)
                    && !CSV.equals(contentType)) {
                throw new IllegalArgumentException("a result's content type is " + TEXT + ", " + JSON + " or " + CSV);
            }
```

- [ ] **Step 4: Run the module's tests, install it**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu install -pl vidocq-runtime-devconsole-spi 2>&1 | grep -E "Tests run:|FAIL|BUILD" | tail -5
```
(`timeout: 600000`.) Expected: `BUILD SUCCESS`, no failure.

- [ ] **Step 5: Commit**

Message:
```
feat(devconsole): a text/csv action result

PanelAction.ActionResult gains a third content type, CSV = "text/csv",
beside TEXT and JSON, with the same 256 KiB body cap. The console passes
it through as it does the others; the page shows it as text with a
Download button.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && git add vidocq-runtime-devconsole-spi/src/main/java/io/vidocq/runtime/spi/devconsole/PanelAction.java vidocq-runtime-devconsole-spi/src/test/java/io/vidocq/runtime/spi/devconsole/ActionResultTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 2: The page — *Download* for a CSV result, *Choose file* for a CSV field (spec §2)

**Files:**
- Modify: `CON/src/main/resources/META-INF/resources/devconsole/console.js`, `console.css`
- Test: `CON/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/PageTest.java`
- Modify: `docs/en/modules/ROOT/pages/dev-console-panels.adoc` (the json-argument section and `[#action-result]`)

**Interfaces:**
- Consumes: Task 1's `text/csv` content type (the page only reads the string).
- Produces: in the page, `isCsvType(type)`, `csvFileName(actionId, now)`, `downloadTools(text, actionId)`,
  `answerBody(answer, nodes, actionId)`, `fileChooser(target)`, `MAX_FILE_BYTES`; `resultBlock(result, actionId)`,
  `inlineOutlet(actionId)`, `resultOutput(result, actionId)`. The devconsole extension installed in `~/.m2`.

- [ ] **Step 1: Write the failing tests**

In `PageTest.java`, replace exactly:
```java
        assertTrue(script.contains("function inlineOutlet()"), "the panel's own bar keeps its look");
```
with:
```java
        assertTrue(script.contains("function inlineOutlet(actionId)"), "the panel's own bar keeps its look");
```
Then replace exactly:
```java
        assertTrue(script.contains("function resultBlock(result)"), "the result, apart from the form");
```
with:
```java
        assertTrue(script.contains("function resultBlock(result, actionId)"), "the result, apart from the form");
```
Then replace exactly:
```java
    @Test
    void aReplayColumnCellThatReplaysNoActionOfThePanelStaysText() {
```
with:
```java
    @Test
    void aCsvResultIsTextWithADownloadThatSendsNoRequest() {
        String script = file("console.js");

        assertTrue(script.contains(
                "const isCsvType = (type) => typeof type === \"string\" && type.startsWith(\"text/csv\");"),
                "PanelAction.ActionResult.CSV");
        assertTrue(script.contains("return { view: el(\"pre\", \"result-body\", answer.body), "
                + "tools: downloadTools(answer.body, actionId) };"), "shown as text, with Download");
        assertTrue(script.contains("new Blob([text], { type: \"text/csv;charset=utf-8\" })"),
                "saved as the browser holds it, in UTF-8");
        assertTrue(script.contains("String(actionId).replace(/[^A-Za-z0-9._-]/g, \"-\")"),
                "the action id, kept to the characters of a file name");
        assertTrue(script.contains("+ \"-\" + stamp + \".csv\""), "<action id>-<yyyyMMdd-HHmmss>.csv");
        assertTrue(script.contains("URL.revokeObjectURL(url)"), "the Blob is released");
        String download = script.substring(script.indexOf("function downloadTools("),
                script.indexOf("function answerBody("));
        assertFalse(download.contains("fetch("), "a download sends no request");
        assertEquals(2, Pattern.compile(Pattern.quote("answerBody(answer, result.nodes.body, actionId)"))
                .matcher(script).results().count(), "a group tab's result block and the panel's own bar");
    }

    @Test
    void aCsvTextareaGetsAChooseFileThatReadsALocalFileOfAtMost60KiB() {
        String script = file("console.js");
        String style = file("console.css");

        assertTrue(script.contains("if (definition.contentMediaType === \"text/csv\") chooser = fileChooser(input);"),
                "a textarea whose schema says text/csv, and only such a one");
        assertTrue(script.contains("file.type = \"file\";") && script.contains("file.accept = \".csv,text/csv\";"),
                "a native file input for CSV files");
        assertTrue(script.contains("const MAX_FILE_BYTES = 60 * 1024;"), "under the console's 64 KiB request");
        assertTrue(script.contains("\"the file is larger than 60 KiB\""), "said under the field");
        assertTrue(script.contains("reader.readAsText(chosen, \"UTF-8\");"), "read in the browser, as UTF-8");
        assertTrue(script.contains("target.value = typeof reader.result === \"string\" ? reader.result : \"\";"),
                "the file replaces the textarea's value");
        String chooser = script.substring(script.indexOf("function fileChooser("),
                script.indexOf("function jsonField("));
        assertFalse(chooser.contains("fetch("), "nothing is sent until the form is");
        assertTrue(script.contains("...choosers]) c.disabled = on;"), "disabled while the form is sent");
        assertTrue(rule(style, ".action .file-note {").contains("var(--crit)"), "the refusal in the error colour");
    }

    @Test
    void aReplayColumnCellThatReplaysNoActionOfThePanelStaysText() {
```

- [ ] **Step 2: Run them to see them fail**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu -q -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=PageTest 2>&1 | grep -E "Tests run:|PageTest\." | tail -8
```
Expected: `Tests run: …, Failures: 4`, naming `aCsvResultIsTextWithADownloadThatSendsNoRequest`,
`aCsvTextareaGetsAChooseFileThatReadsALocalFileOfAtMost60KiB`,
`anActionPublishesEveryOutcomeToItsOutletWithTheWordingOfToday`,
`aGroupTabPicksOneActionAndShowsItsResultApartThenItsHistory`.

- [ ] **Step 3: The page's rules, in its header comment**

In `console.js`, replace exactly:
```js
//   open sub-tab, the action picked in each group and the last result of each action (panelState).
```
with:
```js
//   open sub-tab, the action picked in each group and the last result of each action (panelState).
// - A text/csv answer is shown as text with a Download button, which saves it in the browser, no request sent. A
//   textarea of a form whose schema says "contentMediaType": "text/csv" gets Choose file, which reads a local file of
//   60 KiB at most into it; nothing is sent until the form is.
```

- [ ] **Step 4: *Choose file***

In `console.js`, replace exactly:
```js
const unmasked = (values) => Object.fromEntries(Object.entries(values).filter(([, v]) => v !== MASKED));
```
with:
```js
const unmasked = (values) => Object.fromEntries(Object.entries(values).filter(([, v]) => v !== MASKED));

/** Past this size a chosen file is not read: the console takes a request of 64 KiB at most. */
const MAX_FILE_BYTES = 60 * 1024;

/**
 * A "Choose file" input for a textarea of CSV text (a string property of "contentMediaType": "text/csv"): the file
 * chosen is read in this browser as UTF-8 text and replaces the textarea's value; nothing is sent until the form is.
 * A file larger than 60 KiB is not read, and the line under the input says so.
 */
function fileChooser(target) {
  const root = el("span", "file-choice");
  const file = el("input");
  file.type = "file";
  file.accept = ".csv,text/csv";
  const note = el("span", "file-note");
  file.addEventListener("change", () => {
    note.textContent = "";
    const chosen = file.files && file.files[0];
    if (!chosen) return;
    if (chosen.size > MAX_FILE_BYTES) {
      note.textContent = "the file is larger than 60 KiB";
      return;
    }
    const reader = new FileReader();
    reader.addEventListener("load", () => {
      target.value = typeof reader.result === "string" ? reader.result : "";
    });
    reader.addEventListener("error", () => { note.textContent = "the file could not be read"; });
    reader.readAsText(chosen, "UTF-8");
  });
  root.append(file, note);
  return { root, file };
}
```
Then replace exactly:
```js
 * A json argument: a form generated from its schema when the schema is flat, using required, default, description,
 * enum and a string's "format": "textarea" (a field of several lines), and a raw JSON editor otherwise, starting from
```
with:
```js
 * A json argument: a form generated from its schema when the schema is flat, using required, default, description,
 * enum and a string's "format": "textarea" (a field of several lines, with Choose file when its "contentMediaType"
 * is "text/csv"), and a raw JSON editor otherwise, starting from
```
Then replace exactly:
```js
  const inputs = new Map();
```
with:
```js
  const inputs = new Map();
  const choosers = [];                  // the file inputs of the CSV fields, disabled with the form
```
Then replace exactly:
```js
      let input;
      if (kind === "enum" || kind === "boolean") {
```
with:
```js
      let input;
      let chooser = null;
      if (kind === "enum" || kind === "boolean") {
```
Then replace exactly:
```js
        input.spellcheck = false;
        wrap.classList.add("wide");
      } else {
```
with:
```js
        input.spellcheck = false;
        wrap.classList.add("wide");
        if (definition.contentMediaType === "text/csv") chooser = fileChooser(input);
      } else {
```
Then replace exactly:
```js
      input.name = argument.name + "." + property;
      wrap.append(input);
      form.append(wrap);
```
with:
```js
      input.name = argument.name + "." + property;
      wrap.append(input);
      if (chooser) {
        wrap.append(chooser.root);
        choosers.push(chooser.file);
      }
      form.append(wrap);
```
Then replace exactly:
```js
    disable(on) { for (const c of [editor, raw, ...[...inputs.values()].map((i) => i.input)]) c.disabled = on; },
```
with:
```js
    disable(on) { for (const c of [editor, raw, ...[...inputs.values()].map((i) => i.input), ...choosers]) c.disabled = on; },
```

- [ ] **Step 5: *Download***

In `console.js`, replace exactly:
```js
const isJsonType = (type) => typeof type === "string" && type.startsWith("application/json");
```
with:
```js
const isJsonType = (type) => typeof type === "string" && type.startsWith("application/json");

/** Whether a content type is CSV: a body shown as text, with a Download button. */
const isCsvType = (type) => typeof type === "string" && type.startsWith("text/csv");

/** {@code <action id>-<yyyyMMdd-HHmmss>.csv}, the id's characters outside [A-Za-z0-9._-] replaced by "-". */
function csvFileName(actionId, now) {
  const two = (n) => String(n).padStart(2, "0");
  const stamp = now.getFullYear() + two(now.getMonth() + 1) + two(now.getDate()) + "-" + two(now.getHours())
    + two(now.getMinutes()) + two(now.getSeconds());
  return String(actionId).replace(/[^A-Za-z0-9._-]/g, "-") + "-" + stamp + ".csv";
}

/**
 * A Download button that saves {@code text} as a CSV file: a Blob of type text/csv;charset=utf-8, named by
 * csvFileName, handed to the browser through a link it clicks. No request is sent.
 */
function downloadTools(text, actionId) {
  const tools = el("span", "jv-tools");
  const button = el("button", null, "Download");
  button.type = "button";
  button.addEventListener("click", () => {
    const url = URL.createObjectURL(new Blob([text], { type: "text/csv;charset=utf-8" }));
    const link = el("a");
    link.href = url;
    link.download = csvFileName(actionId, new Date());
    link.hidden = true;
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  });
  tools.append(button);
  return tools;
}

/** The body of an answer: CSV as text with Download, JSON through the viewer, anything else as text. */
function answerBody(answer, nodes, actionId) {
  if (isCsvType(answer.contentType)) {
    return { view: el("pre", "result-body", answer.body), tools: downloadTools(answer.body, actionId) };
  }
  return textOrJson(answer.body, isJsonType(answer.contentType), nodes);
}
```
Then replace exactly:
```js
function resultOutput(result) {
  const answer = result.answer;
  const out = [];
  if (typeof answer.body === "string") {
    const body = textOrJson(answer.body, isJsonType(answer.contentType), result.nodes.body);
```
with:
```js
function resultOutput(result, actionId) {
  const answer = result.answer;
  const out = [];
  if (typeof answer.body === "string") {
    const body = answerBody(answer, result.nodes.body, actionId);
```
Then replace exactly:
```js
function inlineOutlet() {
```
with:
```js
function inlineOutlet(actionId) {
```
Then replace exactly:
```js
      if (!sameAnswer) output.replaceChildren(...(next.answer ? resultOutput(next) : []));
```
with:
```js
      if (!sameAnswer) output.replaceChildren(...(next.answer ? resultOutput(next, actionId) : []));
```
Then replace exactly:
```js
function resultBlock(result) {
```
with:
```js
function resultBlock(result, actionId) {
```
Then replace exactly:
```js
    const body = textOrJson(answer.body, isJsonType(answer.contentType), result.nodes.body);
    if (body.tools) head.append(body.tools);
```
with:
```js
    const body = answerBody(answer, result.nodes.body, actionId);
    if (body.tools) head.append(body.tools);
```
Then replace exactly:
```js
  const showResult = () => resultSlot.replaceChildren(resultBlock(state.results.get(selected) || null));
```
with:
```js
  const showResult = () => resultSlot.replaceChildren(resultBlock(state.results.get(selected) || null, selected));
```
Then replace exactly:
```js
    const outlet = group === null ? inlineOutlet() : groupOutlet(state, action.id, (id) => {
```
with:
```js
    const outlet = group === null ? inlineOutlet(action.id) : groupOutlet(state, action.id, (id) => {
```

- [ ] **Step 6: The style**

In `console.css`, replace exactly:
```css
.action .arg.wide { flex-basis: 100%; align-items: flex-start; }
```
with:
```css
.action .arg.wide { flex-basis: 100%; align-items: flex-start; }
.action .file-choice { display: inline-flex; flex-direction: column; gap: 4px; }
.action .file-note { color: var(--crit); font-size: 12px; }
.action .file-note:empty { display: none; }
```

- [ ] **Step 7: Run the page's tests, install the module**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu install -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension 2>&1 | grep -E "Tests run:|FAIL|BUILD" | tail -5
```
(`timeout: 600000`.) Expected: `BUILD SUCCESS`, no failure (`noFileLoadsAnythingFromElsewhere` stays green:
`createObjectURL(` is not `url(`).

- [ ] **Step 8: The documentation**

In `docs/en/modules/ROOT/pages/dev-console-panels.adoc`, replace exactly:
```
 "required": ["query"]}
----

[#action-result]
```
with:
```
 "required": ["query"]}
----

Such a field whose schema also says `"contentMediaType": "text/csv"` [.tag-new]#NEW# gets a *Choose file* input next
to it: the file picked is read by the browser as UTF-8 text and replaces the field's value, nothing being sent until
the form is. A file larger than 60 KiB is not read, `the file is larger than 60 KiB` showing under the input. The
console's 64 KiB limit on a request stays the last word, and a CSV text grows a little once sent as JSON. The Mansart
Data panel's *Import CSV* uses it
(xref:modules/vidocq-runtime-extensions.adoc#mansart-data-csv[CSV export and import for Mansart Data]):

[source,json]
----
{"type": "object",
 "properties": {"entity": {"type": "string", "enum": ["Product"]},
                "csv": {"type": "string", "format": "textarea", "contentMediaType": "text/csv"}},
 "required": ["entity", "csv"]}
----

[#action-result]
```
Then replace exactly:
```
* `body` is optional content shown under the line, at most 256 KiB: `contentType` is `text/plain` or
  `application/json`, the page showing JSON in its xref:dev-console.adoc#json-viewer[JSON viewer].
```
with:
```
* `body` is optional content shown under the line, at most 256 KiB: `contentType` is `text/plain` or
  `application/json`, the page showing JSON in its xref:dev-console.adoc#json-viewer[JSON viewer], or
  `ActionResult.CSV`, `text/csv` [.tag-new]#NEW#, which the page shows as text with a *Download* button: the body
  saved by the browser as `<action id>-<yyyyMMdd-HHmmss>.csv` (the id's characters outside `[A-Za-z0-9._-]` replaced
  by `-`), without a request. The Mansart Data panel's *Export CSV* answers so.
```

- [ ] **Step 9: Commit**

Message:
```
feat(devconsole): download a CSV result, choose a CSV file

A text/csv answer is shown as text with a Download button, which saves
the body in the browser as <action id>-<yyyyMMdd-HHmmss>.csv, a Blob of
text/csv;charset=utf-8, without a request; both a group tab's result
block and the panel's own bar offer it. In a flat-schema form, a
textarea whose schema says "contentMediaType": "text/csv" gets a Choose
file input that reads a local file of at most 60 KiB into it.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/console.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/console.css vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/PageTest.java docs/en/modules/ROOT/pages/dev-console-panels.adoc && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 3: DEV — the CSV format (spec §5)

**Files:**
- Create: `DEV/src/main/java/.../dev/Csv.java`
- Test: `DEV/src/test/java/.../dev/CsvTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces (package-private, `io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev`):
  - `final class Csv` with `static final String LINE_END = "\r\n"`;
  - `record Csv.Record(int line, List<String> fields)` (`null` for an empty unquoted field);
  - `static final class Csv.Malformed extends Exception` (message `line <n>: <why>`);
  - `static List<Csv.Record> read(String text, char separator) throws Csv.Malformed`;
  - `static String field(String value, char separator)`; `static long utf8Length(CharSequence text)`;
  - `static final class Csv.Writer` with `Writer(char separator, long maxBytes)`, `boolean add(List<String> fields)`,
    `String text()`, `long bytes()`, `int records()`.

- [ ] **Step 1: Write the failing tests**

Create `DEV/src/test/java/.../dev/CsvTest.java`:
```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The CSV the JDQL tab writes and reads (CSV spec §5): RFC 4180, null apart from the empty text. */
class CsvTest {

    private static List<String> row(String... fields) {
        return Arrays.asList(fields);
    }

    private static List<List<String>> fields(List<Csv.Record> records) {
        return records.stream().map(Csv.Record::fields).toList();
    }

    private static List<Integer> lines(List<Csv.Record> records) {
        return records.stream().map(Csv.Record::line).toList();
    }

    private static String malformed(String text) {
        return assertThrows(Csv.Malformed.class, () -> Csv.read(text, ',')).getMessage();
    }

    @Test
    void aFieldIsQuotedWhenItHoldsTheSeparatorAQuoteALineEndOrNothing() {
        assertEquals("plain", Csv.field("plain", ','));
        assertEquals("\"a,b\"", Csv.field("a,b", ','));
        assertEquals("a,b", Csv.field("a,b", ';'), "a comma is text when ; separates");
        assertEquals("\"a;b\"", Csv.field("a;b", ';'));
        assertEquals("\"say \"\"hi\"\"\"", Csv.field("say \"hi\"", ','));
        assertEquals("\"two\nlines\"", Csv.field("two\nlines", ','));
        assertEquals("\"cr\rhere\"", Csv.field("cr\rhere", ','));
        assertEquals("\"\"", Csv.field("", ','), "the empty text");
        assertEquals("", Csv.field(null, ','), "null: nothing at all");
    }

    @Test
    void theWriterEndsEachRecordWithCrLf() {
        Csv.Writer out = new Csv.Writer(',', 1000);

        assertTrue(out.add(row("id", "name")));
        assertTrue(out.add(row("1", null)));
        assertTrue(out.add(row("2", "")));

        assertEquals("id,name\r\n1,\r\n2,\"\"\r\n", out.text());
        assertEquals(3, out.records());
        assertEquals(out.text().length(), out.bytes());
    }

    @Test
    void theWriterRefusesARecordThatWouldPassItsLimitInUtf8Bytes() {
        Csv.Writer out = new Csv.Writer(',', 10);

        assertTrue(out.add(row("éé")), "2 × 2 + 2 = 6 bytes");
        assertFalse(out.add(row("ééé")), "6 + 8 = 14 bytes, past 10 although 11 characters would be too");
        assertEquals("éé\r\n", out.text(), "a refused record leaves the text as it was");
        assertEquals(6, out.bytes());
        assertEquals(1, out.records());
        assertTrue(out.add(row("ab")), "6 + 4 = 10 bytes: the limit itself is allowed");
    }

    @Test
    void utf8LengthCountsBytes() {
        for (String text : List.of("a", "é", "€", "😀", "a,é;€\r\n😀")) {
            assertEquals(text.getBytes(StandardCharsets.UTF_8).length, Csv.utf8Length(text), text);
        }
        assertEquals(3, Csv.utf8Length("\uD83D"), "a lone surrogate: three bytes, never fewer than it may take");
    }

    @Test
    void bothLineEndsAreReadAndTheLastRecordMayHaveNone() throws Csv.Malformed {
        List<Csv.Record> records = Csv.read("a,b\r\n1,2\n3,4", ',');

        assertEquals(List.of(row("a", "b"), row("1", "2"), row("3", "4")), fields(records));
        assertEquals(List.of(1, 2, 3), lines(records));
    }

    @Test
    void anEmptyFieldIsNullAndTwoQuotesAreTheEmptyText() throws Csv.Malformed {
        assertEquals(List.of(row("a", "b", "c"), row(null, "", "x"), row("y", null, null)),
                fields(Csv.read("a,b,c\r\n,\"\",x\r\ny,,\r\n", ',')));
    }

    @Test
    void aByteOrderMarkAtTheStartIsIgnored() throws Csv.Malformed {
        assertEquals(List.of(row("id", "name"), row("1", "x")), fields(Csv.read("﻿id,name\r\n1,x\r\n", ',')));
        assertEquals(List.of(), Csv.read("﻿", ','));
        assertEquals(List.of(), Csv.read("", ','));
    }

    @Test
    void aSemicolonSeparatesAndACommaIsThenText() throws Csv.Malformed {
        assertEquals(List.of(row("a", "b"), row("1;2", "2,50")), fields(Csv.read("a;b\r\n\"1;2\";2,50\r\n", ';')));
    }

    @Test
    void aRecordsLineIsWhereItStartsLineEndsInQuotedFieldsCounted() throws Csv.Malformed {
        List<Csv.Record> records = Csv.read("id,label\r\n1,\"two\r\nlines\"\r\n2,\"x\"\"y\"\r\n3,z", ',');

        assertEquals(List.of(row("id", "label"), row("1", "two\r\nlines"), row("2", "x\"y"), row("3", "z")),
                fields(records));
        assertEquals(List.of(1, 2, 4, 5), lines(records));
    }

    @Test
    void anEmptyLineIsARecordOfOneNullFieldAndEachRecordKeepsItsFieldCount() throws Csv.Malformed {
        assertEquals(List.of(row("a", "b"), row((String) null), row("1", "2")),
                fields(Csv.read("a,b\r\n\r\n1,2\r\n", ',')));
        assertEquals(List.of(3, 2, 4), Csv.read("a,b,c\r\n1,2\r\n1,2,3,4\r\n", ',').stream()
                .map(record -> record.fields().size()).toList());
    }

    @Test
    void aQuoteInsideAnUnquotedFieldAndALoneCarriageReturnAreText() throws Csv.Malformed {
        assertEquals(List.of(row("a"), row("x\"y"), row("p\rq")), fields(Csv.read("a\r\nx\"y\r\np\rq\r\n", ',')));
    }

    @Test
    void aQuoteLeftOpenOrTextAfterAClosingQuoteIsRefusedWithItsLine() {
        assertEquals("line 3: unterminated quoted field", malformed("a\r\n1\r\n\"open\r\nstill"));
        assertEquals("line 2: text after a closing quote", malformed("a\r\n\"x\"y\r\n"));
    }

    @Test
    void whatTheWriterWritesTheReaderReadsBackWithBothSeparators() throws Csv.Malformed {
        List<List<String>> rows = List.of(row("id", "name", "note"), row("1", null, ""),
                row("2", "a,b;c", "say \"hi\"\r\nbye"), row("3", "é😀", "\n"), row((String) null, null, null));
        for (char separator : new char[] {',', ';'}) {
            Csv.Writer out = new Csv.Writer(separator, 10_000);
            for (List<String> row : rows) {
                assertTrue(out.add(row));
            }
            assertEquals(rows, fields(Csv.read(out.text(), separator)), "separator " + separator);
        }
    }
}
```
(In `utf8LengthCountsBytes`, the lone surrogate line checks the count the writer uses for a malformed string: three
bytes, as a `char` of the Basic Multilingual Plane past U+07FF takes.)

- [ ] **Step 2: Run it to see it fail**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu -q -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev test -Dtest=CsvTest 2>&1 | tail -20
```
Expected: `COMPILATION ERROR`, `cannot find symbol` … `Csv`.

- [ ] **Step 3: The class**

Create `DEV/src/main/java/.../dev/Csv.java`:
```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The CSV of the <i>JDQL</i> tab (CSV spec §5), RFC 4180, written and read here rather than by a library. Fields are
 * separated by the chosen separator, records ended by {@value #LINE_END} when written; {@code \r\n}, {@code \n} and a
 * last record without an end of line are read, and a UTF-8 byte order mark at the start is ignored. A field holding
 * the separator, a {@code "}, a {@code \r} or a {@code \n}, or holding nothing, is quoted, a {@code "} doubled.
 * {@code null} is an empty unquoted field and the empty text {@code ""}: both are read back as written.
 */
final class Csv {

    /** What ends each record written. */
    static final String LINE_END = "\r\n";

    /**
     * One record read.
     *
     * @param line   the line of the text it starts on, the first being 1; line ends inside quoted fields counted
     * @param fields its fields, {@code null} for an empty unquoted one
     */
    record Record(int line, List<String> fields) {}

    /** A text that is not CSV: {@code line <n>: <why>}. */
    static final class Malformed extends Exception {

        private static final long serialVersionUID = 1L;

        Malformed(int line, String why) {
            super("line " + line + ": " + why, null, false, false);
        }
    }

    private Csv() {}

    /** {@code value} as one field: quoted when needed; {@code null} as nothing, the empty text as {@code ""}. */
    static String field(String value, char separator) {
        if (value == null) {
            return "";
        }
        boolean quote = value.isEmpty();
        for (int i = 0; i < value.length() && !quote; i++) {
            char c = value.charAt(i);
            quote = c == separator || c == '"' || c == '\r' || c == '\n';
        }
        return quote ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }

    /** How many bytes {@code text} takes in UTF-8; a lone surrogate counts three. */
    static long utf8Length(CharSequence text) {
        long bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                bytes++;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    /**
     * The records of {@code text}. An empty line is a record of one {@code null} field; a lone {@code \r} and a
     * {@code "} inside an unquoted field are text.
     *
     * @throws Malformed for a quote left open at the end of the text, at the line it opened on, or for text after a
     *                   closing quote
     */
    static List<Record> read(String text, char separator) throws Malformed {
        List<Record> records = new ArrayList<>();
        int length = text.length();
        int at = length > 0 && text.charAt(0) == '﻿' ? 1 : 0;
        int line = 1;
        while (at < length) {
            int first = line;
            List<String> fields = new ArrayList<>();
            boolean more = true;
            while (more) {
                if (at < length && text.charAt(at) == '"') {
                    int opened = line;
                    StringBuilder field = new StringBuilder();
                    at++;
                    while (true) {
                        if (at >= length) {
                            throw new Malformed(opened, "unterminated quoted field");
                        }
                        char c = text.charAt(at++);
                        if (c == '"') {
                            if (at < length && text.charAt(at) == '"') {
                                field.append('"');
                                at++;
                                continue;
                            }
                            break;
                        }
                        if (c == '\n') {
                            line++;
                        }
                        field.append(c);
                    }
                    fields.add(field.toString());
                } else {
                    int from = at;
                    while (at < length && text.charAt(at) != separator && lineEnd(text, at) == 0) {
                        at++;
                    }
                    fields.add(at == from ? null : text.substring(from, at));
                }
                // after a field: the separator, a line end or the end of the text; after a quote, nothing else
                if (at >= length) {
                    more = false;
                } else if (text.charAt(at) == separator) {
                    at++;
                } else if (lineEnd(text, at) > 0) {
                    at += lineEnd(text, at);
                    line++;
                    more = false;
                } else {
                    throw new Malformed(line, "text after a closing quote");
                }
            }
            records.add(new Record(first, Collections.unmodifiableList(fields)));
        }
        return records;
    }

    /** The length of the line end at {@code at}: 2 for {@code \r\n}, 1 for {@code \n}, 0 for none. */
    private static int lineEnd(String text, int at) {
        char c = text.charAt(at);
        if (c == '\n') {
            return 1;
        }
        return c == '\r' && at + 1 < text.length() && text.charAt(at + 1) == '\n' ? 2 : 0;
    }

    /** Writes records up to a length in UTF-8 bytes; a record that would pass it is refused, the text kept whole. */
    static final class Writer {

        private final char separator;
        private final long maxBytes;
        private final StringBuilder text = new StringBuilder();
        private long bytes;
        private int records;

        /**
         * @param separator between the fields, {@code ,} or {@code ;}
         * @param maxBytes  the longest text, in UTF-8 bytes
         */
        Writer(char separator, long maxBytes) {
            this.separator = separator;
            this.maxBytes = maxBytes;
        }

        /**
         * Appends one record, each field quoted as needed, then {@value Csv#LINE_END}.
         *
         * @return {@code false}, and nothing appended, when the text would then pass the limit
         */
        boolean add(List<String> fields) {
            StringBuilder record = new StringBuilder();
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) {
                    record.append(separator);
                }
                record.append(field(fields.get(i), separator));
            }
            record.append(LINE_END);
            long size = utf8Length(record);
            if (bytes + size > maxBytes) {
                return false;
            }
            text.append(record);
            bytes += size;
            records++;
            return true;
        }

        String text() {
            return text.toString();
        }

        /** The text's length in UTF-8 bytes. */
        long bytes() {
            return bytes;
        }

        /** The records written, the header included. */
        int records() {
            return records;
        }
    }
}
```

- [ ] **Step 4: Run it to see it pass**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu -q -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev test -Dtest=CsvTest 2>&1 | tail -20
```
Expected: no `[ERROR]` line, exit code 0.

- [ ] **Step 5: Commit**

Message:
```
feat(mansart-data): a CSV reader and writer for the dev panel

Csv writes and reads RFC 4180 by hand, no new dependency: quoting of the
separator, quotes and line ends, null as an empty field apart from the
empty text "", both line ends and a byte order mark read, a record's
line kept for the messages, and a writer that stops at a length counted
in UTF-8 bytes.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && git add vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/Csv.java vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/CsvTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 4: DEV — *Export CSV* (spec §3, §5, §6)

**Files:**
- Modify: `DEV/src/main/java/.../dev/JdqlActions.java` (shared lookups), `EntityJson.java` (`names`, `idOf`)
- Create: `DEV/src/main/java/.../dev/EntitySaver.java`, `DEV/src/main/java/.../dev/CsvActions.java`
- Modify (test fixtures): `DEV/src/test/java/.../dev/RunFixtures.java` (`Slot`)
- Test: `DEV/src/test/java/.../dev/CsvActionsTest.java` (new), `EntityJsonTest.java` (modify)

**Interfaces:**
- Consumes: Task 1's `ActionResult.CSV` (from `~/.m2`); Task 3's `Csv.Writer`; #155's `JdqlRunner`, `JdqlResult`,
  `ResultJson.count(long)`, `TransactionRunner.run/available/modes/ROLLBACK`, `CallHistory.add/calls`,
  `Failures.text/line/cut`, `Scalars.object/toJson`, `Json.parse/write`, `RepositoryActions.TRANSACTION`,
  `RepositoryActions.load(String, List<Class<?>>)`, `RecordingTransactionManager`, `RunFixtures`.
- Produces:
  - in `JdqlActions` (package-private): `static final class Refused extends Exception` (`Refused(String)`),
    `static Object parse(String argument, String sent) throws Refused`,
    `static Map<String, Object> params(Object given) throws Refused`, `Class<?> entity(String query) throws Refused`,
    `Class<?> named(String name) throws Refused`, `EntityModel<?> model(Class<?> entity) throws Refused`,
    `Object runtime() throws Refused`, `List<String> names()`, `String entityNames()`;
  - `List<String> EntityJson.names(Class<?> entity)`, `Object EntityJson.idOf(Object entity)`;
  - `@FunctionalInterface interface EntitySaver { EntitySaver MANSART; Object save(EntityModel<?> model, Object
    entity, Object runtime); }`;
  - `final class CsvActions` with `EXPORT = "jdql.export"`, `IMPORT = "jdql.import"`, `EXPORT_LABEL = "Export CSV"`,
    `IMPORT_LABEL = "Import CSV"`, `STATEMENT = "statement"`, `FILE = "file"`, `CONFIRMATION`, `MAX_BYTES`,
    `MAX_ROWS`, the constructor `CsvActions(JdqlActions jdql, EntityJson json, TransactionRunner transactions,
    JdqlRunner runner, EntitySaver saver, CallHistory history, String group)`, `List<PanelAction> actions()`
    (Task 4: the export only), `PanelAction.ActionResult exportCsv(Map<String, String> given)`,
    `record Written(String text, long rows, long bytes)`, `Written write(JdqlResult, Class<?>, char)`,
    `static String exportSchema()`, `static Map<String, Object> separatorSchema()`, `static String size(long bytes)`,
    `static String text(Object json)`;
  - test fixtures `RunFixtures.Slot`, `RunFixtures.slot(Long, Year, String)`, `RunFixtures.slotModel()`.

- [ ] **Step 1: The test fixture — an entity with a type the console cannot convert**

In `RunFixtures.java`, replace exactly:
```java
import io.vidocq.mansart.data.dialect.attribute.TextAttribute;
```
with:
```java
import io.vidocq.mansart.data.dialect.attribute.TemporalAttribute;
import io.vidocq.mansart.data.dialect.attribute.TextAttribute;
```
Then replace exactly:
```java
import java.time.LocalDate;
```
with:
```java
import java.time.LocalDate;
import java.time.Year;
```
Then replace exactly:
```java
    /** No id field: Mansart cannot build its model. */
    public static class Broken {
        private String label;

        public Broken() {}
    }
```
with:
```java
    /** No id field: Mansart cannot build its model. */
    public static class Broken {
        private String label;

        public Broken() {}
    }

    /** Its model is {@link #slotModel()}: a {@code Year}, a type the console cannot convert, between an id and a label. */
    public static class Slot {
        private Long id;
        private Year year;
        private String label;

        public Slot() {}
    }

    static Slot slot(Long id, Year year, String label) {
        Slot slot = new Slot();
        slot.id = id;
        slot.year = year;
        slot.label = label;
        return slot;
    }
```
Then replace exactly:
```java
    static final Set<String> ENTITIES = Set.of(Gizmo.class.getName(), Part.class.getName(), Broken.class.getName());
```
with:
```java
    static final Set<String> ENTITIES = Set.of(Gizmo.class.getName(), Part.class.getName(), Broken.class.getName(),
            Slot.class.getName());
```
Then replace exactly:
```java
    /** The models: Part's by hand, the others from Mansart itself. */
    static EntityModel<?> model(Class<?> type) {
        return type == Part.class ? partModel() : EntityModels.of(type);
    }
```
with:
```java
    /** The models: Part's and Slot's by hand, the others from Mansart itself. */
    static EntityModel<?> model(Class<?> type) {
        if (type == Part.class) {
            return partModel();
        }
        return type == Slot.class ? slotModel() : EntityModels.of(type);
    }
```
Then replace exactly:
```java
    /** The model of {@link Part}, with real handles: its id, a reference to a gizmo, a label, a joined name. */
```
with:
```java
    /** The model of {@link Slot}, with real handles: its id, a {@code Year}, a label. */
    static EntityModel<Slot> slotModel() {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            IdAttribute<Slot, Long> id = new IdAttribute<>("id", "id", Long.class, Slot.class, true,
                    lookup.findGetter(Slot.class, "id", Long.class), lookup.findSetter(Slot.class, "id", Long.class));
            TemporalAttribute<Slot, Year> year = new TemporalAttribute<>("year", "year", Year.class, Slot.class, true,
                    false, lookup.findGetter(Slot.class, "year", Year.class),
                    lookup.findSetter(Slot.class, "year", Year.class));
            TextAttribute<Slot> label = new TextAttribute<>("label", "label", Slot.class, true, false, 100,
                    lookup.findGetter(Slot.class, "label", String.class),
                    lookup.findSetter(Slot.class, "label", String.class));
            return new EntityModel<>(Slot.class, "slots", "", id, Optional.empty(), List.of(id, year, label),
                    lookup.findConstructor(Slot.class, MethodType.methodType(void.class)));
        } catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The model of {@link Part}, with real handles: its id, a reference to a gizmo, a label, a joined name. */
```

- [ ] **Step 2: Write the failing tests**

In `EntityJsonTest.java`, replace exactly:
```java
import java.time.LocalDate;
```
with:
```java
import java.time.LocalDate;
import java.util.List;
```
Then replace exactly:
```java
    @Test
    void onlyTheCatalogueEntitiesAreEntities() {
```
with:
```java
    @Test
    void theNamesAnExportWritesAreEveryReadableAttributeButAJoinedOne() {
        assertEquals(List.of("id", "name", "stock", "level", "due", "price"), entities.names(Gizmo.class));
        assertEquals(List.of("id", "gizmo", "label"), entities.names(Part.class), "the joined name left out");
        assertEquals(List.of("id", "year", "label"), entities.names(RunFixtures.Slot.class),
                "a Year is read, as its text");
        assertEquals(7L, entities.idOf(RunFixtures.gizmo(7L, "bolt", 3, Level.LOW, null, null)));
    }

    @Test
    void onlyTheCatalogueEntitiesAreEntities() {
```
Create `DEV/src/test/java/.../dev/CsvActionsTest.java`:
```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.mansart.data.core.JdqlResult;
import io.vidocq.mansart.data.core.MansartDataException;
import io.vidocq.mansart.data.core.RepositoryRuntime;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RecordedSample.Table;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Gizmo;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Level;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Part;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Slot;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CSV actions of the JDQL tab (CSV spec §3-§6): what they offer, what they refuse before anything runs, the CSV an
 * export writes and what an import saves — through a recording {@link JdqlRunner} and {@link EntitySaver}, no
 * database — and the calls they keep.
 */
class CsvActionsTest {

    private static final String SECRET = "hunter2";
    /** The {@code RepositoryRuntime} bean the lookup gives: the runner and the saver only receive it. */
    private static final Object RUNTIME = new Object();
    private static final BeanLookup BEANS = type -> {
        if (type == RepositoryRuntime.class) {
            return RUNTIME;
        }
        throw new BeanLookup.NoBean();
    };

    private final RecordingTransactionManager manager = new RecordingTransactionManager();
    private final RecordingRunner runner = new RecordingRunner();
    private final RecordingSaver saver = new RecordingSaver();
    private final CallHistory history = new CallHistory();
    private final EntityJson json = RunFixtures.entities();

    /** Records each statement, its parameters and the transaction's events at that moment; answers or throws. */
    final class RecordingRunner implements JdqlRunner {

        final List<String> queries = new ArrayList<>();
        final List<Map<String, Object>> parameters = new ArrayList<>();
        final List<List<String>> eventsDuringRun = new ArrayList<>();
        JdqlResult answer = new JdqlResult.Entities(List.of());
        RuntimeException failure;

        @Override
        public JdqlResult run(String jdql, Map<String, Object> params, EntityModel<?> model, Object runtime) {
            queries.add(jdql);
            parameters.add(params);
            eventsDuringRun.add(List.copyOf(manager.events));
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    /** Records each entity saved and the transaction's events at that moment; the save number {@code failAt} throws. */
    final class RecordingSaver implements EntitySaver {

        final List<Object> saved = new ArrayList<>();
        final List<Class<?>> models = new ArrayList<>();
        final List<List<String>> eventsDuringSave = new ArrayList<>();
        /** The save, counted from 1, that throws; 0 for none. */
        int failAt;

        @Override
        public Object save(EntityModel<?> model, Object entity, Object runtime) {
            assertSame(RUNTIME, runtime);
            models.add(model.entityClass());
            eventsDuringSave.add(List.copyOf(manager.events));
            if (saved.size() + 1 == failAt) {
                throw new IllegalStateException("duplicate key on jdbc:h2:tcp://sa:" + SECRET + "@db/x");
            }
            saved.add(entity);
            return entity;
        }
    }

    /** The CSV actions over the catalogue's Gizmo and Part, and Slot, whose Year the console cannot convert. */
    private CsvActions tab(TransactionRunner transactions, BeanLookup beans) {
        List<MansartDataCatalogue.Entity> entities = new ArrayList<>(RunFixtures.catalogue().entities());
        entities.add(new MansartDataCatalogue.Entity("Slot", Slot.class.getName(), "slots", List.of(), null));
        JdqlActions jdql = new JdqlActions(entities,
                className -> RepositoryActions.load(className, RunFixtures.REPOSITORIES), RunFixtures::model, json,
                beans, transactions, runner, history, JdqlActions.GROUP);
        return new CsvActions(jdql, json, transactions, runner, saver, history, JdqlActions.GROUP);
    }

    private CsvActions tab() {
        return tab(new TransactionRunner(new JtaDemarcation(() -> manager)), BEANS);
    }

    private static ActionResult export(CsvActions tab, String statement) {
        return tab.exportCsv(Map.of(CsvActions.STATEMENT, statement));
    }

    private static void assertRefused(ActionResult result, String expected) {
        assertTrue(result.error(), result.summary());
        assertEquals(expected, result.summary());
        assertNull(result.body(), expected);
    }

    private static Gizmo bolt(long id) {
        return RunFixtures.gizmo(id, "bolt", 3, Level.LOW, LocalDate.of(2026, 10, 1), new BigDecimal("2.50"));
    }

    private static int utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    void theExportActionIsAQueryOfTheJdqlTab() {
        PanelAction export = tab().actions().getFirst();

        assertEquals(CsvActions.EXPORT, export.id());
        assertEquals("Export CSV", export.label());
        assertEquals("JDQL", export.group());
        assertNull(export.confirmation(), "an export writes nothing: no confirmation");
        assertEquals(List.of("statement"), export.arguments().stream().map(PanelAction.Argument::name).toList());
        String schema = export.arguments().getFirst().schema();
        assertTrue(schema.contains("\"query\":{\"type\":\"string\",\"format\":\"textarea\""), schema);
        assertTrue(schema.contains("\"separator\":{\"type\":\"string\",\"enum\":[\",\",\";\"],\"default\":\",\""),
                schema);
        assertTrue(schema.endsWith("\"required\":[\"query\"]}"), schema);
        assertTrue(export.description().endsWith("Entities: Gizmo, Part, Slot"), export.description());
    }

    @Test
    void anExportOfEntitiesIsEveryAttributeInModelOrderOneRowEach() {
        CsvActions tab = tab();
        runner.answer = new JdqlResult.Entities(List.of(bolt(1),
                RunFixtures.gizmo(2L, "nut, \"big\"", 0, Level.HIGH, null, new BigDecimal("1E+1"))));

        ActionResult result = export(tab, "{\"query\":\" FROM Gizmo ORDER BY id \"}");

        assertFalse(result.error(), result.summary());
        assertEquals(ActionResult.CSV, result.contentType());
        assertEquals("id,name,stock,level,due,price\r\n1,bolt,3,LOW,2026-10-01,2.50\r\n"
                + "2,\"nut, \"\"big\"\"\",0,HIGH,,10\r\n", result.body());
        assertTrue(result.summary().matches("2 rows · " + utf8(result.body()) + " B in \\d+ ms"), result.summary());
        assertEquals(List.of("FROM Gizmo ORDER BY id"), runner.queries, "the statement, stripped");
        assertEquals(List.of(List.of("begin")), runner.eventsDuringRun, "run in a transaction");
        assertEquals(List.of("begin", "rollback"), manager.events, "always rolled back: an export writes nothing");
        assertEquals("{\"entity\":\"" + Gizmo.class.getName() + "\",\"query\":\"FROM Gizmo ORDER BY id\","
                + "\"params\":{},\"separator\":\",\"}", result.details());
        assertEquals(List.of(), saver.saved);
    }

    @Test
    void aProjectionIsItsColumnsAndASemicolonKeepsDecimalPointsUnquoted() {
        runner.answer = new JdqlResult.Rows(List.of("name", "price"), List.<Object[]>of(
                new Object[] {"a;b", new BigDecimal("2.50")}, new Object[] {null, 3.5}, new Object[] {"c,d", null}));

        ActionResult result = export(tab(), "{\"query\":\"SELECT name, price FROM Gizmo WHERE stock > :min\","
                + "\"params\":{\"min\":2},\"separator\":\";\"}");

        assertEquals("name;price\r\n\"a;b\";2.50\r\n;3.5\r\nc,d;\r\n", result.body());
        assertTrue(result.summary().startsWith("3 rows · "), result.summary());
        assertEquals(List.of(Map.of("min", new BigDecimal("2"))), runner.parameters);
        assertTrue(result.details().endsWith("\"params\":{\"min\":2},\"separator\":\";\"}"), result.details());
    }

    @Test
    void aCountAndAValueAreOneColumnOfOneRowNullAnEmptyField() {
        CsvActions tab = tab();

        runner.answer = new JdqlResult.Count(42);
        ActionResult count = export(tab, "{\"query\":\"SELECT COUNT(this) FROM Gizmo\"}");
        assertEquals("count\r\n42\r\n", count.body());
        assertTrue(count.summary().matches("1 row · 11 B in \\d+ ms"), count.summary());

        runner.answer = new JdqlResult.Value(null);
        assertEquals("value\r\n\r\n", export(tab, "{\"query\":\"SELECT MAX(price) FROM Gizmo\"}").body(),
                "null: an empty field");
        runner.answer = new JdqlResult.Value("");
        assertEquals("value\r\n\"\"\r\n", export(tab, "{\"query\":\"SELECT MAX(name) FROM Gizmo\"}").body(),
                "the empty text: \"\"");
        runner.answer = new JdqlResult.Value(LocalDate.of(2026, 10, 1));
        assertEquals("value\r\n2026-10-01\r\n", export(tab, "{\"query\":\"SELECT MAX(due) FROM Gizmo\"}").body());

        runner.answer = new JdqlResult.Entities(List.of());
        ActionResult none = export(tab, "{\"query\":\"FROM Gizmo WHERE stock > 99\"}");
        assertEquals("id,name,stock,level,due,price\r\n", none.body(), "no row: the header only");
        assertTrue(none.summary().matches("no row · 31 B in \\d+ ms"), none.summary());
    }

    @Test
    void aReferenceIsItsIdAJoinedAttributeIsLeftOutAnUnsupportedTypeIsItsText() {
        CsvActions tab = tab();

        runner.answer = new JdqlResult.Entities(List.of(RunFixtures.part(5L, bolt(1), "left")));
        assertEquals("id,gizmo,label\r\n5,1,left\r\n", export(tab, "{\"query\":\"FROM Part\"}").body());

        runner.answer = new JdqlResult.Entities(List.of(RunFixtures.slot(1L, Year.of(2026), "x")));
        assertEquals("id,year,label\r\n1,2026,x\r\n", export(tab, "{\"query\":\"FROM Slot\"}").body());

        runner.answer = new JdqlResult.Rows(List.of("gizmo"), List.<Object[]>of(new Object[] {bolt(7)}));
        assertEquals("gizmo\r\n7\r\n", export(tab, "{\"query\":\"SELECT gizmo FROM Part\"}").body(),
                "an entity in a projection: its id");
    }

    @Test
    void theLimitIs256KiBOfUtf8NotOfCharacters() {
        CsvActions tab = tab();
        runner.answer = new JdqlResult.Entities(LongStream.rangeClosed(1, 1400)
                .mapToObj(id -> RunFixtures.gizmo(id, "e".repeat(100), 3, Level.LOW, null, null)).toList());

        ActionResult ascii = export(tab, "{\"query\":\"FROM Gizmo\"}");

        assertFalse(ascii.error(), ascii.summary());
        assertTrue(ascii.summary().startsWith("1400 rows · "), ascii.summary());
        assertEquals(utf8(ascii.body()), ascii.body().length(), "ASCII: as many bytes as characters");

        runner.answer = new JdqlResult.Entities(LongStream.rangeClosed(1, 1400)
                .mapToObj(id -> RunFixtures.gizmo(id, "é".repeat(100), 3, Level.LOW, null, null)).toList());

        ActionResult accented = export(tab, "{\"query\":\"FROM Gizmo\"}");

        assertRefused(accented, "larger than 256 KiB: narrow the query");
        assertEquals(List.of("begin", "rollback", "begin", "rollback"), manager.events);
    }

    @Test
    void whatIsRefusedRunsNothing() {
        CsvActions tab = tab();

        assertRefused(export(tab, "{\"query\":\"UPDATE Gizmo SET stock = 0\"}"), "an UPDATE or DELETE: export a query");
        assertRefused(export(tab, "{\"query\":\"delete from Gizmo\"}"), "an UPDATE or DELETE: export a query");
        assertRefused(export(tab, "{\"query\":\"FROM Gizmo\",\"separator\":\"|\"}"), "separator: \",\" or \";\"");
        assertRefused(export(tab, "{\"query\":\"FROM Gizmo\",\"limit\":3}"), "limit: unknown argument");
        assertRefused(export(tab, "{}"), "query: missing");
        assertRefused(export(tab, "{\"query\":3}"), "query: not a string");
        assertRefused(export(tab, "{\"query\":\"FROM Nope\"}"), "unknown entity Nope; entities: Gizmo, Part, Slot");
        assertRefused(export(tab, "[1]"), "statement: not a JSON object");
        assertRefused(export(tab, "{\"query\":\"FROM Gizmo\""), "statement: not valid JSON at character 22");
        assertRefused(export(tab(TransactionRunner.NONE, type -> {
            throw new BeanLookup.NoBean();
        }), "{\"query\":\"FROM Gizmo\"}"), "no RepositoryRuntime bean");

        assertEquals(List.of(), runner.queries, "nothing run");
        assertEquals(List.of(), manager.events, "no transaction begun");
    }

    @Test
    void aFailingQueryShowsItsClassAndItsMaskedMessage() {
        runner.failure = new MansartDataException("connection to jdbc:h2:tcp://sa:" + SECRET + "@db/x refused");

        ActionResult result = export(tab(), "{\"query\":\"FROM Gizmo\"}");

        String expected = "io.vidocq.mansart.data.core.MansartDataException: "
                + "connection to jdbc:h2:tcp://***:***@db/x refused";
        assertTrue(result.error());
        assertEquals(expected, result.summary());
        assertEquals(ActionResult.TEXT, result.contentType());
        assertEquals(expected, result.body());
        assertEquals(List.of("begin", "rollback"), manager.events);
    }

    @Test
    void aSizeIsInBytesThenKiB() {
        assertEquals("90 B", CsvActions.size(90));
        assertEquals("1023 B", CsvActions.size(1023));
        assertEquals("1.0 KiB", CsvActions.size(1024));
        assertEquals("3.1 KiB", CsvActions.size(3174));
        assertEquals("256.0 KiB", CsvActions.size(256 * 1024));
    }

    @Test
    void anExportIsKeptInTheTabsHistoryWithItsReplay() {
        CsvActions tab = tab();
        runner.answer = new JdqlResult.Count(3);

        export(tab, "{\"query\": \"SELECT COUNT(this) FROM Gizmo\", \"separator\": \";\"}");

        List<CallHistory.Call> calls = history.calls(JdqlActions.GROUP);
        assertEquals(1, calls.size());
        assertEquals("Export CSV", calls.getFirst().method());
        assertTrue(calls.getFirst().outcome().startsWith("1 row · "), calls.getFirst().outcome());
        assertEquals("{\"query\":\"SELECT COUNT(this) FROM Gizmo\",\"separator\":\";\"}", calls.getFirst().arguments(),
                "the JSON sent, compact");
        assertEquals("jdql.export {\"statement\":{\"query\":\"SELECT COUNT(this) FROM Gizmo\",\"separator\":\";\"}}",
                calls.getFirst().replay());
    }
}
```
(Some imports — `HashMap`, `LinkedHashMap`, `Part`, `Table`, `PanelAction` — serve Tasks 5 and 6; the compiler
accepts them unused.)

- [ ] **Step 3: Run them to see them fail**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu -q -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev test -Dtest=CsvActionsTest+EntityJsonTest 2>&1 | tail -20
```
Expected: `COMPILATION ERROR`, `cannot find symbol` … `CsvActions`, `EntitySaver`, `names`.

- [ ] **Step 4: `JdqlActions` shares its lookups**

In `JdqlActions.java`, replace exactly:
```java
            parsed = parse(sent);
```
with:
```java
            parsed = parse(STATEMENT, sent);
```
Then replace exactly:
```java
    private static Object parse(String sent) throws Refused {
        try {
            return Json.parse(sent);
        } catch (IllegalArgumentException unreadable) {
            throw new Refused(STATEMENT + ": " + unreadable.getMessage());
        }
    }
```
with:
```java
    /** {@code sent}, the text of the json argument {@code argument}; refused, naming it, when it does not parse. */
    static Object parse(String argument, String sent) throws Refused {
        try {
            return Json.parse(sent);
        } catch (IllegalArgumentException unreadable) {
            throw new Refused(argument + ": " + unreadable.getMessage());
        }
    }
```
Then replace exactly:
```java
    private static Map<String, Object> params(Object given) throws Refused {
```
with:
```java
    static Map<String, Object> params(Object given) throws Refused {
```
Then replace exactly:
```java
    private Class<?> entity(String query) throws Refused {
```
with:
```java
    Class<?> entity(String query) throws Refused {
```
Then replace exactly:
```java
        try {
            return classes.apply(found.getFirst().className());
        } catch (RuntimeException | LinkageError missing) {
            throw new Refused("entity " + name + ": " + Failures.text(missing));
        }
    }

    private EntityModel<?> model(Class<?> entity) throws Refused {
```
with:
```java
        return load(found.getFirst(), name);
    }

    /**
     * The class of the catalogue's entity shown as {@code name}: its simple name, or its full class name when two
     * entities share one. An import names its entity so (CSV spec §4).
     */
    Class<?> named(String name) throws Refused {
        for (MansartDataCatalogue.Entity entity : entities) {
            if (entity.name().equals(name)) {
                return load(entity, name);
            }
        }
        throw new Refused("unknown entity " + name + "; entities: " + entityNames());
    }

    private Class<?> load(MansartDataCatalogue.Entity entity, String name) throws Refused {
        try {
            return classes.apply(entity.className());
        } catch (RuntimeException | LinkageError missing) {
            throw new Refused("entity " + name + ": " + Failures.text(missing));
        }
    }

    EntityModel<?> model(Class<?> entity) throws Refused {
```
Then replace exactly:
```java
    private Object runtime() throws Refused {
```
with:
```java
    Object runtime() throws Refused {
```
Then replace exactly:
```java
    /** The catalogue's names of its entities, in alphabetical order. */
    private String entityNames() {
        return entities.stream().map(MansartDataCatalogue.Entity::name).sorted().collect(Collectors.joining(", "));
    }
```
with:
```java
    /** The catalogue's names of its entities, in alphabetical order. */
    List<String> names() {
        return entities.stream().map(MansartDataCatalogue.Entity::name).sorted().toList();
    }

    /** {@link #names()}, joined with commas. */
    String entityNames() {
        return String.join(", ", names());
    }
```
Then replace exactly:
```java
    /** A call refused before the statement runs: its message is the summary. */
    private static final class Refused extends Exception {
```
with:
```java
    /** A call of the tab refused before anything runs: its message is the summary. {@link CsvActions} throws it too. */
    static final class Refused extends Exception {
```

- [ ] **Step 5: `EntityJson` names the export's columns**

In `EntityJson.java`, replace exactly:
```java
import java.lang.invoke.MethodHandle;
import java.util.LinkedHashMap;
```
with:
```java
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
```
Then replace exactly:
```java
    /**
     * One property a JSON object may set.
```
with:
```java
    /**
     * The names {@link #toJson} gives {@code entity}'s instances, in model order: every attribute but a joined one or
     * one without a getter. The header of a CSV export (CSV spec §3).
     *
     * @throws RuntimeException when its model cannot be read, as {@code EntityModels.of} throws it
     */
    List<String> names(Class<?> entity) {
        List<String> names = new ArrayList<>();
        for (Attribute<?, ?> attribute : model(entity).attributes()) {
            if (attribute != null && !(attribute instanceof JoinedAttribute<?, ?>) && attribute.getter() != null) {
                names.add(attribute.name());
            }
        }
        return names;
    }

    /** The id of {@code entity}, an instance of an entity: what a reference to it is written as. */
    Object idOf(Object entity) {
        return get(model(entity.getClass()).id().getter(), entity);
    }

    /**
     * One property a JSON object may set.
```

- [ ] **Step 6: The saver seam**

Create `DEV/src/main/java/.../dev/EntitySaver.java`:
```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.mansart.data.core.RepositoryRuntime;
import io.vidocq.mansart.data.dialect.EntityModel;

/**
 * Saves one entity an import built (CSV spec §4): Mansart's own {@code RepositoryRuntime.save} outside tests
 * ({@link #MANSART}) — an insert when its id is {@code null}, the id generated, an upsert otherwise — a recording fake
 * in them, so that the tab's tests need no database.
 */
@FunctionalInterface
interface EntitySaver {

    /** {@code RepositoryRuntime.save} on the bean the call resolved. */
    EntitySaver MANSART = EntitySaver::mansart;

    /**
     * Saves {@code entity}, in whatever transaction the caller has begun.
     *
     * @param model   the model of its class
     * @param entity  the entity
     * @param runtime the {@code RepositoryRuntime} bean
     * @return what the save returned
     */
    Object save(EntityModel<?> model, Object entity, Object runtime);

    @SuppressWarnings("unchecked")
    private static Object mansart(EntityModel<?> model, Object entity, Object runtime) {
        return ((RepositoryRuntime) runtime).save((EntityModel<Object>) model, entity);
    }
}
```

- [ ] **Step 7: The export action**

Create `DEV/src/main/java/.../dev/CsvActions.java`:
```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.mansart.data.core.JdqlExecutor;
import io.vidocq.mansart.data.core.JdqlResult;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.runtime.spi.devconsole.PanelAction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The CSV actions of the <i>JDQL</i> tab (CSV spec §3-§6). {@value #EXPORT} runs a JDQL query and answers its whole
 * result as a CSV file, which the page shows and downloads; {@value #IMPORT} reads a CSV file the page sends, builds
 * one entity per row, then saves them all in one transaction, rolled back unless {@code commit} is asked. Nothing is
 * read from or written to a file of the server's.
 *
 * <p>Both find the entity, its model and the {@code RepositoryRuntime} bean as the tab's queries do, through
 * {@link JdqlActions}, and keep their calls in the panel's {@link CallHistory} under the tab's title.
 */
final class CsvActions {

    static final String EXPORT = "jdql.export";
    static final String IMPORT = "jdql.import";
    static final String EXPORT_LABEL = "Export CSV";
    static final String IMPORT_LABEL = "Import CSV";
    /** The json argument of an export: {@code query}, {@code params} and {@code separator}. */
    static final String STATEMENT = JdqlActions.STATEMENT;
    /** The json argument of an import: {@code entity}, {@code csv} and {@code separator}. */
    static final String FILE = "file";
    static final String CONFIRMATION = "Saves these CSV rows against the database.";
    /** The longest export, in UTF-8 bytes. */
    static final int MAX_BYTES = 256 * 1024;
    /** The most rows an import reads. */
    static final int MAX_ROWS = 5000;

    private static final String QUERY_MEMBER = "query";
    private static final String PARAMS_MEMBER = "params";
    private static final String SEPARATOR_MEMBER = "separator";
    private static final String ENTITY_MEMBER = "entity";
    private static final String CSV_MEMBER = "csv";
    private static final List<String> SEPARATORS = List.of(",", ";");
    private static final System.Logger LOG = System.getLogger(CsvActions.class.getName());

    /**
     * An export's arguments.
     *
     * @param query     the statement, stripped
     * @param params    its named parameters
     * @param separator between the fields
     */
    private record Export(String query, Map<String, Object> params, char separator) {}

    /**
     * A CSV written.
     *
     * @param text  the file
     * @param rows  its rows, the header left out
     * @param bytes its length in UTF-8 bytes
     */
    record Written(String text, long rows, long bytes) {}

    private final JdqlActions jdql;
    private final EntityJson json;
    private final TransactionRunner transactions;
    private final JdqlRunner runner;
    private final EntitySaver saver;
    private final CallHistory history;
    private final String group;

    /**
     * @param jdql         the tab's JDQL actions, whose lookups these share
     * @param json         how entities are read and built
     * @param transactions the transactions of a call
     * @param runner       runs an export's statement, {@link JdqlRunner#MANSART} outside tests
     * @param saver        saves an imported entity, {@link EntitySaver#MANSART} outside tests
     * @param history      the panel's history
     * @param group        the tab's title
     */
    CsvActions(JdqlActions jdql, EntityJson json, TransactionRunner transactions, JdqlRunner runner,
               EntitySaver saver, CallHistory history, String group) {
        this.jdql = Objects.requireNonNull(jdql, "jdql");
        this.json = Objects.requireNonNull(json, "json");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.saver = Objects.requireNonNull(saver, "saver");
        this.history = Objects.requireNonNull(history, "history");
        this.group = Objects.requireNonNull(group, "group");
    }

    /** The tab's CSV actions: {@value #EXPORT}. */
    List<PanelAction> actions() {
        return List.of(exportAction());
    }

    private PanelAction exportAction() {
        return new PanelAction(EXPORT, EXPORT_LABEL, null,
                List.of(PanelAction.Argument.json(STATEMENT, "Statement", exportSchema())), this::exportCsv, group,
                Failures.cut("A JDQL query whose whole result is downloaded as a CSV file: FROM Product for every "
                        + "attribute of an entity, SELECT name, price FROM Product for some. Every row, the file at "
                        + "most 256 KiB; an empty field is null, \"\" the empty text.\nEntities: "
                        + jdql.entityNames(), PanelAction.MAX_DESCRIPTION - 1));
    }

    /** The JSON Schema of an export's {@value #STATEMENT}: the query, its parameters, the separator. */
    static String exportSchema() {
        return Json.write(Scalars.object("type", "object", "properties", Scalars.object(
                        QUERY_MEMBER, Scalars.object("type", "string", "format", "textarea",
                                "description", "FROM Product ORDER BY id, or SELECT name, price FROM Product"),
                        PARAMS_MEMBER, Scalars.object("type", "string", "format", "textarea",
                                "description", "the named parameters, a JSON object: {\"min\": 3}"),
                        SEPARATOR_MEMBER, separatorSchema()),
                "required", List.of(QUERY_MEMBER)));
    }

    /** {@code ,}, the default, or {@code ;}. */
    static Map<String, Object> separatorSchema() {
        return Scalars.object("type", "string", "enum", SEPARATORS, "default", ",",
                "description", "between the fields: , or ; as a spreadsheet of a decimal-comma locale reads it");
    }

    /** Runs an export with the arguments the console checked, and keeps the call. */
    PanelAction.ActionResult exportCsv(Map<String, String> given) {
        long start = System.nanoTime();
        String sent = given.getOrDefault(STATEMENT, "{}");
        Object parsed = null;
        Export export = null;
        Class<?> entity = null;
        PanelAction.ActionResult result;
        try {
            parsed = JdqlActions.parse(STATEMENT, sent);
            export = readStatement(parsed);
            if (JdqlExecutor.isWrite(export.query())) {
                throw new JdqlActions.Refused("an UPDATE or DELETE: export a query");
            }
            entity = jdql.entity(export.query());
            EntityModel<?> model = jdql.model(entity);
            Object runtime = jdql.runtime();
            result = runExport(export, entity, model, runtime, start);
        } catch (JdqlActions.Refused refused) {
            result = new PanelAction.ActionResult(Failures.line(refused.getMessage()), null, null, true,
                    exportDetails(entity, export));
        }
        history.add(group, System.currentTimeMillis(), EXPORT_LABEL,
                (result.error() ? "error: " : "") + result.summary(), millis(start),
                parsed == null ? sent : Json.write(parsed),
                parsed instanceof Map<?, ?> ? EXPORT + " " + Json.write(Scalars.object(STATEMENT, parsed)) : "");
        return result;
    }

    private PanelAction.ActionResult runExport(Export export, Class<?> entity, EntityModel<?> model, Object runtime,
                                               long start) {
        String details = exportDetails(entity, export);
        // As a query: in a transaction always rolled back when there is a manager, read-only whatever runs.
        String runs = transactions.available() ? TransactionRunner.ROLLBACK : null;
        TransactionRunner.Outcome<Written> outcome = transactions.run(runs,
                () -> write(runner.run(export.query(), export.params(), model, runtime), entity, export.separator()));
        if (outcome.failure() != null) {
            LOG.log(System.Logger.Level.DEBUG, "Mansart Data: " + EXPORT + " failed: "
                    + outcome.failure().getClass().getName());
            String text = Failures.text(outcome.failure());
            return new PanelAction.ActionResult(Failures.line(text), PanelAction.ActionResult.TEXT, text, true,
                    details);
        }
        Written written = outcome.value();
        if (written == null) {
            return new PanelAction.ActionResult("larger than 256 KiB: narrow the query", null, null, true, details);
        }
        return new PanelAction.ActionResult(ResultJson.count(written.rows()) + " · " + size(written.bytes()) + " in "
                + millis(start) + " ms", PanelAction.ActionResult.CSV, written.text(), false, details);
    }

    /**
     * The CSV of a statement's result (CSV spec §3, §5): entities as their attributes in model order, a projection as
     * its columns, a count as {@code count}, an aggregate as {@code value}; {@code null} past {@value #MAX_BYTES}
     * UTF-8 bytes, never a part of it.
     */
    Written write(JdqlResult result, Class<?> entity, char separator) {
        Csv.Writer out = new Csv.Writer(separator, MAX_BYTES);
        boolean fits = switch (result) {
            case JdqlResult.Entities found -> out.add(json.names(entity)) && writeEntities(found.entities(), out);
            case JdqlResult.Rows rows -> out.add(rows.columns()) && writeRows(rows, out);
            case JdqlResult.Count count -> out.add(List.of("count"))
                    && out.add(List.of(Long.toString(count.count())));
            case JdqlResult.Value value -> out.add(List.of("value"))
                    && out.add(Collections.singletonList(cell(value.value())));
        };
        return fits ? new Written(out.text(), out.records() - 1, out.bytes()) : null;
    }

    private boolean writeEntities(List<?> entities, Csv.Writer out) {
        for (Object entity : entities) {
            List<String> fields = new ArrayList<>();
            for (Object value : json.toJson(entity).values()) {
                fields.add(text(value));
            }
            if (!out.add(fields)) {
                return false;
            }
        }
        return true;
    }

    private boolean writeRows(JdqlResult.Rows rows, Csv.Writer out) {
        int columns = rows.columns().size();
        for (Object[] row : rows.rows()) {
            List<String> fields = new ArrayList<>(columns);
            for (int i = 0; i < columns; i++) {
                fields.add(i < row.length ? cell(row[i]) : null);
            }
            if (!out.add(fields)) {
                return false;
            }
        }
        return true;
    }

    /** A projection's or an aggregate's value as text: an entity as its id, anything else as its JSON value. */
    private String cell(Object value) {
        if (value != null && json.isEntity(value.getClass())) {
            return text(Scalars.toJson(json.idOf(value)));
        }
        return text(Scalars.toJson(value));
    }

    /** A JSON value as the text of a field: {@code null} as it is, a {@code BigDecimal} plain, the rest as text. */
    static String text(Object json) {
        if (json == null) {
            return null;
        }
        return json instanceof BigDecimal decimal ? decimal.toPlainString() : json.toString();
    }

    /** {@code 90 B} under 1024 bytes, {@code 3.1 KiB} from it. */
    static String size(long bytes) {
        return bytes < 1024 ? bytes + " B" : String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
    }

    /** An export's members: {@code query}, {@code params} optional, {@code separator} optional; nothing else. */
    private static Export readStatement(Object parsed) throws JdqlActions.Refused {
        Map<?, ?> members = members(parsed, STATEMENT, Set.of(QUERY_MEMBER, PARAMS_MEMBER, SEPARATOR_MEMBER));
        String query = text(members, QUERY_MEMBER).strip();
        return new Export(query, JdqlActions.params(members.get(PARAMS_MEMBER)), separator(members));
    }

    /** {@code parsed} as a JSON object whose members are all {@code allowed}. */
    private static Map<?, ?> members(Object parsed, String argument, Set<String> allowed) throws JdqlActions.Refused {
        if (!(parsed instanceof Map<?, ?> members)) {
            throw new JdqlActions.Refused(argument + ": not a JSON object");
        }
        for (Object name : members.keySet()) {
            if (!allowed.contains(name)) {
                throw new JdqlActions.Refused(name + ": unknown argument");
            }
        }
        return members;
    }

    /** A member that must be a text that is not blank. */
    private static String text(Map<?, ?> members, String name) throws JdqlActions.Refused {
        Object value = members.get(name);
        if (value != null && !(value instanceof String)) {
            throw new JdqlActions.Refused(name + ": not a string");
        }
        if (value == null || ((String) value).isBlank()) {
            throw new JdqlActions.Refused(name + ": missing");
        }
        return (String) value;
    }

    /** {@code separator}: absent for {@code ,}, else {@code ,} or {@code ;}. */
    private static char separator(Map<?, ?> members) throws JdqlActions.Refused {
        Object value = members.get(SEPARATOR_MEMBER);
        if (value == null) {
            return ',';
        }
        if (!SEPARATORS.contains(value)) {
            throw new JdqlActions.Refused(SEPARATOR_MEMBER + ": \",\" or \";\"");
        }
        return ((String) value).charAt(0);
    }

    /** What the page shows under "Exchange" for an export: the entity, the statement, its parameters, the separator. */
    private static String exportDetails(Class<?> entity, Export export) {
        return Json.write(Scalars.object("entity", entity == null ? null : entity.getName(),
                "query", export == null ? null : export.query(),
                "params", export == null ? Map.of() : export.params(),
                "separator", export == null ? null : String.valueOf(export.separator())));
    }

    private static long millis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
```

- [ ] **Step 8: Run the module's tests**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu -q -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev test 2>&1 | tail -30
```
(`timeout: 600000`.) Expected: no `[ERROR]` line, exit code 0 — `CsvActionsTest`, `EntityJsonTest`, and the
unchanged `JdqlActionsTest` (its refusal messages come through the same `Refused`) all green.

- [ ] **Step 9: Commit**

Message:
```
feat(mansart-data): export a JDQL query as CSV

CsvActions adds jdql.export to the JDQL tab: the query runs as Query does,
in a transaction always rolled back, and its whole result comes back as
text/csv -- an entity's attributes in model order without its joined
ones, a projection's columns, count or value -- or, past 256 KiB of
UTF-8, a refusal rather than a cut file. JdqlActions shares its entity
lookup, model and runtime resolution; EntityJson gives the header and a
reference's id; EntitySaver is the seam the import will save through.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && git add vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/JdqlActions.java vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/EntityJson.java vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/EntitySaver.java vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/CsvActions.java vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/RunFixtures.java vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/CsvActionsTest.java vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/EntityJsonTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 5: DEV — *Import CSV* (spec §4, §5, §6)

**Files:**
- Modify: `DEV/src/main/java/.../dev/Scalars.java` (`fromText`), `EntityJson.java` (`columns`, `textRows`),
  `CsvActions.java` (the import)
- Test: `DEV/src/test/java/.../dev/ScalarsFromTextTest.java` (new), `EntityJsonTest.java`, `CsvActionsTest.java`

**Interfaces:**
- Consumes: Task 3's `Csv.read/Record/Malformed`; Task 4's `CsvActions` (fields, `members`, `text`, `separator`,
  `separatorSchema`, `millis`), `JdqlActions.parse/named/model/runtime/names/Refused`, `EntitySaver`;
  `TransactionRunner.run/available/modes`, `ResultJson.count`, `Failures`, `ArgumentException`.
- Produces:
  - `static Object Scalars.fromText(Class<?> type, String text, String name) throws ArgumentException`;
  - `List<String> EntityJson.columns(Class<?> entity)`;
    `EntityJson.TextRows EntityJson.textRows(Class<?> entity, List<String> columns) throws ArgumentException`;
    `@FunctionalInterface interface EntityJson.TextRows { Object build(List<String> values) throws ArgumentException; }`;
  - `CsvActions.actions()` = export then import; `PanelAction.ActionResult CsvActions.importCsv(Map<String, String>
    given)`; `static String CsvActions.importSchema(List<String> entities)`.

- [ ] **Step 1: Write the failing tests**

Create `DEV/src/test/java/.../dev/ScalarsFromTextTest.java`:
```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A field of a CSV file converted by the attribute's type (CSV spec §4, §5), as the page's JSON would be. */
class ScalarsFromTextTest {

    private static String refused(Class<?> type, String text) {
        return assertThrows(ArgumentException.class, () -> Scalars.fromText(type, text, "x")).getMessage();
    }

    @Test
    void aCsvFieldIsReadAsTheJsonThePageWouldSend() throws ArgumentException {
        assertEquals(3, Scalars.fromText(int.class, "3", "stock"));
        assertEquals(1000L, Scalars.fromText(Long.class, "1e3", "id"));
        assertEquals(new BigDecimal("2.50"), Scalars.fromText(BigDecimal.class, "2.50", "price"));
        assertEquals(2.5, Scalars.fromText(double.class, "2.5", "price"));
        assertEquals(1.0E10, Scalars.fromText(Double.class, "1.0E10", "price"), "what Double.toString writes");
        assertEquals(Boolean.TRUE, Scalars.fromText(boolean.class, "true", "done"));
        assertEquals(Boolean.FALSE, Scalars.fromText(Boolean.class, "false", "done"));
        assertEquals(DayOfWeek.MONDAY, Scalars.fromText(DayOfWeek.class, "MONDAY", "day"));
        assertEquals(LocalDate.of(2026, 10, 1), Scalars.fromText(LocalDate.class, "2026-10-01", "due"));
        UUID uuid = UUID.fromString("1b4e28ba-2fa1-11d2-883f-0016d3cca427");
        assertEquals(uuid, Scalars.fromText(UUID.class, uuid.toString(), "key"));
        assertEquals("", Scalars.fromText(String.class, "", "name"), "\"\": the empty text");
        assertEquals(" a ", Scalars.fromText(String.class, " a ", "name"), "never trimmed");
        assertNull(Scalars.fromText(String.class, null, "name"), "an empty field: null");
        assertNull(Scalars.fromText(Integer.class, null, "stock"));
    }

    @Test
    void aCsvFieldThatDoesNotConvertIsRefusedNamingItsColumn() {
        assertEquals("x: not an integer", refused(int.class, "2.5"));
        assertEquals("x: not an integer", refused(int.class, ""));
        assertEquals("x: not an integer", refused(Integer.class, " 3"), "never trimmed");
        assertEquals("x: not a number", refused(BigDecimal.class, "2,50"), "a decimal comma is no number");
        assertEquals("x: not a number", refused(double.class, "2,5"));
        assertEquals("x: out of range for int", refused(int.class, "3000000000"));
        assertEquals("x: null is not allowed for int", refused(int.class, null));
        assertEquals("x: not a boolean", refused(boolean.class, "yes"));
        assertEquals("x: no constant FUNDAY in DayOfWeek", refused(DayOfWeek.class, "FUNDAY"));
        assertEquals("x: not an ISO date", refused(LocalDate.class, "01/10/2026"));
    }
}
```
In `EntityJsonTest.java`, replace exactly:
```java
import java.time.LocalDate;
import java.util.List;
```
with:
```java
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
```
Then replace exactly:
```java
    @Test
    void onlyTheCatalogueEntitiesAreEntities() {
```
with:
```java
    @Test
    void anImportSetsTheAttributesThatConvertAReferenceByItsId() throws ArgumentException {
        assertEquals(List.of("id", "name", "stock", "level", "due", "price"), entities.columns(Gizmo.class));
        assertEquals(List.of("id", "gizmo", "label"), entities.columns(Part.class));
        assertEquals(List.of("id", "label"), entities.columns(RunFixtures.Slot.class), "a Year cannot be converted");

        EntityJson.TextRows parts = entities.textRows(Part.class, List.of("label", "gizmo"));

        assertEquals("{\"id\":null,\"gizmo\":7,\"label\":\"left\"}",
                Json.write(entities.toJson(parts.build(Arrays.asList("left", "7")))));
        assertEquals("{\"id\":null,\"gizmo\":null,\"label\":null}",
                Json.write(entities.toJson(parts.build(Arrays.asList(null, null)))));
        assertEquals("gizmo: not an integer",
                assertThrows(ArgumentException.class, () -> parts.build(Arrays.asList("x", "seven"))).getMessage());
        assertEquals("year: not an attribute that can be set", assertThrows(ArgumentException.class,
                () -> entities.textRows(RunFixtures.Slot.class, List.of("year"))).getMessage());
    }

    @Test
    void onlyTheCatalogueEntitiesAreEntities() {
```
In `CsvActionsTest.java`, replace exactly:
```java
    @Test
    void anExportIsKeptInTheTabsHistoryWithItsReplay() {
```
with:
```java
    private static ActionResult load(CsvActions tab, String entity, String csv, String separator, String transaction) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("entity", entity);
        file.put("csv", csv);
        if (separator != null) {
            file.put("separator", separator);
        }
        Map<String, String> given = new HashMap<>();
        given.put(CsvActions.FILE, Json.write(file));
        if (transaction != null) {
            given.put("transaction", transaction);
        }
        return tab.importCsv(given);
    }

    private String saved(int i) {
        return Json.write(json.toJson(saver.saved.get(i)));
    }

    private void assertNothingSaved() {
        assertEquals(List.of(), saver.saved, "nothing saved");
        assertEquals(List.of(), manager.events, "no transaction begun");
    }

    @Test
    void theImportActionAsksFirstAndTakesAFileAndATransaction() {
        List<PanelAction> actions = tab().actions();

        assertEquals(List.of(CsvActions.EXPORT, CsvActions.IMPORT), actions.stream().map(PanelAction::id).toList());
        PanelAction load = actions.get(1);
        assertEquals("Import CSV", load.label());
        assertEquals("JDQL", load.group());
        assertEquals("Saves these CSV rows against the database.", load.confirmation());
        assertEquals(List.of("file", "transaction"), load.arguments().stream().map(PanelAction.Argument::name)
                .toList());
        assertEquals(List.of("rollback", "commit"), load.arguments().get(1).allowedValues());
        String schema = load.arguments().getFirst().schema();
        assertTrue(schema.contains("\"entity\":{\"type\":\"string\",\"enum\":[\"Gizmo\",\"Part\",\"Slot\"]"), schema);
        assertTrue(schema.contains("\"csv\":{\"type\":\"string\",\"format\":\"textarea\","
                + "\"contentMediaType\":\"text/csv\""), schema);
        assertTrue(schema.endsWith("\"required\":[\"entity\",\"csv\"]}"), schema);
    }

    @Test
    void eachRowIsAnEntitySavedInOrderInOneTransactionRolledBackByDefault() {
        ActionResult result = load(tab(), "Gizmo",
                "id,name,stock,level,due,price\r\n,bolt,3,LOW,2026-10-01,2.50\n7,\"\",0,HIGH,,\r\n", null, null);

        assertFalse(result.error(), result.summary());
        assertEquals("2 rows saved · rolled back", result.summary());
        assertEquals(ActionResult.JSON, result.contentType());
        assertEquals("{\"entity\":\"" + Gizmo.class.getName() + "\",\"saved\":2,\"transaction\":\"rollback\"}",
                result.body());
        assertEquals("{\"id\":null,\"name\":\"bolt\",\"stock\":3,\"level\":\"LOW\",\"due\":\"2026-10-01\","
                + "\"price\":2.50}", saved(0), "an empty id: an insert, its id generated");
        assertEquals("{\"id\":7,\"name\":\"\",\"stock\":0,\"level\":\"HIGH\",\"due\":null,\"price\":null}", saved(1),
                "an id kept for an upsert; \"\" the empty text, an empty field null");
        assertEquals(List.of(Gizmo.class, Gizmo.class), saver.models);
        assertEquals(List.of(List.of("begin"), List.of("begin")), saver.eventsDuringSave, "saved inside one transaction");
        assertEquals(List.of("begin", "rollback"), manager.events);
        assertEquals("{\"entity\":\"" + Gizmo.class.getName() + "\",\"rows\":2,\"separator\":\",\","
                + "\"transaction\":\"rollback\"}", result.details());
    }

    @Test
    void anImportCommitsWhenAsked() {
        ActionResult result = load(tab(), "Gizmo", "name\r\nbolt\r\n", null, "commit");

        assertEquals("1 row saved · committed", result.summary());
        assertEquals(List.of("begin", "commit"), manager.events);
    }

    @Test
    void withoutATransactionManagerAnImportCanOnlyBeCommitted() {
        CsvActions tab = tab(TransactionRunner.NONE, BEANS);

        assertEquals(List.of("commit"), tab.actions().get(1).arguments().get(1).allowedValues());
        assertEquals("2 rows saved · committed", load(tab, "Gizmo", "name\r\nbolt\r\nnut\r\n", null, null).summary());
        assertEquals(List.of(), manager.events);
    }

    @Test
    void withoutATransactionManagerTheRowsBeforeAFailingOneStayAndTheLineSaysSo() {
        saver.failAt = 2;

        ActionResult result = load(tab(TransactionRunner.NONE, BEANS), "Gizmo", "name\r\nbolt\r\nnut\r\nscrew\r\n",
                null, "commit");

        assertTrue(result.error());
        assertEquals("line 3: java.lang.IllegalStateException: duplicate key on jdbc:h2:tcp://***:***@db/x"
                + " · 1 row before it stays committed", result.summary());
        assertEquals(1, saver.saved.size());
    }

    @Test
    void aSaveThatThrowsRollsEverythingBackNamingItsLine() {
        saver.failAt = 2;
        CsvActions tab = tab();

        ActionResult result = load(tab, "Gizmo", "name\r\nbolt\r\nnut\r\nscrew\r\n", null, "commit");

        String expected = "line 3: java.lang.IllegalStateException: duplicate key on jdbc:h2:tcp://***:***@db/x";
        assertTrue(result.error());
        assertEquals(expected, result.summary());
        assertEquals(ActionResult.TEXT, result.contentType());
        assertEquals(expected, result.body());
        assertEquals(List.of("begin", "rollback"), manager.events, "rolled back although commit was asked");
        CallHistory.Call call = history.calls(JdqlActions.GROUP).getFirst();
        assertEquals("error: " + expected, call.outcome());
        assertFalse(call.toString().contains(SECRET));
    }

    @Test
    void theHeaderNamesAttributesThatCanBeSetOnceEach() {
        CsvActions tab = tab();

        assertRefused(load(tab, "Gizmo", "name,colour\r\nbolt,red\r\n", null, null),
                "header: unknown attribute colour; attributes: id, name, stock, level, due, price");
        assertRefused(load(tab, "Part", "label,name\r\nleft,bolt\r\n", null, null),
                "header: unknown attribute name; attributes: id, gizmo, label");
        assertRefused(load(tab, "Slot", "id,year,label\r\n1,2026,x\r\n", null, null),
                "header: year cannot be imported; attributes: id, label");
        assertRefused(load(tab, "Gizmo", "name,stock,name\r\nbolt,3,nut\r\n", null, null), "header: name twice");
        assertRefused(load(tab, "Gizmo", "name,,stock\r\nbolt,,3\r\n", null, null), "header: column 2 has no name");
        assertNothingSaved();
    }

    @Test
    void aConversionErrorNamesTheLineOfTheTextAndTheAttribute() {
        CsvActions tab = tab();

        assertRefused(load(tab, "Part", "id,gizmo,label\r\n1,1,\"two\r\nlines\"\r\n2,x,y\r\n", null, null),
                "line 4, gizmo: not an integer");
        assertRefused(load(tab, "Gizmo", "name;price\r\nbolt;2,50\r\n", ";", null), "line 2, price: not a number");
        assertRefused(load(tab, "Gizmo", "name,stock\r\nbolt,\r\n", null, null),
                "line 2, stock: null is not allowed for int");
        assertRefused(load(tab, "Gizmo", "name,level\r\nbolt,URGENT\r\n", null, null),
                "line 2, level: no constant URGENT in Level");
        assertRefused(load(tab, "Gizmo", "name,due\r\nbolt,tomorrow\r\n", null, null),
                "line 2, due: not an ISO date");
        assertRefused(load(tab, "Gizmo", "name,stock\r\nbolt\r\n", null, null), "line 2: 1 field, the header has 2");
        assertRefused(load(tab, "Gizmo", "name,stock\r\nbolt,3,x\r\n", null, null),
                "line 2: 3 fields, the header has 2");
        assertRefused(load(tab, "Gizmo", "name\r\n\"bolt\r\n", null, null), "line 2: unterminated quoted field");
        assertNothingSaved();
    }

    @Test
    void aReferenceIsBuiltFromItsIdAndASemicolonFileKeepsItsQuotedFields() {
        CsvActions tab = tab();

        assertFalse(load(tab, "Part", "id;gizmo;label\r\n5;1;\"a;b\"\r\n", ";", null).error());
        assertFalse(load(tab, "Gizmo", "﻿name;price\r\n\"c,d\";2.50\r\n", ";", null).error());

        assertEquals("{\"id\":5,\"gizmo\":1,\"label\":\"a;b\"}", saved(0));
        assertEquals("{\"id\":null,\"name\":\"c,d\",\"stock\":0,\"level\":null,\"due\":null,\"price\":2.50}", saved(1),
                "a byte order mark ignored; an attribute without a column keeps the constructor's value");
    }

    @Test
    void atMost5000Rows() {
        CsvActions tab = tab();

        assertRefused(load(tab, "Gizmo", "name\r\n" + "b\r\n".repeat(5001), null, null),
                "more than 5000 rows: split the file");
        assertEquals(List.of(), saver.saved);
        assertEquals("5000 rows saved · rolled back",
                load(tab, "Gizmo", "name\r\n" + "b\r\n".repeat(5000), null, null).summary());
        assertEquals(5000, saver.saved.size());
    }

    @Test
    void whatIsRefusedBeforeReadingSavesNothing() {
        CsvActions tab = tab();

        assertRefused(load(tab, "Gizmo", "name\r\n", null, null), "no row: the file has a header only");
        assertRefused(load(tab, "Gizmo", "  ", null, null), "csv: missing");
        assertRefused(load(tab, "Nope", "name\r\nb\r\n", null, null), "unknown entity Nope; entities: Gizmo, Part, Slot");
        assertRefused(load(tab, "Gizmo", "name\r\nb\r\n", "|", null), "separator: \",\" or \";\"");
        assertRefused(tab.importCsv(Map.of(CsvActions.FILE, "{\"csv\":\"name\\r\\nb\"}")), "entity: missing");
        assertRefused(tab.importCsv(Map.of(CsvActions.FILE, "{\"entity\":\"Gizmo\",\"csv\":\"a\",\"x\":1}")),
                "x: unknown argument");
        assertRefused(tab.importCsv(Map.of(CsvActions.FILE, "{\"query\":\"FROM Gizmo\"")),
                "file: not valid JSON at character 22");
        assertRefused(load(tab(TransactionRunner.NONE, type -> {
            throw new BeanLookup.NoBean();
        }), "Gizmo", "name\r\nb\r\n", null, null), "no RepositoryRuntime bean");
        assertNothingSaved();
    }

    @Test
    void anImportIsKeptWithItsArgumentsCutAndAReplayOnlyWhileItIsShort() {
        CsvActions tab = tab();

        load(tab, "Gizmo", "name\r\nbolt\r\n", null, "commit");
        load(tab, "Gizmo", "name\r\n" + "b\r\n".repeat(2000), null, "rollback");

        List<CallHistory.Call> calls = history.calls(JdqlActions.GROUP);
        assertEquals("Import CSV", calls.get(1).method());
        assertEquals("1 row saved · committed", calls.get(1).outcome());
        assertEquals("jdql.import {\"file\":{\"entity\":\"Gizmo\",\"csv\":\"name\\r\\nbolt\\r\\n\"},"
                + "\"transaction\":\"commit\"}", calls.get(1).replay());
        assertEquals(201, calls.getFirst().arguments().length(), "200 characters and …");
        assertEquals("", calls.getFirst().replay(), "past 4096 characters, no replay");
    }

    @Test
    void anExportIsKeptInTheTabsHistoryWithItsReplay() {
```

- [ ] **Step 2: Run them to see them fail**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu -q -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev test -Dtest=CsvActionsTest+EntityJsonTest+ScalarsFromTextTest 2>&1 | tail -20
```
Expected: `COMPILATION ERROR`, `cannot find symbol` … `fromText`, `columns`, `textRows`, `importCsv`.

- [ ] **Step 3: `Scalars.fromText`**

In `Scalars.java`, replace exactly:
```java
    /**
     * The JSON of a value a method returns (spec §6): {@code null}, a number, a boolean or a string as it is, an enum
```
with:
```java
    /**
     * {@code text}, a field of a CSV file (CSV spec §4, §5), as a {@code type}: read as the JSON value the page would
     * send for it — a number from its text ({@code new BigDecimal}), exactly {@code true} or {@code false} as a
     * boolean, anything else as a string, never trimmed — then converted as {@link #fromJson} does, its refusals
     * included. {@code null}, an empty unquoted field, stays {@code null}; a primitive refuses it.
     *
     * @param name the column it is for, which a refusal names
     * @throws ArgumentException when it does not convert
     */
    static Object fromText(Class<?> type, String text, String name) throws ArgumentException {
        if (text == null) {
            return fromJson(type, null, name);
        }
        if (INTEGERS.contains(type) || NUMBERS.contains(type)) {
            BigDecimal number;
            try {
                number = new BigDecimal(text);
            } catch (NumberFormatException notANumber) {
                throw new ArgumentException(name, INTEGERS.contains(type) ? "not an integer" : "not a number");
            }
            return fromJson(type, number, name);
        }
        if (type == boolean.class || type == Boolean.class) {
            return fromJson(type, "true".equals(text) ? Boolean.TRUE : "false".equals(text) ? Boolean.FALSE : text,
                    name);
        }
        return fromJson(type, text, name);
    }

    /**
     * The JSON of a value a method returns (spec §6): {@code null}, a number, a boolean or a string as it is, an enum
```

- [ ] **Step 4: `EntityJson` builds a row**

In `EntityJson.java`, replace exactly:
```java
            Object value = Scalars.fromJson(property.type(), member.getValue(), path);
            if (property.referenced() != null && value != null) {
                Object reference = construct(property.referenced());
                set(property.referenced().id().setter(), reference, value);
                value = reference;
            }
            set(property.attribute().setter(), instance, value);
        }
        return instance;
    }
```
with:
```java
            put(instance, property, Scalars.fromJson(property.type(), member.getValue(), path));
        }
        return instance;
    }

    /**
     * The names a CSV import may give {@code entity}'s attributes (CSV spec §4), in model order: those
     * {@link #fromJson} sets, each with a setter and a type {@link Scalars} converts, a reference by its id.
     *
     * @throws RuntimeException when its model cannot be read, as {@code EntityModels.of} throws it
     */
    List<String> columns(Class<?> entity) {
        return List.copyOf(settable(model(entity)).keySet());
    }

    /**
     * Builds {@code entity} instances from CSV records whose header is {@code columns}, the model read once: for each
     * record its no-arg constructor, then each column's text converted by the attribute's type
     * ({@link Scalars#fromText}) and set with its setter handle, a reference as a new instance of the referenced
     * entity holding that id. An attribute without a column keeps the constructor's value.
     *
     * @throws ArgumentException for a column no attribute of {@link #columns} has
     */
    TextRows textRows(Class<?> entity, List<String> columns) throws ArgumentException {
        EntityModel<?> model = model(entity);
        Map<String, Settable> settable = settable(model);
        List<Settable> properties = new ArrayList<>(columns.size());
        for (String column : columns) {
            Settable property = settable.get(column);
            if (property == null) {
                throw new ArgumentException(column, "not an attribute that can be set");
            }
            properties.add(property);
        }
        return values -> {
            Object instance = construct(model);
            for (int i = 0; i < properties.size(); i++) {
                Settable property = properties.get(i);
                put(instance, property, Scalars.fromText(property.type(), values.get(i), property.attribute().name()));
            }
            return instance;
        };
    }

    /** One entity from each CSV record of a {@link #textRows} call. */
    @FunctionalInterface
    interface TextRows {

        /**
         * @param values the record's fields, one per column, {@code null} for an empty unquoted one
         * @throws ArgumentException naming the column whose field does not convert: {@code price: not a number}
         */
        Object build(List<String> values) throws ArgumentException;
    }

    /** Sets {@code value} on {@code instance}; for a reference, a new instance of the referenced entity with that id. */
    private static void put(Object instance, Settable property, Object value) {
        Object set = value;
        if (property.referenced() != null && value != null) {
            set = construct(property.referenced());
            set(property.referenced().id().setter(), set, value);
        }
        set(property.attribute().setter(), instance, set);
    }
```

- [ ] **Step 5: The import action**

In `CsvActions.java`, replace exactly:
```java
    /** The tab's CSV actions: {@value #EXPORT}. */
    List<PanelAction> actions() {
        return List.of(exportAction());
    }
```
with:
```java
    /** The tab's CSV actions: {@value #EXPORT}, then {@value #IMPORT}. */
    List<PanelAction> actions() {
        return List.of(exportAction(), importAction());
    }

    private PanelAction importAction() {
        return new PanelAction(IMPORT, IMPORT_LABEL, CONFIRMATION, List.of(
                PanelAction.Argument.json(FILE, "File", importSchema(jdql.names())),
                new PanelAction.Argument(RepositoryActions.TRANSACTION, "Transaction", transactions.modes(), null,
                        null)), this::importCsv, group,
                Failures.cut("Saves each row of a CSV file as an entity, in one transaction rolled back unless commit "
                        + "is asked: an insert when its id is empty, else an update or an insert with that id. The "
                        + "header names the attributes; an empty field is null, \"\" the empty text. At most 5000 "
                        + "rows, a file of at most 60 KiB.", PanelAction.MAX_DESCRIPTION - 1));
    }
```
Then replace exactly:
```java
    private static long millis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
```
with:
```java
    /**
     * An import's arguments.
     *
     * @param entity    the entity, as the catalogue names it
     * @param csv       the CSV text
     * @param separator between the fields
     */
    private record Load(String entity, String csv, char separator) {}

    /**
     * One row read.
     *
     * @param line   the line of the text it starts on
     * @param entity the entity it built
     */
    private record Row(int line, Object entity) {}

    /** The JSON Schema of {@value #FILE}: the entity, one of {@code entities}; the CSV text; the separator. */
    static String importSchema(List<String> entities) {
        return Json.write(Scalars.object("type", "object", "properties", Scalars.object(
                        ENTITY_MEMBER, Scalars.object("type", "string", "enum", entities,
                                "description", "the entity each row becomes"),
                        CSV_MEMBER, Scalars.object("type", "string", "format", "textarea",
                                "contentMediaType", "text/csv",
                                "description", "a header of attribute names, then one row per entity"),
                        SEPARATOR_MEMBER, separatorSchema()),
                "required", List.of(ENTITY_MEMBER, CSV_MEMBER)));
    }

    /**
     * Reads the CSV file the page sent, then saves its rows (CSV spec §4), with the arguments the console checked;
     * keeps the call. Nothing is saved, nor any transaction begun, before every row is read.
     */
    PanelAction.ActionResult importCsv(Map<String, String> given) {
        long start = System.nanoTime();
        String sent = given.getOrDefault(FILE, "{}");
        String mode = given.getOrDefault(RepositoryActions.TRANSACTION, transactions.modes().getFirst());
        Object parsed = null;
        Load load = null;
        Class<?> entity = null;
        List<Row> rows = null;
        PanelAction.ActionResult result;
        try {
            parsed = JdqlActions.parse(FILE, sent);
            load = readFile(parsed);
            entity = jdql.named(load.entity());
            EntityModel<?> model = jdql.model(entity);
            rows = read(load, entity);
            Object runtime = jdql.runtime();
            result = save(entity, model, rows, runtime, mode, importDetails(entity, rows, load, mode));
        } catch (JdqlActions.Refused refused) {
            result = new PanelAction.ActionResult(Failures.line(refused.getMessage()), null, null, true,
                    importDetails(entity, rows, load, mode));
        }
        history.add(group, System.currentTimeMillis(), IMPORT_LABEL,
                (result.error() ? "error: " : "") + result.summary(), millis(start),
                parsed == null ? sent : Json.write(parsed), parsed instanceof Map<?, ?>
                        ? IMPORT + " " + Json.write(Scalars.object(FILE, parsed, RepositoryActions.TRANSACTION, mode))
                        : "");
        return result;
    }

    /** An import's members: {@code entity}, {@code csv}, {@code separator} optional; nothing else. */
    private static Load readFile(Object parsed) throws JdqlActions.Refused {
        Map<?, ?> members = members(parsed, FILE, Set.of(ENTITY_MEMBER, CSV_MEMBER, SEPARATOR_MEMBER));
        return new Load(text(members, ENTITY_MEMBER).strip(), text(members, CSV_MEMBER), separator(members));
    }

    /** Phase 1: the header, then each row built into an entity; the first failure stops it with its line. */
    private List<Row> read(Load load, Class<?> entity) throws JdqlActions.Refused {
        List<Csv.Record> records;
        try {
            records = Csv.read(load.csv(), load.separator());
        } catch (Csv.Malformed malformed) {
            throw new JdqlActions.Refused(malformed.getMessage());
        }
        if (records.isEmpty()) {
            throw new JdqlActions.Refused(CSV_MEMBER + ": missing");
        }
        List<String> header = header(records.getFirst().fields(), entity);
        List<Csv.Record> lines = records.subList(1, records.size());
        if (lines.isEmpty()) {
            throw new JdqlActions.Refused("no row: the file has a header only");
        }
        if (lines.size() > MAX_ROWS) {
            throw new JdqlActions.Refused("more than " + MAX_ROWS + " rows: split the file");
        }
        EntityJson.TextRows build;
        try {
            build = json.textRows(entity, header);
        } catch (ArgumentException refused) {
            throw new JdqlActions.Refused("header: " + refused.getMessage());
        } catch (RuntimeException | LinkageError unreadable) {
            throw new JdqlActions.Refused(Failures.text(unreadable));
        }
        List<Row> rows = new ArrayList<>(lines.size());
        for (Csv.Record record : lines) {
            int count = record.fields().size();
            if (count != header.size()) {
                throw new JdqlActions.Refused("line " + record.line() + ": " + count
                        + (count == 1 ? " field" : " fields") + ", the header has " + header.size());
            }
            try {
                rows.add(new Row(record.line(), build.build(record.fields())));
            } catch (ArgumentException refused) {
                throw new JdqlActions.Refused("line " + record.line() + ", " + refused.getMessage());
            } catch (RuntimeException | LinkageError failed) {
                throw new JdqlActions.Refused("line " + record.line() + ": " + Failures.text(failed));
            }
        }
        return rows;
    }

    /** The header: each name an attribute an import may set, once each. */
    private List<String> header(List<String> names, Class<?> entity) throws JdqlActions.Refused {
        List<String> columns;
        List<String> known;
        try {
            columns = json.columns(entity);
            known = json.names(entity);
        } catch (RuntimeException | LinkageError unreadable) {
            throw new JdqlActions.Refused(Failures.text(unreadable));
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (name == null || name.isEmpty()) {
                throw new JdqlActions.Refused("header: column " + (i + 1) + " has no name");
            }
            if (!columns.contains(name)) {
                throw new JdqlActions.Refused("header: " + (known.contains(name) ? name + " cannot be imported"
                        : "unknown attribute " + name) + "; attributes: " + String.join(", ", columns));
            }
            if (!seen.add(name)) {
                throw new JdqlActions.Refused("header: " + name + " twice");
            }
        }
        return names;
    }

    /** Phase 2: every entity saved, in order, in one transaction; a save that throws rolls it back with its line. */
    private PanelAction.ActionResult save(Class<?> entity, EntityModel<?> model, List<Row> rows, Object runtime,
                                          String mode, String details) {
        int[] saved = {0};
        TransactionRunner.Outcome<Integer> outcome = transactions.run(mode, () -> {
            for (Row row : rows) {
                try {
                    saver.save(model, row.entity(), runtime);
                } catch (RuntimeException | LinkageError failed) {
                    throw new RowFailed(row.line(), failed);
                }
                saved[0]++;
            }
            return saved[0];
        });
        if (outcome.failure() != null) {
            Throwable failure = outcome.failure();
            LOG.log(System.Logger.Level.DEBUG, "Mansart Data: " + IMPORT + " failed: "
                    + failure.getClass().getName());
            String text = failure instanceof RowFailed row
                    ? "line " + row.line + ": " + Failures.text(row.getCause()) : Failures.text(failure);
            if (!transactions.available() && saved[0] > 0) {
                // no transaction to roll back: each row was committed as it was saved
                text += saved[0] == 1 ? " · 1 row before it stays committed"
                        : " · " + saved[0] + " rows before it stay committed";
            }
            return new PanelAction.ActionResult(Failures.line(text), PanelAction.ActionResult.TEXT, text, true,
                    details);
        }
        int count = outcome.value();
        String summary = ResultJson.count(count) + " saved" + (outcome.state() == null ? "" : " · " + outcome.state());
        return new PanelAction.ActionResult(summary, PanelAction.ActionResult.JSON, Json.write(Scalars.object(
                "entity", entity.getName(), "saved", count, "transaction", mode)), false, details);
    }

    /** What the page shows under "Exchange" for an import: the entity, the rows read, the separator, the transaction. */
    private static String importDetails(Class<?> entity, List<Row> rows, Load load, String mode) {
        return Json.write(Scalars.object("entity", entity == null ? null : entity.getName(),
                "rows", rows == null ? null : rows.size(),
                "separator", load == null ? null : String.valueOf(load.separator()),
                "transaction", mode));
    }

    /** A save that threw: the line of its row, and what it threw as the cause. */
    private static final class RowFailed extends Exception {

        private static final long serialVersionUID = 1L;

        private final int line;

        RowFailed(int line, Throwable cause) {
            super(null, cause, false, false);
            this.line = line;
        }
    }

    private static long millis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
```

- [ ] **Step 6: Run the module's tests**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu -q -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev test 2>&1 | tail -30
```
(`timeout: 600000`.) Expected: no `[ERROR]` line, exit code 0 (`EntityJsonTest.builtWithItsConstructorThenEachPropertyPresent`
and the other `fromJson` tests stay green through `put`).

- [ ] **Step 7: Commit**

Message:
```
feat(mansart-data): import a CSV file into an entity

CsvActions adds jdql.import to the JDQL tab. The file is read whole
first: a header of attributes an import can set, once each, 1 to 5000
rows of as many fields, each built into an entity through its no-arg
constructor and setter handles, the text converted as the page's JSON
is (Scalars.fromText), a reference from its id; the first failure stops
it with its line. Then every entity is saved through
RepositoryRuntime.save, in order, in one transaction rolled back unless
commit is asked, a failing save rolling it all back with its line.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && git add vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/Scalars.java vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/EntityJson.java vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/CsvActions.java vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/ScalarsFromTextTest.java vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/EntityJsonTest.java vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/CsvActionsTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 6: DEV — the panel offers the CSV actions (spec §3, §4, §6)

**Files:**
- Modify: `DEV/src/main/java/.../dev/RepositoryActions.java`, `CatalogueLivePanel.java`, `CsvActions.java` (`COUNT`)
- Test: `DEV/src/test/java/.../dev/CsvActionsTest.java`, `CatalogueLivePanelTest.java`

**Interfaces:**
- Consumes: Tasks 4-5's `CsvActions`, `EntitySaver.MANSART`; #155's `RepositoryActions.build(…, int, JdqlRunner)`.
- Produces: `static final int CsvActions.COUNT = 2`; `static RepositoryActions RepositoryActions.build(List<Class<?>>,
  MansartDataCatalogue, BeanLookup, TransactionRunner, Function<Class<?>, EntityModel<?>>, Predicate<Method>, int,
  JdqlRunner, EntitySaver)` (the 8-argument one delegates with `null`: no CSV action); `CatalogueLivePanel.actions()`
  ends with `jdql.query`, `jdql.write`, `jdql.export`, `jdql.import`. The DEV module installed in `~/.m2`.

- [ ] **Step 1: Write the failing tests**

In `CsvActionsTest.java`, replace exactly:
```java
    @Test
    void anExportIsKeptInTheTabsHistoryWithItsReplay() {
```
with:
```java
    @Test
    void theJdqlTabEndsWithTheCsvActionsWhichCountAgainstThePanelsLimit() {
        List<String> ids = RepositoryActions.build(RunFixtures.REPOSITORIES, RunFixtures.catalogue(), BEANS,
                        TransactionRunner.NONE, RunFixtures::model, method -> true, 7, runner, saver)
                .actions().stream().map(PanelAction::id).toList();
        List<String> without = RepositoryActions.build(RunFixtures.REPOSITORIES, RunFixtures.catalogue(), BEANS,
                        TransactionRunner.NONE, RunFixtures::model, method -> true, 7, runner)
                .actions().stream().map(PanelAction::id).toList();

        assertEquals(7, ids.size());
        assertEquals(List.of(JdqlActions.QUERY, JdqlActions.WRITE, CsvActions.EXPORT, CsvActions.IMPORT),
                ids.subList(3, 7), "the repositories get what is left");
        assertEquals(List.of(JdqlActions.QUERY, JdqlActions.WRITE), without.subList(5, 7), "no saver, no CSV action");
    }

    @Test
    void theCsvCallsGoIntoThePanelsCallsTable() {
        RepositoryActions actions = RepositoryActions.build(RunFixtures.REPOSITORIES, RunFixtures.catalogue(), BEANS,
                new TransactionRunner(new JtaDemarcation(() -> manager)), RunFixtures::model, method -> true,
                RepositoryActions.MAX_ACTIONS, runner, saver);
        runner.answer = new JdqlResult.Count(3);
        PanelAction export = actions.actions().stream().filter(a -> a.id().equals(CsvActions.EXPORT)).findFirst()
                .orElseThrow();
        PanelAction load = actions.actions().stream().filter(a -> a.id().equals(CsvActions.IMPORT)).findFirst()
                .orElseThrow();

        export.call().apply(Map.of("statement", "{\"query\":\"SELECT COUNT(this) FROM Gizmo\"}"));
        load.call().apply(Map.of("file", "{\"entity\":\"Part\",\"csv\":\"label\\r\\nleft\\r\\n\"}",
                "transaction", "rollback"));

        RecordedSample sample = new RecordedSample();
        actions.sample(sample);
        Table calls = (Table) sample.value("calls");
        assertEquals("Import CSV", calls.rows().get(0).get(1));
        assertEquals("1 row saved · rolled back", calls.rows().get(0).get(2));
        assertEquals("Export CSV", calls.rows().get(1).get(1));
        assertEquals("jdql.export {\"statement\":{\"query\":\"SELECT COUNT(this) FROM Gizmo\"}}",
                calls.rows().get(1).get(5));
        assertEquals(List.of(Part.class), saver.models, "the catalogue's Part, by its name");
    }

    @Test
    void anExportIsKeptInTheTabsHistoryWithItsReplay() {
```
In `CatalogueLivePanelTest.java`, replace exactly:
```java
        assertEquals(25, actions.size());
        assertEquals(List.of("jdql.query", "jdql.write"),
                actions.subList(23, 25).stream().map(PanelAction::id).toList(), "the JDQL tab, last");
```
with:
```java
        assertEquals(27, actions.size());
        assertEquals(List.of("jdql.query", "jdql.write", "jdql.export", "jdql.import"),
                actions.subList(23, 27).stream().map(PanelAction::id).toList(), "the JDQL tab, last");
        assertEquals(List.of("commit"), actions.get(26).arguments().get(1).allowedValues(),
                "an import without a TransactionManager bean: commit only");
        assertEquals("no RepositoryRuntime bean", actions.get(25).call()
                .apply(Map.of("statement", "{\"query\":\"FROM Gizmo\"}")).summary());
```

- [ ] **Step 2: Run them to see them fail**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu -q -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev test -Dtest=CsvActionsTest+CatalogueLivePanelTest 2>&1 | tail -20
```
Expected: `COMPILATION ERROR`, `no suitable method found for build(…)` (the 9-argument `build`).

- [ ] **Step 3: `CsvActions.COUNT`**

In `CsvActions.java`, replace exactly:
```java
    /** The most rows an import reads. */
    static final int MAX_ROWS = 5000;
```
with:
```java
    /** The most rows an import reads. */
    static final int MAX_ROWS = 5000;
    /** How many actions the CSV adds to the tab. */
    static final int COUNT = 2;
```

- [ ] **Step 4: `RepositoryActions.build` with a saver**

In `RepositoryActions.java`, replace exactly:
```java
 * <p>After the repositories' tabs comes the <i>JDQL</i> tab of {@link JdqlActions}, whose calls the same history keeps.
```
with:
```java
 * <p>After the repositories' tabs comes the <i>JDQL</i> tab of {@link JdqlActions}, then its {@link CsvActions}, whose
 * calls the same history keeps.
```
Then replace exactly:
```java
    /**
     * The actions of {@code repositories}, in the catalogue's name order, then, when {@code jdql} is given and the
     * catalogue holds an entity, the {@value JdqlActions#COUNT} actions of the <i>JDQL</i> tab, which keep their calls
     * in the same history; the repositories then get {@code maxActions} less those.
     *
     * @param jdql runs a JDQL statement, {@link JdqlRunner#MANSART} outside tests; {@code null} for no JDQL tab
     * @see #build(List, MansartDataCatalogue, BeanLookup, TransactionRunner, Function, Predicate, int)
     */
    static RepositoryActions build(List<Class<?>> repositories, MansartDataCatalogue catalogue, BeanLookup beans,
                                   TransactionRunner transactions, Function<Class<?>, EntityModel<?>> models,
                                   Predicate<Method> accessible, int maxActions, JdqlRunner jdql) {
```
with:
```java
    /**
     * The actions of {@code repositories} and the <i>JDQL</i> tab's {@link JdqlActions}, without its CSV actions.
     *
     * @param jdql runs a JDQL statement, {@link JdqlRunner#MANSART} outside tests; {@code null} for no JDQL tab
     * @see #build(List, MansartDataCatalogue, BeanLookup, TransactionRunner, Function, Predicate, int, JdqlRunner,
     *      EntitySaver)
     */
    static RepositoryActions build(List<Class<?>> repositories, MansartDataCatalogue catalogue, BeanLookup beans,
                                   TransactionRunner transactions, Function<Class<?>, EntityModel<?>> models,
                                   Predicate<Method> accessible, int maxActions, JdqlRunner jdql) {
        return build(repositories, catalogue, beans, transactions, models, accessible, maxActions, jdql, null);
    }

    /**
     * The actions of {@code repositories}, in the catalogue's name order, then, when {@code jdql} is given and the
     * catalogue holds an entity, the {@value JdqlActions#COUNT} actions of the <i>JDQL</i> tab, followed, when
     * {@code saver} is given too, by its {@value CsvActions#COUNT} CSV actions; all keep their calls in the same
     * history, and the repositories get {@code maxActions} less those.
     *
     * @param jdql  runs a JDQL statement, {@link JdqlRunner#MANSART} outside tests; {@code null} for no JDQL tab
     * @param saver saves an imported entity, {@link EntitySaver#MANSART} outside tests; {@code null} for no CSV action
     * @see #build(List, MansartDataCatalogue, BeanLookup, TransactionRunner, Function, Predicate, int)
     */
    static RepositoryActions build(List<Class<?>> repositories, MansartDataCatalogue catalogue, BeanLookup beans,
                                   TransactionRunner transactions, Function<Class<?>, EntityModel<?>> models,
                                   Predicate<Method> accessible, int maxActions, JdqlRunner jdql,
                                   EntitySaver saver) {
```
Then replace exactly:
```java
        boolean withJdql = jdql != null && !catalogue.entities().isEmpty();
        int room = withJdql ? maxActions - JdqlActions.COUNT : maxActions;
```
with:
```java
        boolean withJdql = jdql != null && !catalogue.entities().isEmpty();
        boolean withCsv = withJdql && saver != null;
        int room = maxActions - (withJdql ? JdqlActions.COUNT : 0) - (withCsv ? CsvActions.COUNT : 0);
```
Then replace exactly:
```java
        if (withJdql) {
            JdqlActions tab = new JdqlActions(catalogue.entities(), className -> load(className, repositories),
                    models, built.entities, beans, transactions, jdql, built.history,
                    group(JdqlActions.GROUP, groups));
            built.actions.addAll(tab.actions());
        }
```
with:
```java
        if (withJdql) {
            String jdqlGroup = group(JdqlActions.GROUP, groups);
            JdqlActions tab = new JdqlActions(catalogue.entities(), className -> load(className, repositories),
                    models, built.entities, beans, transactions, jdql, built.history, jdqlGroup);
            built.actions.addAll(tab.actions());
            if (withCsv) {
                built.actions.addAll(new CsvActions(tab, built.entities, transactions, jdql, saver, built.history,
                        jdqlGroup).actions());
            }
        }
```

- [ ] **Step 5: The panel passes the saver**

In `CatalogueLivePanel.java`, replace exactly:
```java
                    models, accessible, RepositoryActions.MAX_ACTIONS, JdqlRunner.MANSART);
```
with:
```java
                    models, accessible, RepositoryActions.MAX_ACTIONS, JdqlRunner.MANSART, EntitySaver.MANSART);
```
Then replace exactly:
```java
 * statements in a <i>JDQL</i> tab (see {@link JdqlActions}, on Mansart's {@code JdqlExecutor.run}): built once
```
with:
```java
 * statements in a <i>JDQL</i> tab (see {@link JdqlActions}, on Mansart's {@code JdqlExecutor.run}), which also
 * exports a query as CSV and imports a CSV file (see {@link CsvActions}, on {@code RepositoryRuntime.save}): built once
```

- [ ] **Step 6: Run the module's tests, install it**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu install -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev 2>&1 | grep -E "Tests run:|FAIL|BUILD" | tail -5
```
(`timeout: 600000`.) Expected: `BUILD SUCCESS`, no failure (`JdqlActionsTest.theTabTakesItsTwoActionsFromThePanelsLimit`
still builds with the 8-argument `build`: no CSV action there).

- [ ] **Step 7: Commit**

Message:
```
feat(mansart-data): the JDQL tab offers Export CSV and Import CSV

RepositoryActions.build takes an EntitySaver: after Query and Update /
Delete come jdql.export and jdql.import, counted against the panel's
128 actions and kept in the same calls table. CatalogueLivePanel passes
EntitySaver.MANSART, Mansart's RepositoryRuntime.save.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && git add vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/RepositoryActions.java vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/CatalogueLivePanel.java vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/CsvActions.java vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/CsvActionsTest.java vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/CatalogueLivePanelTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 7: Against a database — the mansart-h2 example (spec §7)

**Files:**
- Modify: `EX/src/test/java/io/vidocq/runtime/examples/mansart/DevConsoleSnapshotTest.java`

**Interfaces:**
- Consumes: Tasks 1-6 installed in `~/.m2`; the test's `panel`, `snapshot`, `postAction`, `json`, `runJdql`;
  products `Espresso` 2.50, `Cappuccino` 3.50, `Latte` 4.00 with ids 1-3 (`V1__products.sql`, `price` a `double`,
  `id` generated by default as identity).
- Produces: `DevConsoleSnapshotTest.exportsAndImportsCsvFromTheConsole`, which leaves the table as it found it
  (the other tests of the class share its boot and count rows).

- [ ] **Step 1: The test**

In `DevConsoleSnapshotTest.java`, replace exactly:
```java
import java.util.ArrayList;
import java.util.List;
```
with:
```java
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
```
Then replace exactly:
```java
    /** Runs {@code jdql.<action>} with this statement; its answer, which must be no error. */
```
with:
```java
    /**
     * Exports the three seeded products as CSV from the JDQL tab, then imports that CSV again with its ids emptied:
     * rolled back it leaves the table as it was, committed it adds one product per line — each with a new id — which
     * the test deletes again, so that the table ends as it began.
     */
    @Test
    void exportsAndImportsCsvFromTheConsole() throws Exception {
        List<?> actions = (List<?>) panel("mansart-data").get("actions");
        List<?> ids = actions.stream().map(a -> ((Map<?, ?>) a).get("id")).toList();
        assertTrue(ids.containsAll(List.of("jdql.export", "jdql.import")), "the CSV actions: " + ids);
        String token = (String) ((Map<?, ?>) snapshot.get("console")).get("actionToken");

        Map<?, ?> export = runCsv(token, "export", Map.of("query", "FROM Product WHERE id <= 3 ORDER BY id"), null);
        assertEquals("text/csv", export.get("contentType"));
        String csv = (String) export.get("body");
        assertEquals("id,name,price\r\n1,Espresso,2.5\r\n2,Cappuccino,3.5\r\n3,Latte,4.0\r\n", csv);
        assertTrue(((String) export.get("result")).matches("3 rows · \\d+ B in \\d+ ms"), "result: " + export);

        String emptied = csv.replaceAll("(?m)^\\d+,", ",");
        long before = countProducts(token);
        long maxId = Long.parseLong((String) runJdql(token, "query", "{\"query\":\"SELECT MAX(id) FROM Product\"}",
                null).get("result"));

        assertEquals("3 rows saved · rolled back", runCsv(token, "import",
                Map.of("entity", "Product", "csv", emptied), "rollback").get("result"));
        assertEquals(before, countProducts(token), "an import rolled back leaves no row");

        assertEquals("3 rows saved · committed", runCsv(token, "import",
                Map.of("entity", "Product", "csv", emptied), "commit").get("result"));
        assertEquals(before + 3, countProducts(token), "an import committed adds a row per line, its id generated");

        assertEquals("3 rows · committed", runJdql(token, "write",
                "{\"query\":\"DELETE FROM Product WHERE id > :max\",\"params\":{\"max\":" + maxId + "}}", "commit")
                .get("result"));
        assertEquals(before, countProducts(token));
    }

    /**
     * Runs {@code jdql.<action>}, {@code export} with its {@code statement} or {@code import} with its {@code file},
     * both written as the page writes them; its answer, which must be no error.
     */
    private static Map<?, ?> runCsv(String token, String action, Map<String, String> argument, String transaction)
            throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        try (Jsonb jsonb = JsonbBuilder.create()) {
            body.put("export".equals(action) ? "statement" : "file", jsonb.toJson(argument));
            if (transaction != null) {
                body.put("transaction", transaction);
            }
            Map<?, ?> answer = json(postAction("mansart-data/jdql." + action, token, jsonb.toJson(body)));
            assertNotEquals(Boolean.TRUE, answer.get("error"), action + ": " + answer);
            return answer;
        }
    }

    /** Runs {@code jdql.<action>} with this statement; its answer, which must be no error. */
```

- [ ] **Step 2: Run it**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu clean verify -pl vidocq-runtime-examples/vidocq-runtime-mansart-h2-example -Dtest=DevConsoleSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run:|FAIL|expected|BUILD" | tail -10
```
(`timeout: 600000`; `clean`: the example's generated sources would be stale otherwise.) Expected: `BUILD SUCCESS`,
`DevConsoleSnapshotTest` with no failure. If the CSV actions are missing, check the dates of the DEV and SPI jars in
`~/.m2/repository/io/vidocq/`; if an assertion on `result` fails, the answer printed says why.

- [ ] **Step 3: Commit**

Message:
```
test(mansart-h2): CSV export and import from the dev console against H2

DevConsoleSnapshotTest exports the seeded products as CSV, then imports
that CSV with its ids emptied: rolled back the count stays, committed it
grows by three, the new rows getting generated ids; they are deleted
again so that the other tests of the boot see the table they expect.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && git add vidocq-runtime-examples/vidocq-runtime-mansart-h2-example/src/test/java/io/vidocq/runtime/examples/mansart/DevConsoleSnapshotTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 8: Documentation (spec §8)

**Files:**
- Modify: `docs/en/modules/ROOT/pages/modules/vidocq-runtime-extensions.adoc` (new section before
  `[#mansart-transactions-jdbc-bridge]`)
- Modify: `docs/en/modules/ROOT/pages/whats-new.adoc` (one entry)

(`dev-console-panels.adoc` was Task 2's.)

**Interfaces:**
- Consumes: the behaviour of Tasks 1-6 as the Rulings state it.
- Produces: the anchor `mansart-data-csv`, which `dev-console-panels.adoc` (Task 2) links to.

- [ ] **Step 1: The section**

In `modules/vidocq-runtime-extensions.adoc`, replace exactly:
```
[#mansart-transactions-jdbc-bridge]
== Mansart Data writes join the transaction [.tag-new]#NEW#
```
with:
````
[#mansart-data-csv]
== CSV export and import for Mansart Data [.tag-new]#NEW#

The *JDQL* tab of the *Mansart Data* panel (<<mansart-data-jdql>>) has two more actions, which move rows between the application's database and a CSV file in your browser: *Export CSV* downloads the result of a query, *Import CSV* saves the rows of a file you paste or pick. Nothing is read from, or written to, the project's directory.

*Export CSV* runs a JDQL query as *Query* does and answers its whole result as CSV, shown as text with a *Download* button, which saves it as `jdql.export-20260929-143012.csv`. `FROM Task` exports every attribute of every task; `SELECT title, priority FROM Task WHERE project = :p` some columns of some. An `UPDATE` or a `DELETE` is refused (`an UPDATE or DELETE: export a query`), and the query runs in a transaction that is always rolled back. The header is the entity's attributes in model order, a joined attribute left out, or the columns of a projection; a count is one column `count`, an aggregate one column `value`. The line says `42 rows · 3.1 KiB in 12 ms`. Every row is exported, but the file is at most 256 KiB in UTF-8: a larger one is refused, `larger than 256 KiB: narrow the query`, never cut.

*Import CSV* takes an entity, picked in the list of the catalogue's, and the CSV text: paste it, or *Choose file* to load a file of at most 60 KiB. The page asks first (`Saves these CSV rows against the database.`). Its *Transaction* list offers `rollback`, the default, then `commit`: with `rollback` an import is a dry run, every row saved then the transaction rolled back, so that what goes through `rollback` goes through `commit`.

. The file is read completely before anything is saved. Its first line is the header: each name an attribute of the entity, once. A joined attribute, or a name the entity does not have, is refused with the attributes it has (`header: unknown attribute colour; attributes: id, title, priority, dueDate`), and so is an attribute of a type the console does not convert (`header: attachment cannot be imported; …`). Then at least one row, at most 5000, each with as many fields as the header (`line 9: 3 fields, the header has 4`).
. Each row becomes an entity: its no-arg constructor, then each column set, its text converted by the attribute's type as a repository method's argument is (<<mansart-data-run-method>>) — an enum by its constant's name, a date or a time as ISO text, a number exactly, `true` or `false`, a `UUID` as its text, a reference by the referenced entity's id. An attribute without a column keeps the constructor's value; an empty field for a primitive is refused. The first row that does not convert stops the import, nothing saved: `line 7, priority: no constant URGENT in Priority`, the line counted in the text, the header being line 1.
. Every entity is then saved with Mansart's `RepositoryRuntime.save`, in order, in one transaction. A row whose id is empty is inserted, its id generated; a row with an id updates that row, or inserts one with that id. Re-importing an edited export therefore updates the rows it holds; empty its ids to add copies. A save that fails rolls everything back: `line 12: io.vidocq.mansart.data.core.MansartDataException: …`, the message cut and a `user:password@` masked. Without a `TransactionManager`, `commit` only, each row committed as it is saved: a failing row leaves the rows before it, and the line says so (`· 11 rows before it stay committed`).

The result says `42 rows saved · rolled back` or `· committed`; *Exchange* holds the entity, the number of rows, the separator and the transaction, never the file.

*The format* is RFC 4180, written and read by the console itself:

* fields separated by `,`, or by `;` as a spreadsheet of a decimal-comma locale expects: the *separator* of both actions; records ended by `\r\n` when written, by `\r\n` or `\n` when read, the last one with or without; a UTF-8 byte order mark at the start ignored;
* a field holding the separator, a `"`, a line end, or nothing at all is quoted, a `"` doubled;
* `null` is an empty field, `a,,b`, and the empty text `""`, `a,"",b`: an export and an import keep them apart;
* a number as Java writes it, `2.50` whatever the separator (a decimal comma is refused: `line 2, price: not a number`), `true` or `false`, an enum by its name, a date or a time in ISO, a `UUID` as its text, a reference as the referenced entity's id: the text an import reads back. An attribute of another type is exported as its `toString()`, and refused at import.

*History.* Both actions join the *JDQL* tab's history, *Export CSV* and *Import CSV*; an export's *Replay* fills its form again, an import's is empty as soon as its call passes 4 KiB.

*Limits.* A file picked with *Choose file* is at most 60 KiB, and the whole request at most the console's 64 KiB, which a CSV fills a little more than its size once sent as JSON: split a larger file. At most 5000 rows per import, 256 KiB per export: export a large table in slices, `FROM Task WHERE id <= 2000 ORDER BY id`. The default data store only, and no joined attribute, as in the JDQL console.

[#mansart-transactions-jdbc-bridge]
== Mansart Data writes join the transaction [.tag-new]#NEW#
````

- [ ] **Step 2: What's new**

In `whats-new.adoc`, replace exactly (the start of that line; the rest of it stays as it is):
```
* **A JDQL console for Mansart Data** [.tag-new]#NEW#
```
with:
```
* **CSV export and import for Mansart Data** [.tag-new]#NEW# — under `mvn vidocq:dev`, the *Mansart Data* panel's *JDQL* tab gains *Export CSV*, which runs a query and downloads its whole result as a CSV file, and *Import CSV*, which saves the rows of a file you paste or pick as entities, in a transaction rolled back unless you commit it: a header of attribute names, `null` apart from `""`, `,` or `;` as separator, at most 5000 rows, the first bad line named before anything is saved. The console gains a `text/csv` action result, shown with a *Download* button, and a *Choose file* input for a CSV field. xref:modules/vidocq-runtime-extensions.adoc#mansart-data-csv[CSV export and import for Mansart Data].
* **A JDQL console for Mansart Data** [.tag-new]#NEW#
```

- [ ] **Step 3: Check the anchors**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq/docs/en/modules/ROOT/pages && grep -c '^\[#mansart-data-csv\]' modules/vidocq-runtime-extensions.adoc && grep -c 'vidocq-runtime-extensions.adoc#mansart-data-csv' whats-new.adoc dev-console-panels.adoc
```
Expected: `1`, then `whats-new.adoc:1` and `dev-console-panels.adoc:1`.

- [ ] **Step 4: Commit**

Message:
```
docs(mansart-data): CSV export and import of the dev panel

A section on the JDQL tab's Export CSV and Import CSV: the export and
its 256 KiB, the import read whole then saved in one transaction,
rollback as a dry run, the header, the conversions and their messages,
the format with null apart from "", the limits. And its entry in what's
new.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && git add docs/en/modules/ROOT/pages/modules/vidocq-runtime-extensions.adoc docs/en/modules/ROOT/pages/whats-new.adoc && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 9: Verification — builds, consumers, and the CSV actions in Chrome (spec §7)

**Files:** none changed, unless a check fails (then fix in the owning task's files, re-run its tests, commit with a
`fix(...)` message following the Global Constraints).

**Interfaces:**
- Consumes: everything above; the script
  `/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad/dev-run.sh`
  (`dev-run.sh <project dir> <log>`: app on 18093, console on 18094).

- [ ] **Step 1: Install the touched modules**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu install -pl vidocq-runtime-devconsole-spi,vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension,vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev 2>&1 | grep -E "Tests run:|FAIL|BUILD" | tail -8
```
(`timeout: 600000`.) Expected: `BUILD SUCCESS`, no failure.

- [ ] **Step 2: The consumers**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu clean verify -pl vidocq-runtime-examples/vidocq-runtime-mansart-h2-example 2>&1 | grep -E "Tests run:|FAIL|BUILD" | tail -6
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu clean verify -pl vidocq-runtime-examples/vidocq-runtime-petstore-example 2>&1 | grep -E "Tests run:|FAIL|BUILD" | tail -6
```
(`timeout: 600000` each.) Expected: `BUILD SUCCESS` for both, no failure.

- [ ] **Step 3: Free ports**

```bash
lsof -nP -iTCP:18093 -sTCP:LISTEN; lsof -nP -iTCP:18094 -sTCP:LISTEN; echo checked
```
Expected: only `checked`. If a port is taken, STOP and ask the user (never kill what this plan did not start).

- [ ] **Step 4: Note the test app's state, start `vidocq:dev`**

```bash
git -C /Users/yblazart/projects/perso/vidocq-tools/lc4jcdi-on-vidocq status --short > /private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad/lc4jcdi-status-before.txt
```
Then run with the Bash tool and `run_in_background: true` (the only background Maven run of this plan):
```bash
bash /private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad/dev-run.sh /Users/yblazart/projects/perso/vidocq-tools/lc4jcdi-on-vidocq/mcp-tasks-server /private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad/mcp-tasks-dev.log
```
Wait with the Monitor tool on an until-loop (never a foreground `sleep`):
`until grep -qE "Vidocq dev console: http://127.0.0.1:18094/|BUILD FAILURE|Exception in thread" /private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad/mcp-tasks-dev.log; do sleep 2; done`.
Then `grep -E "Dev tools:|MANSART-DATA|BUILD FAILURE" <that log> | head` — expected
`vidocq-runtime-mansart-data-extension-dev` among the dev tools, no failure.

- [ ] **Step 5: The CSV actions in Chrome (spec §7) — a manual check, no code**

Load the tools in one call:
`ToolSearch("select:mcp__claude-in-chrome__tabs_context_mcp,mcp__claude-in-chrome__navigate,mcp__claude-in-chrome__computer,mcp__claude-in-chrome__read_page,mcp__claude-in-chrome__tabs_create_mcp,mcp__claude-in-chrome__tabs_close_mcp,mcp__claude-in-chrome__javascript_tool,mcp__claude-in-chrome__get_page_text,mcp__claude-in-chrome__find,mcp__claude-in-chrome__form_input,mcp__claude-in-chrome__file_upload")`.

1. `tabs_context_mcp`, `tabs_create_mcp`, `navigate` to `http://127.0.0.1:18094/`. Before relying on polling, run with
   `javascript_tool`:
   `Object.defineProperty(document, 'hidden', {value: false, configurable: true}); Object.defineProperty(document, 'visibilityState', {value: 'visible', configurable: true}); document.dispatchEvent(new Event('visibilitychange')); 'visible'`.
2. Open *Mansart Data*, then its *JDQL* sub-tab: the list offers *Query*, *Update / Delete*, *Export CSV*,
   *Import CSV*.
3. *Query* `SELECT COUNT(this) FROM Task`: note the count `C`.
4. *Export CSV*, `query` `FROM Task ORDER BY id`, `separator` `,`, run (no confirmation): the line reads
   `N rows · X KiB in Y ms` (or `X B`), the body is the CSV as text, header first, and the result block has
   *Download*. Click it; then with Bash `ls -t ~/Downloads | head -3`: a file `jdql.export-<yyyyMMdd-HHmmss>.csv`
   whose content (`head -3` of it) is the body shown. Screenshot.
5. With the Write tool, make
   `/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad/tasks-import.csv`
   from the downloaded file: its header and its first three rows, each row's `id` field emptied and ` (csv import)`
   appended to its `title` field (quoted if it now needs quotes). Keep every other column; if step 6 refuses a column
   as `cannot be imported`, remove that column from the file (the documented behaviour) and note it.
6. *Import CSV*: `entity` `Task`; on the *Choose file* input next to `csv`, `file_upload` the file of step 5: the
   textarea now holds its text. `transaction` `rollback`, run; the page asks
   `Saves these CSV rows against the database.`; confirm: `3 rows saved · rolled back`. Step 3 again: still `C`.
   Screenshot.
7. The same import with `transaction` `commit`: `3 rows saved · committed`. Step 3 again: `C + 3`; *Query*
   `FROM Task WHERE title LIKE '%(csv import)'`: three tasks, each with a new id.
8. Clean up (Ruling 12): *Update / Delete* `DELETE FROM Task WHERE title LIKE '%(csv import)'`, `commit`, confirm:
   `3 rows · committed`. Step 3 again: `C`.
9. A bad line: in the `csv` textarea, change the third data row's enum value (for example its `status` or
   `priority`) to `URGENT`; import in `rollback`: the error `line 4, <attribute>: no constant URGENT in <Enum>`, and
   step 3 still gives `C` (nothing saved).
10. A file too large: with the Write tool, make `big.csv` in the scratchpad (a header and about 70 KiB of rows);
    *Choose file* it: `the file is larger than 60 KiB` shows under the input, the textarea is unchanged.
11. The *JDQL* tab's history lists *Export CSV* and *Import CSV* calls, newest first; *Replay* on the export row
    fills its form and sends nothing (no new history row). Screenshot.
12. `tabs_close_mcp` on the tab this step opened.

- [ ] **Step 6: Stop what this plan started**

Stop the background task of Step 4 with `TaskStop` (its id), then re-run the command of Step 3: both ports free.
```bash
git -C /Users/yblazart/projects/perso/vidocq-tools/lc4jcdi-on-vidocq status --short | diff - /private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad/lc4jcdi-status-before.txt && echo "test app untouched"
```
Expected: `test app untouched`.

- [ ] **Step 7: Final state**

```bash
git -C /Users/yblazart/projects/perso/vidocq/vidocq status --short
```
```bash
git -C /Users/yblazart/projects/perso/vidocq/vidocq log --oneline -10
```
Expected: the tree clean (but for files that were untracked before this plan) with this plan's commits on
`feat/mansart-data-csv`. Nothing pushed. Report: the branch to push, the screenshots, the count `C` before and after
the manual check, and any column step 5 had to drop.

---

## Self-review (done while writing)

- **Spec coverage.** §1: both actions (Tasks 4-6), the `text/csv` type (Task 1), the page rules (Task 2); out of
  scope respected (no server file, default `RepositoryRuntime`, joined attributes left out and refused, no Mansart
  change). §2: `ActionResult.CSV` and its javadoc (Task 1); *Download* with its name, Blob type and no request,
  *Choose file* with its 60 KiB refusal, `PageTest`, `dev-console-panels.adoc` (Task 2). §3: argument, no
  confirmation, write refused, entity by `target`, rolled-back transaction, CSV per result kind, no row cap, the
  UTF-8 256 KiB refusal, content type, summary, details (Task 4). §4: argument with the entity enum, `contentMediaType`,
  separator, transaction (Ruling 1), confirmation, phase 1 with every refusal, phase 2 with rollback, commit, no
  manager, a failing save with its line, result, summary, details (Task 5). §5: the format (Task 3, values Tasks
  4-5). §6: history with `Export CSV` / `Import CSV`, arguments cut at 200, replay empty past 4096 (Tasks 4-6). §7:
  console tests (Tasks 1-2), `-dev` unit tests (Tasks 3-6), mansart-h2 (Task 7), Chrome (Task 9). §8: docs (Tasks
  2, 8). §9: decisions respected (browser-side files, `save` as upsert, both in the JDQL tab, refuse rather than cut,
  read whole before saving).
- **Types.** `Csv.read(String, char)`, `Csv.Record(int, List<String>)`, `Csv.Malformed`, `Csv.Writer(char, long)`
  `.add(List<String>)`, `EntitySaver.save(EntityModel<?>, Object, Object)`, `CsvActions(JdqlActions, EntityJson,
  TransactionRunner, JdqlRunner, EntitySaver, CallHistory, String)`, `exportCsv`/`importCsv(Map<String, String>)`,
  `EntityJson.names/idOf/columns/textRows`, `EntityJson.TextRows.build(List<String>)`,
  `Scalars.fromText(Class<?>, String, String)`, `JdqlActions.parse(String, String)`/`named(String)`/`Refused`,
  `RepositoryActions.build(…, int, JdqlRunner, EntitySaver)` are used with the same signatures in every task.
- **Placeholders.** None: every code step carries its code, every command its expected output. The manual check's
  attribute and enum names (step 9) are the test app's, read on the page at that moment.
