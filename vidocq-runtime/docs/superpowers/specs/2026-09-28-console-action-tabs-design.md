# Dev console: action groups as tabs, and a JSON viewer

Date: 2026-09-28. Follows the MCP inspector (`2026-09-26-mcp-inspector-design.md`, Vidocq/vidocq#149).

## 1. Goal

The `mcp` panel mixes two jobs on one scrolling page: monitoring the server (tiles, charts, boot facts) and
testing its tools, prompts and resources (one form per item, folded by group). The user wants them apart, and a
lighter test view.

- **In scope:**
  - a panel whose actions have groups gets sub-tabs: *Monitoring*, then one tab per group;
  - in a group tab, a combo that picks the action, its form under it, and a result block clearly apart;
  - the call history under the result, filtered to the group;
  - a JSON viewer with syntax colours and collapsible nodes, for every JSON the page shows.
- **Out of scope:**
  - any change to the SPI records (`PanelAction`, `ActionResult`, `PanelSample`) or to the server;
  - any MCP-specific page code: the page stays generic (ADR 0001), and the MCP panel only benefits;
  - a JSON editor for the input: the raw input stays a `textarea`.

## 2. Sub-tabs of a panel

A generic rule of `console.js`, driven by `PanelAction.group`.

**When:** a panel with at least one action whose `group` is set. A panel with no grouped action (logs, migration)
renders exactly as today.

**Tabs:**
- *Monitoring* first, always. It holds everything the panel shows today, in the same order (summary, links,
  anomalies, flags, live scope, sample groups, boot facts), **except**:
  - the grouped actions; the ungrouped actions of the panel keep their bar here;
  - the tables that have a `replay` column (`PanelSample.REPLAY_COLUMN`), which move to the group tabs (§2.3).
- then one tab per group, in the order of first appearance among the actions. A group with no action has no tab.

For the `mcp` panel this gives *Monitoring*, *Tools*, *Prompts*, *Resources* (resources and templates share the
`Resources` group).

The sub-tab bar uses the same `role="tablist"` / `role="tab"` markup and keyboard behaviour as the top tabs, one
level down.

### 2.1 A group tab

Top to bottom:

1. **Combo:** a native `<select>` listing the group's actions by `label`, in declaration order. From 10 actions
   (`FILTER_FROM`), the existing text filter sits above it and narrows the options.
2. **Description:** the selected action's `description`, if any.
3. **Form:** the selected action's arguments, generated exactly as today (flat-schema form, or raw JSON with the
   *JSON* switch, string arguments with their allowed values or pattern), then its confirmation if it has one, then
   the *Call* button. Changing the selection replaces description and form.
4. **Result block**, visually apart (its own bordered surface, a header bar):
   - header: the state, in colour (`ok` green, `error` red, `running…` neutral), the `summary`, the round-trip time the
     page measured (the server sends no duration), and the viewer's buttons (§3);
   - body: the `body`, through the JSON viewer when `contentType` is `application/json`, as text otherwise;
   - the `details` (the JSON-RPC exchange), folded under *Exchange*, through the JSON viewer.
   Before the first call of the selected action, the block says *No call yet*.
5. **History** of the group (§2.3).

### 2.2 Per-action result

Each action keeps its last result in the page's memory. Selecting it again in the combo shows it. A refused call
(token, origin, busy, validation) shows its message in the result block, as an error, with the wording the page
uses today. While a call runs, *Call* is disabled and the header says `running…`.

### 2.3 History under the result

A table with a `replay` column is shown in the group tabs only, below the result block. Each tab shows the rows
whose `replay` cell names an action of **its** group; the page knows the group of every action id. Rows whose
action is unknown (removed by a dev reload) are dropped.

*Replay* selects that action in the combo and fills its form, without sending, as today (`actionRow.fill`).

## 3. JSON viewer

A small generic component of `console.js`. It replaces `prettyJson` wherever the page shows JSON: an
`application/json` result body and the result `details`.

- **Colours:** keys, strings, numbers, booleans and `null` each have a colour, from new `--json-key`,
  `--json-string`, `--json-number`, `--json-literal` and `--json-punct` tokens in `console.css`, defined for the
  light and the dark themes like the existing ones.
- **Collapse:** every object and array has a ▾/▸ toggle. Collapsed, it shows a summary: `{…} 3 keys`,
  `[…] 12 items` (`1 key`, `1 item` in the singular). Clicking the toggle or the summary flips the node; Alt+click
  flips the node and everything under it.
- **Default depth:** the first two levels are open. When the document has more than 500 nodes, only the first
  level is open.
- **Toolbar**, in the result header: *Expand all*, *Collapse all*, *Copy*. *Copy* writes the indented JSON, with
  the masked values as the server sent them, through `navigator.clipboard`; when the clipboard is refused, the
  button says so and nothing else happens.
- **Safety:** the tree is built with DOM calls and `textContent` only, never `innerHTML`; the CSP stays
  `default-src 'self'`. The parsed document is walked as it is; a key named `__proto__` shows as an ordinary key
  (the page already parses into prototype-less objects).
- **Not JSON:** a body that does not parse is shown as text, as today.
- **Kept across polls:** the page re-renders about once a second. The collapsed or expanded state is kept per node
  path, for the result on screen, and forgotten when a new result replaces it.

## 4. Page state

Kept in the page's memory, per panel: the open sub-tab; per group, the selected action; per action, its last result
and the viewer's node state.

- The open sub-tab is also stored in `localStorage`, next to the selected panel (`stored`/`store`, which tolerate
  a refusal).
- When a dev reload removes the selected action, the combo falls back to the group's first action, and the orphan
  result is forgotten. When it removes a whole group, the panel falls back to *Monitoring*.
- A form being typed in survives polls, as today.

## 5. Errors

No new server error. The page shows every failure in the result block of the action that caused it, never in a
global banner: a call that returns `error=true` (red header, its summary), a refused call (its current message), a
network failure (the page's current message).

## 6. Contract and documentation

- `PanelAction.group` javadoc: "the page shows each group as a tab of the panel, next to a *Monitoring* tab; a
  table with a `replay` column moves to the group tabs, filtered to their actions". `PanelSample.REPLAY_COLUMN`
  javadoc says the same about the table. No field changes; ADR 0001 is unchanged.
- `docs/en/.../dev-console.adoc#mcp-inspector`: rewritten for the tabs, the combo, the result block and the history;
  a new `#json-viewer` section.
- `docs/en/.../dev-console-panels.adoc`: groups are tabs, and what moves out of *Monitoring*.
- `whats-new.adoc`: the existing *An MCP inspector in the dev console* [NEW] entry is updated, since this is the
  same development line; no new entry.

## 7. Testing

The page still has no JavaScript test harness, and this change adds none.

- **`PageTest` (Java, on the resources as text):**
  - `console.js` never uses `innerHTML`, `outerHTML`, `insertAdjacentHTML` or `document.write`;
  - `console.css` defines every `--json-*` token in the light theme (`:root`) and in both dark blocks
    (`prefers-color-scheme: dark` and `[data-theme="dark"]`).
- **Server tests:** unchanged, since the contract does not change; they stay green.
- **Manual check in Chrome**, driven by Claude, under `vidocq:dev`, with the console on a free port of 18090-18099
  (never 8080 or 8888). On the `mcp` panel:
  - the four tabs, and *Monitoring* without any form;
  - switching the combo swaps the form;
  - a call, its coloured result, collapse, Alt+click, *Expand all*, *Collapse all*, *Copy*;
  - an error call;
  - the history per tab, and *Replay*;
  - a reload that removes a tool;
  - the dark theme.
  The logs and migration panels are checked unchanged. Screenshots go in the PR.

## 8. Decisions taken with the user

- Generic, driven by groups, not MCP-specific page code.
- Tabs for the `mcp` panel: *Monitoring*, *Tools*, *Prompts*, *Resources*.
- History under the result, filtered to the tab's group, with *Replay*.
- Layout of a group tab (§2.1) and the JSON viewer (§3), approved as presented.
