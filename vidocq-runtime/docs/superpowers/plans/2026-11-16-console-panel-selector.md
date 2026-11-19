# Console panel selector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A dev-console panel declares one choice, rendered once above its sub-tabs; every action receives the
selected value, and the Monitoring tab shows the selected item only.

**Architecture:** The choices are the names of the groups the panel already writes in its sample (one group per
item: a pool, a persistence unit). The panel declares which action argument the choice fills, and the label of the
dropdown. The page hides that argument's field in every form and sends the selection in its place; Monitoring hides
the cards of the other groups. The server is unchanged past the snapshot: the argument reaches the action as any
other, checked by the action's own argument rule.

**Tech Stack:** Java 25 (devconsole SPI, devconsole extension), the page's ES modules (`console.js` draws,
`editor-core.js` computes, run by GraalJS in the tests), Antora docs.

**Spec:** `noether/docs/superpowers/specs/2026-11-16-persistence-dev-panel-design.md`, part 3 (Vidocq/noether
repository), layout A chosen by Yann: a *Persistence unit* dropdown at the top of the panel selects the unit every
sub-tab works on.

## Global Constraints

- Additive SPI only: a new record and default methods; no signature of `LivePanel`, `DevConsolePanel` or
  `PanelAction` changes (snapshots publish apart, see the workspace memory on additive APIs).
- The page renders every string as text, never markup; it loads nothing from elsewhere (`PageTest`).
- `sample()` reads memory only: the choices come from the sample's groups, never from a call of their own.
- A panel without a selector, or with a selector and no group, draws exactly as today.
- English only; "Java Modules", never the abbreviation.

## Review Focus

- The selected group disappears after a dev reload (a unit removed): the page falls back to the first choice.
- A selector whose argument no action declares: the dropdown still filters Monitoring, no form changes.
- A replay cell that names the selector's argument with a value among the choices switches the dropdown to it; with
  a value that is not a choice, the dropdown stays and the action runs on the selection.
- A panel whose `selector()` throws: shown without a selector, logged at DEBUG, as `charts()` is.
- Monitoring of a selected group keeps its charts and its boot facts (they live in the group card).

---

### Task 1: `PanelSelector` in the SPI

**Files:**
- Create: `vidocq-runtime-devconsole-spi/src/main/java/io/vidocq/runtime/spi/devconsole/PanelSelector.java`
- Modify: `LivePanel.java`, `DevConsolePanel.java` (same package): `default Optional<PanelSelector> selector()`
- Test: `vidocq-runtime-devconsole-spi/src/test/java/io/vidocq/runtime/spi/devconsole/PanelSelectorTest.java`

**Interfaces:**
- Produces: `public record PanelSelector(String argument, String label)`; `argument` follows
  `PanelSample.requireKey`, `label` neither null nor blank (`IllegalArgumentException`, `NullPointerException`).

- [ ] Test: `aSelectorNamesAnArgumentAndALabel`, `anArgumentBreakingTheKeyRuleIsRefused`,
      `aBlankLabelIsRefused`, `aPanelHasNoSelectorByDefault` (an anonymous `LivePanel` and `DevConsolePanel`).
- [ ] Run, see them fail (no class), implement, run green.

### Task 2: the snapshot writes the selector

**Files:**
- Modify: `vidocq-runtime-devconsole-extension/.../PanelEntry.java` (new component `PanelSelector selector`, read
  once per boot like `charts`, `null` when absent or when `selector()` fails, logged at DEBUG),
  `LivePanelAdapter.java` (forwards `selector()`), `Snapshot.java` (member `"selector": {"argument", "label"}` for a
  panel that has one, in every launch; Javadoc of the JSON updated)
- Test: `SnapshotTest` and `LivePanelsTest` (same test package)

- [ ] Tests: `aPanelWithASelectorWritesItsArgumentAndLabel`, `aPanelWithoutSelectorWritesNone`,
      `aSelectorThatThrowsIsLeftOut`, `aLivePanelGivesItsSelector`.
- [ ] Red, implement, green.

### Task 3: the page's pure half

**Files:**
- Modify: `vidocq-runtime-devconsole-extension/src/main/resources/META-INF/resources/devconsole/editor-core.js`
- Test: `SelectorCoreTest.java` (GraalJS, as `RowsCoreTest`)

**Interfaces:**
- `export function selectorOf(panel)`: `{argument, label}` or `null` when absent or malformed.
- `export function choicesOf(panel)`: the group names of a sample that has no error, in order; `[]` otherwise.
- `export function pickChoice(choices, kept)`: `kept` when it is a choice, else the first, else `null`.
- `export function formArguments(action, selector)`: the arguments the form shows, the selector's left out.

- [ ] Tests: one per function and per Review Focus line that is pure (a vanished choice falls back to the first; a
      malformed selector is `null`; a sample error gives no choice; an action without the argument keeps them all).
- [ ] Red, implement, green.

### Task 4: the page draws the dropdown

**Files:**
- Modify: `console.js` (`panelState` keeps `choice`, stored as `vidocq.devconsole.choice.<panel>`; `panelView`
  draws a labelled `<select>` above the sub-tab strip when `selectorOf` and `choicesOf` give one; group cards not
  chosen are `hidden`; `actionRow` takes the selection, leaves the argument's field out and adds it to the body;
  `fill` switches the dropdown when the replayed value is a choice), `console.css` (the selector bar)
- Test: `PageTest` (the page still loads its own files only), then the dev console in Chrome on
  `vidocq-runtime-mansart-h2-example` with a test panel is not needed: the Persistence panel of part 5 is its first
  user, and part 5 checks it in the browser.

- [ ] Implement, run `PageTest`, run the module's tests.

### Task 5: documentation

**Files:**
- Modify: `docs/en/modules/ROOT/pages/dev-console-panels.adoc` (a `[#selector]` section after `#action-groups`, with
  a short example), `docs/en/modules/ROOT/pages/whats-new.adoc` (one entry)

- [ ] Write, check the xrefs, commit.
