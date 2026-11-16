# Console graph result and row menu Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An action can answer with a graph, which the page draws as boxes and lines, and a rows answer can carry a
right-click menu whose entries fill another action's form with the selected rows' values.

**Architecture:**

- **Graph.** A new result content type, `application/x-graph+json`, holds nodes (id, label, kind, facts) and edges
  (from, to, label, arrow, dashed).
  - The SPI writes it by hand, as `RowsBody` writes rows.
  - The page parses it and lays it out in `editor-core.js`, a pure and deterministic layered layout tested with
    GraalJS, then draws it in SVG in `console.js`.
  - Clicking a node lists its facts beside the graph.
- **Row menu.** A rows body gains an optional `menu`, a list of `{label, action, arguments}`.
  - `arguments` is a JSON object, as in a replay cell. Each `${column}` inside one of its strings becomes the
    selected rows' values of that column, written as query literals and joined with `", "`.
  - The page adds a checkbox column and a right-click menu, and calls the target action's existing `fill`. It sends
    nothing: the user still submits, as for a replay.

**Tech Stack:** Java 25 (devconsole SPI), the page's ES modules (`console.js` draws, `editor-core.js` computes; GraalJS
in the tests), SVG, Antora docs.

**Spec:** `noether/docs/superpowers/specs/2026-11-16-persistence-dev-panel-design.md`, part 4 (Vidocq/noether): "a
graph view for an action result (the Model sub-tab), and multi-row selection with a context menu that fills another
action's form (Explore to JPQL, the way a replay cell already fills a form)".

## Global Constraints

- **SPI.** Additive only: new constants, records and overloads, and no existing signature changes. A rows body
  without a menu stays byte for byte what it is today.
- **Page.**
  - It renders every string as text, never markup. It loads nothing from elsewhere and uses no library (`PageTest`).
  - It sends nothing on a menu click: it fills a form, as a replay does.
- **Limits.** A graph has at most 500 nodes and 2,000 edges.
  - Ids and labels are at most `Column.MAX_NAME` (200) characters. A kind is at most 40.
  - A fact name is at most 200 characters, and a fact value at most `MAX_DESCRIPTION` (2,000).
  - A rows menu has at most 16 entries. A label is at most 80 characters, and an entry's `arguments` at most
    `PanelSample.MAX_REPLAY_CELL` (4,096).
  - A body past `MAX_CONTENT` is refused with an `IllegalArgumentException`, never cut: half a graph means nothing.
- **Language.** English only; "Java Modules", never the abbreviation.

## Review Focus

- **Cycles and self-references.** Bidirectional relations, and a self-reference such as `Employee.manager`. The
  layout ends, no two boxes overlap, and a self-edge is drawn as a loop (Task 3).
- **Quotes and exact values.** A selected cell holding `O'Brien`, `NULL`, `true`, or an integer past 2^53 becomes
  `'O''Brien'`, `NULL`, `TRUE`, and the exact digits (Task 4).
- **A poll redraws the result.** The selection and the Table or JSON mode survive. A new result of the action starts
  with no selection (Task 5).
- **An entry naming an action the panel no longer has** (after a dev reload) is not shown. When no entry is shown,
  there is no checkbox column (Task 5).
- **200 entities.** The SVG scrolls inside its box, and the layout stays within O(n + e) per sweep (Task 3, a test
  of 500 nodes under one second).

---

### Task 1: a graph result in the SPI

**Files:**
- Create: `vidocq-runtime-devconsole-spi/src/main/java/io/vidocq/runtime/spi/devconsole/GraphBody.java`
- Create: `vidocq-runtime-devconsole-spi/src/main/java/io/vidocq/runtime/spi/devconsole/JsonText.java`. It holds
  `static void quote(String, StringBuilder)`, moved out of `RowsBody` so that both writers share it.
- Modify: `PanelAction.java` (`ActionResult`: the `GRAPH` constant, the content type check, `graph(...)`, records
  `Node`, `Fact`, `Edge`), `RowsBody.java` (uses `JsonText.quote`)
- Test: `vidocq-runtime-devconsole-spi/src/test/java/io/vidocq/runtime/spi/devconsole/GraphResultTest.java`

**Interfaces:**
- Produces:
  - `ActionResult.GRAPH = "application/x-graph+json"`;
  - `ActionResult.graph(String summary, List<Node> nodes, List<Edge> edges)`;
  - `record Node(String id, String label, String kind, List<Fact> facts)`, with `kind` possibly empty;
  - `record Fact(String name, String value)`;
  - `record Edge(String from, String to, String label, boolean arrow, boolean dashed)`, with `label` possibly empty;
  - `MAX_NODES = 500`, `MAX_EDGES = 2000`.
- Body: `{"nodes":[{"id","label","kind","facts":[[name,value]…]}…],"edges":[{"from","to","label","arrow","dashed"}…]}`.

- [ ] Step 1: write `GraphResultTest`, red. It checks:
  - `aGraphWritesItsNodesAndEdgesInOrder`: the exact body for 2 nodes and 1 edge, with a quote and a newline escaped.
  - `anEdgeToAnUnknownNodeIsRefused`
  - `aNodeIdSeenTwiceIsRefused`
  - `pastFiveHundredNodesIsRefused`
  - `aGraphPastTheContentLimitIsRefused`: a fact value repeated until the body passes 256 KiB.
  - `aBlankIdOrLabelIsRefused`
  - `theContentTypeIsGraph`
- [ ] Step 2: implement, run `-pl vidocq-runtime-devconsole-spi test`, green. `RowsBodyTest` stays green.
- [ ] Step 3: commit `feat(devconsole): an action answers with a graph`.

### Task 2: a menu on a rows result in the SPI

**Files:**
- Modify: `PanelAction.java`:
  - `record MenuItem(String label, String action, String arguments)`;
  - the overloads `rows(summary, columns, rows, more, List<MenuItem> menu)` and
    `rowsHeld(columns, rows, List<MenuItem> menu)`;
  - the four-argument forms delegate with `List.of()`.
- Modify: `RowsBody.java`. It writes `"menu":[{"label","action","arguments":<the object as given>}…]` between
  `columns` and `rows`, only when the menu is not empty. The menu counts toward the size of the prefix.
- Test: `RowsMenuTest.java` (same test package).

- [ ] Step 1: write `RowsMenuTest`, red. It checks:
  - `aMenuIsWrittenBetweenColumnsAndRows`: the exact body.
  - `noMenuWritesTheBodyOfToday`: equal to the four-argument `rows`.
  - `anEntryWhoseActionBreaksTheKeyRuleIsRefused`
  - `argumentsThatAreNoJsonObjectAreRefused`: uses `JsonCheck.isObject` at `MAX_JSON_DEPTH`.
  - `argumentsPastTheReplayCellLimitAreRefused`
  - `seventeenEntriesAreRefused`
  - `aLongMenuHoldsFewerRows`: `rowsHeld` with a menu is less than or equal to `rowsHeld` without one.
- [ ] Step 2: implement, green with the whole SPI module.
- [ ] Step 3: commit `feat(devconsole): a rows result offers a menu on its selected rows`.

### Task 3: reading and laying out a graph in the page

**Files:**
- Modify: `vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/editor-core.js`,
  `editor.js` (re-export)
- Test: `.../devconsole/GraphCoreTest.java` (GraalJS, built like `SelectorCoreTest`)

**Interfaces:**
- `readGraph(text)` returns `{nodes:[{id,label,kind,facts:[[n,v]]}], edges:[{from,to,label,arrow,dashed}]}`, or
  `null` when the text is not that shape. An edge naming an unknown node makes the result `null`.
- `graphLayout(graph)` returns `{width, height, nodes: Map id → {x, y, w, h}, edges: [{from, to, x1, y1, x2, y2, lx,
  ly, loop}]}`.
- The layout, in order:
  1. Split the graph into connected components (undirected), in the order of their first node.
  2. In each component, take the node with the most edges (ties: the first) as the root. Lay out layers by
     breadth-first search, except that the `to` of an `arrow && !dashed` edge sits one layer above its `from` when
     both are unplaced.
  3. Order the layers three times, keeping the current order on ties: by the barycentre of each node's neighbours
     in the layer above, then in the layer below, then above again.
  4. Size the boxes: `w = 16 + 7.2 × max(label, kind) length`, `h = 40`. Leave 40 px between boxes and 70 px between
     layers, and centre each layer.
  5. Place the components side by side, 60 px apart.
  6. Clip each edge to the borders of its two boxes. Put its label at the midpoint. A self-edge is a loop on the
     right side of its box (`loop: true`).

- [ ] Step 1: write `GraphCoreTest`, red. It checks:
  - `aGraphBodyIsRead`
  - `anEdgeToAnUnknownNodeReadsAsNoGraph`
  - `theLayoutIsTheSameForTheSameGraph`
  - `noTwoBoxesOverlap`: on a cycle A–B–C–A, a self-loop, and two components.
  - `aParentSitsAboveItsSubclass`: arrow edge Sub → Base, so `y(Base) < y(Sub)`.
  - `anEdgeEndsOnTheBordersOfItsBoxes`
  - `fiveHundredNodesLayOutUnderOneSecond`
- [ ] Step 2: implement, green with `EditorCoreTest`, `SelectorCoreTest` and `PageTest`.
- [ ] Step 3: commit `feat(devconsole): the page lays out a graph result`.

### Task 4: the menu of a rows result in the page's pure half

**Files:**
- Modify: `editor-core.js`. `readRows` also returns `menu`: the valid entries, `[]` when absent or invalid; an entry
  is valid when its `label` is a string, its `action` follows the key rule, and its `arguments` is an object.
  `editor.js` re-exports the new functions.
- Test: `MenuCoreTest.java` (GraalJS)

**Interfaces:**
- `queryLiteral(cell)`, internal to the module:
  - a string becomes `'…'`, with each `'` doubled;
  - a number keeps its exact source digits;
  - a boolean becomes `TRUE` or `FALSE`;
  - `null` becomes `NULL`.
- `menuArguments(entry, text, picked)`:
  - It re-reads the body `text` with a reviver that keeps every number's source text, so an integer past 2^53 stays
    exact.
  - `picked` is a list of row indexes, taken in ascending order.
  - It returns a copy of `entry.arguments` in which each `${name}` naming a column, inside any string at any depth,
    becomes the literals of that column for the picked rows, joined with `", "`.
  - A `${name}` that names no column stays as it is.

- [ ] Step 1: write `MenuCoreTest`, red. It checks:
  - `aRowsBodyKeepsItsMenu`
  - `anInvalidEntryIsLeftOut`
  - `noMenuReadsAsAnEmptyOne`
  - `aStringIsQuotedWithItsQuotesDoubled`: `O'Brien` becomes `'O''Brien'`.
  - `aBigIntegerKeepsItsDigits`: `12345678901234567890`.
  - `nullAndBooleansAreKeywords`
  - `theSelectedRowsFillThePlaceholderInRowOrder`: the picked list `[2,0]` gives `1, 3`.
  - `aPlaceholderInANestedStringIsFilled`
  - `anUnknownPlaceholderStays`
- [ ] Step 2: implement, green.
- [ ] Step 3: commit `feat(devconsole): the page fills a menu entry with the selected rows`.

### Task 5: drawing both in the page

**Files:**
- Modify: `console.js`:
  - `answerBody` routes `isGraphType` to `graphBody(graph, text, nodes)`, with Graph / JSON tools as `rowsBody` has.
  - `rowsBody` and `rowsTable` take the menu:
    - A checkbox column comes first. Its header box selects or clears every row.
    - A right-click on a row selects it alone when it was not selected, then opens `ul.row-menu` (`role=menu`) at the
      pointer.
    - A "Selected rows" button in the tools, disabled at 0, opens the same menu from the keyboard.
    - Escape, a click elsewhere, or a scroll closes the menu.
    - An entry calls `actionRows.get(actionKey(panelId, entry.action)).fill(menuArguments(entry, text, picked))`.
  - The selection lives in a `WeakMap` keyed by the answer's `nodes`, as `rowsViews` does.
- Modify: `console.css`:
  - `.graph-box`: a scrolling SVG, with the facts aside;
  - `.graph-node`, `.graph-node.chosen`, `.graph-edge.near` (when a chosen node's edge), `.graph-edge.dashed`;
  - `.row-menu`, `td.pick`;
  - colours from the existing variables only.
- Modify: the page's header comment (one paragraph for each).
- Test: `PageTest`:
  - `aGraphAnswerIsDrawnInSvgWithItsFactsOnAClick`
  - `aRowsMenuFillsTheTargetFormAndSendsNothing`
  - `theConsoleImportsTheEditorAndNoOtherScript` stays green.

- [ ] Step 1: write the `PageTest` checks, red.
- [ ] Step 2: implement. `node --check console.js`, then run the module's tests, green.
- [ ] Step 3: in Chrome, against `scratchpad/selector-probe/serve.py` extended with:
  - an action `model` answering a graph of 4 entities: an inheritance, a self-reference, and an embeddable drawn
    dashed;
  - an action `explore` answering rows with a menu that targets `jpql`.

  Check that:
  - no two boxes overlap, and a click lists the facts;
  - selecting 2 rows and choosing *Delete these rows* fills JPQL with `... IN (1, 3)`;
  - the POST log stays empty until Run.
- [ ] Step 4: commit `feat(devconsole): the page draws a graph and a menu on selected rows`.

### Task 6: docs

**Files:**
- Modify: `docs/en/modules/ROOT/pages/dev-console-panels.adoc`, with `[#graph-result]` and `[#row-menu]` after
  `#action-result`. Each gets a short Java example (`ActionResult.graph`, then `rows(..., menu)` with
  `"DELETE FROM Note e WHERE e.id IN (${id})"`), and its limits.
- Modify: `whats-new.adoc`, two entries under *Dev console*.

- [ ] Step 1: write both sections, and check that each limit quoted matches its constant.
- [ ] Step 2: commit `docs(devconsole): graph results and row menus`.
