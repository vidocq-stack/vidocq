# Dev console: a code editor for JSON arguments, and a form for a nested entity

Date: 2026-09-30. Asked while calling `TaskRepository.save(entity: Task)` from the *Mansart Data* panel of
`lc4jcdi-on-vidocq/mcp-tasks-server`: the argument is a raw textarea holding `{"entity": {}}`, and nothing tells the
developer which attributes `Task` has, which are required, or what went wrong in what they typed.

## 1. Goal

The dev console gets a shared code editor, written by hand into the page (no library: the page loads nothing from
elsewhere, and its Content Security Policy is `default-src 'self'`). A language plugs into it as data-driven rules;
the panel only supplies the data. This piece ships the editor and its first language, JSON driven by the argument's
JSON Schema, and teaches the generated form one level of nesting, so that an entity argument gets fields.

It is the first of three pieces:

1. **This spec:** the editor and the JSON language; the nested form; Mansart Data's richer entity schema.
2. **Next:** a query language mode (`FROM` / alias / `WHERE` / `ORDER BY`), fed by a panel's keywords and vocabulary
   (entities, attributes, types); JDQL in the *Mansart Data* panel, with its own diagnostics (unknown entity or
   attribute, a `:param` missing from `params`).
3. **Later:** table views and a SQL editor in the *Mansart pools* panel, reusing piece 2's query mode.

- **In scope:**
  - two new ES modules served with the page: `editor-core.js` (pure, no DOM) and `editor.js` (the DOM component);
  - the JSON language: highlighting, diagnostics, completion, formatting, smart keystrokes;
  - the editor in place of the raw JSON textarea of **every** json argument of every panel;
  - the generated form for a root property that is an object of scalar properties (one level), `readOnly`, and
    `format` hints;
  - `required`, `readOnly` and `description` in Mansart Data's entity schema;
  - GraalJS, test scope, to run `editor-core.js` in JUnit;
  - the documentation.
- **Out of scope:**
  - the query language and JDQL (piece 2); the `format: textarea` fields (the JDQL query, a CSV text) stay plain
    textareas until then;
  - forms deeper than one nested level, arrays in forms, `anyOf`/`oneOf` in forms (they stay in the JSON editor);
  - the JSON result viewer (unchanged);
  - multiple cursors, search and replace, code folding.

## 2. Files and contract

`META-INF/resources/devconsole/` gains `editor-core.js` and `editor.js`, both ES modules served by the console as
`console.js` is. `console.js` imports `editor.js`, which imports `editor-core.js`. The index still loads
`console.js` only. `PageTest.FILES` lists the two new files, so every existing rule (nothing loaded from elsewhere,
no markup parsing, `localStorage` inside a `try` only) applies to them.

`editor-core.js` exports pure functions and the languages, and never touches `document`, `window` or the DOM, so that
it runs in GraalJS without a browser. A language is an object:

```js
{
  id: "json",
  tokenize(text)              // → [{from, to, kind}], covering every non-blank character, in order
  diagnose(text, data)        // → [{from, to, severity: "error" | "warning", message}]
  complete(text, caret, data) // → {from, to, items: [{insert, label, detail, kind, caret?}]} or null
  format(text)                // → the formatted text, or throws an Error whose message says why
  pairs: ["{}", "[]", "()", "\"\""],
}
```

`from`/`to` are UTF-16 offsets into `text`. `caret`, on an item, is where the caret lands, as an offset into
`insert`; absent, it lands after it. `data` is the language's data: for JSON, the argument's schema.

The smart keystrokes are pure too: `keystroke(language, text, selectionStart, selectionEnd, key)` returns
`{from, to, insert, caret}` (the edit to make) or `null` (let the browser do it). The keys: an opening character of
`pairs`, a closing one, `Enter`, `Backspace`, `Tab`, `Shift+Tab`.

## 3. The JSON language

### 3.1 Tokens

`key` (a string followed by `:`), `string`, `number`, `literal` (`true`, `false`, `null`), `punct` (`{ } [ ] : ,`),
`invalid` (anything else, up to the next blank or punctuation). An unterminated string runs to the end of its line
and is a `string` token; the diagnostics report it. The page colours them with the JSON viewer's colours, in the light
theme and in both dark blocks.

### 3.2 Diagnostics

Syntax first: the first syntax error only, at its position (`unterminated string`, `expected ',' or '}'`,
`unexpected token`, `trailing comma`, `nothing after the value` …). When the text parses, the schema is checked, at
every depth, following `properties`, `items` (a schema, or the array form), `additionalProperties` when it is a schema,
and a local `$ref` (`#/$defs/...`, `#/definitions/...`; any other `$ref` is not followed, and its value not checked):

| Check | Severity | Where |
|---|---|---|
| a `required` key missing | error | the object's opening `{` |
| a value of the wrong `type` (`integer` refuses `1.5`; a `type` array accepts any of its types) | error | the value |
| a value outside `enum` | error | the value |
| a string longer than `maxLength` | error | the value |
| a key the schema's `properties` does not list, when the schema lists some and `additionalProperties` is not `true` or a schema | warning | the key |
| a string that does not look like its `format` (`date`, `time`, `date-time`, `uuid`) | warning | the value |

`anyOf`, `oneOf`, `allOf`, `not` and `patternProperties` are not checked: a value under them is accepted as it is.
Diagnostics never block sending: the server stays the judge, as today.

### 3.3 Completion

`complete` finds, by scanning the text up to the caret with the tokenizer, whether the caret is in a key position or
a value position, and the path of keys and indexes from the root to the object or array it is in; it resolves the
schema of that path as §3.2 does.

- **A key position** (after `{` or `,` in an object, or inside a key being typed): the keys of the object's schema not
  yet written in that object, required first (in schema order), then the others, then the `readOnly` ones. `label` is
  the key, `detail` its type (`string`, `integer`, `enum`, `object` …) with `required`/`generated`, and the
  description. `insert` is `"key": ` followed by the value start: `""` (the caret inside), `0`, `false`, `null`, the
  first `enum` value, `{}` or `[]` (the caret inside); an object whose schema has required keys starts with them.
  `from`/`to` cover what is already typed of the key, its quotes included.
- **A value position** (after `:` or in an array): the `enum` values, then `true`/`false` for a boolean, `null` when
  the type allows it, and `{}`/`[]` with their required keys for an object or an array.
- **Anywhere else**, or with no schema for the path: `null` (the page shows nothing).

Items are filtered by the prefix already typed, case-insensitive; the page keeps filtering while the list is open.

### 3.4 Formatting

`format` re-indents from the tokens, two spaces, one member per line, an empty `{}`/`[]` kept on one line, the text of
every string and number copied as written: an integer past 2^53 is never rounded, a `é` escape is never
rewritten. A text with a syntax error is not formatted: `format` throws, and the page shows the message.

### 3.5 Smart keystrokes

- An opening character of `pairs` with no selection inserts the pair, the caret between; with a selection, wraps it.
  A `"` opens a pair only where a string may start (not inside a string, not right after a letter or digit).
- A closing character typed right before the same closing character steps over it.
- `Enter` keeps the current line's indentation; between `{}` or `[]` it opens an indented line and puts the closing
  character on the next one.
- `Backspace` right between an empty pair (`{}`, `[]`, `()`, `""`) deletes both.
- `Tab` inserts two spaces (or indents the selected lines), `Shift+Tab` outdents them. `Escape` then `Tab` leaves the
  editor, so keyboard navigation is not trapped.

## 4. The editor component

`editor.js` exports `createEditor({language, data, value, rows, label})`, which returns
`{root, value(), setValue(text), disable(on), focus(), textarea}`.

- **Structure:** a gutter of line numbers, then a box holding a `<pre>` (the coloured text) under a `<textarea>` whose
  text is transparent and whose caret shows. Both share font, size, line height, padding, tab size and wrapping
  (`pre-wrap`); the `<pre>` follows the textarea's scroll. The coloured text is built from spans whose text is set
  with `textContent`: never `innerHTML`.
- **Diagnostics:** a wavy underline on their range (red for an error, orange for a warning), a dot on the line in the
  gutter, and the message in a tooltip element when the pointer or the caret is on the range. The note under the
  editor counts them (`1 error, 2 warnings`).
- **Completion list:** `Ctrl+Space` opens it; it also opens by itself after a `"` typed in a key position. `Up`/`Down`
  move, `Enter`/`Tab` accept, `Escape` closes, typing filters, a click accepts. It sits at the caret, placed by
  measuring the caret with a hidden mirror of the textarea. Each item shows its label and, dimmed, its detail.
- **Matching bracket:** with the caret next to a bracket, it and its match are outlined.
- **Formatting:** a *Format* button in the editor's bar, and `Shift+Alt+F`.
- **Undo:** every edit the editor makes goes through `document.execCommand("insertText")`, which keeps `Ctrl+Z`;
  where it is refused, `setRangeText` and an `input` event.
- **Size limit:** past 100 000 characters the editor stops colouring and diagnosing (a note says so) and behaves as a
  plain textarea; completion and formatting still work on demand.
- **Work per keystroke:** tokens, colours and diagnostics are computed again in one `requestAnimationFrame` after an
  input, never twice in a frame.

## 5. The nested form

`isFlatSchema` becomes `formShape(schema)`, the one place the rule is written. It returns the form's fields, or
`null` for the JSON editor:

- the root is `"type": "object"`, with no `$ref`/`anyOf`/`oneOf`/`allOf`/`not`;
- each root property is a scalar (`string`, `number`, `integer`, `boolean`) or an enum of strings, as today, **or** an
  object (`"type": "object"`) whose own properties all are scalars or enums of strings, with no `$ref` and no deeper
  nesting.

A nested object is a `fieldset` whose legend is its name, holding its fields. Everything else behaves as today, per
level:

- `required` at each level: a ` *` after the name; sending refuses a missing one with its path, `entity.title is
  required`. A nested object that is itself not required and left entirely empty is not sent.
- `readOnly: true`: the name is followed by ` (generated)`, the field's placeholder is `generated`; it is never
  required. Left empty it is not sent; filled, it is sent (a `save` that updates a row).
- `format`: the placeholder shows the expected shape: `2026-09-30`, `14:30:00`, `2026-09-30T14:30:00`,
  `123e4567-e89b-12d3-a456-426614174000`.
- The JSON switch, the skeleton (the required keys at each level), `fill` (replaying a call) and masked values handle
  the nested object.

The JSON mode is the editor of §4 with the argument's schema; a schema `formShape` refuses gets the editor only, as it
gets the raw textarea today.

## 6. Mansart Data's entity schema

`EntityJson.schema(entity)` adds, per settable attribute:

- `required`: the attribute is not `nullable()`, its field type is not primitive, and it is not a generated id;
- `readOnly: true`: a generated id (`IdAttribute.generated()`) or the version (`VersionAttribute`);
- `description`: `column <column name>`, and for a reference `id of <Entity simple name>, column <column name>`.

`Signature.of` is unchanged: every parameter stays required at the root. A generated id left empty keeps today's
behaviour of `save` (insert); the server-side conversion does not change.

## 7. Tests

- **`EditorCoreTest`** (devconsole extension, JUnit, GraalJS `org.graalvm.polyglot:polyglot` and
  `org.graalvm.polyglot:js-community`, test scope, the newest release that runs on Java 25, interpreter mode with
  `polyglot.engine.WarnInterpreterOnly=false`): loads `editor-core.js` as an ES module from the main resources and
  calls its exports with JSON built in Java. It covers:
  - tokens of every kind, an unterminated string, an invalid run;
  - each syntax error of §3.2 at its offset; each schema check of the table at its range, at depth, through `items`
    and a local `$ref`; nothing under `anyOf`;
  - key completion (missing keys only, required first, `readOnly` last, the insert with the caret inside, a partly
    typed key replaced with its quotes), value completion (`enum`, boolean, `null`, an object with its required
    keys), `null` outside;
  - formatting: indentation, an empty `{}` kept, `12345678901234567890` kept, `é` kept, a syntax error thrown;
  - each smart keystroke of §3.5, `"` not paired after a letter.
- **`PageTest`:** the two new files in `FILES`; `console.js` imports `editor.js` and no other script; the editor
  builds its `<pre>` with `textContent`; `jsonField` uses `createEditor`; `formShape` is the rule of §5; the token
  colours defined in the light theme and in both dark blocks.
- **Mansart Data `-dev`:** `EntityJsonTest` (required, not required for a nullable, a primitive, a generated id;
  `readOnly` for a generated id and a version; the descriptions), `SignatureTest` (the `save(Task)` schema).
- **In the browser:** `mcp-tasks-server` under `vidocq:dev`, ports 18093/18094: `save(Task)` as fields (stars, the
  generated id), a call rolled back; the JSON mode (colours, an error and its tooltip, `Ctrl+Space`, *Format*,
  `Ctrl+Z`); a tool's arguments in the MCP inspector.

## 8. Documentation

With the `[.tag-new]#NEW#` badge: `dev-console.adoc` (the editor, its keys, the JSON Schema keywords the page reads,
the nested form, `readOnly`), the *Mansart Data* section of the extensions page (the entity form), the Javadoc of
`PanelAction.Argument.json` (the keywords the page understands), and an entry in `whats-new.adoc`.

## 9. Review focus

- A schema from elsewhere (an MCP tool's) that is odd: `type` missing, `properties` not an object, a `$ref` cycle,
  a `required` naming an absent key. The editor never throws on it: it checks less.
- A text with a caret inside a string, a comment-like `//`, CRLF line ends, tabs, emoji (surrogate pairs): offsets stay
  right, the colours stay aligned with the text.
- Undo after an accepted completion, a formatting, an auto-closed pair: one `Ctrl+Z` undoes each.
- The completion list near the right or bottom edge, in a scrolled editor, in a narrow window.
- A form's nested object with a key named `__proto__`: it is a key like any other, as at the root today.
