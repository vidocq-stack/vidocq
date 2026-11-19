# Dev console action groups as tabs, and a JSON viewer — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A dev-console panel whose actions have groups (the `mcp` panel) shows a *Monitoring* sub-tab plus one
sub-tab per group, each group tab picking one action in a combo, showing its form, its result in a block apart and
the group's call history; every JSON the page shows goes through a coloured, collapsible JSON viewer.

**Architecture:** All the work is in the page, `console.js` and `console.css` (vanilla ES module, no build, no
dependency), driven generically by `PanelAction.group` (ADR 0001 unchanged, no SPI record or server change). An
action row stops owning where its outcome shows: it publishes a result record to an *outlet* — the inline one keeps
today's look for ungrouped actions, the group one stores results in a per-panel page state that outlives polls and
redraws. The panel view builds the sub-tabs once per structure and only toggles `hidden` between them, so forms
survive polls exactly as today.

**Tech Stack:** vanilla JavaScript (ES2022, DOM APIs only), CSS custom properties, JUnit 5 (`PageTest`, text
assertions on the served resources), Java 25, Maven 3.9, AsciiDoc.

**Spec:** `docs/superpowers/specs/2026-09-28-console-action-tabs-design.md` — the binding authority; § numbers below
refer to it. Context: `docs/superpowers/specs/2026-09-26-mcp-inspector-design.md`, `docs/adr/0001-dev-console-actions.md`.

## Global Constraints

- **Toolchain.** Java 25. Every Maven command starts with
  `export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH;` and uses
  `mvn -nsu` — never `./mvnw` or `mvnw`. Run from the repository root `/Users/yblazart/projects/perso/vidocq/vidocq`.
- **Branch.** `feat/console-action-tabs`, already checked out. Do not switch branches. Never push.
- **Module paths** (the commands below spell them out in full):
  - SPI: `vidocq-runtime-devconsole-spi`
  - CONSOLE: `vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension`
  - DEV: `vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-langchain4j-cdi-mcp-extension-dev`
  - IT: `vidocq-runtime-integration-tests/vidocq-runtime-it-langchain4j-cdi-mcp`
  - PAGE: `$CONSOLE/src/main/resources/META-INF/resources/devconsole` (`console.js`, `console.css`, `index.html`)
  - PageTest: `$CONSOLE/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/PageTest.java`
- **Out of scope (§1):** no change to `PanelAction`, `ActionResult`, `PanelSample` fields or to any server class; no
  MCP-specific page code; no JSON editor for the input (the raw input stays a `textarea`); no JavaScript test harness.
- **Page rules (PageTest enforces them, keep them green):**
  - never `innerHTML`, `outerHTML`, `insertAdjacentHTML`, `document.write`, `eval(`, `new Function`,
    `setAttribute("style"`, `srcdoc`; text only through `textContent`, text nodes or attribute values;
  - no call named `confirm(`, `alert(` or `prompt(` (the regex `(?<![\w.])(confirm|alert|prompt)\(` must not match);
  - `localStorage` appears on exactly two non-comment lines, `stored` and `store`: the new sub-tab key goes through
    those two functions;
  - exactly three lines contain `= Object.create(null);` (`skeleton`, `formObject`, the request body): the viewer
    must not add one (`JSON.parse` already stores a `"__proto__"` key as an own property, and `Object.entries`
    reads it);
  - keep verbatim: `const REPLAY_COLUMN = "replay"`, `const MASKED = "***"`, `const FILTER_FROM = 10`, `"Exchange"`,
    `function replayButton(panelId, cell)`, `function isFlatSchema(schema)`, `function jsonField(argument)`,
    `tr.append(el("td", /^\d+$/.test(cell) ? "n" : null, cell));`, `i === replayAt && buttons ? "" : column`,
    `method: "POST"`, `"Content-Type": "application/json"`, `"X-Vidocq-Console-Token": token`,
    `console.actionToken`, `fetch("api/action/`.
- **CSP stays `default-src 'self'`:** nothing inline in `index.html`, nothing loaded from elsewhere, no `url(` in
  CSS. `index.html` needs no change.
- **Theme tokens:** new colours are `--json-key`, `--json-string`, `--json-number`, `--json-literal`, `--json-punct`,
  defined in `:root`, in `@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) {…} }` and in
  `:root[data-theme="dark"]`, like the existing ones.
- **Wording kept from today (§2.2, §5):** `No token: reload the page.`, `running…`, `failed: <class>`,
  `still running after 60 s: the outcome will show here`, `another action of this panel is running`,
  `refused (<status>)[: <text>]`, `the console did not answer`.
- **New UI wording (§2, §3):** `Monitoring`, `No call yet`, `Expand all`, `Collapse all`, `Copy`, `Copied`,
  `Clipboard refused`, `{…} 3 keys`, `[…] 12 items`, `1 key`, `1 item`, state words `ok`, `error`, `running…`.
- **Viewer numbers (§3):** first two levels open; only the first when the document has more than 500 values
  (`JSON_BIG = 500`).
- **Code style:** English, lines at most 120 characters, the comment density of the file around; follow the style of
  the file you edit (no formatter). No new dependency.
- **Ports:** only 18090-18099 for anything this plan starts; never 8080 or 8888. Check with `lsof` first. Never kill
  a process this plan did not start.
- **Commits:** every commit is `git commit -S -F <msgfile>` (never `-m`, never `-s`), the message ending with
  exactly these three lines:
  ```
  Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
  ```
  Write the message file with a heredoc into `.git/PLAN_COMMIT_MSG` (inside `.git`, never tracked) and delete it
  after the commit.
- **Docs:** keep the existing prose style; update the existing `whats-new.adoc` entry *An MCP inspector in the dev
  console* (no new entry); the new `#json-viewer` section is tagged `[.tag-new]#NEW#`.

## Rulings (where this plan settles what the spec leaves open)

1. **§7's `innerHTML` test already exists** (`PageTest.theScriptNeverParsesTextAsMarkup` checks `innerHTML`,
   `outerHTML`, `insertAdjacentHTML`, `document.write` and more). It is kept as is; the only new spec test is the
   `--json-*` token test (Task 1). The other new tests are structural text assertions that pin the functions and
   rules this plan introduces.
2. **"From 10 actions" (§2.1) reads as today's `rows.length > FILTER_FROM`** ("past ten", as the code, its javadoc
   and `dev-console-panels.adoc` say), counted **per group** in a group tab, and over the ungrouped actions in
   *Monitoring*.
3. **A history row whose replay cell names no action id** — an unparsable cell, or one the server emptied for being
   longer than `MAX_REPLAY_CELL` — cannot be placed in a group, so it is dropped from every group tab, like a row
   whose action a dev reload removed (§2.3). Documented in `dev-console-panels.adoc#replay-column`.
4. **Viewer toolbars:** the header toolbar (§3) acts on the body. The *Exchange* details (also through the viewer,
   §3) get their own *Expand all / Collapse all / Copy* at the top of their fold, so both documents can be folded
   and copied. A body that is text has no toolbar.
5. **Round-trip time** is shown for every answer the console gave (200, 202, 409, 500, other refusals), not for a
   network failure nor a validation error (no request was answered). It appears in the group tab's header only; the
   inline bar of ungrouped actions keeps today's look (line and body, no time).
6. **Server outcome vs. the page's own result:** the snapshot's `last` of an action still updates the line (with its
   time of day, as today). When the result on screen came from this page's call, the body, round trip, fold state and
   *Exchange* state are kept; otherwise the server's line replaces it without a body. A refusal is not overwritten
   by an older server outcome on a redraw (the row starts from the time its result reflects).
7. **Results outlive a dev reload** for actions that still exist (§4 only forgets orphans); the viewer's node state
   lives in the result, so it is forgotten exactly when a new result replaces it (§3).
8. **Collapse all collapses the root too**, which then reads `{…} n keys`.
9. **Integers past 2^53** keep the text the server sent (via `JSON.parse`'s `context.source`), shown as is and copied
   with `JSON.rawJSON` where the browser has it — an inspector must not print a different id than the server sent.
10. **Manual check target:** no in-repo MCP application declares the `vidocq-runtime-maven-plugin` (the MCP IT app
    is launched by `LaunchedServer`, not `vidocq:dev`), so the Chrome check uses
    `~/projects/perso/vidocq-tools/lc4jcdi-on-vidocq/mcp-time-server` (2 tools, 1 prompt, 1 resource template: the
    four tabs), and the in-repo `vidocq-runtime-examples/vidocq-runtime-mansart-h2-example` for the migration panel.

## Review Focus

1. **A dev reload that removes the selected action, or a whole group** — the combo falls back to the group's first
   action, the orphan's result is gone, and a vanished group sends the panel back to *Monitoring*, with no stale form
   or blank tab. Pinned by Task 4's test (`if (!byGroup.has(state.tab)) state.tab = MONITORING;`,
   `state.results.delete(id)`) and Task 3's (`byId.has(state.chosen.get(name))`), and the manual reload checks.
2. **Typing in a group-tab form, then waiting several polls, switching action or sub-tab and coming back** — what
   was typed is still there (rows are built once per structure and only moved in and out of the slot). Pinned by
   Task 3's test (`formSlot.replaceChildren(byId.get(id).root)`) and Task 4's (sub-tabs toggle `hidden`, see
   `body.hidden = tabId !== id`).
3. **A result body declared `application/json` that does not parse, or *Exchange* details that are SSE text** — shown
   as text, never an empty block. Pinned by Task 1's test on `textOrJson`'s fallback line.
4. **A JSON integer larger than 2^53 (ids, nanosecond timestamps)** — shown and copied as the server sent it. Pinned
   by Task 1's test (`context.source`, `JSON.rawJSON`).
5. **A group tab whose history has no row of its own** (first visit, other groups' calls only, rows whose replay
   cell is empty) — says `No call yet` instead of an empty table header. Pinned by Task 3's test
   (`if (keep && !kept) return el("p", "absent", "No call yet");`).

## File Structure

| File | Change | Responsibility |
|------|--------|----------------|
| `PAGE/console.js` | modify | JSON viewer (Task 1); result records and outlets (Task 2); group tab, result block, filtered history (Task 3); sub-tabs, page state, fallbacks (Task 4) |
| `PAGE/console.css` | modify | `--json-*` tokens and viewer style (Task 1); combo, result block (Task 3); sub-tab bar, removal of `.action-group` (Task 4) |
| `PageTest.java` | modify | one new test per JS task, plus the §7 token test |
| `SPI/src/main/java/io/vidocq/runtime/spi/devconsole/PanelAction.java` | modify | `group` javadoc (Task 5) |
| `SPI/src/main/java/io/vidocq/runtime/spi/devconsole/PanelSample.java` | modify | `REPLAY_COLUMN` javadoc (Task 5) |
| `docs/en/modules/ROOT/pages/dev-console.adoc` | modify | `#mcp-inspector` rewritten, new `#json-viewer` (Task 6) |
| `docs/en/modules/ROOT/pages/dev-console-panels.adoc` | modify | groups are tabs, replay tables move (Task 6) |
| `docs/en/modules/ROOT/pages/whats-new.adoc` | modify | the existing MCP inspector entry (Task 6) |

`console.js` stays one file: the page is one ES module by design (no build, `index.html` loads one script), and the
new code is placed in sections next to what it replaces.

**Testing honesty.** There is no JavaScript harness and this plan adds none (§7). The TDD gates are `PageTest` text
assertions plus `node --check` (a syntax check with the local Node, not a project dependency). They prove the code is
there and keeps the page rules; they do not prove it behaves. Behaviour is verified by the manual Chrome task
(Task 7), which is not optional.

---

### Task 1: JSON viewer component and its colour tokens

**Files:**
- Modify: `PAGE/console.css` (the three token blocks at the top; a new viewer section after the `pre.result-body` rule)
- Modify: `PAGE/console.js` (header comment; replace `prettyJson` and `resultOutput`)
- Test: `PageTest.java`

**Interfaces:**
- Consumes: `el(tag, cls, text)`, `plural(n, one, many)` (existing, `console.js`).
- Produces (used by Tasks 2 and 3):
  - `const JSON_BIG = 500`, `const ALL_NODES = "\u0001all"`
  - `class JsonNumber { source: string }`, `isContainer(v): boolean`
  - `parseJson(text: string): any | undefined` (undefined when not JSON)
  - `jsonViewer(text: string, nodes: Map<string, boolean>): { root: HTMLElement, expandAll(), collapseAll(), text(): string } | null`
  - `viewerTools(viewer): HTMLElement` (`span.jv-tools` with *Expand all*, *Collapse all*, *Copy*)
  - `textOrJson(text: string, json: boolean, nodes: Map): { view: HTMLElement, tools: HTMLElement | null }`
  - `isJsonType(type): boolean`
  - `resultOutput(answer)` (interim signature; Task 2 changes it to `resultOutput(result)`)
  - CSS classes `jv`, `jv-node`, `jv-line`, `jv-children`, `jv-toggle`, `jv-gap`, `jv-key`, `jv-string`,
    `jv-number`, `jv-literal`, `jv-punct`, `jv-summary`, `jv-tools`.

- [ ] **Step 1: Write the failing tests**

Add to `PageTest.java`, after `theStyleFollowsTheSystemThemeAndUsesSystemFonts`:

```java
    @Test
    void theStyleDefinesEveryJsonTokenInTheLightThemeAndInBothDarkBlocks() {
        String style = file("console.css");
        String light = rule(style, ":root {");
        String system = rule(style, ":root:not([data-theme=\"light\"]) {");
        String forced = rule(style, ":root[data-theme=\"dark\"] {");

        assertTrue(style.indexOf("@media (prefers-color-scheme: dark)")
                < style.indexOf(":root:not([data-theme=\"light\"]) {"), "the system's dark theme is a media query");
        for (String token : List.of("--json-key", "--json-string", "--json-number", "--json-literal",
                "--json-punct")) {
            assertTrue(light.contains(token + ":"), token + " in the light theme");
            assertTrue(system.contains(token + ":"), token + " in the system's dark theme");
            assertTrue(forced.contains(token + ":"), token + " in data-theme=\"dark\"");
        }
    }

    @Test
    void everyJsonThePageShowsGoesThroughTheViewer() {
        String script = file("console.js");

        assertTrue(script.contains("function jsonViewer(text, nodes)"), "the viewer, written once");
        assertFalse(script.contains("prettyJson"), "the viewer replaced prettyJson everywhere");
        assertTrue(script.contains("const JSON_BIG = 500"), "past 500 values, the first level only is open");
        assertTrue(script.contains("event.altKey"), "Alt+click flips a node and everything under it");
        for (String tool : List.of("\"Expand all\"", "\"Collapse all\"", "\"Copy\"", "\"Clipboard refused\"")) {
            assertTrue(script.contains(tool), "the viewer's tools: " + tool);
        }
        assertTrue(script.contains("navigator.clipboard.writeText(viewer.text())"), "Copy goes through the clipboard");
        assertTrue(script.contains("if (!viewer) return { view: el(\"pre\", \"result-body\", text), tools: null };"),
                "a body that does not parse is shown as text");
        assertTrue(script.contains("context.source") && script.contains("JSON.rawJSON"),
                "an integer past 2^53 is shown and copied as the server sent it");
    }

    /** The declarations of the first rule of {@code style} that starts with {@code opening}, to its closing brace. */
    private static String rule(String style, String opening) {
        int start = style.indexOf(opening);
        assertTrue(start >= 0, "no rule " + opening);
        return style.substring(start, style.indexOf('}', start));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; mvn -nsu -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=PageTest
```
Expected: FAIL — `--json-key in the light theme` and `the viewer, written once`.

- [ ] **Step 3: Add the tokens to `console.css`**

In the `:root {` block, after `--shade: rgba(24, 32, 43, 0.06);` add:

```css
  --json-key: #1F5F8B;
  --json-string: #2D7A4E;
  --json-number: #9C5A12;
  --json-literal: #8E3FA8;
  --json-punct: #8B94A2;
```

In **both** dark blocks (`:root:not([data-theme="light"]) {` inside the media query, and `:root[data-theme="dark"] {`),
after `--shade: rgba(0, 0, 0, 0.35);` add:

```css
    --json-key: #7FB2E0;
    --json-string: #7CCB9A;
    --json-number: #E2A94B;
    --json-literal: #C99BE0;
    --json-punct: #6E7988;
```
(two-space indent less in `:root[data-theme="dark"]`, matching that block).

After the rule `pre.result-body { … }` add:

```css
/* The JSON viewer: a result's body or its exchange, coloured, every object and array folding. */
.jv { font-family: var(--mono); font-size: 12px; line-height: 1.55; background: var(--sunk); border-radius: 6px;
  padding: 10px 12px; margin: 6px 0 0; max-height: 32em; overflow: auto; color: var(--ink); }
.jv-line { white-space: pre-wrap; overflow-wrap: anywhere; }
.jv-children { margin-left: 0.55em; padding-left: 1.2em; border-left: 1px dotted var(--rule); }
.jv-toggle { font: inherit; font-size: 10px; line-height: 1; width: 1.6em; padding: 0; border: 0; border-radius: 3px;
  background: transparent; color: var(--json-punct); cursor: pointer; }
.jv-toggle:hover { background: var(--grid); }
.jv-gap { display: inline-block; width: 1.6em; }
.jv-key { color: var(--json-key); }
.jv-string { color: var(--json-string); }
.jv-number { color: var(--json-number); }
.jv-literal { color: var(--json-literal); }
.jv-punct { color: var(--json-punct); }
.jv-summary { color: var(--json-punct); cursor: pointer; }
.jv-summary:hover { text-decoration: underline; }
.jv-tools { display: inline-flex; flex-wrap: wrap; gap: 6px; }
.jv-tools button { font-size: 11.5px; padding: 1px 8px; }
.exchange > .jv-tools { margin-top: 6px; }
```

- [ ] **Step 4: Write the viewer in `console.js`**

4a. In the header comment, replace:
```
//   JSON editor otherwise, with a "JSON" switch that keeps the values. A structured answer shows its body, pretty
//   printed when it is JSON, and its details folded under "Exchange". A cell of a sample table column named "replay"
//   that reads as "<action id> <JSON object>" of an action of that panel is a button that fills its form: nothing is
//   sent until the user submits. Any other cell of such a column stays text.
```
with:
```
//   JSON editor otherwise, with a "JSON" switch that keeps the values. A structured answer shows its body, through
//   the JSON viewer (jsonViewer) when it is JSON, and its details folded under "Exchange", through it too. A cell of
//   a sample table column named "replay" that reads as "<action id> <JSON object>" of an action of that panel is a
//   button that fills its form: nothing is sent until the user submits. Any other cell of such a column stays text.
```

4b. Replace the whole of `prettyJson` and `resultOutput` (from `/** {@code text} pretty-printed when it is JSON, as
it is otherwise. */` down to the closing `}` of `resultOutput`) with:

```js
// ------------------------------------------------------------------------------------------------ JSON viewer

/** Past this many values, a JSON document opens its first level only. */
const JSON_BIG = 500;
/** The key, in a viewer's node state, of what Expand all or Collapse all last set for every node. */
const ALL_NODES = "\u0001all";

/**
 * An integer of a JSON document past what a double holds exactly, such as 9007199254740993: the viewer shows and
 * copies the text the server sent, never the nearest double.
 */
class JsonNumber {
  constructor(source) { this.source = source; }
}

/** Whether {@code v} is an object or an array of a parsed document, which the viewer folds. */
const isContainer = (v) => v !== null && typeof v === "object" && !(v instanceof JsonNumber);

/**
 * {@code text} parsed, an integer past 2^53 kept as its text where the browser gives the source of a value;
 * undefined when it is not JSON. A key "__proto__" is an own property, as JSON.parse always makes it.
 */
function parseJson(text) {
  try {
    return JSON.parse(text, (key, value, context) => typeof value === "number" && !Number.isSafeInteger(value)
      && context && typeof context.source === "string" && /^-?\d+$/.test(context.source)
      ? new JsonNumber(context.source) : value);
  } catch (notJson) {
    return undefined;
  }
}

/** {@code doc} as indented JSON text, a kept integer written as the server sent it where the browser can. */
function jsonText(doc) {
  return JSON.stringify(doc, (key, value) => !(value instanceof JsonNumber) ? value
    : typeof JSON.rawJSON === "function" ? JSON.rawJSON(value.source) : Number(value.source), 2);
}

/** How many values {@code doc} holds, counting stopped past {@code limit}. */
function countValues(doc, limit) {
  let n = 0;
  const stack = [doc];
  while (stack.length && n <= limit) {
    const v = stack.pop();
    n++;
    if (isContainer(v)) for (const child of Object.values(v)) stack.push(child);
  }
  return n;
}

/**
 * A JSON document as a tree (spec §3): keys, strings, numbers and literals coloured, every object and array folding
 * under a ▾/▸ toggle or a click on its summary, Alt+click flipping everything under it too. The first two levels
 * start open, only the first past JSON_BIG values. {@code nodes} holds the fold state by node path, so that a redraw
 * of the same result keeps it; a new result brings a new map. Built with DOM calls and textContent only. null when
 * {@code text} is not JSON.
 */
function jsonViewer(text, nodes) {
  const doc = parseJson(text);
  if (doc === undefined) return null;
  const openDepth = countValues(doc, JSON_BIG) > JSON_BIG ? 1 : 2;
  const root = el("div", "jv");
  const isOpen = (path, depth) => nodes.has(path) ? nodes.get(path)
    : nodes.has(ALL_NODES) ? nodes.get(ALL_NODES) : depth < openDepth;

  /** Sets every object and array under {@code value}, itself included, to {@code open}. */
  function setDeep(value, path, open) {
    const stack = [[value, path]];
    while (stack.length) {
      const [v, p] = stack.pop();
      if (!isContainer(v)) continue;
      nodes.set(p, open);
      for (const [k, child] of Object.entries(v)) stack.push([child, p + "\u0000" + k]);
    }
  }

  function leaf(value) {
    if (value instanceof JsonNumber) return el("span", "jv-number", value.source);
    if (Array.isArray(value)) return el("span", "jv-punct", "[]");
    if (isContainer(value)) return el("span", "jv-punct", "{}");
    if (value === null) return el("span", "jv-literal", "null");
    if (typeof value === "string") return el("span", "jv-string", JSON.stringify(value));
    if (typeof value === "number") return el("span", "jv-number", String(value));
    return el("span", "jv-literal", String(value));
  }

  /** One value: {@code key} its name in its object, null in an array or at the root; {@code tail} its comma. */
  function node(value, key, path, depth, tail) {
    const head = key === null ? [] : [el("span", "jv-key", JSON.stringify(key)), el("span", "jv-punct", ": ")];
    const entries = isContainer(value) ? Object.entries(value) : [];
    if (!entries.length) {
      const line = el("div", "jv-line");
      line.append(el("span", "jv-gap"), ...head, leaf(value));
      if (tail) line.append(el("span", "jv-punct", tail));
      return line;
    }
    const array = Array.isArray(value);
    const box = el("div", "jv-node");
    const line = el("div", "jv-line");
    const toggle = el("button", "jv-toggle");
    toggle.type = "button";
    const opening = el("span", "jv-punct", array ? "[" : "{");
    const summary = el("span", "jv-summary", array ? "[…] " + plural(entries.length, "item", "items")
      : "{…} " + plural(entries.length, "key", "keys"));
    const summaryTail = el("span", "jv-punct", tail);
    line.append(toggle, ...head, opening, summary, summaryTail);
    const children = el("div", "jv-children");
    const closing = el("div", "jv-line");
    closing.append(el("span", "jv-gap"), el("span", "jv-punct", (array ? "]" : "}") + tail));
    box.append(line, children, closing);
    let built = false;
    const show = (open) => {
      if (open && !built) {
        built = true;       // children are drawn the first time they are shown: a folded big document costs little
        entries.forEach(([k, child], i) => children.append(node(child, array ? null : k, path + "\u0000" + k,
          depth + 1, i < entries.length - 1 ? "," : "")));
      }
      toggle.textContent = open ? "▾" : "▸";
      toggle.setAttribute("aria-expanded", String(open));
      toggle.setAttribute("aria-label", open ? "Collapse" : "Expand");
      opening.hidden = !open;
      children.hidden = !open;
      closing.hidden = !open;
      summary.hidden = open;
      summaryTail.hidden = open;
    };
    const flip = (event) => {
      const open = !isOpen(path, depth);
      if (event.altKey) {
        setDeep(value, path, open);
        box.replaceWith(node(value, key, path, depth, tail));
        return;
      }
      nodes.set(path, open);
      show(open);
    };
    toggle.addEventListener("click", flip);
    summary.addEventListener("click", flip);
    show(isOpen(path, depth));
    return box;
  }

  const draw = () => root.replaceChildren(node(doc, null, "", 0, ""));
  draw();
  return {
    root,
    expandAll() { nodes.clear(); nodes.set(ALL_NODES, true); draw(); },
    collapseAll() { nodes.clear(); nodes.set(ALL_NODES, false); draw(); },
    text: () => jsonText(doc),
  };
}

/** Expand all, Collapse all and Copy, for {@code viewer}; a refused clipboard is said on the button, nothing more. */
function viewerTools(viewer) {
  const tools = el("span", "jv-tools");
  const button = (text, run) => {
    const b = el("button", null, text);
    b.type = "button";
    b.addEventListener("click", run);
    tools.append(b);
    return b;
  };
  button("Expand all", () => viewer.expandAll());
  button("Collapse all", () => viewer.collapseAll());
  const copy = button("Copy", async () => {
    let said;
    try {
      await navigator.clipboard.writeText(viewer.text());
      said = "Copied";
    } catch (refused) {
      said = "Clipboard refused";
    }
    copy.textContent = said;
    setTimeout(() => { copy.textContent = "Copy"; }, 2000);
  });
  return tools;
}

/** Whether a content type is JSON. */
const isJsonType = (type) => typeof type === "string" && type.startsWith("application/json");

/**
 * {@code text} through the JSON viewer, with its tools, when {@code json} and it parses; as text otherwise, a body
 * that claims JSON but is none included. {@code nodes} is the viewer's fold state.
 */
function textOrJson(text, json, nodes) {
  const viewer = json ? jsonViewer(text, nodes) : null;
  if (!viewer) return { view: el("pre", "result-body", text), tools: null };
  return { view: viewer.root, tools: viewerTools(viewer) };
}

/** What an answer shows under its line: its body, then its details folded under "Exchange". */
function resultOutput(answer) {
  const out = [];
  if (typeof answer.body === "string") {
    const body = textOrJson(answer.body, isJsonType(answer.contentType), new Map());
    if (body.tools) out.push(body.tools);
    out.push(body.view);
  }
  if (typeof answer.details === "string") {
    const exchange = el("details", "exchange");
    const details = textOrJson(answer.details, true, new Map());
    exchange.append(el("summary", null, "Exchange"), ...(details.tools ? [details.tools] : []), details.view);
    out.push(exchange);
  }
  return out;
}
```

The call in `actionRow` (`output.replaceChildren(...resultOutput(answer));`) is unchanged.

- [ ] **Step 5: Check the syntax, run the tests**

Run:
```bash
node --check vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/console.js
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; mvn -nsu -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=PageTest
```
Expected: `node --check` prints nothing; `Tests run: 12, Failures: 0, Errors: 0` (10 existing + 2 new).

- [ ] **Step 6: Commit**

```bash
cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(devconsole): a JSON viewer for every JSON the page shows

Keys, strings, numbers and literals coloured from new --json-* tokens in
both themes; every object and array folds, Alt+click folds a subtree;
Expand all, Collapse all and Copy. Replaces prettyJson.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/console.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/console.css vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/PageTest.java
git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 2: Result records and outlets for action rows

A behaviour-preserving refactor: `actionRow` publishes every outcome as a result record to an outlet. Today's bar
uses `inlineOutlet`, which draws exactly what the page draws today. Task 3 adds the group outlet.

**Files:**
- Modify: `PAGE/console.js` (`resultOutput`, `actionRow`, `actionsBar`)
- Test: `PageTest.java`

**Interfaces:**
- Consumes (Task 1): `textOrJson(text, json, nodes)`, `isJsonType(type)`.
- Produces (used by Tasks 3 and 4):
  - `const STATE_CLASS: Map<"ok"|"error"|"running", "ok"|"failed"|"running">`
  - `groupName(action): string | null`
  - `freshNodes(): { body: Map, details: Map }`
  - `outcome(state, summary, fields?): Result` where
    `Result = { state, summary, millis: number|null, answer: object|null, time: number|null, nodes: {body, details}, exchangeOpen: boolean }`
  - `exchangeFold(details: string, result: Result): HTMLDetailsElement`
  - `resultOutput(result: Result): HTMLElement[]` (new signature)
  - `inlineOutlet(): Outlet` where `Outlet = { nodes: HTMLElement[], current(): Result|null, publish(result: Result) }`
  - `actionRow(panelId, action, outlet): Row` where
    `Row = { id, label, group: string|null, text, root: HTMLFormElement, reveal(), fill(values), update(now) }`;
    `reveal` is a writable property that whoever lays the row out may replace.

- [ ] **Step 1: Write the failing test**

Add to `PageTest.java`:

```java
    @Test
    void anActionPublishesEveryOutcomeToItsOutletWithTheWordingOfToday() {
        String script = file("console.js");

        assertTrue(script.contains("function actionRow(panelId, action, outlet)"), "where results show is the outlet's");
        assertTrue(script.contains("function inlineOutlet()"), "the panel's own bar keeps its look");
        assertTrue(script.contains("const started = performance.now();"), "the round trip the page measures");
        for (String wording : List.of("\"No token: reload the page.\"", "\"the console did not answer\"",
                "\"another action of this panel is running\"", "\"still running after 60 s: the outcome will show here\"",
                "\"failed: \" + answer.error", "\"refused (\" + response.status + \")\"")) {
            assertTrue(script.contains(wording), "the wording the page uses today: " + wording);
        }
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run:
```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; mvn -nsu -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=PageTest
```
Expected: FAIL — `where results show is the outlet's`.

- [ ] **Step 3: Replace `resultOutput` (Task 1's interim version) with the result records**

Replace the whole `resultOutput` function (with its javadoc) by:

```js
// ------------------------------------------------------------------------------------------------ results

/** The class a result's state takes on the page: ok green, error red, running neutral. */
const STATE_CLASS = new Map([["ok", "ok"], ["error", "failed"], ["running", "running"]]);

/** The group of an action, or null: a blank group is none. */
const groupName = (action) => typeof action.group === "string" && action.group ? action.group : null;

/** A new fold state for the two viewers of a result: its body and its details. */
const freshNodes = () => ({ body: new Map(), details: new Map() });

/**
 * A result as the page keeps it (spec §2.2): its state, "ok", "error" or "running"; its line; the round trip the
 * page measured, in milliseconds, or null; the structured answer, or null; the time of the snapshot outcome it
 * reflects; the fold state of its viewers, by node path; whether its "Exchange" is open.
 */
function outcome(state, summary, fields) {
  return { state, summary, millis: null, answer: null, time: null, nodes: freshNodes(), exchangeOpen: false,
    ...fields };
}

/** The details of an answer, the JSON-RPC exchange for the MCP inspector, folded under "Exchange". */
function exchangeFold(details, result) {
  const exchange = el("details", "exchange");
  exchange.open = result.exchangeOpen;
  exchange.addEventListener("toggle", () => { result.exchangeOpen = exchange.open; });
  const shown = textOrJson(details, true, result.nodes.details);
  exchange.append(el("summary", null, "Exchange"), ...(shown.tools ? [shown.tools] : []), shown.view);
  return exchange;
}

/** What a result shows under its line in the panel's own bar: its body, with the viewer's tools, then its details. */
function resultOutput(result) {
  const answer = result.answer;
  const out = [];
  if (typeof answer.body === "string") {
    const body = textOrJson(answer.body, isJsonType(answer.contentType), result.nodes.body);
    if (body.tools) out.push(body.tools);
    out.push(body.view);
  }
  if (typeof answer.details === "string") out.push(exchangeFold(answer.details, result));
  return out;
}

/**
 * Where an action of the panel's own bar shows its results, as it always did: the line next to its button, the body
 * and the exchange under it. It keeps the last result for the life of the form; a new line over the same answer
 * leaves the body as it is.
 */
function inlineOutlet() {
  const message = el("span", "msg");
  const output = el("div", "result");
  let last = null;
  return {
    nodes: [message, output],
    current: () => last,
    publish(next) {
      const sameAnswer = last !== null && next.answer === last.answer;
      last = next;
      message.textContent = next.summary;
      message.className = "msg " + STATE_CLASS.get(next.state);
      if (!sameAnswer) output.replaceChildren(...(next.answer ? resultOutput(next) : []));
    },
  };
}
```

- [ ] **Step 4: Replace `actionRow`**

Replace the whole `function actionRow(panelId, action) { … }` (up to, not including, the javadoc of `replayButton`)
with:

```js
/**
 * The form of one action: its description, its fields, its button and inline confirmation. Where its results show
 * is {@code outlet}'s, { nodes, current(), publish(result) }: inlineOutlet for the panel's own bar, groupOutlet for a
 * group tab. A refusal, a network failure and an invalid field are results too, in state "error".
 */
function actionRow(panelId, action, outlet) {
  const root = el("form", "action");
  root.noValidate = true;
  if (typeof action.description === "string" && action.description) {
    root.append(el("p", "action-desc", action.description));
  }
  const fields = [];
  for (const argument of action.arguments || []) {
    const field = isObject(argument.schema) ? jsonField(argument) : stringField(argument);
    fields.push(field);
    root.append(field.root);
  }
  const go = el("button", "act", action.label || action.id);
  go.type = "submit";
  const ask = el("span", "ask");
  ask.hidden = true;
  const yes = el("button", "act confirm", "Confirm");
  yes.type = "button";
  const no = el("button", null, "Cancel");
  no.type = "button";
  ask.append(el("span", "question", action.confirmation || ""), yes, no);
  root.append(go, ask, ...outlet.nodes);

  let sending = false;
  const kept = outlet.current();
  let shown = kept ? kept.time : null;   // the time of the snapshot outcome last shown; a newer one replaces it
  const busy = (on) => {
    for (const c of [go, yes, no]) c.disabled = on;
    for (const field of fields) field.disable(on);
  };
  const publish = (state, summary, more) => outlet.publish(outcome(state, summary, { time: shown, ...more }));
  const closeAsk = () => { ask.hidden = true; go.hidden = false; };

  async function send() {
    const token = page.snapshot && page.snapshot.console && page.snapshot.console.actionToken;
    if (typeof token !== "string") { publish("error", "No token: reload the page."); return; }
    // Object.create(null): an argument named "__proto__" (a server-declared name like any other) must still reach
    // the request body as an own property, not be swallowed by the prototype's own accessor of that name.
    const body = Object.create(null);
    try {
      for (const field of fields) body[field.name] = field.value();
    } catch (invalid) {
      publish("error", invalid.message);
      return;
    }
    sending = true;
    busy(true);
    publish("running", "running…");
    const started = performance.now();
    try {
      const response = await fetch("api/action/" + encodeURIComponent(panelId) + "/" + encodeURIComponent(action.id), {
        method: "POST",
        cache: "no-store",
        headers: { "Content-Type": "application/json", "X-Vidocq-Console-Token": token },
        body: JSON.stringify(body),
      });
      const type = response.headers.get("Content-Type") || "";
      const answer = type.startsWith("application/json") ? await response.json() : { text: await response.text() };
      const millis = Math.round(performance.now() - started);
      if (response.status === 200 && typeof answer.result === "string") {
        publish(answer.error === true ? "error" : "ok", answer.result, { millis, answer });
      } else if (response.status === 500 && typeof answer.error === "string") {
        publish("error", "failed: " + answer.error, { millis });
      } else if (response.status === 202) {
        publish("running", "still running after 60 s: the outcome will show here", { millis });
      } else if (response.status === 409) {
        publish("error", "another action of this panel is running", { millis });
      } else {
        publish("error", "refused (" + response.status + ")" + (answer.text ? ": " + answer.text : ""), { millis });
      }
    } catch (unreachable) {
      publish("error", "the console did not answer");
    } finally {
      sending = false;
      busy(false);
    }
  }

  root.addEventListener("submit", (event) => {
    event.preventDefault();
    if (sending) return;
    if (action.confirmation) { go.hidden = true; ask.hidden = false; yes.focus(); return; }
    send();
  });
  yes.addEventListener("click", () => { closeAsk(); send(); });
  no.addEventListener("click", closeAsk);

  /**
   * Follows the snapshot: an action running elsewhere, or a newer outcome of it. The outcome of this page's own call
   * keeps its body, round trip, fold state and exchange; any other outcome replaces it without a body.
   */
  function update(now) {
    if (!now || sending) return;
    busy(!!now.running);
    const current = outlet.current();
    if (now.running) {
      if (!current || current.state !== "running") publish("running", "running…");
      shown = null;
      return;
    }
    const last = now.last;
    if (!last || typeof last.text !== "string" || last.time === shown) return;
    shown = last.time;
    const own = current && current.state !== "running" ? current : null;
    const failed = !last.ok || last.error === true;
    outlet.publish(outcome(failed ? "error" : "ok",
      (last.ok ? "" : "failed: ") + last.text + " · " + clockTime(last.time),
      own ? { millis: own.millis, answer: own.answer, nodes: own.nodes, exchangeOpen: own.exchangeOpen, time: shown }
        : { time: shown }));
  }

  const row = {
    id: action.id,
    label: action.label || action.id,
    group: groupName(action),
    text: [action.label, action.id, action.description].filter((t) => typeof t === "string").join(" ").toLowerCase(),
    root,
    /** Shows the form; whoever lays the row out may replace it, as a group tab does to pick it in its combo. */
    reveal() { root.hidden = false; },
    /** Fills the form with a replayed call's arguments, by name; sends nothing. */
    fill(values) {
      for (const field of fields) if (Object.hasOwn(values, field.name)) field.fill(values[field.name]);
      row.reveal();
      root.scrollIntoView({ block: "nearest" });
      go.focus();
    },
    update,
  };
  return row;
}
```

- [ ] **Step 5: Give today's bar its outlet**

In `actionsBar(panel)`, replace
```js
  const rows = actions.map((action) => actionRow(panel.id, action));
```
with
```js
  const rows = actions.map((action) => actionRow(panel.id, action, inlineOutlet()));
```
and, inside the `for (const row of rows)` loop that builds the sections, after `section.rows.push(row);` add:
```js
    const box = section.box;
    row.reveal = () => { row.root.hidden = false; box.hidden = false; box.open = true; };
```
(The folded sections disappear in Task 4; this keeps Replay opening its section until then.)

- [ ] **Step 6: Check the syntax, run the tests**

Run:
```bash
node --check vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/console.js
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; mvn -nsu -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=PageTest
```
Expected: no syntax error; `Tests run: 13, Failures: 0, Errors: 0` (`aJsonPropertyNamedProtoIsAnObjectKeyLikeAnyOther`
still counts 3 `= Object.create(null);` lines).

- [ ] **Step 7: Commit**

```bash
cat > .git/PLAN_COMMIT_MSG <<'EOF'
refactor(devconsole): an action publishes its outcomes to an outlet

Every outcome, refusals and network failures included, becomes a result
record with the round trip the page measured. The panel's own bar keeps
its look through inlineOutlet; group tabs get their own outlet next.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/console.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/PageTest.java
git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 3: The group tab — combo, form, result block, filtered history

Builds the pieces of a group tab (§2.1–§2.3) and the per-panel page state they read. Nothing calls `groupTab` yet;
Task 4 wires it, so the page still renders as after Task 2.

**Files:**
- Modify: `PAGE/console.js` (constants and `panelState` after the `page` object; `sampleTable`; new functions after
  `replayButton`)
- Modify: `PAGE/console.css` (after the `button.replay` rule)
- Test: `PageTest.java`

**Interfaces:**
- Consumes (Tasks 1–2): `textOrJson`, `isJsonType`, `STATE_CLASS`, `exchangeFold(details, result)`, `Result`,
  `Row` (`id`, `label`, `text`, `root`, writable `reveal`), `FILTER_FROM`, `REPLAY_COLUMN`, `label(key)`,
  `stored(key)`.
- Produces (used by Task 4):
  - `const SUBTAB_KEY = "vidocq.devconsole.subtab."`, `const MONITORING = ""`
  - `panelState(panelId): { tab: string, chosen: Map<group, actionId>, results: Map<actionId, Result> }`
  - `groupOutlet(state, actionId, changed: (actionId) => void): Outlet`
  - `resultBlock(result: Result | null): HTMLElement`
  - `replayTarget(cell): string | null`
  - `sampleTable(value, panelId, keep?: (actionId: string|null) => boolean): HTMLElement`
  - `groupTab(panelId, name, rows: Row[], state, open: () => void): { root, changed(actionId), update(tables: {key, value}[], groupOf: Map<actionId, group>) }`
  - CSS classes `action-picker`, `action-select`, `action-slot`, `result-slot`, `result-block`
    (`data-state` = `ok|error|running|none`), `result-head`, `result-state`, `result-summary`, `result-time`,
    `result-none`, `history`, `subpanel`.

- [ ] **Step 1: Write the failing test**

Add to `PageTest.java`:

```java
    @Test
    void aGroupTabPicksOneActionAndShowsItsResultApartThenItsHistory() {
        String script = file("console.js");

        assertTrue(script.contains("function groupTab(panelId, name, rows, state, open)"), "a tab per group");
        assertTrue(script.contains("el(\"select\", \"action-select\")"), "a native combo of the group's actions");
        assertTrue(script.contains("rows.length > FILTER_FROM"), "a filter past ten actions of the group");
        assertTrue(script.contains("formSlot.replaceChildren(byId.get(id).root)"),
                "the selected action's own form, kept while it is typed in");
        assertTrue(script.contains("byId.has(state.chosen.get(name))"),
                "a selected action a dev reload removed falls back to the group's first");
        assertTrue(script.contains("function resultBlock(result)"), "the result, apart from the form");
        assertTrue(script.contains("\"No call yet\""), "before the first call");
        assertTrue(script.contains("function sampleTable(value, panelId, keep)"), "a table filtered to a group");
        assertTrue(script.contains("(id) => groupOf.get(id) === name"), "the rows of this group's actions only");
        assertTrue(script.contains("if (keep && !kept) return el(\"p\", \"absent\", \"No call yet\");"),
                "a history with no row of the group says so");
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run:
```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; mvn -nsu -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=PageTest
```
Expected: FAIL — `a tab per group`.

- [ ] **Step 3: Add the page state**

After `const PAUSED_KEY = "vidocq.devconsole.paused";` add:
```js
/** Prefix of the key, by panel id, of a panel's open sub-tab. */
const SUBTAB_KEY = "vidocq.devconsole.subtab.";
/** The id of a panel's Monitoring sub-tab: a group title is never blank, so no group has it. */
const MONITORING = "";
```

After the `history` map (`const history = new Map();`) add:
```js
/**
 * What the page keeps of each panel with action groups, by panel id, across polls and redraws (spec §4): the open
 * sub-tab (also in localStorage), the action picked per group, and the last result of each action, which a dev
 * reload that removes the action forgets.
 */
const panelStates = new Map();

function panelState(panelId) {
  let state = panelStates.get(panelId);
  if (!state) {
    state = { tab: stored(SUBTAB_KEY + panelId) || MONITORING, chosen: new Map(), results: new Map() };
    panelStates.set(panelId, state);
  }
  return state;
}
```

- [ ] **Step 4: Let `sampleTable` filter its rows**

Replace the javadoc and the first lines of `sampleTable`, from `/**\n * A table of a sample, its columns and rows;` down
to and including `  for (const row of value.rows || []) {`, with:

```js
/**
 * A table of a sample, its columns and rows; a cell of a REPLAY_COLUMN column that replays an action is drawn as a
 * Replay button, and that column's header left blank when at least one cell is. {@code keep}, when given, keeps only
 * the rows whose replay cell names an action it accepts, by id, as a group tab does; with no row kept, the table is
 * a line that says so.
 */
function sampleTable(value, panelId, keep) {
  const table = el("table", "ext");
  const columns = value.columns || [];
  const replayAt = columns.indexOf(REPLAY_COLUMN);
  const body = el("tbody");
  let buttons = 0;
  let kept = 0;
  for (const row of value.rows || []) {
    if (keep && !(replayAt >= 0 && keep(replayTarget(row[replayAt])))) continue;
    kept++;
```

and, just before `  const head = el("tr");` in the same function, add:
```js
  if (keep && !kept) return el("p", "absent", "No call yet");
```
Every other line of `sampleTable` stays verbatim (PageTest pins two of them).

- [ ] **Step 5: Add the group outlet, the result block and the group tab**

After the closing `}` of `replayButton`, add:

```js
/** The action id a REPLAY_COLUMN cell names, the text before its first space, or null. */
function replayTarget(cell) {
  const space = typeof cell === "string" ? cell.indexOf(" ") : -1;
  return space > 0 ? cell.slice(0, space) : null;
}

// ------------------------------------------------------------------------------------------------ a group tab

/**
 * Where an action of a group tab shows its results: the panel's page state, which keeps the last result of each
 * action across polls and redraws; {@code changed} redraws the result block when that action is the one on screen.
 */
function groupOutlet(state, actionId, changed) {
  return {
    nodes: [],
    current: () => state.results.get(actionId) || null,
    publish(next) {
      state.results.set(actionId, next);
      changed(actionId);
    },
  };
}

/**
 * The result of the selected action, apart from its form (spec §2.1): a header with the state in colour, the line,
 * the round trip the page measured and the viewer's tools, then the body, then the exchange folded under "Exchange".
 */
function resultBlock(result) {
  const block = el("section", "result-block");
  if (!result) {
    block.dataset.state = "none";
    block.append(el("p", "result-none", "No call yet"));
    return block;
  }
  block.dataset.state = result.state;
  const head = el("div", "result-head");
  const stateText = result.state === "running" ? "running…" : result.state;
  head.append(el("span", "result-state " + STATE_CLASS.get(result.state), stateText));
  if (result.summary !== stateText) head.append(el("span", "result-summary", result.summary));
  if (typeof result.millis === "number") head.append(el("span", "result-time", result.millis + " ms"));
  block.append(head);
  const answer = result.answer;
  if (answer && typeof answer.body === "string") {
    const body = textOrJson(answer.body, isJsonType(answer.contentType), result.nodes.body);
    if (body.tools) head.append(body.tools);
    block.append(body.view);
  }
  if (answer && typeof answer.details === "string") block.append(exchangeFold(answer.details, result));
  return block;
}

/**
 * The tab of one group (spec §2.1): a combo of its actions by label, in declaration order, with a text filter above
 * it past FILTER_FROM of them; the selected action's description and form; its result block; the group's history.
 * {@code rows} are the group's action rows, built once per panel structure with a groupOutlet: a form keeps what was
 * typed in while the combo shows another action. {@code open} shows this tab, for a Replay.
 */
function groupTab(panelId, name, rows, state, open) {
  const root = el("div", "subpanel");
  root.setAttribute("role", "tabpanel");
  root.setAttribute("aria-label", name);
  const picker = el("div", "action-picker");
  const combo = el("select", "action-select");
  combo.setAttribute("aria-label", name);
  let filter = null;
  if (rows.length > FILTER_FROM) {
    filter = el("input", "action-filter");
    filter.type = "search";
    filter.placeholder = "Filter " + rows.length + " actions";
    filter.autocomplete = "off";
    filter.spellcheck = false;
    picker.append(filter);
  }
  picker.append(combo);
  const formSlot = el("div", "action-slot");
  const resultSlot = el("div", "result-slot");
  resultSlot.setAttribute("aria-live", "polite");
  const historyBox = el("div", "history");
  root.append(picker, formSlot, resultSlot, historyBox);

  const byId = new Map(rows.map((row) => [row.id, row]));
  let selected = byId.has(state.chosen.get(name)) ? state.chosen.get(name) : rows[0].id;

  /** Lists the rows whose text holds {@code words} in the combo, and returns them. */
  function options(words) {
    const shown = words === "" ? rows : rows.filter((row) => row.text.includes(words));
    combo.replaceChildren(...shown.map((row) => {
      const option = el("option", null, row.label);
      option.value = row.id;
      return option;
    }));
    if (!shown.length) {
      const none = el("option", null, "no action matches");
      none.value = "";
      none.disabled = true;
      combo.append(none);
    }
    return shown;
  }
  const showResult = () => resultSlot.replaceChildren(resultBlock(state.results.get(selected) || null));
  function choose(id) {
    selected = id;
    state.chosen.set(name, id);
    combo.value = id;
    formSlot.replaceChildren(byId.get(id).root);
    showResult();
  }

  combo.addEventListener("change", () => { if (byId.has(combo.value)) choose(combo.value); });
  if (filter) {
    filter.addEventListener("input", () => {
      const shown = options(filter.value.trim().toLowerCase());
      if (shown.length && !shown.some((row) => row.id === selected)) choose(shown[0].id);
      else combo.value = selected;
    });
  }
  for (const row of rows) {
    row.reveal = () => {
      if (filter && filter.value !== "") { filter.value = ""; options(""); }
      open();
      choose(row.id);
    };
  }
  options("");
  choose(selected);

  return {
    root,
    /** Redraws the result block when {@code actionId} is the action on screen. */
    changed(actionId) { if (actionId === selected) showResult(); },
    /**
     * Draws the history (spec §2.3): each of {@code tables}, { key, value } of the panel's tables with a replay
     * column, keeping the rows whose action {@code groupOf} places in this group; a row of an unknown action is
     * dropped.
     */
    update(tables, groupOf) {
      historyBox.replaceChildren();
      for (const { key, value } of tables) {
        historyBox.append(el("h4", "table-key", label(key)), value.kind === "table"
          ? sampleTable(value, panelId, (id) => groupOf.get(id) === name)
          : el("p", "absent", value.reason || "not available"));
      }
    },
  };
}
```

- [ ] **Step 6: Style the group tab**

In `console.css`, after `button.replay { font-size: 11.5px; padding: 1px 8px; }` add:

```css
/* A group tab: a combo that picks the action, its form, its result apart, then the group's history. */
.action-picker { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; margin-bottom: 12px; }
.action-select { font: inherit; font-family: var(--mono); font-size: 13px; color: var(--ink);
  background: var(--surface); border: 1px solid var(--rule); border-radius: 5px; padding: 5px 8px;
  min-width: 18em; max-width: 100%; }
.action-slot { background: var(--surface); border: 1px solid var(--rule); border-radius: 6px; padding: 12px 14px; }
.result-slot { margin-top: 16px; }
.result-block { background: var(--surface); border: 1px solid var(--rule); border-left: 3px solid var(--faint);
  border-radius: 6px; overflow: hidden; }
.result-block[data-state="ok"] { border-left-color: var(--good); }
.result-block[data-state="error"] { border-left-color: var(--crit); }
.result-head { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 12px; padding: 8px 12px;
  background: var(--sunk); border-bottom: 1px solid var(--rule); font-family: var(--mono); font-size: 12px; }
.result-state { font-weight: 600; }
.result-state.ok { color: var(--good); }
.result-state.failed { color: var(--crit); }
.result-state.running { color: var(--muted); }
.result-summary { flex: 1 1 20ch; color: var(--ink); overflow-wrap: anywhere; }
.result-time { color: var(--faint); font-variant-numeric: tabular-nums; }
.result-block > .jv, .result-block > pre.result-body { margin: 0; border-radius: 0; }
.result-block > .exchange { padding: 6px 12px 10px; }
.result-block .exchange summary { cursor: pointer; font-size: 12px; color: var(--muted); }
.result-none { margin: 0; padding: 12px; color: var(--faint); font-size: 13px; }
.history { margin-top: 8px; }
```

- [ ] **Step 7: Check the syntax, run the tests**

Run:
```bash
node --check vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/console.js
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; mvn -nsu -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=PageTest
```
Expected: no syntax error; `Tests run: 14, Failures: 0, Errors: 0` (`theScriptTouchesLocalStorageInsideATryOnly`
still finds 2 lines: `panelState` goes through `stored`).

- [ ] **Step 8: Commit**

```bash
cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(devconsole): a group tab with a combo, a result block and its history

The pieces of a group tab: a native combo of the group's actions, a
filter past ten, the selected form, the result apart with the viewer's
tools, and the replay tables filtered to the group. Wired next.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/console.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/console.css vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/PageTest.java
git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 4: Sub-tabs in the panel view, state persistence and reload fallbacks

**Files:**
- Modify: `PAGE/console.js` (header comment; `actionsBar`; `structure`; `panelView`)
- Modify: `PAGE/console.css` (sub-tab bar; drop `.action-group`)
- Test: `PageTest.java`

**Interfaces:**
- Consumes (Tasks 2–3): `actionRow(panelId, action, outlet)`, `inlineOutlet()`, `groupOutlet(state, id, changed)`,
  `groupName(action)`, `panelState(panelId)`, `SUBTAB_KEY`, `MONITORING`,
  `groupTab(panelId, name, rows, state, open)` and its `changed`/`update(tables, groupOf)`, `store(key, value)`,
  `actionRows`, `actionKey`, `scopeView`, `REPLAY_COLUMN`.
- Produces: `actionsBar(rows: Row[]): HTMLElement | null` (new signature, ungrouped rows only); `panelView` with
  sub-tabs when a panel has a grouped action. No later task consumes code from here.

- [ ] **Step 1: Write the failing test**

Add to `PageTest.java`:

```java
    @Test
    void aPanelWhoseActionsHaveGroupsGetsSubTabsMonitoringFirst() {
        String script = file("console.js");

        assertTrue(script.contains("const MONITORING = \"\""), "Monitoring's id, which no group has");
        assertTrue(script.contains("[[MONITORING, \"Monitoring\"], ...[...byGroup.keys()].map((g) => [g, g])]"),
                "Monitoring first, then one tab per group in order of first appearance");
        assertTrue(script.contains("strip.setAttribute(\"role\", \"tablist\")"), "the markup of the top tabs");
        assertTrue(script.contains("const box = byGroup.size ? el(\"div\", \"subpanel\") : panelArea;"),
                "a panel without a grouped action renders as before");
        assertTrue(script.contains("body.hidden = tabId !== id"), "a sub-tab is shown, never drawn again");
        assertTrue(script.contains("v.columns.includes(REPLAY_COLUMN)"), "the replay tables move to the group tabs");
        assertTrue(script.contains("store(SUBTAB_KEY + panel.id, id)"), "the open sub-tab is remembered");
        assertTrue(script.contains("if (!byGroup.has(state.tab)) state.tab = MONITORING;"),
                "a group a dev reload removed falls back to Monitoring");
        assertTrue(script.contains("state.results.delete(id)"), "the result of a removed action is forgotten");
        assertFalse(script.contains("action-group"), "grouped actions are no longer folded sections");
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run:
```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; mvn -nsu -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest=PageTest
```
Expected: FAIL — `Monitoring first, then one tab per group…`.

- [ ] **Step 3: Update the header comment**

Replace the line
```
// - localStorage keeps the selected panel and the pause, nothing else, and may refuse both.
```
with
```
// - localStorage keeps the selected panel, the pause and each panel's open sub-tab, nothing else, and may refuse all.
```
and after the last line of the replay bullet (`…Any other cell of such a column stays text.`) add:
```
// - A panel whose actions have groups gets sub-tabs: Monitoring, with everything else the panel shows, then one per
//   group, in order of first appearance, which picks one action in a combo and shows its form, its last result apart
//   and the rows of the panel's replay tables that name one of the group's actions. The page keeps, per panel, the
//   open sub-tab, the action picked in each group and the last result of each action (panelState).
```

- [ ] **Step 4: Replace `actionsBar`**

Replace the whole `actionsBar` function, with its javadoc, by:

```js
/**
 * The panel's own action bar, in a dev launch only (the snapshot then carries console.actionToken): the ungrouped
 * actions, one form each, a text filter above them past FILTER_FROM. A confirmation is asked inline, never with the
 * browser's blocking dialog. The request is a same-origin fetch with the token of the boot.
 */
function actionsBar(rows) {
  if (!rows.length) return null;
  const bar = el("div", "actions");
  if (rows.length > FILTER_FROM) {
    const filter = el("input", "action-filter");
    filter.type = "search";
    filter.placeholder = "Filter " + rows.length + " actions";
    filter.autocomplete = "off";
    filter.spellcheck = false;
    filter.addEventListener("input", () => {
      const words = filter.value.trim().toLowerCase();
      for (const row of rows) row.root.hidden = words !== "" && !row.text.includes(words);
    });
    bar.append(filter);
  }
  bar.append(...rows.map((row) => row.root));
  return bar;
}
```

- [ ] **Step 5: Redraw a panel when an action changes group**

In `structure(panel)`, replace
```js
  const acts = Array.isArray(panel.actions) ? "#" + panel.actions.map((a) => a.id).join(",") : "";
```
with
```js
  const acts = Array.isArray(panel.actions)
    ? "#" + panel.actions.map((a) => a.id + "@" + (groupName(a) || "")).join(",") : "";
```

- [ ] **Step 6: Replace `panelView`**

Replace the whole `function panelView(panel, snapshot) { … }` with:

```js
function panelView(panel, snapshot) {
  panelArea.append(panelHead(panel.title || panel.id, panel.id + (panel.live ? " · live" : " · boot facts only")));
  const state = panelState(panel.id);
  const actions = Array.isArray(panel.actions) ? panel.actions : [];
  // a result is kept for as long as its action exists: a dev reload that removes the action forgets it (spec §4)
  for (const id of [...state.results.keys()]) if (!actions.some((a) => a.id === id)) state.results.delete(id);
  const groupTabs = new Map();
  for (const key of [...actionRows.keys()]) if (key.startsWith(panel.id + "\u0000")) actionRows.delete(key);
  const rows = actions.map((action) => {
    const group = groupName(action);
    const outlet = group === null ? inlineOutlet() : groupOutlet(state, action.id, (id) => {
      const tab = groupTabs.get(group);
      if (tab) tab.changed(id);
    });
    const row = actionRow(panel.id, action, outlet);
    actionRows.set(actionKey(panel.id, row.id), row);
    return row;
  });
  const byGroup = new Map();              // the grouped rows, by group in order of first appearance
  for (const row of rows) {
    if (!row.group) continue;
    if (!byGroup.has(row.group)) byGroup.set(row.group, []);
    byGroup.get(row.group).push(row);
  }
  const groupOf = new Map(rows.filter((row) => row.group).map((row) => [row.id, row.group]));

  // Monitoring: everything the panel shows but its grouped actions and its replay tables. With no grouped action, the
  // panel itself, drawn as it always was.
  const box = byGroup.size ? el("div", "subpanel") : panelArea;
  if (byGroup.size) {
    if (!byGroup.has(state.tab)) state.tab = MONITORING;
    box.setAttribute("role", "tabpanel");
    box.setAttribute("aria-label", "Monitoring");
    const strip = el("div", "subtabs");
    strip.setAttribute("role", "tablist");
    strip.setAttribute("aria-label", (panel.title || panel.id) + " views");
    const bodies = new Map([[MONITORING, box]]);
    const buttons = [];
    const openTab = (id) => {
      state.tab = id;
      store(SUBTAB_KEY + panel.id, id);
      for (const [tabId, body] of bodies) body.hidden = tabId !== id;
      for (const b of buttons) {
        const on = b.dataset.tab === id;
        b.setAttribute("aria-selected", String(on));
        b.tabIndex = on ? 0 : -1;
      }
    };
    for (const [id, title] of [[MONITORING, "Monitoring"], ...[...byGroup.keys()].map((g) => [g, g])]) {
      const b = el("button", "subtab", title);
      b.type = "button";
      b.setAttribute("role", "tab");
      b.dataset.tab = id;
      b.addEventListener("click", () => openTab(id));
      buttons.push(b);
    }
    strip.append(...buttons);
    strip.addEventListener("keydown", (event) => {
      const at = buttons.indexOf(document.activeElement);
      if (at < 0) return;
      const step = { ArrowDown: 1, ArrowRight: 1, ArrowUp: -1, ArrowLeft: -1 }[event.key];
      if (!step) return;
      event.preventDefault();
      const next = buttons[(at + step + buttons.length) % buttons.length];
      openTab(next.dataset.tab);
      next.focus();
    });
    panelArea.append(strip, box);
    for (const [name, groupRows] of byGroup) {
      const tab = groupTab(panel.id, name, groupRows, state, () => openTab(name));
      groupTabs.set(name, tab);
      bodies.set(name, tab.root);
      panelArea.append(tab.root);
    }
    openTab(state.tab);
  }

  if (panel.summary) box.append(el("p", "summary", panel.summary));
  const opens = openButtons(panel.lines);
  if (opens) box.append(opens);
  const bar = actionsBar(rows.filter((row) => !row.group));
  if (bar) box.append(bar);
  const anomalies = anomaliesOf(panel.id, snapshot);
  if (anomalies.length) {
    const list = el("div", "anoms");
    list.append(...anomalies.map(anomalyBox));
    box.append(list);
  }
  const flags = el("div", "flags");
  const failure = el("div", "anom crit");
  failure.hidden = true;
  box.append(failure, flags);

  const sample = panel.sample && !panel.sample.error ? panel.sample : null;
  const groups = sample ? sample.groups || [] : [];
  const charts = panel.charts || [];
  // the tables with a replay column, [scope, key], move to the group tabs (spec §2.3)
  const replayTables = [];
  if (byGroup.size && sample) {
    for (const [scope, values] of [["", sample.values || []], ...groups.map((g) => [g.name, g.values || []])]) {
      for (const v of values) {
        if (v.kind === "table" && Array.isArray(v.columns) && v.columns.includes(REPLAY_COLUMN)) {
          replayTables.push([scope, v.key]);
        }
      }
    }
  }
  const staying = (scope, values) => values.filter((v) => !replayTables.some(([s, k]) => s === scope && k === v.key));
  const scopes = [];
  if (sample) scopes.push(["", scopeView(box, panel, "", staying("", sample.values || []), charts)]);

  // the boot facts of a group are the lines whose key is its name, or starts with it: "@Default size"
  const lines = panel.lines || [];
  const claimed = new Set();
  const byLength = [...groups].sort((a, b) => b.name.length - a.name.length);
  const factsOf = new Map(groups.map((g) => [g.name, { kind: null, lines: [] }]));
  lines.forEach((line, i) => {
    const key = line[0];
    if (typeof key !== "string") return;
    for (const g of byLength) {
      if (key === g.name && line.length > 1) {
        factsOf.get(g.name).kind = splitLine(line).values.join(", ");
        claimed.add(i);
        return;
      }
      if (key.startsWith(g.name + " ")) {
        factsOf.get(g.name).lines.push([key.slice(g.name.length + 1), ...line.slice(1)]);
        claimed.add(i);
        return;
      }
    }
  });

  groups.forEach((group, index) => {
    const card = el("article", "group");
    const gh = el("div", "group-head");
    gh.append(el("h3", null, group.name));
    const facts = factsOf.get(group.name);
    if (facts.kind) gh.append(el("span", "kind", facts.kind));
    card.append(gh);
    scopes.push([group.name, scopeView(card, panel, group.name, staying(group.name, group.values || []), charts)]);
    if (facts.lines.length) {
      const id = panel.id + "\u0000" + group.name;
      const details = el("details");
      details.open = page.openGroups.has(id) || (index === 0 && !page.closedGroups.has(id));
      details.addEventListener("toggle", () => {
        if (details.open) { page.openGroups.add(id); page.closedGroups.delete(id); }
        else { page.closedGroups.add(id); page.openGroups.delete(id); }
      });
      details.append(el("summary", null, "Boot facts"), ...linesBlock(facts.lines, null));
      card.append(details);
    }
    box.append(card);
  });

  const rest = lines.filter((line, i) => !claimed.has(i));
  if (rest.length) {
    const block = el("section", "block");
    block.append(el("h3", null, "Boot facts"), ...linesBlock(rest, null));
    if (panel.truncated) block.append(el("p", "note", "The snapshot carries the first lines only."));
    box.append(block);
  }

  return {
    update(current) {
      const now = current.panels.find((p) => p.id === panel.id);
      if (!now) return;
      const nowActions = Array.isArray(now.actions) ? now.actions : [];
      for (const row of rows) row.update(nowActions.find((a) => a.id === row.id));
      const s = now.sample;
      failure.hidden = !(s && s.error);
      if (s && s.error) {
        failure.replaceChildren(el("div", "code", "sample failed · " + s.error),
          el("p", null, "The panel's sample() threw " + s.error + ": its live values are missing from this poll, "
            + "its boot facts stay. The console logged VIDOCQ-DEVC-005 once and asks the panel again on every poll."));
      }
      flags.replaceChildren();
      if (s && s.slow) flags.append(el("span", "chip warn", "slow sample: " + duration(s.nanos)));
      if (s && s.truncated) flags.append(el("span", "chip", "some values dropped: past the console's limits"));
      if (!s || s.error) return;
      const values = new Map([["", s.values], ...(s.groups || []).map((g) => [g.name, g.values])]);
      for (const [name, scope] of scopes) scope.update(values.get(name), current);
      if (!groupTabs.size) return;
      const tables = replayTables
        .map(([scope, key]) => ({ key, value: (values.get(scope) || []).find((v) => v.key === key) }))
        .filter((table) => table.value);
      for (const tab of groupTabs.values()) tab.update(tables, groupOf);
    },
  };
}
```

(The group card variable is renamed `card` from `box`, since `box` now names the Monitoring container; the rest of
the boot-facts code is unchanged.)

- [ ] **Step 7: Style the sub-tabs, drop the folded sections**

In `console.css`, replace
```css
.action[hidden], .action-group[hidden], .action .json-form[hidden] { display: none; }
```
with
```css
.action[hidden], .action .json-form[hidden] { display: none; }
```
delete the three rules
```css
.action-group { border: 1px solid var(--rule); border-radius: 6px; padding: 6px 10px; }
.action-group > summary { cursor: pointer; font-size: 13px; font-weight: 600; }
.action-group[open] > .action { margin-top: 10px; }
```
and, just before the `/* A group tab: …` block added in Task 3, add:

```css
/* The sub-tabs of a panel whose actions have groups: Monitoring, then one per group. */
.subtabs { display: flex; flex-wrap: wrap; gap: 2px; border-bottom: 1px solid var(--rule); margin: 6px 0 18px; }
.subtab { border: 0; border-bottom: 2px solid transparent; border-radius: 0; background: transparent;
  padding: 7px 14px; margin-bottom: -1px; font-size: 14px; color: var(--muted); }
.subtab:hover { color: var(--ink); background: var(--sunk); }
.subtab[aria-selected="true"] { color: var(--ink); font-weight: 600; border-bottom-color: var(--accent); }
.subtab:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
```

- [ ] **Step 8: Check the syntax, run the tests**

Run:
```bash
node --check vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/console.js
grep -n "action-group\|actionsBar(panel)" vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/console.*
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; mvn -nsu -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test
```
Expected: no syntax error; the `grep` prints nothing; the whole module's tests pass, `PageTest` with 15 tests.

- [ ] **Step 9: Commit**

```bash
cat > .git/PLAN_COMMIT_MSG <<'EOF'
feat(devconsole): action groups as sub-tabs of their panel

A panel whose actions have groups shows Monitoring, with everything else
it shows, then one sub-tab per group. The open sub-tab is remembered in
localStorage; a dev reload that removes an action or a group falls back
to the group's first action or to Monitoring, and forgets the orphan
result. A panel with no grouped action is drawn as before.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/console.js vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/console.css vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/PageTest.java
git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 5: SPI javadoc — groups are tabs, replay tables move

**Files:**
- Modify: `vidocq-runtime-devconsole-spi/src/main/java/io/vidocq/runtime/spi/devconsole/PanelAction.java` (`@param group`)
- Modify: `vidocq-runtime-devconsole-spi/src/main/java/io/vidocq/runtime/spi/devconsole/PanelSample.java` (`REPLAY_COLUMN` javadoc)

**Interfaces:** documentation only; no field, signature or behaviour change (§1, §6).

- [ ] **Step 1: Update `PanelAction`'s `@param group`**

Replace
```java
 * @param group        a short title such as {@code Tools}: the page folds the actions of a panel by group, in
 *                     order of first appearance, and adds a filter past ten actions; {@code null} for none
```
with
```java
 * @param group        a short title such as {@code Tools}: the page shows each group as a tab of the panel, next to
 *                     a <i>Monitoring</i> tab, in order of first appearance, and adds a filter past ten actions of a
 *                     group; a table with a {@link PanelSample#REPLAY_COLUMN replay} column moves to the group tabs,
 *                     filtered to their actions; {@code null} for none
```

- [ ] **Step 2: Update `PanelSample.REPLAY_COLUMN`'s javadoc**

Replace
```java
     * {@value #MAX_REPLAY_CELL} characters, and empties a longer one. A cell of this column that is no such replay,
     * or whose id is no action of the panel, is shown as text, cut as any other cell.
     */
    String REPLAY_COLUMN = "replay";
```
with
```java
     * {@value #MAX_REPLAY_CELL} characters, and empties a longer one. A cell of this column that is no such replay,
     * or whose id is no action of the panel, is shown as text, cut as any other cell. When the panel's actions have
     * {@link PanelAction#group() groups}, the page shows such a table in the group tabs rather than in the
     * <i>Monitoring</i> tab, each tab keeping the rows whose cell names one of its own actions; a row whose cell
     * names no action of the panel is left out there.
     */
    String REPLAY_COLUMN = "replay";
```

- [ ] **Step 3: Build the SPI with its tests and javadoc**

Run:
```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; mvn -nsu -pl vidocq-runtime-devconsole-spi install
```
Expected: `BUILD SUCCESS` (tests green; a javadoc error, if the build runs javadoc, would fail here).

- [ ] **Step 4: Commit**

```bash
cat > .git/PLAN_COMMIT_MSG <<'EOF'
docs(devconsole-spi): action groups are tabs, replay tables move to them

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add vidocq-runtime-devconsole-spi/src/main/java/io/vidocq/runtime/spi/devconsole/PanelAction.java vidocq-runtime-devconsole-spi/src/main/java/io/vidocq/runtime/spi/devconsole/PanelSample.java
git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 6: Documentation

**Files:**
- Modify: `docs/en/modules/ROOT/pages/dev-console.adoc` (`#mcp-inspector`; new `#json-viewer` before `[#security]`)
- Modify: `docs/en/modules/ROOT/pages/dev-console-panels.adoc` (`#action-result` body bullet, `#action-groups`,
  `#replay-column`)
- Modify: `docs/en/modules/ROOT/pages/whats-new.adoc` (the existing MCP inspector entry)

**Interfaces:** new anchor `json-viewer` in `dev-console.adoc`; existing anchors unchanged.

- [ ] **Step 1: `dev-console.adoc#mcp-inspector`, the opening paragraph**

Replace
```
the application's tools, prompts, resources and resource templates, and can call each of them. The list is three
folded sections, `Tools`, `Prompts` and `Resources`, each titled with its count and opened on demand; past ten
actions a text filter narrows the list and opens the sections that match.
```
with
```
the application's tools, prompts, resources and resource templates, and can call each of them. The tab has four
sub-tabs: *Monitoring*, with the panel's values, charts and boot facts, then *Tools*, *Prompts* and *Resources*,
resources and resource templates sharing the last. A sub-tab of a kind is a list to pick the item from, its
description and its form under it, then its result in a block of its own, then the history of that kind's calls;
past ten items a text filter above the list narrows it. The page remembers the open sub-tab, in this browser, and
the item picked in each.
```

- [ ] **Step 2: `dev-console.adoc#mcp-inspector`, the result paragraph**

Replace
```
**A result** shows the summary line, coloured when it is an error, then the body — JSON pretty-printed — and, under
"Exchange", the JSON-RPC request and response exactly as they were sent and received (or the SSE events, for a
streamed answer). What each outcome shows:
```
with
```
**A result** shows in a block of its own under the form: a header with the outcome, `ok` in green, `error` in red,
`running…` while the call runs, then the summary line, the round trip the page measured and the viewer's buttons;
then the body, JSON going through the <<json-viewer,JSON viewer>>; and, folded under "Exchange", the JSON-RPC
request and response exactly as they were sent and received (or the SSE events, for a streamed answer). Each item
keeps its last result while the page is open: picking it again in the list shows it. A refused call, or a console
that does not answer, shows its reason in that block too, as an error. What each outcome shows:
```

- [ ] **Step 3: `dev-console.adoc#mcp-inspector`, the history paragraph**

Replace
```
**History.** The panel keeps the last 20 calls of the boot, newest first, each with its time, action, outcome,
duration and arguments, and a "Replay" button that fills the action's form with those arguments and sends nothing
until you submit. A dev reload clears the history along with the catalogue.
```
with
```
**History.** The panel keeps the last 20 calls of the boot, newest first, each with its time, action, outcome,
duration and arguments, and a "Replay" button that picks the action in the list and fills its form with those
arguments, and sends nothing until you submit. Each sub-tab shows the calls of its own kind, under the result. A
dev reload clears the history along with the catalogue; an item it removed loses its result, the list falling back
to the first item of its kind, and a kind left with no item sends the tab back to *Monitoring*.
```

- [ ] **Step 4: `dev-console.adoc`, the new `#json-viewer` section**

Insert, just before the line `[#security]` (with one blank line before and after):

```
[#json-viewer]
== JSON viewer [.tag-new]#NEW#

Every JSON the page shows — the body of an `application/json` result and the details under "Exchange" — goes
through a small viewer instead of plain text. Keys, strings, numbers, and `true`, `false` and `null` each have their
colour, in the light and the dark themes. Every object and array folds under a ▾/▸ toggle; folded, it reads
`{…} 3 keys` or `[…] 12 items`. A click on the toggle or on that summary flips the node; Alt+click flips it and
everything under it. The first two levels start open, only the first when the document holds more than 500 values.

*Expand all*, *Collapse all* and *Copy* sit next to what they act on: in the result's header for the body, at the
top of "Exchange" for the details. *Copy* writes the JSON indented, masked values as the server sent them; when the
browser refuses the clipboard, the button says `Clipboard refused` and nothing else happens. An integer too large
for a JavaScript number, such as `9007199254740993`, is shown as the server wrote it.

The viewer keeps what is folded while the page polls, and forgets it when a new result replaces the one on screen.
A body that does not parse as JSON is shown as text. The tree is built with DOM calls only, never as markup, so a
key or a string is never read as HTML.
```

- [ ] **Step 5: `dev-console-panels.adoc`**

5a. In `#action-result`, replace
```
  `application/json`, the page pretty-printing JSON.
```
with
```
  `application/json`, the page showing JSON in its xref:dev-console.adoc#json-viewer[JSON viewer].
```

5b. In `#action-groups`, replace
```
* `group`, at most 40 characters, such as `Tools`: the page folds the actions of a panel into sections by group,
  in the order each group is first seen, rather than listing every button flat.
```
with
```
* `group`, at most 40 characters, such as `Tools`: the page shows each group as a sub-tab of the panel, in the
  order each group is first seen, after a *Monitoring* sub-tab that keeps everything else — the summary, the links,
  the ungrouped actions, the values, the charts and the boot facts. A group's sub-tab picks one action at a time
  from a list, shows its form, then its result in a block of its own; a `table` with a
  <<replay-column,replay column>> moves out of *Monitoring* to the group sub-tabs. A panel with no grouped action has
  no sub-tab and looks as before.
```
and replace
```
Past ten actions, whatever their groups, the page adds a text filter that narrows the list and opens the groups
that match.
```
with
```
Past ten actions of a group, the page adds a text filter above its list; past ten ungrouped actions, one above the
panel's own buttons.
```
(The sentence continues unchanged: `A panel keeps 128 actions at most; …`.)

5c. In `#replay-column`, after the paragraph ending `…the column keeps its header unless at least one of its cells
is a Replay button.` add a new paragraph:
```

When the panel's actions have groups, the table is shown in the group sub-tabs rather than in *Monitoring*, each
sub-tab keeping the rows whose cell names one of its own actions. A row whose cell names no action of the panel —
one a dev reload orphaned, or a cell emptied for its length — is left out there.
```

- [ ] **Step 6: `whats-new.adoc`, the existing entry**

In the line starting `* **An MCP inspector in the dev console** [.tag-new]#NEW#`, replace
```
calls them through its own `/mcp`: a form from each tool's input schema, the result and the exact JSON-RPC exchange, the last 20 calls with a replay, secrets masked.
```
with
```
calls them through its own `/mcp`: a *Monitoring* sub-tab and one per kind, a list to pick the item, a form from its input schema, the result in a block of its own through a JSON viewer that colours and folds it, the exact JSON-RPC exchange, the kind's last calls with a replay, secrets masked.
```
and replace, at the end of the same line,
```
See xref:dev-console.adoc#mcp-inspector[MCP inspector] and xref:dev-console-panels.adoc#json-argument[a JSON argument].
```
with
```
See xref:dev-console.adoc#mcp-inspector[MCP inspector], xref:dev-console.adoc#json-viewer[JSON viewer] and xref:dev-console-panels.adoc#json-argument[a JSON argument].
```

- [ ] **Step 7: Check the docs**

Run:
```bash
./check-doc-versions.sh
grep -n 'json-viewer\|pretty-print\|folded sections\|folds the actions' docs/en/modules/ROOT/pages/*.adoc
```
Expected: the script reports no error; `grep` shows `[#json-viewer]` defined once in `dev-console.adoc`, the three
`xref`/`<<json-viewer` uses, and no `pretty-print`, `folded sections` or `folds the actions` left.

- [ ] **Step 8: Commit**

```bash
cat > .git/PLAN_COMMIT_MSG <<'EOF'
docs(devconsole): action groups as tabs, and the JSON viewer

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
EOF
git add docs/en/modules/ROOT/pages/dev-console.adoc docs/en/modules/ROOT/pages/dev-console-panels.adoc docs/en/modules/ROOT/pages/whats-new.adoc
git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 7: Build verification and the manual check in Chrome

This is where behaviour is verified: the Java tests only prove the code is there. Report every difference from the
checklist in the final summary, with screenshots for the PR.

**Files:** none changed in this repository. In `~/projects/perso/vidocq-tools/lc4jcdi-on-vidocq` two committed,
clean files are edited temporarily and restored (Steps 7 and 8); nothing else there is touched. Its uncommitted
`.run/*.xml` files are never touched.

**Interfaces:** consumes the whole page (Tasks 1–4), the SPI (Task 5).

- [ ] **Step 1: Build and test the touched modules and their consumers**

Run:
```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; mvn -nsu -pl vidocq-runtime-devconsole-spi,vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension,vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-langchain4j-cdi-mcp-extension-dev install
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; mvn -nsu -pl vidocq-runtime-integration-tests/vidocq-runtime-it-langchain4j-cdi-mcp test
```
Expected: `BUILD SUCCESS` twice. The IT (`McpInspectorConsoleTest`, `McpDevConsolePanelTest`) launches the app with the
console and reads `/api/snapshot`; it picks its own ports in 18090-18099. If the first command fails on a missing
upstream SNAPSHOT artifact, run `mvn -nsu install -DskipTests` at the root once (same `export` prefix), then again.

- [ ] **Step 2: Pick free ports**

Run: `lsof -nP -iTCP:18090-18099 -sTCP:LISTEN`
Choose two ports of 18090-18099 absent from the output: `APP` for the application, `CONSOLE` for the console (for
example 18093 and 18094). Never 8080 or 8888.

- [ ] **Step 3: Check the example's files are clean, then start `vidocq:dev`**

Run: `git -C ~/projects/perso/vidocq-tools/lc4jcdi-on-vidocq status --short -- mcp-time-server/src`
Expected: empty. If not, stop and ask the user before editing anything in Steps 7-8.

Start in the background (the Bash tool's `run_in_background`), with the ports of Step 2:
```bash
cd ~/projects/perso/vidocq-tools/lc4jcdi-on-vidocq/mcp-time-server && export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; mvn -nsu vidocq:dev -Dvidocq.chappe.listener.default.host=127.0.0.1 -Dvidocq.chappe.listener.default.port=18093 -Dvidocq.devconsole.port=18094 -Dvidocq.dev.debug=false
```
Wait (Monitor with an until-loop on its output) for `Vidocq dev console: http://127.0.0.1:18094/`. Note the
background task id: it is the only process this task may stop.

- [ ] **Step 4: Open the console in Chrome**

Invoke the `claude-in-chrome` skill, load the Chrome tools in one ToolSearch call
(`tabs_context_mcp, tabs_create_mcp, navigate, computer, read_page, find, javascript_tool, read_console_messages,
tabs_close_mcp`), open a new tab on `http://127.0.0.1:18094/` and select the `mcp` panel.

- [ ] **Step 5: The checklist of spec §7 (and the Review Focus)**

Take a screenshot at each item and note what is seen.
1. **Four tabs:** *Monitoring*, *Tools*, *Prompts*, *Resources*, in that order; *Monitoring* shows the summary, tiles,
   charts and boot facts, **no form and no `calls` table**. Arrow keys move between sub-tabs.
2. **Combo swaps the form:** in *Tools*, pick `current_time` then `convert_time`: description and form change; type
   `Europe/Paris` in `fromZone`, pick `current_time`, wait 3 s, pick `convert_time` again: the value is still there;
   switch to *Prompts* and back: still there.
3. **A call and its viewer:** call `current_time` with `zone` = `Europe/Paris`: header `ok` in green, summary, `… ms`;
   its body is text (`text/plain`), so it shows as text with no header toolbar; the *Exchange* fold is JSON through
   the viewer, with its own toolbar (colours for keys, strings, numbers). Then, in the *Exchange*: click a
   ▾ (the node folds to `{…} n keys`), Alt+click the root (everything folds), *Expand all*, *Collapse all*, *Copy*
   (the button says `Copied`; check with `javascript_tool`: `navigator.clipboard.readText()` if allowed, otherwise
   paste nowhere and just note the button text). Wait 3 polls: the fold state is kept.
4. **An error call:** `convert_time` with `localDateTime` = `nope`, `fromZone` = `UTC`, `toZone` = `Mars/Base`: header
   `error` in red with the server's message; the previous `current_time` result is still shown when `current_time`
   is picked again.
5. **History per tab and Replay:** *Tools* shows only the two tool calls under the result; call `plan_meeting` in
   *Prompts* (any values) and the resource template in *Resources* (`zone` = `UTC`): each tab shows only its own
   rows. The prompt's body is JSON (its messages): it goes through the viewer, with *Expand all*, *Collapse all* and
   *Copy* in the result header. In *Tools*, click *Replay* on the `convert_time` row: the combo switches to `convert_time`, the form is
   filled, nothing is sent (no new history row).
6. **Persistence:** leave the panel on *Resources*, reload the browser tab: the `mcp` panel opens on *Resources*.
   Open the `logs` panel and come back: still *Resources*.
7. **Dark theme:** with `javascript_tool`, run `document.documentElement.dataset.theme = "dark"`; screenshot a result
   in the viewer and the sub-tabs; then `delete document.documentElement.dataset.theme`.
8. **Other panels unchanged:** the `logs` panel has no sub-tab and its *Set level* works as before (line next to the
   button, green).

- [ ] **Step 6: Check the console for errors**

Read the page's console messages (`read_console_messages`): expected no error from `console.js`.

- [ ] **Step 7: A reload that removes a tool**

In *Tools*, pick `convert_time` (it has a result from item 4). Then edit
`~/projects/perso/vidocq-tools/lc4jcdi-on-vidocq/mcp-time-server/src/main/java/io/vidocq/tools/lc4jcdi/mcptimeserver/TimeTools.java`:
change `name = "convert_time",` to `name = "convert_time_renamed",` and save. Wait for the dev reload in the log.
Expected: *Tools* lists `current_time` and `convert_time_renamed`, the combo is on `current_time` (the group's first),
`convert_time_renamed` shows `No call yet`, the history is empty (a reload clears it). Then restore:
`git -C ~/projects/perso/vidocq-tools/lc4jcdi-on-vidocq checkout -- mcp-time-server/src/main/java/io/vidocq/tools/lc4jcdi/mcptimeserver/TimeTools.java`
and wait for the next reload.

- [ ] **Step 8: A reload that removes a whole group**

Open *Prompts*. Edit `…/mcptimeserver/MeetingPrompts.java`: put `// ` in front of the line starting with
`    @Prompt(name = "plan_meeting"`, save, wait for the reload.
Expected: no *Prompts* sub-tab, the panel shows *Monitoring*. Restore with
`git -C ~/projects/perso/vidocq-tools/lc4jcdi-on-vidocq checkout -- mcp-time-server/src/main/java/io/vidocq/tools/lc4jcdi/mcptimeserver/MeetingPrompts.java`,
then check `git -C ~/projects/perso/vidocq-tools/lc4jcdi-on-vidocq status --short -- mcp-time-server/src` is empty.

- [ ] **Step 9: Stop the application**

Stop the background task started in Step 3 (TaskStop on its id), then check with `lsof -nP -iTCP:18090-18099
-sTCP:LISTEN` that its two ports are free. Kill nothing else.

- [ ] **Step 10: The migration panel**

Check the ports again (`lsof -nP -iTCP:18090-18099 -sTCP:LISTEN`), then start in the background:
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq/vidocq-runtime-examples/vidocq-runtime-mansart-h2-example && export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; mvn -nsu vidocq:dev -Dvidocq.chappe.listener.default.host=127.0.0.1 -Dvidocq.chappe.listener.default.port=18093 -Dvidocq.devconsole.port=18094 -Dvidocq.dev.debug=false
```
On `http://127.0.0.1:18094/`, the `migration` panel: no sub-tab, *Migrate now* and *Clean and migrate* as before
(inline line, inline confirmation). Click *Migrate now*: the line shows next to the button. Screenshot. Stop that
background task, check its ports are free, close the Chrome tab.

- [ ] **Step 11: Report**

No commit in this task. Summarise in the final report: each checklist item seen or not, any difference, the
screenshots to attach to the PR. Then use superpowers:finishing-a-development-branch; never push without the user.
