# MCP inspector in the dev console — design

* Date: 2026-09-26
* Builds on: Vidocq/vidocq#143 (the `-dev` panel modules), ADR 0001 (actions in the dev console)
* Module: `vidocq-runtime-langchain4j-cdi-mcp-extension-dev`, plus the devconsole SPI, the console and ADR 0001 for
  the generic part

## 1. Goal

A developer who writes MCP tools, prompts and resources with langchain4j-cdi on Vidocq tests them in seconds, from
the dev console's `mcp` tab. There is no separate MCP Inspector to start and no client to configure. The developer
picks a tool, fills its arguments, calls it, and reads the result, the exact JSON-RPC exchange and the recent calls.

It is a development tool: it exists under `vidocq:dev` only, and never ships (#143).

### In scope (v1)

- List and call the application's tools, get its prompts, and read its resources and resource templates.
- Arguments come from a form generated from the JSON Schema when the schema is flat, with a raw JSON editor otherwise.
- Calls go through the application's real `/mcp`, from the console's JVM.
- Results show the rendered content, the raw JSON-RPC request and response, and the last 20 calls of the boot, each
  one replayable.
- A tool that is not read-only asks for confirmation first.

### Out of scope (v1)

- Elicitation, sampling and roots. A tool that asks the client for input gets a clear refusal.
- Resource subscriptions, notifications and progress display, completion (`completion/complete`), and calling an
  external MCP server.

## 2. The generic part: `PanelAction`, amended

The inspector is built on the dev console's actions (ADR 0001). The actions gain what the inspector needs, in a form
any panel can use. Everything existing keeps working unchanged. The existing constructors and the
`Function<Map<String, String>, String>` of `run` stay, and so does the Migration panel's use of them.

### 2.1 A JSON argument

`PanelAction.Argument.json(String name, String label, String schema)`: the value is a JSON document, and `schema` is a
JSON Schema, as a string. It is at most 32 KiB and must parse as a JSON object, or the constructor throws.

- The value sent must parse, and its root must be an object; otherwise the console answers 400 before `run`. The
  console does not validate the value against the schema. The panel's target does, and the MCP server already
  refuses a wrongly typed argument with a JSON-RPC error.
- One action has at most one `json` argument. It may also have `oneOf` and `matching` arguments.
- The value reaches `run` as its JSON text, in the same `Map<String, String>`.
- `accepts(String)` stays the only check for the two string kinds. For `json`, the check is "parses as an object".

### 2.2 A structured result

`PanelAction.ActionResult(String summary, String contentType, String body, boolean error, String details)`:

- `summary` is the line an action returns today, at most 200 characters, never null (a null becomes `done`);
- `body` is optional content to show, at most 256 KiB: `contentType` is `text/plain` or `application/json`, and the
  page pretty-prints JSON;
- `error` is true when the call went through but its outcome is an error of the target, such as a tool returning
  `isError`. It is distinct from an exception thrown by `run`, which stays a 500 with the exception's simple class name;
- `details` is optional JSON, at most 256 KiB, shown folded. The inspector puts the exact JSON-RPC request and
  response there.

A longer `body` or `details` is truncated, and the result says so (`… truncated at 256 KiB`).

A new constructor takes `Function<Map<String, String>, ActionResult> call` in place of `run`. The record keeps one
internal form. The old `run` is wrapped as `args -> ActionResult.of(run.apply(args))`, so existing panels change
nothing.

### 2.3 Groups and descriptions

Two optional fields:

- `group`: a short title such as `Tools`. The page puts the actions of a panel in folded sections by group, in
  order of first appearance, with a text filter when a panel has more than ten actions.
- `description`: a longer text shown under the label, such as a tool's description, at most 2,000 characters.

### 2.4 The console and the page

- The body limit is 64 KiB for an action that has a `json` argument, and stays 4 KiB otherwise. The console
  checks the limit before reading the body.
- The snapshot carries, for each action: `group`, `description`, and for each `json` argument its `schema`. The
  answer to `POST /api/action/...` becomes `{"result": summary, "error": bool, "contentType": …, "body": …,
  "details": …}`; the fields are omitted when empty. A page that reads `result` only still works.
- The page renders a `json` argument as a generated form when the schema is flat, and as a raw JSON editor otherwise.
  - A schema is flat when its root is `"type": "object"`, and every property is a `string`, `number`, `integer` or
    `boolean`, or an `enum` of strings, with no `$ref` and no nesting.
  - The form uses `required`, `default`, `description` and `enum`.
  - A "JSON" switch shows the same value as raw JSON, and switching back keeps the values.
  - The raw editor starts from a skeleton made of the required properties.
- The page shows `summary`, colored when `error` is set, then `body`, then `details` folded under "Exchange".

### 2.5 ADR 0001, amendment 1

The ADR gains an *Amendment 1 (2026-09-26)* section. It states 2.1 to 2.4 and the limits: 64 KiB, 32 KiB and 256 KiB.
It also states that everything else holds unchanged: dev launch only, the per-boot token, same origin, POST JSON, one
action at a time per panel, the 60 s limit, and logging. The logged result line is `summary`.

## 3. The inspector (`mcp` panel, `-dev` module)

### 3.1 The catalogue

At `start(context)`, the panel reads langchain4j-cdi's registries through the `BeanManager`: `McpToolRegistry`,
`McpPromptRegistry` and `McpResourceRegistry`, with its templates. It reads them the way the rest of the panel reads
beans, taking only an instance that already exists. A registry with no instance yet counts as an empty catalogue, and
the panel says why. `stop()` drops the catalogue and the history. Each dev reload builds them again.

It builds three groups of actions:

| Group | One action per | Arguments | Confirmation |
|---|---|---|---|
| `Tools` | tool | one `json` argument, `arguments`, whose schema is the tool's input schema (`getModernInputSchema()`, else `getInputSchema()`) | if the tool is not `readOnlyHint: true`, or is `destructiveHint: true`: `Call <name>? It runs the application's code, and may change data.` |
| `Prompts` | prompt | one `json` argument whose flat schema has a `string` property per `PromptArg`, `required` as declared | never |
| `Resources` | fixed resource, and resource template | none for a fixed resource; for a template, one `json` argument with a flat schema of one `string` per variable, all required | never |

- **Labels and descriptions.** The label is the tool's title, or else its name, or the resource's URI (or URI
  template). The description is the item's description.
- **Action ids.** An action id must match the console's key rule: a lowercase letter, then at most 39 lowercase
  letters, digits, dots or hyphens. The id is a prefix (`tool.`, `prompt.`, `res.`, `tpl.`) followed by the name,
  lowercased, with any other character turned into `-`. It is cut to fit, and on a collision it gets a suffix `-2`,
  `-3` and so on. For a resource or template, the id is the prefix followed by the first 8 hex characters of the
  SHA-256 of the URI. The panel keeps the map from id to the real name or URI. Ids are therefore stable across
  reloads as long as the names do not change.

### 3.2 The call

A small MCP client inside the `-dev` module calls the application's own `/mcp`:

- **Address.** It takes the URL of `/mcp` the startup report prints, through the same route-to-URL resolution as
  `McpStartupSection` (`routeUrls` for `McpEndpoint`, without the internal listen route). A loopback URL is
  preferred. When there is none, the panel publishes no action and shows `absent: /mcp has no bound address`.
- **Protocol.** `java.net.http.HttpClient` sends a `POST` in protocol 2026-07-28, which is stateless:
  - the header is `MCP-Protocol-Version: 2026-07-28`, with the per-request `_meta` the modern era expects;
  - no `initialize` is sent and no session is opened, so `McpSessionManager` and its cleanup thread are never
    created;
  - the methods are `tools/call`, `prompts/get` and `resources/read`;
  - the client accepts `application/json` and `text/event-stream`: from an SSE answer it keeps the final JSON-RPC
    response, and ignores the notifications (progress, logging) that come before it.
- **Origin.** The request carries no `Origin` header. langchain4j-cdi's `McpOriginValidator` accepts a request
  without `Origin` (a non-browser client).
- **Time.** The request has a 55 s timeout, under the action limit of 60 s. At most one call runs at a time per
  panel, as for every action.
- **JSON.** Requests and responses are handled with `jakarta.json`, already on the module's path through
  langchain4j-cdi. No new dependency is added.

### 3.3 Results

| Outcome | `summary` | `error` | `body` |
|---|---|---|---|
| tool result | `ok in <ms> ms` | from `isError` | the content: the text items joined, or the JSON of `structuredContent` when present |
| prompt | `<n> message(s) in <ms> ms` | false | the messages as JSON |
| resource | `<n> content item(s) in <ms> ms` | false | the first text content, or the JSON of the contents |
| JSON-RPC error | `error <code>: <message>` | true | the error object |
| `input_required` (elicitation, sampling, roots) | `this tool asks the client for input (<kinds>): not supported by the dev console inspector yet` | true | the input request |
| transport failure | `/mcp unreachable at <url>`, `timed out after 55 s`, `HTTP <status>` | true | none |

`details` always holds the JSON-RPC request and response, or the SSE events, as sent and received, with secrets
masked (3.5).

### 3.4 History

The panel keeps the last 20 calls of the boot in memory. For each call it keeps the time, the action, the arguments
(masked), the summary, the error flag, the duration and the details. It publishes the history in its sample as a
table: newest first, with a "Replay" link that fills the action's form with those arguments. The page does the
replay: nothing is sent until the user submits. A masked value is not replayed; the user types it again.

### 3.5 Secrets

An argument whose property name contains, ignoring case, `password`, `passwd`, `secret`, `token`, `apikey`,
`api-key`, `api_key`, `credential` or `authorization` is replaced by `"***"` in three places:

- the history;
- `details`;
- the console's INFO log line.

The MCP server receives the real value. The result `body` is shown as the server returned it: the tool is the
developer's own code.

## 4. Errors

- An action that no longer exists (a tool renamed between reloads) gets the console's 404; the next snapshot shows
  the new list.
- A malformed JSON value, or a body over the limit, is refused by the console before the call (400, 413).
- An exception inside the client is caught and becomes a transport failure line. A `RuntimeException` that escapes
  anyway is the console's usual 500 with its simple class name.
- A catalogue that cannot be read (for example a registry that throws) turns the panel's actions off, and shows
  `absent: <reason>`. The live values of the panel keep working.

## 5. Testing

- **SPI** (`vidocq-runtime-devconsole-spi`):
  - `Argument.json`: a flat schema, a nested one, a schema that is not an object;
  - `ActionResult`: its limits and truncation;
  - the old constructors still produce the same behavior;
  - `group` and `description` bounds.
- **Console** (`vidocq-runtime-devconsole-extension`):
  - the 64 KiB and 4 KiB limits;
  - a `json` value that does not parse, and one whose root is not an object (400);
  - the new fields in the snapshot and in the action answer;
  - an old-style action still answers `{"result": …}`.
- **Page:** the page has no JS test harness. The flat-schema rule is implemented once in `console.js`, and tested
  through the snapshot JSON it consumes. A manual check under `vidocq:dev` is part of the plan's last task.
- **`-dev` module**, with the CDI test container `McpLivePanelTest` already uses and a real `/mcp` on an ephemeral
  port:
  - the catalogue and the ids, including collisions and URIs;
  - the confirmation rule (read-only, destructive, no annotation);
  - a tool call that succeeds, one with `isError`, a JSON-RPC error, an `input_required` refusal, an unreachable
    `/mcp`, and a truncated body;
  - a prompt get, a resource read and a template read;
  - secret masking in the history, the details and the log;
  - a history of 20 that is cleared on `stop()`.
- **IT** (`vidocq-runtime-it-langchain4j-cdi-mcp`): the application is booted, a tool is called through
  `POST /api/action/mcp/tool.<id>` with the snapshot's token, and the IT checks the result and the history. It also
  does one prompt get and one resource read.
- Ports: ephemeral or 18090-18099 only. Never 8080 or 8888.

## 6. Documentation

Every new section is tagged `[.tag-new]#NEW#`:

- ADR 0001, *Amendment 1*;
- `dev-console.adoc`: a *MCP inspector* section, what it does, the confirmation rule, the history, the secrets, what
  v1 does not do;
- `dev-console-panels.adoc`: how an action declares a `json` argument, returns an `ActionResult`, and uses `group`
  and `description`;
- the MCP extension's page (`modules/vidocq-runtime-extensions.adoc#langchain4j-cdi-mcp-live`);
- `whats-new.adoc`: one bullet.

## 7. Decisions taken with the user

- Test tools, prompts and resources quickly; elicitation and sampling are refused in v1.
- Calls go through the application's real `/mcp`, from the console's server.
- A form for flat schemas, with a raw JSON fallback.
- Approach A: the generic `PanelAction` gains a JSON argument with a schema, a structured result and groups, through
  an amendment of ADR 0001.
- Confirmation follows the tool annotations: a tool asks for confirmation unless it is `readOnlyHint: true` and not
  `destructiveHint: true`. Prompts and resources never ask.
- The result shows the rendered content, the raw JSON-RPC exchange, and the last 20 calls of the boot, each one
  replayable.
