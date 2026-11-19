# Dev console code editor for JSON arguments, and a nested form — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every `json` argument of a dev console action gets a hand-written code editor (colours, diagnostics against
the argument's JSON Schema, completion, formatting, smart keystrokes), the generated form takes one level of nesting
so that `TaskRepository.save(entity: Task)` gets fields, and Mansart Data's entity schema says what is `required`,
`readOnly`, and which column each attribute is.

**Architecture:** Two new ES modules next to `console.js`: `editor-core.js`, pure (no DOM), holding the JSON language
(`tokenize`, `diagnose`, `complete`, `format`, `pairs`) and `keystroke()`, run in JUnit by GraalJS; and `editor.js`,
the DOM component (`createEditor`) that draws a coloured `<pre>` under a transparent `<textarea>` and turns keys into
edits through `document.execCommand("insertText")`. `console.js` imports `editor.js` only; `formShape` replaces
`isFlatSchema` and builds a fieldset per nested object. The Mansart `-dev` module enriches `EntityJson.schema`.

**Tech Stack:** vanilla JavaScript (ES2022 modules, DOM APIs only, no build, no library), CSS custom properties,
JUnit 5, GraalJS `org.graalvm.polyglot:polyglot` + `org.graalvm.polyglot:js` 25.4.4.1.1 (test scope, interpreter
mode), Java 25, Maven 3.9, AsciiDoc.

**Spec:** `docs/superpowers/specs/2026-09-30-devconsole-editor-json-design.md` — the binding authority; § numbers
below refer to it.

## Global Constraints

- **Paths.** `$VIDOCQ` is this repository's checkout, `$JAVA25` a Java 25 home (Temurin), `$LC4JCDI` a checkout
  of LC4JCDI-on-vidocq, `$SCRATCH` a scratch directory outside the repository.
- **Toolchain.** Java 25: every Maven command is `JAVA_HOME=$JAVA25 mvn -nsu …`,
  run from the repository root `$VIDOCQ`. Never `./mvnw` or `mvnw`.
- **Branch.** `feat/devconsole-editor`, already checked out. Do not switch branches. Never push.
- **Module paths** (commands spell them out in full):
  - DC: `vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension`
  - PAGE: `DC/src/main/resources/META-INF/resources/devconsole` (`index.html`, `console.css`, `console.js`, new
    `editor-core.js`, `editor.js`)
  - DCT: `DC/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole` (`PageTest`, `DevConsoleExtensionTest`,
    new `EditorCoreTest`)
  - MD: `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev`
    (main `…/mansart/data/dev/EntityJson.java`; tests `RunFixtures.java`, `EntityJsonTest.java`, `SignatureTest.java`
    under `MD/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev`)
  - SPI: `vidocq-runtime-devconsole-spi` (`src/main/java/io/vidocq/runtime/spi/devconsole/PanelAction.java`)
- **The page loads nothing from another site; CSP stays `default-src 'self'`.** No `innerHTML`, `outerHTML`,
  `insertAdjacentHTML`, `document.write`, `eval(`, `new Function`, `setAttribute("style"`, `srcdoc`, `import(` in any
  script (PageTest checks every `.js` of `FILES`); every text through `textContent`, a text node or an attribute value;
  no `url(` and no `@import` in CSS; `localStorage` only inside a `try`, and only in `console.js` (`stored`/`store`).
- **`editor-core.js` never references `document`, `window`, `navigator`, `requestAnimationFrame`, `localStorage`,
  `globalThis` or any DOM API, and imports nothing** (PageTest checks it with comments stripped).
- **`console.js` imports `./editor.js` and no other script; `editor.js` imports `./editor-core.js` only; `index.html`
  still loads `console.js` only.**
- **Theme:** the editor's token colours are the JSON viewer's `--json-key`, `--json-string`, `--json-number`,
  `--json-literal`, `--json-punct`; an invalid run and an error use `--crit`, a warning `--warn` — all defined in
  `:root {`, in `@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) {…} }` and in
  `:root[data-theme="dark"] {`. No new colour variable.
- **Code style:** English; lines at most 120 characters in new code; the comment density of the file around;
  sentence-like test names (`aJsonPropertyNamedProtoIsAnObjectKeyLikeAnyOther`). Every new Java/JS file starts with the
  repository's license header (the one of `console.js` / `PageTest.java`, copied verbatim in this plan).
- **Keep verbatim** (PageTest pins them): `const REPLAY_COLUMN = "replay"`, `const MASKED = "***"`,
  `const FILTER_FROM = 10`, `"Exchange"`, `function jsonField(argument)`, `function fileChooser(target)` placed right
  before `jsonField`, `} else if (kind === "string" && definition.format === "textarea") {`,
  `input = el("textarea", "json-text");`, `input.rows = 4;`, `wrap.classList.add("wide");`,
  `if (definition.contentMediaType === "text/csv") chooser = fileChooser(input);`,
  `object[property] = fileText ? fileText() : input.value;`, `...choosers]) c.disabled = on;`,
  `Object.hasOwn(properties, name)`, `Object.hasOwn(object, property) ? object[property] : undefined`, and exactly three
  lines holding `= Object.create(null);` in `console.js`.
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

## Rulings (where this plan settles what the spec leaves open)

1. **GraalJS coordinates and version (§7).** The newest release on Maven Central is **25.4.4.1.1** (2026-09-22).
   Its `org.graalvm.polyglot:js-community` POM reads *"Deprecated: Please use the 'org.graalvm.polyglot:js' Maven
   coordinate instead"* and only depends on `js`, which is now itself MIT/UPL: the plan uses
   `org.graalvm.polyglot:polyglot` + `org.graalvm.polyglot:js` (type `pom`), the same community bits without the
   deprecated alias. Versions live in the root `pom.xml` (`graaljs.version`, `dependencyManagement`), the DC `pom.xml`
   declares both at `test` scope without a version. **Verified while planning** on Java 25 (Temurin) in DC itself: the
   module's tests run on the module path (main `module-info.java`, test classes patched into
   `io.vidocq.runtime.extensions.essentials.devconsole`), GraalJS lands on the class path and the patched module reads
   `ALL-UNNAMED`, so no `requires` and no surefire change are needed; `--enable-native-access=ALL-UNNAMED` is appended
   to the existing `argLine` because Truffle calls `System.load` from the unnamed module (a warning on 25, blocked by a
   later JDK). The module is loaded with `Source.newBuilder("js", text, "editor-core.mjs").mimeType(
   "application/javascript+module").buildLiteral()` and `Context` options `engine.WarnInterpreterOnly=false` (the
   builder form of `polyglot.engine.WarnInterpreterOnly`) and `js.esm-eval-returns-exports=true`, which makes
   `context.eval(source)` return the module's exports. A missing member throws
   `UnsupportedOperationException: Non readable or non-existent member key '<name>'`; a missing export is Java `null`.
   The JVM may print `sun.misc.Unsafe` warnings from Truffle: harmless.
2. **The gutter (§4 Structure)** is drawn by the `<pre>` itself, in the left padding the `<pre>`, the textarea and the
   caret's mirror share: one `div.ed-line` per text line, its number a CSS counter in `::before`, its diagnostic dot in
   `::after`. A wrapped line keeps its number and the gutter scrolls with the text without any measuring. It looks as
   the spec describes (numbers, then the text box).
3. **Where a container's diagnostic sits.** A `type` or `enum` error on an object or an array is marked on its opening
   bracket, like a missing `required` key, not over the whole value, so its tooltip does not show wherever the caret is
   inside it. A scalar's is its whole token.
4. **The version attribute** is `readOnly` and never `required` (§5 says a read-only property is never required; §6's
   rule alone would make a non-null `Long version` required).
5. **The entity schema always has `required`**, possibly empty, after `properties`, as `Signature` writes its own. A
   Gizmo (whose id is neither generated nor nullable) now has `"required":["id"]`.
6. **The JSON mode sends the editor's own text** once it parses as a JSON object, instead of `JSON.stringify(JSON.parse
   (text))`, so an id past 2^53 typed there reaches the server as typed (§3.4's intent; the form still sends numbers).
7. **A nested object must list its `properties`** to be a fieldset; one that does not (a free-form object) sends the
   whole argument to the editor.
8. **`keystroke()` result:** `caret` is an offset into `insert`, as on a completion item; an optional `anchor` (also into
   `insert`) is the other end of the selection it leaves (a wrapped selection, indented lines); an empty `insert` over
   an empty range only moves the caret (stepping over a closing character). `Shift+Tab` with nothing to outdent returns
   `null`, and the editor still keeps the focus (only `Escape` then `Tab` leaves).
9. **Unknown keys** are not warned about on a schema that also has `anyOf`, `oneOf`, `allOf`, `not` or
   `patternProperties` (the keys those allow are unknown to the editor: §3.2 accepts what is under them). A `type` name
   the editor does not know, a non-array `enum`/`required`, a non-number `maxLength`, a non-string `format` are ignored.
10. **Completion details:** a key with no closing quote is replaced up to the caret only (it runs to the end of its
    line, §3.1); a key already followed by `:` is replaced alone, with no value; a string or number value with no enum
    offers nothing (`null`). `detail` reads `string, required — column title` (type, `required`/`generated`, then the
    description after an em dash).
11. **Formats (§3.2 warnings):** `time` accepts `14:30` and `14:30:00`, with an optional fraction and offset (Mansart's
    `LocalTime` takes `10:15`); `date-time` needs the `T`; `uuid` is the 8-4-4-4-12 hex shape.
12. **Formatting** throws `line <n>: <message>`; after *Format* the caret goes to the start of the text.
13. **Mansart fixture:** a hand-built `Task` model (generated id, `Long` version, non-null `title`, nullable `notes`,
    non-null primitive `points`, non-null enum `level`, non-null reference `owner` to a Gizmo, column `owner_id`) and a
    `TaskRepository extends BasicRepository<Task, Long>` join `RunFixtures`; `Task` is added to `ENTITIES`, not the
    repository to `REPOSITORIES`, so no other test changes. **Verified while planning:** the whole MD suite (159 tests)
    passes with Task 9 applied.
14. **Docs:** besides §8's list, `dev-console-panels.adoc#json-argument` states the old flat rule and is updated too.
15. **The editor is 8 rows** (the raw textarea had 6).
16. **The helper `dev-run.sh`** named in the task brief is no longer in the scratchpad: Task 11 recreates it (its
    content is given) before using it.

## Review Focus

1. **A schema from elsewhere that is odd** (an MCP tool's: `type` missing or unknown, `properties` not an object, a
   `$ref` cycle, `required` naming a key `properties` lacks) — the editor checks less and never throws, the page never
   breaks. Pinned by Task 3's `anOddSchemaNeverThrowsItChecksLess` and Task 7's PageTest line
   `diagnostics = safely(() => language.diagnose(text, data), []);`.
2. **An id past 2^53 typed in the JSON mode** — formatted and sent exactly as typed, never rounded. Pinned by Task 5's
   `numbersAndStringsAreCopiedAsWritten` and Task 8's PageTest `return editor.value();`.
3. **A key being typed with no closing quote in the middle of a text** (the string runs to the end of its line) — an
   accepted completion replaces what is typed up to the caret, not the rest of the line. Pinned by Task 4's
   `aKeyWithNoClosingQuoteIsReplacedUpToTheCaretOnly`.
4. **Escape, then Shift+Tab** (the Shift key goes down first) — still leaves the editor: keyboard users are not trapped.
   Pinned by Task 7's PageTest `if (event.isComposing || MODIFIERS.has(event.key)) return;` and Task 11's check.
5. **The completion list near the right or bottom edge, in a scrolled editor, in a narrow window** — it stays inside the
   editor's width and flips above the caret when the window has no room below. Pinned by Task 7's PageTest lines on
   `place` and Task 11's check.

## File Structure

| File | Change | Responsibility |
|------|--------|----------------|
| `pom.xml` (root) | modify | `graaljs.version`; `polyglot` and `js` in `dependencyManagement` (Task 1) |
| `DC/pom.xml` | modify | GraalJS test dependencies; `--enable-native-access=ALL-UNNAMED` (Task 1) |
| `PAGE/editor-core.js` | create | tokens (Task 1), syntax (2), schema (3), completion (4), formatting (5), keystrokes (6) |
| `PAGE/editor.js` | create | `createEditor`, the DOM component (Task 7) |
| `PAGE/console.css` | modify | editor styles (Task 7); nested fieldset, old `.json-editor` rule removed (Task 8) |
| `PAGE/console.js` | modify | import, `formShape`, nested form, editor wiring, masked values at depth (Task 8) |
| `PAGE/index.html` | modify | its comment names the imported modules (Task 8) |
| `DCT/EditorCoreTest.java` | create | GraalJS harness and the core's tests (Tasks 1-6) |
| `DCT/PageTest.java` | modify | new files, rules for every script (Tasks 1, 7, 8) |
| `DCT/DevConsoleExtensionTest.java` | modify | the two modules are served as JavaScript (Task 7) |
| `MD/…/EntityJson.java` | modify | `required`, `readOnly`, `description` (Task 9) |
| `MD/…/RunFixtures.java`, `EntityJsonTest.java`, `SignatureTest.java` | modify | Task fixture and tests (Task 9) |
| `SPI/…/PanelAction.java` | modify | Javadoc of `Argument` and `Argument.json` (Task 10) |
| `docs/en/modules/ROOT/pages/dev-console.adoc`, `dev-console-panels.adoc`, `modules/vidocq-runtime-extensions.adoc`, `whats-new.adoc` | modify | Task 10 |

**Testing honesty.** `editor-core.js` is covered by `EditorCoreTest` under GraalJS (45 tests). `editor.js` and the
`console.js` form have no JavaScript harness: `PageTest` pins their rules as text, and Task 11's browser check is what
proves they behave. While planning, `editor.js` and the new `jsonField` were run in Chrome on a scratch copy of the page
(alignment of the coloured text with wrapped lines, tabs and emoji; automatic list after `"`; `Ctrl+Space`; accept;
one `⌘Z` per completion and per *Format*; hover and caret tooltips; bracket outline; the list clamped at the right
edge; the 100 000-character limit; the nested form, its path refusals, the JSON switch both ways, masked replay values,
a `__proto__` key, an id past 2^53 sent as typed; the dark theme). Task 11 repeats it on the real application.

**The editor draws in `requestAnimationFrame`.** A Chrome tab driven by the MCP extension may report
`document.visibilityState === "hidden"` and not run animation frames until a screenshot is taken: in Task 11 take a
screenshot (or a zoom) before reading what the editor drew.

---

### Task 1: GraalJS harness and the JSON tokens

**Files:**
- Modify: `pom.xml` (root: `<properties>` end, `<dependencyManagement>` before `<!-- Arquillian BOM -->`)
- Modify: `DC/pom.xml` (dependencies, surefire `argLine`)
- Create: `PAGE/editor-core.js`
- Create: `DCT/EditorCoreTest.java`
- Modify: `DCT/PageTest.java` (`FILES`, markup and `localStorage` rules for every script, a rule for the core)

**Interfaces:**
- Consumes: nothing.
- Produces (every later task relies on these names):
  - `editor-core.js` exports `INDENT` (`"  "`), `FORMAT_EXAMPLES` (`Map`: `date` → `2026-09-30`, `time` → `14:30:00`,
    `date-time` → `2026-09-30T14:30:00`, `uuid` → `123e4567-e89b-12d3-a456-426614174000`) and `jsonLanguage` (frozen
    object: `id: "json"`, `tokenize(text) → [{from, to, kind}]`, `pairs: ["{}", "[]", "()", "\"\""]`).
  - internal: `PUNCT`, `NUMBER`, `LITERALS`, `isBlank(c)`, `isObject(v)`, `isStringToken(t)`, `lex(text)`,
    `punctAt(text, t) → char | null`, `isClosed(text, t) → boolean`.
  - `EditorCoreTest`: static `context`, `json` (the engine's `JSON`), `language` (`jsonLanguage`), helpers
    `resource(name)`, `parsed(jsonText) → Value | null`, `number(value, member) → int`, `tokens(text) → "kind:text …"`,
    constant `EMOJI`.
  - `PageTest`: `SCRIPTS` (every `.js` of `FILES`), `IMPORT` (pattern of an `import … from "…";` line).

- [ ] **Step 1: The GraalJS dependencies**

In the root `pom.xml`, replace:
```xml
        <liquibase.version>4.31.1</liquibase.version>
    </properties>
```
with:
```xml
        <liquibase.version>4.31.1</liquibase.version>
        <!-- GraalJS, test scope only: the dev console's EditorCoreTest runs the page's editor-core.js as the ES module
             it is. The community coordinates: org.graalvm.polyglot:js (js-community is its deprecated alias). -->
        <graaljs.version>25.4.4.1.1</graaljs.version>
    </properties>
```
and, in `<dependencyManagement>`, replace:
```xml
                <artifactId>vidocq-runtime-mansart-transactions-extension-codegen</artifactId>
                <version>${project.version}</version>
                <type>pom</type>
            </dependency>

            <!-- Arquillian BOM -->
```
with:
```xml
                <artifactId>vidocq-runtime-mansart-transactions-extension-codegen</artifactId>
                <version>${project.version}</version>
                <type>pom</type>
            </dependency>

            <!-- GraalJS for tests that run the dev console's JavaScript: never on a runtime module path. -->
            <dependency>
                <groupId>org.graalvm.polyglot</groupId>
                <artifactId>polyglot</artifactId>
                <version>${graaljs.version}</version>
            </dependency>
            <dependency>
                <groupId>org.graalvm.polyglot</groupId>
                <artifactId>js</artifactId>
                <version>${graaljs.version}</version>
                <type>pom</type>
            </dependency>

            <!-- Arquillian BOM -->
```

In `DC/pom.xml`, replace:
```xml
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
```
with:
```xml
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <!-- For the tests only: GraalJS runs editor-core.js, the page's pure module, as the ES module it is. Not
             required by the module: its tests reach it on the class path, through the reads of ALL-UNNAMED. -->
        <dependency>
            <groupId>org.graalvm.polyglot</groupId>
            <artifactId>polyglot</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.graalvm.polyglot</groupId>
            <artifactId>js</artifactId>
            <type>pom</type>
            <scope>test</scope>
        </dependency>
    </dependencies>
```
and replace:
```xml
                    <argLine>--add-reads io.vidocq.runtime.extensions.essentials.devconsole=io.vidocq.runtime.core</argLine>
```
with:
```xml
                    <!-- GraalJS loads a native library of its own from the class path: allowed, so that a later JDK
                         that blocks restricted methods does not stop EditorCoreTest. -->
                    <argLine>--add-reads io.vidocq.runtime.extensions.essentials.devconsole=io.vidocq.runtime.core --enable-native-access=ALL-UNNAMED</argLine>
```

- [ ] **Step 2: Write the failing tests**

Create `DCT/EditorCoreTest.java` (all its imports now: later tasks only add members):

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
 * The editor's pure half, {@code editor-core.js}, run by GraalJS as the ES module the page loads (spec §7): the JSON
 * language's tokens, diagnostics, completion and formatting, and the smart keystrokes. The engine runs interpreted: the
 * tests need no compiler, and the warning that says so is off.
 */
class EditorCoreTest {

    /** One emoji, two UTF-16 units: offsets must count both. */
    private static final String EMOJI = "\uD83D\uDE00";

    private static Context context;
    private static Value json;
    private static Value language;

    @BeforeAll
    static void loadTheModule() {
        context = Context.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .option("js.esm-eval-returns-exports", "true")
                .build();
        Value exports = context.eval(Source.newBuilder("js", resource("editor-core.js"), "editor-core.mjs")
                .mimeType("application/javascript+module").buildLiteral());
        json = context.eval("js", "JSON");
        language = exports.getMember("jsonLanguage");
    }

    @AfterAll
    static void closeTheEngine() {
        context.close();
    }

    private static String resource(String name) {
        String path = DevConsoleExtension.PAGE_RESOURCES + "/" + name;
        try (InputStream in = EditorCoreTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "the page has no " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code text} parsed by the engine's own JSON.parse, as the page parses a schema; null stays null. */
    private static Value parsed(String text) {
        return text == null ? null : json.invokeMember("parse", text);
    }

    private static int number(Value object, String member) {
        return object.getMember(member).asInt();
    }

    /** The tokens of {@code text}, each as kind:text, separated by spaces. */
    private static String tokens(String text) {
        Value tokens = language.invokeMember("tokenize", text);
        List<String> out = new ArrayList<>();
        for (long i = 0; i < tokens.getArraySize(); i++) {
            Value t = tokens.getArrayElement(i);
            out.add(t.getMember("kind").asString() + ":" + text.substring(number(t, "from"), number(t, "to")));
        }
        return String.join(" ", out);
    }

    // ------------------------------------------------------------------------------------------------ tokens

    @Test
    void everyKindOfToken() {
        assertEquals("punct:{ key:\"a\" punct:: punct:[ number:1 punct:, number:-2.5e3 punct:, literal:true punct:, "
                + "literal:null punct:, string:\"x\" punct:] punct:, key:\"b\" punct:: literal:false punct:}",
                tokens("{\"a\": [1, -2.5e3, true, null, \"x\"], \"b\": false}"));
    }

    @Test
    void anUnterminatedStringRunsToTheEndOfItsLineAndIsAString() {
        assertEquals("punct:{ key:\"a\" punct:: string:\"open punct:, number:1 punct:}",
                tokens("{\"a\": \"open\n, 1}"));
    }

    @Test
    void anInvalidRunEndsAtTheNextBlankPunctuationOrQuote() {
        assertEquals("punct:[ invalid:tru punct:, invalid:01 punct:, invalid:'x' punct:, invalid:1. punct:, "
                + "invalid:abc string:\"d\" punct:]", tokens("[tru, 01, 'x', 1., abc\"d\"]"));
        assertEquals("punct:{ key:\"a\" punct:: number:1 invalid:// invalid:note punct:}",
                tokens("{\"a\": 1 // note\n}"), "a comment-like // is no comment in JSON");
    }

    @Test
    void offsetsAreUtf16UnitsWhateverTheLineEndsTabsAndEmoji() {
        String text = "{\r\n\t\"" + EMOJI + "\": \"\u00e9" + EMOJI + "\",\r\n\t\"b\": 1" + EMOJI + "\r\n}";

        assertEquals("[{\"from\":0,\"to\":1,\"kind\":\"punct\"},{\"from\":4,\"to\":8,\"kind\":\"key\"},"
                + "{\"from\":8,\"to\":9,\"kind\":\"punct\"},{\"from\":10,\"to\":15,\"kind\":\"string\"},"
                + "{\"from\":15,\"to\":16,\"kind\":\"punct\"},{\"from\":19,\"to\":22,\"kind\":\"key\"},"
                + "{\"from\":22,\"to\":23,\"kind\":\"punct\"},{\"from\":24,\"to\":27,\"kind\":\"invalid\"},"
                + "{\"from\":29,\"to\":30,\"kind\":\"punct\"}]",
                json.invokeMember("stringify", language.invokeMember("tokenize", text)).asString());
    }

    @Test
    void theLanguageDeclaresItsIdAndPairs() {
        assertEquals("json", language.getMember("id").asString());
        assertEquals("[\"{}\",\"[]\",\"()\",\"\\\"\\\"\"]",
                json.invokeMember("stringify", language.getMember("pairs")).asString());
    }
}
```

In `DCT/PageTest.java`, replace:
```java
    /** Every file of the page. */
    private static final List<String> FILES = List.of("index.html", "console.css", "console.js", "favicon.svg");
```
with:
```java
    /** Every file of the page. */
    private static final List<String> FILES = List.of("index.html", "console.css", "console.js", "favicon.svg",
            "editor-core.js");
    /** The page's scripts: console.js, which the index loads, and the modules it imports. */
    private static final List<String> SCRIPTS = FILES.stream().filter(name -> name.endsWith(".js")).toList();
    /** An import statement of a module, and the module it names. */
    private static final Pattern IMPORT = Pattern.compile("(?m)^import .* from \"([^\"]+)\";$");
```

Replace the whole of `theScriptNeverParsesTextAsMarkup` with:
```java
    @Test
    void theScriptNeverParsesTextAsMarkup() {
        for (String name : SCRIPTS) {
            String script = file(name);
            for (String sink : List.of("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(",
                    "new Function", "setAttribute(\"style\"", "srcdoc", "import(")) {
                assertFalse(script.contains(sink), name + " uses " + sink + ": every text goes through textContent");
            }
        }
        String script = file("console.js");
        assertTrue(script.contains("textContent"), script.length() + " characters and no textContent");
    }
```

Replace the whole of `theScriptTouchesLocalStorageInsideATryOnly` with (and add the new test after it):
```java
    @Test
    void theScriptTouchesLocalStorageInsideATryOnly() {
        for (String name : SCRIPTS) {
            List<String> uses = file(name).lines()
                    .filter(line -> !line.strip().startsWith("//") && !line.strip().startsWith("*"))
                    .filter(line -> line.contains("localStorage"))
                    .toList();

            assertEquals(name.equals("console.js") ? 2 : 0, uses.size(),
                    "one read, one write, in console.js only: " + name + " " + uses);
            for (String use : uses) {
                assertTrue(use.contains("try {") && use.contains("catch"), "a private window may refuse it: " + use);
            }
        }
    }

    @Test
    void theEditorCoreTouchesNothingOfThePageSoThatGraalJsRunsIt() {
        String code = file("editor-core.js").replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");

        for (String global : List.of("document", "window", "navigator", "requestAnimationFrame", "localStorage",
                "globalThis")) {
            assertFalse(Pattern.compile("\\b" + global + "\\b").matcher(code).find(), "editor-core.js uses " + global);
        }
        assertEquals(List.of(), IMPORT.matcher(code).results().map(m -> m.group(1)).toList(), "it imports nothing");
        assertTrue(code.contains("export const jsonLanguage = Object.freeze({"), "the JSON language");
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -q -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=EditorCoreTest,PageTest
```
Expected: FAIL — `EditorCoreTest` errors in `loadTheModule` with `the page has no META-INF/resources/devconsole/editor-core.js`;
`PageTest` fails `noFileLoadsAnythingFromElsewhere`, `theScriptNeverParsesTextAsMarkup`,
`theScriptTouchesLocalStorageInsideATryOnly` and `theEditorCoreTouchesNothingOfThePageSoThatGraalJsRunsIt` with the
same message. (It compiles: GraalJS resolves from Maven Central the first time.)

- [ ] **Step 4: Write `editor-core.js` with its tokens**

Create `PAGE/editor-core.js`:

```js
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

// The dev console's code editor, its pure half: the languages and the smart keystrokes. It never touches the page, so
// that it runs in GraalJS for the tests (EditorCoreTest) exactly as it runs in the browser; editor.js draws what it
// computes.
//
// - A language is { id, tokenize(text), diagnose(text, data), complete(text, caret, data), format(text), pairs }.
//   Every offset is a UTF-16 offset into the text, as a textarea counts them.
// - jsonLanguage reads JSON, its data being the JSON Schema of the value: the first syntax error, then what the
//   schema says at every depth, following properties, items, additionalProperties and a local $ref, never anyOf,
//   oneOf, allOf, not or patternProperties. A schema, however odd, never makes it throw: it checks less.
// - keystroke() is the edit a key makes, or null to let the browser type it.

/** One level of indentation, as the formatter and the Tab key write it. */
export const INDENT = "  ";

/** An example of each string format the editor knows, which the generated form also shows as a placeholder. */
export const FORMAT_EXAMPLES = new Map([["date", "2026-09-30"], ["time", "14:30:00"],
  ["date-time", "2026-09-30T14:30:00"], ["uuid", "123e4567-e89b-12d3-a456-426614174000"]]);

const PUNCT = "{}[]:,";
const NUMBER = /^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$/;
const LITERALS = new Map([["true", true], ["false", false], ["null", null]]);

const isBlank = (c) => c === " " || c === "\t" || c === "\n" || c === "\r";
const isObject = (v) => v !== null && typeof v === "object" && !Array.isArray(v);
const isStringToken = (t) => t.kind === "string" || t.kind === "key";

// ------------------------------------------------------------------------------------------------ tokens

/**
 * The tokens of {@code text}, in order, covering every character that is not blank: key (a string followed by ":"),
 * string, number, literal (true, false, null), punct ({ } [ ] : ,), and invalid for any other run of characters up to
 * the next blank, punctuation or quote. A string with no closing quote runs to the end of its line.
 */
function lex(text) {
  const tokens = [];
  const n = text.length;
  let i = 0;
  while (i < n) {
    const c = text[i];
    if (isBlank(c)) {
      i++;
      continue;
    }
    if (PUNCT.includes(c)) {
      tokens.push({ from: i, to: i + 1, kind: "punct" });
      i++;
      continue;
    }
    let j = i + 1;
    if (c === "\"") {
      while (j < n) {
        const d = text[j];
        if (d === "\n" || d === "\r") break;
        j++;
        if (d === "\"") break;
        if (d === "\\" && j < n && text[j] !== "\n" && text[j] !== "\r") j++;
      }
      tokens.push({ from: i, to: j, kind: "string" });
    } else {
      while (j < n && !isBlank(text[j]) && !PUNCT.includes(text[j]) && text[j] !== "\"") j++;
      const word = text.slice(i, j);
      tokens.push({ from: i, to: j, kind: NUMBER.test(word) ? "number" : LITERALS.has(word) ? "literal" : "invalid" });
    }
    i = j;
  }
  for (let k = 0; k + 1 < tokens.length; k++) {
    if (tokens[k].kind === "string" && punctAt(text, tokens[k + 1]) === ":") tokens[k].kind = "key";
  }
  return tokens;
}

/** The punctuation character of token {@code t}, or null when it is none. */
const punctAt = (text, t) => t !== undefined && t.kind === "punct" ? text[t.from] : null;

/** Whether the string token {@code t} ends with its closing quote, an escaped quote not being one. */
function isClosed(text, t) {
  if (t.to - t.from < 2 || text[t.to - 1] !== "\"") return false;
  let backslashes = 0;
  for (let i = t.to - 2; i > t.from && text[i] === "\\"; i--) backslashes++;
  return backslashes % 2 === 0;
}

// ------------------------------------------------------------------------------------------------ the language

/** JSON, its data the JSON Schema of the value (spec §3). */
export const jsonLanguage = Object.freeze({
  id: "json",
  tokenize: (text) => lex(text),
  pairs: Object.freeze(["{}", "[]", "()", "\"\""]),
});
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: the command of Step 3.
Expected: exit status 0; with `-q` Maven prints nothing but, possibly, `WARNING: … sun.misc.Unsafe …` lines from
Truffle (harmless). `EditorCoreTest`: 5 tests, `PageTest`: all green.

- [ ] **Step 6: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
test(devconsole): run the page's editor-core.js in GraalJS, and its JSON tokens

The dev console gets editor-core.js, the pure half of a code editor: no DOM, so that EditorCoreTest runs it with
GraalJS (org.graalvm.polyglot:polyglot and :js 25.4.4.1.1, test scope, interpreted) as the ES module the page
loads. First piece: the JSON tokens (key, string, number, literal, punct, invalid), UTF-16 offsets. PageTest now
applies its markup and localStorage rules to every script, and checks the core touches nothing of the page.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add pom.xml vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/pom.xml vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/editor-core.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/EditorCoreTest.java vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/PageTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 2: JSON syntax diagnostics

**Files:**
- Modify: `PAGE/editor-core.js` (a `syntax` section before `// ---… the language`; the language block)
- Test: `DCT/EditorCoreTest.java` (a `diagnostics` section)

**Interfaces:**
- Consumes (Task 1): `lex`, `punctAt`, `isClosed`, `isStringToken`, `LITERALS`, `jsonLanguage`.
- Produces:
  - `stringValue(text, t) → string | undefined` (undefined: unterminated, or an invalid escape).
  - `parse(text, tokens) → { value, error }`: `value` a tree of nodes keeping their offsets —
    `{ kind: "object", from, to, members: [{ key, keyFrom, keyTo, value }] }`, `{ kind: "array", from, to, items }`,
    `{ kind: "string" | "number" | "literal", from, to, value }` — or `null`; `error` the first syntax error
    `{ from, to, severity: "error", message }` or `null`. An error at the end of the text has `from === to === length`.
  - `jsonLanguage.diagnose(text, data) → [{ from, to, severity, message }]` (syntax only in this task; Task 3 adds the
    schema checks).
  - `EditorCoreTest.diagnose(text, schemaJson) → "severity from-to message"`, one per line.
  - Messages: `unterminated string`, `invalid string`, `expected ',' or '}'`, `expected ',' or ']'`,
    `trailing comma`, `nothing after the value`, `expected ':'`, `expected a key or '}'`, `expected a key`,
    `expected a value`, `unexpected token`.

- [ ] **Step 1: Write the failing tests**

In `DCT/EditorCoreTest.java`, add before the class's final `}`:

```java

    // ------------------------------------------------------------------------------------------------ diagnostics

    /** The diagnostics of {@code text} under {@code schema}, one per line: severity from-to message. */
    private static String diagnose(String text, String schema) {
        Value found = language.invokeMember("diagnose", text, parsed(schema));
        List<String> out = new ArrayList<>();
        for (long i = 0; i < found.getArraySize(); i++) {
            Value d = found.getArrayElement(i);
            out.add(d.getMember("severity").asString() + " " + number(d, "from") + "-" + number(d, "to") + " "
                    + d.getMember("message").asString());
        }
        return String.join("\n", out);
    }

    @Test
    void theFirstSyntaxErrorOnlyAtItsOffsets() {
        assertEquals("error 6-11 unterminated string", diagnose("{\"a\": \"open", null));
        assertEquals("error 6-10 invalid string", diagnose("{\"a\": \"\\x\"}", null));
        assertEquals("error 8-11 expected ',' or '}'", diagnose("{\"a\": 1 \"b\": 2}", null));
        assertEquals("error 3-4 expected ',' or ']'", diagnose("[1 2]", null));
        assertEquals("error 7-8 trailing comma", diagnose("{\"a\": 1,}", null));
        assertEquals("error 2-3 trailing comma", diagnose("[1,]", null));
        assertEquals("error 9-10 nothing after the value", diagnose("{\"a\": 1} x", null));
        assertEquals("error 5-6 expected ':'", diagnose("{\"a\" 1}", null));
        assertEquals("error 1-2 expected a key or '}'", diagnose("{1: 2}", null));
        assertEquals("error 9-10 expected a key", diagnose("{\"a\": 1, 2}", null));
        assertEquals("error 6-7 expected a value", diagnose("{\"a\": }", null));
        assertEquals("error 6-9 unexpected token", diagnose("{\"a\": tru}", null));
        assertEquals("error 8-10 expected ',' or '}'", diagnose("{\"a\": 1 // note\n}", null));
    }

    @Test
    void anErrorAtTheEndOfTheTextIsEmptyAtItsLength() {
        assertEquals("error 7-7 expected ',' or '}'", diagnose("{\"a\": 1", null));
        assertEquals("error 0-0 expected a value", diagnose("", null));
        assertEquals("error 3-3 expected a value", diagnose(" \r\n", null));
    }

    @Test
    void aTextThatParsesHasNoDiagnosticWithoutASchema() {
        assertEquals("", diagnose("{\"a\": [1, {\"b\": null}], \"c\": \"\\u00e9\", \"d\": -0.5e-3}", null));
    }

    @Test
    void syntaxOffsetsCountCrlfTabsAndEmojiAsTheTextareaDoes() {
        assertEquals("error 15-16 trailing comma",
                diagnose("{\r\n\t\"" + EMOJI + "\": \"\u00e9" + EMOJI + "\",\r\n}", null));
    }

    @Test
    void aDeeplyNestedTextNeverExhaustsTheStack() {
        assertEquals("", diagnose("[".repeat(10_000) + "]".repeat(10_000), null));
        assertEquals("error 10000-10000 expected a value", diagnose("[".repeat(10_000), null));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -q -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=EditorCoreTest
```
Expected: FAIL — the 5 new tests error with `java.lang.UnsupportedOperationException: Non readable or non-existent
member key 'diagnose'`; the 5 token tests pass.

- [ ] **Step 3: Write the syntax section**

In `PAGE/editor-core.js`, insert right before the line
`// ------------------------------------------------------------------------------------------------ the language`:

```js
// ------------------------------------------------------------------------------------------------ syntax

/** The value of the string token {@code t}, or undefined when it is unterminated or holds an invalid escape. */
function stringValue(text, t) {
  if (!isClosed(text, t)) return undefined;
  try {
    return JSON.parse(text.slice(t.from, t.to));
  } catch (invalid) {
    return undefined;
  }
}

/**
 * {@code text} parsed from its {@code tokens}: { value, error }, value a tree of nodes that keep their offsets
 * ({ kind: "object", from, to, members: [{ key, keyFrom, keyTo, value }] }, { kind: "array", from, to, items },
 * { kind: "string" | "number" | "literal", from, to, value }), error the first syntax error, or null. The parser
 * keeps its own stack, so that a deeply nested text never exhausts the engine's. An error at the end of the text is
 * empty: from and to are both its length.
 */
function parse(text, tokens) {
  const end = text.length;
  const stack = [];
  let root;
  let k = 0;
  const fail = (t, message) => ({ value: null, error: { from: t ? t.from : end, to: t ? t.to : end,
    severity: "error", message } });
  const attach = (node) => {
    const top = stack[stack.length - 1];
    if (!top) {
      root = node;
    } else if (top.kind === "object") {
      top.members.push({ key: top.key, keyFrom: top.keyFrom, keyTo: top.keyTo, value: node });
      top.expect = "comma";
    } else {
      top.items.push(node);
      top.expect = "comma";
    }
  };
  const close = (container, t) => {
    container.to = t.to;
    stack.pop();
    attach(container);
  };
  for (;;) {
    const t = tokens[k];
    const top = stack[stack.length - 1];
    const c = punctAt(text, t);
    if (!top) {
      if (root !== undefined) return t ? fail(t, "nothing after the value") : { value: root, error: null };
    } else if (top.kind === "object" && top.expect !== "value") {
      if (top.expect === "comma") {
        if (c === ",") { top.expect = "key"; top.comma = t; k++; continue; }
        if (c === "}") { k++; close(top, t); continue; }
        return fail(t, "expected ',' or '}'");
      }
      if (top.expect === "colon") {
        if (c === ":") { top.expect = "value"; k++; continue; }
        return fail(t, "expected ':'");
      }
      if (c === "}") {
        if (top.expect === "key") return fail(top.comma, "trailing comma");
        k++;
        close(top, t);
        continue;
      }
      if (!t || !isStringToken(t)) return fail(t, top.expect === "first" ? "expected a key or '}'" : "expected a key");
      const key = stringValue(text, t);
      if (key === undefined) return fail(t, isClosed(text, t) ? "invalid string" : "unterminated string");
      top.key = key;
      top.keyFrom = t.from;
      top.keyTo = t.to;
      top.expect = "colon";
      k++;
      continue;
    } else if (top.kind === "array" && top.expect === "comma") {
      if (c === ",") { top.expect = "value"; top.comma = t; k++; continue; }
      if (c === "]") { k++; close(top, t); continue; }
      return fail(t, "expected ',' or ']'");
    } else if (top.kind === "array" && c === "]") {
      if (top.expect === "value") return fail(top.comma, "trailing comma");
      k++;
      close(top, t);
      continue;
    }
    // a value is expected here
    if (!t) return fail(t, "expected a value");
    if (c === "{") {
      stack.push({ kind: "object", from: t.from, to: end, members: [], expect: "first" });
    } else if (c === "[") {
      stack.push({ kind: "array", from: t.from, to: end, items: [], expect: "first" });
    } else if (isStringToken(t)) {
      const value = stringValue(text, t);
      if (value === undefined) return fail(t, isClosed(text, t) ? "invalid string" : "unterminated string");
      attach({ kind: "string", from: t.from, to: t.to, value });
    } else if (t.kind === "number") {
      attach({ kind: "number", from: t.from, to: t.to, value: Number(text.slice(t.from, t.to)) });
    } else if (t.kind === "literal") {
      attach({ kind: "literal", from: t.from, to: t.to, value: LITERALS.get(text.slice(t.from, t.to)) });
    } else {
      return fail(t, t.kind === "invalid" ? "unexpected token" : "expected a value");
    }
    k++;
  }
}

```

Then replace the language block:
```js
export const jsonLanguage = Object.freeze({
  id: "json",
  tokenize: (text) => lex(text),
  pairs: Object.freeze(["{}", "[]", "()", "\"\""]),
});
```
with:
```js
export const jsonLanguage = Object.freeze({
  id: "json",
  tokenize: (text) => lex(text),
  diagnose(text, data) {
    const { error } = parse(text, lex(text));
    return error ? [error] : [];
  },
  pairs: Object.freeze(["{}", "[]", "()", "\"\""]),
});
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: the command of Step 2.
Expected: exit status 0 (10 tests), no `[ERROR]` line.

- [ ] **Step 5: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(devconsole): the JSON editor's first syntax error, at its offsets

editor-core.js parses JSON with a stack of its own (a text nested 10 000 deep is fine) into a tree of nodes that
keep their offsets, and reports the first syntax error only: unterminated string, expected ',' or '}', trailing
comma, nothing after the value... An error at the end of the text is empty at its length.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/editor-core.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/EditorCoreTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 3: JSON Schema diagnostics at every depth

**Files:**
- Modify: `PAGE/editor-core.js` (a `schema` section before `// ---… the language`; the language block)
- Test: `DCT/EditorCoreTest.java` (the `TASK` schema; schema tests appended to the diagnostics section)

**Interfaces:**
- Consumes (Tasks 1-2): `isObject`, `FORMAT_EXAMPLES`, `parse`, `lex`.
- Produces:
  - `resolve(schema, root) → object | null` (local `$ref` to `#/$defs/…` or `#/definitions/…` followed, at most
    `MAX_REFS` = 32 in a chain; any other `$ref`, or a non-object, gives `null`), `pointer(root, ref)`.
  - `memberSchema(s, key)`, `itemSchema(s, i)` (unresolved schemas, or `null`), `typesOf(s) → string[] | null`,
    `hasType(node, type)`, `same(node, v)`, `listed(values)`, `check(value, schema) → diagnostics sorted by offset`.
  - `FORMAT_PATTERNS` (`Map` of `date`, `time`, `date-time`, `uuid` to a `RegExp`), `TYPES`, `UNCHECKED`.
  - `jsonLanguage.diagnose(text, data)` now returns the syntax error, or the schema checks.
  - Messages: `missing required key "<name>"` (error, on the object's `{`), `expected <type>[ or <type>…]` (error),
    `not one of <up to 5 values as JSON>[, …]` (error), `longer than <n> characters` (error),
    `unknown key "<key>"` (warning, on the key), `not a <format>, such as <example>` (warning).
  - `EditorCoreTest.TASK`: the schema the later completion tests use.

- [ ] **Step 1: Write the failing tests**

In `DCT/EditorCoreTest.java`, add right after the line `class EditorCoreTest {`:

```java

    /** The schema most tests check against, as a panel sends one: a Task, with every keyword the editor reads. */
    private static final String TASK = """
            {"type": "object", "required": ["title", "status"],
             "properties": {
               "id": {"type": "integer", "readOnly": true, "description": "column id"},
               "title": {"type": "string", "maxLength": 5, "description": "column title"},
               "status": {"type": "string", "enum": ["OPEN", "DONE"]},
               "due": {"type": "string", "format": "date"},
               "at": {"type": "string", "format": "time"},
               "when": {"type": "string", "format": "date-time"},
               "ref": {"type": "string", "format": "uuid"},
               "points": {"type": ["integer", "null"]},
               "done": {"type": "boolean"},
               "tags": {"type": "array", "items": {"type": "string"}},
               "pair": {"type": "array", "items": [{"type": "integer"}, {"type": "string"}]},
               "owner": {"$ref": "#/$defs/owner"},
               "extra": {"$ref": "other.json#/x"},
               "any": {"anyOf": [{"type": "string"}]}},
             "$defs": {"owner": {"type": "object", "required": ["name"],
               "properties": {"name": {"type": "string"}, "age": {"type": "integer"}},
               "additionalProperties": false}}}
            """;
```

and add before the class's final `}`:

```java

    @Test
    void aMissingRequiredKeyIsAnErrorOnItsObjectsOpeningBrace() {
        assertEquals("error 0-1 missing required key \"status\"", diagnose("{\"title\": \"a\"}", TASK));
    }

    @Test
    void aValueOfTheWrongTypeIsAnErrorOnTheValue() {
        assertEquals("error 10-11 expected string\nerror 41-44 expected integer or null",
                diagnose("{\"title\": 1, \"status\": \"OPEN\", \"points\": 1.5}", TASK));
        assertEquals("error 56-60 expected boolean",
                diagnose("{\"title\": \"a\", \"status\": \"OPEN\", \"points\": 2.0, \"done\": null}", TASK),
                "2.0 is an integer, null is one of the types listed");
    }

    @Test
    void aValueOutsideItsEnumIsAnError() {
        assertEquals("error 25-31 not one of \"OPEN\", \"DONE\"",
                diagnose("{\"title\": \"a\", \"status\": \"LATE\"}", TASK));
    }

    @Test
    void aStringLongerThanMaxLengthIsAnErrorCountingCharactersNotUnits() {
        assertEquals("error 10-18 longer than 5 characters",
                diagnose("{\"title\": \"abcdef\", \"status\": \"OPEN\"}", TASK));
        assertEquals("", diagnose("{\"title\": \"" + EMOJI.repeat(5) + "\", \"status\": \"OPEN\"}", TASK));
    }

    @Test
    void aKeyTheSchemaDoesNotListIsAWarningOnTheKey() {
        assertEquals("warning 33-41 unknown key \"colour\"",
                diagnose("{\"title\": \"a\", \"status\": \"OPEN\", \"colour\": 1}", TASK));
        assertEquals("", diagnose("{\"b\": 1}",
                "{\"type\": \"object\", \"properties\": {\"a\": {}}, \"additionalProperties\": true}"));
        assertEquals("error 6-9 expected integer", diagnose("{\"b\": \"x\"}", "{\"type\": \"object\", "
                + "\"properties\": {\"a\": {}}, \"additionalProperties\": {\"type\": \"integer\"}}"));
        assertEquals("", diagnose("{\"b\": 1}",
                "{\"type\": \"object\", \"properties\": {\"a\": {}}, \"patternProperties\": {\"^b\": {}}}"));
    }

    @Test
    void aStringThatDoesNotLookLikeItsFormatIsAWarning() {
        assertEquals("warning 40-52 not a date, such as 2026-09-30\n"
                + "warning 77-95 not a date-time, such as 2026-09-30T14:30:00\n"
                + "warning 104-109 not a uuid, such as 123e4567-e89b-12d3-a456-426614174000",
                diagnose("{\"title\": \"a\", \"status\": \"OPEN\", \"due\": \"30/09/2026\", \"at\": \"14:30\", "
                        + "\"when\": \"2026-09-30 14:30\", \"ref\": \"123\"}", TASK));
    }

    @Test
    void theChecksFollowItemsTheirArrayFormAndALocalRefAtDepth() {
        assertEquals("error 47-48 expected string\nerror 63-64 expected string\n"
                + "error 82-83 missing required key \"name\"\nwarning 83-89 unknown key \"nick\"",
                diagnose("{\"title\": \"a\", \"status\": \"OPEN\", \"tags\": [\"x\", 2], \"pair\": [1, 2, true], "
                        + "\"owner\": {\"nick\": \"y\"}}", TASK));
    }

    @Test
    void aSelfReferencingSchemaIsCheckedAtEveryDepth() {
        assertEquals("error 34-37 expected integer", diagnose("{\"children\": [{\"children\": [{\"n\": \"x\"}]}]}",
                "{\"$ref\": \"#/$defs/node\", \"$defs\": {\"node\": {\"type\": \"object\", \"properties\": {"
                        + "\"children\": {\"type\": \"array\", \"items\": {\"$ref\": \"#/$defs/node\"}}, "
                        + "\"n\": {\"type\": \"integer\"}}}}}"));
    }

    @Test
    void anotherRefIsNotFollowedAndNothingUnderAnyOfIsChecked() {
        assertEquals("", diagnose("{\"title\": \"a\", \"status\": \"OPEN\", \"extra\": 5, \"any\": 5}", TASK));
    }

    @Test
    void anOddSchemaNeverThrowsItChecksLess() {
        assertEquals("", diagnose("{\"x\": 1}", "{\"$ref\": \"#/$defs/a\", \"$defs\": {"
                + "\"a\": {\"$ref\": \"#/$defs/b\"}, \"b\": {\"$ref\": \"#/$defs/a\"}}}"), "a $ref cycle");
        assertEquals("", diagnose("{\"x\": 1}", "{\"type\": \"object\", \"properties\": 5, \"required\": \"x\"}"));
        assertEquals("", diagnose("\"abcdef\"", "{\"type\": 7, \"enum\": \"x\", \"maxLength\": \"3\", \"format\": 5}"));
        assertEquals("", diagnose("{\"x\": 1}", "\"not a schema\""));
        assertEquals("", diagnose("{\"a\": 1}", "{\"properties\": {\"a\": {\"type\": \"strng\"}}}"),
                "a type the editor does not know");
        assertEquals("error 0-1 missing required key \"b\"",
                diagnose("{\"a\": 1}", "{\"properties\": {\"a\": {}}, \"required\": [\"b\"]}"),
                "a required key that properties does not list is still required");
    }

    @Test
    void aKeyNamedProtoIsAKeyLikeAnyOther() {
        String schema = "{\"type\": \"object\", \"required\": [\"__proto__\"], "
                + "\"properties\": {\"__proto__\": {\"type\": \"integer\"}}}";

        assertEquals("error 14-17 expected integer", diagnose("{\"__proto__\": \"x\"}", schema));
        assertEquals("error 0-1 missing required key \"__proto__\"", diagnose("{}", schema));
        assertEquals("", diagnose("{\"__proto__\": 1}", schema));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -q -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=EditorCoreTest
```
Expected: FAIL — 10 of the 11 new tests fail on `expected: <error …> but was: <>` (no schema check yet);
`anotherRefIsNotFollowedAndNothingUnderAnyOfIsChecked` already passes, since nothing is checked yet.

- [ ] **Step 3: Write the schema section**

In `PAGE/editor-core.js`, insert right before the line
`// ------------------------------------------------------------------------------------------------ the language`:

```js
// ------------------------------------------------------------------------------------------------ schema

/** The JSON Schema types a value can have: a type the editor does not know is not checked. */
const TYPES = new Set(["object", "array", "string", "number", "integer", "boolean", "null"]);
/** The keywords whose subschemas are never checked: a value under them is accepted as it is. */
const UNCHECKED = ["anyOf", "oneOf", "allOf", "not", "patternProperties"];
/** How many $ref one resolution follows before it gives up: a cycle of references ends there. */
const MAX_REFS = 32;
/** An ISO date, and a time of day with or without seconds and offset, as the formats below read them. */
const DATE = "\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])";
const TIME = "([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d(\\.\\d+)?)?([Zz]|[+-]([01]\\d|2[0-3]):[0-5]\\d)?";
/** What a string of each format looks like: a warning, never a refusal, since the server stays the judge. */
const FORMAT_PATTERNS = new Map([
  ["date", new RegExp("^" + DATE + "$")],
  ["time", new RegExp("^" + TIME + "$")],
  ["date-time", new RegExp("^" + DATE + "[Tt]" + TIME + "$")],
  ["uuid", /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/],
]);

/**
 * {@code schema} with its local $ref followed, or null when it is no object, its $ref is not a local one (it is then
 * not checked), or a chain of references runs past MAX_REFS, as a cycle does. {@code root} is what "#" names.
 */
function resolve(schema, root) {
  let s = schema;
  for (let refs = 0; isObject(s) && Object.hasOwn(s, "$ref"); refs++) {
    if (refs === MAX_REFS || typeof s.$ref !== "string") return null;
    s = pointer(root, s.$ref);
  }
  return isObject(s) ? s : null;
}

/** The schema a local $ref names under #/$defs/ or #/definitions/, or undefined for any other reference. */
function pointer(root, ref) {
  if (!ref.startsWith("#/$defs/") && !ref.startsWith("#/definitions/")) return undefined;
  let s = root;
  for (const part of ref.slice(2).split("/")) {
    const name = part.replaceAll("~1", "/").replaceAll("~0", "~");
    if (!isObject(s) || !Object.hasOwn(s, name)) return undefined;
    s = s[name];
  }
  return s;
}

/** The schema of member {@code key} of an object of schema {@code s}, unresolved; null when it has none. */
function memberSchema(s, key) {
  if (isObject(s.properties) && Object.hasOwn(s.properties, key)) return s.properties[key];
  return isObject(s.additionalProperties) ? s.additionalProperties : null;
}

/** The schema of item {@code i} of an array of schema {@code s}, unresolved; null when it has none. */
function itemSchema(s, i) {
  if (Array.isArray(s.items)) return i < s.items.length ? s.items[i] : null;
  return isObject(s.items) ? s.items : null;
}

/** The types {@code s} allows that the editor knows, or null when it says none it knows. */
function typesOf(s) {
  const named = typeof s.type === "string" ? [s.type] : Array.isArray(s.type) ? s.type : [];
  const known = named.filter((type) => typeof type === "string" && TYPES.has(type));
  return known.length ? known : null;
}

/** Whether the parsed {@code node} is of JSON Schema type {@code type}; integer refuses 1.5, not 1.0. */
function hasType(node, type) {
  switch (type) {
    case "object":
    case "array":
    case "string":
    case "number":
      return node.kind === type;
    case "integer":
      return node.kind === "number" && Number.isInteger(node.value);
    case "boolean":
      return node.kind === "literal" && typeof node.value === "boolean";
    default:
      return node.kind === "literal" && node.value === null;
  }
}

/** Whether the parsed {@code node} equals the JSON value {@code v}. */
function same(node, v) {
  switch (node.kind) {
    case "object":
      return isObject(v) && Object.keys(v).length === new Set(node.members.map((m) => m.key)).size
        && node.members.every((m) => Object.hasOwn(v, m.key) && same(m.value, v[m.key]));
    case "array":
      return Array.isArray(v) && v.length === node.items.length && node.items.every((item, i) => same(item, v[i]));
    default:
      return node.value === v;
  }
}

/** The values of an enum as a message lists them: the first five, as JSON. */
const listed = (values) => values.slice(0, 5).map((v) => JSON.stringify(v)).join(", ")
  + (values.length > 5 ? ", …" : "");

/**
 * The schema checks of the parsed {@code value} against {@code schema}, at every depth, in the order of the text. A
 * value of the wrong type is checked no further. An object or an array is marked on its opening bracket, so that a
 * tooltip does not cover all it holds. Its own stack, as the parser.
 */
function check(value, schema) {
  const out = [];
  const diagnostic = (from, to, severity, message) => out.push({ from, to, severity, message });
  const work = [[value, resolve(schema, schema)]];
  while (work.length) {
    const [node, s] = work.pop();
    if (s === null) continue;
    const container = node.kind === "object" || node.kind === "array";
    const from = node.from;
    const to = container ? node.from + 1 : node.to;
    const types = typesOf(s);
    if (types && !types.some((type) => hasType(node, type))) {
      diagnostic(from, to, "error", "expected " + types.join(" or "));
      continue;
    }
    if (Array.isArray(s.enum) && s.enum.length && !s.enum.some((v) => same(node, v))) {
      diagnostic(from, to, "error", "not one of " + listed(s.enum));
      continue;
    }
    if (node.kind === "string") {
      if (typeof s.maxLength === "number" && [...node.value].length > s.maxLength) {
        diagnostic(from, to, "error", "longer than " + s.maxLength + " characters");
      }
      const format = typeof s.format === "string" ? FORMAT_PATTERNS.get(s.format) : undefined;
      if (format && !format.test(node.value)) {
        diagnostic(from, to, "warning", "not a " + s.format + ", such as " + FORMAT_EXAMPLES.get(s.format));
      }
    } else if (node.kind === "object") {
      const keys = new Set(node.members.map((m) => m.key));
      for (const name of Array.isArray(s.required) ? s.required : []) {
        if (typeof name === "string" && !keys.has(name)) {
          diagnostic(from, to, "error", "missing required key " + JSON.stringify(name));
        }
      }
      const properties = isObject(s.properties) ? s.properties : null;
      const closed = properties !== null && Object.keys(properties).length > 0 && s.additionalProperties !== true
        && !isObject(s.additionalProperties) && !UNCHECKED.some((keyword) => Object.hasOwn(s, keyword));
      for (const m of node.members) {
        if (closed && !Object.hasOwn(properties, m.key)) {
          diagnostic(m.keyFrom, m.keyTo, "warning", "unknown key " + JSON.stringify(m.key));
        }
        work.push([m.value, resolve(memberSchema(s, m.key), schema)]);
      }
    } else if (node.kind === "array") {
      node.items.forEach((item, i) => work.push([item, resolve(itemSchema(s, i), schema)]));
    }
  }
  return out.sort((a, b) => a.from - b.from || a.to - b.to);
}

```

Then, in the language block, replace:
```js
  diagnose(text, data) {
    const { error } = parse(text, lex(text));
    return error ? [error] : [];
  },
```
with:
```js
  diagnose(text, data) {
    const { value, error } = parse(text, lex(text));
    return error ? [error] : check(value, data);
  },
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: the command of Step 2.
Expected: exit status 0 (21 tests), no `[ERROR]` line.

- [ ] **Step 5: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(devconsole): the JSON editor checks the argument's schema at every depth

When the text parses, editor-core.js checks it against the argument's JSON Schema, following properties, items
(a schema or the array form), additionalProperties and a local $ref (a cycle ends after 32): a missing required
key, a wrong type, a value outside enum, a string past maxLength are errors; an unlisted key and a string that does
not look like its date, time, date-time or uuid format are warnings. Nothing under anyOf, oneOf, allOf, not or
patternProperties is checked, and an odd schema makes it check less, never throw.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/editor-core.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/EditorCoreTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 4: Completion of keys and values

**Files:**
- Modify: `PAGE/editor-core.js` (a `completion` section before `// ---… the language`; the language block)
- Test: `DCT/EditorCoreTest.java` (a `completion` section)

**Interfaces:**
- Consumes (Tasks 1-3): `lex`, `punctAt`, `isClosed`, `isStringToken`, `stringValue`, `resolve`, `memberSchema`,
  `itemSchema`, `typesOf`, `isObject`.
- Produces:
  - `jsonLanguage.complete(text, caret, data) → { from, to, items: [{ insert, label, detail, kind, caret? }] } | null`;
    `kind` is `"key"` or `"value"`; `caret` an offset into `insert` (absent: after it). Task 7's editor opens the list
    by itself after a `"` only when `items[0].kind === "key"`.
  - internal: `contextAt(text, tokens, caret, data) → { position: "key" | "value" | null, schema, keys, current }`,
    `touches(text, t, caret)`, `kindOf(p)`, `typesFor(p)`, `valueStart(p, root, depth) → { text, caret? }`,
    `objectStart(s, root)`, `keyItems(s, present, keyOnly, root)`, `valueItems(p, root)`.
  - `EditorCoreTest.complete(marked, schemaJson)` (`from-to`, then each insert, `|` where its caret lands, or `null`),
    `details(marked, schemaJson)` (`label: detail` lines), `completion(marked, schemaJson) → Value`.

- [ ] **Step 1: Write the failing tests**

In `DCT/EditorCoreTest.java`, add before the class's final `}`:

```java

    // ------------------------------------------------------------------------------------------------ completion

    /**
     * The completion where {@code marked} has its |: "from-to", then each item's insert on a line of its own, a |
     * where the caret lands inside it; "null" when there is none.
     */
    private static String complete(String marked, String schema) {
        Value found = completion(marked, schema);
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
        return String.join("\n", out);
    }

    /** The items of that completion as label: detail, one per line. */
    private static String details(String marked, String schema) {
        Value items = completion(marked, schema).getMember("items");
        List<String> out = new ArrayList<>();
        for (long i = 0; i < items.getArraySize(); i++) {
            Value item = items.getArrayElement(i);
            out.add(item.getMember("label").asString() + ": " + item.getMember("detail").asString());
        }
        return String.join("\n", out);
    }

    private static Value completion(String marked, String schema) {
        int caret = marked.indexOf('|');
        return language.invokeMember("complete", marked.substring(0, caret) + marked.substring(caret + 1), caret,
                parsed(schema));
    }

    @Test
    void aKeyPositionOffersTheMissingKeysRequiredFirstReadOnlyLast() {
        assertEquals("""
                15-15
                "title": "|"
                "status": "OPEN"
                "due": "|"
                "at": "|"
                "when": "|"
                "ref": "|"
                "points": 0
                "tags": [|]
                "pair": [|]
                "owner": {"name": "|"}
                "extra": null
                "any": null
                "id": 0""", complete("{\"done\": true, |}", TASK));
    }

    @Test
    void eachKeyItemSaysItsTypeWhetherItIsRequiredOrGeneratedAndItsDescription() {
        assertEquals("""
                title: string, required — column title
                status: enum, required
                due: string
                at: string
                when: string
                ref: string
                points: integer | null
                done: boolean
                tags: array
                pair: array
                owner: object
                extra: any
                any: any
                id: integer, generated — column id""", details("{|}", TASK));
    }

    @Test
    void theKeysAfterTheCaretAreWrittenToo() {
        assertEquals("1-1\n\"status\": \"OPEN\"", complete("{|\n  \"title\": \"a\", \"due\": \"\", \"at\": \"\", "
                + "\"when\": \"\", \"ref\": \"\", \"points\": 1, \"done\": true, \"tags\": [], \"pair\": [], "
                + "\"owner\": {}, \"extra\": 1, \"any\": 1, \"id\": 1\n}", TASK));
    }

    @Test
    void aPartlyTypedKeyIsReplacedWithItsQuotesIgnoringCase() {
        assertEquals("1-5\n\"title\": \"|\"", complete("{\"ti|\"}", TASK));
        assertEquals("1-5\n\"title\": \"|\"", complete("{\"TI|\"}", TASK));
        assertEquals("1-3\n\"title\": \"|\"", complete("{ti|}", TASK), "typed without its quotes");
        assertEquals("1-5\n\"title\"", complete("{\"ti|\": \"x\"}", TASK), "a key already followed by ':' alone");
    }

    @Test
    void aKeyWithNoClosingQuoteIsReplacedUpToTheCaretOnly() {
        assertEquals("10-13\n\"due\": \"|\"", complete("{\"id\": 1, \"du|\n\"title\": \"a\"}", TASK));
    }

    @Test
    void theKeysOfANestedObjectComeFromItsSchema() {
        assertEquals("11-11\n\"name\": \"|\"\n\"age\": 0", complete("{\"owner\": {|}}", TASK));
    }

    @Test
    void aValuePositionOffersTheEnumBooleansNullAndContainers() {
        assertEquals("11-11\n\"OPEN\"\n\"DONE\"", complete("{\"status\": |}", TASK));
        assertEquals("9-9\ntrue\nfalse", complete("{\"done\": |}", TASK));
        assertEquals("11-11\nnull", complete("{\"points\": |}", TASK));
        assertEquals("10-10\n{\"name\": \"|\"}", complete("{\"owner\": |}", TASK));
        assertEquals("9-9\n[|]", complete("{\"tags\": |}", TASK));
        assertEquals("11-14\n\"DONE\"", complete("{\"status\": \"d|\"}", TASK), "filtered by what is typed");
        assertEquals("9-10\ntrue", complete("{\"done\": t|}", TASK));
    }

    @Test
    void theRootValueStartsWithTheRequiredKeys() {
        assertEquals("0-0\n{\"title\": \"|\", \"status\": \"OPEN\"}", complete("|", TASK));
    }

    @Test
    void nothingElsewhereNorWithoutASchemaForThePlace() {
        assertEquals("null", complete("{\"title\": \"a\"|}", TASK), "after a value");
        assertEquals("null", complete("{\"title\": \"a|\"}", TASK), "a string that is no enum");
        assertEquals("null", complete("{\"tags\": [|]}", TASK), "an item that is a string");
        assertEquals("null", complete("{\"colour\": |}", TASK), "a key the schema does not list");
        assertEquals("null", complete("{|}", null), "no schema");
        assertEquals("null", complete("{\"a\": 1}|", TASK), "after the root value");
    }

    @Test
    void completionOffsetsSurviveEmojiAndCrlf() {
        assertEquals("10-14\n\"title\": \"|\"", complete("{\"" + EMOJI + "\": 1, \"ti|\"}", TASK));
        assertEquals("5-9\n\"title\": \"|\"", complete("{\r\n  \"ti|\"\r\n}", TASK));
    }

    @Test
    void aKeyNamedProtoIsOfferedLikeAnyOther() {
        assertEquals("1-1\n\"__proto__\": 0",
                complete("{|}", "{\"type\": \"object\", \"properties\": {\"__proto__\": {\"type\": \"integer\"}}}"));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -q -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=EditorCoreTest
```
Expected: FAIL — the 11 new tests error with `java.lang.UnsupportedOperationException: Non readable or non-existent
member key 'complete'`; the 21 earlier tests pass.

- [ ] **Step 3: Write the completion section**

In `PAGE/editor-core.js`, insert right before the line
`// ------------------------------------------------------------------------------------------------ the language`:

```js
// ------------------------------------------------------------------------------------------------ completion

/**
 * Where {@code caret} is (spec §3.3), from the tokens before it: { position: "key" | "value" | null, schema, keys,
 * current }. schema is the resolved schema of the object whose key is typed, or of the value typed; keys, the keys
 * that object already has, before and after the caret, the one being typed excepted; current, the token the caret
 * is in, being typed, or null. Unlike the parser, it goes on past an error: a text being typed rarely parses.
 */
function contextAt(text, tokens, caret, data) {
  const stack = [];
  let rootDone = false;
  let found = null;
  const valueSchema = (top) => top === undefined ? resolve(data, data)
    : top.schema === null ? null
    : resolve(top.kind === "object" ? memberSchema(top.schema, top.key) : itemSchema(top.schema, top.count), data);
  const here = () => {
    const top = stack[stack.length - 1];
    if (!top) return { frame: null, position: rootDone ? null : "value", schema: resolve(data, data) };
    if (top.kind === "object" && (top.expect === "first" || top.expect === "key")) {
      return { frame: top, position: "key", schema: top.schema };
    }
    if ((top.kind === "object" && top.expect === "value") || (top.kind === "array" && top.expect !== "comma")) {
      return { frame: top, position: "value", schema: valueSchema(top) };
    }
    return { frame: top, position: null, schema: null };
  };
  const step = (t) => {
    const top = stack[stack.length - 1];
    const c = punctAt(text, t);
    if (c === "{" || c === "[") {
      const schema = valueSchema(top);
      if (top) {
        top.count++;
        top.expect = "comma";
      }
      stack.push({ kind: c === "{" ? "object" : "array", schema, keys: new Set(), expect: "first", key: null,
        count: 0 });
    } else if (c === "}" || c === "]") {
      const kind = c === "}" ? "object" : "array";
      for (let i = stack.length - 1; i >= 0; i--) {
        if (stack[i].kind === kind) {
          stack.length = i;
          if (i === 0) rootDone = true;
          break;
        }
      }
    } else if (!top) {
      rootDone = true;
    } else if (c === ",") {
      top.expect = top.kind === "object" ? "key" : "value";
    } else if (c === ":") {
      if (top.kind === "object") top.expect = "value";
    } else if (top.kind === "object" && top.expect !== "colon" && top.expect !== "value" && isStringToken(t)) {
      const key = stringValue(text, t);
      top.key = key !== undefined ? key : text.slice(t.from + 1, t.to);
      top.keys.add(top.key);
      top.expect = "colon";
    } else if (top.kind === "array" || top.expect === "value" || top.expect === "colon") {
      top.count++;
      top.expect = "comma";
    }
  };
  let current = null;
  for (const t of tokens) {
    if (found === null && (t.from >= caret || touches(text, t, caret))) {
      found = here();
      if (t.from < caret) current = t;
    }
    if (found !== null && found.frame !== null && !stack.includes(found.frame)) break;
    if (found !== null && found.frame === null) break;
    if (t === current && found.position === "key") continue;
    step(t);
  }
  if (found === null) found = here();
  return { position: found.position, schema: found.schema,
    keys: found.frame !== null ? found.frame.keys : new Set(), current };
}

/** Whether the caret is in token {@code t}, typing it: inside a closed string, at the end of an open one or a word. */
const touches = (text, t, caret) => t.kind !== "punct" && t.from < caret
  && (caret < t.to || (caret === t.to && (!isStringToken(t) || !isClosed(text, t))));

/** What a completion says of a schema's type: string, integer, enum, object, string | null …, or any. */
function kindOf(p) {
  if (p === null) return "any";
  if (Array.isArray(p.enum) && p.enum.length) return "enum";
  const types = typesOf(p);
  return types ? types.join(" | ") : isObject(p.properties) ? "object" : "any";
}

/** The types a value of schema {@code p} may take, an object assumed when it lists properties and no type. */
const typesFor = (p) => typesOf(p) || (isObject(p.properties) ? ["object"] : []);

/**
 * The start of a value of schema {@code p}: { text, caret }, caret an offset into text, or undefined for after it.
 * "" with the caret inside, 0, false, null, the first enum value, [] or {} with the caret inside; at the first
 * level, an object whose schema has required keys starts with them.
 */
function valueStart(p, root, depth) {
  if (p === null) return { text: "null" };
  if (Array.isArray(p.enum) && p.enum.length) return { text: JSON.stringify(p.enum[0]) };
  const types = typesFor(p);
  switch (types.find((type) => type !== "null") || types[0]) {
    case "string":
      return { text: "\"\"", caret: 1 };
    case "number":
    case "integer":
      return { text: "0" };
    case "boolean":
      return { text: "false" };
    case "array":
      return { text: "[]", caret: 1 };
    case "object":
      return depth === 0 ? objectStart(p, root) : { text: "{}", caret: 1 };
    default:
      return { text: "null" };
  }
}

/** An object of schema {@code s} with its required keys, the caret in or after the first one's value; else {}. */
function objectStart(s, root) {
  const required = Array.isArray(s.required) ? s.required.filter((name) => typeof name === "string") : [];
  if (!required.length) return { text: "{}", caret: 1 };
  const properties = isObject(s.properties) ? s.properties : {};
  let text = "{";
  let caret;
  for (const name of required) {
    if (text.length > 1) text += ", ";
    text += JSON.stringify(name) + ": ";
    const start = valueStart(resolve(Object.hasOwn(properties, name) ? properties[name] : null, root), root, 1);
    if (caret === undefined) caret = text.length + (start.caret !== undefined ? start.caret : start.text.length);
    text += start.text;
  }
  return { text: text + "}", caret };
}

/**
 * The keys of object schema {@code s} not in {@code present}: required first, then the others, then the read-only
 * ones, each group in schema order. {@code keyOnly}: the key is already followed by ":", only it is replaced.
 */
function keyItems(s, present, keyOnly, root) {
  const properties = isObject(s.properties) ? s.properties : {};
  const required = new Set(Array.isArray(s.required) ? s.required.filter((name) => typeof name === "string") : []);
  const names = [...Object.keys(properties), ...[...required].filter((name) => !Object.hasOwn(properties, name))];
  const ranked = [];
  for (const name of names) {
    if (present.has(name)) continue;
    const p = resolve(Object.hasOwn(properties, name) ? properties[name] : null, root);
    const readOnly = p !== null && p.readOnly === true;
    const mandatory = required.has(name) && !readOnly;
    const description = p !== null && typeof p.description === "string" && p.description ? " — " + p.description : "";
    const key = JSON.stringify(name);
    const item = { insert: key, label: name, kind: "key",
      detail: [kindOf(p), ...(mandatory ? ["required"] : []), ...(readOnly ? ["generated"] : [])].join(", ")
        + description };
    if (!keyOnly) {
      const start = valueStart(p, root, 0);
      item.insert = key + ": " + start.text;
      if (start.caret !== undefined) item.caret = key.length + 2 + start.caret;
    }
    ranked.push({ rank: readOnly ? 2 : mandatory ? 0 : 1, item });
  }
  return ranked.sort((a, b) => a.rank - b.rank).map((r) => r.item);
}

/** The values a value of schema {@code p} may start with: its enum, true and false, null, then {} or []. */
function valueItems(p, root) {
  const items = [];
  const add = (insert, detail, caret) => {
    if (items.some((item) => item.insert === insert)) return;
    items.push(caret === undefined ? { insert, label: insert, detail, kind: "value" }
      : { insert, label: insert, detail, kind: "value", caret });
  };
  if (Array.isArray(p.enum)) for (const v of p.enum) add(JSON.stringify(v), "enum");
  const types = typesFor(p);
  if (types.includes("boolean")) {
    add("true", "boolean");
    add("false", "boolean");
  }
  if (types.includes("null")) add("null", "null");
  if (types.includes("object")) {
    const start = objectStart(p, root);
    add(start.text, "object", start.caret);
  }
  if (types.includes("array")) add("[]", "array", 1);
  return items;
}

/**
 * The completion at {@code caret} (spec §3.3): { from, to, items }, from-to what is already typed, a key with its
 * quotes (a string with no closing quote up to the caret only, since it runs to the end of its line), items filtered
 * by it ignoring case; null anywhere else, with no schema for the place, or with no item left.
 */
function complete(text, caret, data) {
  const at = contextAt(text, lex(text), caret, data);
  if (at.position === null || at.schema === null) return null;
  const t = at.current;
  const from = t ? t.from : caret;
  const to = t && (!isStringToken(t) || isClosed(text, t)) ? t.to : caret;
  if (at.position === "key") {
    const prefix = t ? text.slice(t.from + (isStringToken(t) ? 1 : 0), caret).toLowerCase() : "";
    const items = keyItems(at.schema, at.keys, t !== null && t.kind === "key", data)
      .filter((item) => item.label.toLowerCase().startsWith(prefix));
    return items.length ? { from, to, items } : null;
  }
  const prefix = t ? text.slice(t.from, caret).toLowerCase() : "";
  const items = valueItems(at.schema, data).filter((item) => item.insert.toLowerCase().startsWith(prefix));
  return items.length ? { from, to, items } : null;
}

```

Then, in the language block, replace:
```js
    return error ? [error] : check(value, data);
  },
  pairs: Object.freeze(["{}", "[]", "()", "\"\""]),
```
with:
```js
    return error ? [error] : check(value, data);
  },
  complete,
  pairs: Object.freeze(["{}", "[]", "()", "\"\""]),
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: the command of Step 2.
Expected: exit status 0 (32 tests), no `[ERROR]` line.

- [ ] **Step 5: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(devconsole): the JSON editor completes keys and values from the schema

complete() reads, from the tokens before the caret, whether it is in a key or a value and the schema of the
object or array it is in. A key position offers the keys the object does not have yet, before and after the
caret, required first and read-only last, each with its type, required or generated, and description, its value
started ("" with the caret inside, 0, false, the first enum value, an object with its required keys). A value
position offers the enum, true and false, null, {} or []. What is typed filters, ignoring case.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/editor-core.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/EditorCoreTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 5: Formatting that keeps numbers and strings as written

**Files:**
- Modify: `PAGE/editor-core.js` (a `formatting` section before `// ---… the language`; the language block)
- Test: `DCT/EditorCoreTest.java` (a `formatting` section)

**Interfaces:**
- Consumes (Tasks 1-2): `lex`, `parse`, `punctAt`, `INDENT`.
- Produces: `lineOf(text, offset) → number` (from 1); `jsonLanguage.format(text) → string`, or throws
  `Error("line <n>: <syntax message>")`. `EditorCoreTest.format(text) → String`.

- [ ] **Step 1: Write the failing tests**

In `DCT/EditorCoreTest.java`, add before the class's final `}`:

```java

    // ------------------------------------------------------------------------------------------------ formatting

    private static String format(String text) {
        return language.invokeMember("format", text).asString();
    }

    @Test
    void formatIndentsTwoSpacesOneMemberPerLine() {
        assertEquals("""
                {
                  "a": [
                    1,
                    {
                      "b": null
                    }
                  ],
                  "c": true
                }""", format("{\"a\":[1,{\"b\":null}],\"c\":true}"));
    }

    @Test
    void anEmptyObjectOrArrayStaysOnOneLine() {
        assertEquals("{\n  \"a\": [],\n  \"b\": {}\n}", format("{ \"a\" : [ ] , \"b\" : {\r\n} }"));
    }

    @Test
    void numbersAndStringsAreCopiedAsWritten() {
        assertEquals("{\n  \"id\": 12345678901234567890,\n  \"name\": \"\\u00e9\",\n  \"x\": 1.50e+3\n}",
                format("{\"id\":12345678901234567890,\"name\":\"\\u00e9\",\"x\":1.50e+3}"));
    }

    @Test
    void formattingAFormattedTextChangesNothing() {
        String once = format("[{\"a\":{\"b\":[1,2]}},\"" + EMOJI + "\"]");

        assertEquals(once, format(once));
    }

    @Test
    void aTextWithASyntaxErrorIsNotFormattedAndSaysWhy() {
        PolyglotException thrown = assertThrows(PolyglotException.class, () -> format("{\n\"a\": 1,\n}"));

        assertTrue(thrown.isGuestException(), "an Error thrown by the module");
        assertEquals("Error: line 2: trailing comma", thrown.getMessage());
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -q -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=EditorCoreTest
```
Expected: FAIL — the 5 new tests fail with `java.lang.UnsupportedOperationException: Non readable or non-existent
member key 'format'` (the last one as `Unexpected exception type thrown`); the 32 earlier tests pass.

- [ ] **Step 3: Write the formatting section**

In `PAGE/editor-core.js`, insert right before the line
`// ------------------------------------------------------------------------------------------------ the language`:

```js
// ------------------------------------------------------------------------------------------------ formatting

/** The line of {@code offset}, counted from 1. */
function lineOf(text, offset) {
  let line = 1;
  for (let i = text.indexOf("\n"); i >= 0 && i < offset; i = text.indexOf("\n", i + 1)) line++;
  return line;
}

/**
 * {@code text} re-indented from its tokens: two spaces, one member per line, an empty {} or [] kept on one line, the
 * text of every string and number copied as written. Throws, saying why, when the text does not parse.
 */
function format(text) {
  const tokens = lex(text);
  const { error } = parse(text, tokens);
  if (error) throw new Error("line " + lineOf(text, error.from) + ": " + error.message);
  let out = "";
  let depth = 0;
  for (let k = 0; k < tokens.length; k++) {
    const t = tokens[k];
    const c = punctAt(text, t);
    if (c === "{" || c === "[") {
      if (punctAt(text, tokens[k + 1]) === (c === "{" ? "}" : "]")) {
        out += c === "{" ? "{}" : "[]";
        k++;
      } else {
        depth++;
        out += c + "\n" + INDENT.repeat(depth);
      }
    } else if (c === "}" || c === "]") {
      depth--;
      out += "\n" + INDENT.repeat(depth) + c;
    } else if (c === ",") {
      out += ",\n" + INDENT.repeat(depth);
    } else if (c === ":") {
      out += ": ";
    } else {
      out += text.slice(t.from, t.to);
    }
  }
  return out;
}

```

Then, in the language block, replace:
```js
  complete,
  pairs: Object.freeze(["{}", "[]", "()", "\"\""]),
```
with:
```js
  complete,
  format,
  pairs: Object.freeze(["{}", "[]", "()", "\"\""]),
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: the command of Step 2.
Expected: exit status 0 (37 tests), no `[ERROR]` line.

- [ ] **Step 5: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(devconsole): the JSON editor formats from its tokens, numbers and strings as written

format() re-indents two spaces, one member per line, keeps an empty {} or [] on one line and copies every string
and number as written: 12345678901234567890 is not rounded, a \u00e9 escape stays. A text that does not parse is
not formatted: format() throws "line <n>: <why>".

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/editor-core.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/EditorCoreTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 6: Smart keystrokes

**Files:**
- Modify: `PAGE/editor-core.js` (a `keystrokes` section appended after the language block, at the end of the file)
- Test: `DCT/EditorCoreTest.java` (the `keystroke` export loaded; a `keystrokes` section)

**Interfaces:**
- Consumes (Task 1): `INDENT`, `isClosed`, `isStringToken`; a language's `tokenize` and `pairs`.
- Produces (Task 7 calls it on every key it handles):
  - `export function keystroke(language, text, selectionStart, selectionEnd, key)` →
    `{ from, to, insert, caret, anchor? } | null`: replace `from`-`to` with `insert`, put the caret at `from + caret`
    and, when `anchor` is present, select from `from + anchor` to it; an empty `insert` over an empty range only moves
    the caret. `null`: the browser types the key. Keys: an opening or closing character of `language.pairs`,
    `"Enter"`, `"Backspace"`, `"Tab"`, `"Shift+Tab"`.
  - internal: `lineStart(text, offset)`, `lineSpan(text, start, end)`, `stringAround(language, text, offset)`,
    `enter`, `backspace`, `indent`, `outdent`.
  - `EditorCoreTest.keystroke` (the export), `press(marked, key)`: one `|` the caret, two `|` a selection; the result
    marked the same way, or `"null"`.

- [ ] **Step 1: Write the failing tests**

In `DCT/EditorCoreTest.java`, replace:
```java
    private static Value language;
```
with:
```java
    private static Value language;
    private static Value keystroke;
```
and replace:
```java
        language = exports.getMember("jsonLanguage");
```
with:
```java
        language = exports.getMember("jsonLanguage");
        keystroke = exports.getMember("keystroke");
```

Then add before the class's final `}`:

```java

    // ------------------------------------------------------------------------------------------------ keystrokes

    /**
     * {@code marked} after {@code key}: one | is the caret, two | the ends of the selection, before and after;
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
        int low = Math.min(other, caret);
        int high = Math.max(other, caret);
        return next.substring(0, low) + "|" + next.substring(low, high) + "|" + next.substring(high);
    }

    @Test
    void anOpeningCharacterInsertsItsPairTheCaretBetween() {
        assertEquals("{|}", press("|", "{"));
        assertEquals("[1, [|]]", press("[1, |]", "["));
        assertEquals("(|)", press("|", "("));
        assertEquals("{\"a\": \"|\"}", press("{\"a\": |}", "\""));
    }

    @Test
    void anOpeningCharacterWrapsTheSelection() {
        assertEquals("[|abc|]", press("|abc|", "["));
        assertEquals("\"|abc|\"", press("|abc|", "\""));
    }

    @Test
    void aQuoteOpensAPairOnlyWhereAStringMayStart() {
        assertEquals("null", press("abc|", "\""), "after a letter");
        assertEquals("null", press("[1|]", "\""), "after a digit");
        assertEquals("null", press("{\"a|b\": 1}", "\""), "inside a string");
        assertEquals("null", press("{\"a|b\": 1}", "{"), "a bracket inside a string is text");
    }

    @Test
    void aClosingCharacterRightBeforeTheSameStepsOverIt() {
        assertEquals("{}|", press("{|}", "}"));
        assertEquals("[1]|", press("[1|]", "]"));
        assertEquals("{\"a\"|: 1}", press("{\"a|\": 1}", "\""));
        assertEquals("null", press("{|", "}"), "nothing to step over: the browser types it");
    }

    @Test
    void enterKeepsTheIndentationAndOpensALineBetweenBrackets() {
        assertEquals("{\n  \"a\": 1,\n  |\n}", press("{\n  \"a\": 1,|\n}", "Enter"));
        assertEquals("{\n  |\n}", press("{|}", "Enter"));
        assertEquals("  [\n    |\n  ]", press("  [|]", "Enter"));
        assertEquals("{\r\n  \"a\": 1,\n  |\r\n}", press("{\r\n  \"a\": 1,|\r\n}", "Enter"));
    }

    @Test
    void backspaceBetweenAnEmptyPairDeletesBoth() {
        assertEquals("|", press("{|}", "Backspace"));
        assertEquals("|", press("[|]", "Backspace"));
        assertEquals("|", press("(|)", "Backspace"));
        assertEquals("{\"a\": |}", press("{\"a\": \"|\"}", "Backspace"));
        assertEquals("null", press("\"a\\\"|\"", "Backspace"), "an escaped quote then the closing one");
        assertEquals("null", press("{|a}", "Backspace"));
    }

    @Test
    void tabInsertsTwoSpacesOrIndentsTheSelectedLines() {
        assertEquals("a  |", press("a|", "Tab"));
        assertEquals("|  a\n  b|\nc", press("|a\nb|\nc", "Tab"));
        assertEquals("|  a|\nb", press("|a\n|b", "Tab"), "a selection ending at a line start leaves that line");
    }

    @Test
    void shiftTabOutdentsTheLinesOrLetsTheBrowserMoveOn() {
        assertEquals("  a|", press("    a|", "Shift+Tab"));
        assertEquals("|a\nb|", press("|  a\n  b|", "Shift+Tab"));
        assertEquals("|a", press("  |a", "Shift+Tab"));
        assertEquals("| a", press(" |  a", "Shift+Tab"));
        assertEquals("null", press("a|", "Shift+Tab"));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -q -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=EditorCoreTest
```
Expected: FAIL — the 8 new tests error with a `NullPointerException` on `keystroke.execute(…)` (the module exports no
`keystroke` yet, so `getMember` gave `null`); the 37 earlier tests pass.

- [ ] **Step 3: Write the keystrokes section**

Append to the end of `PAGE/editor-core.js`, after the language block:

```js

// ------------------------------------------------------------------------------------------------ keystrokes

/** The offset where the line holding {@code offset} starts. */
const lineStart = (text, offset) => offset === 0 ? 0 : text.lastIndexOf("\n", offset - 1) + 1;

/** The lines {@code start}-{@code end} touch, from the start of the first to the end of the last. */
function lineSpan(text, start, end) {
  const last = end > start && text[end - 1] === "\n" ? end - 1 : end;
  const to = text.indexOf("\n", last);
  return { from: lineStart(text, start), to: to < 0 ? text.length : to };
}

/** The string token {@code offset} is inside of, its closing quote excluded, or null. */
function stringAround(language, text, offset) {
  for (const t of language.tokenize(text)) {
    if (t.from >= offset) return null;
    if (isStringToken(t) && (offset < t.to || (offset === t.to && !isClosed(text, t)))) return t;
  }
  return null;
}

/**
 * The edit {@code key} makes in {@code text}, whose selection is {@code selectionStart}-{@code selectionEnd} (spec
 * §3.5): { from, to, insert, caret }, caret an offset into insert as on a completion item, and anchor, when present,
 * the other end of the selection it leaves, an offset into insert too; an empty insert over an empty range only moves
 * the caret. null: the browser types the key itself. The keys: an opening or closing character of language.pairs,
 * "Enter", "Backspace", "Tab", "Shift+Tab".
 */
export function keystroke(language, text, selectionStart, selectionEnd, key) {
  const start = Math.min(selectionStart, selectionEnd);
  const end = Math.max(selectionStart, selectionEnd);
  if (key === "Enter") return enter(text, start, end);
  if (key === "Tab") {
    return start === end ? { from: start, to: start, insert: INDENT, caret: INDENT.length } : indent(text, start, end);
  }
  if (key === "Shift+Tab") return outdent(text, start, end);
  const pairs = Array.isArray(language.pairs) ? language.pairs : [];
  if (key === "Backspace") return start === end ? backspace(language, pairs, text, start) : null;
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

/** Enter: the line's indentation kept; between {} or [], an indented line and the closing character on the next. */
function enter(text, start, end) {
  const indentation = /^[ \t]*/.exec(text.slice(lineStart(text, start), start))[0];
  const before = text[start - 1];
  const after = text[end];
  if ((before === "{" && after === "}") || (before === "[" && after === "]")) {
    const opened = "\n" + indentation + INDENT;
    return { from: start, to: end, insert: opened + "\n" + indentation, caret: opened.length };
  }
  return { from: start, to: end, insert: "\n" + indentation, caret: 1 + indentation.length };
}

/** Backspace right between an empty pair of the language, an empty string's quotes included, deletes both. */
function backspace(language, pairs, text, offset) {
  if (offset === 0 || !pairs.includes(text.slice(offset - 1, offset + 1))) return null;
  if (text[offset] === "\"") {
    const empty = language.tokenize(text).find((t) => t.from === offset - 1);
    if (!empty || !isStringToken(empty) || empty.to !== offset + 1) return null;
  }
  return { from: offset - 1, to: offset + 1, insert: "", caret: 0 };
}

/** Tab over a selection: every line it touches indented, and selected. */
function indent(text, start, end) {
  const { from, to } = lineSpan(text, start, end);
  const insert = text.slice(from, to).split("\n").map((line) => INDENT + line).join("\n");
  return { from, to, insert, anchor: 0, caret: insert.length };
}

/** Shift+Tab: every line the selection or the caret touches outdented by one level; null when none can be. */
function outdent(text, start, end) {
  const { from, to } = lineSpan(text, start, end);
  const lines = text.slice(from, to).split("\n");
  const cuts = lines.map((line) => line.startsWith(INDENT) ? INDENT.length : /^[ \t]/.test(line) ? 1 : 0);
  if (cuts.every((cut) => cut === 0)) return null;
  const insert = lines.map((line, i) => line.slice(cuts[i])).join("\n");
  if (start === end) return { from, to, insert, caret: Math.max(0, start - from - cuts[0]) };
  return { from, to, insert, anchor: 0, caret: insert.length };
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -q -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=EditorCoreTest,PageTest
```
Expected: exit status 0 — `EditorCoreTest` 45 tests, `PageTest` green (the core still touches nothing of the page).

- [ ] **Step 5: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(devconsole): the editor's smart keystrokes, as pure edits

keystroke() returns the edit a key makes, or null to let the browser type it: an opening bracket or quote inserts
its pair (a quote only where a string may start) or wraps the selection, a closing one steps over the same,
Enter keeps the indentation and opens a line between {} or [], Backspace deletes an empty pair, Tab and
Shift+Tab indent and outdent by two spaces.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/editor-core.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/EditorCoreTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 7: The editor component, its style, and its page rules

**Files:**
- Create: `PAGE/editor.js`
- Modify: `PAGE/console.css` (the editor's rules, after `.action .json-editor { … }`)
- Test: `DCT/PageTest.java` (`FILES`; two tests), `DCT/DevConsoleExtensionTest.java` (the modules are served)

**Interfaces:**
- Consumes (Tasks 1-6): `jsonLanguage`, `keystroke`, `FORMAT_EXAMPLES` from `./editor-core.js`; a language's
  `tokenize`, `diagnose`, `complete`, `format`, `pairs`.
- Produces (Task 8 uses exactly these):
  - `editor.js` exports `createEditor({ language, data, value, rows, label })` →
    `{ root, textarea, value(), setValue(text), disable(on), focus() }`, and re-exports `jsonLanguage` and
    `FORMAT_EXAMPLES`.
  - CSS classes: `ed`, `ed-box`, `ed-pre`, `ed-text`, `ed-mirror`, `ed-line`, `ed-dot-error`, `ed-dot-warning`,
    `ed-key`, `ed-string`, `ed-number`, `ed-literal`, `ed-punct`, `ed-invalid`, `ed-error`, `ed-warning`, `ed-mark`,
    `ed-match`, `ed-plain`, `ed-disabled`, `ed-list`, `ed-item`, `ed-item-label`, `ed-item-detail`, `ed-tip`,
    `ed-tip-error`, `ed-tip-warning`, `ed-foot`, `ed-note`, `ed-bad`, `ed-format`; custom property `--ed-digits`.

- [ ] **Step 1: Write the failing tests**

In `DCT/PageTest.java`, replace:
```java
    private static final List<String> FILES = List.of("index.html", "console.css", "console.js", "favicon.svg",
            "editor-core.js");
```
with:
```java
    private static final List<String> FILES = List.of("index.html", "console.css", "console.js", "favicon.svg",
            "editor-core.js", "editor.js");
```
and add, right after the test `theEditorCoreTouchesNothingOfThePageSoThatGraalJsRunsIt`:

```java

    @Test
    void theEditorImportsTheCoreAndDrawsItsTextWithTextContent() {
        String editor = file("editor.js");

        assertEquals(List.of("./editor-core.js"), IMPORT.matcher(editor).results().map(m -> m.group(1)).toList());
        assertTrue(editor.contains("export function createEditor({ language, data, value, rows, label })"), "spec §4");
        assertTrue(editor.contains("span.textContent = text.slice(part.from, part.to);"), "the <pre>'s text, as text");
        assertTrue(editor.contains("if (frame === 0) frame = requestAnimationFrame("), "one draw per frame at most");
        assertTrue(editor.contains("const LIMIT = 100_000;"), "past 100 000 characters, a plain textarea");
        assertTrue(editor.contains("diagnostics = safely(() => language.diagnose(text, data), []);"),
                "a schema the language chokes on never breaks the page");
        assertTrue(editor.contains("document.execCommand(\"insertText\", false, insert)"), "Ctrl+Z undoes an edit");
        assertTrue(editor.contains("textarea.setRangeText(insert, from, to, \"end\");"),
                "where the browser refuses execCommand");
        assertTrue(editor.contains("event.key === \" \" && event.ctrlKey"), "Ctrl+Space opens the completion list");
        assertTrue(editor.contains("event.code === \"KeyF\" && event.shiftKey && event.altKey"), "Shift+Alt+F formats");
        assertTrue(editor.contains("if (event.isComposing || MODIFIERS.has(event.key)) return;"),
                "Shift pressed before Tab does not cancel the Escape of Escape then Shift+Tab");
        assertTrue(editor.contains("if (tab && leaving) return;"), "Escape then Tab leaves the editor");
        assertTrue(editor.contains("popup.style.left = Math.max(0, Math.min(point.left, box.clientWidth - width))"),
                "the completion list never past the editor's right edge");
        assertTrue(editor.contains("const up = frameTop + below + height > window.innerHeight && frameTop + above >= 0;"),
                "over the caret when the window has no room below");
        assertTrue(editor.contains("document.elementsFromPoint(x, y)"), "the tooltip of what the pointer is on");
        assertTrue(editor.contains("el(\"button\", \"ed-format\", \"Format\")"), "the Format button");
    }

    @Test
    void theEditorColoursItsTokensWithTheViewersColoursInEveryTheme() {
        String style = file("console.css");

        for (String kind : List.of("key", "string", "number", "literal", "punct")) {
            assertTrue(rule(style, ".ed-" + kind + " {").contains("var(--json-" + kind + ")"), "the viewer's " + kind);
        }
        assertTrue(rule(style, ".ed-invalid {").contains("var(--crit)"), "an invalid run");
        assertTrue(rule(style, ".ed-error {").contains("var(--crit)"), "an error, a red wavy underline");
        assertTrue(rule(style, ".ed-warning {").contains("var(--warn)"), "a warning, an orange one");
        for (String opening : List.of(":root {", ":root:not([data-theme=\"light\"]) {",
                ":root[data-theme=\"dark\"] {")) {
            String theme = rule(style, opening);
            assertTrue(theme.contains("--crit:") && theme.contains("--warn:"), "both colours in " + opening);
        }
        assertTrue(rule(style, ".ed-text {").contains("color: transparent"), "the textarea shows the <pre>'s text");
        assertTrue(rule(style, ".ed-pre, .ed-text, .ed-mirror {").contains("white-space: pre-wrap"),
                "the <pre>, the textarea and the caret's mirror wrap alike");
    }
```

In `DCT/DevConsoleExtensionTest.java`, in `theConsolesPageIsItsIndexWithItsScriptAndStyle`, replace:
```java
        assertEquals("no-cache", script.headers().firstValue("Cache-Control").orElse(null));
    }
```
with:
```java
        assertEquals("no-cache", script.headers().firstValue("Cache-Control").orElse(null));
        for (String module : List.of("editor.js", "editor-core.js")) {
            HttpResponse<String> imported = get(root + module);
            assertEquals(200, imported.statusCode(), module);
            assertTrue(imported.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"),
                    "a module console.js imports needs a JavaScript type too: " + module + " " + imported.headers());
        }
    }
```
(`List` and `HttpResponse` are already imported there.)

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -q -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=PageTest,DevConsoleExtensionTest
```
Expected: FAIL — `PageTest` on `the page has no META-INF/resources/devconsole/editor.js` (four rules and the editor
test) and on `no rule .ed-key {`; `DevConsoleExtensionTest.theConsolesPageIsItsIndexWithItsScriptAndStyle` on
`editor.js ==> expected: <200> but was: <404>`.

- [ ] **Step 3: Write `editor.js`**

Create `PAGE/editor.js`:

```js
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

// The dev console's code editor, its page half: a textarea over a <pre> that shows the same text coloured, with line
// numbers, diagnostics, a completion list, the matching bracket and a Format button. What the text means is
// editor-core.js's; this module draws it and turns keys into edits.
//
// - The <pre> is built from spans whose text is set with textContent, never parsed as markup. It draws the line
//   numbers too, in the padding it shares with the textarea, so that a wrapped line keeps its number without measuring.
// - Tokens, colours and diagnostics are computed again in one requestAnimationFrame after an input, never twice in a
//   frame, and only the lines that changed are drawn again. Past LIMIT characters the editor stops colouring and
//   checking and shows the textarea's own text; completion and formatting still work when asked for.
// - Every edit it makes goes through document.execCommand("insertText"), which keeps Ctrl+Z; where the browser
//   refuses it, setRangeText and an input event.

import { jsonLanguage, keystroke, FORMAT_EXAMPLES } from "./editor-core.js";

export { jsonLanguage, FORMAT_EXAMPLES };

/** Past this many characters, no colours and no diagnostics: the textarea alone. */
const LIMIT = 100_000;
/** The keys, besides the characters of a language's pairs, that editor-core.js may turn into an edit. */
const KEYS = new Set(["Enter", "Backspace", "Tab", "Shift+Tab"]);
/** The brackets whose match is outlined, by opening character. */
const BRACKETS = new Map([["{", "}"], ["[", "]"], ["(", ")"]]);
/** Keys pressed alone before another: they neither end the Escape of Escape-then-Tab nor make an edit. */
const MODIFIERS = new Set(["Shift", "Control", "Alt", "Meta", "CapsLock"]);

let editors = 0;

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined && text !== null) e.textContent = String(text);
  return e;
}

const plural = (n, one, many) => n + " " + (n === 1 ? one : many);
const clamp = (offset, length) => Math.max(0, Math.min(length, Math.trunc(Number(offset)) || 0));

/** {@code compute}'s value, or {@code fallback} when it throws: a language never breaks the page. */
function safely(compute, fallback) {
  try {
    return compute();
  } catch (failed) {
    return fallback;
  }
}

/**
 * An editor of {@code language} (spec §4), {@code data} its data (for JSON, the argument's schema), starting with
 * {@code value}, {@code rows} lines high; {@code label} names it for a screen reader.
 *
 * @returns {{root: HTMLElement, value(): string, setValue(text: string): void, disable(on: boolean): void,
 *   focus(): void, textarea: HTMLTextAreaElement}}
 */
export function createEditor({ language, data, value, rows, label }) {
  const id = "ed-" + ++editors;
  const root = el("div", "ed");
  const box = el("div", "ed-box");
  const pre = el("pre", "ed-pre");
  pre.setAttribute("aria-hidden", "true");
  const textarea = el("textarea", "ed-text");
  textarea.rows = rows || 8;
  textarea.spellcheck = false;
  textarea.setAttribute("autocomplete", "off");
  textarea.setAttribute("autocapitalize", "off");
  textarea.setAttribute("aria-autocomplete", "list");
  textarea.setAttribute("aria-controls", id + "-list");
  textarea.setAttribute("aria-expanded", "false");
  if (label) textarea.setAttribute("aria-label", label);
  textarea.value = typeof value === "string" ? value : "";
  const list = el("div", "ed-list");
  list.id = id + "-list";
  list.setAttribute("role", "listbox");
  list.hidden = true;
  const tip = el("div", "ed-tip");
  tip.id = id + "-tip";
  tip.setAttribute("role", "tooltip");
  tip.hidden = true;
  const mirror = el("div", "ed-mirror");
  mirror.setAttribute("aria-hidden", "true");
  box.append(pre, textarea, list, tip, mirror);
  const foot = el("div", "ed-foot");
  const note = el("span", "ed-note");
  note.setAttribute("aria-live", "polite");
  const format = el("button", "ed-format", "Format");
  format.type = "button";
  format.title = "Format (Shift+Alt+F)";
  foot.append(note, format);
  root.append(box, foot);

  let frame = 0;                // the pending animation frame of a draw, 0 when none
  let hoverFrame = 0;           // the pending animation frame of a pointer move, 0 when none
  let tokens = [];
  let diagnostics = [];
  let matches = new Map();      // bracket offset -> offset of its match
  let shown = [];               // the lines drawn: { key, from, parts: [{ from, to }], node }
  let outlined = [];            // the spans of the matching brackets
  let formatNote = "";          // why Format refused, until the next edit
  let escaped = false;          // Escape was the last key: the next Tab leaves the editor
  let hovering = false;         // the tooltip shows what the pointer is on
  let completion = null;        // while the list is open: { from, to, items, active }

  // -------------------------------------------------------------------------------------------- drawing

  function schedule() {
    if (frame === 0) frame = requestAnimationFrame(() => { frame = 0; draw(); });
  }

  function draw() {
    const text = textarea.value;
    const plain = text.length > LIMIT;
    root.classList.toggle("ed-plain", plain);
    if (plain) {
      tokens = [];
      diagnostics = [];
      matches = new Map();
      if (shown.length) pre.replaceChildren();
      shown = [];
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
    note.classList.toggle("ed-bad", errors > 0 || formatNote !== "");
  }

  /** The offsets of each bracket and its match, from the punctuation tokens. */
  function bracketMatches(text) {
    const found = new Map();
    const open = [];
    for (const t of tokens) {
      if (t.kind !== "punct") continue;
      const c = text[t.from];
      if (BRACKETS.has(c)) {
        open.push(t.from);
      } else if ([...BRACKETS.values()].includes(c)) {
        const from = open.pop();
        if (from !== undefined && BRACKETS.get(text[from]) === c) {
          found.set(from, t.from);
          found.set(t.from, from);
        }
      }
    }
    return found;
  }

  /**
   * Draws {@code text} into the <pre>: one element per line, one span per run of characters of the same token and
   * the same diagnostic level, a mark where an empty diagnostic stands, such as a value missing at the end. The lines
   * that did not change are kept, those between the first and the last change drawn again.
   */
  function paint(text) {
    const n = text.length;
    const tokenAt = new Int32Array(n).fill(-1);
    tokens.forEach((t, k) => tokenAt.fill(k, clamp(t.from, n), clamp(t.to, n)));
    const levelAt = new Uint8Array(n);
    const marks = new Map();                            // offset -> level of an empty diagnostic there
    for (const d of diagnostics) {
      const level = d.severity === "error" ? 2 : 1;
      const from = clamp(d.from, n);
      const to = clamp(d.to, n);
      if (from >= to) {
        marks.set(from, Math.max(marks.get(from) || 0, level));
      } else {
        for (let i = from; i < to; i++) if (levelAt[i] < level) levelAt[i] = level;
      }
    }
    const lines = [{ from: 0, parts: [] }];
    let start = 0;
    const cut = (to) => {
      if (start < to) {
        lines[lines.length - 1].parts.push({ from: start, to, token: tokenAt[start], level: levelAt[start] });
      }
      start = to;
    };
    const mark = (at) => {
      if (marks.has(at)) lines[lines.length - 1].parts.push({ from: at, to: at, token: -1, level: marks.get(at) });
    };
    for (let i = 0; i < n; i++) {
      if (i > start && (tokenAt[i] !== tokenAt[start] || levelAt[i] !== levelAt[start])) cut(i);
      if (marks.has(i)) {
        cut(i);
        mark(i);
      }
      if (text[i] === "\n") {
        cut(i);
        start = i + 1;
        lines.push({ from: i + 1, parts: [] });
      }
    }
    cut(n);
    mark(n);
    const classOf = (part) => [part.token >= 0 ? "ed-" + tokens[part.token].kind : "",
      part.from === part.to ? "ed-mark" : "", part.level === 2 ? "ed-error" : part.level === 1 ? "ed-warning" : ""]
      .filter(Boolean).join(" ");
    for (const line of lines) {
      line.level = Math.max(0, ...line.parts.map((part) => part.level));
      line.key = line.level + "\u0001" + line.parts.map((part) => classOf(part) + "\u0002"
        + text.slice(part.from, part.to)).join("\u0003");
    }
    let head = 0;
    while (head < shown.length && head < lines.length && shown[head].key === lines[head].key) head++;
    let tail = 0;
    while (tail < shown.length - head && tail < lines.length - head
        && shown[shown.length - 1 - tail].key === lines[lines.length - 1 - tail].key) tail++;
    const after = tail > 0 ? shown[shown.length - tail].node : null;
    for (let k = head; k < shown.length - tail; k++) shown[k].node.remove();
    const fresh = document.createDocumentFragment();
    for (let k = head; k < lines.length - tail; k++) {
      const node = el("div", "ed-line" + (lines[k].level === 2 ? " ed-dot-error" : lines[k].level === 1
        ? " ed-dot-warning" : ""));
      for (const part of lines[k].parts) {
        const span = el("span", classOf(part) || null);
        span.textContent = text.slice(part.from, part.to);
        node.append(span);
      }
      lines[k].node = node;
      fresh.append(node);
    }
    pre.insertBefore(fresh, after);
    for (let k = 0; k < lines.length; k++) {
      if (k < head) lines[k].node = shown[k].node;
      else if (k >= lines.length - tail) lines[k].node = shown[shown.length - (lines.length - k)].node;
    }
    shown = lines;
  }

  /** The span that draws offset {@code offset}, or null. */
  function spanAt(offset) {
    let low = 0;
    let high = shown.length - 1;
    while (low < high) {
      const middle = (low + high + 1) >> 1;
      if (shown[middle].from <= offset) low = middle;
      else high = middle - 1;
    }
    const line = shown[low];
    if (!line) return null;
    const index = line.parts.findIndex((part) => part.from <= offset && offset < part.to);
    return index < 0 ? null : line.node.childNodes[index];
  }

  /** The part a span of the <pre> draws, or null. */
  function partOf(span) {
    const line = shown.find((l) => l.node === span.parentNode);
    return line ? line.parts[[...line.node.childNodes].indexOf(span)] || null : null;
  }

  /** The <pre> follows the textarea's scroll. */
  function follow() {
    pre.scrollTop = textarea.scrollTop;
    pre.scrollLeft = textarea.scrollLeft;
  }

  /** Outlines the bracket next to the caret and its match. */
  function outline() {
    for (const span of outlined) span.classList.remove("ed-match");
    outlined = [];
    if (textarea.selectionStart !== textarea.selectionEnd) return;
    const caret = textarea.selectionStart;
    const at = matches.has(caret - 1) ? caret - 1 : matches.has(caret) ? caret : -1;
    if (at < 0) return;
    for (const offset of [at, matches.get(at)]) {
      const span = spanAt(offset);
      if (span) {
        span.classList.add("ed-match");
        outlined.push(span);
      }
    }
  }

  // -------------------------------------------------------------------------------------------- placing

  /** Where offset {@code offset} is drawn, relative to the box, measured on a hidden copy of the textarea. */
  function pointAt(offset) {
    const text = textarea.value;
    mirror.style.width = textarea.offsetWidth + "px";
    mirror.textContent = text.slice(0, offset);
    const next = text.slice(offset, offset + 1);
    const marker = el("span", null, next && next !== "\n" ? next : ".");
    mirror.append(marker);
    const height = parseFloat(getComputedStyle(textarea).lineHeight) || marker.offsetHeight;
    return { left: textarea.clientLeft + marker.offsetLeft - textarea.scrollLeft,
      top: textarea.clientTop + marker.offsetTop - textarea.scrollTop, height };
  }

  /**
   * Places {@code popup} under {@code point}, or over it when the window has no room below, and never past the
   * right edge of the box.
   */
  function place(popup, point) {
    popup.style.left = "0px";
    popup.style.top = "0px";
    const width = popup.offsetWidth;
    const height = popup.offsetHeight;
    const frameTop = box.getBoundingClientRect().top;
    const below = point.top + point.height + 2;
    const above = point.top - height - 2;
    const up = frameTop + below + height > window.innerHeight && frameTop + above >= 0;
    popup.style.left = Math.max(0, Math.min(point.left, box.clientWidth - width)) + "px";
    popup.style.top = (up ? above : below) + "px";
  }

  // -------------------------------------------------------------------------------------------- tooltip

  function showTip(found, point) {
    tip.replaceChildren(...found.map((d) => el("div", "ed-tip-" + (d.severity === "error" ? "error" : "warning"),
      String(d.message))));
    tip.hidden = false;
    textarea.setAttribute("aria-describedby", tip.id);
    place(tip, point);
  }

  function hideTip() {
    tip.hidden = true;
    textarea.removeAttribute("aria-describedby");
  }

  /** The messages of the diagnostics the caret is on, unless the pointer shows others or the list is open. */
  function tipAtCaret() {
    if (hovering) return;
    if (completion || document.activeElement !== textarea || textarea.selectionStart !== textarea.selectionEnd) {
      hideTip();
      return;
    }
    const caret = textarea.selectionStart;
    const here = diagnostics.filter((d) => d.from <= caret && caret <= d.to);
    if (!here.length) {
      hideTip();
      return;
    }
    showTip(here, pointAt(Math.min(...here.map((d) => d.from))));
  }

  /** The messages of the diagnostics under the pointer, found among the spans the textarea covers. */
  function tipAtPointer(x, y) {
    const span = document.elementsFromPoint(x, y).find((e) => e.parentNode && e.parentNode.parentNode === pre
      && (e.classList.contains("ed-error") || e.classList.contains("ed-warning")));
    const part = span ? partOf(span) : null;
    const here = part ? diagnostics.filter((d) => part.from === part.to ? d.from === part.from && d.to === part.to
      : d.from <= part.from && part.from < d.to) : [];
    hovering = here.length > 0 && !completion;
    if (!hovering) {
      tipAtCaret();
      return;
    }
    const spanBox = span.getBoundingClientRect();
    const area = box.getBoundingClientRect();
    showTip(here, { left: spanBox.left - area.left, top: spanBox.top - area.top, height: spanBox.height });
  }

  // -------------------------------------------------------------------------------------------- editing

  /**
   * Replaces {@code from}-{@code to} with {@code insert} as typing would, so that Ctrl+Z undoes it in one step, then
   * puts the caret at {@code caret}, an offset into insert, and selects back to {@code anchor} when given.
   */
  function edit(from, to, insert, caret, anchor) {
    textarea.focus();
    if (from !== to || insert !== "") {
      textarea.setSelectionRange(from, to);
      const typed = insert === "" ? document.execCommand("delete")
        : document.execCommand("insertText", false, insert);
      if (!typed) {
        textarea.setRangeText(insert, from, to, "end");
        textarea.dispatchEvent(new Event("input", { bubbles: true }));
      }
    }
    const end = from + caret;
    const start = anchor === undefined ? end : from + anchor;
    textarea.setSelectionRange(Math.min(start, end), Math.max(start, end), start > end ? "backward" : "forward");
    outline();
  }

  function formatText() {
    let formatted;
    try {
      formatted = language.format(textarea.value);
    } catch (refused) {
      formatNote = "not formatted: " + (refused && refused.message ? refused.message : String(refused));
      say();
      return;
    }
    formatNote = "";
    closeList();
    if (formatted !== textarea.value) edit(0, textarea.value.length, formatted, 0);
    say();
  }

  // -------------------------------------------------------------------------------------------- completion

  /**
   * Opens the completion list at the caret, or refilters it; {@code keysOnly}: only when the caret is in a key, as
   * after a quote typed there. Closes it when there is nothing to offer.
   */
  function openList(keysOnly) {
    const found = textarea.selectionStart !== textarea.selectionEnd ? null
      : safely(() => language.complete(textarea.value, textarea.selectionStart, data), null);
    if (!found || !Array.isArray(found.items) || !found.items.length || (keysOnly && found.items[0].kind !== "key")) {
      closeList();
      return;
    }
    completion = { from: found.from, to: found.to, items: found.items, active: 0 };
    list.replaceChildren(...found.items.map((item, i) => {
      const option = el("div", "ed-item");
      option.id = id + "-item-" + i;
      option.setAttribute("role", "option");
      option.append(el("span", "ed-item-label", item.label), el("span", "ed-item-detail", item.detail || ""));
      option.addEventListener("mousedown", (event) => event.preventDefault());
      option.addEventListener("click", () => accept(i));
      return option;
    }));
    list.hidden = false;
    textarea.setAttribute("aria-expanded", "true");
    hideTip();
    hovering = false;
    activate(0);
    place(list, pointAt(completion.from));
  }

  function activate(index) {
    completion.active = index;
    [...list.children].forEach((option, i) => option.setAttribute("aria-selected", String(i === index)));
    textarea.setAttribute("aria-activedescendant", id + "-item-" + index);
    const option = list.children[index];
    if (option.offsetTop < list.scrollTop) list.scrollTop = option.offsetTop;
    else if (option.offsetTop + option.offsetHeight > list.scrollTop + list.clientHeight) {
      list.scrollTop = option.offsetTop + option.offsetHeight - list.clientHeight;
    }
  }

  function closeList() {
    if (!completion) return;
    completion = null;
    list.hidden = true;
    list.replaceChildren();
    textarea.setAttribute("aria-expanded", "false");
    textarea.removeAttribute("aria-activedescendant");
  }

  function accept(index) {
    const { from, to, items } = completion;
    const item = items[index];
    closeList();
    edit(from, to, item.insert, typeof item.caret === "number" ? item.caret : item.insert.length);
  }

  // -------------------------------------------------------------------------------------------- events

  textarea.addEventListener("keydown", (event) => {
    if (event.isComposing || MODIFIERS.has(event.key)) return;
    if (completion) {
      const count = completion.items.length;
      if (event.key === "ArrowDown" || event.key === "ArrowUp") {
        event.preventDefault();
        activate((completion.active + (event.key === "ArrowDown" ? 1 : count - 1)) % count);
        return;
      }
      if (event.key === "Enter" || event.key === "Tab") {
        event.preventDefault();
        accept(completion.active);
        return;
      }
      if (event.key === "Escape") {
        event.preventDefault();
        closeList();
        return;
      }
    }
    if (event.key === " " && event.ctrlKey && !event.altKey && !event.metaKey) {
      event.preventDefault();
      openList(false);
      return;
    }
    if (event.code === "KeyF" && event.shiftKey && event.altKey && !event.ctrlKey && !event.metaKey) {
      event.preventDefault();
      formatText();
      return;
    }
    const leaving = escaped;
    escaped = event.key === "Escape";
    if (event.ctrlKey || event.metaKey || event.altKey) return;
    const key = event.key === "Tab" && event.shiftKey ? "Shift+Tab" : event.key;
    const tab = key === "Tab" || key === "Shift+Tab";
    if (tab && leaving) return;
    const paired = key.length === 1 && Array.isArray(language.pairs) && language.pairs.some((p) => p.includes(key));
    if (!KEYS.has(key) && !paired) return;
    const change = safely(() => keystroke(language, textarea.value, textarea.selectionStart, textarea.selectionEnd,
      key), null);
    if (change === null) {
      if (tab) event.preventDefault();
      return;
    }
    event.preventDefault();
    edit(change.from, change.to, change.insert, change.caret, change.anchor);
    if (key === "\"") openList(true);
  });

  textarea.addEventListener("input", () => {
    formatNote = "";
    schedule();
    if (completion) openList(false);
  });
  textarea.addEventListener("scroll", () => {
    follow();
    if (completion) place(list, pointAt(completion.from));
    hovering = false;
    hideTip();
  });
  for (const type of ["keyup", "mouseup", "focus"]) {
    textarea.addEventListener(type, () => {
      outline();
      tipAtCaret();
    });
  }
  textarea.addEventListener("blur", () => {
    closeList();
    hovering = false;
    hideTip();
  });
  textarea.addEventListener("mousemove", (event) => {
    const x = event.clientX;
    const y = event.clientY;
    if (hoverFrame === 0) hoverFrame = requestAnimationFrame(() => { hoverFrame = 0; tipAtPointer(x, y); });
  });
  textarea.addEventListener("mouseleave", () => {
    hovering = false;
    tipAtCaret();
  });
  format.addEventListener("click", formatText);

  draw();
  return {
    root,
    textarea,
    value: () => textarea.value,
    setValue(text) {
      textarea.value = typeof text === "string" ? text : "";
      textarea.scrollTop = 0;
      formatNote = "";
      closeList();
      schedule();
    },
    disable(on) {
      textarea.disabled = on;
      format.disabled = on;
      root.classList.toggle("ed-disabled", on);
      if (on) closeList();
    },
    focus: () => textarea.focus(),
  };
}
```

- [ ] **Step 4: Add the editor's style**

In `PAGE/console.css`, right after the rule
```css
.action .json-editor { font-family: var(--mono); font-size: 12px; color: var(--ink); background: var(--surface);
  border: 1px solid var(--rule); border-radius: 5px; padding: 6px 8px; width: 100%; max-width: 80ch; }
```
insert:
```css
/* The code editor (editor.js): a textarea over a <pre> that draws the same text coloured, with the line numbers in
   the padding both share. Both lay the text out identically — font, size, line height, padding, border, wrapping,
   tab size — and nothing in the <pre> changes a width: colours, underlines and outlines only. */
.ed { width: 100%; max-width: 88ch; --ed-gutter: calc(var(--ed-digits, 2) * 1ch + 16px); }
.ed-box { position: relative; }
.ed-pre, .ed-text, .ed-mirror { box-sizing: border-box; margin: 0; border: 1px solid transparent; border-radius: 5px;
  padding: 6px 8px 6px calc(var(--ed-gutter) + 8px); font-family: var(--mono); font-size: 12px; line-height: 18px;
  letter-spacing: normal; tab-size: 2; font-variant-ligatures: none; font-kerning: none; white-space: pre-wrap;
  overflow-wrap: break-word; word-break: normal; scrollbar-gutter: stable; }
.ed-pre { position: absolute; inset: 0; overflow: hidden; color: var(--ink); counter-reset: ed-line;
  background: linear-gradient(to right, var(--sunk) var(--ed-gutter), var(--surface) var(--ed-gutter)); }
.ed-text { position: relative; z-index: 1; display: block; width: 100%; min-height: 64px; overflow: auto;
  resize: vertical; border-color: var(--rule); background: transparent; color: transparent; caret-color: var(--ink);
  outline: none; }
.ed-text:focus-visible { border-color: var(--accent); }
.ed-text::selection { background: color-mix(in srgb, var(--accent) 28%, transparent); color: transparent; }
.ed-mirror { position: absolute; top: 0; left: 0; visibility: hidden; overflow: hidden; pointer-events: none; }
.ed-line { position: relative; min-height: 18px; counter-increment: ed-line; }
.ed-line::before { content: counter(ed-line); position: absolute; right: 100%; margin-right: 12px;
  width: calc(var(--ed-gutter) - 4px); text-align: right; color: var(--faint); }
.ed-line.ed-dot-error::after, .ed-line.ed-dot-warning::after { content: ""; position: absolute; right: 100%; top: 6px;
  margin-right: 3px; width: 6px; height: 6px; border-radius: 50%; }
.ed-line.ed-dot-error::after { background: var(--crit); }
.ed-line.ed-dot-warning::after { background: var(--warn); }
.ed-key { color: var(--json-key); }
.ed-string { color: var(--json-string); }
.ed-number { color: var(--json-number); }
.ed-literal { color: var(--json-literal); }
.ed-punct { color: var(--json-punct); }
.ed-invalid { color: var(--crit); }
.ed-error { text-decoration: underline wavy var(--crit); text-decoration-skip-ink: none; text-underline-offset: 3px; }
.ed-warning { text-decoration: underline wavy var(--warn); text-decoration-skip-ink: none; text-underline-offset: 3px; }
.ed-mark::after { content: "\00a0"; }
.ed-match { outline: 1px solid var(--accent); outline-offset: -1px; border-radius: 2px; }
.ed-plain .ed-pre { display: none; }
.ed-plain .ed-text { padding-left: 8px; color: var(--ink); background: var(--surface); }
.ed-disabled .ed-pre { opacity: 0.55; }
.ed-list { position: absolute; z-index: 3; min-width: 18ch; max-width: 100%; max-height: 15em; overflow-y: auto;
  background: var(--surface); border: 1px solid var(--rule); border-radius: 5px; box-shadow: 0 4px 14px var(--shade);
  font-family: var(--mono); font-size: 12px; }
.ed-item { display: flex; justify-content: space-between; gap: 14px; padding: 3px 8px; cursor: pointer;
  white-space: nowrap; }
.ed-item[aria-selected="true"] { background: var(--accent-soft); }
.ed-item-label { color: var(--ink); }
.ed-item-detail { min-width: 0; overflow: hidden; text-overflow: ellipsis; color: var(--faint); }
.ed-tip { position: absolute; z-index: 4; max-width: min(60ch, 100%); padding: 4px 8px; border-radius: 4px;
  background: var(--ink); color: var(--surface); font-size: 12px; line-height: 1.4; pointer-events: none; }
.ed-tip-error::before, .ed-tip-warning::before { content: ""; display: inline-block; width: 6px; height: 6px;
  margin-right: 6px; border-radius: 50%; vertical-align: 1px; }
.ed-tip-error::before { background: var(--crit); }
.ed-tip-warning::before { background: var(--warn); }
.ed-foot { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 12px; margin-top: 4px; font-size: 12px;
  color: var(--muted); }
.ed-note { flex: 1 1 20ch; overflow-wrap: anywhere; }
.ed-note.ed-bad { color: var(--crit); }
.ed-format { font-size: 11.5px; padding: 1px 8px; }
```
(Keep `.ed-error {` and `.ed-warning {` as two separate rules: `PageTest.rule` reads the first rule whose opening is
`.ed-warning {`, and a combined `.ed-error, .ed-warning {` would be that rule.)

- [ ] **Step 5: Check the syntax, then run the tests to verify they pass**

```bash
node --check $VIDOCQ/vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/editor.js && echo syntax-ok
```
Expected: `syntax-ok` (Node is only a local syntax check, not a project dependency).

Run: the command of Step 2.
Expected: exit status 0, no `[ERROR]` line.

- [ ] **Step 6: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(devconsole): the code editor component, drawn with textContent only

editor.js's createEditor lays a transparent textarea over a <pre> that draws the same text coloured with the JSON
viewer's colours, its line numbers in the padding both share, diagnostics as wavy underlines with a dot on the
line and a tooltip at the caret or under the pointer, a count under the editor, a completion list (Ctrl+Space, by
itself after a quote in a key position) placed by a hidden mirror, the matching bracket outlined, Format and
Shift+Alt+F. Every edit goes through execCommand("insertText") so that one Ctrl+Z undoes it; the <pre> is redrawn
once per animation frame, the changed lines only; past 100 000 characters it is a plain textarea.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/editor.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/console.css vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/PageTest.java vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/DevConsoleExtensionTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 8: `formShape`, the nested form, and the editor in every JSON argument

**Files:**
- Modify: `PAGE/console.js` (header comment; the import; `SCALAR_TYPES` … `unmasked`; the whole `jsonField`)
- Modify: `PAGE/console.css` (`.action .json-editor` replaced by the nested fieldset's rules)
- Modify: `PAGE/index.html` (its comment)
- Test: `DCT/PageTest.java`

**Interfaces:**
- Consumes (Task 7): `import { createEditor, jsonLanguage, FORMAT_EXAMPLES } from "./editor.js";`,
  `createEditor({ language, data, value, rows, label }) → { root, textarea, value(), setValue(text), disable(on),
  focus() }`; `FORMAT_EXAMPLES.get(format)`.
- Produces (inside `console.js`, used by `actionRow` as before):
  - `formShape(schema) → [field] | null`, field `{ name, kind, definition, required, readOnly, fields }` (`kind` a
    scalar type, `"enum"` or `"object"`; `fields` a nested object's own, else `null`); `scalarKind(p)`,
    `formFields(schema, nested)`; constants `NOT_FORM`, `NOT_SCALAR`, `NOT_NESTED` (`NOT_FLAT` and `isFlatSchema`
    removed).
  - `skeleton(schema)` now also fills a required nested object with its own required keys.
  - `hasMasked(values)`, `unmasked(values)` at every depth of objects.
  - `jsonField(argument) → { name, root, value(), fill(values), disable(on) }` (same shape as today): a form with a
    `fieldset.json-group` per nested object when `formShape` accepts the schema, the editor otherwise; `value()`
    refuses a missing required field by its path (`entity.title is required`) and sends, in JSON mode, the editor's
    own text once it parses as an object.

- [ ] **Step 1: Write the failing tests**

In `DCT/PageTest.java`, replace:
```java
    @Test
    void jsonArgumentsGetAFormForAFlatSchemaAndARawEditorOtherwise() {
        String script = file("console.js");

        assertTrue(script.contains("function isFlatSchema(schema)"), "the flat-schema rule, written once");
        assertTrue(script.contains("\"$ref\""), "a $ref is never flat");
        assertTrue(script.contains("function jsonField(argument)"), "a json argument's field");
```
with:
```java
    @Test
    void jsonArgumentsGetAFormWhenFormShapeAcceptsTheirSchemaAndTheEditorOtherwise() {
        String script = file("console.js");

        assertTrue(script.contains("function formShape(schema)"), "the form's rule, written once");
        assertFalse(script.contains("isFlatSchema"), "formShape replaced it");
        assertTrue(script.contains("\"$ref\""), "a $ref is never a form");
        assertTrue(script.contains("function jsonField(argument)"), "a json argument's field");
        assertTrue(script.contains("const editor = createEditor({ language: jsonLanguage, data: schema,"),
                "the JSON mode is the editor, checking against the argument's schema");
        assertFalse(script.contains("\"json-editor\""), "no raw textarea left");
        assertTrue(script.contains("return editor.value();"), "the editor's own text is sent: no id past 2^53 rounded");
```

Then replace:
```java
        assertTrue(script.contains("\"Exchange\""), "the details folded under Exchange");
    }

    @Test
    void aStringPropertyOfFormatTextareaIsAMultiLineFieldOfTheForm() {
```
with:
```java
        assertTrue(script.contains("\"Exchange\""), "the details folded under Exchange");
    }

    @Test
    void theConsoleImportsTheEditorAndNoOtherScript() {
        String script = file("console.js");

        assertEquals(List.of("./editor.js"), IMPORT.matcher(script).results().map(m -> m.group(1)).toList());
        assertTrue(script.contains("import { createEditor, jsonLanguage, FORMAT_EXAMPLES } from \"./editor.js\";"));
        assertEquals(1, Pattern.compile("<script").matcher(file("index.html")).results().count(),
                "the index still loads console.js only");
    }

    @Test
    void aNestedObjectIsAFieldsetWhoseMissingFieldsAreRefusedByTheirPath() {
        String script = file("console.js");

        assertTrue(script.contains("const group = el(\"fieldset\", \"json-group\");"), "a fieldset per nested object");
        assertTrue(script.contains("group.append(el(\"legend\", null, f.name + (f.required ? \" *\" : \"\")"),
                "its name the legend, starred when required");
        assertTrue(script.contains("if (!nested || !isObject(p) || p.type !== \"object\" || !isObject(p.properties)"),
                "one level of nesting, and an object that lists its properties");
        assertTrue(script.contains("throw new Error(prefix + f.name + \" is required\")"), "entity.title is required");
        assertTrue(script.contains("if (!f.required && Object.keys(inner).length === 0) continue;"),
                "an optional nested object left empty is not sent");
        assertTrue(script.contains("required: !readOnly && required.has(name)"),
                "a readOnly property is never required");
        assertTrue(script.contains("(f.readOnly ? \" (generated)\" : \"\")"), "a readOnly property says so");
        assertTrue(script.contains(
                "input.placeholder = f.readOnly ? \"generated\" : FORMAT_EXAMPLES.get(definition.format) || \"\";"),
                "the placeholder: generated, or the shape of a date, a time, a date-time, a uuid");
        assertTrue(script.contains(": p.type === \"object\" ? skeleton(p)"),
                "the skeleton, required keys at each level");
        assertTrue(script.contains(".map(([k, v]) => [k, isObject(v) ? unmasked(v) : v])"), "masked values, at depth");
        assertTrue(rule(file("console.css"), ".action .json-group {").contains("flex-basis: 100%"),
                "a line of its own");
    }

    @Test
    void aStringPropertyOfFormatTextareaIsAMultiLineFieldOfTheForm() {
```

In `anUneditedFileIsSentAsReadItsLineEndsKept`, replace:
```java
        assertTrue(script.contains("inputs.set(property, { input, kind, text: chooser ? chooser.text : null });"),
                "the form keeps it");
```
with:
```java
        assertTrue(script.contains("inputs.set(f, { input, kind, text: chooser ? chooser.text : null });"),
                "the form keeps it");
```

In `aJsonPropertyNamedProtoIsAnObjectKeyLikeAnyOther`, replace:
```java
                "skeleton(), formObject() and the request body: a bare {} would silently drop a \"__proto__\" key");
```
with:
```java
                "skeleton(), valuesOf() and the request body: a bare {} would silently drop a \"__proto__\" key");
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -q -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=PageTest
```
Expected: FAIL — `jsonArgumentsGetAFormWhenFormShapeAcceptsTheirSchemaAndTheEditorOtherwise` (`the form's rule,
written once`), `theConsoleImportsTheEditorAndNoOtherScript` (`expected: <[./editor.js]> but was: <[]>`),
`aNestedObjectIsAFieldsetWhoseMissingFieldsAreRefusedByTheirPath`, `anUneditedFileIsSentAsReadItsLineEndsKept`.

- [ ] **Step 3: Import the editor**

In `PAGE/console.js`, replace the header paragraph:
```js
// - An action's json argument is a form generated from its JSON Schema when the schema is flat (isFlatSchema), a raw
//   JSON editor otherwise, with a "JSON" switch that keeps the values. A structured answer shows its body, through
//   the JSON viewer (jsonViewer) when it is JSON, and its details folded under "Exchange", through it too. A cell of
//   a sample table column named "replay" that reads as "<action id> <JSON object>" of an action of that panel is a
//   button that fills its form: nothing is sent until the user submits. Any other cell of such a column stays text.
```
with:
```js
// - An action's json argument is a form generated from its JSON Schema when formShape accepts the schema (scalars,
//   and objects of scalars one level down), the code editor of editor.js otherwise, with a "JSON" switch to that
//   editor that keeps the values. The editor colours, checks and completes the JSON against the same schema. A
//   structured answer shows its body, through the JSON viewer (jsonViewer) when it is JSON, and its details folded
//   under "Exchange", through it too. A cell of a sample table column named "replay" that reads as "<action id>
//   <JSON object>" of an action of that panel is a button that fills its form: nothing is sent until the user
//   submits. Any other cell of such a column stays text.
```
and replace:
```js
//   60 KiB at most into it; nothing is sent until the form is.

const HISTORY_POINTS = 300;          // five minutes at one poll per second
```
with:
```js
//   60 KiB at most into it; nothing is sent until the form is.

import { createEditor, jsonLanguage, FORMAT_EXAMPLES } from "./editor.js";

const HISTORY_POINTS = 300;          // five minutes at one poll per second
```

- [ ] **Step 4: Write `formShape`, the nested skeleton and the masked values at depth**

In `PAGE/console.js`, replace everything from the line
`const SCALAR_TYPES = new Set(["string", "number", "integer", "boolean"]);` down to and including the line
`const unmasked = (values) => Object.fromEntries(Object.entries(values).filter(([, v]) => v !== MASKED));` with:

```js
const SCALAR_TYPES = new Set(["string", "number", "integer", "boolean"]);
/** What a schema of a form's root may not use: the form would not say what it means. */
const NOT_FORM = ["$ref", "anyOf", "oneOf", "allOf", "not"];
/** What a scalar field's schema may not use. */
const NOT_SCALAR = ["$ref", "properties", "items", "anyOf", "oneOf", "allOf", "not", "patternProperties"];
/** What a nested object's schema may not use: it lists its properties, and nothing else says what it holds. */
const NOT_NESTED = ["$ref", "items", "anyOf", "oneOf", "allOf", "not", "patternProperties"];

/** The kind of a form field of property schema {@code p}: its scalar type, "enum" (of strings), or null. */
function scalarKind(p) {
  if (!isObject(p) || NOT_SCALAR.some((k) => k in p)) return null;
  if (Array.isArray(p.enum)) {
    return (p.type === undefined || p.type === "string") && p.enum.length > 0
      && p.enum.every((v) => typeof v === "string") ? "enum" : null;
  }
  return SCALAR_TYPES.has(p.type) ? p.type : null;
}

/**
 * The generated form of a json argument's schema (editor spec §5), the one place its rule is written: its fields, or
 * null for the JSON editor. The root is "type": "object", with no $ref, anyOf, oneOf, allOf or not; each property is
 * a string, a number, an integer or a boolean, or an enum of strings, or an object ("type": "object") whose own
 * properties all are such scalars, with no $ref and no deeper nesting. A field is { name, kind, definition, required,
 * readOnly, fields }: kind a scalar type, "enum" or "object"; fields, a nested object's own. A readOnly property is
 * never required.
 */
function formShape(schema) {
  if (!isObject(schema) || schema.type !== "object" || NOT_FORM.some((k) => k in schema)) return null;
  return formFields(schema, true);
}

/** The fields of object schema {@code schema}, or null when a property cannot be one; nested: one more level. */
function formFields(schema, nested) {
  if (schema.properties === undefined) return [];
  if (!isObject(schema.properties)) return null;
  const required = new Set(Array.isArray(schema.required) ? schema.required.filter((n) => typeof n === "string")
    : []);
  const fields = [];
  for (const [name, p] of Object.entries(schema.properties)) {
    const readOnly = isObject(p) && p.readOnly === true;
    const field = { name, kind: scalarKind(p), definition: p, required: !readOnly && required.has(name), readOnly,
      fields: null };
    if (field.kind === null) {
      if (!nested || !isObject(p) || p.type !== "object" || !isObject(p.properties)
        || NOT_NESTED.some((k) => k in p)) return null;
      field.kind = "object";
      field.fields = formFields(p, false);
      if (field.fields === null) return null;
    }
    fields.push(field);
  }
  return fields;
}

/**
 * The JSON editor's first value: the required properties, each with its default, or an empty value of its type, a
 * nested object with its own required properties. A property named "__proto__" is a schema-declared name like any
 * other: a bare {} would silently drop a write to that key (or repoint the object's own prototype) instead of
 * storing it, so the object is prototype-less.
 */
function skeleton(schema) {
  const object = Object.create(null);
  const properties = isObject(schema) && isObject(schema.properties) ? schema.properties : {};
  const required = isObject(schema) && Array.isArray(schema.required) ? schema.required : [];
  for (const name of required) {
    if (typeof name !== "string") continue;
    const p = Object.hasOwn(properties, name) && isObject(properties[name]) ? properties[name] : {};
    object[name] = p.default !== undefined ? p.default
      : Array.isArray(p.enum) && p.enum.length ? p.enum[0]
      : p.type === "object" ? skeleton(p)
      : SKELETON.has(p.type) ? structuredClone(SKELETON.get(p.type)) : null;
  }
  return object;
}

/** Whether {@code values} holds a member a panel masked, in it or in an object it holds. */
const hasMasked = (values) => Object.values(values).some((v) => v === MASKED || (isObject(v) && hasMasked(v)));

/** {@code values} without the members a panel masked, at every depth of its objects: the user types those again. */
const unmasked = (values) => Object.fromEntries(Object.entries(values).filter(([, v]) => v !== MASKED)
  .map(([k, v]) => [k, isObject(v) ? unmasked(v) : v]));
```

- [ ] **Step 5: Write the new `jsonField`**

In `PAGE/console.js`, replace the whole of `jsonField` — from its Javadoc line
` * A json argument: a form generated from its schema when the schema is flat, using required, default, description,`
(with the `/**` above it) down to its closing `}` just before
`// ------------------------------------------------------------------------------------------------ JSON viewer` — with:

```js
/**
 * A json argument: a form generated from its schema when formShape accepts it, using required, readOnly, default,
 * description, enum, a string's "format" (a placeholder of its shape; "textarea", a field of several lines, with
 * Choose file when its "contentMediaType" is "text/csv"), a nested object as a fieldset; the code editor of editor.js
 * otherwise, checking and completing against the schema, starting from the required properties. A "JSON" switch shows
 * the form's value in that editor; switching back keeps the values. value() returns the JSON text sent, or throws
 * what is wrong: the editor's own text once it parses as an object, so that an id past 2^53 reaches the server as
 * typed.
 */
function jsonField(argument) {
  const root = el("div", "json-arg");
  const schema = argument.schema;
  const name = argument.label || argument.name;
  const shape = formShape(schema);
  const head = el("div", "json-head");
  head.append(el("span", "json-label", name));
  const note = el("span", "json-note");
  const editor = createEditor({ language: jsonLanguage, data: schema, value: JSON.stringify(skeleton(schema), null, 2),
    rows: 8, label: name });
  editor.textarea.name = argument.name;
  const form = el("div", "json-form");
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
      input = el("select");
      for (const v of ["", ...(kind === "enum" ? definition.enum : ["true", "false"])]) {
        const option = el("option", null, v === "" ? "–" : v);
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

  if (shape) {
    for (const f of shape) {
      if (f.kind !== "object") {
        form.append(field(f, f.name));
        continue;
      }
      // A nested object: a fieldset of its own fields, its name the legend.
      const group = el("fieldset", "json-group");
      group.append(el("legend", null, f.name + (f.required ? " *" : "") + (f.readOnly ? " (generated)" : "")));
      for (const inner of f.fields) group.append(field(inner, f.name + "." + inner.name));
      form.append(group);
    }
    const toggle = el("label", "json-switch");
    toggle.append(raw, el("span", null, "JSON"));
    head.append(toggle);
  }
  head.append(note);
  root.append(head);
  if (shape) root.append(form);
  root.append(editor.root);
  const rawMode = () => !shape || raw.checked;
  const show = () => { form.hidden = rawMode(); editor.root.hidden = !rawMode(); };
  show();

  /**
   * The values of {@code fields} as an object, {@code prefix} the path of that object for a refusal; strict, a number
   * that is none is refused. A nested object neither required nor filled in is left out; one that is, its missing
   * required fields refused by their path. A property literally named "__proto__" is a name like any other: a bare
   * {} would silently drop the write, which would then make it forever "required" instead of present.
   */
  function valuesOf(fields, prefix, strict) {
    const object = Object.create(null);
    for (const f of fields) {
      const property = f.name;
      if (f.kind === "object") {
        const inner = valuesOf(f.fields, prefix + property + ".", strict);
        if (!f.required && Object.keys(inner).length === 0) continue;
        if (strict) requireAll(f.fields, inner, prefix + property + ".");
        object[property] = inner;
        continue;
      }
      const { input, kind, text: fileText } = inputs.get(f);
      const text = input.value.trim();
      if (text === "") continue;
      if (kind === "integer" || kind === "number") {
        const n = Number(text);
        const valid = kind === "integer" ? /^-?\d+$/.test(text) : Number.isFinite(n);
        if (!valid && strict) {
          throw new Error(prefix + property + ": not " + (kind === "integer" ? "an integer" : "a number"));
        }
        object[property] = valid ? n : text;
      } else if (kind === "boolean") {
        object[property] = text === "true";
      } else {
        object[property] = fileText ? fileText() : input.value;
      }
    }
    return object;
  }
  /** Refuses the first required field of {@code fields} that {@code object} lacks: "entity.title is required". */
  function requireAll(fields, object, prefix) {
    for (const f of fields) {
      if (f.required && !Object.hasOwn(object, f.name)) throw new Error(prefix + f.name + " is required");
    }
  }
  function formObject(strict) {
    const object = valuesOf(shape, "", strict);
    if (strict) requireAll(shape, object, "");
    return object;
  }
  function editorObject() {
    let value;
    try { value = JSON.parse(editor.value()); } catch (unparsable) { throw new Error(name + ": not valid JSON"); }
    if (!isObject(value)) throw new Error(name + ": not a JSON object");
    return value;
  }
  function toForm(object, fields) {
    for (const f of fields) {
      const property = f.name;
      // Object.hasOwn: a property object lacks, such as "constructor" or "toString", must read as absent, never
      // as the inherited member of that name.
      const v = isObject(object) && Object.hasOwn(object, property) ? object[property] : undefined;
      if (f.kind === "object") {
        toForm(v, f.fields);
        continue;
      }
      inputs.get(f).input.value = v === undefined || v === null ? "" : typeof v === "object" ? JSON.stringify(v)
        : String(v);
    }
  }
  raw.addEventListener("change", () => {
    note.textContent = "";
    if (raw.checked) {
      editor.setValue(JSON.stringify(formObject(false), null, 2));
    } else {
      try { toForm(editorObject(), shape); } catch (invalid) { raw.checked = true; note.textContent = invalid.message; }
    }
    show();
  });
  return {
    name: argument.name,
    root,
    value() {
      if (!rawMode()) return JSON.stringify(formObject(true));
      editorObject();                   // refuses a text that is no JSON object, as before
      return editor.value();
    },
    fill(values) {
      if (!isObject(values)) return;
      const kept = unmasked(values);
      editor.setValue(JSON.stringify(kept, null, 2));
      if (shape) toForm(kept, shape);
      note.textContent = hasMasked(values) ? "masked values: type them again" : "";
    },
    disable(on) {
      editor.disable(on);
      for (const c of [raw, ...[...inputs.values()].map((i) => i.input), ...choosers]) c.disabled = on;
    },
  };
}
```

- [ ] **Step 6: The fieldset's style, and the index's comment**

In `PAGE/console.css`, replace:
```css
.action .json-editor { font-family: var(--mono); font-size: 12px; color: var(--ink); background: var(--surface);
  border: 1px solid var(--rule); border-radius: 5px; padding: 6px 8px; width: 100%; max-width: 80ch; }
```
with:
```css
/* A nested object of the form: a fieldset of its own fields, its name the legend. */
.action .json-group { flex-basis: 100%; display: flex; flex-wrap: wrap; align-items: center; gap: 6px 12px;
  margin: 0; padding: 6px 10px 8px; border: 1px solid var(--rule); border-radius: 5px; min-width: 0; }
.action .json-group > legend { padding: 0 4px; font-size: 12px; color: var(--muted); }
```

In `PAGE/index.html`, replace:
```
    The Vidocq dev console. Plain HTML, one stylesheet and one ES module, all served by the console itself:
    no build step, nothing loaded from another site. console.js polls api/snapshot and draws it.
```
with:
```
    The Vidocq dev console. Plain HTML, one stylesheet and one ES module, console.js, which imports the code
    editor's editor.js and editor-core.js, all served by the console itself: no build step, nothing loaded from
    another site. console.js polls api/snapshot and draws it.
```

- [ ] **Step 7: Check the syntax, then run the whole module's tests**

```bash
node --check $VIDOCQ/vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/console.js && echo syntax-ok
```
Expected: `syntax-ok`.

```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -q -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test
```
Expected: exit status 0, no `[ERROR]` line (418 tests when the plan was written, `EditorCoreTest` 45, `PageTest` 28).

- [ ] **Step 8: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(devconsole): a form for a nested object, and the code editor for every JSON argument

formShape replaces isFlatSchema, the one place the form's rule is written: a root property may now be an object
of scalars, drawn as a fieldset under its name. Required fields are starred and refused by their path
(entity.title is required); a readOnly property reads (generated), shows "generated" and is never required; a
date, time, date-time or uuid field shows the shape it expects; an optional nested object left empty is not
sent; the skeleton, a replay and masked values handle the nested object. The raw textarea is gone: the JSON mode
is the code editor, checking and completing against the argument's schema, and it sends its own text, so an id
past 2^53 is never rounded.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/console.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/console.css vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/index.html vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/PageTest.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 9: Mansart Data's entity schema says required, readOnly and the column

**Files:**
- Modify: `MD/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/EntityJson.java` (imports;
  `schema`)
- Modify (tests, same package under `MD/src/test/java`): `RunFixtures.java` (a `Task` entity, its model, a
  `TaskRepository`), `EntityJsonTest.java`, `SignatureTest.java`

**Interfaces:**
- Consumes: Mansart's `Attribute.nullable()`, `Attribute.columnName()`, `IdAttribute.generated()` (a record
  component), `VersionAttribute`, `EntityModel.entityClass()`; `EntityJson`'s private `Settable(attribute, type,
  referenced)` and `fieldType(attribute)`; `Scalars.schema(type)` (a fresh mutable map per call) and
  `Scalars.object(...)`.
- Produces: `EntityJson.schema(entity)` →
  `{"type":"object","properties":{<name>:{<scalar schema>[,"readOnly":true],"description":"…"}…},"required":[…]}`;
  description `column <column name>`, or `id of <Entity simple name>, column <column name>` for a reference.
  `RunFixtures.Task`, `RunFixtures.taskModel()`, `RunFixtures.TaskRepository`; `EntityJsonTest.TASK_SCHEMA`.
  `Signature.of` is unchanged.

- [ ] **Step 1: The fixture**

In `RunFixtures.java`, replace the imports block:
```java
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.attribute.IdAttribute;
import io.vidocq.mansart.data.dialect.attribute.JoinPath;
import io.vidocq.mansart.data.dialect.attribute.JoinedAttribute;
import io.vidocq.mansart.data.dialect.attribute.ReferenceAttribute;
import io.vidocq.mansart.data.dialect.attribute.TemporalAttribute;
import io.vidocq.mansart.data.dialect.attribute.TextAttribute;
```
with:
```java
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.attribute.EnumAttribute;
import io.vidocq.mansart.data.dialect.attribute.EnumStorage;
import io.vidocq.mansart.data.dialect.attribute.IdAttribute;
import io.vidocq.mansart.data.dialect.attribute.JoinPath;
import io.vidocq.mansart.data.dialect.attribute.JoinedAttribute;
import io.vidocq.mansart.data.dialect.attribute.NumericAttribute;
import io.vidocq.mansart.data.dialect.attribute.ReferenceAttribute;
import io.vidocq.mansart.data.dialect.attribute.TemporalAttribute;
import io.vidocq.mansart.data.dialect.attribute.TextAttribute;
import io.vidocq.mansart.data.dialect.attribute.VersionAttribute;
```

Replace:
```java
    static Slot slot(Long id, Year year, String label) {
```
with:
```java
    /**
     * Its model is {@link #taskModel()}, as an application's task is mapped: a generated id, a version, a title and a
     * level that cannot be null, notes that can, a primitive and a reference to a gizmo that cannot.
     */
    public static class Task {
        private Long id;
        private Long version;
        private String title;
        private String notes;
        private int points;
        private Level level;
        private Gizmo owner;

        public Task() {}
    }

    static Slot slot(Long id, Year year, String label) {
```

Replace:
```java
    @Repository
    public interface BrokenRepository extends BasicRepository<Broken, Long> {}
```
with:
```java
    @Repository
    public interface BrokenRepository extends BasicRepository<Broken, Long> {}

    /** Only what it inherits: the save(Task) whose schema describes a whole entity. */
    @Repository
    public interface TaskRepository extends BasicRepository<Task, Long> {}
```

Replace:
```java
    static final Set<String> ENTITIES = Set.of(Gizmo.class.getName(), Part.class.getName(), Broken.class.getName(),
            Slot.class.getName(), Tally.class.getName());
```
with:
```java
    static final Set<String> ENTITIES = Set.of(Gizmo.class.getName(), Part.class.getName(), Broken.class.getName(),
            Slot.class.getName(), Tally.class.getName(), Task.class.getName());
```

Replace:
```java
    /** The models: Part's, Slot's and Tally's by hand, the others from Mansart itself. */
    static EntityModel<?> model(Class<?> type) {
        if (type == Part.class) {
            return partModel();
        }
        if (type == Tally.class) {
            return tallyModel();
        }
```
with:
```java
    /** The models: Part's, Slot's, Tally's and Task's by hand, the others from Mansart itself. */
    static EntityModel<?> model(Class<?> type) {
        if (type == Part.class) {
            return partModel();
        }
        if (type == Tally.class) {
            return tallyModel();
        }
        if (type == Task.class) {
            return taskModel();
        }
```

Replace:
```java
    /** The model of {@link Part}, with real handles: its id, a reference to a gizmo, a label, a joined name. */
```
with:
```java
    /** The model of {@link Task}, with real handles, in the order of its fields. */
    static EntityModel<Task> taskModel() {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            IdAttribute<Task, Long> id = new IdAttribute<>("id", "id", Long.class, Task.class, true,
                    lookup.findGetter(Task.class, "id", Long.class), lookup.findSetter(Task.class, "id", Long.class));
            VersionAttribute<Task, Long> version = new VersionAttribute<>("version", "version", Long.class, Task.class,
                    lookup.findGetter(Task.class, "version", Long.class),
                    lookup.findSetter(Task.class, "version", Long.class));
            TextAttribute<Task> title = new TextAttribute<>("title", "title", Task.class, false, false, 200,
                    lookup.findGetter(Task.class, "title", String.class),
                    lookup.findSetter(Task.class, "title", String.class));
            TextAttribute<Task> notes = new TextAttribute<>("notes", "notes", Task.class, true, false, 2000,
                    lookup.findGetter(Task.class, "notes", String.class),
                    lookup.findSetter(Task.class, "notes", String.class));
            NumericAttribute<Task, Integer> points = new NumericAttribute<>("points", "points", Integer.class,
                    Task.class, false, false, 0, 0, lookup.findGetter(Task.class, "points", int.class),
                    lookup.findSetter(Task.class, "points", int.class));
            EnumAttribute<Task, Level> level = new EnumAttribute<>("level", "level", Level.class, Task.class, false,
                    false, EnumStorage.STRING, lookup.findGetter(Task.class, "level", Level.class),
                    lookup.findSetter(Task.class, "level", Level.class));
            ReferenceAttribute<Task, Gizmo> owner = new ReferenceAttribute<>("owner", "owner_id", Gizmo.class,
                    Task.class, false, false, false, lookup.findGetter(Task.class, "owner", Gizmo.class),
                    lookup.findSetter(Task.class, "owner", Gizmo.class));
            return new EntityModel<>(Task.class, "tasks", "", id, Optional.of(version),
                    List.of(id, version, title, notes, points, level, owner),
                    lookup.findConstructor(Task.class, MethodType.methodType(void.class)));
        } catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The model of {@link Part}, with real handles: its id, a reference to a gizmo, a label, a joined name. */
```

- [ ] **Step 2: Write the failing tests**

In `EntityJsonTest.java`, replace:
```java
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Part;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
```
with:
```java
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Part;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Task;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
```

Replace everything from `    /** The schema of a Gizmo, in model order, none required. */` down to and including the closing `}` of
`aReferenceTakesTheReferencedIdAndAJoinedAttributeIsLeftOut` with:

```java
    /**
     * The schema of a Gizmo, in model order: its id is neither generated nor nullable, so it is required; every other
     * column may be null, or is a primitive.
     */
    static final String GIZMO_SCHEMA = "{\"type\":\"object\",\"properties\":{"
            + "\"id\":{\"type\":\"integer\",\"description\":\"column id\"},"
            + "\"name\":{\"type\":\"string\",\"description\":\"column name\"},"
            + "\"stock\":{\"type\":\"integer\",\"description\":\"column stock\"},"
            + "\"level\":{\"type\":\"string\",\"enum\":[\"LOW\",\"HIGH\"],\"description\":\"column level\"},"
            + "\"due\":{\"type\":\"string\",\"format\":\"date\",\"description\":\"column due\"},"
            + "\"price\":{\"type\":\"number\",\"description\":\"column price\"}},\"required\":[\"id\"]}";
    /** The schema of a Task: the generated id and the version read-only, the columns that cannot be null required. */
    static final String TASK_SCHEMA = "{\"type\":\"object\",\"properties\":{"
            + "\"id\":{\"type\":\"integer\",\"readOnly\":true,\"description\":\"column id\"},"
            + "\"version\":{\"type\":\"integer\",\"readOnly\":true,\"description\":\"column version\"},"
            + "\"title\":{\"type\":\"string\",\"description\":\"column title\"},"
            + "\"notes\":{\"type\":\"string\",\"description\":\"column notes\"},"
            + "\"points\":{\"type\":\"integer\",\"description\":\"column points\"},"
            + "\"level\":{\"type\":\"string\",\"enum\":[\"LOW\",\"HIGH\"],\"description\":\"column level\"},"
            + "\"owner\":{\"type\":\"integer\",\"description\":\"id of Gizmo, column owner_id\"}},"
            + "\"required\":[\"title\",\"level\",\"owner\"]}";

    private final EntityJson entities = RunFixtures.entities();

    @Test
    void anEntityIsAnObjectOfItsColumnsTheOnesThatCannotBeNullRequired() {
        assertEquals(GIZMO_SCHEMA, Json.write(entities.schema(Gizmo.class)));
    }

    @Test
    void aReferenceTakesTheReferencedIdAndAJoinedAttributeIsLeftOut() {
        assertEquals("{\"type\":\"object\",\"properties\":{"
                + "\"id\":{\"type\":\"integer\",\"readOnly\":true,\"description\":\"column id\"},"
                + "\"gizmo\":{\"type\":\"integer\",\"description\":\"id of Gizmo, column gizmo_id\"},"
                + "\"label\":{\"type\":\"string\",\"description\":\"column label\"}},\"required\":[]}",
                Json.write(entities.schema(Part.class)));
    }

    @Test
    void aTaskSaysWhatIsRequiredWhatTheDatabaseWritesAndWhichColumnEachIs() {
        assertEquals(TASK_SCHEMA, Json.write(entities.schema(Task.class)));
    }

    @Test
    void neitherANullableColumnNorAPrimitiveNorAGeneratedIdNorTheVersionIsRequired() {
        Map<String, Object> schema = entities.schema(Task.class);

        assertEquals(List.of("title", "level", "owner"), schema.get("required"),
                "notes may be null, points is an int, id is generated, version is the version");
        Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
        assertEquals(true, ((Map<?, ?>) properties.get("id")).get("readOnly"), "a generated id");
        assertEquals(true, ((Map<?, ?>) properties.get("version")).get("readOnly"), "the version");
        assertNull(((Map<?, ?>) properties.get("title")).get("readOnly"), "a column the application writes");
        assertFalse(((List<?>) entities.schema(Gizmo.class).get("required")).contains("stock"), "an int");
    }
```

In `SignatureTest.java`, replace:
```java
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.GizmoRepository;
```
with:
```java
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.GizmoRepository;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.TaskRepository;
```
and replace:
```java
    @Test
    void anUnsupportedParameterMakesTheMethodNotRunnable() {
```
with:
```java
    @Test
    void theSaveOfATaskDescribesItsEntityForTheForm() {
        assertEquals("{\"type\":\"object\",\"properties\":{\"entity\":" + EntityJsonTest.TASK_SCHEMA
                + "},\"required\":[\"entity\"]}", signature(TaskRepository.class, "save(Task)").schema());
    }

    @Test
    void anUnsupportedParameterMakesTheMethodNotRunnable() {
```

- [ ] **Step 3: Run the tests to verify they fail**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -q -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev test -Dtest=EntityJsonTest,SignatureTest
```
Expected: FAIL — `anEntityIsAnObjectOfItsColumnsTheOnesThatCannotBeNullRequired`,
`aReferenceTakesTheReferencedIdAndAJoinedAttributeIsLeftOut`,
`aTaskSaysWhatIsRequiredWhatTheDatabaseWritesAndWhichColumnEachIs`,
`neitherANullableColumnNorAPrimitiveNorAGeneratedIdNorTheVersionIsRequired` (`required` is `null`),
`SignatureTest.theSchemaOfEachParameterAllRequired` and `theSaveOfATaskDescribesItsEntityForTheForm` (the schemas
have no description, readOnly nor required yet). Everything compiles.

- [ ] **Step 4: Write the schema**

In `EntityJson.java`, replace:
```java
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.attribute.JoinedAttribute;
import io.vidocq.mansart.data.dialect.attribute.ReferenceAttribute;
```
with:
```java
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.attribute.IdAttribute;
import io.vidocq.mansart.data.dialect.attribute.JoinedAttribute;
import io.vidocq.mansart.data.dialect.attribute.ReferenceAttribute;
import io.vidocq.mansart.data.dialect.attribute.VersionAttribute;
```
and replace:
```java
    /**
     * The JSON Schema of {@code entity}: an object, one property per settable attribute, none required.
     *
     * @throws RuntimeException when its model cannot be read, as {@code EntityModels.of} throws it
     */
    Map<String, Object> schema(Class<?> entity) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Settable> property : settable(model(entity)).entrySet()) {
            properties.put(property.getKey(), Scalars.schema(property.getValue().type()));
        }
        return Scalars.object("type", "object", "properties", properties);
    }
```
with:
```java
    /**
     * The JSON Schema of {@code entity} (editor spec §6): an object, one property per settable attribute, each with
     * its column as {@code description}, {@code id of <Entity>, column <name>} for a reference. A generated id and the
     * version are {@code readOnly}: the database writes them. Any other attribute is {@code required} when its column
     * is not nullable and its field is not primitive, which always holds a value.
     *
     * @throws RuntimeException when its model cannot be read, as {@code EntityModels.of} throws it
     */
    Map<String, Object> schema(Class<?> entity) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Map.Entry<String, Settable> entry : settable(model(entity)).entrySet()) {
            Settable property = entry.getValue();
            Attribute<?, ?> attribute = property.attribute();
            Map<String, Object> schema = Scalars.schema(property.type());
            if (attribute instanceof IdAttribute<?, ?> id && id.generated()
                    || attribute instanceof VersionAttribute<?, ?>) {
                schema.put("readOnly", true);
            } else if (!attribute.nullable() && !fieldType(attribute).isPrimitive()) {
                required.add(entry.getKey());
            }
            schema.put("description", (property.referenced() == null ? ""
                    : "id of " + property.referenced().entityClass().getSimpleName() + ", ")
                    + "column " + attribute.columnName());
            properties.put(entry.getKey(), schema);
        }
        return Scalars.object("type", "object", "properties", properties, "required", required);
    }
```
(`fieldType` is the existing private helper: for a reference it is the entity class, never primitive; `ArrayList`
and `List` are already imported.)

- [ ] **Step 5: Run the module's tests to verify they pass**

Run:
```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -q -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev test
```
Expected: exit status 0, no `[ERROR]` line (159 tests when the plan was written: `EntityJsonTest` 11, `SignatureTest` 9;
the other suites unchanged).

- [ ] **Step 6: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(mansart-data): the entity schema says what is required, generated, and which column

EntityJson.schema now marks required an attribute whose column is not nullable and whose field is no primitive,
readOnly the generated id and the version (never required), and describes each with its column, "id of <Entity>,
column <name>" for a reference, so that the dev console's form stars, marks and explains the fields of
save(entity). Signature.of and the conversion of what the page sends are unchanged.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 10: Documentation

**Files:**
- Modify: `docs/en/modules/ROOT/pages/dev-console.adoc` (the MCP inspector's form paragraph; new `#json-editor` and
  `#json-form` sections before `[#security]`)
- Modify: `docs/en/modules/ROOT/pages/dev-console-panels.adoc` (`#json-argument`)
- Modify: `docs/en/modules/ROOT/pages/modules/vidocq-runtime-extensions.adoc` (`#mansart-data-run-method`)
- Modify: `docs/en/modules/ROOT/pages/whats-new.adoc`
- Modify: `SPI/src/main/java/io/vidocq/runtime/spi/devconsole/PanelAction.java` (Javadoc only)

**Interfaces:**
- Consumes: the behaviour of Tasks 1-9 (wording: `1 error, 2 warnings`, `entity.title is required`,
  `id (generated)`, placeholders `2026-09-30`, `14:30:00`, `2026-09-30T14:30:00`,
  `123e4567-e89b-12d3-a456-426614174000`, `past 100 000 characters: no colours, no checks`,
  `not formatted: line <n>: …`).
- Produces: anchors `dev-console.adoc#json-editor` and `dev-console.adoc#json-form`.

- [ ] **Step 1: `dev-console.adoc`, the MCP inspector's form**

Replace the paragraph:
```
A tool's arguments are a form generated from its input schema when the schema is flat — its root is
`"type": "object"` and every property is a `string`, `number`, `integer` or `boolean`, or an `enum` of strings, with
no `$ref` and no nesting — and a raw JSON editor otherwise, started from its required properties. A
"JSON" switch shows the same values as JSON on a flat form too, and switching back keeps them. A prompt's arguments
and a resource template's variables are always a flat form, one `string` per argument or variable.
```
with:
```
A tool's arguments are a <<json-form,generated form>> when its input schema allows one — scalars, enums of strings,
and objects of those one level down — and the <<json-editor,JSON editor>> otherwise, started from its required
properties, which checks and completes them against the schema. A "JSON" switch shows the same values in that editor
on a form too, and switching back keeps them. A prompt's arguments and a resource template's variables are always a
flat form, one `string` per argument or variable.
```

- [ ] **Step 2: `dev-console.adoc`, the editor and the form**

Insert right before the line `[#security]`:
```
[#json-editor]
== JSON editor [.tag-new]#NEW#

A `json` argument of an action — an MCP tool's arguments in the <<mcp-inspector,MCP inspector>>, the arguments of a
repository method in the *Mansart Data* tab — is typed in a small code editor written for the page, which loads
nothing from elsewhere. It reads the argument's JSON Schema as you type:

* **Colours.** Keys, strings, numbers, `true`, `false` and `null` take the <<json-viewer,JSON viewer>>'s colours, in
the light and the dark themes; what is no JSON at all is red. The lines are numbered.
* **Diagnostics.** The first syntax error — `unterminated string`, `expected ',' or '}'`, `trailing comma`,
`nothing after the value`… — then what the schema says, at every depth: a missing `required` key (marked on its
object's `{`), a value of the wrong `type` (`integer` refuses `1.5`), a value outside its `enum`, a string longer
than `maxLength` are errors; a key its object's `properties` does not list, and a string that does not look like its
`format` (`date`, `time`, `date-time`, `uuid`), are warnings. Each is underlined, red or orange, with a dot on its
line; its message shows when the caret or the pointer is on it, and the line under the editor counts them:
`1 error, 2 warnings`. They never stop a call: the server stays the judge.
* **Completion.** `Ctrl+Space` lists what fits where the caret is, and a `"` typed where a key goes opens the list by
itself. In an object, the keys of its schema it does not have yet, required first and read-only last, each with its
type, `required` or `generated`, and its description; the value comes with the key, an object with its required
keys. After a `:` or in an array: the `enum` values, `true` and `false`, `null`, `{}` or `[]`. `Up` and `Down` move,
`Enter` or `Tab` or a click accepts, `Escape` closes, and typing filters the list.
* **Keys.** An opening bracket or quote brings its closing one, a closing character typed before the same steps over
it, `Backspace` between an empty pair deletes both, `Enter` keeps the line's indentation and opens an indented line
between `{}` or `[]`, `Tab` and `Shift+Tab` indent and outdent the selected lines by two spaces. `Escape` then `Tab`
leaves the editor. The bracket next to the caret and its match are outlined.
* **Format.** The *Format* button, or `Shift+Alt+F`, indents the JSON by two spaces, one member per line, and keeps
every string and number as written: an id past 2^53 or a `\u00e9` escape does not change. A text that does not
parse is not formatted, and the line under the editor says why: `not formatted: line 3: trailing comma`.
* **Undo.** Every edit the editor makes — an accepted completion, a formatting, a closed pair — is undone by one
`Ctrl+Z` (`⌘Z`).

The editor reads these keywords of the schema: `type` (one name or a list), `properties`, `required`,
`additionalProperties`, `items` (a schema, or a list for the positions of an array), `enum`, `maxLength`, `format`,
`readOnly`, `description`, and a `$ref` to `#/$defs/…` or `#/definitions/…`. Any other `$ref` is not followed; what
is under `anyOf`, `oneOf`, `allOf`, `not` or `patternProperties` is accepted as it is. A schema the editor does not
understand makes it check less, never fail. What you typed is sent as you typed it. Past 100 000 characters the
editor stops colouring and checking, and says `past 100 000 characters: no colours, no checks`; completion and
*Format* still work.

[#json-form]
=== The generated form [.tag-new]#NEW#

When the schema allows it, a `json` argument is a form rather than JSON, with a *JSON* switch to the editor that
keeps the values both ways. The schema's root is `"type": "object"`, with no `$ref`, `anyOf`, `oneOf`, `allOf` or
`not`, and each property is a `string`, `number`, `integer` or `boolean`, an `enum` of strings, or an object whose own
`properties` all are such: one level of nesting, drawn as a group of fields under the object's name. A string of
`"format": "textarea"` is a field of several lines (xref:dev-console-panels.adoc#json-argument[A JSON argument]). Any
other schema gets the editor alone.

* A required property is starred, `title *`, and a call without it is refused with its path, before anything is
sent: `entity.title is required`. A nested object that is not required and left empty is not sent at all.
* A `readOnly` property, such as an id the database generates, reads `id (generated)`, its field showing
`generated`. It is never required: left empty it is not sent; filled, it is.
* A field of `"format"` `date`, `time`, `date-time` or `uuid` shows the shape it expects: `2026-09-30`, `14:30:00`,
`2026-09-30T14:30:00`, `123e4567-e89b-12d3-a456-426614174000`.
* The editor starts from the required properties, at each level. *Replay* fills the form, a nested object included;
a value the panel masked is left for you to type again.

```

- [ ] **Step 3: `dev-console-panels.adoc`, a JSON argument**

Replace:
```
The page renders it as a form generated from the schema when the schema is flat (xref:dev-console.adoc#mcp-inspector[the
rule the MCP inspector uses]) and as a raw JSON editor otherwise; the console never validates the value against the
schema itself — the action's target does. The MCP inspector's own tool action is a short example
```
with:
```
The page renders it as a form generated from the schema when the schema allows one, an object of scalars one level
down included (xref:dev-console.adoc#json-form[the generated form] [.tag-new]#NEW#), and as the
xref:dev-console.adoc#json-editor[JSON editor] [.tag-new]#NEW# otherwise, which colours, checks and completes the
value against the schema, whose keywords that page lists; the console never validates the value against the schema
itself — the action's target does. The MCP inspector's own tool action is a short example
```

- [ ] **Step 4: The extensions page, Mansart Data's arguments and entities**

In `modules/vidocq-runtime-extensions.adoc`, section `[#mansart-data-run-method]`, replace the sentence
```
The page shows a form, or a JSON editor when a parameter is an entity.
```
with:
```
The page shows a form, an entity as a group of fields in it [.tag-new]#NEW#: one per column, a column that cannot be null starred as required, the generated id and the version marked `(generated)`, each field's tooltip naming its column (xref:dev-console.adoc#json-form[The generated form]). Its *JSON* switch shows the same arguments in the xref:dev-console.adoc#json-editor[JSON editor], which completes an entity's attributes and checks their types.
```
and, in the table row of `An entity of a repository`, replace:
```
| An object, one property per column, each optional: an absent one keeps what the entity's no-arg constructor sets, so that an absent generated `id` is generated. A reference to another entity takes that entity's id. The entity is built through Mansart's own model, as Mansart builds it from a row.
```
with:
```
| An object, one property per column. Its schema [.tag-new]#NEW# says `required` for a column that cannot be null and whose field is not a primitive, `readOnly` for the generated id and the version, and names each column in `description` (`id of Gizmo, column gizmo_id` for a reference); the console does not enforce it: an absent property keeps what the entity's no-arg constructor sets, so that an absent generated `id` is generated. A reference to another entity takes that entity's id. The entity is built through Mansart's own model, as Mansart builds it from a row.
```

- [ ] **Step 5: `whats-new.adoc`**

Insert, right before the line that starts with `* **CSV export and import for Mansart Data** [.tag-new]#NEW#`:
```
* **A code editor for JSON arguments, and a form for an entity** [.tag-new]#NEW# — in the dev console, every `json` argument of an action, an MCP tool's arguments as a Mansart Data repository method's, is typed in a code editor written for the page: colours, line numbers, the first syntax error and then what the argument's JSON Schema says at every depth (a missing required key, a wrong type, a value outside its enum, a key the schema does not list, a date that does not look like one), underlined with its message; completion of keys and values with `Ctrl+Space`; *Format* (`Shift+Alt+F`), which keeps numbers and strings as written; brackets and quotes closed as you type; one `Ctrl+Z` per edit it makes. The generated form takes one level of nesting, so `TaskRepository.save(entity: Task)` gets fields: the required ones starred and refused by their path when empty, the generated id marked `(generated)`, dates with the shape they expect. Mansart Data's entity schema now says which attributes are `required` and `readOnly`, and names each column. xref:dev-console.adoc#json-editor[JSON editor], xref:dev-console.adoc#json-form[The generated form].
```

- [ ] **Step 6: `PanelAction` Javadoc**

In `PanelAction.java`, replace:
```java
     * page offers as a list; a whole match of {@code pattern}, which it offers as a text field; or a JSON object
     * described by the JSON Schema {@code schema}, which it offers as a form when the schema is flat and as a JSON
     * editor otherwise. Exactly one of the three is set.
```
with:
```java
     * page offers as a list; a whole match of {@code pattern}, which it offers as a text field; or a JSON object
     * described by the JSON Schema {@code schema}, which it offers as a form when the schema allows one and as a JSON
     * editor otherwise (see {@link #json}). Exactly one of the three is set.
```
and replace:
```java
         * An argument whose value is a JSON object, such as the arguments of an MCP tool. The action receives it as
         * its JSON text. One action has one such argument at most.
         *
```
with:
```java
         * An argument whose value is a JSON object, such as the arguments of an MCP tool. The action receives it as
         * its JSON text. One action has one such argument at most.
         *
         * <p>The page reads these keywords of {@code schema}: {@code type}, {@code properties}, {@code required},
         * {@code additionalProperties}, {@code items}, {@code enum}, {@code maxLength}, {@code format} ({@code date},
         * {@code time}, {@code date-time}, {@code uuid}; {@code textarea} for a field of several lines),
         * {@code contentMediaType} ({@code text/csv}), {@code readOnly}, {@code description}, {@code default}, and a
         * local {@code $ref} ({@code #/$defs/...}, {@code #/definitions/...}). It generates a form when the root is an
         * object whose properties are scalars, enums of strings, or objects of those one level down, and offers its
         * JSON editor otherwise, which checks and completes the value against the schema; neither refuses to send
         * it: the action's target stays the judge.
         *
```

- [ ] **Step 7: Check**

```bash
cd $VIDOCQ && grep -c "json-editor\|json-form" docs/en/modules/ROOT/pages/dev-console.adoc docs/en/modules/ROOT/pages/dev-console-panels.adoc docs/en/modules/ROOT/pages/modules/vidocq-runtime-extensions.adoc docs/en/modules/ROOT/pages/whats-new.adoc && grep -n "^== JSON editor \[.tag-new\]#NEW#$\|^=== The generated form \[.tag-new\]#NEW#$" docs/en/modules/ROOT/pages/dev-console.adoc
```
Expected: a non-zero count for each of the four pages, and both headings printed with their badge.

```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu -q -pl vidocq-runtime-devconsole-spi compile
```
Expected: exit status 0 (the Javadoc change compiles).

- [ ] **Step 8: Commit**

```bash
cd $VIDOCQ && cat > .git/PLAN_COMMIT_MSG <<'EOF'
docs(devconsole): the JSON editor, the generated form and the entity schema

dev-console.adoc gets a JSON editor section (colours, diagnostics, completion, keys, Format, undo, the schema
keywords it reads, the size limit) and a generated form section (the rule, one level of nesting, required
starred and refused by path, readOnly as generated, format placeholders). The MCP inspector, the JSON argument of
the panels guide, Mansart Data's arguments and entity row, PanelAction.Argument.json's Javadoc and What's new
follow.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add docs/en/modules/ROOT/pages/dev-console.adoc docs/en/modules/ROOT/pages/dev-console-panels.adoc docs/en/modules/ROOT/pages/modules/vidocq-runtime-extensions.adoc docs/en/modules/ROOT/pages/whats-new.adoc vidocq-runtime-devconsole-spi/src/main/java/io/vidocq/runtime/spi/devconsole/PanelAction.java && git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 11: Verification — builds, a consumer, and the editor and the form in Chrome (spec §7)

**Files:** none changed, unless a check fails (then fix in the owning task's files, re-run its tests, and commit with a
`fix(devconsole): …` or `fix(mansart-data): …` message following the Global Constraints).

**Interfaces:**
- Consumes: everything above; the test application `$LC4JCDI/mcp-tasks-server`
  (PostgreSQL through the Vidocq dev service: Docker must run); the helper
  `$SCRATCH/dev-run.sh`
  (`dev-run.sh <project dir> <log>`: `vidocq:dev` of that project, the application on 18093, the console on 18094,
  no debugger, no continuous testing).
- Produces: nothing; a report.

- [ ] **Step 1: Install what the test application resolves**

```bash
cd $VIDOCQ && JAVA_HOME=$JAVA25 mvn -nsu install -pl .,vidocq-runtime-devconsole-spi,vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension,vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev 2>&1 | grep -E "Tests run:|FAIL|BUILD" | tail -8
```
(`timeout: 600000`.) Expected: `BUILD SUCCESS`, no failure. (`.` installs the root POM, whose `dependencyManagement`
changed.)

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

- [ ] **Step 4: Start `vidocq:dev`**

Run with the Bash tool and `run_in_background: true` (the only background Maven run of this plan):
```bash
bash $SCRATCH/dev-run.sh $LC4JCDI/mcp-tasks-server $SCRATCH/mcp-tasks-dev.log
```
Wait with the Monitor tool on an until-loop (never a foreground `sleep`):
`until grep -qE "Vidocq dev console: http://127.0.0.1:18094/|BUILD FAILURE|Exception in thread" $SCRATCH/mcp-tasks-dev.log; do sleep 2; done`.
Then `grep -E "Dev tools:|MANSART-DATA|BUILD FAILURE" <that log> | head` — expected: the console and
`vidocq-runtime-mansart-data-extension-dev` among the dev tools, no failure.

- [ ] **Step 5: The editor and the form in Chrome (spec §7) — a manual check, no code**

Load the tools in one call: `ToolSearch("select:mcp__claude-in-chrome__tabs_context_mcp,mcp__claude-in-chrome__navigate,mcp__claude-in-chrome__computer,mcp__claude-in-chrome__read_page,mcp__claude-in-chrome__tabs_create_mcp,mcp__claude-in-chrome__tabs_close_mcp,mcp__claude-in-chrome__javascript_tool,mcp__claude-in-chrome__find,mcp__claude-in-chrome__form_input,mcp__claude-in-chrome__resize_window,mcp__claude-in-chrome__read_console_messages")`.
Remember: the editor draws in an animation frame, which a hidden MCP tab only runs when a screenshot is taken; take a
screenshot or a zoom before reading what it drew. On macOS the undo key is `cmd+z`. Tick each item, and screenshot
the ones marked (S).

1. `tabs_context_mcp`, `tabs_create_mcp`, `navigate` to `http://127.0.0.1:18094/`. Run with `javascript_tool`:
   `Object.defineProperty(document, 'hidden', {value: false, configurable: true}); Object.defineProperty(document, 'visibilityState', {value: 'visible', configurable: true}); document.dispatchEvent(new Event('visibilitychange')); 'visible'`.
   `read_console_messages` with pattern `error|Error`: nothing from `console.js`, `editor.js` or `editor-core.js`.
2. *Mansart Data* → the `TaskRepository` sub-tab → pick `save(Task)` in its list. The form shows a group `entity *`
   holding one field per column: `id (generated)` whose field shows `generated`; the columns that cannot be null
   starred (`title *`, `project *`, `status *`, `priority *`, `createdAt *`, `updatedAt *`); `dueDate` showing
   `2026-09-30`; `createdAt` showing `2026-09-30T14:30:00`; hovering a field shows its column (`column title`). (S)
3. Run it with every field empty: the line says `entity.title is required` and no request is sent (the history gains
   no row).
4. *Query* `SELECT COUNT(this) FROM Task` in the *JDQL* tab: note the count `C`. Back to `save(Task)`: fill `title`
   `editor check`, `project` `vidocq`, `status` and `priority` (first values), `createdAt` and `updatedAt`
   `2026-09-30T14:30:00Z`, *Transaction* `rollback`; run, confirm: the answer is the saved task with a new id, and the
   transaction rolled back; the *JDQL* count is still `C`.
5. Tick *JSON*: the editor shows the same values, coloured, numbered, the note under it empty. Delete the comma after
   one member: a red wavy underline where the next key starts, a red dot on its line, `1 error` under the editor; put
   the caret on the underline: the tooltip `expected ',' or '}'`; hover it with the pointer: the same tooltip. (S)
   `cmd+z` restores the comma.
6. Put the caret after the `{` of `entity` and press `ctrl+space`: the list offers the keys `entity` does not have,
   `id` last with `integer, generated — column id`; `Down`, `Enter`: the key is inserted with its value started, the
   caret inside it. `cmd+z` once: exactly that insertion is undone. (S)
7. After a `,` in `entity`, type `"`: the list opens by itself; type `du`: only `dueDate` is left; `Escape` closes it.
8. Type `[` somewhere a value goes: `[]`, the caret between; `Backspace`: both gone. `cmd+z` then `cmd+shift+z` do not
   leave a stray bracket.
9. Break the JSON (a trailing comma), press *Format*: the note says `not formatted: line <n>: trailing comma`. Fix it,
   press `shift+alt+f`: the JSON is indented two spaces, one member per line; `cmd+z` once restores the previous
   text. (S)
10. Put the caret at the end of the longest line, near the editor's right edge, press `ctrl+space` where a key goes:
    the list stays inside the editor's width. `resize_window` to 700 × 800 and repeat: still inside. Make the editor
    scroll (paste 40 lines, or type Enter many times), scroll it, open the list at a visible caret: it sits at the
    caret; near the window's bottom it opens above the caret. `resize_window` back.
11. Press `Escape`, then `Tab`: the focus leaves the editor (to *Format*). Click back in, press `Escape`, then
    `shift+tab`: the focus leaves backwards.
12. Untick *JSON*: the form shows the values typed in the editor.
13. In the `TaskRepository` history, *Replay* the call of item 4: the form, `entity` group included, is filled;
    nothing is sent.
14. The `mcp` tab → *Tools* → pick a tool that takes arguments: its form (or the editor when its schema needs one).
    Tick *JSON*, `ctrl+space` inside the object: its argument names are offered. (S)
15. Run with `javascript_tool` `document.documentElement.dataset.theme = "dark"; 'dark'`: the editor's colours,
    underlines, list and tooltip are readable (S); then `delete document.documentElement.dataset.theme; 'auto'`.
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
git -C $VIDOCQ log --oneline -12
```
Expected: the tree clean (but for files that were untracked before this plan), this plan's ten commits on
`feat/devconsole-editor`. Nothing pushed. Report: the branch to push, the screenshots, the count `C`, and any item of
Step 5 that did not behave as written.

---

## Self-review (done while writing)

- **Spec coverage.** §1 in scope: both modules (Tasks 1-7), the JSON language (1-6), the editor for every json
  argument (8), the one-level form with `readOnly` and `format` (8), Mansart's `required`/`readOnly`/`description` (9),
  GraalJS test scope (1), the docs (10); out of scope respected (no query language, `format: textarea` fields stay
  textareas, no deeper forms, arrays or `anyOf` in forms, the viewer untouched, no multi-cursor/search/folding). §2:
  files, contract, `PageTest.FILES`, the core without DOM, `keystroke` (1, 6, 7). §3.1 tokens (1); §3.2 syntax (2) and
  every row of the table plus `anyOf`/… and the `$ref` rules (3); §3.3 (4); §3.4 (5); §3.5 every key (6), `Escape` then
  `Tab` (7). §4: structure, diagnostics, list, bracket, Format and `Shift+Alt+F`, undo, limit, one frame (7). §5:
  `formShape`, fieldset, required with path, optional empty object not sent, `readOnly`, `format` placeholders,
  switch/skeleton/fill/masked, editor for a refused schema (8). §6 (9). §7: `EditorCoreTest` bullets (1-6),
  `PageTest` (1, 7, 8), Mansart tests (9), browser (11). §8 (10). §9: odd schemas (3), offsets with a caret in a
  string, `//`, CRLF, tabs, emoji (1, 2, 4), undo (7, 11), the list's placement (7, 11), `__proto__` (3, 4, and
  `valuesOf`'s prototype-less object in 8).
- **Placeholders.** None: every code step has its code, every run step its command and expectation.
- **Types and names.** `jsonLanguage`, `keystroke`, `FORMAT_EXAMPLES`, `INDENT` (core) → re-exported `jsonLanguage`,
  `FORMAT_EXAMPLES` and `createEditor` (editor.js) → the one import line of `console.js`. `createEditor` returns
  `{ root, textarea, value(), setValue(text), disable(on), focus() }`, used as `editor.root`, `editor.textarea.name`,
  `editor.value()`, `editor.setValue(…)`, `editor.disable(on)` in `jsonField`. `formShape` fields
  `{ name, kind, definition, required, readOnly, fields }` are the keys of `inputs` and what `valuesOf`, `requireAll`
  and `toForm` walk. `EditorCoreTest` helpers `tokens`, `diagnose`, `complete`, `details`, `completion`, `format`,
  `press` and the `TASK` constant are each defined in the task that first uses them.
- **Verified while planning.** The code blocks of this very plan were extracted and applied, step by step, to the
  files of `HEAD`: every RED and GREEN of Tasks 1-6 ran under GraalJS and failed and passed exactly as their
  *Expected* lines say (Task 3's RED: 10 failures, `anotherRefIsNotFollowedAndNothingUnderAnyOfIsChecked` passing;
  Task 5's: 4 errors and 1 failure; Task 6's: `NullPointerException`); with Tasks 1-9 applied, `mvn test` passed for DC
  (418 tests: `EditorCoreTest` 45, `PageTest` 28, `DevConsoleExtensionTest` 22) and for MD (159 tests), `node --check`
  accepted `console.js` and `editor.js`, and no new line of the new files passed 120 characters; the repository was then
  restored (and the stale `editor*.js` of those runs removed from `target/`). Task 10's replaced texts were checked to
  occur exactly once. The browser behaviour was checked on a scratch copy of the page (see *Testing honesty*); Task 11
  repeats it on the application.
- **Note for the executor.** If a `target/classes/META-INF/resources/devconsole/editor.js` exists before Task 7 (a
  stale build output), Task 7's RED will not show: delete it, or run that RED with `clean test`.

