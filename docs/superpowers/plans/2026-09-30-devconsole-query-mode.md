# Dev console query mode, and JDQL in Mansart Data — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The dev console's code editor gets a query mode fed by a language a panel publishes (its dialect and its
vocabulary of targets and attributes), the Mansart Data panel publishes one for JDQL, and the `query` and `params` of
its three query actions become a query editor and a JSON editor whose schema follows the query.

**Architecture:** The console SPI gains `PanelLanguage` and `languages()`; the console reads them once per dev boot
with the actions, names their ids in the snapshot and serves each at `GET api/language/<panel>/<id>`. The pure
`editor-core.js` gains `queryLanguage()` (tokens, context, completion, diagnostics, `parameters()`, formatting), run
by GraalJS in a new `QueryLanguageTest`; `editor.js` gains `setData(data, note)` and an `onDraw` hook; `console.js`
turns a `text/x-query` property into a query editor, fetches its language once (a shared promise) and makes an
`x-parameters-of` property a JSON editor that follows it. The Mansart `-dev` module builds the `jdql` language from
the catalogue and Mansart's models.

**Tech Stack:** vanilla JavaScript (ES2022 modules, DOM APIs only, no build, no library), CSS custom properties, JUnit
5, GraalJS `org.graalvm.polyglot:polyglot` + `js` 25.4.4.1.1 (test scope, already in the DC module since piece 1),
Java 25, Maven 3.9, AsciiDoc.

**Spec:** `docs/superpowers/specs/2026-09-30-devconsole-query-mode-design.md` — the binding authority; § numbers below
refer to it. The previous piece, whose code this plan extends, is
`docs/superpowers/plans/2026-09-30-devconsole-editor-json.md` (merged as Vidocq/vidocq#173).

## Global Constraints

- **Paths.** `$VIDOCQ` is this repository's checkout, `$JAVA25` a Java 25 home (Temurin), `$LC4JCDI` a checkout
  of LC4JCDI-on-vidocq, `$SCRATCH` a scratch directory outside the repository.
- **Toolchain.** Java 25: every Maven command is `JAVA_HOME=$JAVA25 mvn -nsu …`,
  run from the repository root `$VIDOCQ`. Never `./mvnw` or `mvnw`.
- **Branch.** `feat/devconsole-query-mode`, already checked out. Do not switch branches. Never push.
- **Module paths** (commands spell them out in full; the steps name files with these prefixes):
  - SPI: `vidocq-runtime-devconsole-spi`; SPIM its main package directory
    `SPI/src/main/java/io/vidocq/runtime/spi/devconsole`, SPIT its test one `SPI/src/test/java/io/vidocq/runtime/spi/devconsole`
  - DC: `vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension`
  - DCM: `DC/src/main/java/io/vidocq/runtime/extensions/essentials/devconsole`
  - DCT: `DC/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole`
  - PAGE: `DC/src/main/resources/META-INF/resources/devconsole` (`console.css`, `console.js`, `editor-core.js`,
    `editor.js`)
  - MD: `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev`
  - MDM: `MD/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev`
  - MDT: `MD/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev`
  - DOCS: `docs/en/modules/ROOT/pages`
- **The page loads nothing from another site; CSP stays `default-src 'self'`.** No `innerHTML`, `outerHTML`,
  `insertAdjacentHTML`, `document.write`, `eval(`, `new Function`, `setAttribute("style"`, `srcdoc`, `import(` in any
  script (PageTest checks every `.js`); every text through `textContent`, a text node or an attribute value; no `url(`
  and no `@import` in CSS; `localStorage` only inside a `try`, and only in `console.js` (`stored`/`store`).
- **`editor-core.js` never references `document`, `window`, `navigator`, `requestAnimationFrame`, `localStorage`,
  `globalThis` or any DOM API, and imports nothing** (PageTest checks it with comments stripped).
- **Imports:** `console.js` imports `./editor.js` and no other script; `editor.js` imports `./editor-core.js` only;
  `index.html` still loads `console.js` only. **The fetch of `api/language` lives in `console.js`** (Ruling 5).
- **Theme:** the query tokens' colours are five new variables, `--code-keyword`, `--code-function`, `--code-target`,
  `--code-attribute`, `--code-parameter`, each defined in `:root {`, in
  `@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) {…} }` and in `:root[data-theme="dark"] {`;
  strings and numbers keep `--json-string` and `--json-number`. The seven are distinct in each of the three blocks.
- **Code style:** English; lines at most 120 characters in new code; the comment density of the file around;
  sentence-like test names (`aPathGoesThroughTheReferencesOneStepAtATimeACycleIncluded`). Every new Java file starts
  with the repository's license header, copied verbatim in this plan.
- **Keep verbatim** (PageTest pins them): `const REPLAY_COLUMN = "replay"`, `const MASKED = "***"`,
  `const FILTER_FROM = 10`, `"Exchange"`, `function fileChooser(target)` placed right before `jsonField`,
  `} else if (kind === "string" && definition.format === "textarea") {`, `input = el("textarea", "json-text");`,
  `input.rows = 4;`, `wrap.classList.add("wide");`,
  `if (definition.contentMediaType === "text/csv") chooser = fileChooser(input);`,
  `object[property] = fileText ? fileText() : input.value;`, `...choosers]) c.disabled = on;`,
  `Object.hasOwn(properties, name)`, `Object.hasOwn(object, property) ? object[property] : undefined`,
  `diagnostics = safely(() => language.diagnose(text, data), []);`, and exactly three lines holding
  `= Object.create(null);` in `console.js`.
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
  replacements assume the tasks before it are done.

## Rulings (where this plan settles what the spec leaves open)

1. **The SPI's JSON check (§2.1).** The SPI has no `JsonValues` (that class is the console's): `PanelLanguage` checks
   its text with the SPI's own `JsonCheck.isObject(json, PanelAction.Argument.MAX_JSON_DEPTH)`, which its Javadoc
   already calls "as strict as the console's own reader". 1 MiB is `MAX_JSON = 1024 * 1024` characters, counted as
   `MAX_SCHEMA` is. A bad id throws `requireKey`'s message (it names the key); a bad text throws
   `language '<id>' is not a JSON object of at most 1048576 characters and 64 levels`.
2. **When languages are read and shown.** With the actions: once per boot, in a `dev` launch only (a `PanelEntry`
   component, `languages`), for a contributed panel, a live panel (through `LivePanelAdapter`) and a built-in one.
   The snapshot writes `"languages": [ids]` only in the page's document of a dev boot, and only for a panel that has
   some; the dev MCP's `toolDocument()` never carries them.
3. **The endpoint's checks, in order (§2.3).** The `Host`, as for every request (`403`); `GET` or `HEAD`, else `405`
   `Allow: GET, HEAD`; a dev boot, else `404`; an `Origin` absent or the console's own, else `403` (a same-origin
   `fetch` GET sends no `Origin`, a cross-origin one does — the rule of the dev MCP); then the panel and the language
   (`404`). `200` carries the text as the panel wrote it, `application/json; charset=utf-8`, `Cache-Control: no-cache`.
   Nothing is logged, a refusal included ("never logged").
4. **What the console logs of a panel's languages.** Two languages of one id: one WARNING,
   `Dev console panel '<panel>' offers the language '<id>' twice: it is shown without languages`; a `languages()` that
   throws: DEBUG with the exception, as for `actions()`; `null` elements are dropped.
5. **The fetch lives in `console.js`** (`panelLanguage`, `fetchLanguage`): `console.js` owns every request the page
   sends (the snapshot, the actions), knows the panel of each form and holds the snapshot; `editor.js` stays a
   component that knows no URL and is given its data with `setData`.
6. **Fetched once per boot, gated by the snapshot.** The shared promise is kept per panel and id for the current
   `console.boot`, not for the whole page load: a dev reload may bring new entities, and a stale vocabulary would
   underline them. An id the panel's snapshot `languages` does not list is not requested; its editors say
   `no vocabulary: not offered`. A failed request says `no vocabulary: <status>`, a network failure
   `no vocabulary: the console did not answer`, a body that is no JSON `no vocabulary: unreadable`. The promise never
   rejects.
7. **A test class of its own.** `EditorCoreTest` is already 650 lines; the query mode's GraalJS tests go into a new
   `QueryLanguageTest` (same harness, same module loaded the same way), which is what §5's "EditorCoreTest (GraalJS)"
   names: the GraalJS suite of `editor-core.js`.
8. **The language contract gains data.** `tokenize(text, data)` and `format(text, data)`: a query's tokens and format
   depend on its vocabulary. `jsonLanguage` ignores the argument; `editor.js` passes `data` to both.
9. **`setData(data, note)` and `onDraw`.** `createEditor` takes an optional `onDraw(text)`, called at the end of each
   draw in its animation frame (how `params` follows the query, §4); `setData` takes an optional note, shown in the line
   under the editor after the counts (how `no vocabulary: 404` is said). `setData` never touches the text or the
   selection.
10. **The quote of the keystrokes.** `pairs` is `["()", "''"]` as §3.6 says; `keystroke()` now treats any pair whose
    two characters are the same as a quote (`"` in JSON, `'` in a query), and `isClosed` reads a token's quote from
    its first character: a backslash escapes a JSON `"`, a doubled quote is inside a query string. The tokenizer reads
    the dialect's `quote` (default `'`); the keystrokes, which get no data, pair `'`.
11. **Completion in an expression clause** lists the target's attributes, `self`, the functions, then the other
    keywords (not in §3.3's table, which would otherwise leave `AND`, `ORDER`… impossible to complete in a `WHERE`); a
    keyword that is a function or `self` is not listed twice. `self`'s detail is `the <Target> itself`; a function is
    inserted as `NAME()` with the caret inside, or `NAME` alone when a `(` already follows the word; a target position
    with no target to offer, a path that does not resolve to a reference, a string, a number and a parameter give
    `null`.
12. **Words.** A name next to a dot (`project.order`) is never a keyword or a function; a name right after a
    `targetAfter` word that is a known target is a target even when it spells a keyword (an entity `Order`); a
    function is a name of `functions` followed by `(`, blanks allowed between (`upper (title)`), as JDQL reads it.
13. **What is checked (§3.4).** An unknown target is an error only when the vocabulary has targets. A target written
    as a qualified name (`FROM io.acme.Task`) is not checked, nor is anything after it. A target that lists no
    attribute checks none; a `target` naming no target of the language stops a path silently. Messages:
    `unknown target Tsk`, `unknown attribute titel of Task`, `title is not a reference` (on the name after the dot,
    or on the dot when none follows), `unterminated string`, `'(' never closed`, `no '(' to close`.
14. **`parameters()` (§3.5).** BETWEEN's descriptions: `lower bound of price (number)` and
    `upper bound of price (number)` (the upper bound form is `attr [NOT] BETWEEN <one token> AND :b`); the parenthesis
    shows the attribute's `format` when it has one, else its `type`, none without a type; an "anything else" use is
    `{}`, with no description. A use is "typed" when its schema has a `type`. The properties object is built with
    `Object.fromEntries`, so a parameter `:__proto__` is an own key.
15. **Formatting (§3.6).** The `AND` of a `BETWEEN` stays on its line; functions are written in capitals too, and
    `self` as the dialect writes it (`this`, not `THIS`); no space after `(`, `.` or a sign, none before `,`, `)` or
    `.`, none between a function and its `(`; line ends become `\n`.
16. **The parameters editor starts at `{}`,** and a replay whose `params` is absent fills `{}`: the schema of a query
    without parameters accepts it, and the server reads it as none. Its text is sent as typed (a string, as the
    textarea's was).
17. **An editor field is a `div`, not a `label`,** because an editor holds a button and a completion list, which a
    `label` would take over; its `description` becomes the textarea's placeholder, not a `title`, whose native tooltip
    would cover the editor's diagnostics. The query editor is 5 rows (§4), the parameters editor 4.
18. **Mansart's targets (§2.4).** Keyed by the class's simple name (Mansart's `JdqlAst` requires it after `FROM`); an
    entity whose simple name another entity of the catalogue shares is left out (the tab refuses the name as
    ambiguous), as is one with a `failure`, an unloadable class or an unreadable model; targets in the catalogue's name
    order. Details are computed from the model as the catalogue shows its columns: `<simple Java type> · id,
    generated` / `· id` / `· version` / `· column <column>`, a reference `→ <Target> · column <column>` with `target`
    its referenced class's simple name, its type the referenced id's (none when that model cannot be read). A boxed
    type shows boxed (`Integer · column points`: Mansart models an `int` field as `Integer`). An attribute of a type
    `Scalars.schema` does not know is kept without a `type`; a `char`'s `maxLength` is not carried. `languages()` is
    empty before `start`, as `actions()` is; a failure while building gives none, at DEBUG.
19. **Shared schema pieces.** `JdqlActions.query(example)` and `JdqlActions.params()` build the two properties; the
    Export CSV schema uses them too.
20. **Docs.** The panel author's part lives in `dev-console.adoc#query-language` as §6 says; the panels guide's
    `#json-argument`, which showed JDQL's old schema, is updated and links to it; `dev-console.adoc` gets a Security
    bullet for the new endpoint. The missing blank line before `[#security]` in `dev-console.adoc` goes with the edit.
21. **The browser check (§5)** runs on `$LC4JCDI/mcp-tasks-server`, whose entities have no reference (`Task.project`
    is a `String`): there `project.` shows `project is not a reference`; a reference path is covered by
    `QueryLanguageTest` only. The helper `dev-run.sh` is recreated by Task 12 when missing.

## Review Focus

1. **A vocabulary from a large application (hundreds of entities)** — the page fetches it once per boot, and each
   keystroke costs the text, not the vocabulary: `vocabulary(data)` is read once per data object (a `WeakMap`), and one
   draw reads the text once (`lastRead`) for its tokens, diagnostics and parameters. Pinned by Task 4's
   `aLargeVocabularyIsReadOnceAndCompletesByPrefix` (500 targets of 30 attributes; a change of the object after the
   first read is not seen) and Task 9's PageTest lines on the shared promise.
2. **A query being typed** — half a keyword, a dangling `.`, `FROM` with nothing after it, a string left open, `(`
   never closed: the editor offers what fits and never throws. Pinned by Task 5's
   `aQueryBeingTypedIsCheckedForItsNamesOnlyAndNeverThrows`, which completes at every caret position of such texts.
3. **The target written after the caret, or changed while `params` holds values** — completion and `params` follow
   the `FROM` wherever it is, and the values typed stay. Pinned by Task 4's `SELECT | FROM Task`, Task 6's
   `SELECT title FROM Task WHERE status != :s`, Task 8's `setData` never touching the text, Task 12's check.
4. **A reference cycle** (`Task.project → Project.lead → Task`) — paths resolve one step at a time and never loop.
   Pinned by Task 3's `aPathGoesThroughTheReferencesOneStepAtATimeACycleIncluded`, Task 4's `project.lead.|` and Task
   5's `project.lead.titel`.
5. **The language fetch failing or slow while the user types** — the editor works with keywords only, then gains the
   vocabulary without losing the text or the caret. Pinned by Task 8's
   `setDataGivesTheEditorOtherDataAndKeepsItsTextAndCaret`, Task 9's PageTest (`fetchLanguage` never rejects, the
   note says why) and Task 12's check with a delayed `fetch`.

## File Structure

| File | Change | Responsibility |
|------|--------|----------------|
| `SPIM/PanelLanguage.java` | create | the record, its checks, the Javadoc of the language JSON (Task 1) |
| `SPIM/DevConsolePanel.java`, `SPIM/LivePanel.java` | modify | `default List<PanelLanguage> languages()` (Task 1) |
| `SPIT/PanelLanguageTest.java` | create | the record's refusals (Task 1) |
| `SPIT/DevConsolePanelTest.java`, `SPIT/LivePanelTest.java` | modify | empty by default (Task 1) |
| `DCM/PanelEntry.java` | modify | `languages` component, read in a dev boot, duplicates refused (Task 2) |
| `DCM/LivePanelAdapter.java` | modify | a live panel's languages (Task 2) |
| `DCM/Snapshot.java` | modify | `"languages": [ids]` of a dev boot's panel (Task 2) |
| `DCM/ConsoleHandler.java` | modify | `GET /api/language/<panel>/<id>` (Task 2) |
| `DCT/TestPanels.java` | modify | `LanguagePanel` (Task 2) |
| `DCT/ConsoleLanguagesTest.java` | create | the endpoint, the snapshot, duplicates, failures (Task 2) |
| `PAGE/editor-core.js` | modify | query tokens and context (Task 3), completion (4), diagnostics and `isClosed` (5), `parameters()` (6), formatting and the quote keystrokes (7) |
| `DCT/QueryLanguageTest.java` | create | GraalJS tests of the query mode (Tasks 3-7) |
| `PAGE/editor.js` | modify | `setData`, `onDraw`, data to `tokenize`/`format`, re-export (Task 8) |
| `PAGE/console.css` | modify | five colour variables in three blocks, the query token classes (Task 8) |
| `PAGE/console.js` | modify | query editor, language fetch, parameters editor (Task 9) |
| `DCT/PageTest.java` | modify | page rules for the new code (Tasks 3, 8, 9) |
| `MDM/JdqlLanguage.java` | create | the `jdql` language's JSON (Task 10) |
| `MDM/CatalogueLivePanel.java` | modify | `languages()` (Task 10) |
| `MDM/JdqlActions.java`, `MDM/CsvActions.java` | modify | the three actions' `query` and `params` schemas (Task 10) |
| `MDT/JdqlLanguageTest.java` | create | the language's JSON (Task 10) |
| `MDT/CatalogueLivePanelTest.java`, `MDT/JdqlActionsTest.java`, `MDT/CsvActionsTest.java` | modify | Task 10 |
| `DOCS/dev-console.adoc`, `DOCS/dev-console-panels.adoc`, `DOCS/modules/vidocq-runtime-extensions.adoc`, `DOCS/whats-new.adoc`, `SPIM/PanelAction.java` (Javadoc) | modify | Task 11 |

**Testing honesty.** `editor-core.js`'s query mode is covered by `QueryLanguageTest` under GraalJS (36 tests). The
console and the SPI have Java tests. `editor.js` and the `console.js` form have no JavaScript harness: `PageTest` pins
their rules as text, and Task 12's browser check is what proves they behave. **While planning, the page was not run
in a browser** (starting a server was out of bounds for the planner): every item of Task 12 is a first run, and its
failures are fixed in the owning task's files.

**The editor draws in `requestAnimationFrame`.** A Chrome tab driven by the MCP extension may report
`document.visibilityState === "hidden"` and not run animation frames until a screenshot is taken: in Task 12 take a
screenshot (or a zoom) before reading what the editor drew.

---

### Task 1: `PanelLanguage` and `languages()` in the console SPI (§2.1)

**Files:**
- Create: `SPIM/PanelLanguage.java`
- Modify: `SPIM/DevConsolePanel.java` (after `actions()`), `SPIM/LivePanel.java` (after `actions()`)
- Create: `SPIT/PanelLanguageTest.java`
- Modify: `SPIT/DevConsolePanelTest.java`, `SPIT/LivePanelTest.java`

**Interfaces:**
- Consumes: `PanelSample.requireKey(String)`, `JsonCheck.isObject(String, int)`,
  `PanelAction.Argument.MAX_JSON_DEPTH` (64), all in the SPI's package.
- Produces: `public record PanelLanguage(String id, String json)` with `public static final int MAX_JSON = 1024 * 1024`;
  `default List<PanelLanguage> languages()` on `DevConsolePanel` and on `LivePanel`, both `List.of()`.

- [ ] **Step 1: Write the failing tests**

Create `SPIT/PanelLanguageTest.java`:

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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A language a panel offers the page's code editor: checked where it is written, never on the page. */
class PanelLanguageTest {

    private static final String JDQL = "{\"mode\":\"query\",\"dialect\":{\"quote\":\"'\"},\"targets\":{\"Task\":"
            + "{\"detail\":\"table task\",\"attributes\":{\"title\":{\"type\":\"string\"}}}}}";

    @Test
    void aLanguageIsAnIdAndTheTextOfAJsonObjectKeptAsItIs() {
        PanelLanguage jdql = new PanelLanguage("jdql", JDQL);

        assertEquals("jdql", jdql.id());
        assertEquals(JDQL, jdql.json(), "served as it is written");
    }

    @Test
    void anIdOutsideTheKeyRuleIsRefused() {
        for (String id : new String[] {"", "JDQL", "1jdql", "jd ql", "j".repeat(41)}) {
            assertThrows(IllegalArgumentException.class, () -> new PanelLanguage(id, JDQL), id);
        }
        assertThrows(NullPointerException.class, () -> new PanelLanguage(null, JDQL));
    }

    @Test
    void aTextThatIsNoJsonObjectIsRefusedNamingTheId() {
        for (String json : new String[] {"", "[]", "\"jdql\"", "{\"mode\":", "{\"a\":1,\"a\":2}", "{} {}"}) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new PanelLanguage("jdql", json), json);
            assertTrue(refused.getMessage().startsWith("language 'jdql' is not a JSON object"), refused.getMessage());
        }
        assertThrows(NullPointerException.class, () -> new PanelLanguage("jdql", null));
    }

    @Test
    void aTextPastOneMebibyteOrPastTheDepthOfAJsonArgumentIsRefused() {
        String head = "{\"detail\":\"";
        String tail = "\"}";
        String largest = head + "a".repeat(PanelLanguage.MAX_JSON - head.length() - tail.length()) + tail;
        assertEquals(1024 * 1024, largest.length());
        assertEquals(largest, new PanelLanguage("jdql", largest).json());
        assertThrows(IllegalArgumentException.class, () -> new PanelLanguage("jdql", largest.replace("{", "{ ")));

        int depth = PanelAction.Argument.MAX_JSON_DEPTH;
        String deepest = "{\"a\":".repeat(depth - 1) + "{}" + "}".repeat(depth - 1);
        assertEquals(deepest, new PanelLanguage("jdql", deepest).json());
        assertThrows(IllegalArgumentException.class,
                () -> new PanelLanguage("jdql", "{\"a\":" + deepest + "}"), "one level more");
    }
}
```

In `SPIT/DevConsolePanelTest.java`, replace:
```java
        assertThrows(UnsupportedOperationException.class,
                () -> charts.add(new Chart("entries", "Entries", List.of(Series.area("entries")))));
    }
}
```
with:
```java
        assertThrows(UnsupportedOperationException.class,
                () -> charts.add(new Chart("entries", "Entries", List.of(Series.area("entries")))));
    }

    @Test
    void aPanelOffersNoLanguageUnlessItAsks() {
        List<PanelLanguage> languages = new CachePanel().languages();

        assertEquals(List.of(), languages);
        assertThrows(UnsupportedOperationException.class, () -> languages.add(new PanelLanguage("jdql", "{}")));
    }
}
```

In `SPIT/LivePanelTest.java`, replace:
```java
        assertEquals(List.of(), panel.actions());
        assertDoesNotThrow(() -> panel.start(null));
```
with:
```java
        assertEquals(List.of(), panel.actions());
        assertEquals(List.of(), panel.languages());
        assertDoesNotThrow(() -> panel.start(null));
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -q -pl vidocq-runtime-devconsole-spi test 2>&1 | grep -E "ERROR.*(cannot find symbol|symbol:)|BUILD" | head -6
```
Expected: FAIL at `testCompile` — `cannot find symbol` / `symbol: class PanelLanguage` (and `method languages()`).

- [ ] **Step 3: Write `PanelLanguage` and the two defaults**

Create `SPIM/PanelLanguage.java`:

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

import java.util.Objects;

/**
 * A language a {@link DevConsolePanel} offers the dev console's code editor, such as the JDQL its query actions take:
 * a dialect and a vocabulary, as the text of one JSON object. Checked when it is built, so that a mistake fails where
 * it is written rather than on the page.
 *
 * <p><b>How the page uses it.</b> A string property of a {@link PanelAction.Argument#json json} argument's schema
 * with {@code "format": "textarea"}, {@code "contentMediaType": "text/x-query"} and {@code "x-language": "<id>"} is
 * a query editor: the page fetches the language once, {@code GET /api/language/<panel>/<id>}, in a
 * {@link io.vidocq.runtime.spi.report.LaunchMode#DEV dev} launch only, and colours, completes and checks the query
 * with it. The server stays the judge of the grammar. The snapshot names the ids a panel offers, never the content.
 *
 * <p><b>Its JSON.</b> For the {@code query} mode of the page's editor:
 * <pre>{@code
 * { "mode": "query",
 *   "dialect": { "keywords": ["SELECT", "FROM", "WHERE", ...], "functions": ["UPPER", "LOWER", ...],
 *                "clauses": ["SELECT", "FROM", "WHERE", "ORDER BY", "SET", "UPDATE", "DELETE FROM"],
 *                "targetAfter": ["FROM", "UPDATE"], "self": "this", "quote": "'" },
 *   "targets": { "Task": { "detail": "table task",
 *                          "attributes": { "title": { "type": "string", "detail": "String · column title" },
 *                                          "project": { "type": "integer", "target": "Project",
 *                                                       "detail": "→ Project · column project_id" } } } } }
 * }</pre>
 * {@code keywords} are matched ignoring case and written in capitals; {@code functions} are the names that may be
 * followed by {@code (}; {@code clauses} start a clause; {@code targetAfter} are the words after which a target is
 * named; {@code self} is the target's own name in an expression; {@code quote} delimits a string, doubled inside it.
 * An attribute has a JSON Schema {@code type} ({@code string}, {@code integer}, {@code number}, {@code boolean}),
 * optionally {@code format} and {@code enum}, a {@code detail}, and {@code target} when it refers to another target of
 * the same language. A word the dialect leaves out takes JDQL's.
 *
 * @param id   identifies the language among those of its panel, stable across boots; it follows the rule of
 *             {@link PanelSample#requireKey}, such as {@code jdql}
 * @param json the language, the text of one JSON object of at most {@value #MAX_JSON} characters, nested
 *             {@value PanelAction.Argument#MAX_JSON_DEPTH} levels deep at most
 */
public record PanelLanguage(String id, String json) {

    /** The longest language, in characters: 1 MiB. */
    public static final int MAX_JSON = 1024 * 1024;

    public PanelLanguage {
        PanelSample.requireKey(id);
        Objects.requireNonNull(json, "json");
        if (json.length() > MAX_JSON || !JsonCheck.isObject(json, PanelAction.Argument.MAX_JSON_DEPTH)) {
            throw new IllegalArgumentException("language '" + id + "' is not a JSON object of at most " + MAX_JSON
                    + " characters and " + PanelAction.Argument.MAX_JSON_DEPTH + " levels");
        }
    }
}
```

In `SPIM/DevConsolePanel.java`, replace:
```java
    default List<PanelAction> actions() {
        return List.of();
    }

    /**
     * Writes the current values of this panel.
```
with:
```java
    default List<PanelAction> actions() {
        return List.of();
    }

    /**
     * The languages this panel offers the page's code editor, such as the JDQL its query actions take: a json
     * argument's property names one with {@code "x-language": "<id>"}, and the page fetches it once,
     * {@code GET /api/language/<panel>/<id>}. Read once per boot, with {@link #actions()}, and only in a
     * {@link io.vidocq.runtime.spi.report.LaunchMode#DEV dev} launch. Two languages of one id leave the panel without
     * languages, and the console logs why; a {@link RuntimeException} or a {@link LinkageError} thrown here leaves it
     * without languages too. See {@link PanelLanguage}.
     *
     * @return the languages, each with its own id; none by default
     */
    default List<PanelLanguage> languages() {
        return List.of();
    }

    /**
     * Writes the current values of this panel.
```

In `SPIM/LivePanel.java`, replace:
```java
    /** The actions, in page order, offered in a dev launch only; none by default. */
    default List<PanelAction> actions() {
        return List.of();
    }
}
```
with:
```java
    /** The actions, in page order, offered in a dev launch only; none by default. */
    default List<PanelAction> actions() {
        return List.of();
    }

    /** The languages of the page's code editor, read with the actions in a dev launch only; none by default. */
    default List<PanelLanguage> languages() {
        return List.of();
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-devconsole-spi test 2>&1 | grep -E "Tests run:|FAIL|BUILD" | tail -3
```
Expected: `Tests run: 67, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS` (`PanelLanguageTest`: 4).

- [ ] **Step 5: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(devconsole): a panel offers languages to the page's code editor

PanelLanguage(id, json) is a language a panel publishes for the dev console's query editor: an id by the key rule,
and the text of one JSON object of at most 1 MiB and 64 levels, checked when it is built by the SPI's own JSON
check. DevConsolePanel and LivePanel gain languages(), empty by default, read with the actions in a dev launch.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-devconsole-spi/src/main/java/io/vidocq/runtime/spi/devconsole/PanelLanguage.java vidocq-runtime-devconsole-spi/src/main/java/io/vidocq/runtime/spi/devconsole/DevConsolePanel.java vidocq-runtime-devconsole-spi/src/main/java/io/vidocq/runtime/spi/devconsole/LivePanel.java vidocq-runtime-devconsole-spi/src/test/java/io/vidocq/runtime/spi/devconsole/PanelLanguageTest.java vidocq-runtime-devconsole-spi/src/test/java/io/vidocq/runtime/spi/devconsole/DevConsolePanelTest.java vidocq-runtime-devconsole-spi/src/test/java/io/vidocq/runtime/spi/devconsole/LivePanelTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 2: The console reads, names and serves a panel's languages (§2.1, §2.3)

**Files:**
- Modify: `DCM/PanelEntry.java` (the record, `language(id)`, `contributed`, `builtIn`, a `languages` reader)
- Modify: `DCM/LivePanelAdapter.java` (`languages()`), `DCM/Snapshot.java` (`writeLanguages`),
  `DCM/ConsoleHandler.java` (`LANGUAGE_PREFIX`, the route, `language(…)`)
- Modify: `DCT/TestPanels.java` (`LanguagePanel`)
- Create: `DCT/ConsoleLanguagesTest.java`

**Interfaces:**
- Consumes: Task 1's `PanelLanguage`, `DevConsolePanel.languages()`, `LivePanel.languages()`.
- Produces: `record PanelEntry(String id, String title, ReportSection section, DevConsolePanel panel,
  List<Chart> charts, List<PanelAction> actions, List<PanelLanguage> languages)` and `PanelLanguage language(String id)`
  (`null` when none); `ConsoleHandler.LANGUAGE_PREFIX = "/api/language/"`; the snapshot member
  `panels[].languages` (ids); `TestPanels.LanguagePanel` (`id()` `acme-data`, field `languages`, field `failure`,
  counter `languagesCalls`, constant `JDQL`).

- [ ] **Step 1: Write the failing tests**

In `DCT/TestPanels.java`, replace:
```java
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.devconsole.Series;
```
with:
```java
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelLanguage;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.devconsole.Series;
```

In `DCT/TestPanels.java`, replace:
```java
    /** A panel offering more actions than the console keeps. */
```
with:
```java
    /**
     * A panel with a query action and the languages {@link #languages} holds, {@code jdql} by default; counts the
     * calls of {@code languages()}, and throws from it when {@link #failure} is set.
     */
    static final class LanguagePanel implements DevConsolePanel {

        static final String JDQL = "{\"mode\":\"query\",\"targets\":{\"Task\":{\"detail\":\"table task\","
                + "\"attributes\":{\"title\":{\"type\":\"string\",\"detail\":\"String · column title\"}}}}}";

        final AtomicInteger languagesCalls = new AtomicInteger();
        List<PanelLanguage> languages = List.of(new PanelLanguage("jdql", JDQL));
        RuntimeException failure;

        @Override
        public String id() {
            return "acme-data";
        }

        @Override
        public void contribute(StartupReportContext context, StartupReportSection section) {
            section.summary("things to query");
        }

        @Override
        public void sample(PanelSample sample) {
            // no value
        }

        @Override
        public List<PanelAction> actions() {
            return List.of(new PanelAction("query", "Query", null, List.of(PanelAction.Argument.json("statement",
                    "Statement", "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"format\":"
                            + "\"textarea\",\"contentMediaType\":\"text/x-query\",\"x-language\":\"jdql\"}}}")),
                    arguments -> "0 rows"));
        }

        @Override
        public List<PanelLanguage> languages() {
            languagesCalls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return languages;
        }
    }

    /** A panel offering more actions than the console keeps. */
```

Create `DCT/ConsoleLanguagesTest.java`:

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

import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.runtime.spi.devconsole.PanelLanguage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The languages a panel offers the page's code editor (query mode spec §2): read once with the actions in a dev boot,
 * named in the snapshot by their ids, served as they are at {@code GET /api/language/<panel>/<id>}, behind the
 * snapshot's checks.
 */
class ConsoleLanguagesTest {

    private static final String HOST = "127.0.0.1:18096";
    private static final String ORIGIN = "http://127.0.0.1:18096";
    private static final long NOW = 1_789_740_602_114L;

    private final TestPanels.LanguagePanel panel = new TestPanels.LanguagePanel();
    private LogRecords log;

    @BeforeEach
    void captureTheConsoleLog() {
        log = new LogRecords(DevConsoleExtension.LOGGER_NAME);
    }

    @AfterEach
    void release() {
        log.close();
    }

    /** A boot showing {@link #panel}: a dev one, with actions, or any other. */
    private Snapshot boot(boolean dev) {
        FakeReportView report = FakeReportView.of(panel);
        return new Snapshot("7f3a91c04be2d812", "0.4.0-TEST", () -> Optional.of(report), List.of(), () -> NOW,
                dev ? new ConsoleActions("6b".repeat(32), () -> NOW, Duration.ofSeconds(5)) : null);
    }

    private static ConsoleHandler handler(Snapshot snapshot) {
        return new ConsoleHandler(new HostGuard("127.0.0.1"), () -> 18096, snapshot, request ->
                Response.builder().status(StatusCode.OK).body("<!doctype html>").build());
    }

    /** A request of {@code method} for {@code path}, with these headers besides {@code Host}. */
    private static FakeRequest request(HttpMethod method, String path, String host, Map<String, String> headers) {
        return new FakeRequest(method, path, host, Map.of(), headers, new byte[0]);
    }

    private static String body(Response response) throws IOException {
        try (InputStream in = response.body().asInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ours(Snapshot snapshot) {
        List<Map<String, Object>> panels = (List<Map<String, Object>>) Json.object(snapshot.document()).get("panels");
        return panels.stream().filter(p -> "acme-data".equals(p.get("id"))).findFirst().orElseThrow();
    }

    @Test
    void aDevBootServesALanguageAsThePanelWroteItNeverCachedStale() throws Exception {
        ConsoleHandler handler = handler(boot(true));

        Response response = handler.handle(FakeRequest.get("/api/language/acme-data/jdql", HOST));

        assertEquals(200, response.status().code(), body(response));
        assertEquals("application/json; charset=utf-8", response.headers().firstOrNull("Content-Type"));
        assertEquals("no-cache", response.headers().firstOrNull("Cache-Control"));
        assertEquals("default-src 'self'; frame-ancestors 'none'",
                response.headers().firstOrNull("Content-Security-Policy"));
        assertTrue(response.headers().all("Access-Control-Allow-Origin").isEmpty(), "no CORS");
        assertEquals(TestPanels.LanguagePanel.JDQL, body(response));
        Response head = handler.handle(request(HttpMethod.HEAD, "/api/language/acme-data/jdql", HOST, Map.of()));
        assertEquals(200, head.status().code());
        Response fromThePage = handler.handle(request(HttpMethod.GET, "/api/language/acme-data/jdql", HOST,
                Map.of("Origin", ORIGIN)));
        assertEquals(200, fromThePage.status().code(), "the console's own origin, should a browser send it");
        assertEquals(List.of(), log.messages(), "a language served is never logged");
    }

    @Test
    void anUnknownPanelOrLanguageIsNotFound() throws Exception {
        ConsoleHandler handler = handler(boot(true));

        for (String path : new String[] {"/api/language/acme-data/sql", "/api/language/nothing/jdql",
                "/api/language/acme-data", "/api/language/acme-data/", "/api/language//jdql",
                "/api/language/acme-data/jdql/more", "/api/language/", "/api/language/jvm/jdql"}) {
            Response response = handler.handle(FakeRequest.get(path, HOST));

            assertEquals(404, response.status().code(), path);
            assertFalse(body(response).contains("targets"), path);
        }
    }

    @Test
    void outsideADevBootThereIsNoLanguageAndLanguagesIsNeverCalled() throws Exception {
        Snapshot prod = boot(false);
        ConsoleHandler handler = handler(prod);

        Response response = handler.handle(FakeRequest.get("/api/language/acme-data/jdql", HOST));

        assertEquals(404, response.status().code());
        assertFalse(prod.document().contains("\"languages\""), prod.document());
        assertEquals(0, panel.languagesCalls.get(), "languages() is never called outside a dev launch");
    }

    @Test
    void aForeignOriginOrHostIsRefusedAndAnyMethodButGetOrHeadIsNotAllowed() throws Exception {
        ConsoleHandler handler = handler(boot(true));

        for (String origin : new String[] {"null", "https://evil.example.com", "http://127.0.0.1:9999",
                "http://localhost:18096"}) {
            Response response = handler.handle(request(HttpMethod.GET, "/api/language/acme-data/jdql", HOST,
                    Map.of("Origin", origin)));

            assertEquals(403, response.status().code(), origin);
            assertFalse(body(response).contains("targets"), origin);
        }
        Response foreignHost = handler.handle(FakeRequest.get("/api/language/acme-data/jdql",
                "evil.example.com:18096"));
        assertEquals(403, foreignHost.status().code());
        for (HttpMethod method : new HttpMethod[] {HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE}) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("Origin", ORIGIN);
            Response response = handler.handle(request(method, "/api/language/acme-data/jdql", HOST, headers));

            assertEquals(405, response.status().code(), method.toString());
            assertEquals("GET, HEAD", response.headers().firstOrNull("Allow"));
        }
        assertEquals(List.of(), log.messages(Level.WARNING), "a refusal is never logged either");
    }

    @Test
    void theSnapshotOfADevBootNamesTheLanguagesNeverTheirContent() {
        Snapshot dev = boot(true);

        Map<String, Object> shown = ours(dev);

        assertEquals(List.of("jdql"), shown.get("languages"));
        assertFalse(dev.document().contains("table task"), "the content is fetched, never polled");
        assertFalse(dev.toolDocument().contains("\"languages\""), "the dev MCP gets no language");
        assertEquals(1, panel.languagesCalls.get(), "read once per boot");
        dev.document();
        assertEquals(1, panel.languagesCalls.get(), "read once per boot");
    }

    @Test
    void aPanelThatNamesALanguageTwiceIsShownWithoutLanguagesAndTheConsoleSaysWhy() throws Exception {
        panel.languages = List.of(new PanelLanguage("jdql", "{}"), new PanelLanguage("sql", "{}"),
                new PanelLanguage("jdql", "{\"b\":1}"));
        Snapshot dev = boot(true);

        Map<String, Object> shown = ours(dev);

        assertFalse(shown.containsKey("languages"), shown.toString());
        assertEquals(1, ((List<?>) shown.get("actions")).size(), "the panel and its actions are kept");
        assertEquals(404, handler(dev).handle(FakeRequest.get("/api/language/acme-data/sql", HOST)).status().code());
        assertEquals(List.of("Dev console panel 'acme-data' offers the language 'jdql' twice: it is shown without "
                + "languages"), log.messages(Level.WARNING));
    }

    @Test
    void aPanelWhoseLanguagesThrowIsShownWithoutLanguages() throws Exception {
        panel.failure = new IllegalStateException("password=hunter2");
        Snapshot dev = boot(true);

        Map<String, Object> shown = ours(dev);

        assertFalse(shown.containsKey("languages"), shown.toString());
        assertEquals(1, ((List<?>) shown.get("actions")).size(), "the panel and its actions are kept");
        assertEquals(404, handler(dev).handle(FakeRequest.get("/api/language/acme-data/jdql", HOST)).status().code());
        assertTrue(log.messages(Level.WARNING).stream().noneMatch(m -> m.contains("hunter2")));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-devconsole-spi,vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=ConsoleLanguagesTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run:|BUILD" | tail -3
```
Expected: FAIL — `Tests run: 7, Failures: 7`: without the route, `/api/language/…` reaches the page handler, which
answers `200 <!doctype html>`, and the snapshot has no `languages`. (The SPI is in the reactor so that the console
compiles against Task 1's `PanelLanguage` without an install.)

- [ ] **Step 3: Read the languages, name them, serve them**

In `DCM/PanelEntry.java`, replace:
```java
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.ReportSection;
import io.vidocq.runtime.spi.report.StartupReportContributor;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One panel of the console for one boot, as it is read once and shown on every poll: the boot facts of a section of
 * the report, and, for a {@link DevConsolePanel}, the panel to sample and its charts.
 *
 * @param id      the id of its contributor, the section's id
 * @param title   what the page calls it
 * @param section its boot facts
 * @param panel   what writes its live values, or {@code null} for a contributor that is no panel
 * @param charts  what the page plots of them; empty when there is no panel or its {@code charts()} failed
 * @param actions what the page may ask it to do: read in a dev launch only, empty in any other, when there is no
 *                panel or its {@code actions()} failed; each id once, {@value #MAX_ACTIONS} at most
 */
record PanelEntry(String id, String title, ReportSection section, DevConsolePanel panel, List<Chart> charts,
                  List<PanelAction> actions) {

    /** The most actions of one panel the console shows. */
    static final int MAX_ACTIONS = 128;

    private static final System.Logger LOG = System.getLogger(DevConsoleExtension.LOGGER_NAME);

    PanelEntry {
        charts = List.copyOf(charts);
        actions = List.copyOf(actions);
    }

    /**
     * The action {@code id} of this panel.
     *
     * @param id the id of the action, as the request names it
     * @return the action, or {@code null} when the panel offers none of that id
     */
    PanelAction action(String id) {
        for (PanelAction action : actions) {
            if (action.id().equals(id)) {
                return action;
            }
        }
        return null;
    }
```
with:
```java
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelLanguage;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.ReportSection;
import io.vidocq.runtime.spi.report.StartupReportContributor;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One panel of the console for one boot, as it is read once and shown on every poll: the boot facts of a section of
 * the report, and, for a {@link DevConsolePanel}, the panel to sample and its charts.
 *
 * @param id        the id of its contributor, the section's id
 * @param title     what the page calls it
 * @param section   its boot facts
 * @param panel     what writes its live values, or {@code null} for a contributor that is no panel
 * @param charts    what the page plots of them; empty when there is no panel or its {@code charts()} failed
 * @param actions   what the page may ask it to do: read in a dev launch only, empty in any other, when there is no
 *                  panel or its {@code actions()} failed; each id once, {@value #MAX_ACTIONS} at most
 * @param languages what the page's code editor may fetch of it: read in a dev launch only, with the actions; empty
 *                  in any other, when there is no panel, its {@code languages()} failed or named one id twice
 */
record PanelEntry(String id, String title, ReportSection section, DevConsolePanel panel, List<Chart> charts,
                  List<PanelAction> actions, List<PanelLanguage> languages) {

    /** The most actions of one panel the console shows. */
    static final int MAX_ACTIONS = 128;

    private static final System.Logger LOG = System.getLogger(DevConsoleExtension.LOGGER_NAME);

    PanelEntry {
        charts = List.copyOf(charts);
        actions = List.copyOf(actions);
        languages = List.copyOf(languages);
    }

    /**
     * The action {@code id} of this panel.
     *
     * @param id the id of the action, as the request names it
     * @return the action, or {@code null} when the panel offers none of that id
     */
    PanelAction action(String id) {
        for (PanelAction action : actions) {
            if (action.id().equals(id)) {
                return action;
            }
        }
        return null;
    }

    /**
     * The language {@code id} of this panel.
     *
     * @param id the id of the language, as the request names it
     * @return the language, or {@code null} when the panel offers none of that id
     */
    PanelLanguage language(String id) {
        for (PanelLanguage language : languages) {
            if (language.id().equals(id)) {
                return language;
            }
        }
        return null;
    }
```

In `DCM/PanelEntry.java`, replace:
```java
     * @param dev         whether the boot is a dev launch, the only one whose panels offer actions
     */
    static PanelEntry contributed(StartupReportContributor contributor, ReportSection section, boolean dev) {
        DevConsolePanel panel = contributor instanceof DevConsolePanel live ? live : null;
        return new PanelEntry(section.id(), title(contributor, section.id()), section, panel,
                charts(panel, section.id()), dev ? actions(panel, section.id()) : List.of());
    }
```
with:
```java
     * @param dev         whether the boot is a dev launch, the only one whose panels offer actions and languages
     */
    static PanelEntry contributed(StartupReportContributor contributor, ReportSection section, boolean dev) {
        DevConsolePanel panel = contributor instanceof DevConsolePanel live ? live : null;
        return new PanelEntry(section.id(), title(contributor, section.id()), section, panel,
                charts(panel, section.id()), dev ? actions(panel, section.id()) : List.of(),
                dev ? languages(panel, section.id()) : List.of());
    }
```

In `DCM/PanelEntry.java`, replace:
```java
        return new PanelEntry(id, title, section.toSection(), panel, charts(panel, id),
                mode == LaunchMode.DEV ? actions(panel, id) : List.of());
```
with:
```java
        return new PanelEntry(id, title, section.toSection(), panel, charts(panel, id),
                mode == LaunchMode.DEV ? actions(panel, id) : List.of(),
                mode == LaunchMode.DEV ? languages(panel, id) : List.of());
```

In `DCM/PanelEntry.java`, replace:
```java
        } catch (RuntimeException | LinkageError failed) {
            LOG.log(System.Logger.Level.DEBUG, "Dev console panel '" + id + "' has no actions: its actions() failed",
                    failed);
            return List.of();
        }
    }
}
```
with:
```java
        } catch (RuntimeException | LinkageError failed) {
            LOG.log(System.Logger.Level.DEBUG, "Dev console panel '" + id + "' has no actions: its actions() failed",
                    failed);
            return List.of();
        }
    }

    /**
     * The languages of {@code panel}, read once per boot and in a dev launch only: none when it has none or they fail,
     * and none, with a WARNING that says why, when two of them have one id.
     */
    private static List<PanelLanguage> languages(DevConsolePanel panel, String id) {
        if (panel == null) {
            return List.of();
        }
        try {
            List<PanelLanguage> declared = panel.languages();
            if (declared == null) {
                return List.of();
            }
            List<PanelLanguage> kept = declared.stream().filter(Objects::nonNull).toList();
            Set<String> ids = new HashSet<>();
            for (PanelLanguage language : kept) {
                if (!ids.add(language.id())) {
                    LOG.log(System.Logger.Level.WARNING, "Dev console panel '" + Texts.clean(id)
                            + "' offers the language '" + language.id() + "' twice: it is shown without languages");
                    return List.of();
                }
            }
            return kept;
        } catch (RuntimeException | LinkageError failed) {
            LOG.log(System.Logger.Level.DEBUG, "Dev console panel '" + id + "' has no languages: its languages() "
                    + "failed", failed);
            return List.of();
        }
    }
}
```

In `DCM/LivePanelAdapter.java`, replace:
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

In `DCM/LivePanelAdapter.java`, replace:
```java
/**
 * A static section and its live panel, seen as one {@link DevConsolePanel}: the contributor gives the id, the title
 * and the section; the live panel gives the samples, the charts and the actions. What {@link PanelEntry} and
 * {@link Snapshot} already know how to show.
 */
```
with:
```java
/**
 * A static section and its live panel, seen as one {@link DevConsolePanel}: the contributor gives the id, the title
 * and the section; the live panel gives the samples, the charts, the actions and the languages. What
 * {@link PanelEntry} and {@link Snapshot} already know how to show.
 */
```

In `DCM/LivePanelAdapter.java`, replace:
```java
    @Override
    public List<PanelAction> actions() {
        return live.actions();
    }
```
with:
```java
    @Override
    public List<PanelAction> actions() {
        return live.actions();
    }

    @Override
    public List<PanelLanguage> languages() {
        return live.languages();
    }
```

In `DCM/Snapshot.java`, replace:
```java
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.Series;
```
with:
```java
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelLanguage;
import io.vidocq.runtime.spi.devconsole.Series;
```

In `DCM/Snapshot.java`, replace:
```java
 *       {@code error} written only when set, {@code schema} being the JSON object of a json argument;
 *       {@code allowed} {@code null} for an argument checked by a pattern, {@code last} {@code null} before the
 *       first run of the boot (see {@link ConsoleActions}). In any other launch, neither member is written.</li>
```
with:
```java
 *       {@code error} written only when set, {@code schema} being the JSON object of a json argument;
 *       {@code allowed} {@code null} for an argument checked by a pattern, {@code last} {@code null} before the
 *       first run of the boot (see {@link ConsoleActions}). A panel that offers languages to the page's code editor
 *       has {@code "languages": ["jdql"]}, their ids only: the page fetches one at {@code /api/language/<panel>/<id>}.
 *       In any other launch, none of these members is written.</li>
```

In `DCM/Snapshot.java`, replace:
```java
        if (page) {
            out.name("history");
            history.writeTo(out, panel.id(), since);
            if (actions != null) {
                writeActions(out, panel);
            }
        }
        out.endObject();
    }
```
with:
```java
        if (page) {
            out.name("history");
            history.writeTo(out, panel.id(), since);
            if (actions != null) {
                writeActions(out, panel);
                writeLanguages(out, panel);
            }
        }
        out.endObject();
    }

    /** The member {@code languages} of a panel of a dev boot that offers some: their ids, never their content. */
    private static void writeLanguages(JsonWriter out, PanelEntry panel) {
        if (panel.languages().isEmpty()) {
            return;
        }
        out.name("languages").beginArray();
        for (PanelLanguage language : panel.languages()) {
            out.value(language.id());
        }
        out.endArray();
    }
```

In `DCM/ConsoleHandler.java`, replace:
```java
import io.vidocq.chappe.api.StatusCode;

import java.util.List;
```
with:
```java
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.runtime.spi.devconsole.PanelLanguage;

import java.util.List;
```

In `DCM/ConsoleHandler.java`, replace:
```java
 *   <li>in a {@code dev} launch, {@code /mcp} is the {@linkplain DevMcp dev MCP}: {@code POST} only
 *       ({@code 405}), {@code application/json} ({@code 415}), no {@code Origin} or the console's own
 *       ({@code 403}), an {@code Accept} that takes JSON ({@code 406}), and no token, since its tools only read;
 *       outside it, that path is nothing special;</li>
```
with:
```java
 *   <li>in a {@code dev} launch, {@code /mcp} is the {@linkplain DevMcp dev MCP}: {@code POST} only
 *       ({@code 405}), {@code application/json} ({@code 415}), no {@code Origin} or the console's own
 *       ({@code 403}), an {@code Accept} that takes JSON ({@code 406}), and no token, since its tools only read;
 *       outside it, that path is nothing special;</li>
 *   <li>{@code /api/language/<panel>/<id>} is a language a panel offers the page's code editor, its JSON as the
 *       panel wrote it, {@code Cache-Control: no-cache}: {@code GET} or {@code HEAD} ({@code 405}), a {@code dev}
 *       launch ({@code 404} in any other), no {@code Origin} or the console's own ({@code 403}), a panel and a
 *       language of those ids ({@code 404}). No token, since it only reads, and never logged;</li>
```

In `DCM/ConsoleHandler.java`, replace:
```java
    /** The path of the snapshot. */
    static final String SNAPSHOT_PATH = "/api/snapshot";
```
with:
```java
    /** The path of the snapshot. */
    static final String SNAPSHOT_PATH = "/api/snapshot";
    /** Where a panel's language is, {@code /api/language/<panel>/<id>}. */
    static final String LANGUAGE_PREFIX = "/api/language/";
```

In `DCM/ConsoleHandler.java`, replace:
```java
        } else if (mcp != null && DevMcp.PATH.equals(request.pathInfo())) {
            response = mcp(request, host, actions);
        } else if (request.method() != HttpMethod.GET && request.method() != HttpMethod.HEAD) {
```
with:
```java
        } else if (mcp != null && DevMcp.PATH.equals(request.pathInfo())) {
            response = mcp(request, host, actions);
        } else if (request.pathInfo() != null && request.pathInfo().startsWith(LANGUAGE_PREFIX)) {
            response = language(request, host, actions != null);
        } else if (request.method() != HttpMethod.GET && request.method() != HttpMethod.HEAD) {
```

In `DCM/ConsoleHandler.java`, replace:
```java
    /** Whether an {@code Accept} header takes {@code application/json}: itself, {@code application/*}, or all. */
```
with:
```java
    /**
     * A request for a panel's language, its {@code Host} already let in: the method, {@code GET} or {@code HEAD}
     * ({@code 405}); a dev boot ({@code 404}); the {@code Origin}, which a same-origin {@code GET} does not send, and
     * which must otherwise be the console's own ({@code 403}); a panel and a language of the ids the path names
     * ({@code 404}). Then its JSON, as the panel wrote it. Nothing is logged: it only reads.
     */
    private Response language(Request request, String host, boolean dev) {
        if (request.method() != HttpMethod.GET && request.method() != HttpMethod.HEAD) {
            return Response.builder().status(StatusCode.METHOD_NOT_ALLOWED).header("Allow", "GET, HEAD").build();
        }
        if (!dev) {
            return ConsoleActions.text(StatusCode.NOT_FOUND, "No such language.");
        }
        String origin = request.header("Origin").orElse(null);
        if (origin != null && !sameOrigin(origin, host)) {
            return ConsoleActions.text(StatusCode.FORBIDDEN, "This origin is not the dev console's.");
        }
        String rest = request.pathInfo().substring(LANGUAGE_PREFIX.length());
        int slash = rest.indexOf('/');
        PanelEntry panel = slash <= 0 || rest.indexOf('/', slash + 1) >= 0 ? null
                : snapshot.panel(rest.substring(0, slash));
        PanelLanguage language = panel == null ? null : panel.language(rest.substring(slash + 1));
        if (language == null) {
            return ConsoleActions.text(StatusCode.NOT_FOUND, "No such language.");
        }
        return Response.builder()
                .status(StatusCode.OK)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Cache-Control", "no-cache")
                .body(language.json())
                .build();
    }

    /** Whether an {@code Accept} header takes {@code application/json}: itself, {@code application/*}, or all. */
```

- [ ] **Step 4: Run the console's tests to verify they pass**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-devconsole-spi,vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test 2>&1 | grep -E "Tests run:.*Fail|FAIL|BUILD" | grep -v " in io" | tail -4
```
(`timeout: 600000`.) Expected: two `Tests run:` totals with `Failures: 0, Errors: 0` (the SPI's 67, the console's 430)
and `BUILD SUCCESS`; `ConsoleLanguagesTest`: 7 green, `ConsoleActionsTest`, `SnapshotTest` and the others unchanged.

- [ ] **Step 5: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(devconsole): serve a panel's languages at api/language, and name them in the snapshot

In a dev boot, the console reads each panel's languages() once, with its actions: two of one id leave the panel
without languages, with a WARNING that says so, and a languages() that throws leaves it without any. The snapshot
lists the ids of a panel's languages, never their content, which GET /api/language/<panel>/<id> serves as the
panel wrote it: GET or HEAD, a dev launch only, no token, an Origin that is absent or the console's own, never
logged, Cache-Control: no-cache.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/java/io/vidocq/runtime/extensions/essentials/devconsole/PanelEntry.java vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/java/io/vidocq/runtime/extensions/essentials/devconsole/LivePanelAdapter.java vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/java/io/vidocq/runtime/extensions/essentials/devconsole/Snapshot.java vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/java/io/vidocq/runtime/extensions/essentials/devconsole/ConsoleHandler.java vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/TestPanels.java vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/ConsoleLanguagesTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 3: The query mode's vocabulary, tokens and context (§3, §3.1, §3.2)

**Files:**
- Modify: `PAGE/editor-core.js` (the header comment; two sections appended at the end of the file)
- Create: `DCT/QueryLanguageTest.java`
- Modify: `DCT/PageTest.java` (`theEditorCoreTouchesNothingOfThePageSoThatGraalJsRunsIt`)

**Interfaces:**
- Consumes: `editor-core.js`'s `isBlank`, `isObject` (top of the file).
- Produces (Tasks 4-9 rely on these names):
  - `export function queryLanguage()` returns the frozen `QUERY`: `id: "query"`, `tokenize(text, data) → [{from, to,
    kind}]`, `pairs: ["()", "''"]`; Tasks 4-7 add `complete`, `diagnose`, `parameters`, `format` to it.
  - internal: `DIALECT`, `OPERATORS`, `NAME_START`, `NAME_PART`, `isDigit(c)`, `VOCABULARIES` (a `WeakMap`),
    `wordsOf(list, fallback)`, `vocabulary(data) → v` (`{ keywords: Set, keywordList, functions: Set, functionList,
    clauses: [[word…]] longest first, targetAfter: Set, self, quote, targets: Map name → { name, detail, attributes:
    Map name → { name, type, format, enum, detail, target } } }`), `readVocabulary(data)`, `NO_VOCABULARY`,
    `lexQuery(text, quote)`, `lastRead`, and `read(text, data) → { v, tokens, target }`, whose tokens carry `word`
    (capitals), `position` (`"target"`), `pathFrom`, `attribute`, `self` and `problem` (a message) as they apply.
  - `QueryLanguageTest`: static `context`, `json`, `language` (`queryLanguage()`), `keystroke`, `jdql` (the parsed
    `JDQL` fixture), helpers `resource(name)`, `parsed(jsonText)`, `number(value, member)`,
    `tokens(text[, data]) → "kind:text …"`, constant `EMOJI`.

- [ ] **Step 1: Write the failing tests**

Create `DCT/QueryLanguageTest.java`:

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
import org.graalvm.polyglot.PolyglotException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The query mode of the editor's pure half, {@code editor-core.js}, run by GraalJS as the ES module the page loads
 * (query mode spec §3, §5): its tokens and context, completion, diagnostics, parameters, formatting and keystrokes,
 * with the language a Mansart Data panel publishes for JDQL. The JSON language is {@link EditorCoreTest}'s.
 */
class QueryLanguageTest {

    /**
     * The JDQL fixture, as the Mansart Data panel sends its language: a Task whose project refers to a Project, whose
     * lead refers back to a Task, a cycle of references.
     */
    private static final String JDQL = """
            {"mode": "query",
             "dialect": {
               "keywords": ["SELECT", "FROM", "WHERE", "ORDER", "BY", "AND", "OR", "NOT", "IS", "NULL", "BETWEEN",
                            "LIKE", "IN", "ASC", "DESC", "UPDATE", "SET", "DELETE", "COUNT", "THIS", "SUM", "AVG",
                            "MIN", "MAX", "TRUE", "FALSE"],
               "functions": ["UPPER", "LOWER", "LENGTH", "ABS", "CONCAT", "COUNT", "SUM", "AVG", "MIN", "MAX"],
               "clauses": ["SELECT", "FROM", "WHERE", "ORDER BY", "SET", "UPDATE", "DELETE FROM"],
               "targetAfter": ["FROM", "UPDATE"], "self": "this", "quote": "'"},
             "targets": {
               "Task": {"detail": "table task", "attributes": {
                 "id": {"type": "integer", "detail": "Long · id, generated"},
                 "title": {"type": "string", "detail": "String · column title"},
                 "status": {"type": "string", "enum": ["OPEN", "DONE"], "detail": "Status · column status"},
                 "price": {"type": "number", "detail": "BigDecimal · column price"},
                 "dueDate": {"type": "string", "format": "date", "detail": "LocalDate · column due_date"},
                 "project": {"type": "integer", "detail": "→ Project · column project_id", "target": "Project"}}},
               "Project": {"detail": "table project", "attributes": {
                 "id": {"type": "integer", "detail": "Long · id, generated"},
                 "name": {"type": "string", "detail": "String · column name"},
                 "lead": {"type": "integer", "detail": "→ Task · column lead_id", "target": "Task"}}}}}
            """;

    /** One emoji, two UTF-16 units: offsets must count both. */
    private static final String EMOJI = "\uD83D\uDE00";

    private static Context context;
    private static Value json;
    private static Value language;
    private static Value keystroke;
    private static Value jdql;

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
        jdql = parsed(JDQL);
    }

    @AfterAll
    static void closeTheEngine() {
        context.close();
    }

    private static String resource(String name) {
        String path = DevConsoleExtension.PAGE_RESOURCES + "/" + name;
        try (InputStream in = QueryLanguageTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "the page has no " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code text} parsed by the engine's own JSON.parse, as the page parses a language; null stays null. */
    private static Value parsed(String text) {
        return text == null ? null : json.invokeMember("parse", text);
    }

    private static int number(Value object, String member) {
        return object.getMember(member).asInt();
    }

    /** The tokens of {@code text} read with {@code data}, each as kind:text, separated by spaces. */
    private static String tokens(String text, Value data) {
        Value tokens = language.invokeMember("tokenize", text, data);
        List<String> out = new ArrayList<>();
        for (long i = 0; i < tokens.getArraySize(); i++) {
            Value t = tokens.getArrayElement(i);
            out.add(t.getMember("kind").asString() + ":" + text.substring(number(t, "from"), number(t, "to")));
        }
        return String.join(" ", out);
    }

    private static String tokens(String text) {
        return tokens(text, jdql);
    }

    // ------------------------------------------------------------------------------------------------ tokens

    @Test
    void theLanguageIsAQueryWhosePairsAreParenthesesAndQuotes() {
        assertEquals("query", language.getMember("id").asString());
        assertEquals("[\"()\",\"''\"]", json.invokeMember("stringify", language.getMember("pairs")).asString());
    }

    @Test
    void everyKindOfToken() {
        assertEquals("keyword:SELECT function:UPPER punct:( attribute:title punct:) punct:, string:'it''s' punct:, "
                + "number:12 punct:, number:1.5 punct:, parameter::min punct:, parameter:?1 keyword:FROM target:Task "
                + "keyword:WHERE attribute:project punct:. attribute:name operator:<> string:'x' keyword:AND "
                + "attribute:id operator:>= number:3 operator:- number:2L invalid:12ab invalid:# identifier:nothing",
                tokens("SELECT UPPER(title), 'it''s', 12, 1.5, :min, ?1 FROM Task "
                        + "WHERE project.name <> 'x' AND id >= 3 - 2L 12ab # nothing"));
    }

    @Test
    void keywordsIgnoreCaseAndAFunctionIsOneOfTheDialectsBeforeAParenthesis() {
        assertEquals("keyword:select function:count punct:( keyword:this punct:) keyword:from target:Task "
                + "keyword:where function:upper punct:( attribute:title punct:) keyword:like string:'a%'",
                tokens("select count(this) from Task where upper (title) like 'a%'"));
        assertEquals("keyword:SELECT keyword:COUNT keyword:FROM target:Task", tokens("SELECT COUNT FROM Task"),
                "COUNT with no parenthesis is a keyword");
    }

    @Test
    void theTargetIsTheNameAfterTheFirstFromOrUpdateWhereverTheCaretIs() {
        assertEquals("keyword:SELECT attribute:title punct:, attribute:price keyword:FROM target:Task",
                tokens("SELECT title, price FROM Task"), "the attributes of a target written after them");
        assertEquals("keyword:UPDATE target:Task keyword:SET attribute:title operator:= parameter::t",
                tokens("UPDATE Task SET title = :t"));
        assertEquals("keyword:SELECT identifier:Task keyword:FROM target:Project", tokens("SELECT Task FROM Project"),
                "a target's name elsewhere is no attribute of the target");
    }

    @Test
    void aPathGoesThroughTheReferencesOneStepAtATimeACycleIncluded() {
        assertEquals("keyword:FROM target:Task keyword:WHERE attribute:project punct:. attribute:lead punct:. "
                + "attribute:project punct:. attribute:name operator:= keyword:this punct:. attribute:title",
                tokens("FROM Task WHERE project.lead.project.name = this.title"));
        assertEquals("keyword:FROM target:Task keyword:WHERE attribute:project punct:. identifier:title",
                tokens("FROM Task WHERE project.title"), "a Project has no title");
        assertEquals("keyword:FROM target:Task keyword:WHERE attribute:project punct:. identifier:order",
                tokens("FROM Task WHERE project.order"), "a keyword in a path is a name");
    }

    @Test
    void aStringRunsToItsClosingQuoteADoubledOneInsideOrToTheEndOfItsLine() {
        assertEquals("keyword:WHERE attribute:title operator:= string:'it''s' keyword:AND identifier:x operator:= "
                + "string:'open keyword:FROM target:Task", tokens("WHERE title = 'it''s' AND x = 'open\nFROM Task"));
        assertEquals("string:'''", tokens("'''"), "a doubled quote, then no closing one");
    }

    @Test
    void withNoVocabularyOrAnUnknownOrQualifiedTargetEveryNameIsAnIdentifier() {
        assertEquals("keyword:FROM identifier:Task keyword:WHERE identifier:title operator:= number:1",
                tokens("FROM Task WHERE title = 1", null));
        assertEquals("keyword:FROM identifier:Tsk keyword:WHERE identifier:title", tokens("FROM Tsk WHERE title"));
        assertEquals("keyword:FROM identifier:io punct:. identifier:acme punct:. identifier:Task keyword:WHERE "
                + "identifier:title", tokens("FROM io.acme.Task WHERE title"));
    }

    @Test
    void aKnownTargetMaySpellAKeyword() {
        Value order = parsed("{\"targets\": {\"Order\": {\"attributes\": {\"id\": {\"type\": \"integer\"}}}}}");

        assertEquals("keyword:FROM target:Order keyword:ORDER keyword:BY attribute:id",
                tokens("FROM Order ORDER BY id", order));
    }

    @Test
    void offsetsAreUtf16UnitsWhateverTheLineEndsAndEmoji() {
        String text = "FROM Task\r\nWHERE title = '" + EMOJI + "' " + EMOJI;

        assertEquals("[{\"from\":0,\"to\":4,\"kind\":\"keyword\"},{\"from\":5,\"to\":9,\"kind\":\"target\"},"
                + "{\"from\":11,\"to\":16,\"kind\":\"keyword\"},{\"from\":17,\"to\":22,\"kind\":\"attribute\"},"
                + "{\"from\":23,\"to\":24,\"kind\":\"operator\"},{\"from\":25,\"to\":29,\"kind\":\"string\"},"
                + "{\"from\":30,\"to\":32,\"kind\":\"invalid\"}]",
                json.invokeMember("stringify", language.invokeMember("tokenize", text, jdql)).asString());
    }

    @Test
    void oddDataReadsAsNoVocabularyAndNeverThrows() {
        String text = "FROM Task WHERE p.q = :a AND (x";
        for (String odd : new String[] {null, "\"a string\"", "42", "[]", "{\"targets\": \"x\"}",
                "{\"dialect\": \"x\", \"targets\": {\"Task\": 1}}",
                "{\"dialect\": {\"keywords\": \"x\", \"quote\": \"ab\", \"self\": 3, \"clauses\": [1]}}"}) {
            assertEquals("keyword:FROM identifier:Task keyword:WHERE identifier:p punct:. identifier:q operator:= "
                    + "parameter::a keyword:AND punct:( identifier:x", tokens(text, parsed(odd)), odd);
        }
        assertEquals("keyword:FROM target:Task keyword:WHERE attribute:p punct:. identifier:q",
                tokens("FROM Task WHERE p.q", parsed("{\"targets\": {\"Task\": {\"attributes\": "
                        + "{\"p\": {\"target\": \"Nothing\"}}}}}")), "a reference to a target that is none");
        assertEquals("keyword:FROM target:Task keyword:WHERE identifier:p",
                tokens("FROM Task WHERE p", parsed("{\"targets\": {\"Task\": {}}}")), "a target with no attributes");
    }
}
```

In `DCT/PageTest.java`, replace:
```java
        assertEquals(List.of(), IMPORT.matcher(code).results().map(m -> m.group(1)).toList(), "it imports nothing");
        assertTrue(code.contains("export const jsonLanguage = Object.freeze({"), "the JSON language");
    }
```
with:
```java
        assertEquals(List.of(), IMPORT.matcher(code).results().map(m -> m.group(1)).toList(), "it imports nothing");
        assertTrue(code.contains("export const jsonLanguage = Object.freeze({"), "the JSON language");
        assertTrue(code.contains("export function queryLanguage() {"), "the query language");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-devconsole-spi,vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=QueryLanguageTest,PageTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run:|NullPointer|the query language|BUILD" | tail -6
```
Expected: FAIL — `Tests run: 29, Failures: 1, Errors: 1`: `QueryLanguageTest.loadTheModule` errors with a
`NullPointerException` (the module exports no `queryLanguage`: `getMember` returns `null`), which surefire reports as
one error for the class; `PageTest` fails `theEditorCoreTouchesNothingOfThePageSoThatGraalJsRunsIt` with
`the query language`. (The SPI stays in every DC command from now on: the console compiles against Task 1's SPI
without an install.)

- [ ] **Step 3: Write the vocabulary, the tokens and the context**

In `PAGE/editor-core.js`, replace:
```js
// - A language is { id, tokenize(text), diagnose(text, data), complete(text, caret, data), format(text), pairs }.
//   Every offset is a UTF-16 offset into the text, as a textarea counts them.
// - jsonLanguage reads JSON, its data being the JSON Schema of the value: the first syntax error, then what the
//   schema says at every depth, following properties, items, additionalProperties and a local $ref, never anyOf,
//   oneOf, allOf, not or patternProperties. A schema, however odd, never makes it throw: it checks less.
// - keystroke() is the edit a key makes, or null to let the browser type it.
```
with:
```js
// - A language is { id, tokenize(text, data), diagnose(text, data), complete(text, caret, data), format(text, data),
//   pairs }. Every offset is a UTF-16 offset into the text, as a textarea counts them.
// - jsonLanguage reads JSON, its data being the JSON Schema of the value: the first syntax error, then what the
//   schema says at every depth, following properties, items, additionalProperties and a local $ref, never anyOf,
//   oneOf, allOf, not or patternProperties. A schema, however odd, never makes it throw: it checks less.
// - queryLanguage() reads a query (JDQL), its data being the language a panel publishes: a dialect and a vocabulary
//   of targets and their attributes. It colours, completes and checks names, never the grammar, which the server
//   judges; parameters() is the JSON Schema of the query's named parameters. Odd data reads as none.
// - keystroke() is the edit a key makes, or null to let the browser type it.
```

In `PAGE/editor-core.js`, replace (the end of the file, the end of `outdent`):
```js
  if (start === end) return { from, to, insert, caret: Math.max(0, start - from - cuts[0]) };
  return { from, to, insert, anchor: 0, caret: insert.length };
}
```
with:
```js
  if (start === end) return { from, to, insert, caret: Math.max(0, start - from - cuts[0]) };
  return { from, to, insert, anchor: 0, caret: insert.length };
}

// ------------------------------------------------------------------------------------------------ query: tokens

/** The dialect of data that says none, or of each word it leaves out: JDQL's, as Mansart reads it (spec §2.2). */
const DIALECT = Object.freeze({
  keywords: ["SELECT", "FROM", "WHERE", "ORDER", "BY", "AND", "OR", "NOT", "IS", "NULL", "BETWEEN", "LIKE", "IN",
    "ASC", "DESC", "UPDATE", "SET", "DELETE", "COUNT", "THIS", "SUM", "AVG", "MIN", "MAX", "TRUE", "FALSE"],
  functions: ["UPPER", "LOWER", "LENGTH", "ABS", "CONCAT", "COUNT", "SUM", "AVG", "MIN", "MAX"],
  clauses: ["SELECT", "FROM", "WHERE", "ORDER BY", "SET", "UPDATE", "DELETE FROM"],
  targetAfter: ["FROM", "UPDATE"],
  self: "this",
  quote: "'",
});
/** The operators of a query, the longest first, so that <= is not read as < then =. */
const OPERATORS = ["<=", ">=", "<>", "!=", "=", "<", ">", "+", "-", "*", "/"];
/** What starts a name and what continues it, as JDQL reads them: a letter or _, then digits too. */
const NAME_START = /[\p{L}_]/u;
const NAME_PART = /[\p{L}\p{N}_]/u;
const isDigit = (c) => c !== undefined && c >= "0" && c <= "9";
/** The vocabularies read so far, by the data they were read from: a large one is read once, not on every key. */
const VOCABULARIES = new WeakMap();

/** The words of a dialect's list, in capitals, or {@code fallback}'s when it is no list. */
const wordsOf = (list, fallback) => (Array.isArray(list) ? list : fallback)
  .filter((w) => typeof w === "string" && w.trim() !== "").map((w) => w.trim().toUpperCase());

/**
 * The vocabulary of a query language's data (spec §2.2), read once per data object: keywords and functions (Sets of
 * capitals, and keywordList and functionList in the dialect's order), clauses (each a list of words, the longest
 * first), targetAfter (a Set), self, quote, and targets, a Map of each target's name to { name, detail, attributes },
 * attributes a Map of each name to { name, type, format, enum, detail, target }. Anything odd is left out; data that
 * is no object reads as the default dialect and no target.
 */
function vocabulary(data) {
  if (!isObject(data)) return NO_VOCABULARY;
  let known = VOCABULARIES.get(data);
  if (known === undefined) {
    known = readVocabulary(data);
    VOCABULARIES.set(data, known);
  }
  return known;
}

function readVocabulary(data) {
  const d = isObject(data.dialect) ? data.dialect : {};
  const keywords = wordsOf(d.keywords, DIALECT.keywords);
  const functions = wordsOf(d.functions, DIALECT.functions);
  const quote = typeof d.quote === "string" && d.quote.length === 1 && !isBlank(d.quote) && !NAME_PART.test(d.quote)
    && !"(),.:?!".includes(d.quote) && !OPERATORS.includes(d.quote) ? d.quote : DIALECT.quote;
  const self = typeof d.self === "string" && /^[\p{L}_][\p{L}\p{N}_]*$/u.test(d.self) ? d.self : DIALECT.self;
  const targets = new Map();
  for (const [name, t] of Object.entries(isObject(data.targets) ? data.targets : {})) {
    if (!isObject(t)) continue;
    const attributes = new Map();
    for (const [attribute, a] of Object.entries(isObject(t.attributes) ? t.attributes : {})) {
      if (!isObject(a)) continue;
      attributes.set(attribute, { name: attribute, type: typeof a.type === "string" ? a.type : null,
        format: typeof a.format === "string" ? a.format : null, enum: Array.isArray(a.enum) ? a.enum : null,
        detail: typeof a.detail === "string" ? a.detail : "", target: typeof a.target === "string" ? a.target : null });
    }
    targets.set(name, { name, detail: typeof t.detail === "string" ? t.detail : "", attributes });
  }
  return { keywords: new Set(keywords), keywordList: keywords, functions: new Set(functions), functionList: functions,
    clauses: wordsOf(d.clauses, DIALECT.clauses).map((c) => c.split(/\s+/)).sort((a, b) => b.length - a.length),
    targetAfter: new Set(wordsOf(d.targetAfter, DIALECT.targetAfter)), self, quote, targets };
}

/** The vocabulary of no data: the default dialect, and no target. */
const NO_VOCABULARY = readVocabulary({});

/**
 * The raw tokens of a query: { from, to, kind }, kind name, string, number, parameter (:name, ?1), operator, punct
 * (( ) , .) or invalid (one character). A string runs from {@code quote} to the next one that is not doubled, or to
 * the end of its line when there is none; a number written into a name, 12ab, is invalid as a whole.
 */
function lexQuery(text, quote) {
  const tokens = [];
  const n = text.length;
  let i = 0;
  while (i < n) {
    const c = text[i];
    if (isBlank(c)) {
      i++;
      continue;
    }
    let j = i + 1;
    let kind;
    if (c === quote) {
      kind = "string";
      while (j < n && text[j] !== "\n" && text[j] !== "\r") {
        if (text[j] === quote && text[j + 1] === quote) {
          j += 2;
        } else if (text[j++] === quote) {
          break;
        }
      }
    } else if (isDigit(c)) {
      kind = "number";
      while (isDigit(text[j])) j++;
      if (text[j] === "." && isDigit(text[j + 1])) for (j += 1; isDigit(text[j]); j++);
      if (j < n && "lLfFdD".includes(text[j])) j++;
      if (j < n && NAME_PART.test(text[j])) {
        while (j < n && NAME_PART.test(text[j])) j++;
        kind = "invalid";
      }
    } else if (NAME_START.test(c)) {
      kind = "name";
      while (j < n && NAME_PART.test(text[j])) j++;
    } else if ((c === ":" && j < n && NAME_PART.test(text[j])) || (c === "?" && isDigit(text[j]))) {
      kind = "parameter";
      while (j < n && (c === ":" ? NAME_PART.test(text[j]) : isDigit(text[j]))) j++;
    } else if ("(),.".includes(c)) {
      kind = "punct";
    } else {
      const operator = OPERATORS.find((o) => text.startsWith(o, i));
      kind = operator ? "operator" : "invalid";
      if (operator) j = i + operator.length;
      else if (c >= "\uD800" && c <= "\uDBFF" && j < n) j++;          // a character outside the BMP stays whole
    }
    tokens.push({ from: i, to: j, kind });
    i = j;
  }
  return tokens;
}

/** The last text read and what it gave: one draw asks for the tokens, the diagnostics and the parameters of it. */
let lastRead = { text: null, data: null, read: null };

/**
 * {@code text} read with the vocabulary of {@code data} (spec §3.1-§3.2): { v, tokens, target }. A token is { from,
 * to, kind }, kind keyword, function, target, attribute, identifier, string, number, parameter, operator, punct or
 * invalid; a word also has word, its capitals; a name of a path pathFrom, where the path starts, and attribute when
 * it resolves to one, or self when it is the dialect's self; a name that is wrong, problem, what is wrong. target is
 * the target named right after the first targetAfter word, wherever the caret is, or null when that name is unknown,
 * qualified (a.b.C, never checked) or missing. A path goes through the targets one step at a time: a cycle of
 * references costs one lookup per step of the text.
 */
function read(text, data) {
  if (lastRead.text === text && lastRead.data === data) return lastRead.read;
  const v = vocabulary(data);
  const tokens = lexQuery(text, v.quote);
  const self = v.self.toUpperCase();
  const isDot = (t) => t !== undefined && t.kind === "punct" && text[t.from] === ".";
  const joined = (a, b) => a !== undefined && b !== undefined && a.to === b.from;
  // A word next to a dot is a name of a path; else a function before "(", a keyword, or a name.
  tokens.forEach((t, k) => {
    if (t.kind !== "name") return;
    t.word = text.slice(t.from, t.to).toUpperCase();
    const next = tokens[k + 1];
    if ((isDot(tokens[k - 1]) && joined(tokens[k - 1], t)) || (isDot(next) && joined(t, next))) return;
    if (next !== undefined && next.kind === "punct" && text[next.from] === "(" && v.functions.has(t.word)) {
      t.kind = "function";
    } else if (v.keywords.has(t.word)) {
      t.kind = "keyword";
      if (t.word === self) t.self = true;
    }
  });
  // The name right after a targetAfter word is a target, known or not (a known one may be spelt as a keyword, such
  // as Order); the first one is the query's.
  let target = null;
  let first = true;
  tokens.forEach((t, k) => {
    const name = tokens[k + 1];
    if (t.kind !== "keyword" || !v.targetAfter.has(t.word) || name === undefined) return;
    const written = text.slice(name.from, name.to);
    if (name.kind !== "name" && !(name.kind === "keyword" && v.targets.has(written))) return;
    name.kind = "name";
    name.self = false;
    name.position = "target";
    const qualified = isDot(tokens[k + 2]) && joined(name, tokens[k + 2]);
    const entry = qualified ? null : v.targets.get(written) || null;
    if (entry !== null) name.kind = "target";
    else if (!qualified && v.targets.size) name.problem = "unknown target " + written;
    if (first) target = entry;
    first = false;
  });
  // Every other name heads a path, name.name…, resolved from the target one step at a time; with no known target,
  // or after a qualified or unknown one, nothing is checked.
  tokens.forEach((head, k) => {
    if (head.kind !== "name" || (isDot(tokens[k - 1]) && joined(tokens[k - 1], head))) return;
    let entry = head.position === "target" ? null : target;
    for (let at = k; ; at += 2) {
      const t = tokens[at];
      const name = text.slice(t.from, t.to);
      t.pathFrom = head.from;
      if (at === k && head.position !== "target" && t.word === self) {
        t.kind = "keyword";
        t.self = true;
      } else if (entry !== null && entry.attributes.has(name)) {
        t.kind = "attribute";
        t.attribute = entry.attributes.get(name);
      } else {
        t.kind = "identifier";
        if (entry !== null && entry.attributes.size) t.problem = "unknown attribute " + name + " of " + entry.name;
      }
      const dot = tokens[at + 1];
      if (!isDot(dot) || !joined(t, dot)) break;
      const after = tokens[at + 2];
      const more = after !== undefined && after.kind === "name" && joined(dot, after);
      if (t.attribute !== undefined && t.attribute.target === null) {
        (more ? after : dot).problem = name + " is not a reference";
      }
      entry = t.self ? entry : t.attribute !== undefined && t.attribute.target !== null
        ? v.targets.get(t.attribute.target) || null : null;
      if (!more) break;
    }
  });
  lastRead = { text, data, read: { v, tokens, target } };
  return lastRead.read;
}

// ------------------------------------------------------------------------------------------------ the query language

/** A query, its data the language a panel publishes (spec §3); every function reads odd data as none. */
const QUERY = Object.freeze({
  id: "query",
  tokenize: (text, data) => read(text, data).tokens.map(({ from, to, kind }) => ({ from, to, kind })),
  pairs: Object.freeze(["()", "''"]),
});

/** The query language (spec §3), with the contract of jsonLanguage plus parameters(text, data). */
export function queryLanguage() {
  return QUERY;
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: the command of Step 2.
Expected: `Tests run: 10, Failures: 0, Errors: 0` for `QueryLanguageTest`, `PageTest` green, `BUILD SUCCESS`. Then
`node --check` the module: `cp PAGE/editor-core.js $SCRATCH/core.mjs && node --check $SCRATCH/core.mjs` (the `PAGE`
path spelled out) prints nothing.

- [ ] **Step 5: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(devconsole): the editor's query mode reads a panel's language, and colours a query with it

editor-core.js gains queryLanguage(): the dialect and vocabulary of a panel's language (a WeakMap reads a data
object once, and odd data reads as the default dialect with no target), and a query's tokens — keyword, function,
target, attribute, identifier, string with its doubled quote, number, parameter, operator, punct, invalid. The
target is the name after the first FROM or UPDATE, wherever the caret is; a path resolves through the references
one step at a time, a cycle included. QueryLanguageTest runs it in GraalJS with a JDQL fixture.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/editor-core.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/QueryLanguageTest.java vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/PageTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 4: Completion — targets, a reference's attributes, a clause's attributes, keywords (§3.3)

**Files:**
- Modify: `PAGE/editor-core.js` (a completion section before the query language; `complete` in `QUERY`)
- Modify: `DCT/QueryLanguageTest.java` (a completion section at the end)

**Interfaces:**
- Consumes: Task 3's `read`, `vocabulary`'s `v`, the tokens' `word`, `attribute`, `self`.
- Produces: `QUERY.complete(text, caret, data) → { from, to, items: [{ insert, label, detail, kind, caret? }] } | null`
  (the contract of `jsonLanguage.complete`; `kind` is `target`, `attribute`, `keyword` or `function`); internal
  `EXPRESSION_CLAUSES`, `WORDS`, `clauseAt(v, tokens, k) → [word…] | null`, `clauseBefore(v, tokens, end) → "ORDER BY"
  | … | null` (Tasks 6 and 7 use both), `attributeItems(entry)`. `QueryLanguageTest` gains `complete(marked[, data])`,
  `details(marked)`, `completion(marked, data)`, constants `OTHER_KEYWORDS`, `KEYWORDS`, `FUNCTIONS`.

- [ ] **Step 1: Write the failing tests**

In `DCT/QueryLanguageTest.java`, replace (the end of the file):
```java
        assertEquals("keyword:FROM target:Task keyword:WHERE identifier:p",
                tokens("FROM Task WHERE p", parsed("{\"targets\": {\"Task\": {}}}")), "a target with no attributes");
    }
}
```
with:
```java
        assertEquals("keyword:FROM target:Task keyword:WHERE identifier:p",
                tokens("FROM Task WHERE p", parsed("{\"targets\": {\"Task\": {}}}")), "a target with no attributes");
    }

    // ------------------------------------------------------------------------------------------------ completion

    /** Every keyword of the fixture but the functions and self, in its order: what follows them in a clause. */
    private static final String OTHER_KEYWORDS = "SELECT FROM WHERE ORDER BY AND OR NOT IS NULL BETWEEN LIKE IN ASC "
            + "DESC UPDATE SET DELETE TRUE FALSE";
    /** Every keyword of the fixture, in its order. */
    private static final String KEYWORDS = "SELECT FROM WHERE ORDER BY AND OR NOT IS NULL BETWEEN LIKE IN ASC DESC "
            + "UPDATE SET DELETE COUNT THIS SUM AVG MIN MAX TRUE FALSE";
    /** The functions of the fixture as they are inserted, a | where the caret lands. */
    private static final String FUNCTIONS = "UPPER(|) LOWER(|) LENGTH(|) ABS(|) CONCAT(|) COUNT(|) SUM(|) AVG(|) "
            + "MIN(|) MAX(|)";

    /**
     * The completion where {@code marked} has its |, read with {@code data}: "from-to", then each item's insert, a |
     * where the caret lands inside it, separated by spaces; "null" when there is none.
     */
    private static String complete(String marked, Value data) {
        Value found = completion(marked, data);
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

    private static String complete(String marked) {
        return complete(marked, jdql);
    }

    /** The items of that completion as label: detail, one per line. */
    private static String details(String marked) {
        Value items = completion(marked, jdql).getMember("items");
        List<String> out = new ArrayList<>();
        for (long i = 0; i < items.getArraySize(); i++) {
            Value item = items.getArrayElement(i);
            out.add(item.getMember("label").asString() + ": " + item.getMember("detail").asString());
        }
        return String.join("\n", out);
    }

    private static Value completion(String marked, Value data) {
        int caret = marked.indexOf('|');
        return language.invokeMember("complete", marked.substring(0, caret) + marked.substring(caret + 1), caret,
                data);
    }

    @Test
    void afterFromOrUpdateTheTargetsWithTheirDetail() {
        assertEquals("5-5 Task Project", complete("FROM |"));
        assertEquals("Task: table task\nProject: table project", details("FROM |"));
        assertEquals("5-6 Task", complete("FROM T|"));
        assertEquals("12-16 Task", complete("DELETE FROM ta|sk WHERE id = 1"), "the whole word, ignoring case");
        assertEquals("7-7 Task Project", complete("UPDATE |"));
    }

    @Test
    void afterANameAndADotTheAttributesOfTheTargetItRefersTo() {
        assertEquals("24-24 id name lead", complete("FROM Task WHERE project.|"));
        assertEquals("id: Long · id, generated\nname: String · column name\nlead: → Task · column lead_id",
                details("FROM Task WHERE project.|"));
        assertEquals("24-26 name", complete("FROM Task WHERE project.na|"), "the last segment only");
        assertEquals("29-29 id title status price dueDate project", complete("FROM Task WHERE project.lead.|"),
                "back to the Task: a cycle is one step at a time");
        assertEquals("21-21 id title status price dueDate project", complete("FROM Task WHERE this.|"));
        assertEquals("null", complete("FROM Task WHERE title.|"), "a title is no reference");
        assertEquals("null", complete("FROM Task WHERE nothing.|"));
    }

    @Test
    void inAnExpressionClauseOfAKnownTargetItsAttributesSelfTheFunctionsThenTheOtherKeywords() {
        assertEquals("7-7 id title status price dueDate project this " + FUNCTIONS + " " + OTHER_KEYWORDS,
                complete("SELECT | FROM Task"), "the target written after the caret");
        assertEquals("id: Long · id, generated\ntitle: String · column title\nstatus: Status · column status\n"
                + "price: BigDecimal · column price\ndueDate: LocalDate · column due_date\n"
                + "project: → Project · column project_id\nthis: the Task itself",
                String.join("\n", details("SELECT | FROM Task").lines().limit(7).toList()));
        assertEquals("16-18 title", complete("FROM Task WHERE ti|"));
        assertEquals("16-18 UPPER(|) UPDATE", complete("FROM Task WHERE up|"));
        assertEquals("16-18 UPPER UPDATE", complete("FROM Task WHERE up|(title) = 'A'"), "a parenthesis already there");
        assertEquals("16-19 ORDER", complete("FROM Task WHERE ord|"));
        assertEquals("26-26 id title status price dueDate project this " + FUNCTIONS + " " + OTHER_KEYWORDS,
                complete("FROM Task ORDER BY title, |"));
        assertEquals("16-18 status", complete("UPDATE Task SET st| = 'DONE'"));
    }

    @Test
    void anywhereElseOrWithNoKnownTargetTheKeywordsInCapitals() {
        assertEquals("0-0 " + KEYWORDS, complete("|"));
        assertEquals("10-10 " + KEYWORDS, complete("FROM Task |"), "after the target");
        assertEquals("10-12 WHERE", complete("FROM Task wh|"), "filtered ignoring case");
        assertEquals("7-7 " + KEYWORDS, complete("SELECT |"), "no target yet");
        assertEquals("16-16 " + KEYWORDS, complete("FROM Nope WHERE |"), "an unknown target");
        assertEquals("16-18 UPDATE", complete("FROM Task WHERE up|", null), "no vocabulary");
    }

    @Test
    void nothingInAStringANumberOrAParameter() {
        assertEquals("null", complete("FROM Task WHERE title = 'a|'"));
        assertEquals("null", complete("FROM Task WHERE title = 'open|"));
        assertEquals("null", complete("FROM Task WHERE id = 1|"));
        assertEquals("null", complete("FROM Task WHERE id = :i|"));
        assertEquals("null", complete("FROM |", null), "a target position with no target to offer");
    }

    @Test
    void oddDataOffersTheKeywordsAndNeverThrows() {
        for (String odd : new String[] {"\"a string\"", "[]", "{\"targets\": {\"Task\": {}}}",
                "{\"targets\": {\"Task\": {\"attributes\": {\"p\": {\"target\": \"Nothing\"}}}}}"}) {
            assertEquals("16-18 UPDATE", complete("FROM Task WHERE up|", parsed(odd)).replace("UPPER(|) ", ""), odd);
            assertEquals("null", complete("FROM Task WHERE p.|", parsed(odd)), odd);
        }
    }

    @Test
    void aLargeVocabularyIsReadOnceAndCompletesByPrefix() {
        StringBuilder targets = new StringBuilder("{\"targets\": {");
        for (int t = 0; t < 500; t++) {
            targets.append(t == 0 ? "" : ",").append("\"T").append(t).append("\": {\"attributes\": {");
            for (int a = 0; a < 30; a++) {
                targets.append(a == 0 ? "" : ",").append("\"a").append(a).append("\": {\"type\": \"string\"}");
            }
            targets.append("}}");
        }
        Value large = parsed(targets.append("}}").toString());

        assertEquals("5-8 T49 T490 T491 T492 T493 T494 T495 T496 T497 T498 T499", complete("FROM T49|", large));
        large.getMember("targets").putMember("T4999", parsed("{\"attributes\": {}}"));
        assertEquals("5-9 T499", complete("FROM T499|", large), "read once: a later change of the object is not seen");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-devconsole-spi,vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=QueryLanguageTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run:|non-existent member|BUILD" | tail -4
```
Expected: FAIL — `Tests run: 17, Failures: 0, Errors: 7`: each completion test errors with
`UnsupportedOperationException: Non readable or non-existent member key 'complete'`; the 10 token tests pass.

- [ ] **Step 3: Write the completion**

In `PAGE/editor-core.js`, replace:
```js
// ------------------------------------------------------------------------------------------------ the query language
```
with:
```js
// ------------------------------------------------------------------------------------------------ query: completion

/** The clauses whose expressions name the target's attributes (spec §3.3). */
const EXPRESSION_CLAUSES = new Set(["SELECT", "WHERE", "ORDER BY", "SET"]);
/** The kinds of a token that is a word being typed. */
const WORDS = new Set(["keyword", "function", "target", "attribute", "identifier"]);

/** The clause phrase that starts at token {@code k}, as its list of words, the longest one; null when none does. */
const clauseAt = (v, tokens, k) => v.clauses.find((words) => words.every((w, i) => tokens[k + i] !== undefined
  && tokens[k + i].kind === "keyword" && tokens[k + i].word === w)) || null;

/** The clause the tokens before index {@code end} leave open: the last clause phrase, such as "ORDER BY", or null. */
function clauseBefore(v, tokens, end) {
  let clause = null;
  for (let k = 0; k < end; k++) {
    const words = clauseAt(v, tokens, k);
    if (words !== null && k + words.length <= end) {
      clause = words.join(" ");
      k += words.length - 1;
    }
  }
  return clause;
}

/** The completion items of the attributes of {@code entry}, in its order. */
const attributeItems = (entry) => [...entry.attributes.values()].map((a) => ({ insert: a.name, label: a.name,
  detail: a.detail, kind: "attribute" }));

/**
 * The completion at {@code caret} (spec §3.3): { from, to, items }, from-to the word being typed (a path's last
 * segment only), items filtered by it ignoring case: after a targetAfter word the targets; after "name." the
 * attributes of the target it refers to; in a SELECT, WHERE, ORDER BY or SET clause of a known target its
 * attributes, self, the functions (inserted with "(" and the caret inside) and the other keywords; anywhere else the
 * keywords. null in a string, a number or a parameter, or with nothing to offer.
 */
function completeQuery(text, caret, data) {
  const { v, tokens, target } = read(text, data);
  const current = tokens.find((t) => t.from < caret && caret <= t.to) || null;
  const word = current !== null && WORDS.has(current.kind) ? current : null;
  if (current !== null && word === null && current.kind !== "punct" && current.kind !== "operator") return null;
  const from = word !== null ? word.from : caret;
  const to = word !== null ? word.to : caret;
  let p = -1;
  while (p + 1 < tokens.length && tokens[p + 1].to <= from) p++;
  const previous = tokens[p];
  let items;
  if (previous !== undefined && previous.kind === "punct" && text[previous.from] === "." && previous.to === from) {
    const owner = tokens[p - 1];
    const entry = owner === undefined || owner.to !== previous.from ? null : owner.self ? target
      : owner.attribute !== undefined && owner.attribute.target !== null
        ? v.targets.get(owner.attribute.target) || null : null;
    items = entry !== null ? attributeItems(entry) : [];
  } else if (previous !== undefined && previous.kind === "keyword" && v.targetAfter.has(previous.word)) {
    items = [...v.targets.values()].map((t) => ({ insert: t.name, label: t.name, detail: t.detail, kind: "target" }));
  } else {
    const keywords = v.keywordList.map((k) => ({ insert: k, label: k, detail: "keyword", kind: "keyword" }));
    if (target !== null && EXPRESSION_CLAUSES.has(clauseBefore(v, tokens, p + 1))) {
      const call = text[to] === "(";
      items = [...attributeItems(target),
        { insert: v.self, label: v.self, detail: "the " + target.name + " itself", kind: "keyword" },
        ...v.functionList.map((f) => call ? { insert: f, label: f, detail: "function", kind: "function" }
          : { insert: f + "()", label: f, detail: "function", kind: "function", caret: f.length + 1 }),
        ...keywords.filter((k) => !v.functions.has(k.label) && k.label !== v.self.toUpperCase())];
    } else {
      items = keywords;
    }
  }
  const prefix = text.slice(from, caret).toLowerCase();
  items = items.filter((item) => item.label.toLowerCase().startsWith(prefix));
  return items.length ? { from, to, items } : null;
}

// ------------------------------------------------------------------------------------------------ the query language
```

In `PAGE/editor-core.js`, replace:
```js
  tokenize: (text, data) => read(text, data).tokens.map(({ from, to, kind }) => ({ from, to, kind })),
  pairs: Object.freeze(["()", "''"]),
```
with:
```js
  tokenize: (text, data) => read(text, data).tokens.map(({ from, to, kind }) => ({ from, to, kind })),
  complete: completeQuery,
  pairs: Object.freeze(["()", "''"]),
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: the command of Step 2.
Expected: `Tests run: 17, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(devconsole): the query editor completes targets, attributes, paths and keywords

After FROM or UPDATE, the targets with their table; after name., the attributes of the target the reference leads
to, one step at a time; in a SELECT, WHERE, ORDER BY or SET clause of a known target, wherever FROM is written, its
attributes with their detail, this, the functions (inserted with the caret between their parentheses) and the other
keywords; anywhere else the keywords, in capitals. Filtered by the word being typed, ignoring case; nothing in a
string, a number or a parameter.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/editor-core.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/QueryLanguageTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 5: Diagnostics — unknown names, paths, strings, parentheses (§3.4)

**Files:**
- Modify: `PAGE/editor-core.js` (`isClosed` reads the quote of its token; a diagnostics section; `diagnose` in `QUERY`)
- Modify: `DCT/QueryLanguageTest.java` (a diagnostics section at the end)

**Interfaces:**
- Consumes: Task 3's tokens and their `problem`; Task 4's `completion(marked, data)` test helper.
- Produces: `QUERY.diagnose(text, data) → [{ from, to, severity: "error", message }]`, sorted; `isClosed(text, t)`
  now closes a `"` string by the JSON rule and any other quote by doubling (Task 7's `format` and keystrokes rely on
  it). `QueryLanguageTest` gains `diagnose(text[, data])`.

- [ ] **Step 1: Write the failing tests**

In `DCT/QueryLanguageTest.java`, replace (the end of the file):
```java
        assertEquals("5-8 T49 T490 T491 T492 T493 T494 T495 T496 T497 T498 T499", complete("FROM T49|", large));
        large.getMember("targets").putMember("T4999", parsed("{\"attributes\": {}}"));
        assertEquals("5-9 T499", complete("FROM T499|", large), "read once: a later change of the object is not seen");
    }
}
```
with:
```java
        assertEquals("5-8 T49 T490 T491 T492 T493 T494 T495 T496 T497 T498 T499", complete("FROM T49|", large));
        large.getMember("targets").putMember("T4999", parsed("{\"attributes\": {}}"));
        assertEquals("5-9 T499", complete("FROM T499|", large), "read once: a later change of the object is not seen");
    }

    // ------------------------------------------------------------------------------------------------ diagnostics

    /** The diagnostics of {@code text} read with {@code data}, one per line: severity from-to message. */
    private static String diagnose(String text, Value data) {
        Value found = language.invokeMember("diagnose", text, data);
        List<String> out = new ArrayList<>();
        for (long i = 0; i < found.getArraySize(); i++) {
            Value d = found.getArrayElement(i);
            out.add(d.getMember("severity").asString() + " " + number(d, "from") + "-" + number(d, "to") + " "
                    + d.getMember("message").asString());
        }
        return String.join("\n", out);
    }

    private static String diagnose(String text) {
        return diagnose(text, jdql);
    }

    @Test
    void anUnknownTargetIsAnError() {
        assertEquals("error 5-8 unknown target Tsk", diagnose("FROM Tsk WHERE titel = 1"),
                "and no attribute of it checked");
        assertEquals("error 7-10 unknown target Tsk", diagnose("UPDATE Tsk SET title = 1"));
    }

    @Test
    void anUnknownAttributeOfTheTargetOrOfTheTargetOfAReferenceIsAnError() {
        assertEquals("error 16-21 unknown attribute titel of Task", diagnose("FROM Task WHERE titel = 1"));
        assertEquals("error 7-12 unknown attribute titel of Task", diagnose("SELECT titel FROM Task"),
                "the target written after it");
        assertEquals("error 24-27 unknown attribute nme of Project", diagnose("FROM Task WHERE project.nme = 'a'"));
        assertEquals("error 29-34 unknown attribute titel of Task",
                diagnose("FROM Task WHERE project.lead.titel = 'a'"), "back through the cycle");
    }

    @Test
    void aPathThroughAnAttributeThatIsNoReferenceIsAnError() {
        assertEquals("error 22-23 title is not a reference", diagnose("FROM Task WHERE title.x = 1"));
        assertEquals("error 21-22 title is not a reference", diagnose("FROM Task WHERE title."),
                "the dot itself when nothing follows it");
        assertEquals("", diagnose("FROM Task WHERE project."), "a reference being typed");
    }

    @Test
    void anUnterminatedStringAndAnUnbalancedParenthesisAreErrors() {
        assertEquals("error 24-29 unterminated string", diagnose("FROM Task WHERE title = 'open\nORDER BY id"));
        assertEquals("", diagnose("FROM Task WHERE title = 'it''s'"), "a doubled quote is inside the string");
        assertEquals("error 16-17 '(' never closed", diagnose("FROM Task WHERE (id = 1 OR (id = 2)"));
        assertEquals("error 22-23 no '(' to close", diagnose("FROM Task WHERE id = 1)"));
    }

    @Test
    void withNoVocabularyNoKnownTargetOrAQualifiedOneNoNameIsChecked() {
        assertEquals("", diagnose("FROM Tsk WHERE titel.x = 1", null));
        assertEquals("", diagnose("SELECT titel WHERE x = 1"), "no target");
        assertEquals("", diagnose("FROM io.acme.Task WHERE titel = 1"), "a qualified name, never checked");
        assertEquals("error 24-29 unterminated string", diagnose("FROM Task WHERE title = 'open", null),
                "a string is checked without a vocabulary");
    }

    @Test
    void aQueryBeingTypedIsCheckedForItsNamesOnlyAndNeverThrows() {
        assertEquals("", diagnose("FROM "));
        assertEquals("", diagnose("FROM Task WHERE "));
        assertEquals("", diagnose("SELECT COUNT(this) FROM Task WHERE id BETWEEN 1 AND :max ORDER BY"),
                "the grammar is the server's to judge");
        assertEquals("error 16-17 '(' never closed\nerror 17-19 unknown attribute ti of Task",
                diagnose("FROM Task WHERE (ti"));
        for (String typed : new String[] {"", "F", "FROM T", "FROM Task WHERE project.", "FROM Task WHERE '",
                "FROM Task WHERE ((", "FROM Task WHERE project..name", ".", "FROM .", "SELECT this. FROM Task"}) {
            for (int caret = 0; caret <= typed.length(); caret++) {
                completion(typed.substring(0, caret) + "|" + typed.substring(caret), jdql);
            }
            diagnose(typed);
        }
    }

    @Test
    void oddDataChecksLessAndNeverThrows() {
        Value nothing = parsed("{\"targets\": {\"Task\": {\"attributes\": {\"p\": {\"target\": \"Nothing\"}}}}}");

        assertEquals("", diagnose("FROM Task WHERE p.q.r = 1", nothing), "a reference to a target that is none");
        assertEquals("", diagnose("FROM Task WHERE zz = 1", parsed("{\"targets\": {\"Task\": {}}}")),
                "a target that lists no attribute");
        assertEquals("error 5-9 unknown target Nope", diagnose("FROM Nope", nothing));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-devconsole-spi,vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=QueryLanguageTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run:|non-existent member|BUILD" | tail -4
```
Expected: FAIL — `Tests run: 24, Failures: 0, Errors: 7`: each diagnostics test errors with
`Non readable or non-existent member key 'diagnose'`.

- [ ] **Step 3: Close a query string by doubling, and write the diagnostics**

In `PAGE/editor-core.js`, replace:
```js
/** Whether the string token {@code t} ends with its closing quote, an escaped quote not being one. */
function isClosed(text, t) {
  if (t.to - t.from < 2 || text[t.to - 1] !== "\"") return false;
  let backslashes = 0;
  for (let i = t.to - 2; i > t.from && text[i] === "\\"; i--) backslashes++;
  return backslashes % 2 === 0;
}
```
with:
```js
/**
 * Whether the string token {@code t} ends with its closing quote, the quote it opens with: in JSON ("), an escaped
 * quote is no closing one; in a query (the dialect's quote, ' in JDQL), a doubled one is none.
 */
function isClosed(text, t) {
  const quote = text[t.from];
  if (t.to - t.from < 2 || text[t.to - 1] !== quote) return false;
  if (quote !== "\"") {
    let quotes = 0;
    for (let i = t.to - 1; i > t.from && text[i] === quote; i--) quotes++;
    return quotes % 2 === 1;
  }
  let backslashes = 0;
  for (let i = t.to - 2; i > t.from && text[i] === "\\"; i--) backslashes++;
  return backslashes % 2 === 0;
}
```

In `PAGE/editor-core.js`, replace:
```js
// ------------------------------------------------------------------------------------------------ the query language
```
with:
```js
// ------------------------------------------------------------------------------------------------ query: diagnostics

/**
 * The errors of a query (spec §3.4), in the order of the text: an unknown target, an unknown attribute of the target
 * or of the target a reference leads to, a path through an attribute that is no reference, an unterminated string, a
 * parenthesis never closed or closing none. Without a vocabulary, or a known target, no attribute is checked; the
 * grammar never is: the server judges it when the query runs.
 */
function diagnoseQuery(text, data) {
  const out = [];
  const error = (t, message) => out.push({ from: t.from, to: t.to, severity: "error", message });
  const open = [];
  for (const t of read(text, data).tokens) {
    if (t.problem !== undefined) error(t, t.problem);
    if (t.kind === "string" && !isClosed(text, t)) error(t, "unterminated string");
    if (t.kind === "punct" && text[t.from] === "(") open.push(t);
    if (t.kind === "punct" && text[t.from] === ")" && open.pop() === undefined) error(t, "no '(' to close");
  }
  for (const t of open) error(t, "'(' never closed");
  return out.sort((a, b) => a.from - b.from || a.to - b.to);
}

// ------------------------------------------------------------------------------------------------ the query language
```

In `PAGE/editor-core.js`, replace:
```js
  tokenize: (text, data) => read(text, data).tokens.map(({ from, to, kind }) => ({ from, to, kind })),
  complete: completeQuery,
```
with:
```js
  tokenize: (text, data) => read(text, data).tokens.map(({ from, to, kind }) => ({ from, to, kind })),
  diagnose: diagnoseQuery,
  complete: completeQuery,
```

- [ ] **Step 4: Run the tests to verify they pass, the JSON ones included**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-devconsole-spi,vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=QueryLanguageTest,EditorCoreTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run:|BUILD" | tail -4
```
Expected: `QueryLanguageTest` 24 and `EditorCoreTest` 50, `Failures: 0, Errors: 0`, `BUILD SUCCESS` (`isClosed` still
closes a JSON string by its backslashes).

- [ ] **Step 5: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(devconsole): the query editor flags unknown names, broken paths, open strings and parentheses

An unknown target, an unknown attribute of the target or of the one a reference leads to, a path through an
attribute that is no reference, an unterminated string and a parenthesis never closed or closing none are errors;
nothing is checked without a vocabulary or a known target, and the grammar never is. isClosed reads a token's quote:
a doubled one stays inside a query string.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/editor-core.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/QueryLanguageTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 6: `parameters()` — the schema of `params`, from the query (§3.5)

**Files:**
- Modify: `PAGE/editor-core.js` (a parameters section before the query language; `parameters` in `QUERY`)
- Modify: `DCT/QueryLanguageTest.java` (a parameters section at the end)

**Interfaces:**
- Consumes: Task 3's tokens (`attribute`, `pathFrom`, `word`), Task 4's `clauseBefore`.
- Produces: `QUERY.parameters(text, data) → { type: "object", properties: {name: schema}, required: [names],
  additionalProperties: false }` — Task 9 gives it to the parameters editor as its JSON Schema; internal `COMPARISONS`,
  `useOf(text, v, tokens, k)`. `QueryLanguageTest` gains `parameters(text[, data])`, `schema(properties, required…)`.

- [ ] **Step 1: Write the failing tests**

In `DCT/QueryLanguageTest.java`, replace (the end of the file):
```java
        assertEquals("", diagnose("FROM Task WHERE zz = 1", parsed("{\"targets\": {\"Task\": {}}}")),
                "a target that lists no attribute");
        assertEquals("error 5-9 unknown target Nope", diagnose("FROM Nope", nothing));
    }
}
```
with:
```java
        assertEquals("", diagnose("FROM Task WHERE zz = 1", parsed("{\"targets\": {\"Task\": {}}}")),
                "a target that lists no attribute");
        assertEquals("error 5-9 unknown target Nope", diagnose("FROM Nope", nothing));
    }

    // ------------------------------------------------------------------------------------------------ parameters

    /** The JSON Schema of the params of {@code text} read with {@code data}, as JSON. */
    private static String parameters(String text, Value data) {
        return json.invokeMember("stringify", language.invokeMember("parameters", text, data)).asString();
    }

    private static String parameters(String text) {
        return parameters(text, jdql);
    }

    /** The whole schema of {@code properties} (a JSON object's members), their names all required, in that order. */
    private static String schema(String properties, String... required) {
        return "{\"type\":\"object\",\"properties\":{" + properties + "},\"required\":["
                + String.join(",", List.of(required).stream().map(name -> "\"" + name + "\"").toList())
                + "],\"additionalProperties\":false}";
    }

    @Test
    void aComparedParameterTakesTheTypeOfItsAttribute() {
        assertEquals(schema("\"s\":{\"type\":\"string\",\"enum\":[\"OPEN\",\"DONE\"],"
                + "\"description\":\"compared with status (string)\"}", "s"),
                parameters("FROM Task WHERE status = :s"));
        assertEquals(schema("\"min\":{\"type\":\"number\",\"description\":\"compared with price (number)\"}", "min"),
                parameters("FROM Task WHERE :min <= price"), "the parameter first");
        assertEquals(schema("\"d\":{\"type\":\"string\",\"format\":\"date\","
                + "\"description\":\"compared with dueDate (date)\"}", "d"),
                parameters("FROM Task WHERE dueDate <> :d"));
        assertEquals(schema("\"n\":{\"type\":\"string\",\"description\":\"compared with project.name (string)\"}",
                "n"), parameters("FROM Task WHERE project.name = :n"), "through a reference");
        assertEquals(schema("\"s\":{\"type\":\"string\",\"enum\":[\"OPEN\",\"DONE\"],"
                + "\"description\":\"compared with status (string)\"}", "s"),
                parameters("SELECT title FROM Task WHERE status != :s"), "the target after the parameter's clause");
    }

    @Test
    void likeBetweenAndInHaveSchemasOfTheirOwn() {
        assertEquals(schema("\"p\":{\"type\":\"string\",\"description\":\"pattern for title\"}", "p"),
                parameters("FROM Task WHERE title NOT LIKE :p"));
        assertEquals(schema("\"lo\":{\"type\":\"number\",\"description\":\"lower bound of price (number)\"},"
                + "\"hi\":{\"type\":\"number\",\"description\":\"upper bound of price (number)\"}", "lo", "hi"),
                parameters("FROM Task WHERE price BETWEEN :lo AND :hi"));
        assertEquals(schema("\"hi\":{\"type\":\"number\",\"description\":\"upper bound of price (number)\"}", "hi"),
                parameters("FROM Task WHERE price NOT BETWEEN 3 AND :hi"));
        assertEquals(schema("\"ss\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"OPEN\",\"DONE\"]},"
                + "\"description\":\"list of status values\"}", "ss"),
                parameters("FROM Task WHERE status NOT IN :ss"));
    }

    @Test
    void aSetParameterIsTheNewValueOfItsAttributeAndAnyOtherUseTakesAnyValue() {
        assertEquals(schema("\"t\":{\"type\":\"string\",\"description\":\"new value of title\"},\"f\":{},"
                + "\"id\":{\"type\":\"integer\",\"description\":\"compared with id (integer)\"}", "t", "f", "id"),
                parameters("UPDATE Task SET title = :t, price = price * :f WHERE id = :id"));
        assertEquals(schema("\"a\":{},\"b\":{}", "a", "b"), parameters("FROM Task WHERE UPPER(title) = :a OR :b"));
    }

    @Test
    void aParameterUsedTwiceKeepsItsFirstTypedUseAndAPositionalOneHasNoKey() {
        assertEquals(schema("\"x\":{\"type\":\"string\",\"description\":\"compared with title (string)\"}", "x"),
                parameters("FROM Task WHERE :x IS NULL OR title = :x OR price = :x"));
        assertEquals(schema("\"t\":{\"type\":\"string\",\"description\":\"compared with title (string)\"}", "t"),
                parameters("FROM Task WHERE id = ?1 AND title = :t"));
        assertEquals(schema(""), parameters("FROM Task WHERE id = ?1"));
    }

    @Test
    void withNoVocabularyEveryParameterTakesAnyValueAndIsRequired() {
        assertEquals(schema("\"t\":{},\"n\":{}", "t", "n"), parameters("FROM Task WHERE title = :t AND n = :n", null));
    }

    @Test
    void aParameterNamedProtoIsAPropertyLikeAnyOther() {
        assertEquals(schema("\"__proto__\":{\"type\":\"string\",\"description\":\"compared with title (string)\"}",
                "__proto__"), parameters("FROM Task WHERE title = :__proto__"));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-devconsole-spi,vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=QueryLanguageTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run:|non-existent member|BUILD" | tail -4
```
Expected: FAIL — `Tests run: 30, Failures: 0, Errors: 6`: each parameters test errors with
`Non readable or non-existent member key 'parameters'`.

- [ ] **Step 3: Write `parameters()`**

In `PAGE/editor-core.js`, replace:
```js
// ------------------------------------------------------------------------------------------------ the query language
```
with:
```js
// ------------------------------------------------------------------------------------------------ query: parameters

/** The comparisons through which a parameter takes the type of the attribute it meets. */
const COMPARISONS = new Set(["=", "<>", "!=", "<", ">", "<=", ">="]);

/**
 * The JSON Schema of the params object of a query (spec §3.5): one property per distinct :name, in order of first
 * use, all required, no other. A property's type is the one of the attribute it meets (attr op :p, :p op attr, SET
 * attr = :p, BETWEEN), a string for LIKE, an array of it for IN; its description says where it is used; a parameter
 * used twice keeps its first typed use; any other use takes any value. A positional parameter (?1) has no key.
 */
function parametersOf(text, data) {
  const { v, tokens } = read(text, data);
  const properties = new Map();
  const typed = new Set();
  tokens.forEach((t, k) => {
    if (t.kind !== "parameter" || text[t.from] !== ":") return;
    const name = text.slice(t.from + 1, t.to);
    const use = useOf(text, v, tokens, k);
    if (!properties.has(name) || (!typed.has(name) && use.type !== undefined)) properties.set(name, use);
    if (use.type !== undefined) typed.add(name);
  });
  return { type: "object", properties: Object.fromEntries(properties), required: [...properties.keys()],
    additionalProperties: false };
}

/**
 * The schema of the parameter at token {@code k}, from the tokens around it: { type, format, enum, description } as
 * its attribute has them, or {} when it meets none.
 */
function useOf(text, v, tokens, k) {
  const keyword = (i, word) => tokens[i] !== undefined && tokens[i].kind === "keyword" && tokens[i].word === word;
  const comparison = (i) => tokens[i] !== undefined && tokens[i].kind === "operator"
    && COMPARISONS.has(text.slice(tokens[i].from, tokens[i].to));
  // the path that ends at token i, or starts there when forward; null when it resolves to no attribute
  const path = (i, forward) => {
    let end = i;
    while (forward && tokens[end + 2] !== undefined && tokens[end + 2].pathFrom === tokens[i].from) end += 2;
    const t = tokens[end];
    return t !== undefined && t.attribute !== undefined ? { attribute: t.attribute, text: text.slice(t.pathFrom, t.to) }
      : null;
  };
  const not = (i) => keyword(i, "NOT") ? i - 1 : i;
  const typeOf = (a) => Object.fromEntries([["type", a.type], ["format", a.format], ["enum", a.enum]]
    .filter(([, value]) => value !== null));
  const described = (schema, description) => ({ ...schema, description });
  const kind = (a) => a.format !== null ? " (" + a.format + ")" : a.type !== null ? " (" + a.type + ")" : "";
  let met;
  if (comparison(k - 1) && (met = path(k - 2, false)) !== null) {
    const set = text.slice(tokens[k - 1].from, tokens[k - 1].to) === "=" && clauseBefore(v, tokens, k) === "SET";
    return described(typeOf(met.attribute), set ? "new value of " + met.text
      : "compared with " + met.text + kind(met.attribute));
  }
  if (comparison(k + 1) && (met = path(k + 2, true)) !== null) {
    return described(typeOf(met.attribute), "compared with " + met.text + kind(met.attribute));
  }
  if (keyword(k - 1, "LIKE") && (met = path(not(k - 2), false)) !== null) {
    return described({ type: "string" }, "pattern for " + met.text);
  }
  if (keyword(k - 1, "BETWEEN") && (met = path(not(k - 2), false)) !== null) {
    return described(typeOf(met.attribute), "lower bound of " + met.text + kind(met.attribute));
  }
  if (keyword(k - 1, "AND") && keyword(k - 3, "BETWEEN") && (met = path(not(k - 4), false)) !== null) {
    return described(typeOf(met.attribute), "upper bound of " + met.text + kind(met.attribute));
  }
  if (keyword(k - 1, "IN") && (met = path(not(k - 2), false)) !== null) {
    return described({ type: "array", items: typeOf(met.attribute) }, "list of " + met.text + " values");
  }
  return {};
}

// ------------------------------------------------------------------------------------------------ the query language
```

In `PAGE/editor-core.js`, replace:
```js
  complete: completeQuery,
  pairs: Object.freeze(["()", "''"]),
```
with:
```js
  complete: completeQuery,
  parameters: parametersOf,
  pairs: Object.freeze(["()", "''"]),
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: the command of Step 2.
Expected: `Tests run: 30, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(devconsole): the JSON Schema of a query's named parameters

queryLanguage().parameters(text, data) is the schema of params: one required key per :name, in order of first use,
no other. A parameter compared with an attribute takes its type, format and enum; LIKE a string, BETWEEN the
attribute's type for both bounds, IN a list of it, SET attr = :p the attribute's type; its description says where
it is used. A parameter used twice keeps its first typed use; ?1 has no key; any other use takes any value.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/editor-core.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/QueryLanguageTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 7: Formatting, and the smart keystrokes with the dialect's quote (§3.6)

**Files:**
- Modify: `PAGE/editor-core.js` (a formatting section before the query language; `format` in `QUERY`; `keystroke`
  and `backspace` treat a pair of one character as a quote)
- Modify: `DCT/QueryLanguageTest.java` (formatting and keystroke sections at the end)

**Interfaces:**
- Consumes: Task 4's `clauseAt`, Task 5's `isClosed`, the existing `lineOf`, `INDENT`, `keystroke`.
- Produces: `QUERY.format(text, data) → string`, throwing `Error("line <n>: unterminated string")`; `keystroke(language,
  …)` unchanged in signature, its quote logic now any pair whose two characters are the same (`""` in JSON, `''` in a
  query). `QueryLanguageTest` gains `format(text)`, `press(marked, key)`.

- [ ] **Step 1: Write the failing tests**

In `DCT/QueryLanguageTest.java`, replace (the end of the file):
```java
    @Test
    void aParameterNamedProtoIsAPropertyLikeAnyOther() {
        assertEquals(schema("\"__proto__\":{\"type\":\"string\",\"description\":\"compared with title (string)\"}",
                "__proto__"), parameters("FROM Task WHERE title = :__proto__"));
    }
}
```
with:
```java
    @Test
    void aParameterNamedProtoIsAPropertyLikeAnyOther() {
        assertEquals(schema("\"__proto__\":{\"type\":\"string\",\"description\":\"compared with title (string)\"}",
                "__proto__"), parameters("FROM Task WHERE title = :__proto__"));
    }

    // ------------------------------------------------------------------------------------------------ formatting

    private static String format(String text) {
        return language.invokeMember("format", text, jdql).asString();
    }

    @Test
    void formatStartsEachClauseOnALineOfItsOwnAndAndOrOnAnIndentedOne() {
        assertEquals("""
                SELECT title, price
                FROM Task
                WHERE status = :s
                  AND price BETWEEN 1 AND :max
                  OR NOT project.name LIKE 'a%'
                ORDER BY title DESC""", format("select title , price from Task where status=:s and price between 1 "
                + "and :max or not project.name like 'a%' order by title desc"));
        assertEquals("DELETE FROM Task\nWHERE id = ?1", format("delete   from Task where id = ?1"));
        assertEquals("UPDATE Task\nSET title = 'x', price = price * 2\nWHERE (id = 1\n  OR id = 2)",
                format("update Task set title='x',price=price*2 where (id=1 or id=2)"));
    }

    @Test
    void formatWritesKeywordsAndFunctionsInCapitalsSelfAsTheDialectDoesAndTheRestAsWritten() {
        assertEquals("SELECT COUNT(this)\nFROM Task\nWHERE UPPER(title) = 'It''s'\n  AND price > -1.50\n  AND id IN "
                + "(:a, :b)\n  AND dueDate = :Due", format("select count ( THIS ) from Task where upper(title)='It''s' "
                + "and price>-1.50 and id in(:a,:b) and dueDate=:Due"));
    }

    @Test
    void formattingAFormattedQueryChangesNothing() {
        String once = format("select title from Task where title = '" + EMOJI + "' and (id = 1 or id = 2)");

        assertEquals(once, format(once));
    }

    @Test
    void aQueryWithAnUnterminatedStringIsNotFormattedAndSaysWhere() {
        PolyglotException thrown = assertThrows(PolyglotException.class, () -> format("FROM Task\nWHERE title = 'a"));

        assertTrue(thrown.isGuestException(), "an Error thrown by the module");
        assertEquals("Error: line 2: unterminated string", thrown.getMessage());
    }

    // ------------------------------------------------------------------------------------------------ keystrokes

    /**
     * {@code marked} after {@code key} in the query language: one | is the caret, two | the ends of the selection;
     * "null" when the module lets the browser type the key.
     */
    private static String press(String marked, String key) {
        int start = marked.indexOf('|');
        int second = marked.indexOf('|', start + 1);
        String text = marked.replace("|", "");
        int end = second < 0 ? start : second - 1;
        Value edit = keystroke.execute(language, text, start, end, key);
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
    void aQuoteOpensAPairWrapsASelectionAndStepsOverItsClosingQuote() {
        assertEquals("title = '|'", press("title = |", "'"));
        assertEquals("'|abc|'", press("|abc|", "'"));
        assertEquals("'abc'|", press("'abc|'", "'"));
        assertEquals("null", press("it|", "'"), "after a letter: typed as it is");
        assertEquals("'it''|'", press("'it'|", "'"), "a doubled quote is typed as a pair, inside the string");
    }

    @Test
    void aParenthesisPairsOutsideAStringOnlyAndBackspaceDeletesAnEmptyPair() {
        assertEquals("UPPER(|)", press("UPPER|", "("));
        assertEquals("null", press("'a|b'", "("), "a parenthesis in a string is text");
        assertEquals("|", press("'|'", "Backspace"));
        assertEquals("|", press("(|)", "Backspace"));
        assertEquals("null", press("'it''|'", "Backspace"), "a doubled quote then the closing one");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-devconsole-spi,vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=QueryLanguageTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run:|non-existent member|expected:|BUILD" | tail -8
```
Expected: FAIL — `Tests run: 36, Failures: 3, Errors: 3`: the three `format` tests that call it error with
`Non readable or non-existent member key 'format'`; `aQueryWithAnUnterminatedStringIsNotFormattedAndSaysWhere` fails
(the exception thrown is not the `Error` it expects); `aQuoteOpensAPairWrapsASelectionAndStepsOverItsClosingQuote`
fails (`'abc|'` then `'` does not step over the quote) and
`aParenthesisPairsOutsideAStringOnlyAndBackspaceDeletesAnEmptyPair` fails (`'it''|'` then Backspace deletes a pair).

- [ ] **Step 3: Write the formatting, and the quote keystrokes**

In `PAGE/editor-core.js`, replace:
```js
// ------------------------------------------------------------------------------------------------ the query language
```
with:
```js
// ------------------------------------------------------------------------------------------------ query: formatting

/**
 * {@code text} with its keywords and functions in capitals (self as the dialect writes it), each clause on a line of
 * its own, AND and OR on an indented line of their own (the AND of a BETWEEN stays on its line), one space between
 * tokens but around a dot, inside parentheses, before a comma and after a sign; strings, numbers, parameters and
 * names copied as written. Throws, saying where, on an unterminated string.
 */
function formatQuery(text, data) {
  const { v, tokens } = read(text, data);
  const open = tokens.find((t) => t.kind === "string" && !isClosed(text, t));
  if (open !== undefined) throw new Error("line " + lineOf(text, open.from) + ": unterminated string");
  let out = "";
  let between = false;        // a BETWEEN waits for its AND
  let glue = true;            // the previous token takes no space after it: "(", "." or a sign
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
      separator = "\n" + INDENT;
    } else if (keyword === "AND" || keyword === "BETWEEN") {
      between = keyword === "BETWEEN";
    }
    const sign = (c === "-" || c === "+") && (out === "" || ["operator", "keyword"].includes(tokens[k - 1].kind)
      || (tokens[k - 1].kind === "punct" && "(,".includes(text[tokens[k - 1].from])));
    glue = c === "(" || c === "." || sign;
    call = t.kind === "function";
    out += (out === "" ? "" : separator) + piece;
  }
  return out;
}

// ------------------------------------------------------------------------------------------------ the query language
```

In `PAGE/editor-core.js`, replace:
```js
  complete: completeQuery,
  parameters: parametersOf,
```
with:
```js
  complete: completeQuery,
  format: formatQuery,
  parameters: parametersOf,
```

In `PAGE/editor-core.js`, replace:
```js
  const opening = pairs.find((pair) => pair[0] === key);
  const closing = pairs.find((pair) => pair[1] === key);
  if (!opening && !closing) return null;
  if (start !== end) {
    return opening ? { from: start, to: end, insert: key + text.slice(start, end) + opening[1], anchor: 1,
      caret: 1 + end - start } : null;
  }
  const string = stringAround(language, text, start);
  if (closing && text[start] === key
      && (key === "\"" ? string !== null && string.to === start + 1 && isClosed(text, string) : string === null)) {
    return { from: start + 1, to: start + 1, insert: "", caret: 0 };
  }
  if (!opening || string !== null) return null;
  if (key === "\"" && /[\p{L}\p{N}]$/u.test(text.slice(Math.max(0, start - 2), start))) return null;
  return { from: start, to: start, insert: opening, caret: 1 };
}
```
with:
```js
  const opening = pairs.find((pair) => pair[0] === key);
  const closing = pairs.find((pair) => pair[1] === key);
  if (!opening && !closing) return null;
  // a quote opens and closes its strings: " in JSON, ' in a query
  const quote = pairs.some((pair) => pair[0] === key && pair[1] === key);
  if (start !== end) {
    return opening ? { from: start, to: end, insert: key + text.slice(start, end) + opening[1], anchor: 1,
      caret: 1 + end - start } : null;
  }
  const string = stringAround(language, text, start);
  if (closing && text[start] === key
      && (quote ? string !== null && string.to === start + 1 && isClosed(text, string) : string === null)) {
    return { from: start + 1, to: start + 1, insert: "", caret: 0 };
  }
  if (!opening || string !== null) return null;
  if (quote && /[\p{L}\p{N}]$/u.test(text.slice(Math.max(0, start - 2), start))) return null;
  return { from: start, to: start, insert: opening, caret: 1 };
}
```

In `PAGE/editor-core.js`, replace:
```js
  if (offset === 0 || !pairs.includes(text.slice(offset - 1, offset + 1))) return null;
  if (text[offset] === "\"") {
```
with:
```js
  if (offset === 0 || !pairs.includes(text.slice(offset - 1, offset + 1))) return null;
  if (text[offset] === text[offset - 1]) {
```

- [ ] **Step 4: Run the tests to verify they pass, the JSON ones included**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-devconsole-spi,vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=QueryLanguageTest,EditorCoreTest,PageTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run:|BUILD" | tail -5
```
Expected: `QueryLanguageTest` 36, `EditorCoreTest` 50, `PageTest` 28, all `Failures: 0, Errors: 0`, `BUILD SUCCESS`.
`cp PAGE/editor-core.js $SCRATCH/core.mjs && node --check $SCRATCH/core.mjs` prints nothing.

- [ ] **Step 5: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(devconsole): format a query, and pair its quotes and parentheses

Format writes the keywords and functions in capitals, starts each clause on a line of its own and AND or OR on an
indented one, the AND of a BETWEEN kept on its line, and copies names, strings, numbers and parameters as written; a
query with an unterminated string is not formatted, and says on which line. The smart keystrokes treat a pair of one
character as a quote: ' opens, wraps and steps over as " does in JSON, and a doubled quote stays in its string.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/editor-core.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/QueryLanguageTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 8: `setData`, `onDraw`, and the query tokens' colours (§3.1, §4)

**Files:**
- Modify: `PAGE/editor.js` (header, import and re-export, `createEditor`'s option and Javadoc, `dataNote`, `draw`,
  `say`, `formatText`, the returned `setData`)
- Modify: `PAGE/console.css` (five variables in each of the three theme blocks; the query token classes; the
  placeholder)
- Modify: `DCT/PageTest.java` (`createEditor`'s signature; two new tests)

**Interfaces:**
- Consumes: Task 3's `queryLanguage` export; Task 7's `format(text, data)`.
- Produces: `createEditor({ language, data, value, rows, label, onDraw })`, returning `{ root, textarea, value(),
  setValue(text), setData(data, note), disable(on), focus() }`; `editor.js` exports `jsonLanguage`, `queryLanguage`,
  `FORMAT_EXAMPLES`, `createEditor`. CSS: `--code-keyword`, `--code-function`, `--code-target`, `--code-attribute`,
  `--code-parameter`; classes `.ed-keyword`, `.ed-function`, `.ed-target`, `.ed-attribute`, `.ed-parameter`,
  `.ed-identifier`, `.ed-operator`.

- [ ] **Step 1: Write the failing tests**

In `DCT/PageTest.java`, replace:
```java
        assertEquals(List.of("./editor-core.js"), IMPORT.matcher(editor).results().map(m -> m.group(1)).toList());
        assertTrue(editor.contains("export function createEditor({ language, data, value, rows, label })"), "spec §4");
```
with:
```java
        assertEquals(List.of("./editor-core.js"), IMPORT.matcher(editor).results().map(m -> m.group(1)).toList());
        assertTrue(editor.contains("export function createEditor({ language, data, value, rows, label, onDraw })"),
                "spec §4");
```

In `DCT/PageTest.java`, replace:
```java
        assertTrue(editor.contains("el(\"button\", \"ed-format\", \"Format\")"), "the Format button");
    }
```
with:
```java
        assertTrue(editor.contains("el(\"button\", \"ed-format\", \"Format\")"), "the Format button");
    }

    @Test
    void setDataGivesTheEditorOtherDataAndKeepsItsTextAndCaret() {
        String editor = file("editor.js");
        String setData = editor.substring(editor.indexOf("    setData(next, why) {"),
                editor.indexOf("    disable(on) {"));

        assertTrue(editor.contains("tokens = safely(() => language.tokenize(text, data), []);"),
                "a query's tokens depend on its vocabulary");
        assertTrue(editor.contains("formatted = language.format(textarea.value, data);"), "and its format");
        assertTrue(setData.contains("data = next;") && setData.contains("closeList();")
                && setData.contains("schedule();"), "colours and diagnostics again, the list closed (query spec §4)");
        assertFalse(setData.contains("textarea.value") || setData.contains("setSelectionRange"),
                "the vocabulary arriving while the user types loses neither the text nor the caret");
        assertTrue(editor.contains("note.textContent = [limit, counts, formatNote, dataNote]"),
                "no vocabulary: <status> said under the editor");
        assertTrue(editor.contains("if (onDraw) safely(() => onDraw(text), null);"),
                "after each draw, in its animation frame: the parameters follow the query");
        assertTrue(editor.contains("export { jsonLanguage, queryLanguage, FORMAT_EXAMPLES };"));
    }
```

In `DCT/PageTest.java`, replace:
```java
        assertTrue(rule(style, ".ed-text {").contains("color: transparent"), "the textarea shows the <pre>'s text");
        assertTrue(rule(style, ".ed-pre, .ed-text, .ed-mirror {").contains("white-space: pre-wrap"),
                "the <pre>, the textarea and the caret's mirror wrap alike");
    }
```
with:
```java
        assertTrue(rule(style, ".ed-text {").contains("color: transparent"), "the textarea shows the <pre>'s text");
        assertTrue(rule(style, ".ed-pre, .ed-text, .ed-mirror {").contains("white-space: pre-wrap"),
                "the <pre>, the textarea and the caret's mirror wrap alike");
    }

    @Test
    void theQueryTokensHaveSevenDistinctColoursInEveryTheme() {
        String style = file("console.css");

        for (String kind : List.of("keyword", "function", "target", "attribute", "parameter")) {
            assertTrue(rule(style, ".ed-" + kind + " {").contains("var(--code-" + kind + ")"), kind);
        }
        assertTrue(rule(style, ".ed-identifier {").contains("var(--ink)"), "a name the vocabulary does not know");
        assertTrue(rule(style, ".ed-operator {").contains("var(--json-punct)"));
        for (String opening : List.of(":root {", ":root:not([data-theme=\"light\"]) {",
                ":root[data-theme=\"dark\"] {")) {
            String theme = rule(style, opening);
            List<String> colours = List.of("--code-keyword", "--code-function", "--code-target", "--code-attribute",
                    "--code-parameter", "--json-string", "--json-number").stream().map(name -> {
                        Matcher value = Pattern.compile(Pattern.quote(name) + ":\\s*(#[0-9A-Fa-f]{6});").matcher(theme);
                        assertTrue(value.find(), name + " in " + opening);
                        return value.group(1).toUpperCase();
                    }).toList();
            assertEquals(7, colours.stream().distinct().count(), "each distinct in " + opening + ": " + colours);
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-devconsole-spi,vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=PageTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run:|PageTest\.[a-zA-Z]+|BUILD" | tail -6
```
Expected: FAIL — `Tests run: 30, Failures: 2, Errors: 1`: `theEditorImportsTheCoreAndDrawsItsTextWithTextContent`
fails (`spec §4`), `theQueryTokensHaveSevenDistinctColoursInEveryTheme` fails (`no rule .ed-keyword {`), and
`setDataGivesTheEditorOtherDataAndKeepsItsTextAndCaret` errors with a `StringIndexOutOfBoundsException` (there is no
`setData(next, why)` to cut at yet).

- [ ] **Step 3: Give the editor its data, and the query tokens their colours**

In `PAGE/editor.js`, replace:
```js
// - Every edit it makes goes through document.execCommand("insertText"), which keeps Ctrl+Z; where the browser
//   refuses it, setRangeText and an input event.

import { jsonLanguage, keystroke, isShortcut, FORMAT_EXAMPLES } from "./editor-core.js";

export { jsonLanguage, FORMAT_EXAMPLES };
```
with:
```js
// - Every edit it makes goes through document.execCommand("insertText"), which keeps Ctrl+Z; where the browser
//   refuses it, setRangeText and an input event.
// - setData() gives it other data, such as a query's vocabulary once fetched or the schema a query's parameters
//   follow: the next frame draws with it, the text and the caret untouched.

import { jsonLanguage, queryLanguage, keystroke, isShortcut, FORMAT_EXAMPLES } from "./editor-core.js";

export { jsonLanguage, queryLanguage, FORMAT_EXAMPLES };
```

In `PAGE/editor.js`, replace:
```js
/**
 * An editor of {@code language} (spec §4), {@code data} its data (for JSON, the argument's schema), starting with
 * {@code value}, {@code rows} lines high; {@code label} names it for a screen reader.
 *
 * @returns {{root: HTMLElement, value(): string, setValue(text: string): void, disable(on: boolean): void,
 *   focus(): void, textarea: HTMLTextAreaElement}}
 */
export function createEditor({ language, data, value, rows, label }) {
```
with:
```js
/**
 * An editor of {@code language} (spec §4), {@code data} its data (for JSON, the argument's schema; for a query, the
 * language its panel publishes), starting with {@code value}, {@code rows} lines high; {@code label} names it for a
 * screen reader. {@code onDraw}, when given, is called with the text at the end of each draw, in its animation frame.
 * setData(data, note) gives it other data (a query's language once fetched, the schema a query's parameters follow):
 * tokens, colours and diagnostics are computed again, an open completion list closes, the text and the caret stay;
 * note, when given, is said under the editor, such as why the data could not be had.
 *
 * @returns {{root: HTMLElement, value(): string, setValue(text: string): void, setData(data: *, note: string): void,
 *   disable(on: boolean): void, focus(): void, textarea: HTMLTextAreaElement}}
 */
export function createEditor({ language, data, value, rows, label, onDraw }) {
```

In `PAGE/editor.js`, replace:
```js
  let formatNote = "";          // why Format refused, until the next edit
```
with:
```js
  let formatNote = "";          // why Format refused, until the next edit
  let dataNote = "";            // what setData said of its data, such as "no vocabulary: 404"
```

In `PAGE/editor.js`, replace:
```js
    } else {
      tokens = safely(() => language.tokenize(text), []);
      diagnostics = safely(() => language.diagnose(text, data), []);
      matches = bracketMatches(text);
      paint(text);
    }
    root.style.setProperty("--ed-digits", String(Math.max(2, String(Math.max(1, shown.length)).length)));
    say();
    follow();
    outline();
    tipAtCaret();
  }

  /** The note under the editor: how many errors and warnings, the size limit, why Format refused. */
  function say() {
    const errors = diagnostics.filter((d) => d.severity === "error").length;
    const warnings = diagnostics.length - errors;
    const counts = [errors ? plural(errors, "error", "errors") : "", warnings ? plural(warnings, "warning", "warnings")
      : ""].filter(Boolean).join(", ");
    const limit = textarea.value.length > LIMIT ? "past 100 000 characters: no colours, no checks" : "";
    note.textContent = [limit, counts, formatNote].filter(Boolean).join(" · ");
```
with:
```js
    } else {
      tokens = safely(() => language.tokenize(text, data), []);
      diagnostics = safely(() => language.diagnose(text, data), []);
      matches = bracketMatches(text);
      paint(text);
    }
    root.style.setProperty("--ed-digits", String(Math.max(2, String(Math.max(1, shown.length)).length)));
    say();
    follow();
    outline();
    tipAtCaret();
    if (onDraw) safely(() => onDraw(text), null);
  }

  /** The note under the editor: how many errors and warnings, the size limit, why Format refused, setData's note. */
  function say() {
    const errors = diagnostics.filter((d) => d.severity === "error").length;
    const warnings = diagnostics.length - errors;
    const counts = [errors ? plural(errors, "error", "errors") : "", warnings ? plural(warnings, "warning", "warnings")
      : ""].filter(Boolean).join(", ");
    const limit = textarea.value.length > LIMIT ? "past 100 000 characters: no colours, no checks" : "";
    note.textContent = [limit, counts, formatNote, dataNote].filter(Boolean).join(" · ");
```

In `PAGE/editor.js`, replace:
```js
    let formatted;
    try {
      formatted = language.format(textarea.value);
    } catch (refused) {
```
with:
```js
    let formatted;
    try {
      formatted = language.format(textarea.value, data);
    } catch (refused) {
```

In `PAGE/editor.js`, replace:
```js
    setValue(text) {
      textarea.value = typeof text === "string" ? text : "";
      textarea.scrollTop = 0;
      formatNote = "";
      closeList();
      schedule();
    },
```
with:
```js
    setValue(text) {
      textarea.value = typeof text === "string" ? text : "";
      textarea.scrollTop = 0;
      formatNote = "";
      closeList();
      schedule();
    },
    setData(next, why) {
      data = next;
      dataNote = typeof why === "string" ? why : "";
      closeList();
      schedule();
    },
```

In `PAGE/console.css`, replace (the light theme):
```css
  --json-punct: #8B94A2;
  --sans:
```
with:
```css
  --json-punct: #8B94A2;
  --code-keyword: #7A3E9D;
  --code-function: #0F766E;
  --code-target: #B0306E;
  --code-attribute: #1F5F8B;
  --code-parameter: #6D7A00;
  --sans:
```

In `PAGE/console.css`, replace (the system's dark theme, indented four spaces):
```css
    --json-punct: #6E7988;
    color-scheme: dark;
```
with:
```css
    --json-punct: #6E7988;
    --code-keyword: #C99BE0;
    --code-function: #5FC8B8;
    --code-target: #F08CB8;
    --code-attribute: #7FB2E0;
    --code-parameter: #C8D86A;
    color-scheme: dark;
```

In `PAGE/console.css`, replace (the forced dark theme, indented two spaces, its closing brace included):
```css
  --json-punct: #6E7988;
  color-scheme: dark;
}
```
with:
```css
  --json-punct: #6E7988;
  --code-keyword: #C99BE0;
  --code-function: #5FC8B8;
  --code-target: #F08CB8;
  --code-attribute: #7FB2E0;
  --code-parameter: #C8D86A;
  color-scheme: dark;
}
```

In `PAGE/console.css`, replace:
```css
.ed-punct { color: var(--json-punct); }
.ed-invalid { color: var(--crit); }
```
with:
```css
.ed-punct { color: var(--json-punct); }
.ed-invalid { color: var(--crit); }
/* The query mode's tokens: its strings, numbers and punctuation take JSON's colours. */
.ed-keyword { color: var(--code-keyword); }
.ed-function { color: var(--code-function); }
.ed-target { color: var(--code-target); }
.ed-attribute { color: var(--code-attribute); }
.ed-parameter { color: var(--code-parameter); }
.ed-identifier { color: var(--ink); }
.ed-operator { color: var(--json-punct); }
.ed-text::placeholder { color: var(--faint); }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: the command of Step 2.
Expected: `Tests run: 30, Failures: 0, Errors: 0`, `BUILD SUCCESS`. Then
`cp PAGE/editor.js $SCRATCH/editor.mjs && node --check $SCRATCH/editor.mjs` prints nothing.

- [ ] **Step 5: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(devconsole): the editor takes new data without losing its text, and colours a query

createEditor gains setData(data, note) — a query's vocabulary once fetched, the schema its parameters follow —
which draws again and closes the completion list, never touching the text or the caret, and says its note under the
editor; and onDraw(text), called after each draw in its animation frame. The data now reaches tokenize and format.
Five colour variables, in the light theme and both dark ones, give keywords, functions, the target, attributes and
parameters colours distinct from each other and from strings and numbers.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/editor.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/console.css vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/PageTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 9: The page — a query editor, its language fetched once, a parameters editor that follows it (§4)

**Files:**
- Modify: `PAGE/console.js` (header comment and import; `actionRow` passes the panel; the language constants,
  `panelLanguage` and `fetchLanguage` before `MAX_FILE_BYTES`; in `jsonField`: its Javadoc and signature, the query and
  parameters editors, `field`, the followers joined, `toForm`, `disable`)
- Modify: `DCT/PageTest.java` (three pinned lines follow the new code; two new tests)

**Interfaces:**
- Consumes: Task 2's `GET api/language/<panel>/<id>` and the snapshot's `panels[].languages`; Task 6's
  `queryLanguage().parameters(text, data)`; Task 8's `createEditor({ …, onDraw })`, `setData(data, note)`,
  `queryLanguage` re-export.
- Produces: `jsonField(argument, panelId)`; module-level `QUERY_TYPE`, `LANGUAGE`, `PARAMETERS_OF`, `languages` (a
  `Map`), `languagesBoot`, `panelLanguage(panelId, id) → Promise<{ data, note }>`, `fetchLanguage(panelId, id)`; in
  `jsonField`: `queries` (a `Map` by path of `{ editor, data, followers }`), `followers`, `queryEditor(definition,
  path)`, `parametersEditor(definition, path, property)`, and the `inputs` entries `{ input, kind, text, code }`.

- [ ] **Step 1: Write the failing tests**

In `DCT/PageTest.java`, replace:
```java
        assertTrue(script.contains("function jsonField(argument)"), "a json argument's field");
```
with:
```java
        assertTrue(script.contains("function jsonField(argument, panelId)"), "a json argument's field");
```

In `DCT/PageTest.java`, replace:
```java
        assertTrue(script.contains("import { createEditor, jsonLanguage, FORMAT_EXAMPLES } from \"./editor.js\";"));
```
with:
```java
        assertTrue(script.contains(
                "import { createEditor, jsonLanguage, queryLanguage, FORMAT_EXAMPLES } from \"./editor.js\";"));
```

In `DCT/PageTest.java`, replace:
```java
        assertTrue(script.contains("inputs.set(f, { input, kind, text: chooser ? chooser.text : null });"),
                "the form keeps it");
```
with:
```java
        assertTrue(script.contains("inputs.set(f, { input, kind, text: chooser ? chooser.text : null, code });"),
                "the form keeps it");
```

In `DCT/PageTest.java`, replace:
```java
    private static String chooser(String script) {
        return script.substring(script.indexOf("function fileChooser("), script.indexOf("function jsonField("));
    }
```
with:
```java
    private static String chooser(String script) {
        return script.substring(script.indexOf("function fileChooser("), script.indexOf("function jsonField("));
    }

    @Test
    void aQueryPropertyIsTheQueryEditorItsPanelsLanguageFetchedOncePerBootAndShared() {
        String script = file("console.js");
        String fetching = script.substring(script.indexOf("async function fetchLanguage("),
                script.indexOf("const MAX_FILE_BYTES"));

        assertTrue(script.contains("const QUERY_TYPE = \"text/x-query\";"), "contentMediaType: text/x-query");
        assertTrue(script.contains("const query = multiLine && definition.contentMediaType === QUERY_TYPE;"),
                "a string property of format textarea and that media type");
        assertTrue(script.contains("createEditor({ language: queryLanguage(), data: null, value: \"\", rows: 5,"),
                "five lines, no vocabulary until it arrives");
        assertTrue(script.contains("const id = definition[LANGUAGE];") && script.contains("const LANGUAGE = "
                + "\"x-language\";"), "the id its x-language names");
        assertTrue(script.contains("if (!languages.has(key)) {")
                && script.contains("languages.set(key, offered ? fetchLanguage(panelId, id)"),
                "one request per panel and id, a promise every editor shares");
        assertTrue(script.contains("if (boot !== languagesBoot) {"), "fetched again after a dev reload only");
        assertTrue(script.contains("query.editor.setData(data, note);"), "given to the editor once it arrives");
        assertTrue(fetching.contains("fetch(\"api/language/\" + encodeURIComponent(panelId)"),
                "to the console itself, never another origin");
        assertFalse(fetching.contains("X-Vidocq-Console-Token"), "a GET that only reads: no token");
        assertTrue(fetching.contains("\"no vocabulary: \" + response.status"), "a failed fetch says why");
    }

    @Test
    void aParametersPropertyIsAJsonEditorWhoseSchemaFollowsItsQueryAndItsTextIsSentAsTyped() {
        String script = file("console.js");

        assertTrue(script.contains("const PARAMETERS_OF = \"x-parameters-of\";"));
        assertTrue(script.contains(
                "const parameters = multiLine && !query && typeof definition[PARAMETERS_OF] === \"string\";"));
        assertTrue(script.contains("follow: (text, data) => editor.setData(queryLanguage().parameters(text, data))"),
                "its schema is the query's parameters()");
        assertTrue(script.contains(
                "onDraw: (text) => { for (const follow of query.followers) follow(text, query.data); }"),
                "computed again after each draw of the query, and when its vocabulary arrives");
        assertTrue(script.contains("wrap.append(code ? code.root : input);"), "the editor in place of the textarea");
        assertTrue(script.contains("if (code) code.setValue("), "a replay fills the query and its parameters");
        assertTrue(script.contains("for (const { code } of inputs.values()) if (code) code.disable(on);"),
                "disabled while the form is sent");
        assertTrue(script.contains("object[property] = fileText ? fileText() : input.value;"),
                "sent as typed: an id past 2^53 is never rounded");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-devconsole-spi,vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=PageTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run:|BUILD" | tail -3
```
Expected: FAIL — `Tests run: 32, Failures: 4, Errors: 1`:
`jsonArgumentsGetAFormWhenFormShapeAcceptsTheirSchemaAndTheEditorOtherwise`,
`theConsoleImportsTheEditorAndNoOtherScript` and `anUneditedFileIsSentAsReadItsLineEndsKept` fail on their updated
line, `aParametersPropertyIsAJsonEditorWhoseSchemaFollowsItsQueryAndItsTextIsSentAsTyped` fails, and
`aQueryPropertyIsTheQueryEditorItsPanelsLanguageFetchedOncePerBootAndShared` errors with a
`StringIndexOutOfBoundsException` (no `fetchLanguage` to cut at).

- [ ] **Step 3: Wire the query editor, its language and its parameters**

In `PAGE/console.js`, replace:
```js
// - A text/csv answer is shown as text with a Download button, which saves it in the browser, no request sent. A
//   textarea of a form whose schema says "contentMediaType": "text/csv" gets Choose file, which reads a local file of
//   60 KiB at most into it; nothing is sent until the form is.

import { createEditor, jsonLanguage, FORMAT_EXAMPLES } from "./editor.js";
```
with:
```js
// - A text/csv answer is shown as text with a Download button, which saves it in the browser, no request sent. A
//   textarea of a form whose schema says "contentMediaType": "text/csv" gets Choose file, which reads a local file of
//   60 KiB at most into it; nothing is sent until the form is.
// - A string property of "contentMediaType": "text/x-query" is the query editor: its vocabulary is the panel's
//   language its "x-language" names, fetched once per boot from api/language (a GET, no token) and shared by every
//   editor waiting for it. A property of "x-parameters-of": "<query property>" is a JSON editor whose schema is that
//   query's parameters, computed again as the query is typed; both send their text as typed.

import { createEditor, jsonLanguage, queryLanguage, FORMAT_EXAMPLES } from "./editor.js";
```

In `PAGE/console.js`, replace:
```js
    const field = isObject(argument.schema) ? jsonField(argument) : stringField(argument);
```
with:
```js
    const field = isObject(argument.schema) ? jsonField(argument, panelId) : stringField(argument);
```

In `PAGE/console.js`, replace:
```js
/** Past this size a chosen file is not read: the console takes a request of 64 KiB at most. */
const MAX_FILE_BYTES = 60 * 1024;
```
with:
```js
/** The media type of a string property that is a query: its field is the query editor (query mode spec §4). */
const QUERY_TYPE = "text/x-query";
/** The keyword of a query property that names its panel's language, and the one of its parameters' property. */
const LANGUAGE = "x-language";
const PARAMETERS_OF = "x-parameters-of";
/** The languages the query editors asked for, by panel and id, each fetched once for the boot languagesBoot. */
const languages = new Map();
let languagesBoot = null;

/**
 * The language {@code id} of panel {@code panelId}, for the query editors (query mode spec §4): a promise of { data,
 * note }, fetched once per boot (a dev reload may bring other entities), only when the snapshot says the panel offers
 * it, and shared by every editor waiting for it; data null and note "no vocabulary: <why>" when it cannot be had.
 */
function panelLanguage(panelId, id) {
  const snapshot = page.snapshot;
  const boot = snapshot && snapshot.console ? snapshot.console.boot : null;
  if (boot !== languagesBoot) {
    languages.clear();
    languagesBoot = boot;
  }
  const key = panelId + "\u0000" + id;
  if (!languages.has(key)) {
    const panel = snapshot && Array.isArray(snapshot.panels) ? snapshot.panels.find((p) => p.id === panelId) : null;
    const offered = !!panel && Array.isArray(panel.languages) && panel.languages.includes(id);
    languages.set(key, offered ? fetchLanguage(panelId, id)
      : Promise.resolve({ data: null, note: "no vocabulary: not offered" }));
  }
  return languages.get(key);
}

/** GET api/language/<panel>/<id>, same-origin, no token: { data, note }, never rejected. */
async function fetchLanguage(panelId, id) {
  let response;
  try {
    response = await fetch("api/language/" + encodeURIComponent(panelId) + "/" + encodeURIComponent(id), {
      headers: { Accept: "application/json" },
    });
  } catch (unreachable) {
    return { data: null, note: "no vocabulary: the console did not answer" };
  }
  if (!response.ok) return { data: null, note: "no vocabulary: " + response.status };
  try {
    return { data: await response.json(), note: "" };
  } catch (unreadable) {
    return { data: null, note: "no vocabulary: unreadable" };
  }
}

/** Past this size a chosen file is not read: the console takes a request of 64 KiB at most. */
const MAX_FILE_BYTES = 60 * 1024;
```

In `PAGE/console.js`, replace:
```js
 * the form's value in that editor; switching back keeps the values. value() returns the JSON text sent, or throws
 * what is wrong: the editor's own text once it parses as an object, so that an id past 2^53 reaches the server as
 * typed.
 */
function jsonField(argument) {
```
with:
```js
 * the form's value in that editor; switching back keeps the values. value() returns the JSON text sent, or throws
 * what is wrong: the editor's own text once it parses as an object, so that an id past 2^53 reaches the server as
 * typed. A query property of the form is the query editor, the vocabulary of panel {@code panelId}'s language it
 * names given to it once fetched; a property holding its parameters, a JSON editor whose schema follows the query.
 */
function jsonField(argument, panelId) {
```

In `PAGE/console.js`, replace:
```js
  const inputs = new Map();             // by field of the shape: { input, kind, text }
  const choosers = [];                  // the file inputs of the CSV fields, disabled with the form
  const raw = el("input");
  raw.type = "checkbox";

  /** The label and input of field {@code f}, {@code path} its name from the root, "entity.title". */
  function field(f, path) {
    const definition = f.definition;
    const kind = f.kind;
    const wrap = el("label", "arg");
    wrap.append(el("span", null, f.name + (f.required ? " *" : "") + (f.readOnly ? " (generated)" : "")));
    let input;
    let chooser = null;
    if (kind === "enum" || kind === "boolean") {
```
with:
```js
  const inputs = new Map();             // by field of the shape: { input, kind, text, code }
  const choosers = [];                  // the file inputs of the CSV fields, disabled with the form
  const queries = new Map();            // the query editors by path: { editor, data, followers }
  const followers = [];                 // the parameters editors: { of, follow(text, data) }, joined once all are built
  const raw = el("input");
  raw.type = "checkbox";

  /**
   * The query editor of a query property (spec §4), five lines high: the query language, whose data is the panel's
   * language its x-language names, given to it once fetched; its followers are told its text after each draw.
   */
  function queryEditor(definition, path) {
    const query = { editor: null, data: null, followers: [] };
    query.editor = createEditor({ language: queryLanguage(), data: null, value: "", rows: 5, label: path,
      onDraw: (text) => { for (const follow of query.followers) follow(text, query.data); } });
    const id = definition[LANGUAGE];
    if (typeof id === "string" && id !== "") {
      panelLanguage(panelId, id).then(({ data, note }) => {
        query.data = data;
        query.editor.setData(data, note);
      });
    }
    queries.set(path, query);
    return query.editor;
  }

  /**
   * The JSON editor of a property holding the parameters of the query property its x-parameters-of names, beside it:
   * its schema is that query's parameters(), computed again after each draw of the query; "{}" when it is empty.
   */
  function parametersEditor(definition, path, property) {
    const editor = createEditor({ language: jsonLanguage, data: null, value: "{}", rows: 4, label: path });
    followers.push({ of: path.slice(0, path.length - property.length) + definition[PARAMETERS_OF],
      follow: (text, data) => editor.setData(queryLanguage().parameters(text, data)) });
    return editor;
  }

  /** The label and input of field {@code f}, {@code path} its name from the root, "entity.title". */
  function field(f, path) {
    const definition = f.definition;
    const kind = f.kind;
    const multiLine = kind === "string" && definition.format === "textarea";
    const query = multiLine && definition.contentMediaType === QUERY_TYPE;
    const parameters = multiLine && !query && typeof definition[PARAMETERS_OF] === "string";
    // an editor's buttons and completion list are no part of a label
    const wrap = el(query || parameters ? "div" : "label", "arg");
    wrap.append(el("span", null, f.name + (f.required ? " *" : "") + (f.readOnly ? " (generated)" : "")));
    let input;
    let chooser = null;
    let code = null;
    if (kind === "enum" || kind === "boolean") {
```

In `PAGE/console.js`, replace:
```js
        option.value = v;
        input.append(option);
      }
    } else if (kind === "string" && definition.format === "textarea") {
      // A text of several lines, such as a query: a textarea, read, filled and sent as an input is.
      input = el("textarea", "json-text");
      input.rows = 4;
      input.spellcheck = false;
      wrap.classList.add("wide");
      if (definition.contentMediaType === "text/csv") chooser = fileChooser(input);
    } else {
      input = el("input");
      input.type = "text";
      input.autocomplete = "off";
      input.spellcheck = false;
      if (kind !== "string") input.inputMode = "decimal";
      input.placeholder = f.readOnly ? "generated" : FORMAT_EXAMPLES.get(definition.format) || "";
    }
    if (definition.default !== undefined && definition.default !== null) input.value = String(definition.default);
    if (typeof definition.description === "string") input.title = definition.description;
    input.name = argument.name + "." + path;
    wrap.append(input);
    if (chooser) {
      wrap.append(chooser.root);
      choosers.push(chooser.file);
    }
    inputs.set(f, { input, kind, text: chooser ? chooser.text : null });
    return wrap;
  }
```
with:
```js
        option.value = v;
        input.append(option);
      }
    } else if (query || parameters) {
      // A query, or its parameters: a code editor, read, filled and sent as a textarea is, through its own textarea.
      code = query ? queryEditor(definition, path) : parametersEditor(definition, path, f.name);
      input = code.textarea;
      wrap.classList.add("wide");
    } else if (kind === "string" && definition.format === "textarea") {
      // A text of several lines, such as a CSV file: a textarea, read, filled and sent as an input is.
      input = el("textarea", "json-text");
      input.rows = 4;
      input.spellcheck = false;
      wrap.classList.add("wide");
      if (definition.contentMediaType === "text/csv") chooser = fileChooser(input);
    } else {
      input = el("input");
      input.type = "text";
      input.autocomplete = "off";
      input.spellcheck = false;
      if (kind !== "string") input.inputMode = "decimal";
      input.placeholder = f.readOnly ? "generated" : FORMAT_EXAMPLES.get(definition.format) || "";
    }
    if (definition.default !== undefined && definition.default !== null) {
      if (code) code.setValue(String(definition.default));
      else input.value = String(definition.default);
    }
    if (typeof definition.description === "string") {
      // an editor's own tooltips show its diagnostics: its description is the placeholder of an empty text
      if (code) input.placeholder = definition.description;
      else input.title = definition.description;
    }
    input.name = argument.name + "." + path;
    wrap.append(code ? code.root : input);
    if (chooser) {
      wrap.append(chooser.root);
      choosers.push(chooser.file);
    }
    inputs.set(f, { input, kind, text: chooser ? chooser.text : null, code });
    return wrap;
  }
```

In `PAGE/console.js`, replace:
```js
    const toggle = el("label", "json-switch");
    toggle.append(raw, el("span", null, "JSON"));
    head.append(toggle);
  }
  head.append(note);
```
with:
```js
    const toggle = el("label", "json-switch");
    toggle.append(raw, el("span", null, "JSON"));
    head.append(toggle);
  }
  // each parameters editor follows the query it names, from now on and from its text of now
  for (const { of, follow } of followers) {
    const query = queries.get(of);
    if (!query) continue;
    query.followers.push(follow);
    follow(query.editor.value(), query.data);
  }
  head.append(note);
```

In `PAGE/console.js`, replace:
```js
      if (f.kind === "object") {
        toForm(v, f.fields);
        continue;
      }
      inputs.get(f).input.value = v === undefined || v === null ? "" : typeof v === "object" ? JSON.stringify(v)
        : String(v);
    }
  }
```
with:
```js
      if (f.kind === "object") {
        toForm(v, f.fields);
        continue;
      }
      const shown = v === undefined || v === null ? "" : typeof v === "object" ? JSON.stringify(v) : String(v);
      const { input, code } = inputs.get(f);
      // an empty parameters editor holds {}, which the schema of a query without parameters accepts
      if (code) code.setValue(shown === "" && typeof f.definition[PARAMETERS_OF] === "string" ? "{}" : shown);
      else input.value = shown;
    }
  }
```

In `PAGE/console.js`, replace:
```js
    disable(on) {
      editor.disable(on);
      for (const c of [raw, ...[...inputs.values()].map((i) => i.input), ...choosers]) c.disabled = on;
    },
  };
}
```
with:
```js
    disable(on) {
      editor.disable(on);
      for (const c of [raw, ...[...inputs.values()].map((i) => i.input), ...choosers]) c.disabled = on;
      for (const { code } of inputs.values()) if (code) code.disable(on);
    },
  };
}
```

- [ ] **Step 4: Run the console's tests to verify they pass**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-devconsole-spi,vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test 2>&1 | grep -E "Tests run:.*Fail|FAIL|BUILD" | grep -v " in io" | tail -4
```
(`timeout: 600000`.) Expected: the SPI's 67 and the console's 470 tests, `Failures: 0, Errors: 0`, `BUILD SUCCESS`.
Then check the scripts parse and no new line passes 120 characters:
```bash
cd $VIDOCQ && P=vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole && for f in console editor editor-core; do cp $P/$f.js $SCRATCH/check-$f.mjs && node --check $SCRATCH/check-$f.mjs && echo "$f parses"; done; git diff -U0 | grep '^+' | grep -v '^+++' | awk 'length > 121' | grep -v '\.adoc' | head -3
```
Expected: `console parses`, `editor parses`, `editor-core parses`, and no line after them.

- [ ] **Step 5: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(devconsole): a query field is the query editor, and its parameters follow it

A string property of the generated form with contentMediaType text/x-query is the query editor, five lines high:
its x-language is fetched once per boot from api/language, only when the snapshot lists it, and shared by every
editor waiting for it; until then, or when it fails, the editor works with keywords only and says why. A property of
x-parameters-of is a JSON editor whose schema is that query's parameters, computed again after each draw of the
query and when its vocabulary arrives; both send their text as typed, replay fills them, and they are disabled while
a call runs.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/console.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/PageTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 10: Mansart Data's `jdql` language, and the three query actions' schemas (§2.4)

**Files:**
- Create: `MDM/JdqlLanguage.java`
- Modify: `MDM/CatalogueLivePanel.java` (import, class Javadoc, `languages()` and `languages(models)`)
- Modify: `MDM/JdqlActions.java` (`schema()`, new `query(example)` and `params()`), `MDM/CsvActions.java`
  (`exportSchema()`)
- Create: `MDT/JdqlLanguageTest.java`
- Modify: `MDT/CatalogueLivePanelTest.java`, `MDT/JdqlActionsTest.java`, `MDT/CsvActionsTest.java`

**Interfaces:**
- Consumes: Task 1's `PanelLanguage`; the catalogue (`MansartDataCatalogue.Entity(name, className, table, columns,
  failure)`), `MansartDataLive.catalogue()`/`repositories()`, `RepositoryActions.load(className, repositories)`,
  `Scalars.schema(Class)`, `Scalars.object(…)`, `Json.write`/`Json.parse`, Mansart's `EntityModel`, `Attribute`,
  `IdAttribute`, `VersionAttribute`, `ReferenceAttribute`, `JoinedAttribute`.
- Produces: `JdqlLanguage.ID = "jdql"`, `JdqlLanguage.MEDIA_TYPE = "text/x-query"`, `KEYWORDS`, `FUNCTIONS`, `CLAUSES`,
  `TARGET_AFTER`, `static String json(List<Entity>, Function<String, Class<?>>, Function<Class<?>, EntityModel<?>>)`;
  `CatalogueLivePanel.languages()` and `languages(Function<Class<?>, EntityModel<?>>)`;
  `JdqlActions.query(String example)` and `JdqlActions.params()` (`Map<String, Object>` schemas).

- [ ] **Step 1: Write the failing tests**

Create `MDT/JdqlLanguageTest.java`:

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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Broken;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Gizmo;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Part;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Slot;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Task;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code jdql} language of the Mansart Data panel (query mode spec §2.4): the dialect of JDQL, one target per
 * entity whose model could be read, its attributes typed as the page's editor checks them.
 */
class JdqlLanguageTest {

    private static MansartDataCatalogue.Entity entity(String name, Class<?> type, String table) {
        return new MansartDataCatalogue.Entity(name, type.getName(), table, List.of(), null);
    }

    /** The language of these entities, their classes loaded as the panel loads them, their models the fixtures'. */
    private static Map<?, ?> language(MansartDataCatalogue.Entity... entities) {
        return (Map<?, ?>) Json.parse(JdqlLanguage.json(List.of(entities),
                className -> RepositoryActions.load(className, RunFixtures.REPOSITORIES), RunFixtures::model));
    }

    private static Map<?, ?> targets(MansartDataCatalogue.Entity... entities) {
        return (Map<?, ?>) language(entities).get("targets");
    }

    @Test
    void theDialectIsTheWordsOfMansartsJdql() {
        assertEquals("{\"mode\":\"query\",\"dialect\":{\"keywords\":[\"SELECT\",\"FROM\",\"WHERE\",\"ORDER\",\"BY\","
                + "\"AND\",\"OR\",\"NOT\",\"IS\",\"NULL\",\"BETWEEN\",\"LIKE\",\"IN\",\"ASC\",\"DESC\",\"UPDATE\","
                + "\"SET\",\"DELETE\",\"COUNT\",\"THIS\",\"SUM\",\"AVG\",\"MIN\",\"MAX\",\"TRUE\",\"FALSE\"],"
                + "\"functions\":[\"UPPER\",\"LOWER\",\"LENGTH\",\"ABS\",\"CONCAT\",\"COUNT\",\"SUM\",\"AVG\",\"MIN\","
                + "\"MAX\"],\"clauses\":[\"SELECT\",\"FROM\",\"WHERE\",\"ORDER BY\",\"SET\",\"UPDATE\",\"DELETE FROM\"],"
                + "\"targetAfter\":[\"FROM\",\"UPDATE\"],\"self\":\"this\",\"quote\":\"'\"},\"targets\":{}}",
                JdqlLanguage.json(List.of(), className -> null, type -> null));
    }

    @Test
    void oneTargetPerEntityWhoseModelIsReadUnderItsSimpleNameInNameOrder() {
        Map<?, ?> targets = targets(entity("Task", Task.class, "tasks"), entity("Gizmo", Gizmo.class, "gizmos"),
                new MansartDataCatalogue.Entity("Broken", Broken.class.getName(), "", List.of(), "unusable model"),
                new MansartDataCatalogue.Entity("Missing", "com.acme.Missing", "missing", List.of(), null),
                new MansartDataCatalogue.Entity("x.Twin", "x.Twin", "twins", List.of(), null),
                new MansartDataCatalogue.Entity("y.Twin", "y.Twin", "twins", List.of(), null),
                entity("Part", Part.class, "parts"));

        assertEquals(List.of("Gizmo", "Part", "Task"), List.copyOf(targets.keySet()),
                "no model, no class, or a name two entities share: left out");
        assertEquals("table gizmos", ((Map<?, ?>) targets.get("Gizmo")).get("detail"));
    }

    @Test
    void anAttributeHasTheTypeTheEditorChecksAndADetailOfItsJavaTypeAndItsKeyOrColumn() {
        assertEquals("{\"id\":{\"type\":\"integer\",\"detail\":\"Long · id, generated\"},"
                + "\"version\":{\"type\":\"integer\",\"detail\":\"Long · version\"},"
                + "\"title\":{\"type\":\"string\",\"detail\":\"String · column title\"},"
                + "\"notes\":{\"type\":\"string\",\"detail\":\"String · column notes\"},"
                + "\"points\":{\"type\":\"integer\",\"detail\":\"Integer · column points\"},"
                + "\"level\":{\"type\":\"string\",\"enum\":[\"LOW\",\"HIGH\"],\"detail\":\"Level · column level\"},"
                + "\"owner\":{\"type\":\"integer\",\"detail\":\"→ Gizmo · column owner_id\",\"target\":\"Gizmo\"}}",
                Json.write(((Map<?, ?>) targets(entity("Task", Task.class, "tasks")).get("Task")).get("attributes")));
        assertEquals("{\"id\":{\"type\":\"integer\",\"detail\":\"Long · id\"},"
                + "\"name\":{\"type\":\"string\",\"detail\":\"String · column name\"},"
                + "\"stock\":{\"type\":\"integer\",\"detail\":\"Integer · column stock\"},"
                + "\"level\":{\"type\":\"string\",\"enum\":[\"LOW\",\"HIGH\"],\"detail\":\"Level · column level\"},"
                + "\"due\":{\"type\":\"string\",\"format\":\"date\",\"detail\":\"LocalDate · column due\"},"
                + "\"price\":{\"type\":\"number\",\"detail\":\"BigDecimal · column price\"}}",
                Json.write(((Map<?, ?>) targets(entity("Gizmo", Gizmo.class, "gizmos")).get("Gizmo")).get("attributes")),
                "a model Mansart builds itself");
    }

    @Test
    void aReferenceNamesItsTargetAndAJoinedAttributeIsLeftOut() {
        assertEquals("{\"id\":{\"type\":\"integer\",\"detail\":\"Long · id, generated\"},"
                + "\"gizmo\":{\"type\":\"integer\",\"detail\":\"→ Gizmo · column gizmo_id\",\"target\":\"Gizmo\"},"
                + "\"label\":{\"type\":\"string\",\"detail\":\"String · column label\"}}",
                Json.write(((Map<?, ?>) targets(entity("Part", Part.class, "parts")).get("Part")).get("attributes")),
                "the gizmo's name, joined, is no attribute of the language");
    }

    @Test
    void anAttributeOfATypeWithNoJsonSchemaIsKeptWithoutAType() {
        assertEquals("{\"detail\":\"Year · column year\"}", Json.write(((Map<?, ?>) ((Map<?, ?>) targets(
                entity("Slot", Slot.class, "slots")).get("Slot")).get("attributes")).get("year")));
    }
}
```

In `MDT/CatalogueLivePanelTest.java`, replace:
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

In `MDT/CatalogueLivePanelTest.java`, replace:
```java
    @Test
    void stopDropsTheActionsAndTheirCalls() {
```
with:
```java
    @Test
    void theJdqlTabsLanguageIsBuiltFromTheCatalogueOnceStarted() {
        assertEquals(List.of(), panel.languages(), "before start, as the actions");
        assertEquals(List.of(), panel.languages(RunFixtures::model), "no catalogue yet");
        publishRunFixtures();

        List<PanelLanguage> languages = panel.languages(RunFixtures::model);

        assertEquals(List.of("jdql"), languages.stream().map(PanelLanguage::id).toList());
        Map<?, ?> targets = (Map<?, ?>) ((Map<?, ?>) Json.parse(languages.getFirst().json())).get("targets");
        assertEquals(List.of("Gizmo", "Part"), List.copyOf(targets.keySet()), "the catalogue's entities");
        String none = panel.languages(type -> {
            throw new IllegalStateException("no model");
        }).getFirst().json();
        assertEquals(Map.of(), ((Map<?, ?>) Json.parse(none)).get("targets"), "no model read, no target");
    }

    @Test
    void stopDropsTheActionsAndTheirCalls() {
```

In `MDT/JdqlActionsTest.java`, replace:
```java
        assertTrue(schema.contains("\"query\":{\"type\":\"string\",\"format\":\"textarea\""), schema);
        assertTrue(schema.contains("\"params\":{\"type\":\"string\",\"format\":\"textarea\""), schema);
```
with:
```java
        assertTrue(schema.contains("\"query\":{\"type\":\"string\",\"format\":\"textarea\","
                + "\"contentMediaType\":\"text/x-query\",\"x-language\":\"jdql\",\"description\":\"FROM Product "
                + "WHERE price > :min ORDER BY name\"}"), "the query editor, with the panel's jdql: " + schema);
        assertTrue(schema.contains("\"params\":{\"type\":\"string\",\"format\":\"textarea\","
                + "\"x-parameters-of\":\"query\",\"description\":"), "the JSON editor of its parameters: " + schema);
```

In `MDT/CsvActionsTest.java`, replace:
```java
        assertTrue(schema.contains("\"query\":{\"type\":\"string\",\"format\":\"textarea\""), schema);
        assertTrue(schema.contains("\"separator\":{\"type\":\"string\",\"enum\":[\",\",\";\"],\"default\":\",\""),
```
with:
```java
        assertTrue(schema.contains("\"query\":{\"type\":\"string\",\"format\":\"textarea\","
                + "\"contentMediaType\":\"text/x-query\",\"x-language\":\"jdql\",\"description\":"), schema);
        assertTrue(schema.contains("\"params\":{\"type\":\"string\",\"format\":\"textarea\","
                + "\"x-parameters-of\":\"query\","), schema);
        assertTrue(schema.contains("\"separator\":{\"type\":\"string\",\"enum\":[\",\",\";\"],\"default\":\",\""),
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -q -pl vidocq-runtime-devconsole-spi,vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev test -Dtest=JdqlLanguageTest,CatalogueLivePanelTest,JdqlActionsTest,CsvActionsTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "ERROR.*(cannot find symbol|symbol:)|BUILD" | head -6
```
Expected: FAIL at `testCompile` — `cannot find symbol`, among them `symbol:   variable JdqlLanguage`.

- [ ] **Step 3: Build the language, offer it, and name it from the schemas**

Create `MDM/JdqlLanguage.java`:

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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.mansart.data.dialect.Attribute;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.attribute.IdAttribute;
import io.vidocq.mansart.data.dialect.attribute.JoinedAttribute;
import io.vidocq.mansart.data.dialect.attribute.ReferenceAttribute;
import io.vidocq.mansart.data.dialect.attribute.VersionAttribute;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The {@value #ID} language of the Mansart Data panel (query mode spec §2.4), which the page's query editor fetches
 * once per boot: the dialect of JDQL as Mansart's parser reads it, and one target per entity of the catalogue whose
 * model could be read, under the name {@code FROM} takes, its simple name, with every attribute of its model but the
 * joined ones. An attribute's type is its Java type's JSON Schema type ({@link Scalars#schema}), a reference's the
 * type of the id of the entity it refers to, which it names as its {@code target}. Built from memory: no bean, no
 * query.
 */
final class JdqlLanguage {

    /** The language's id, which the query property of the tab's actions names in {@code x-language}. */
    static final String ID = "jdql";
    /** The media type of a property that is a query: the page shows it in its query editor. */
    static final String MEDIA_TYPE = "text/x-query";
    /** The words Mansart's JDQL reads as keywords, and its literals. */
    static final List<String> KEYWORDS = List.of("SELECT", "FROM", "WHERE", "ORDER", "BY", "AND", "OR", "NOT", "IS",
            "NULL", "BETWEEN", "LIKE", "IN", "ASC", "DESC", "UPDATE", "SET", "DELETE", "COUNT", "THIS", "SUM", "AVG",
            "MIN", "MAX", "TRUE", "FALSE");
    /** Its scalar functions and aggregates, each followed by {@code (}. */
    static final List<String> FUNCTIONS = List.of("UPPER", "LOWER", "LENGTH", "ABS", "CONCAT", "COUNT", "SUM", "AVG",
            "MIN", "MAX");
    /** The words that start a clause. */
    static final List<String> CLAUSES = List.of("SELECT", "FROM", "WHERE", "ORDER BY", "SET", "UPDATE", "DELETE FROM");
    /** The words after which the entity is named. */
    static final List<String> TARGET_AFTER = List.of("FROM", "UPDATE");

    private JdqlLanguage() {}

    /**
     * The language's JSON, {@code {"mode": "query", "dialect": {…}, "targets": {…}}}, its targets in name order. An
     * entity whose model could not be read, whose class does not load, or whose simple name another entity shares
     * (JDQL names an entity by its simple name, and the tab refuses an ambiguous one) is left out, as is an
     * attribute Mansart cannot describe.
     *
     * @param entities the catalogue's entities
     * @param classes  the class of an entity by its full name, as the application loads it
     * @param models   the model of an entity class, {@code EntityModels.of} outside tests
     */
    static String json(List<MansartDataCatalogue.Entity> entities, Function<String, Class<?>> classes,
                       Function<Class<?>, EntityModel<?>> models) {
        Map<String, Integer> uses = new HashMap<>();
        for (MansartDataCatalogue.Entity entity : entities) {
            uses.merge(simpleName(entity.className()), 1, Integer::sum);
        }
        Map<String, Object> targets = new LinkedHashMap<>();
        for (MansartDataCatalogue.Entity entity : entities.stream()
                .sorted(Comparator.comparing(MansartDataCatalogue.Entity::name)).toList()) {
            String name = simpleName(entity.className());
            EntityModel<?> model = entity.failure() != null || uses.get(name) > 1 ? null
                    : model(entity.className(), classes, models);
            if (model != null) {
                targets.put(name, Scalars.object("detail", "table " + entity.table(),
                        "attributes", attributes(model, models)));
            }
        }
        return Json.write(Scalars.object("mode", "query",
                "dialect", Scalars.object("keywords", KEYWORDS, "functions", FUNCTIONS, "clauses", CLAUSES,
                        "targetAfter", TARGET_AFTER, "self", "this", "quote", "'"),
                "targets", targets));
    }

    /** The model of the entity {@code className}, or {@code null} when its class or its model cannot be had. */
    private static EntityModel<?> model(String className, Function<String, Class<?>> classes,
                                        Function<Class<?>, EntityModel<?>> models) {
        try {
            return models.apply(classes.apply(className));
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    /** One attribute per attribute of {@code model} but the joined ones, in model order. */
    private static Map<String, Object> attributes(EntityModel<?> model, Function<Class<?>, EntityModel<?>> models) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Attribute<?, ?> attribute : model.attributes()) {
            if (attribute == null || attribute instanceof JoinedAttribute<?, ?>) {
                continue;
            }
            try {
                out.put(attribute.name(), attribute(attribute, models));
            } catch (RuntimeException | LinkageError unreadable) {
                // an attribute Mansart cannot describe is left out, as a joined one is
            }
        }
        return out;
    }

    /**
     * An attribute: its {@code type}, {@code format} and {@code enum} when its Java type has a JSON Schema, then its
     * {@code detail}, {@code <Java type> · <key or column>}, and for a reference its {@code target}.
     */
    private static Map<String, Object> attribute(Attribute<?, ?> attribute,
                                                 Function<Class<?>, EntityModel<?>> models) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (attribute instanceof ReferenceAttribute<?, ?> reference) {
            String target = simpleName(reference.javaType().getName());
            typeOf(idType(reference.javaType(), models), out);
            out.put("detail", "→ " + target + " · column " + attribute.columnName());
            out.put("target", target);
            return out;
        }
        Class<?> type = attribute.javaType();
        typeOf(type, out);
        String where = switch (attribute) {
            case IdAttribute<?, ?> id -> id.generated() ? "id, generated" : "id";
            case VersionAttribute<?, ?> _ -> "version";
            default -> "column " + attribute.columnName();
        };
        out.put("detail", (type == null ? "?" : type.getSimpleName().isEmpty() ? type.getName()
                : type.getSimpleName()) + " · " + where);
        return out;
    }

    /** Puts the {@code type}, {@code format} and {@code enum} of {@code type}'s JSON Schema into {@code out}. */
    private static void typeOf(Class<?> type, Map<String, Object> out) {
        Map<String, Object> schema = type == null ? null : Scalars.schema(type);
        if (schema == null) {
            return;
        }
        for (String keyword : List.of("type", "format", "enum")) {
            if (schema.containsKey(keyword)) {
                out.put(keyword, schema.get(keyword));
            }
        }
    }

    /** The type of the id of {@code entity}, or {@code null} when its model cannot be read. */
    private static Class<?> idType(Class<?> entity, Function<Class<?>, EntityModel<?>> models) {
        try {
            EntityModel<?> model = models.apply(entity);
            return model == null || model.id() == null ? null : model.id().javaType();
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    /** {@code io.x.Outer$Gizmo} → {@code Gizmo}: the name a statement uses. */
    private static String simpleName(String className) {
        String name = className.substring(className.lastIndexOf('.') + 1);
        return name.substring(name.lastIndexOf('$') + 1);
    }
}
```

In `MDM/CatalogueLivePanel.java`, replace:
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

In `MDM/CatalogueLivePanel.java`, replace:
```java
 * exports a query as CSV and imports a CSV file (see {@link CsvActions}, on {@code RepositoryRuntime.save}): built once
 * per boot by {@link #actions()}, which the console calls after {@link #start}, from the repository interfaces
 * {@link MansartDataLive} holds and the {@link BeanManager} {@code start} keeps; dropped by {@link #stop}.
```
with:
```java
 * exports a query as CSV and imports a CSV file (see {@link CsvActions}, on {@code RepositoryRuntime.save}): built once
 * per boot by {@link #actions()}, which the console calls after {@link #start}, from the repository interfaces
 * {@link MansartDataLive} holds and the {@link BeanManager} {@code start} keeps; dropped by {@link #stop}. Its
 * {@link #languages()} gives that tab's query editors the {@code jdql} language (see {@link JdqlLanguage}).
```

In `MDM/CatalogueLivePanel.java`, replace:
```java
    @Override
    public void sample(PanelSample sample) {
        Optional<MansartDataCatalogue> published = MansartDataLive.catalogue();
```
with:
```java
    /**
     * The {@value JdqlLanguage#ID} language the JDQL tab's query editors complete and check with, built now from the
     * catalogue and the models; none before {@link #start}, as there is no action then either.
     */
    @Override
    public List<PanelLanguage> languages() {
        return beans == null ? List.of() : languages(type -> EntityModels.of(type));
    }

    /** The JDQL language of what {@link MansartDataLive} holds; none without an entity, or when it cannot be built. */
    List<PanelLanguage> languages(Function<Class<?>, EntityModel<?>> models) {
        Optional<MansartDataCatalogue> catalogue = MansartDataLive.catalogue();
        List<Class<?>> repositories = MansartDataLive.repositories();
        if (catalogue.isEmpty() || repositories.isEmpty() || catalogue.get().entities().isEmpty()) {
            return List.of();
        }
        try {
            return List.of(new PanelLanguage(JdqlLanguage.ID, JdqlLanguage.json(catalogue.get().entities(),
                    className -> RepositoryActions.load(className, repositories), models)));
        } catch (RuntimeException | LinkageError failed) {
            LOG.log(System.Logger.Level.DEBUG, "Mansart Data: no JDQL language: " + failed.getClass().getName());
            return List.of();
        }
    }

    @Override
    public void sample(PanelSample sample) {
        Optional<MansartDataCatalogue> published = MansartDataLive.catalogue();
```

In `MDM/JdqlActions.java`, replace:
```java
    /** The JSON Schema of {@value #STATEMENT}: {@code query} and {@code params}, each a field of several lines. */
    static String schema() {
        return Json.write(Scalars.object("type", "object", "properties", Scalars.object(
                        QUERY_MEMBER, Scalars.object("type", "string", "format", "textarea",
                                "description", "FROM Product WHERE price > :min ORDER BY name"),
                        PARAMS_MEMBER, Scalars.object("type", "string", "format", "textarea",
                                "description", "the named parameters, a JSON object: {\"min\": 3}")),
                "required", List.of(QUERY_MEMBER)));
    }
```
with:
```java
    /**
     * The JSON Schema of {@value #STATEMENT}: {@code query}, which the page shows in its query editor with the
     * {@value JdqlLanguage#ID} language, and {@code params}, in its JSON editor whose schema follows the query.
     */
    static String schema() {
        return Json.write(Scalars.object("type", "object", "properties", Scalars.object(
                        QUERY_MEMBER, query("FROM Product WHERE price > :min ORDER BY name"),
                        PARAMS_MEMBER, params()),
                "required", List.of(QUERY_MEMBER)));
    }

    /** A query property of the tab's actions: a text of several lines, JDQL, its example as {@code description}. */
    static Map<String, Object> query(String example) {
        return Scalars.object("type", "string", "format", "textarea", "contentMediaType", JdqlLanguage.MEDIA_TYPE,
                "x-language", JdqlLanguage.ID, "description", example);
    }

    /** The parameters property of the tab's actions: the text of a JSON object, of the named parameters of query. */
    static Map<String, Object> params() {
        return Scalars.object("type", "string", "format", "textarea", "x-parameters-of", QUERY_MEMBER,
                "description", "the named parameters, a JSON object: {\"min\": 3}");
    }
```

In `MDM/CsvActions.java`, replace:
```java
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
```
with:
```java
    /**
     * The JSON Schema of an export's {@value #STATEMENT}: the query, in the page's query editor as the tab's queries
     * are, its parameters, in the JSON editor that follows it, and the separator.
     */
    static String exportSchema() {
        return Json.write(Scalars.object("type", "object", "properties", Scalars.object(
                        QUERY_MEMBER, JdqlActions.query("FROM Product ORDER BY id, or SELECT name, price FROM Product"),
                        PARAMS_MEMBER, JdqlActions.params(),
                        SEPARATOR_MEMBER, separatorSchema()),
                "required", List.of(QUERY_MEMBER)));
    }
```

- [ ] **Step 4: Run the module's tests to verify they pass**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -pl vidocq-runtime-devconsole-spi,vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev test 2>&1 | grep -E "Tests run:.*Fail|FAIL|BUILD" | grep -v " in io" | tail -4
```
(`timeout: 600000`.) Expected: the SPI's 67 and the module's 165 tests, `Failures: 0, Errors: 0`, `BUILD SUCCESS`
(`JdqlLanguageTest` 5, `CatalogueLivePanelTest` 14).

- [ ] **Step 5: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(mansart-data): the JDQL tab's query editor knows the entities and their attributes

The Mansart Data panel offers the jdql language: the words of Mansart's JDQL, and one target per entity of the
catalogue whose model could be read, under its simple name, with its table and every attribute but the joined ones —
their JSON type, a detail such as Long · id, generated or → Project · column project_id, and a reference's target.
The query of Query, Update / Delete and Export CSV is a text/x-query of x-language jdql, and their params follow it
with x-parameters-of.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/JdqlLanguage.java vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/CatalogueLivePanel.java vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/JdqlActions.java vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/CsvActions.java vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/JdqlLanguageTest.java vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/CatalogueLivePanelTest.java vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/JdqlActionsTest.java vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/CsvActionsTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 11: Documentation (§6)

**Files:**
- Modify: `DOCS/dev-console.adoc` (the query editor sections after the generated form; a Security bullet)
- Modify: `DOCS/dev-console-panels.adoc` (`#json-argument`: the JDQL example and its query properties)
- Modify: `DOCS/modules/vidocq-runtime-extensions.adoc` (`#mansart-data-jdql`: the editors, the `jdql` language)
- Modify: `DOCS/whats-new.adoc` (one entry)
- Modify: `SPIM/PanelAction.java` (the Javadoc of `Argument.json`: the keywords the page reads)

**Interfaces:**
- Consumes: every name above: `PanelLanguage`, `languages()`, `GET /api/language/<panel>/<id>`, the snapshot's
  `languages`, `text/x-query`, `x-language`, `x-parameters-of`, the messages of Rulings 6 and 13.
- Produces: the anchors `#query-editor`, `#query-parameters`, `#query-language` of `dev-console.adoc`.

- [ ] **Step 1: The query editor, its parameters, and a query language for a panel**

In `DOCS/dev-console.adoc`, replace (the end of the generated form's list, which runs into `[#security]`):
```adoc
* The editor starts from the required properties, at each level. *Replay* fills the form, a nested object included;
a value the panel masked is left for you to type again.
[#security]
```
with:
```adoc
* The editor starts from the required properties, at each level. *Replay* fills the form, a nested object included;
a value the panel masked is left for you to type again.

[#query-editor]
== Query editor [.tag-new]#NEW#

A query argument — the `query` of the *JDQL* tab of *Mansart Data*, in *Query*, *Update / Delete* and *Export CSV*
(xref:modules/vidocq-runtime-extensions.adoc#mansart-data-jdql[A JDQL console for Mansart Data]) — is typed in the
same editor, in its query mode, five lines high, with the vocabulary its panel publishes: the entities and their
attributes. The page fetches that vocabulary once, when the first form that needs it is drawn, and again after a dev
reload only; until it arrives, or when it cannot be had, the editor works with the keywords alone and says why under
it, `no vocabulary: 404`. It arrives without moving the text or the caret.

* **Colours.** Keywords, functions, the entity, its attributes, strings, numbers and parameters (`:name`, `?1`) each
have a colour of their own, in the light and the dark themes; a name the vocabulary does not know is plain.
* **Completion.** `Ctrl+Space` lists what fits where the caret is: after `FROM` or `UPDATE`, the entities, each with
its table; after `project.`, the attributes of the entity the reference leads to, one step at a time however long
the path; in a `SELECT`, `WHERE`, `ORDER BY` or `SET` clause, the attributes of the query's entity, even when `FROM`
comes after the caret, each with its Java type and its column, then `this`, the functions, inserted with `(` and the
caret inside, and the other keywords; anywhere else, the keywords. Typing filters the list, ignoring case; a keyword
is inserted in capitals.
* **Diagnostics.** An unknown entity, an unknown attribute of the entity or of the one a reference leads to, a path
through an attribute that is no reference (`title is not a reference`), an unterminated string, and a parenthesis
never closed or closing none are errors, underlined as the JSON editor's. Nothing else: the grammar is the server's
to judge, and it says what is wrong when the query runs. An entity written by its full class name is not checked.
* **Keys.** `(` and `'` come in pairs; a `'` typed right before a closing one steps over it, and a doubled `''` inside
a string is one quote. The other keys are the JSON editor's.
* **Format.** *Format*, or `Shift+Alt+F`, writes the keywords in capitals, starts each clause on a line of its own and
`AND` or `OR` on an indented line; the `AND` of a `BETWEEN` stays where it is, and names, strings, numbers and
parameters are kept as written. A query with an unterminated string is not formatted: `not formatted: line 2:
unterminated string`.

[#query-parameters]
=== Its parameters [.tag-new]#NEW#

The `params` of a query is a JSON editor whose schema follows the query as you type it: one key per named parameter,
in order of first use, all required, and no other. A parameter compared with an attribute takes its type, its format
and its values: after `WHERE status = :status`, completion offers `"status"` with the values of its enum, and the
editor flags the key while it is missing. `LIKE :pattern` takes a string, `BETWEEN :low AND :high` the attribute's
type for both, `IN :statuses` a list of it, `SET title = :title` the attribute's type; the description of each says
where it is used, such as `compared with price (number)`. A parameter used twice keeps its first typed use; one used
otherwise, such as `price * :factor`, takes any value; a positional one, `?1`, has no key. Nothing is refused before
the call. Its text is sent as typed; empty, it holds `{}`.

[#query-language]
=== A query language for a panel [.tag-new]#NEW#

A panel gives a `json` argument's property this mode in two steps.

. Its `languages()`, on `DevConsolePanel` or `LivePanel`, returns the `PanelLanguage` records it offers: an id, which
follows the key rule of `PanelSample`, such as `jdql`, and the text of one JSON object, at most 1 MiB and 64 levels
deep, checked when the record is built. It is read once per boot, with `actions()`, in a `dev` launch only. Two
languages of one id leave the panel without languages, and the console logs a WARNING saying so; a `languages()`
that throws leaves it without languages too. The snapshot names each panel's ids, `"languages": ["jdql"]`, never
their content, which `GET /api/language/<panel>/<id>` serves as the panel wrote it: `GET` or `HEAD`, a `dev` launch
only (`404` otherwise), no token since it only reads, an `Origin`, when there is one, that is the console's own
(`403`), and never logged.
. The schema of the argument names it. A string property of `"format": "textarea"` whose schema also says
`"contentMediaType": "text/x-query"` and `"x-language": "<id>"` is the query editor; a string property of
`"format": "textarea"` and `"x-parameters-of": "<query property>"`, beside the query, is the JSON editor of its
parameters. Both are sent as strings, as typed.

[source,json]
----
{ "mode": "query",
  "dialect": {
    "keywords": ["SELECT", "FROM", "WHERE", "ORDER", "BY", "AND", "OR", "NOT", "IS", "NULL", "BETWEEN", "LIKE",
                 "IN", "ASC", "DESC", "UPDATE", "SET", "DELETE", "COUNT", "THIS", "SUM", "AVG", "MIN", "MAX",
                 "TRUE", "FALSE"],
    "functions": ["UPPER", "LOWER", "LENGTH", "ABS", "CONCAT", "COUNT", "SUM", "AVG", "MIN", "MAX"],
    "clauses": ["SELECT", "FROM", "WHERE", "ORDER BY", "SET", "UPDATE", "DELETE FROM"],
    "targetAfter": ["FROM", "UPDATE"],
    "self": "this",
    "quote": "'" },
  "targets": {
    "Task": { "detail": "table task",
      "attributes": {
        "id": { "type": "integer", "detail": "Long · id, generated" },
        "title": { "type": "string", "detail": "String · column title" },
        "dueDate": { "type": "string", "format": "date", "detail": "LocalDate · column due_date" },
        "project": { "type": "integer", "detail": "→ Project · column project_id", "target": "Project" } } } } }
----

`mode` is `query`. `keywords` are matched ignoring case and written in capitals; `functions` are the names that may be
followed by `(`; `clauses` start a clause; `targetAfter` are the words after which the entity is named; `self` is the
entity's own name in an expression; `quote` delimits a string, doubled inside it. A word the dialect leaves out takes
JDQL's. `targets` maps each entity's name to its `detail` and `attributes`; an attribute has a JSON Schema `type`
(`string`, `integer`, `number`, `boolean`), optionally a `format` and an `enum`, a `detail`, and a `target` when it
refers to another entity of the language. Data the editor does not understand makes it know less, never fail.

[#security]
```

In `DOCS/dev-console.adoc`, replace:
```adoc
* **A read-only dev MCP in a `dev` launch** [.tag-new]#NEW#. `POST /mcp` serves the page's facts to a coding agent as MCP tools that change nothing, behind the checks of <<dev-mcp>>: no token, since nothing it answers is not already on the page, but an `Origin`, when there is one, that is the console's own.
```
with:
```adoc
* **A read-only dev MCP in a `dev` launch** [.tag-new]#NEW#. `POST /mcp` serves the page's facts to a coding agent as MCP tools that change nothing, behind the checks of <<dev-mcp>>: no token, since nothing it answers is not already on the page, but an `Origin`, when there is one, that is the console's own.
* **Query languages, read-only, in a `dev` launch** [.tag-new]#NEW#. `GET /api/language/<panel>/<id>` serves the vocabulary of a panel's query editor, its entities and attributes, behind the checks of <<query-language>>: `GET` or `HEAD`, no token, an `Origin`, when there is one, that is the console's own; outside `dev`, `404`.
```

- [ ] **Step 2: The panels guide, the Mansart Data page, What's new and the Javadoc**

In `DOCS/dev-console-panels.adoc`, replace:
```adoc
A string property whose schema also says `"format": "textarea"` [.tag-new]#NEW# is a field of several lines in that
form rather than a one-line one: four lines of monospace text to start with, taller when dragged, its value sent as
any other string's. The Mansart Data panel's *JDQL* tab uses it for its statement and its parameters
(xref:modules/vidocq-runtime-extensions.adoc#mansart-data-jdql[A JDQL console for Mansart Data]):

[source,json]
----
{"type": "object",
 "properties": {"query": {"type": "string", "format": "textarea"},
                "params": {"type": "string", "format": "textarea"}},
 "required": ["query"]}
----
```
with:
```adoc
A string property whose schema also says `"format": "textarea"` [.tag-new]#NEW# is a field of several lines in that
form rather than a one-line one: four lines of monospace text to start with, taller when dragged, its value sent as
any other string's.

Such a field whose schema also says `"contentMediaType": "text/x-query"` and `"x-language": "<id>"` [.tag-new]#NEW#
is the query editor, which colours, completes and checks a query with the vocabulary of the panel's language of that
id, and one that says `"x-parameters-of": "<query property>"` [.tag-new]#NEW# is the JSON editor of that query's
parameters, its schema following the query (xref:dev-console.adoc#query-language[A query language for a panel]).
The Mansart Data panel's *JDQL* tab uses both for its statement and its parameters
(xref:modules/vidocq-runtime-extensions.adoc#mansart-data-jdql[A JDQL console for Mansart Data]):

[source,json]
----
{"type": "object",
 "properties": {"query": {"type": "string", "format": "textarea", "contentMediaType": "text/x-query",
                          "x-language": "jdql"},
                "params": {"type": "string", "format": "textarea", "x-parameters-of": "query"}},
 "required": ["query"]}
----
```

In `DOCS/modules/vidocq-runtime-extensions.adoc`, replace:
```adoc
The form has two fields of several lines: `query`, the statement, and `params`, its named parameters as a JSON object.
```
with:
```adoc
The form has two fields of several lines: `query`, the statement, and `params`, its named parameters as a JSON object. The statement is typed in the dev console's query editor [.tag-new]#NEW#, which colours it, completes the entities after `FROM` and their attributes elsewhere, and underlines an unknown entity or attribute before anything runs (xref:dev-console.adoc#query-editor[Query editor]); `params` in a JSON editor whose schema follows the statement [.tag-new]#NEW#: after `WHERE status = :status`, it offers `status` with the values of its enum, and flags it while it is missing (xref:dev-console.adoc#query-parameters[Its parameters]).
```

In `DOCS/modules/vidocq-runtime-extensions.adoc`, replace:
```adoc
*The entity.* A statement names its entity after `FROM`, `UPDATE` or `DELETE FROM`, by its simple name, or by its full class name when two entities share a simple name, as the catalogue then shows them. A name the catalogue does not know is refused, with the names it knows: `unknown entity Nope; entities: Task, TaskEvent`.
```
with:
```adoc
*The entity.* A statement names its entity after `FROM`, `UPDATE` or `DELETE FROM`, by its simple name, or by its full class name when two entities share a simple name, as the catalogue then shows them. A name the catalogue does not know is refused, with the names it knows: `unknown entity Nope; entities: Task, TaskEvent`.

*The `jdql` language* [.tag-new]#NEW#. What the query editor knows comes from the panel, built once per boot from the catalogue and Mansart's models (xref:dev-console.adoc#query-language[A query language for a panel]): the words of Mansart's JDQL, then one entity per entity of the catalogue whose model could be read, under its simple name, with its table, and every attribute of its model but the joined ones, with the JSON type of its Java type and a detail such as `Long · id, generated`, `String · column title` or `→ Project · column project_id`; a reference leads to its entity, so that `project.` completes a project's attributes. An entity whose simple name another shares, which the tab refuses as ambiguous, is left out. *Export CSV*'s statement is typed in the same editor.
```

In `DOCS/whats-new.adoc`, replace:
```adoc
* **Dev service containers are named `vidocq-dev-…`** [.tag-new]#NEW# — the PostgreSQL
```
with:
```adoc
* **A query editor for JDQL in the dev console** [.tag-new]#NEW# — the statement of the *Mansart Data* panel's *JDQL* tab, in *Query*, *Update / Delete* and *Export CSV*, is typed in the dev console's code editor in a new query mode: keywords, functions, the entity, its attributes, strings, numbers and parameters each coloured; `Ctrl+Space` completes the entities after `FROM`, their attributes with their Java type and column in `SELECT`, `WHERE`, `ORDER BY` and `SET` — `FROM` written after the caret included — and a reference's attributes after `project.`; an unknown entity or attribute, a path through an attribute that is no reference, an unterminated string or an unbalanced parenthesis is underlined before anything runs; *Format* puts each clause on its line. Its `params` become a JSON editor whose schema follows the statement as you type: `WHERE status = :status` offers `status` with the values of its enum and flags it missing. A panel publishes such a vocabulary with `DevConsolePanel.languages()` (or `LivePanel`'s), `PanelLanguage` records the page fetches once at `GET /api/language/<panel>/<id>` in a `dev` launch, and names it from a `json` argument's schema with `"contentMediaType": "text/x-query"`, `"x-language"` and `"x-parameters-of"`. xref:dev-console.adoc#query-editor[Query editor], xref:modules/vidocq-runtime-extensions.adoc#mansart-data-jdql[A JDQL console for Mansart Data].
* **Dev service containers are named `vidocq-dev-…`** [.tag-new]#NEW# — the PostgreSQL
```

In `SPIM/PanelAction.java`, replace:
```java
         * {@code contentMediaType} ({@code text/csv}), {@code readOnly}, {@code description}, {@code default}, and a
         * local {@code $ref} ({@code #/$defs/...}, {@code #/definitions/...}). It generates a form when the root is an
         * object whose properties are scalars, enums of strings, or objects of those one level down, and offers its
         * JSON editor otherwise, which checks and completes the value against the schema; neither refuses to send
```
with:
```java
         * {@code contentMediaType} ({@code text/csv}; {@code text/x-query} for a query, with {@code x-language}, the
         * id of one of the panel's {@link DevConsolePanel#languages() languages}), {@code x-parameters-of} (the
         * query property whose parameters a property holds), {@code readOnly}, {@code description}, {@code default},
         * and a local {@code $ref} ({@code #/$defs/...}, {@code #/definitions/...}). It generates a form when the root
         * is an object whose properties are scalars, enums of strings, or objects of those one level down, and offers
         * its JSON editor otherwise, which checks and completes the value against the schema; neither refuses to send
```

- [ ] **Step 3: Check the anchors, the badges and the SPI**

```bash
cd $VIDOCQ && D=docs/en/modules/ROOT/pages && grep -cE '^\[#(query-editor|query-parameters|query-language)\]$' $D/dev-console.adoc && grep -c '^\[#mansart-data-jdql\]$' $D/modules/vidocq-runtime-extensions.adoc && grep -c 'A query editor for JDQL in the dev console\*\* \[.tag-new\]#NEW#' $D/whats-new.adoc
```
Expected: `3`, `1`, `1`.
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -q -pl vidocq-runtime-devconsole-spi test 2>&1 | grep -E "ERROR|BUILD" | head -3
```
Expected: nothing (the Javadoc change compiles; the SPI's tests pass).

- [ ] **Step 4: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
docs(devconsole): the query editor, its parameters, a panel's language, and JDQL's

dev-console.adoc gets a query editor section (colours, completion, diagnostics, keys, Format, the vocabulary fetched
once), its parameters, and what a panel's author writes: languages(), PanelLanguage, the language's JSON,
contentMediaType text/x-query, x-language, x-parameters-of, GET /api/language and its checks, with a Security
bullet. The panels guide shows the JDQL tab's schema as it now is; the Mansart Data page says what the jdql language
holds; PanelAction.Argument.json's Javadoc names the new keywords; What's new has the entry.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add docs/en/modules/ROOT/pages/dev-console.adoc docs/en/modules/ROOT/pages/dev-console-panels.adoc docs/en/modules/ROOT/pages/modules/vidocq-runtime-extensions.adoc docs/en/modules/ROOT/pages/whats-new.adoc vidocq-runtime-devconsole-spi/src/main/java/io/vidocq/runtime/spi/devconsole/PanelAction.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 12: Verification — builds, a consumer, and the query editor in Chrome (§5)

**Files:** none changed, unless a check fails (then fix in the owning task's files, re-run its tests, and commit with a
`fix(devconsole): …` or `fix(mansart-data): …` message following the Global Constraints).

**Interfaces:**
- Consumes: everything above; the test application `$LC4JCDI/mcp-tasks-server` (PostgreSQL through the Vidocq dev
  service: Docker must run; entities `Task` — `title`, `description`, `project` (a `String`), `status` and `priority`
  (enums), `dueDate`, `createdAt`, `updatedAt`, `completedAt` — and `TaskEvent`); the helper `$SCRATCH/dev-run.sh`
  (`dev-run.sh <project dir> <log>`: `vidocq:dev` of that project, the application on 18093, the console on 18094, no
  debugger, no continuous testing).
- Produces: nothing; a report.

- [ ] **Step 1: The three modules' whole builds, installed for the test application**

```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu install -pl vidocq-runtime-devconsole-spi,vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension,vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev 2>&1 | grep -E "Tests run:.*Fail|FAIL|BUILD" | grep -v " in io" | tail -5
```
(`timeout: 600000`.) Expected: `Tests run: 67`, `Tests run: 470`, `Tests run: 165`, each `Failures: 0, Errors: 0`, and
`BUILD SUCCESS`.

- [ ] **Step 2: A consumer**

```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu clean verify -pl vidocq-runtime-examples/vidocq-runtime-mansart-h2-example 2>&1 | grep -E "Tests run:|FAIL|BUILD" | tail -6
```
(`timeout: 600000`.) Expected: `BUILD SUCCESS`, no failure (its `DevConsoleSnapshotTest` reads the console and the
Mansart Data panel).

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
Then `grep -E "Dev tools:|MANSART-DATA|BUILD FAILURE" <that log> | head` — expected: the console and
`vidocq-runtime-mansart-data-extension-dev` among the dev tools, no failure.

- [ ] **Step 5: The query editor in Chrome (§5) — a manual check, no code**

Load the tools in one call: `ToolSearch("select:mcp__claude-in-chrome__tabs_context_mcp,mcp__claude-in-chrome__navigate,mcp__claude-in-chrome__computer,mcp__claude-in-chrome__read_page,mcp__claude-in-chrome__tabs_create_mcp,mcp__claude-in-chrome__tabs_close_mcp,mcp__claude-in-chrome__javascript_tool,mcp__claude-in-chrome__find,mcp__claude-in-chrome__form_input,mcp__claude-in-chrome__read_console_messages,mcp__claude-in-chrome__read_network_requests")`.
Remember: the editor draws in an animation frame, which a hidden MCP tab only runs when a screenshot is taken; take a
screenshot or a zoom before reading what it drew. On macOS the undo key is `cmd+z`. Tick each item, and screenshot
the ones marked (S).

1. `tabs_context_mcp`, `tabs_create_mcp`, `navigate` to `http://127.0.0.1:18094/`. Run with `javascript_tool`:
   `Object.defineProperty(document, 'hidden', {value: false, configurable: true}); Object.defineProperty(document, 'visibilityState', {value: 'visible', configurable: true}); document.dispatchEvent(new Event('visibilitychange')); 'visible'`.
   `read_console_messages` with pattern `error|Error`: nothing from `console.js`, `editor.js` or `editor-core.js`.
2. *Mansart Data* → the *JDQL* tab → *Query*. `query` is the code editor, five lines, its placeholder
   `FROM Product WHERE price > :min ORDER BY name`; `params` is a second editor holding `{}`; nothing under either.
   `read_network_requests` with pattern `api/language`: one request, `/api/language/mansart-data/jdql`, `200`.
3. In `query`, type `select title from Task where status = :status and dueDate < :day order by dueDate`: keywords,
   the target `Task`, the attributes, the parameters each in their colour, numbers and strings as JSON's. (S)
4. Clear it; type `FROM T` and press `ctrl+space`: the list offers `Task` and `TaskEvent`, each with `table …`;
   `Enter` inserts `Task`. Type ` WHERE ` and `ctrl+space`: `id` (`Long · id, generated`), `title`
   (`String · column title`), … `status` (`TaskStatus · column status`), then `this`, the functions, the keywords. (S)
5. Put the caret right after `SELECT ` in `SELECT  FROM Task` and press `ctrl+space`: the attributes of `Task` (the
   target is after the caret).
6. Type `FROM Task WHERE project.`: no list; the dot is underlined red and its tooltip reads
   `project is not a reference` (`project` is a `String` here: a reference path is covered by `QueryLanguageTest`).
7. `FROM Task WHERE titel = 1`: `titel` underlined, tooltip `unknown attribute titel of Task`, `1 error` under the
   editor; `FROM Tsk`: `unknown target Tsk`.
8. `FROM Task WHERE status = :status`: `params` says `1 error` (`missing required key "status"` on its `{`); put the
   caret inside `{}` and press `ctrl+space`: `"status": "OPEN"` (the first value of the enum), its detail
   `enum, required — compared with status (string)`; accept: the error is gone. Change the value to `"NOPE"`:
   `not one of "OPEN", …`. (S)
9. Add ` AND dueDate < :day` to the query: `params` flags the missing `day`; the `status` typed is still there. Then
   `SELECT title FROM Task WHERE status = :status` (the target after the parameter): `status` still offered.
10. With `WHERE status = :status` and `{"status": "OPEN"}`, run *Query*: rows answer. In the tab's history, *Replay*
    the call: `query` and `params` are filled, nothing is sent.
11. Type `select title from Task where status=:status and project='vidocq' order by title` and press *Format*: one
    clause per line, `  AND project = 'vidocq'` indented, `:status` and `'vidocq'` as written; `cmd+z` once restores
    the text. Type `FROM Task WHERE title = 'open` and press `shift+alt+f`: `not formatted: line 1: unterminated
    string` under the editor. (S)
12. Type `'` where a value goes: `''`, the caret between; `(`: `()`.
13. Pick *Export CSV* in the tab's list: its `query` and `params` are the same editors (colours, `ctrl+space` after
    `FROM`, the parameters following the query). `read_network_requests` with pattern `api/language`: still one
    request since item 2.
14. A slow vocabulary: `navigate` to `http://127.0.0.1:18094/` again, run with `javascript_tool`:
    `const f = window.fetch; window.fetch = (u, o) => String(u).includes('api/language') ? new Promise((r) => setTimeout(() => r(f(u, o)), 6000)) : f(u, o); 'slow'`,
    then open *Mansart Data* → *JDQL* → *Query* and at once type `FROM Task WHERE ti` in `query`: `FROM` and `WHERE`
    coloured, `Task` plain. Within six seconds `Task` takes the target's colour and `ti` is underlined
    (`unknown attribute ti of Task`); the text is unchanged and `document.activeElement.selectionStart` (read with
    `javascript_tool` before and after) is the same. (S)
15. A failed vocabulary: `navigate` again, run
    `const f = window.fetch; window.fetch = (u, o) => String(u).includes('api/language') ? Promise.resolve(new Response('', {status: 404})) : f(u, o); 'failing'`,
    open the *JDQL* tab: the query editor's note reads `no vocabulary: 404`; `FROM ` then `ctrl+space` offers
    nothing, `WHERE` is still a keyword.
16. Run with `javascript_tool` `document.documentElement.dataset.theme = "dark"; 'dark'`: the query's colours are
    readable and distinct (S); then `delete document.documentElement.dataset.theme; 'auto'`.
17. `tabs_close_mcp` on the tab this step opened.

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
Expected: the tree clean (but for files that were untracked before this plan), this plan's eleven commits on
`feat/devconsole-query-mode` after `docs(spec): a query mode for the dev console's editor, and JDQL in Mansart Data`.
Nothing pushed. Report: the branch to push, the screenshots, and any item of Step 5 that did not behave as written.

---

## Self-review (done while writing)

- **Spec coverage.** §1 in scope: the SPI (Task 1), the endpoint and the snapshot's ids (2), the query mode — tokens,
  context, completion, diagnostics, `parameters()`, formatting, pairs (3-7) —, the page's query and parameters
  editors, `setData`, the language fetched once (8, 9), Mansart's `jdql` and the three schemas (10), the docs (11);
  out of scope respected (no alias, join or sub-query, no grammar in the page, `?1` coloured and given no key, the
  pools panel untouched). §2.1: the record, its refusals, both defaults, read once with the actions, duplicates and a
  throwing `languages()` (1, 2; Rulings 1, 2, 4). §2.2 read (3) and written (10). §2.3 (2; Ruling 3). §2.4 (10;
  Ruling 18). §3 contract and odd data (3-7). §3.1 (3, colours 8). §3.2 (3). §3.3 every row (4; Ruling 11). §3.4
  every error and none without vocabulary (5; Ruling 13). §3.5 every row, twice, positional (6; Ruling 14). §3.6
  (7; Rulings 10, 15). §4: the query editor, five rows, `x-language` fetched once and shared, `setData`, the failed
  fetch's note, `x-parameters-of` recomputed after each draw and when the language arrives, sent as typed, the JSON
  switch, replay, disabling and Choose file unchanged (8, 9; Rulings 5, 6, 9, 16, 17). §5: SPI (1), console (2),
  `PageTest` (3, 8, 9), GraalJS (3-7), Mansart (10), browser (12). §6 (11; Ruling 20). §7: the Review Focus.
- **Placeholders.** None: every code step has its code, every run step its command and its Expected line.
- **Types and names.** `PanelLanguage(id, json)`, `MAX_JSON` (1) → `PanelEntry.languages()`/`language(id)`,
  `ConsoleHandler.LANGUAGE_PREFIX`, the snapshot's `languages` (2) → `panelLanguage(panelId, id)` reading
  `snapshot.panels[].languages` and fetching `api/language/<panel>/<id>` (9). `queryLanguage()`'s `QUERY` gains
  `complete` (4), `diagnose` (5), `parameters` (6), `format` (7), in the order `tokenize, diagnose, complete, format,
  parameters, pairs`; `clauseAt`/`clauseBefore` (4) are used by 6 and 7; `isClosed` (5) by 7. `createEditor({ …,
  onDraw })` and `setData(next, why)` (8) are what `queryEditor`/`parametersEditor` call (9). `JdqlLanguage.ID`,
  `MEDIA_TYPE`, `JdqlActions.query(example)`/`params()` (10) are the strings the page reads as `x-language`,
  `contentMediaType`, `x-parameters-of` (9).
- **Verified while planning.** The code blocks of this very plan were applied by a script that reads its
  `Create`/`In …, replace` steps, task by task, to the files of `HEAD` (the three modules' `target/` removed first, so
  that no stale class hid a RED). Every RED and GREEN ran with the plan's commands on Java 25 and ended as its
  Expected line says: Task 1 `cannot find symbol`, then 67; Task 2 7 failures, then 67 and 430; Task 3 one failure
  and one error, then 10 and 28; Task 4 7 errors, then 17; Task 5 7 errors, then 24 and 50; Task 6 6 errors, then 30;
  Task 7 3 failures and 3 errors, then 36, 50 and 28; Task 8 2 failures and 1 error, then 30; Task 9 4 failures and 1
  error, then 67 and 470; Task 10 `cannot find symbol` (`variable JdqlLanguage`), then 67 and 165; Task 11's
  checks `3`, `1`, `1`. The 31 files the plan leaves were byte-identical to the planner's own final state; `node
  --check` accepted `console.js`, `editor.js` and `editor-core.js`; no new line of code passes 120 characters; and
  `mvn verify` of the SPI, the console, the Mansart `-dev` module and `vidocq-runtime-mansart-h2-example` in one
  reactor passed (the example's `DevConsoleSnapshotTest`: 10). The page itself was not run in a browser (Task 12 is
  its first run). The repository was then restored to `HEAD`.
- **Note for the executor.** If a `target/` of the SPI, the console or the Mansart `-dev` module holds classes or page
  files built from this plan's code before the task that adds them (an interrupted run), a RED may not show: run that
  RED with `clean test`.
