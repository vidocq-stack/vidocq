# Dev console: a query mode for the code editor, and JDQL in Mansart Data

Date: 2026-09-30. The second of three pieces of the dev console's code editor. The first
(`2026-09-30-devconsole-editor-json-design.md`, merged as Vidocq/vidocq#173) gave the console a hand-written editor
and its JSON language; the third will give the *Mansart pools* panel table views and a SQL editor.

## 1. Goal

The *JDQL* tab of the *Mansart Data* panel takes a query as a plain textarea: nothing says which entities and
attributes exist, a typo is found only when the query runs, and `params` is a JSON object the developer writes from
memory. The editor gets a generic **query mode**, fed by data a panel publishes (its dialect and its vocabulary), and
the Mansart Data panel publishes one for JDQL. The `params` field becomes the JSON editor, its schema computed from the
query as it is typed.

- **In scope:**
  - `PanelLanguage` and `languages()` in the console SPI; a `GET api/language/<panel>/<id>` endpoint; the ids a
    panel offers in the snapshot;
  - the query mode in `editor-core.js`: tokens, context, completion, diagnostics, `parameters()`, formatting, pairs;
  - in the page: a query field becomes a query editor, a `params` field becomes a JSON editor whose schema follows the
    query, the language loaded once per page;
  - the `jdql` language of the Mansart Data panel, and the schemas of the three actions that take a query (Query,
    Update / Delete, Export CSV);
  - the documentation.
- **Out of scope:**
  - aliases, joins, sub-queries, several targets in one statement (JDQL has none; SQL, piece 3, will);
  - a full JDQL grammar in the page: the server stays the judge of the syntax;
  - positional parameters (`?1`) in `params`: coloured as parameters, given no key;
  - the *Mansart pools* panel (piece 3).

## 2. The panel language (server side)

### 2.1 SPI

`vidocq-runtime-devconsole-spi` gains:

```java
public record PanelLanguage(String id, String json) { … }
```

- `id` follows `PanelSample.requireKey`, such as `jdql`;
- `json` is the text of a JSON object, at most 1 MiB, parsed when the record is built (`JsonValues.parse`, the depth
  limit of a json argument); anything else throws `IllegalArgumentException` naming the id.

`DevConsolePanel` and `LivePanel` gain `default List<PanelLanguage> languages() { return List.of(); }`, read once,
when the console first reads the panel, as `actions()` is. Two languages of one panel with the same id are refused
there (the panel is kept, without its languages, and the console logs why). A panel whose `languages()` throws is
kept without languages.

### 2.2 The language's JSON

```json
{ "mode": "query",
  "dialect": {
    "keywords": ["SELECT", "FROM", "WHERE", "ORDER", "BY", "AND", "OR", "NOT", "IS", "NULL", "BETWEEN", "LIKE", "IN",
                 "ASC", "DESC", "UPDATE", "SET", "DELETE", "COUNT", "THIS", "SUM", "AVG", "MIN", "MAX", "TRUE", "FALSE"],
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
```

- `mode` names a mode of the page's `editor-core.js`; this piece has `query`.
- `dialect.keywords` are matched ignoring case and inserted in capitals; `functions` are the names that may be
  followed by `(`; `clauses` are the words (or two-word phrases) that start a clause; `targetAfter` the words after
  which a target name is expected (`DELETE FROM` is covered by `FROM`); `self` is the implicit target's name in an
  expression; `quote` the string delimiter, doubled inside a string.
- `targets` maps each target's name to its `detail` and `attributes`. An attribute has a JSON Schema `type`
  (`string`, `integer`, `number`, `boolean`), optionally `format` and `enum`, a `detail`, and `target` when it refers
  to another target of the same language (a path `project.name` goes through it).

### 2.3 The endpoint and the snapshot

- `GET api/language/<panel>/<id>` answers `200` with the language's JSON (`application/json; charset=utf-8`,
  `Cache-Control: no-cache`), `404` for an unknown panel or id, and applies the snapshot's checks: a `dev` launch only
  (`404` otherwise), the `Host` and `Origin` checks, no token (it reads, it changes nothing). It is never logged.
- The snapshot adds to each panel that has languages `"languages": ["jdql", …]`, the ids only, never the content.

### 2.4 Mansart Data's `jdql` language

The `-dev` module builds it once from the catalogue (`MansartDataCatalogue.Entity`/`Column`) and `EntityModels`:

- one target per entity of the catalogue whose model could be read, under the name `FROM` takes (its simple name);
  `detail` is `table <table>`;
- one attribute per attribute of the model but the joined ones: its `type` (and `format`, `enum`) from `Scalars.schema`
  of the field type, a reference's from its target's id type, with `target`; `detail` `<Java simple type> · <key or
  column>` (`Long · id, generated`, `String · column title`, `→ Project · column project_id`);
- the dialect of §2.2, the words `JdqlAst` accepts.

The `query` property of the three actions that take one (`jdql.query`, `jdql.write`, `jdql.export`) becomes
`{"type": "string", "format": "textarea", "contentMediaType": "text/x-query", "x-language": "jdql",
"description": …}`; their `params` property gains `"x-parameters-of": "query"`. A language id is looked up in the
panel of the action.

## 3. The query mode (`editor-core.js`)

`export function queryLanguage()` returns a language object with the contract of `jsonLanguage` (`id`, `tokenize`,
`diagnose`, `complete`, `format`, `pairs`) plus `parameters(text, data)`. Its `data` is the language's JSON of §2.2;
`null` or anything odd means no vocabulary and a default dialect (the keywords of §2.2's example), and it never throws.

### 3.1 Tokens

`keyword`, `function` (a name followed by `(`, in `functions`), `target` (a known target in a target position),
`attribute` (a known attribute of the target, or a known path through references), `identifier` (any other name),
`string` (`'…'`, `''` inside; an unterminated one runs to the end of its line), `number`, `parameter` (`:name`, `?1`),
`operator` (`= <> != < > <= >= + - * /`), `punct` (`( ) , .`), `invalid` (anything else). The page colours them with
the editor's palette: keyword, function, target, attribute, string, number, parameter each distinct in both themes.

### 3.2 Context

- **The target** is the name after the first `targetAfter` word of the text, wherever the caret is: in
  `SELECT | FROM Task`, the target is `Task`.
- **The clause at the caret** is the last `clauses` word or phrase before it.
- **A target position** is right after a `targetAfter` word (the name being typed).
- **A path position** is right after `name.`, where `name` (or a longer path) resolves to an attribute with a `target`.

### 3.3 Completion

| At the caret | Items |
|---|---|
| a target position | the targets, detail their `detail` |
| a path position | the attributes of the referenced target |
| in a `SELECT`, `WHERE`, `ORDER BY` or `SET` clause, with a known target | the target's attributes (detail: `detail`), `self`, then the functions, inserted with `(` and the caret inside |
| anywhere else, or with no known target | the keywords, in capitals |

Items are filtered by the word being typed, ignoring case; `from`-`to` covers that word (a path's last segment only).

### 3.4 Diagnostics

Errors, each on its range: an unknown target; an unknown attribute of the known target (the head of a path); an
unknown attribute after a reference; a path through an attribute that has no `target`; an unterminated string;
an unbalanced parenthesis (on the one left open, or the extra one). With no vocabulary, or no known target, no
attribute is checked. Nothing else: a grammar mistake is found by the server when the query runs.

### 3.5 `parameters(text, data)`

Returns the JSON Schema of the `params` object: `{"type": "object", "properties": {…}, "required": […],
"additionalProperties": false}`, one property per distinct `:name`, in order of first use, all required. A property's
type comes from the attribute it meets:

| Form | Schema |
|---|---|
| `attr op :p` or `:p op attr`, op one of `= <> != < > <= >=` | the attribute's type (and `format`, `enum`) |
| `attr [NOT] LIKE :p` | `{"type": "string"}` |
| `attr [NOT] BETWEEN :a AND :b` | the attribute's type, for both |
| `attr [NOT] IN :p` | `{"type": "array", "items": <the attribute's type>}` |
| `SET attr = :p` | the attribute's type |
| anything else | `{}` (any value) |

Its `description` says where it is used: `compared with price (number)`, `pattern for title`, `list of status values`,
`new value of title`. A parameter used twice keeps its first typed use.

### 3.6 Formatting and keystrokes

`format` writes the keywords in capitals and starts each clause of `clauses` on a line of its own, `AND` and `OR` on
an indented line of their own; strings, numbers, parameters and names are copied as written. A text with an
unterminated string is not formatted (`format` throws `line <n>: unterminated string`). `pairs` are `()` and `''`; the
smart keystrokes of piece 1 apply (`keystroke()`), with the quote of the dialect.

## 4. The page

- A string property of the generated form with `format: "textarea"` and `contentMediaType: "text/x-query"` is a
  query editor (`createEditor` with `queryLanguage()`), 5 rows, in place of the textarea; with `x-language`, the page
  fetches `api/language/<panel>/<id>` once per page load and per id (a shared promise), then gives it to every query
  editor waiting for it through a new `setData(data)` of the editor. Until then, and if the fetch fails, the editor
  works with no vocabulary; a failed fetch shows `no vocabulary: <status>` in the editor's note.
- A property with `x-parameters-of: "<name>"` is a JSON editor whose data is `parameters(<that field's text>,
  <its language data>)`, recomputed after each change of that field (in the same animation frame as its draw) and
  when the language arrives. Its value is sent as typed, as the JSON mode does since piece 1.
- `setData(data)` recomputes tokens, colours and diagnostics, and closes an open completion list.
- The JSON switch, `fill` (replaying a call: the query and the params are filled), disabling while a call runs, and the
  CSV `Choose file` are unchanged.

## 5. Tests

- **SPI:** `PanelLanguage` refuses a bad id, a text that is not a JSON object, one past 1 MiB or past the depth limit;
  `languages()` is empty by default on both interfaces.
- **Console:** `api/language` answers the JSON, `404` for an unknown panel or id and outside a `dev` launch, refuses a
  foreign `Origin` or `Host`; a panel with a duplicate id, or whose `languages()` throws, is kept without languages;
  the snapshot lists the ids and never the content; `PageTest`: the query editor, `x-parameters-of`, `setData`, the
  fetch once per id, no markup sink, the new token colours in both themes.
- **`EditorCoreTest` (GraalJS)**, with a JDQL fixture: every token kind; the target before and after the caret; each
  row of §3.3's table; each diagnostic of §3.4 and none without vocabulary; `parameters()` for each row of §3.5's table,
  a parameter used twice, a positional parameter ignored; formatting and its refusal; an odd `data` (null, a string, a
  target with no attributes, a `target` naming nothing, a cycle of references) never throws.
- **Mansart Data `-dev`:** the `jdql` language's JSON (targets, types, `format`/`enum`, references, details, joined
  attributes left out, an entity whose model cannot be read left out); the three actions' schemas.
- **In the browser**, on `lc4jcdi-on-vidocq/mcp-tasks-server` under `vidocq:dev` (ports 18093/18094): the JDQL tab's
  colours; `FROM T` then completion of `Task`; the attributes of `Task` in `WHERE`; `project.` (when the test app has a
  reference; otherwise on the h2 example); an unknown attribute underlined; `params` offering `status` with its enum
  after `WHERE status = :status`, and flagging a missing one; *Format*; the Export CSV action's editor.

## 6. Documentation

With the `[.tag-new]#NEW#` badge: `dev-console.adoc` (the query mode, its keys, `PanelLanguage` and `languages()` for
a panel's author, `contentMediaType: text/x-query`, `x-language`, `x-parameters-of`), the JDQL section of Mansart
Data in the extensions page, the Javadoc of `PanelLanguage`, and an entry in `whats-new.adoc`.

## 7. Review focus

- A vocabulary from a large application (hundreds of entities): the language is fetched once, and completion and
  diagnostics stay fast per keystroke.
- A query being typed: half a keyword, a dangling `.`, `FROM` with nothing after it, a string left open, `(` never
  closed — the editor offers what fits and never throws.
- The target written after the caret, or changed while `params` holds values: `params`' schema follows the query,
  the values already typed stay.
- A reference cycle (`Task.project → Project.owner → … → Task`): paths resolve step by step and never loop.
- The language fetch failing or slow while the user types: the editor works with keywords only, then gains the
  vocabulary without losing the text or the caret.
