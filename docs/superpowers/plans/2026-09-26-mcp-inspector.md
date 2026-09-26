# MCP inspector in the dev console — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In a `vidocq:dev` launch, the `mcp` tab of the dev console lists the application's MCP tools, prompts,
resources and resource templates, calls them through the application's own `/mcp`, and shows the result, the exact
JSON-RPC exchange and the last 20 calls, each replayable.

**Architecture:**
- **Generic part (ADR 0001, amendment 1):**
  - `PanelAction` gains a `json` argument with a JSON Schema, a structured `ActionResult`, a `group` and a
    `description`; the old constructors and `run` keep working.
  - The console accepts a larger body for such an action, checks the JSON value, answers the structured result, and
    carries the new fields in the snapshot.
  - The page draws a form for a flat schema, a raw JSON editor otherwise, the result, the exchange and the groups.
- **Runtime MCP extension:** publishes the resolved `/mcp` URLs in its `.live` package, as Mansart publishes its
  pools.
- **`-dev` module:** reads the catalogue from langchain4j-cdi's registries, builds one action per item, calls `/mcp`
  with a small stateless `java.net.http` client in protocol 2026-07-28, maps the answer to an `ActionResult`, masks
  secrets, and keeps a history of 20 calls in its sample.

**Tech Stack:** Java 25, JPMS, Maven 3.9, `java.net.http`, `jakarta.json` (JSON-P, already on the `-dev` module's
path through langchain4j-cdi), langchain4j-cdi MCP server 1.4.0-SNAPSHOT, vanilla JavaScript, JUnit 5,
`com.sun.net.httpserver` (JDK, tests only).

**Spec:** `docs/superpowers/specs/2026-09-26-mcp-inspector-design.md`. Read it first; it is the binding authority,
and section numbers below refer to it.

## Global Constraints

- **Toolchain.**
  - Java 25. Every Maven call starts with
    `export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH;`.
  - `mvn` only, never `./mvnw`; add `-o` unless a download is needed. Run long Maven commands through the wrapper
    script, as in #122.
  - Build one module with `mvn -o -pl <module> install` or `test`. For the runtime MCP module and its `-dev`
    companion, run `install -DskipTests` on the runtime module, then `test` on the `-dev` one, and never `clean`
    between them: the runtime module's `module-info` compiles at `prepare-package`.
- **Module paths** used by every command below (run from the repository root):
  ```
  SPI=vidocq-runtime-devconsole-spi
  CONSOLE=vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension
  MCP=vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-langchain4j-cdi-mcp-extension
  DEV=vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-langchain4j-cdi-mcp-extension-dev
  IT=vidocq-runtime-integration-tests/vidocq-runtime-it-langchain4j-cdi-mcp
  ```
- **Branch.** `feat/mcp-inspector`, created from `docs/mcp-inspector-design`, in the main checkout. The repository
  has untracked files of the user's: never `git add -A` or `git add .`; add the files a task lists.
- **Backward compatibility is required** (spec §2):
  - `PanelAction(id, label, confirmation, run)` and `PanelAction(id, label, confirmation, arguments, run)` keep
    their signatures, `run` stays a `Function<Map<String, String>, String>`, and `action.run().apply(...)` still
    returns the line;
  - `PanelAction.Argument(name, label, allowedValues, pattern)` and `Argument.oneOf`/`matching` keep their meaning;
  - the Migration, Logs and Tests panels change nothing;
  - an action that returns one line answers exactly `{"result": "<line>"}`, as today.
- **Names and limits, verbatim** (spec §2-§3):
  - `PanelAction.Argument.json(String name, String label, String schema)`; a schema of at most 32 KiB
    (`32 * 1024` characters) that parses as a JSON object; one `json` argument per action at most;
  - `PanelAction.ActionResult(String summary, String contentType, String body, boolean error, String details)`;
    summary at most 200 characters, `null` → `done`; `contentType` `text/plain` or `application/json`; `body` and
    `details` at most 256 KiB (`256 * 1024` characters), truncated with the marker `… truncated at 256 KiB`;
  - `group` at most 40 characters, `description` at most 2,000 characters;
  - console body limit: 64 KiB (`64 * 1024` bytes) for an action with a `json` argument, 4 KiB otherwise;
  - action ids: prefixes `tool.`, `prompt.`, `res.`, `tpl.`; groups `Tools`, `Prompts`, `Resources`;
  - confirmation text: `Call <name>? It runs the application's code, and may change data.`;
  - `/mcp` protocol `2026-07-28`, timeout 55 s, history of 20 calls;
  - absent reason `/mcp has no bound address`; input refusal
    `this tool asks the client for input (<kinds>): not supported by the dev console inspector yet`;
  - transport lines `/mcp unreachable at <url>`, `timed out after 55 s`, `HTTP <status>`;
  - secret markers `password`, `passwd`, `secret`, `token`, `apikey`, `api-key`, `api_key`, `credential`,
    `authorization`, ignoring case; mask `***`.
- **No new dependency.** `jakarta.json-api` 2.1.3 is already on the `-dev` module's path through the runtime module;
  the `-dev` pom only declares it explicitly (Task 8). `java.net.http` and `com.sun.net.httpserver` are JDK modules.
- **Code rules.**
  - Code, Javadoc, comments, commits and docs in English; lines at most 120 characters; the Javadoc density of the
    files around.
  - Each new Java file starts with the 19-line license header of
    `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/RecompileRunner.java`, which the code
    blocks below omit.
  - The repository uses no Spotless or Palantir formatter: follow the style of the file you edit.
  - `console.js` stays vanilla JavaScript: every text through `textContent` or a text node, never `innerHTML`; no
    `confirm(`, `alert(` or `prompt(` call; no `setAttribute("style"`. `PageTest` checks these.
- **Ports:** ephemeral (`0`) or 18090-18099 only; never 8080 or 8888. The `-dev` tests bind their stub `/mcp` to the
  loopback on port `0`; the IT uses `LaunchedServer`, which picks free ports in 18090-18099.
- **Commits:** the executor's wrapper script signs each commit and adds the trailers. Each task's commit step gives
  the subject line and the files to add, nothing else.
- **Docs:** English; every new section tagged `[.tag-new]#NEW#`; one `whats-new.adoc` bullet (spec §6).

## Rulings (where this plan settles what the spec leaves open)

1. **The `/mcp` URL comes from a holder.** `routeUrls` exists on `StartupReportContext` only, which a live panel's
   `start(ExtensionContext)` does not get. So `McpExtension.contribute` publishes
   `McpStartupSection.endpointUrls(context.routeUrls(ENDPOINT))` in a new `McpEndpointLive` of its `.live` package
   (already `exports ... to ....mcp.dev`), and `onStop` clears it. The report is written before the console starts
   its live panels, so the panel always finds this boot's URLs. A loopback URL is preferred; with none, the first.
2. **The SPI checks JSON by hand.** `vidocq-runtime-devconsole-spi` depends on no JSON library. A package-private
   `JsonCheck` is as strict as the console's `JsonValues` (a name written twice, text after the value, a raw control
   character, a bad number, nesting deeper than 64 are refused), so that whatever the SPI accepts the console can
   parse.
3. **A `json` value travels as a JSON string.** The page sends `{"arguments": "{\"city\":\"Paris\"}"}`: the action
   body stays an object of strings, `JsonStrings` is unchanged, and the value reaches `call`/`run` as its text.
4. **The snapshot carries a schema as a JSON object**, not a string; `group` and `description` only when set;
   `last.error` only when `true`. The answer omits `error` when `false` and the other fields when `null`, so a
   one-line action answers `{"result": …}` byte for byte as before.
5. **`PanelAction`'s one internal form is `call`.** The record components become
   `(id, label, confirmation, arguments, call, group, description)`; `run()` stays as a method that returns the
   summary of `call`. An old `run` returning `null` now reads `done` through `run()` too, which is what the console
   always showed.
6. **`PanelEntry.MAX_ACTIONS` goes from 16 to 128.** An MCP server easily has more than 16 tools; 16 would silently
   drop them.
7. **Replay travels through a table column.** `PanelSample.REPLAY_COLUMN = "replay"`: each cell is
   `<action id> <JSON object of its arguments by name>`. The console keeps such a cell whole up to
   `PanelSample.MAX_REPLAY_CELL = 4096` characters and empties a longer one; the page draws it as a "Replay" button
   that fills the action's form and sends nothing.
8. **The inspector declares elicitation, sampling and roots.** langchain4j-cdi refuses an interaction whose
   capability the client did not declare with a JSON-RPC error; declaring them makes such a tool answer
   `input_required`, which the inspector then reports as spec §3.3 says. In `CONTINUATION` mode the parked
   invocation ends by the server's own continuation timeout.
9. **Confirmation reads the annotations model as langchain4j-cdi builds it.** `McpToolAnnotationsModel` keeps a
   member only when it differs from its default, so `destructiveHint` is `null` or `false` from `@Tool`; the rule is
   "ask unless `readOnlyHint` is `TRUE` and `destructiveHint` is not `TRUE`", which honours an explicit `TRUE` from a
   hand-built descriptor.
10. **Limits are in characters** for `ActionResult` (256 KiB = 262,144 characters) and the schema (32,768); the
    console's body limit stays in bytes.
11. **The `-dev` tests use a stub `/mcp`.** Booting langchain4j-cdi's JAX-RS endpoint needs Cassini and Chappe,
    which the `-dev` module does not have. The stub (`com.sun.net.httpserver`, loopback, port `0`) runs langchain4j-
    cdi's own `McpEraDetector` on every request, so the headers and `_meta` are checked by the server's code; the
    real endpoint is covered by the IT (Task 10).
12. **An unexpected exception inside the client** becomes the transport line `/mcp call failed: <SimpleClassName>`.
13. **Secrets are also scrubbed by value.** Besides the name rule of spec §3.5, the string values of secret
    arguments (4 characters or more) are replaced by `***` in `summary` and `details`, since a server's error
    message may quote them, and `summary` is the console's log line. `body` is never touched.
14. **A resource template's URI** is expanded as RFC 6570 level 1 (every byte but `A-Z a-z 0-9 - . _ ~`
    percent-encoded), which langchain4j-cdi's matcher decodes.
15. **The history keeps `details` in memory** as spec §3.4 says; the table shows no details (a cell holds 200
    characters).
16. **Exact JSON strings in the `-dev` tests rely on the JSON-P provider on the path**, Champollion's
    `champollion-jsonp` (brought by the runtime extension through Cassini): its `toString` is compact and keeps
    insertion order, and it does not escape `/`. If a test fails only on member order or spacing, compare parsed
    `JsonObject`s instead of weakening the assertion.

## Review Focus

1. **A tool name or resource URI that is not plain ASCII** (`météo`, a space at an end): `Mcp-Name` must be sent
   `=?base64?…?=`, or the server answers `-32020 HeaderMismatch`. Test in Task 7.
2. **A tool with an `@McpHeader`-designated argument**: the client must mirror it into `Mcp-Param-<designation>`, or
   every call fails with `-32020`. Test in Task 7.
3. **A template variable holding `/` or a space** (`Europe/Paris`): it must be percent-encoded and still reach the
   method decoded. Tests in Tasks 7 and 8, and the IT (Task 10).
4. **An application with more than 16 tools**: every tool must get its action, up to 128. Test in Task 3.
5. **A secret quoted back by the server** (`Invalid token hunter22`): the summary, which the console logs at INFO,
   and the details must not carry it. Test in Task 9.

---

## File structure

| Area | Files |
|---|---|
| SPI | `SPI_SRC/PanelAction.java` (json argument, `ActionResult`, `call`, `group`, `description`), `SPI_SRC/JsonCheck.java` (new), `SPI_SRC/PanelSample.java` (replay column constants) |
| Console | `CONSOLE_SRC/ConsoleActions.java`, `Snapshot.java`, `PanelEntry.java`, `Texts.java`, `RecordingSample.java`; `CONSOLE_RES/console.js`, `console.css` |
| Runtime MCP | `MCP_SRC/live/McpEndpointLive.java` (new), `MCP_SRC/McpExtension.java` |
| `-dev` | `DEV_SRC/ActionIds.java`, `McpCatalogue.java`, `McpClient.java`, `McpTransportException.java`, `UriTemplates.java`, `McpResults.java`, `McpInspector.java`, `Secrets.java`, `CallHistory.java` (all new), `McpLivePanel.java`, `module-info.java`, `pom.xml` |
| IT | `IT_TEST/McpInspectorConsoleTest.java` (new) |
| Docs | `docs/adr/0001-dev-console-actions.md`, `dev-console.adoc`, `dev-console-panels.adoc`, `modules/vidocq-runtime-extensions.adoc`, `whats-new.adoc` |

Abbreviations:
- `SPI_SRC/` = `vidocq-runtime-devconsole-spi/src/main/java/io/vidocq/runtime/spi/devconsole`, `SPI_TEST/` its test
  twin;
- `CONSOLE_SRC/` = `$CONSOLE/src/main/java/io/vidocq/runtime/extensions/essentials/devconsole`, `CONSOLE_TEST/` its
  test twin, `CONSOLE_RES/` = `$CONSOLE/src/main/resources/META-INF/resources/devconsole`;
- `MCP_SRC/` = `$MCP/src/main/java/io/vidocq/runtime/extensions/essentials/langchain4jcdi/mcp`, `MCP_TEST/` its test
  twin;
- `DEV_SRC/` = `$DEV/src/main/java/io/vidocq/runtime/extensions/essentials/langchain4jcdi/mcp/dev`, `DEV_TEST/` its
  test twin;
- `IT_TEST/` = `$IT/src/test/java/io/vidocq/runtime/it/lc4jcdimcp`;
- `DOCS/` = `docs/en/modules/ROOT/pages`.

---

### Task 1: The SPI's `json` argument

**Files:**
- Create: `SPI_SRC/JsonCheck.java`, `SPI_TEST/JsonCheckTest.java`
- Modify: `SPI_SRC/PanelAction.java` (the nested `Argument` record, and the checks of the canonical constructor)
- Test: `SPI_TEST/PanelActionTest.java` (added tests)

**Interfaces:**
- Produces:
  - `final class JsonCheck` (package-private): `static boolean isObject(String text, int maxDepth)`;
  - `PanelAction.Argument` components become `(String name, String label, List<String> allowedValues, String pattern,
    String schema)`, with the 4-argument constructor kept;
  - `static Argument json(String name, String label, String schema)`;
  - constants `Argument.MAX_SCHEMA = 32 * 1024`, `Argument.MAX_JSON_DEPTH = 64`;
  - `Argument.accepts(String)`: for a `json` argument, "parses as a JSON object", with no 200-character limit.

- [ ] **Step 1: Write the failing tests**

`SPI_TEST/JsonCheckTest.java`:

```java
package io.vidocq.runtime.spi.devconsole;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The SPI's own JSON check: as strict as the console's reader, so that what one accepts the other can read. */
class JsonCheckTest {

    @Test
    void oneObjectIsAccepted() {
        assertTrue(JsonCheck.isObject("{}", 64));
        assertTrue(JsonCheck.isObject(" {\"a\":1,\"b\":[true,null,\"x\"],\"c\":{\"d\":-1.5e3}} ", 64));
        assertTrue(JsonCheck.isObject("{\"u\":\"\\u00e9\\n\"}", 64));
    }

    @Test
    void anythingButOneObjectIsRefused() {
        assertFalse(JsonCheck.isObject(null, 64));
        assertFalse(JsonCheck.isObject("", 64));
        assertFalse(JsonCheck.isObject("[]", 64));
        assertFalse(JsonCheck.isObject("\"x\"", 64));
        assertFalse(JsonCheck.isObject("{\"a\":1} x", 64), "text after the object");
        assertFalse(JsonCheck.isObject("{\"a\":1,\"a\":2}", 64), "a name written twice");
        assertFalse(JsonCheck.isObject("{\"a\":01}", 64), "a number JSON does not allow");
        assertFalse(JsonCheck.isObject("{\"a\":\"\u0001\"}", 64), "a raw control character");
        assertFalse(JsonCheck.isObject("{'a':1}", 64));
        assertFalse(JsonCheck.isObject("{\"a\":1,}", 64));
        assertFalse(JsonCheck.isObject("{\"a\":", 64));
    }

    @Test
    void nestingPastTheLimitIsRefused() {
        assertFalse(JsonCheck.isObject("{\"a\":{\"b\":1}}", 1));
        assertTrue(JsonCheck.isObject("{\"a\":{\"b\":1}}", 2));
        assertFalse(JsonCheck.isObject("{\"a\":[[1]]}", 2));
    }
}
```

Add to `SPI_TEST/PanelActionTest.java`:

```java
    @Test
    void aJsonArgumentCarriesItsSchemaAndAcceptsAnObjectOnly() {
        String schema = "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}";
        PanelAction.Argument arguments = PanelAction.Argument.json("arguments", "Arguments", schema);

        assertEquals(schema, arguments.schema());
        assertNull(arguments.allowedValues());
        assertNull(arguments.pattern());
        assertTrue(arguments.accepts("{\"city\":\"Paris\"}"));
        assertTrue(arguments.accepts("{\"city\":\"" + "a".repeat(1000) + "\"}"),
                "no 200-character limit: the console's body limit bounds a json value");
        assertFalse(arguments.accepts("{\"city\":"));
        assertFalse(arguments.accepts("[\"Paris\"]"));
        assertFalse(arguments.accepts("\"Paris\""));
        assertFalse(arguments.accepts(null));
    }

    @Test
    void aNestedSchemaIsFineAsLongAsItIsAnObject() {
        String nested = "{\"type\":\"object\",\"properties\":{\"when\":{\"type\":\"object\","
                + "\"properties\":{\"at\":{\"type\":\"string\"}}}}}";

        assertEquals(nested, PanelAction.Argument.json("arguments", "Arguments", nested).schema());
    }

    @Test
    void aSchemaThatIsNoObjectOrTooLargeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> PanelAction.Argument.json("arguments", "Arguments", "[]"));
        assertThrows(IllegalArgumentException.class,
                () -> PanelAction.Argument.json("arguments", "Arguments", "{\"type\":"));
        assertThrows(NullPointerException.class, () -> PanelAction.Argument.json("arguments", "Arguments", null));
        String large = "{\"description\":\"" + "a".repeat(PanelAction.Argument.MAX_SCHEMA) + "\"}";
        assertThrows(IllegalArgumentException.class,
                () -> PanelAction.Argument.json("arguments", "Arguments", large));
        assertThrows(IllegalArgumentException.class,
                () -> new PanelAction.Argument("a", "A", null, "[a-z]+", "{}"), "a pattern or a schema, not both");
    }

    @Test
    void anActionHasOneJsonArgumentAtMostBesideItsOtherArguments() {
        PanelAction.Argument one = PanelAction.Argument.json("arguments", "Arguments", "{}");
        PanelAction.Argument two = PanelAction.Argument.json("variables", "Variables", "{}");

        PanelAction mixed = new PanelAction("call", "Call", null, List.of(one,
                PanelAction.Argument.oneOf("mode", "Mode", "fast", "slow"),
                PanelAction.Argument.matching("tag", "Tag", "[a-z]{1,10}")), a -> "ok");

        assertEquals(3, mixed.arguments().size());
        assertThrows(IllegalArgumentException.class,
                () -> new PanelAction("call", "Call", null, List.of(one, two), a -> "ok"));
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -o -q -pl $SPI test -Dtest='JsonCheckTest,PanelActionTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAILURE, `cannot find symbol: class JsonCheck` and `method json`.

- [ ] **Step 3: Write the implementation**

`SPI_SRC/JsonCheck.java`:

```java
package io.vidocq.runtime.spi.devconsole;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whether a text is one JSON object (RFC 8259), checked by hand: this module depends on no JSON library. As strict
 * as the console's own reader, so that whatever this accepts the console can read: a name written twice in one
 * object, text after the object, a raw control character in a string, a number JSON does not allow, or values
 * nested deeper than the limit are refused.
 */
final class JsonCheck {

    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    private final String text;
    private final int maxDepth;
    private int at;

    private JsonCheck(String text, int maxDepth) {
        this.text = text;
        this.maxDepth = maxDepth;
    }

    /**
     * Whether {@code text} is one JSON object within {@code maxDepth}, and nothing else.
     *
     * @param text     the text, or {@code null}
     * @param maxDepth how deep objects and arrays may nest, {@code 1} for one object of scalars
     * @return {@code true} when it is
     */
    static boolean isObject(String text, int maxDepth) {
        if (text == null) {
            return false;
        }
        JsonCheck json = new JsonCheck(text, maxDepth);
        try {
            json.blanks();
            if (json.peek() != '{') {
                return false;
            }
            json.value(0);
            json.blanks();
            return json.at == text.length();
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private void value(int depth) {
        blanks();
        switch (peek()) {
            case '{' -> object(depth + 1);
            case '[' -> array(depth + 1);
            case '"' -> string();
            case 't' -> literal("true");
            case 'f' -> literal("false");
            case 'n' -> literal("null");
            default -> number();
        }
    }

    private void object(int depth) {
        if (depth > maxDepth) {
            throw new IllegalArgumentException("nested too deep");
        }
        Set<String> names = new HashSet<>();
        at++;
        blanks();
        if (peek() == '}') {
            at++;
            return;
        }
        while (true) {
            blanks();
            if (peek() != '"' || !names.add(string())) {
                throw new IllegalArgumentException("a name was expected, once");
            }
            blanks();
            expect(':');
            value(depth);
            blanks();
            if (peek() == ',') {
                at++;
            } else {
                expect('}');
                return;
            }
        }
    }

    private void array(int depth) {
        if (depth > maxDepth) {
            throw new IllegalArgumentException("nested too deep");
        }
        at++;
        blanks();
        if (peek() == ']') {
            at++;
            return;
        }
        while (true) {
            value(depth);
            blanks();
            if (peek() == ',') {
                at++;
            } else {
                expect(']');
                return;
            }
        }
    }

    /** Reads a string and returns it decoded, so that two spellings of one name count as the same name. */
    private String string() {
        at++;
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return out.toString();
            }
            if (c < 0x20) {
                throw new IllegalArgumentException("a control character in a string");
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escaped = next();
            switch (escaped) {
                case '"', '\\', '/' -> out.append(escaped);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    int code = 0;
                    for (int i = 0; i < 4; i++) {
                        int digit = Character.digit(next(), 16);
                        if (digit < 0) {
                            throw new IllegalArgumentException("a \\u escape that is not hexadecimal");
                        }
                        code = code * 16 + digit;
                    }
                    out.append((char) code);
                }
                default -> throw new IllegalArgumentException("an unknown escape");
            }
        }
    }

    private void number() {
        int start = at;
        while (at < text.length() && "-+0123456789.eE".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
        if (!NUMBER.matcher(text.substring(start, at)).matches()) {
            throw new IllegalArgumentException("a value was expected");
        }
    }

    private void literal(String word) {
        if (!text.startsWith(word, at)) {
            throw new IllegalArgumentException("a value was expected");
        }
        at += word.length();
    }

    private void blanks() {
        while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
    }

    private char peek() {
        if (at >= text.length()) {
            throw new IllegalArgumentException("end of text");
        }
        return text.charAt(at);
    }

    private char next() {
        char c = peek();
        at++;
        return c;
    }

    private void expect(char c) {
        if (peek() != c) {
            throw new IllegalArgumentException("'" + c + "' was expected");
        }
        at++;
    }
}
```

In `SPI_SRC/PanelAction.java`:

- In the canonical constructor of `PanelAction`, after the loop that checks the names, add:

```java
        if (arguments.stream().filter(argument -> argument.schema() != null).count() > 1) {
            throw new IllegalArgumentException("action '" + id + "' declares more than one json argument");
        }
```

- Replace the nested `Argument` record (its Javadoc, components, constructors, factories and `accepts`) with:

```java
    /**
     * One value an action takes, and what the console accepts for it: a value of {@code allowedValues}, which the
     * page offers as a list; a whole match of {@code pattern}, which it offers as a text field; or a JSON object
     * described by the JSON Schema {@code schema}, which it offers as a form when the schema is flat and as a JSON
     * editor otherwise. Exactly one of the three is set.
     *
     * @param name          the key of the value in the request and in the map the action receives; it follows the
     *                      rule of {@link PanelSample#requireKey}, such as {@code level}
     * @param label         what the page writes next to its field; neither {@code null} nor blank
     * @param allowedValues the values accepted, in the order the page lists them, at least one, none blank nor
     *                      longer than {@value #MAX_VALUE_LENGTH} characters; an immutable copy, or {@code null}
     * @param pattern       the regular expression, {@link Pattern} syntax, that a value must match as a whole, or
     *                      {@code null}
     * @param schema        a JSON Schema, as the text of a JSON object of at most {@value #MAX_SCHEMA} characters,
     *                      or {@code null}; the console never validates a value against it, the action's target does
     */
    public record Argument(String name, String label, List<String> allowedValues, String pattern, String schema) {

        /** The longest schema of a {@link #json json} argument, in characters. */
        public static final int MAX_SCHEMA = 32 * 1024;
        /** How deep a {@link #json json} value or schema may nest. */
        public static final int MAX_JSON_DEPTH = 64;

        public Argument {
            PanelSample.requireKey(name);
            Objects.requireNonNull(label, "label");
            if (label.isBlank()) {
                throw new IllegalArgumentException("argument '" + name + "' has a blank label");
            }
            int kinds = (allowedValues == null ? 0 : 1) + (pattern == null ? 0 : 1) + (schema == null ? 0 : 1);
            if (kinds != 1) {
                throw new IllegalArgumentException("argument '" + name
                        + "' needs exactly one of its allowed values, a pattern or a schema");
            }
            if (allowedValues != null) {
                allowedValues = List.copyOf(allowedValues);
                if (allowedValues.isEmpty()) {
                    throw new IllegalArgumentException("argument '" + name + "' allows no value");
                }
                for (String value : allowedValues) {
                    if (value.isBlank() || value.length() > MAX_VALUE_LENGTH) {
                        throw new IllegalArgumentException("argument '" + name + "' allows a blank value or one "
                                + "longer than " + MAX_VALUE_LENGTH + " characters");
                    }
                }
            } else if (pattern != null) {
                try {
                    Pattern.compile(pattern);
                } catch (PatternSyntaxException invalid) {
                    throw new IllegalArgumentException("argument '" + name + "' has an invalid pattern", invalid);
                }
            } else if (schema.length() > MAX_SCHEMA || !JsonCheck.isObject(schema, MAX_JSON_DEPTH)) {
                throw new IllegalArgumentException("argument '" + name + "' has a schema that is not a JSON object "
                        + "of at most " + MAX_SCHEMA + " characters");
            }
        }

        /**
         * A value among a list, or a whole match of a pattern: the two string kinds, as before the {@link #json json}
         * kind existed.
         *
         * @param name          its name, by the key rule
         * @param label         what the page writes next to it
         * @param allowedValues the values accepted, or {@code null} when {@code pattern} is set
         * @param pattern       the regular expression, or {@code null} when {@code allowedValues} is set
         */
        public Argument(String name, String label, List<String> allowedValues, String pattern) {
            this(name, label, allowedValues, pattern, null);
        }

        /**
         * An argument whose value is one of {@code values}.
         *
         * @param name   its name, by the key rule
         * @param label  what the page writes next to it
         * @param values the values accepted, at least one
         * @return the argument
         */
        public static Argument oneOf(String name, String label, String... values) {
            return new Argument(name, label, List.of(values), null, null);
        }

        /**
         * An argument whose value matches {@code regex} as a whole.
         *
         * @param name  its name, by the key rule
         * @param label what the page writes next to it
         * @param regex a regular expression, {@link Pattern} syntax; keep it as narrow as the value it takes
         * @return the argument
         */
        public static Argument matching(String name, String label, String regex) {
            return new Argument(name, label, null, Objects.requireNonNull(regex, "regex"), null);
        }

        /**
         * An argument whose value is a JSON object, such as the arguments of an MCP tool. The action receives it as
         * its JSON text. One action has one such argument at most.
         *
         * @param name   its name, by the key rule
         * @param label  what the page writes next to it
         * @param schema a JSON Schema describing the object, as the text of a JSON object
         * @return the argument
         * @throws IllegalArgumentException when {@code schema} is not a JSON object of at most
         *                                  {@value #MAX_SCHEMA} characters
         */
        public static Argument json(String name, String label, String schema) {
            return new Argument(name, label, null, null, Objects.requireNonNull(schema, "schema"));
        }

        /**
         * Whether the console passes {@code value} to the action: not {@code null}, and for a {@link #json json}
         * argument one JSON object, whatever its length; for a string argument at most {@value #MAX_VALUE_LENGTH}
         * characters, and one of the allowed values or a whole match of the pattern.
         *
         * @param value the value a request carries
         * @return {@code true} when it is accepted
         */
        public boolean accepts(String value) {
            if (value == null) {
                return false;
            }
            if (schema != null) {
                return JsonCheck.isObject(value, MAX_JSON_DEPTH);
            }
            if (value.length() > MAX_VALUE_LENGTH) {
                return false;
            }
            return allowedValues != null ? allowedValues.contains(value) : Pattern.matches(pattern, value);
        }
    }
```

- In the class Javadoc, section **What it receives**, append: `A {@link Argument#json json} argument is the exception: its value is the text of a JSON object, of any length the console's body limit lets through, described by a JSON Schema.`

- [ ] **Step 4: Run them to see them pass**

Run: `mvn -o -q -pl $SPI test`
Expected: `BUILD SUCCESS`; `PanelActionTest` (old and new tests) and `JsonCheckTest` pass.

- [ ] **Step 5: Commit** — `feat(devconsole-spi): a json action argument with its JSON Schema`.
  Files: `SPI_SRC/JsonCheck.java`, `SPI_SRC/PanelAction.java`, `SPI_TEST/JsonCheckTest.java`,
  `SPI_TEST/PanelActionTest.java`.

---

### Task 2: The SPI's structured result, groups and descriptions

**Files:**
- Modify: `SPI_SRC/PanelAction.java`, `SPI_SRC/PanelSample.java`
- Create: `SPI_TEST/ActionResultTest.java`
- Test: `SPI_TEST/PanelActionTest.java` (added tests)

**Interfaces:**
- Consumes: `PanelAction.Argument` of Task 1.
- Produces:
  - `PanelAction` components `(String id, String label, String confirmation, List<Argument> arguments,
    Function<Map<String, String>, ActionResult> call, String group, String description)`, the canonical constructor
    being the new one;
  - kept: `PanelAction(String, String, String, Function<Map<String, String>, String> run)` and
    `PanelAction(String, String, String, List<Argument>, Function<Map<String, String>, String> run)`;
  - `Function<Map<String, String>, String> run()`, a method returning the summary of `call`;
  - constants `PanelAction.MAX_GROUP = 40`, `PanelAction.MAX_DESCRIPTION = 2000`;
  - `public record PanelAction.ActionResult(String summary, String contentType, String body, boolean error,
    String details)` with `MAX_SUMMARY = 200`, `MAX_CONTENT = 256 * 1024`, `TEXT = "text/plain"`,
    `JSON = "application/json"`, `TRUNCATED = "\n… truncated at 256 KiB"`, and `static ActionResult of(String)`;
  - `PanelSample.REPLAY_COLUMN = "replay"`, `PanelSample.MAX_REPLAY_CELL = 4096`.

- [ ] **Step 1: Write the failing tests**

`SPI_TEST/ActionResultTest.java`:

```java
package io.vidocq.runtime.spi.devconsole;

import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What an action returns: a summary line, and optionally a body, an error flag and the details of the exchange. */
class ActionResultTest {

    @Test
    void aNullSummaryReadsDoneAndALongOneIsCut() {
        assertEquals("done", ActionResult.of(null).summary());
        String cut = ActionResult.of("x".repeat(250)).summary();
        assertEquals(200, cut.length());
        assertTrue(cut.endsWith("..."), cut);
        assertEquals("y".repeat(200), ActionResult.of("y".repeat(200)).summary());
    }

    @Test
    void aBodyIsTextOrJsonAndDefaultsToText() {
        assertEquals("text/plain", new ActionResult("ok", null, "hello", false, null).contentType());
        assertEquals("application/json", new ActionResult("ok", "application/json", "{}", false, null).contentType());
        assertNull(new ActionResult("ok", "application/json", null, false, null).contentType(), "no body, no type");
        assertThrows(IllegalArgumentException.class, () -> new ActionResult("ok", "text/html", "<b>", false, null));
    }

    @Test
    void aBodyOrDetailsPastTheLimitIsTruncatedAndSaysSo() {
        String big = "a".repeat(300_000);

        ActionResult result = new ActionResult("ok", "text/plain", big, false, big);

        assertEquals(ActionResult.MAX_CONTENT, result.body().length());
        assertTrue(result.body().endsWith("… truncated at 256 KiB"), result.body().substring(262_100));
        assertEquals(ActionResult.MAX_CONTENT, result.details().length());
        String exact = "b".repeat(ActionResult.MAX_CONTENT);
        assertEquals(exact, new ActionResult("ok", "text/plain", exact, false, null).body());
    }

    @Test
    void theErrorFlagIsKept() {
        assertTrue(new ActionResult("error -32602: bad", "application/json", "{}", true, null).error());
        assertFalse(ActionResult.of("ok").error());
        assertNull(ActionResult.of("ok").body());
        assertNull(ActionResult.of("ok").details());
    }
}
```

Add to `SPI_TEST/PanelActionTest.java` (and `import java.util.function.Function;`):

```java
    @Test
    void anOldStyleActionStillReturnsItsLineAndIsCalledThroughItsResult() {
        PanelAction clear = new PanelAction("clear", "Clear the cache", null, arguments -> "0 entries");

        assertEquals("0 entries", clear.run().apply(Map.of()));
        assertEquals(PanelAction.ActionResult.of("0 entries"), clear.call().apply(Map.of()));
        assertNull(clear.group());
        assertNull(clear.description());
        assertEquals("done", new PanelAction("noop", "Noop", null, a -> null).call().apply(Map.of()).summary());
    }

    @Test
    void aStructuredActionReturnsAResultAndMayHaveAGroupAndADescription() {
        PanelAction weather = new PanelAction("tool.weather", "Weather", null, List.of(), arguments ->
                new PanelAction.ActionResult("ok in 3 ms", "application/json", "{\"t\":21}", false, "{}"),
                "Tools", "The weather in a city.\nIn Celsius.");

        assertEquals("Tools", weather.group());
        assertEquals("The weather in a city.\nIn Celsius.", weather.description());
        assertEquals("ok in 3 ms", weather.run().apply(Map.of()), "run() still gives the line");
        assertEquals("{\"t\":21}", weather.call().apply(Map.of()).body());
    }

    @Test
    void aGroupAndADescriptionAreBounded() {
        Function<Map<String, String>, PanelAction.ActionResult> ok = a -> PanelAction.ActionResult.of("ok");

        assertThrows(IllegalArgumentException.class, () -> new PanelAction("a", "A", null, List.of(), ok, " ", null));
        assertThrows(IllegalArgumentException.class,
                () -> new PanelAction("a", "A", null, List.of(), ok, "g".repeat(41), null));
        assertThrows(IllegalArgumentException.class, () -> new PanelAction("a", "A", null, List.of(), ok, null, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new PanelAction("a", "A", null, List.of(), ok, null, "d".repeat(2001)));
        PanelAction longest = new PanelAction("a", "A", null, List.of(), ok, "g".repeat(40), "d".repeat(2000));
        assertEquals(40, longest.group().length());
        assertEquals(2000, longest.description().length());
        assertThrows(NullPointerException.class, () -> new PanelAction("a", "A", null, List.of(), null, null, null));
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -o -q -pl $SPI test -Dtest='ActionResultTest,PanelActionTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAILURE, `cannot find symbol: class ActionResult` and `method call()`.

- [ ] **Step 3: Write the implementation**

In `SPI_SRC/PanelAction.java`:

- Record header and constants:

```java
public record PanelAction(String id, String label, String confirmation, List<Argument> arguments,
                          Function<Map<String, String>, ActionResult> call, String group, String description) {

    /** The longest value a string argument accepts. */
    public static final int MAX_VALUE_LENGTH = 200;
    /** The longest group title. */
    public static final int MAX_GROUP = 40;
    /** The longest description. */
    public static final int MAX_DESCRIPTION = 2000;
```

- In the canonical constructor, replace `Objects.requireNonNull(run, "run");` with `Objects.requireNonNull(call,
  "call");`, and add at its end:

```java
        if (group != null && (group.isBlank() || group.length() > MAX_GROUP)) {
            throw new IllegalArgumentException("action '" + id + "' has a blank group or one longer than "
                    + MAX_GROUP + " characters: null for none");
        }
        if (description != null && (description.isBlank() || description.length() > MAX_DESCRIPTION)) {
            throw new IllegalArgumentException("action '" + id + "' has a blank description or one longer than "
                    + MAX_DESCRIPTION + " characters: null for none");
        }
```

- Replace the 4-argument constructor with the two kept constructors, `run()` and the wrapper:

```java
    /**
     * An action that returns one line, as before {@link ActionResult} existed: {@code run} is called through
     * {@link #call}, its line becoming the result's summary.
     *
     * @param id           its id, by the key rule
     * @param label        its button
     * @param confirmation the question asked before sending, or {@code null}
     * @param arguments    what it takes
     * @param run          what it does, returning one short line
     */
    public PanelAction(String id, String label, String confirmation, List<Argument> arguments,
                       Function<Map<String, String>, String> run) {
        this(id, label, confirmation, arguments, summaryOnly(run), null, null);
    }

    /**
     * An action that takes no argument and returns one line.
     *
     * @param id           its id, by the key rule
     * @param label        its button
     * @param confirmation the question asked before sending, or {@code null}
     * @param run          what it does, called with an empty map
     */
    public PanelAction(String id, String label, String confirmation, Function<Map<String, String>, String> run) {
        this(id, label, confirmation, List.of(), run);
    }

    /**
     * What the action does, as one line: the summary of {@link #call}, {@code done} when it returns {@code null}.
     *
     * @return a function of the checked arguments to that line
     */
    public Function<Map<String, String>, String> run() {
        return arguments -> {
            ActionResult result = call.apply(arguments);
            return result == null ? ActionResult.of(null).summary() : result.summary();
        };
    }

    private static Function<Map<String, String>, ActionResult> summaryOnly(Function<Map<String, String>, String> run) {
        Objects.requireNonNull(run, "run");
        return arguments -> ActionResult.of(run.apply(arguments));
    }
```

- Add the nested record after `Argument`:

```java
    /**
     * What an action returns: the line the page shows and the console logs, and optionally what to show under it.
     *
     * @param summary     one line, at most {@value #MAX_SUMMARY} characters, a longer one cut with {@code ...};
     *                    {@code null} reads as {@code done}
     * @param contentType {@value #TEXT} or {@value #JSON}, which the page pretty-prints; {@code null} when there is
     *                    no body, {@value #TEXT} when there is one and none was given
     * @param body        what to show, or {@code null}; at most {@value #MAX_CONTENT} characters, a longer one
     *                    truncated and ending with the marker {@code … truncated at 256 KiB}
     * @param error       {@code true} when the call went through but its outcome is an error of its target, such as
     *                    a tool returning {@code isError}; an exception thrown by the action is not a result
     * @param details     JSON the page shows folded under "Exchange", such as a request and its response, or
     *                    {@code null}; truncated as {@code body}
     */
    public record ActionResult(String summary, String contentType, String body, boolean error, String details) {

        /** The longest summary, in characters. */
        public static final int MAX_SUMMARY = 200;
        /** The longest body or details, in characters. */
        public static final int MAX_CONTENT = 256 * 1024;
        /** A body shown as it is. */
        public static final String TEXT = "text/plain";
        /** A body the page pretty-prints. */
        public static final String JSON = "application/json";
        /** What ends a body or details that was truncated. */
        public static final String TRUNCATED = "\n… truncated at 256 KiB";

        public ActionResult {
            summary = summary == null ? "done" : cut(summary);
            if (contentType != null && !TEXT.equals(contentType) && !JSON.equals(contentType)) {
                throw new IllegalArgumentException("a result's content type is " + TEXT + " or " + JSON);
            }
            if (body == null) {
                contentType = null;
            } else if (contentType == null) {
                contentType = TEXT;
            }
            body = truncated(body);
            details = truncated(details);
        }

        /**
         * A result that is one line, and nothing else.
         *
         * @param summary the line, or {@code null} for {@code done}
         * @return the result
         */
        public static ActionResult of(String summary) {
            return new ActionResult(summary, null, null, false, null);
        }

        private static String cut(String summary) {
            if (summary.length() <= MAX_SUMMARY) {
                return summary;
            }
            int end = MAX_SUMMARY - 3;
            if (Character.isHighSurrogate(summary.charAt(end - 1))) {
                end--;
            }
            return summary.substring(0, end) + "...";
        }

        private static String truncated(String text) {
            if (text == null || text.length() <= MAX_CONTENT) {
                return text;
            }
            int end = MAX_CONTENT - TRUNCATED.length();
            if (Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            return text.substring(0, end) + TRUNCATED;
        }
    }
```

- In the class Javadoc, section **What it returns**, replace the first sentence with: `One short line of text, such as {@code 3 migrations applied}, that the page shows and the console logs, or an {@link ActionResult} that adds a body, an error flag and the details of the exchange, which the page shows under the line; the line never carries a secret.` Document `call`, `group` (`a short title such as {@code Tools}: the page folds the actions of a panel by group, in order of first appearance, and adds a filter past ten actions; {@code null} for none`) and `description` (`a longer text shown under the label, such as a tool's description, line breaks kept; {@code null} for none`) in the `@param` list, and replace `@param run` with `@param call does the work with the checked arguments, by name, and returns its result`.

In `SPI_SRC/PanelSample.java`, add after the interface's opening brace:

```java
    /**
     * The header of a {@link #table table} column the page turns into a "Replay" button: each cell is the id of an
     * action of the same panel, a space, then a JSON object of its arguments by name, such as
     * {@code tool.weather {"arguments":{"city":"Paris"}}}. The button fills that action's form with them and sends
     * nothing; a value {@code "***"} is left empty for the user to type. The console keeps such a cell whole up to
     * {@value #MAX_REPLAY_CELL} characters, and empties a longer one.
     */
    String REPLAY_COLUMN = "replay";

    /** The longest cell of a {@value #REPLAY_COLUMN} column the console keeps. */
    int MAX_REPLAY_CELL = 4096;
```

- [ ] **Step 4: Run them to see them pass, and install the SPI**

Run: `mvn -o -q -pl $SPI install`
Expected: `BUILD SUCCESS`, every SPI test passing.

Then check that nothing that uses `PanelAction` broke:
`mvn -o -q -pl $CONSOLE,vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-migration-extension-dev test`
Expected: `BUILD SUCCESS` (the Logs, Tests and Migration panels change nothing).

- [ ] **Step 5: Commit** — `feat(devconsole-spi): a structured action result, groups and descriptions`.
  Files: `SPI_SRC/PanelAction.java`, `SPI_SRC/PanelSample.java`, `SPI_TEST/ActionResultTest.java`,
  `SPI_TEST/PanelActionTest.java`.

---

### Task 3: The console accepts, answers and shows the new actions

**Files:**
- Modify: `CONSOLE_SRC/ConsoleActions.java` (class Javadoc, `MAX_JSON_BODY`, `Outcome`, `run`, `execute`,
  `arguments`, new `answer` and `hasJson`), `CONSOLE_SRC/Snapshot.java` (class Javadoc items on actions,
  `writeActions`, new `writeSchema`), `CONSOLE_SRC/PanelEntry.java` (`MAX_ACTIONS`), `CONSOLE_SRC/Texts.java`
  (`clean(String, int)`, `block`), `CONSOLE_SRC/RecordingSample.java` (`table`, new `replayCell`)
- Create: `CONSOLE_TEST/ConsoleActionResultsTest.java`
- Modify (tests): `CONSOLE_TEST/TestPanels.java` (two panels), `CONSOLE_TEST/RecordingSampleTest.java` (one test)

**Interfaces:**
- Consumes: `PanelAction.call()`, `group()`, `description()`, `Argument.schema()`, `Argument.MAX_JSON_DEPTH`,
  `ActionResult`, `PanelSample.REPLAY_COLUMN`, `PanelSample.MAX_REPLAY_CELL` (Tasks 1-2).
- Produces (what the page of Task 4 reads):
  - the answer `200 {"result": s, "error": true, "contentType": t, "body": b, "details": d}`, each of the last four
    only when set;
  - in the snapshot, per action: `group` and `description` when set; per argument, `schema` (a JSON object) for a
    `json` argument; `last.error: true` for an error result;
  - `ConsoleActions.MAX_JSON_BODY = 64 * 1024`; `PanelEntry.MAX_ACTIONS = 128`;
  - `Texts.clean(String value, int max)`, `Texts.block(String value, int max)`.

- [ ] **Step 1: Write the failing tests**

Add to `CONSOLE_TEST/TestPanels.java` (and `import java.util.stream.IntStream;`):

```java
    /** A panel whose actions take a json argument and return structured results, as the MCP inspector's do. */
    static final class InspectorPanel implements DevConsolePanel {

        static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},"
                + "\"required\":[\"city\"]}";

        final List<Map<String, String>> runs = new CopyOnWriteArrayList<>();

        @Override
        public String id() {
            return "acme-inspect";
        }

        @Override
        public String title() {
            return "Acme inspector";
        }

        @Override
        public void contribute(StartupReportContext context, StartupReportSection section) {
            section.summary("things to call");
        }

        @Override
        public void sample(PanelSample sample) {
            sample.counter("runs", runs.size(), Unit.COUNT);
        }

        @Override
        public List<PanelAction> actions() {
            return List.of(
                    new PanelAction("tool.weather", "Weather", null,
                            List.of(PanelAction.Argument.json("arguments", "Arguments", SCHEMA)), arguments -> {
                                runs.add(arguments);
                                return new PanelAction.ActionResult("ok in 3 ms", "application/json",
                                        "{\"temp\":21}", false, "{\"request\":{\"method\":\"tools/call\"}}");
                            }, "Tools", "The weather in a city.\nIn Celsius."),
                    new PanelAction("tool.broken", "Broken", null,
                            List.of(PanelAction.Argument.json("arguments", "Arguments", "{\"type\":\"object\"}")),
                            arguments -> {
                                runs.add(arguments);
                                return new PanelAction.ActionResult("error -32602: Invalid params",
                                        "application/json", "{\"code\":-32602}", true, null);
                            }, "Tools", null),
                    new PanelAction("note", "Note", null,
                            List.of(PanelAction.Argument.matching("text", "Text", "[a-z]{1,200}")), arguments -> {
                                runs.add(arguments);
                                return "noted";
                            }));
        }
    }

    /** A panel offering more actions than the console keeps. */
    static final class ManyActionsPanel implements DevConsolePanel {

        @Override
        public String id() {
            return "acme-many";
        }

        @Override
        public void contribute(StartupReportContext context, StartupReportSection section) {
            section.summary("many things to do");
        }

        @Override
        public void sample(PanelSample sample) {
            // no value
        }

        @Override
        public List<PanelAction> actions() {
            return IntStream.range(0, 130)
                    .mapToObj(i -> new PanelAction("tool.t" + i, "Tool " + i, null, arguments -> "ok"))
                    .toList();
        }
    }
```

`CONSOLE_TEST/ConsoleActionResultsTest.java`:

```java
package io.vidocq.runtime.extensions.essentials.devconsole;

import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.runtime.spi.devconsole.DevConsolePanel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Amendment 1 of ADR 0001 on the console's side: an action with a json argument, the larger body it may send, the
 * structured result it answers, and what the snapshot says of it. The checks of ADR 0001 themselves are
 * {@link ConsoleActionsTest}'s.
 */
class ConsoleActionResultsTest {

    private static final String HOST = "127.0.0.1:18095";
    private static final String ORIGIN = "http://127.0.0.1:18095";
    private static final String TOKEN = "5a".repeat(32);
    private static final long NOW = 1_789_740_602_114L;

    private final TestPanels.InspectorPanel panel = new TestPanels.InspectorPanel();
    private final Handler page = request -> Response.builder().status(StatusCode.OK).body("<!doctype html>").build();
    private LogRecords log;
    private Snapshot snapshot;
    private ConsoleHandler handler;

    @BeforeEach
    void aDevBoot() {
        log = new LogRecords(DevConsoleExtension.LOGGER_NAME);
        boot(panel);
    }

    @AfterEach
    void release() {
        log.close();
    }

    private void boot(DevConsolePanel shown) {
        ConsoleActions actions = new ConsoleActions(TOKEN, () -> NOW, Duration.ofSeconds(5));
        FakeReportView report = FakeReportView.of(shown);
        snapshot = new Snapshot("7f3a91c04be2d811", "0.4.0-TEST", () -> Optional.of(report), List.of(), () -> NOW,
                actions);
        handler = new ConsoleHandler(new HostGuard("127.0.0.1"), () -> 18095, snapshot, page);
    }

    private Response post(String action, String body) throws Exception {
        return handler.handle(FakeRequest.post("/api/action/acme-inspect/" + action, HOST, Map.of(
                "Content-Type", "application/json", "Origin", ORIGIN, ConsoleActions.TOKEN_HEADER, TOKEN), body));
    }

    private static String body(Response response) throws IOException {
        try (InputStream in = response.body().asInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** A request body of exactly {@code size} bytes for tool.weather: a city of 'a's. */
    private static String weatherBody(int size) {
        String head = "{\"arguments\":\"{\\\"city\\\":\\\"";
        String tail = "\\\"}\"}";
        return head + "a".repeat(size - head.length() - tail.length()) + tail;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> actionsOf(String panelId) {
        List<Map<String, Object>> panels = (List<Map<String, Object>>) Json.object(snapshot.document()).get("panels");
        Map<String, Object> ours = panels.stream().filter(p -> panelId.equals(p.get("id"))).findFirst()
                .orElseThrow();
        return (List<Map<String, Object>>) ours.get("actions");
    }

    private Map<String, Object> actionOf(String id) {
        return actionsOf("acme-inspect").stream().filter(a -> id.equals(a.get("id"))).findFirst().orElseThrow();
    }

    @Test
    void aStructuredResultAnswersEveryFieldItHasAndIsLoggedByItsSummary() throws Exception {
        Response response = post("tool.weather", "{\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\"}");

        assertEquals(200, response.status().code(), body(response));
        assertEquals("{\"result\":\"ok in 3 ms\",\"contentType\":\"application/json\",\"body\":\"{\\\"temp\\\":21}\","
                + "\"details\":\"{\\\"request\\\":{\\\"method\\\":\\\"tools/call\\\"}}\"}", body(response));
        assertEquals(List.of(Map.of("arguments", "{\"city\":\"Paris\"}")), panel.runs,
                "the json value reaches the action as its text");
        assertTrue(log.messages(Level.INFO).contains(
                "Vidocq dev console: action acme-inspect/tool.weather by 127.0.0.1: ok in 3 ms"),
                log.messages().toString());
    }

    @Test
    void anErrorOfTheTargetIsFlaggedAndStaysA200() throws Exception {
        Response response = post("tool.broken", "{\"arguments\":\"{}\"}");

        assertEquals(200, response.status().code(), body(response));
        assertEquals("{\"result\":\"error -32602: Invalid params\",\"error\":true,\"contentType\":\"application/json\","
                + "\"body\":\"{\\\"code\\\":-32602}\"}", body(response));
    }

    @Test
    void aOneLineActionStillAnswersItsResultOnly() throws Exception {
        Response response = post("note", "{\"text\":\"hello\"}");

        assertEquals(200, response.status().code(), body(response));
        assertEquals("{\"result\":\"noted\"}", body(response));
    }

    @Test
    void aJsonValueThatDoesNotParseOrIsNoObjectIsRefusedBeforeTheAction() throws Exception {
        Response truncated = post("tool.weather", "{\"arguments\":\"{\\\"city\\\":\"}");
        Response array = post("tool.weather", "{\"arguments\":\"[\\\"Paris\\\"]\"}");

        assertEquals(400, truncated.status().code());
        assertEquals("Argument arguments is not a JSON object.", body(truncated));
        assertEquals(400, array.status().code());
        assertEquals(List.of(), panel.runs);
    }

    @Test
    void aBodyWithAJsonArgumentMayReachSixtyFourKibAndAnyOtherFour() throws Exception {
        assertEquals(200, post("tool.weather", weatherBody(ConsoleActions.MAX_JSON_BODY)).status().code());
        assertEquals(413, post("tool.weather", weatherBody(ConsoleActions.MAX_JSON_BODY + 1)).status().code());
        assertEquals(413, post("note", "{\"text\":\"" + "a".repeat(ConsoleActions.MAX_BODY) + "\"}").status().code());
        assertEquals(1, panel.runs.size(), "only the body within the limit reached the action");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSnapshotCarriesTheGroupTheDescriptionAndTheSchemaAsAnObject() {
        Map<String, Object> weather = actionOf("tool.weather");
        assertEquals("Tools", weather.get("group"));
        assertEquals("The weather in a city.\nIn Celsius.", weather.get("description"), "line breaks kept");
        Map<String, Object> argument = (Map<String, Object>) ((List<?>) weather.get("arguments")).get(0);
        assertEquals("arguments", argument.get("name"));
        assertNull(argument.get("allowed"));
        assertEquals(Json.object(TestPanels.InspectorPanel.SCHEMA), argument.get("schema"));

        Map<String, Object> note = actionOf("note");
        assertFalse(note.containsKey("group"));
        assertFalse(note.containsKey("description"));
        assertFalse(((Map<?, ?>) ((List<?>) note.get("arguments")).get(0)).containsKey("schema"));
    }

    @Test
    void theLastOutcomeOfAnErrorResultSaysSo() throws Exception {
        post("tool.broken", "{\"arguments\":\"{}\"}");
        post("tool.weather", "{\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\"}");

        assertEquals(Map.of("text", "error -32602: Invalid params", "time", NOW, "ok", true, "error", true),
                actionOf("tool.broken").get("last"));
        assertEquals(Map.of("text", "ok in 3 ms", "time", NOW, "ok", true), actionOf("tool.weather").get("last"));
    }

    @Test
    void aPanelMayOfferUpToOneHundredAndTwentyEightActions() {
        boot(new TestPanels.ManyActionsPanel());

        assertEquals(128, PanelEntry.MAX_ACTIONS);
        assertEquals(PanelEntry.MAX_ACTIONS, actionsOf("acme-many").size());
    }
}
```

Add to `CONSOLE_TEST/RecordingSampleTest.java`:

```java
    @Test
    @SuppressWarnings("unchecked")
    void aReplayCellIsKeptWholeUpToItsLimitAndEmptiedPastIt() {
        RecordingSample sample = new RecordingSample();
        String replay = "tool.weather {\"arguments\":{\"city\":\"" + "a".repeat(300) + "\"}}";
        String tooLong = "tool.weather {\"arguments\":{\"city\":\"" + "a".repeat(PanelSample.MAX_REPLAY_CELL) + "\"}}";

        sample.table("calls", List.of("action", PanelSample.REPLAY_COLUMN),
                List.of(List.of("a".repeat(300), replay), List.of("b", tooLong)));

        List<List<String>> rows = (List<List<String>>) values(written(sample)).get(0).get("rows");
        assertEquals(200, rows.get(0).get(0).length(), "another column is cut as always");
        assertEquals(replay, rows.get(0).get(1));
        assertEquals("", rows.get(1).get(1), "a replay cell past its limit is emptied, never cut");
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -o -q -pl $CONSOLE test -Dtest='ConsoleActionResultsTest,RecordingSampleTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAILURE, `cannot find symbol: variable MAX_JSON_BODY`.

- [ ] **Step 3: Write the implementation**

`CONSOLE_SRC/Texts.java` — replace `clean(String)` with:

```java
    /**
     * {@code value} safe to show on one line: every control character (line breaks, {@code ESC}, C1 controls),
     * every invisible formatting character (bidirectional overrides) and the Unicode line and paragraph separators
     * replaced by {@code ?}, then cut to {@value #MAX_VALUE} characters, the last three being {@code ...}, as the
     * report cleans its own values.
     *
     * @param value the text, or {@code null}
     * @return the clean text, or {@code null} for {@code null}
     */
    static String clean(String value) {
        return clean(value, MAX_VALUE, false);
    }

    /**
     * {@code value} cleaned as {@link #clean(String)} does, cut to {@code max} characters instead.
     *
     * @param value the text, or {@code null}
     * @param max   the most characters kept, the last three being {@code ...} when it is cut
     * @return the clean text, or {@code null} for {@code null}
     */
    static String clean(String value, int max) {
        return clean(value, max, false);
    }

    /**
     * {@code value} safe to show as a block of lines, such as an action's description: cleaned as
     * {@link #clean(String, int)}, but a line feed and a tab are kept and a carriage return is dropped.
     *
     * @param value the text, or {@code null}
     * @param max   the most characters kept
     * @return the clean text, or {@code null} for {@code null}
     */
    static String block(String value, int max) {
        return clean(value, max, true);
    }

    private static String clean(String value, int max, boolean lines) {
        if (value == null) {
            return null;
        }
        StringBuilder cleaned = new StringBuilder(Math.min(value.length(), max + 1));
        for (int i = 0; i < value.length() && cleaned.length() <= max; ) {
            int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);
            if (lines && codePoint == '\r') {
                continue;
            }
            if (lines && (codePoint == '\n' || codePoint == '\t')) {
                cleaned.appendCodePoint(codePoint);
            } else if (unsafe(codePoint)) {
                cleaned.append(REPLACEMENT);
            } else {
                cleaned.appendCodePoint(codePoint);
            }
        }
        if (cleaned.length() <= max) {
            return cleaned.toString();
        }
        int end = max - CUT.length();
        if (Character.isLowSurrogate(cleaned.charAt(end))) {
            end--;
        }
        return cleaned.substring(0, end) + CUT;
    }
```

`CONSOLE_SRC/RecordingSample.java` — in `Scope.table`, replace the inner loop and add the helper (with
`import io.vidocq.runtime.spi.devconsole.PanelSample;` if missing):

```java
            int replay = heads.indexOf(PanelSample.REPLAY_COLUMN);
            List<List<String>> table = new ArrayList<>();
            for (List<String> row : rows.subList(0, Math.min(MAX_ROWS, rows.size()))) {
                List<String> cells = new ArrayList<>(heads.size());
                for (int i = 0; i < heads.size(); i++) {
                    String raw = row != null && i < row.size() ? row.get(i) : null;
                    String cell = i == replay ? replayCell(raw) : Texts.clean(raw);
                    cells.add(cell == null ? "" : cell);
                }
                table.add(List.copyOf(cells));
            }
```

```java
    /**
     * A cell of the {@value PanelSample#REPLAY_COLUMN} column: kept whole up to
     * {@value PanelSample#MAX_REPLAY_CELL} characters, since a cut would break its JSON, and emptied past it.
     */
    private static String replayCell(String raw) {
        return raw == null || raw.length() > PanelSample.MAX_REPLAY_CELL
                ? null : Texts.clean(raw, PanelSample.MAX_REPLAY_CELL);
    }
```

`CONSOLE_SRC/PanelEntry.java`: `static final int MAX_ACTIONS = 128;` (Javadoc unchanged: it cites the value).

`CONSOLE_SRC/ConsoleActions.java`:

- Constant, after `MAX_BODY`:

```java
    /** The largest body of an action request that has a json argument, in bytes (ADR 0001, amendment 1). */
    static final int MAX_JSON_BODY = 64 * 1024;
```

- `Outcome`:

```java
    /**
     * How an action ended.
     *
     * @param text   the summary it returned, cleaned, or the simple name of the class of what it threw
     * @param time   when it ended, by the server's clock, in epoch milliseconds
     * @param ok     {@code false} when it threw
     * @param result what it returned, {@code null} when it threw
     */
    record Outcome(String text, long time, boolean ok, PanelAction.ActionResult result) {

        /** Whether the call went through with an outcome that is an error of its target. */
        boolean error() {
            return result != null && result.error();
        }
    }
```

- In `run`, replace the body read:

```java
        int max = hasJson(action) ? MAX_JSON_BODY : MAX_BODY;
        String body;
        try {
            body = body(request, max);
        } catch (TooLarge tooLarge) {
            return text(StatusCode.PAYLOAD_TOO_LARGE, "The body is larger than " + max + " bytes.");
        } catch (IOException | RuntimeException unreadable) {
            return text(StatusCode.BAD_REQUEST, "The body is not UTF-8 text.");
        }
```

  and the answer of an action that returned:

```java
            return outcome.ok()
                    ? json(StatusCode.OK, answer(outcome))
                    : json(StatusCode.INTERNAL_SERVER_ERROR, new JsonWriter().beginObject().name("error")
                            .value(outcome.text()).endObject().toString());
```

- In `execute`, replace the `try` block's first lines and the failure's outcome:

```java
            PanelAction.ActionResult result = action.call().apply(arguments);
            if (result == null) {
                result = PanelAction.ActionResult.of(null);
            }
            outcome = new Outcome(Texts.clean(result.summary()), clock.getAsLong(), true, result);
            LOG.log(System.Logger.Level.INFO, "Vidocq dev console: action " + name + " by " + by + ": "
                    + outcome.text());
        } catch (Throwable failure) {
            outcome = new Outcome(Snapshot.className(failure), clock.getAsLong(), false, null);
```

- In `arguments`, replace the refusal of a value:

```java
            if (!argument.accepts(value)) {
                throw new IllegalArgumentException("Argument " + argument.name() + (argument.schema() != null
                        ? " is not a JSON object." : " has a value it does not accept."));
            }
```

- New helpers:

```java
    /**
     * The answer of an action that returned: {@code result}, its summary, then {@code error}, {@code contentType},
     * {@code body} and {@code details}, each only when set, so that an action returning one line answers
     * {@code {"result": "..."}} as it always did.
     */
    static String answer(Outcome outcome) {
        JsonWriter out = new JsonWriter().beginObject().name("result").value(outcome.text());
        PanelAction.ActionResult result = outcome.result();
        if (result.error()) {
            out.name("error").value(true);
        }
        if (result.body() != null) {
            out.name("contentType").value(result.contentType()).name("body").value(result.body());
        }
        if (result.details() != null) {
            out.name("details").value(result.details());
        }
        return out.endObject().toString();
    }

    /** Whether {@code action} takes a json argument, which allows it a body of {@value #MAX_JSON_BODY} bytes. */
    private static boolean hasJson(PanelAction action) {
        return action.arguments().stream().anyMatch(argument -> argument.schema() != null);
    }
```

- Class Javadoc: list item 2 becomes `the body is at most {@value #MAX_BODY} bytes, {@value #MAX_JSON_BODY} for an action with a json argument, checked before it is read, else {@code 413};`; list item 3 gains `, a json argument's value one JSON object`; and the answer sentence becomes `{@code 200 {"result": "<summary>"}} when it returned, with {@code error}, {@code contentType}, {@code body} and {@code details} when its {@link PanelAction.ActionResult} has them (ADR 0001, amendment 1)`.

`CONSOLE_SRC/Snapshot.java`:

- Replace `writeActions` with:

```java
    /**
     * The member {@code actions} of a panel of a dev boot: what each action is, its group and description when it
     * has them, each argument with its schema when it is a json one, whether it is running, and how it last ended
     * this boot, {@code null} before its first run.
     */
    private void writeActions(JsonWriter out, PanelEntry panel) {
        out.name("actions").beginArray();
        for (PanelAction action : panel.actions()) {
            out.beginObject()
                    .name("id").value(action.id())
                    .name("label").value(Texts.clean(action.label()))
                    .name("confirmation").value(Texts.clean(action.confirmation()));
            if (action.group() != null) {
                out.name("group").value(Texts.clean(action.group()));
            }
            if (action.description() != null) {
                out.name("description").value(Texts.block(action.description(), PanelAction.MAX_DESCRIPTION));
            }
            out.name("arguments").beginArray();
            for (PanelAction.Argument argument : action.arguments()) {
                out.beginObject().name("name").value(argument.name())
                        .name("label").value(Texts.clean(argument.label()))
                        .name("allowed");
                if (argument.allowedValues() == null) {
                    out.nullValue();
                } else {
                    out.beginArray();
                    for (String value : argument.allowedValues()) {
                        out.value(Texts.clean(value));
                    }
                    out.endArray();
                }
                if (argument.schema() != null) {
                    out.name("schema");
                    writeSchema(out, argument.schema());
                }
                out.endObject();
            }
            out.endArray();
            out.name("running").value(actions.running(panel.id(), action.id()));
            out.name("last");
            ConsoleActions.Outcome last = actions.outcome(panel.id(), action.id());
            if (last == null) {
                out.nullValue();
            } else {
                out.beginObject().name("text").value(last.text()).name("time").value(last.time())
                        .name("ok").value(last.ok());
                if (last.error()) {
                    out.name("error").value(true);
                }
                out.endObject();
            }
            out.endObject();
        }
        out.endArray();
    }

    /**
     * A json argument's schema, as the JSON object it is, so that the page reads it without parsing a string. The
     * SPI already checked that it parses; one that does not, which cannot happen, is written {@code null}.
     */
    private static void writeSchema(JsonWriter out, String schema) {
        Object parsed;
        try {
            parsed = JsonValues.parse(schema, PanelAction.Argument.MAX_JSON_DEPTH);
        } catch (IllegalArgumentException unreadable) {
            out.nullValue();
            return;
        }
        JsonValues.write(out, parsed);
    }
```

- Class Javadoc, the item on actions: `{@code actions}: {@code [{"id", "label", "confirmation", "group", "description", "arguments": [{"name", "label", "allowed", "schema"}], "running", "last": {"text", "time", "ok", "error"}}]}`, with `group`, `description`, `schema` and `error` written only when set, `schema` being the JSON object of a json argument.

- [ ] **Step 4: Run them to see them pass**

Run: `mvn -o -q -pl $CONSOLE test`
Expected: `BUILD SUCCESS`; `ConsoleActionResultsTest`, `RecordingSampleTest` and the whole existing suite
(`ConsoleActionsTest` included: `{"result":"0 entries"}` and its `last` map unchanged) pass.

- [ ] **Step 5: Commit** — `feat(devconsole): json arguments, structured results and groups in the console (ADR 0001 amendment 1)`.
  Files: `CONSOLE_SRC/ConsoleActions.java`, `CONSOLE_SRC/Snapshot.java`, `CONSOLE_SRC/PanelEntry.java`,
  `CONSOLE_SRC/Texts.java`, `CONSOLE_SRC/RecordingSample.java`, `CONSOLE_TEST/ConsoleActionResultsTest.java`,
  `CONSOLE_TEST/TestPanels.java`, `CONSOLE_TEST/RecordingSampleTest.java`.

---

### Task 4: The page draws forms, results, groups and replays

**Files:**
- Modify: `CONSOLE_RES/console.js` (header comment; the `actions` section: `actionsBar`, `actionRow`, new helpers;
  `sampleTable`; `scopeView`'s call to it), `CONSOLE_RES/console.css` (the actions block)
- Test: `CONSOLE_TEST/PageTest.java` (one test)

**Interfaces:**
- Consumes: the snapshot and answer fields of Task 3; `PanelSample.REPLAY_COLUMN` (`"replay"`).
- Produces: `isFlatSchema(schema)`, the one place the flat-schema rule of spec §2.4 lives.

The page has no JavaScript test harness (spec §5): the data it reads is pinned by Task 3's snapshot tests, its
static rules by `PageTest`, its syntax by `node --check`, and its behaviour by the manual check after the last task.

- [ ] **Step 1: Write the failing test**

Add to `CONSOLE_TEST/PageTest.java`:

```java
    @Test
    void jsonArgumentsGetAFormForAFlatSchemaAndARawEditorOtherwise() {
        String script = file("console.js");

        assertTrue(script.contains("function isFlatSchema(schema)"), "the flat-schema rule, written once");
        assertTrue(script.contains("\"$ref\""), "a $ref is never flat");
        assertTrue(script.contains("function jsonField(argument)"), "a json argument's field");
        assertTrue(script.contains("const REPLAY_COLUMN = \"replay\""), "PanelSample.REPLAY_COLUMN");
        assertTrue(script.contains("const MASKED = \"***\""), "a masked value is not replayed");
        assertTrue(script.contains("const FILTER_FROM = 10"), "a filter past ten actions");
        assertTrue(script.contains("\"Exchange\""), "the details folded under Exchange");
    }
```

- [ ] **Step 2: Run it to see it fail**

Run: `mvn -o -q -pl $CONSOLE test -Dtest=PageTest`
Expected: FAIL, `the flat-schema rule, written once`.

- [ ] **Step 3: Write the implementation**

`console.js`, header comment: after the paragraph on actions, add:

```js
// - An action's json argument is a form generated from its JSON Schema when the schema is flat (isFlatSchema), a raw
//   JSON editor otherwise, with a "JSON" switch that keeps the values. A structured answer shows its body, pretty
//   printed when it is JSON, and its details folded under "Exchange". A sample table column named "replay" is a
//   button that fills an action's form: nothing is sent until the user submits.
```

Replace the whole `actions` section (from the first `// ---… actions` banner, which appears twice today — keep one —
down to the end of `actionRow`) with:

```js
// ------------------------------------------------------------------------------------------------ actions

/** The time of an outcome, by the server's clock, as the reader's local time of day. */
const clockTime = (t) => typeof t === "number" ? new Date(t).toLocaleTimeString() : "";

/** Past this many actions, a panel's actions get a text filter. */
const FILTER_FROM = 10;
/** The header of a sample table column whose cells replay an action: PanelSample.REPLAY_COLUMN. */
const REPLAY_COLUMN = "replay";
/** What a panel writes in place of a secret: a replay leaves it for the user to type again. */
const MASKED = "***";
/** The rows of the actions on the page, by panel and action: what a Replay button fills. */
const actionRows = new Map();
const actionKey = (panelId, actionId) => panelId + "\u0000" + actionId;
const isObject = (v) => v !== null && typeof v === "object" && !Array.isArray(v);

/**
 * The actions a panel offers, in a dev launch only (the snapshot then carries console.actionToken): one form each.
 * Actions with a group go into a folded section per group, in order of first appearance; past FILTER_FROM actions a
 * text filter narrows them. A confirmation is asked inline, never with the browser's blocking dialog. The request is
 * a same-origin fetch with the token of the boot.
 */
function actionsBar(panel) {
  const actions = Array.isArray(panel.actions) ? panel.actions : [];
  if (!actions.length) return null;
  const bar = el("div", "actions");
  const rows = actions.map((action) => actionRow(panel.id, action));
  for (const key of [...actionRows.keys()]) if (key.startsWith(panel.id + "\u0000")) actionRows.delete(key);
  for (const row of rows) actionRows.set(actionKey(panel.id, row.id), row);

  let filter = null;
  if (rows.length > FILTER_FROM) {
    filter = el("input", "action-filter");
    filter.type = "search";
    filter.placeholder = "Filter " + rows.length + " actions";
    filter.autocomplete = "off";
    filter.spellcheck = false;
    bar.append(filter);
  }
  const sections = new Map();
  for (const row of rows) {
    if (!row.group) { bar.append(row.root); continue; }
    let section = sections.get(row.group);
    if (!section) {
      const box = el("details", "action-group");
      const title = el("summary");
      box.append(title);
      bar.append(box);
      section = { name: row.group, box, title, rows: [] };
      sections.set(row.group, section);
    }
    section.box.append(row.root);
    section.rows.push(row);
  }
  const titles = () => {
    for (const s of sections.values()) {
      const shown = s.rows.filter((r) => !r.root.hidden).length;
      s.title.textContent = s.name + " (" + (shown === s.rows.length ? shown : shown + " of " + s.rows.length) + ")";
    }
  };
  titles();
  if (filter) {
    filter.addEventListener("input", () => {
      const words = filter.value.trim().toLowerCase();
      for (const row of rows) row.root.hidden = words !== "" && !row.text.includes(words);
      for (const s of sections.values()) {
        const any = s.rows.some((r) => !r.root.hidden);
        s.box.hidden = !any;
        if (words !== "" && any) s.box.open = true;
      }
      titles();
    });
  }
  return {
    root: bar,
    update(current) {
      const now = (current.actions || []);
      for (const row of rows) row.update(now.find((a) => a.id === row.id));
    },
  };
}

/** A string argument: a list when the server named the values it accepts, a text field otherwise. */
function stringField(argument) {
  const wrap = el("label", "arg");
  wrap.append(el("span", null, argument.label || argument.name));
  let input;
  if (Array.isArray(argument.allowed)) {
    input = el("select");
    for (const value of argument.allowed) {
      const option = el("option", null, value);
      option.value = value;
      input.append(option);
    }
  } else {
    input = el("input");
    input.type = "text";
    input.maxLength = 200;
    input.autocomplete = "off";
    input.spellcheck = false;
  }
  input.name = argument.name;
  wrap.append(input);
  return {
    name: argument.name,
    root: wrap,
    value: () => input.value,
    fill(v) { if (typeof v === "string" && v !== MASKED) input.value = v; },
    disable(on) { input.disabled = on; },
  };
}

const SKELETON = new Map([["string", ""], ["number", 0], ["integer", 0], ["boolean", false], ["array", []],
  ["object", {}]]);
const SCALAR_TYPES = new Set(["string", "number", "integer", "boolean"]);
const NOT_FLAT = ["$ref", "properties", "items", "anyOf", "oneOf", "allOf", "not", "patternProperties"];

/**
 * Whether a json argument's schema gets a generated form (spec §2.4): its root is "type": "object", and every
 * property is a string, a number, an integer or a boolean, or an enum of strings, with no $ref and no nesting. The
 * one place this rule is written.
 */
function isFlatSchema(schema) {
  if (!isObject(schema) || schema.type !== "object") return false;
  if (["$ref", "anyOf", "oneOf", "allOf", "not"].some((k) => k in schema)) return false;
  if (schema.properties === undefined) return true;
  if (!isObject(schema.properties)) return false;
  return Object.values(schema.properties).every((p) => isObject(p) && !NOT_FLAT.some((k) => k in p)
    && (Array.isArray(p.enum)
      ? (p.type === undefined || p.type === "string") && p.enum.length > 0 && p.enum.every((v) => typeof v === "string")
      : SCALAR_TYPES.has(p.type)));
}

/** The raw editor's first value: the required properties, each with its default, or an empty value of its type. */
function skeleton(schema) {
  const object = {};
  const properties = isObject(schema) && isObject(schema.properties) ? schema.properties : {};
  const required = isObject(schema) && Array.isArray(schema.required) ? schema.required : [];
  for (const name of required) {
    if (typeof name !== "string") continue;
    const p = isObject(properties[name]) ? properties[name] : {};
    object[name] = p.default !== undefined ? p.default
      : Array.isArray(p.enum) && p.enum.length ? p.enum[0]
      : SKELETON.has(p.type) ? structuredClone(SKELETON.get(p.type)) : null;
  }
  return object;
}

/** {@code values} without the members a panel masked: the user types those again. */
const unmasked = (values) => Object.fromEntries(Object.entries(values).filter(([, v]) => v !== MASKED));

/**
 * A json argument: a form generated from its schema when the schema is flat, using required, default, description
 * and enum, and a raw JSON editor otherwise, starting from the required properties. A "JSON" switch shows the form's
 * value as JSON; switching back keeps the values. value() returns the JSON text sent, or throws what is wrong.
 */
function jsonField(argument) {
  const root = el("div", "json-arg");
  const schema = argument.schema;
  const name = argument.label || argument.name;
  const flat = isFlatSchema(schema);
  const required = new Set(Array.isArray(schema.required) ? schema.required.filter((n) => typeof n === "string")
    : []);
  const head = el("div", "json-head");
  head.append(el("span", "json-label", name));
  const note = el("span", "json-note");
  const editor = el("textarea", "json-editor");
  editor.spellcheck = false;
  editor.rows = 6;
  editor.name = argument.name;
  editor.value = JSON.stringify(skeleton(schema), null, 2);
  const form = el("div", "json-form");
  const inputs = new Map();
  const raw = el("input");
  raw.type = "checkbox";
  if (flat) {
    for (const [property, definition] of Object.entries(schema.properties || {})) {
      const kind = Array.isArray(definition.enum) ? "enum" : definition.type;
      const wrap = el("label", "arg");
      wrap.append(el("span", null, property + (required.has(property) ? " *" : "")));
      let input;
      if (kind === "enum" || kind === "boolean") {
        input = el("select");
        for (const v of ["", ...(kind === "enum" ? definition.enum : ["true", "false"])]) {
          const option = el("option", null, v === "" ? "–" : v);
          option.value = v;
          input.append(option);
        }
      } else {
        input = el("input");
        input.type = "text";
        input.autocomplete = "off";
        input.spellcheck = false;
        if (kind !== "string") input.inputMode = "decimal";
      }
      if (definition.default !== undefined && definition.default !== null) input.value = String(definition.default);
      if (typeof definition.description === "string") input.title = definition.description;
      input.name = argument.name + "." + property;
      wrap.append(input);
      form.append(wrap);
      inputs.set(property, { input, kind });
    }
    const toggle = el("label", "json-switch");
    toggle.append(raw, el("span", null, "JSON"));
    head.append(toggle);
  }
  head.append(note);
  root.append(head);
  if (flat) root.append(form);
  root.append(editor);
  const rawMode = () => !flat || raw.checked;
  const show = () => { form.hidden = rawMode(); editor.hidden = !rawMode(); };
  show();

  /** The form's values as an object; strict, it refuses a number that is none and a missing required property. */
  function formObject(strict) {
    const object = {};
    for (const [property, { input, kind }] of inputs) {
      const text = input.value.trim();
      if (text === "") continue;
      if (kind === "integer" || kind === "number") {
        const n = Number(text);
        const valid = kind === "integer" ? /^-?\d+$/.test(text) : Number.isFinite(n);
        if (!valid && strict) throw new Error(property + ": not " + (kind === "integer" ? "an integer" : "a number"));
        object[property] = valid ? n : text;
      } else if (kind === "boolean") {
        object[property] = text === "true";
      } else {
        object[property] = input.value;
      }
    }
    if (strict) {
      for (const property of required) {
        if (!Object.hasOwn(object, property)) throw new Error(property + " is required");
      }
    }
    return object;
  }
  function editorObject() {
    let value;
    try { value = JSON.parse(editor.value); } catch (unparsable) { throw new Error(name + ": not valid JSON"); }
    if (!isObject(value)) throw new Error(name + ": not a JSON object");
    return value;
  }
  function toForm(object) {
    for (const [property, { input }] of inputs) {
      const v = object[property];
      input.value = v === undefined || v === null ? "" : typeof v === "object" ? JSON.stringify(v) : String(v);
    }
  }
  raw.addEventListener("change", () => {
    note.textContent = "";
    if (raw.checked) {
      editor.value = JSON.stringify(formObject(false), null, 2);
    } else {
      try { toForm(editorObject()); } catch (invalid) { raw.checked = true; note.textContent = invalid.message; }
    }
    show();
  });
  return {
    name: argument.name,
    root,
    value: () => JSON.stringify(rawMode() ? editorObject() : formObject(true)),
    fill(values) {
      if (!isObject(values)) return;
      const kept = unmasked(values);
      editor.value = JSON.stringify(kept, null, 2);
      if (flat) toForm(kept);
      note.textContent = Object.keys(kept).length < Object.keys(values).length ? "masked values: type them again" : "";
    },
    disable(on) { for (const c of [editor, raw, ...[...inputs.values()].map((i) => i.input)]) c.disabled = on; },
  };
}

/** {@code text} pretty-printed when it is JSON, as it is otherwise. */
function prettyJson(text) {
  try { return JSON.stringify(JSON.parse(text), null, 2); } catch (notJson) { return text; }
}

/** What an answer shows under its line: its body, then its details folded under "Exchange". */
function resultOutput(answer) {
  const out = [];
  if (typeof answer.body === "string") {
    const json = typeof answer.contentType === "string" && answer.contentType.startsWith("application/json");
    out.push(el("pre", "result-body", json ? prettyJson(answer.body) : answer.body));
  }
  if (typeof answer.details === "string") {
    const exchange = el("details", "exchange");
    exchange.append(el("summary", null, "Exchange"), el("pre", "result-body", prettyJson(answer.details)));
    out.push(exchange);
  }
  return out;
}

function actionRow(panelId, action) {
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
  const message = el("span", "msg");
  const output = el("div", "result");
  root.append(go, ask, message, output);

  let sending = false;
  let shown = null;            // the time of the outcome of the snapshot last shown; a newer one replaces the message
  const busy = (on) => {
    for (const c of [go, yes, no]) c.disabled = on;
    for (const field of fields) field.disable(on);
  };
  const say = (text, cls) => { message.textContent = text; message.className = "msg" + (cls ? " " + cls : ""); };
  const closeAsk = () => { ask.hidden = true; go.hidden = false; };
  const reveal = () => {
    root.hidden = false;
    const box = root.closest("details");
    if (box) { box.hidden = false; box.open = true; }
  };

  async function send() {
    const token = page.snapshot && page.snapshot.console && page.snapshot.console.actionToken;
    if (typeof token !== "string") { say("No token: reload the page.", "failed"); return; }
    const body = {};
    try {
      for (const field of fields) body[field.name] = field.value();
    } catch (invalid) {
      say(invalid.message, "failed");
      return;
    }
    sending = true;
    busy(true);
    output.replaceChildren();
    say("running…", "running");
    try {
      const response = await fetch("api/action/" + encodeURIComponent(panelId) + "/" + encodeURIComponent(action.id), {
        method: "POST",
        cache: "no-store",
        headers: { "Content-Type": "application/json", "X-Vidocq-Console-Token": token },
        body: JSON.stringify(body),
      });
      const type = response.headers.get("Content-Type") || "";
      const answer = type.startsWith("application/json") ? await response.json() : { text: await response.text() };
      if (response.status === 200 && typeof answer.result === "string") {
        say(answer.result, answer.error === true ? "failed" : "ok");
        output.replaceChildren(...resultOutput(answer));
      } else if (response.status === 500 && typeof answer.error === "string") say("failed: " + answer.error, "failed");
      else if (response.status === 202) say("still running after 60 s: the outcome will show here", "running");
      else if (response.status === 409) say("another action of this panel is running", "failed");
      else say("refused (" + response.status + ")" + (answer.text ? ": " + answer.text : ""), "failed");
    } catch (unreachable) {
      say("the console did not answer", "failed");
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

  return {
    id: action.id,
    group: typeof action.group === "string" && action.group ? action.group : null,
    text: [action.label, action.id, action.description].filter((t) => typeof t === "string").join(" ").toLowerCase(),
    root,
    /** Fills the form with a replayed call's arguments, by name; sends nothing. */
    fill(values) {
      for (const field of fields) if (Object.hasOwn(values, field.name)) field.fill(values[field.name]);
      reveal();
      root.scrollIntoView({ block: "nearest" });
      go.focus();
    },
    update(now) {
      if (!now || sending) return;
      busy(!!now.running);
      if (now.running) { say("running…", "running"); shown = null; return; }
      const last = now.last;
      if (last && typeof last.text === "string" && last.time !== shown) {
        shown = last.time;
        say((last.ok ? "" : "failed: ") + last.text + " · " + clockTime(last.time),
          last.ok && last.error !== true ? "ok" : "failed");
      }
    },
  };
}

/**
 * A cell of a REPLAY_COLUMN column: "<action id> <JSON object of its arguments>", as a button that fills that
 * action's form with them. Nothing is sent: the user submits. An empty or unreadable cell stays empty.
 */
function replayCell(panelId, cell) {
  const td = el("td");
  const space = typeof cell === "string" ? cell.indexOf(" ") : -1;
  if (space <= 0) return td;
  let values;
  try { values = JSON.parse(cell.slice(space + 1)); } catch (unreadable) { return td; }
  const row = actionRows.get(actionKey(panelId, cell.slice(0, space)));
  if (!row || !isObject(values)) return td;
  const button = el("button", "replay", "Replay");
  button.type = "button";
  button.title = "Fill the form of this action with these arguments";
  button.addEventListener("click", () => row.fill(values));
  td.append(button);
  return td;
}
```

Replace `sampleTable` with:

```js
/** A table of a sample, its columns and rows; a REPLAY_COLUMN column is drawn as Replay buttons. */
function sampleTable(value, panelId) {
  const table = el("table", "ext");
  const columns = value.columns || [];
  const replayAt = columns.indexOf(REPLAY_COLUMN);
  const head = el("tr");
  columns.forEach((column, i) => head.append(el("th", null, i === replayAt ? "" : column)));
  const thead = el("thead");
  thead.append(head);
  const body = el("tbody");
  for (const row of value.rows || []) {
    const tr = el("tr");
    row.forEach((cell, i) => tr.append(i === replayAt ? replayCell(panelId, cell)
      : el("td", /^\d+$/.test(cell) ? "n" : null, cell)));
    body.append(tr);
  }
  table.append(thead, body);
  const scroll = el("div", "scroll");
  scroll.append(table);
  return scroll;
}
```

In `scopeView`'s `update`, change `sampleTable(value)` to `sampleTable(value, panel.id)`.

`console.css`, append to the actions block (after `.action .msg.running`):

```css
.action[hidden], .action-group[hidden], .action .json-form[hidden] { display: none; }
.action .action-desc { flex-basis: 100%; margin: 0; font-size: 12.5px; color: var(--muted); white-space: pre-line;
  max-width: 80ch; overflow-wrap: anywhere; }
.action .json-arg { flex-basis: 100%; display: flex; flex-direction: column; gap: 6px; }
.action .json-head { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 12px; font-size: 12px;
  color: var(--muted); }
.action .json-switch { display: inline-flex; align-items: center; gap: 4px; }
.action .json-note { color: var(--crit); }
.action .json-form { display: flex; flex-wrap: wrap; gap: 6px 12px; }
.action .json-editor { font-family: var(--mono); font-size: 12px; color: var(--ink); background: var(--surface);
  border: 1px solid var(--rule); border-radius: 5px; padding: 6px 8px; width: 100%; max-width: 80ch; }
.action .result { flex-basis: 100%; }
pre.result-body { background: var(--sunk); border-radius: 6px; padding: 10px 12px; margin: 6px 0 0;
  font-family: var(--mono); font-size: 12px; max-height: 24em; overflow: auto; white-space: pre-wrap;
  overflow-wrap: anywhere; color: var(--ink); }
.action .exchange summary { cursor: pointer; font-size: 12px; color: var(--muted); margin-top: 6px; }
.action-group { border: 1px solid var(--rule); border-radius: 6px; padding: 6px 10px; }
.action-group > summary { cursor: pointer; font-size: 13px; font-weight: 600; }
.action-group[open] > .action { margin-top: 10px; }
.action-filter { font: inherit; font-size: 12px; color: var(--ink); background: var(--surface);
  border: 1px solid var(--rule); border-radius: 5px; padding: 4px 8px; width: 24em; max-width: 100%; }
button.replay { font-size: 11.5px; padding: 1px 8px; }
```

- [ ] **Step 4: Run the test, then check the syntax**

Run: `mvn -o -q -pl $CONSOLE test`
Expected: `BUILD SUCCESS`, `PageTest` included (no markup sink, no blocking dialog, one `localStorage` read and
write).

Run: `node --check $CONSOLE/src/main/resources/META-INF/resources/devconsole/console.js`
Expected: no output, exit code 0. (If `node` is missing, say so in the task report; the manual check after the last
task still covers the page.)

- [ ] **Step 5: Commit** — `feat(devconsole): the page draws json forms, results, action groups and replays`.
  Files: `CONSOLE_RES/console.js`, `CONSOLE_RES/console.css`, `CONSOLE_TEST/PageTest.java`.

---

### Task 5: The runtime MCP extension publishes the URL of `/mcp`

**Files:**
- Create: `MCP_SRC/live/McpEndpointLive.java`, `MCP_TEST/McpEndpointPublishedTest.java`
- Modify: `MCP_SRC/McpExtension.java` (`contribute`, `onStop`)

**Interfaces:**
- Produces: `public final class McpEndpointLive` in `...langchain4jcdi.mcp.live` (already exported to the `-dev`
  module): `static List<String> urls()`, `static void publish(List<String>)`, `static void clear()`.

- [ ] **Step 1: Write the failing test**

`MCP_TEST/McpEndpointPublishedTest.java`:

```java
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp;

import io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.live.McpEndpointLive;
import io.vidocq.runtime.spi.report.Verbosity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The URLs of {@code /mcp} the startup report resolved, published for the MCP inspector of the {@code -dev} module:
 * each once, without the internal listen route, and forgotten when the extension stops, so that a dev reload never
 * calls the previous boot's address.
 */
class McpEndpointPublishedTest {

    @AfterEach
    void clear() {
        McpEndpointLive.clear();
    }

    @Test
    void contributePublishesTheUrlsOfMcpAndOnStopForgetsThem() {
        McpExtension extension = new McpExtension();
        FakeReportContext context = new FakeReportContext(Verbosity.SUMMARY)
                .route(McpStartupSection.ENDPOINT, "http://127.0.0.1:18090/mcp")
                .route(McpStartupSection.ENDPOINT, "http://127.0.0.1:18090/mcp")
                .route(McpStartupSection.ENDPOINT, "http://127.0.0.1:18090/mcp/_listen");

        extension.contribute(context, new RecordingSection());

        assertEquals(List.of("http://127.0.0.1:18090/mcp"), McpEndpointLive.urls());

        extension.onStop();

        assertEquals(List.of(), McpEndpointLive.urls());
    }

    @Test
    void noRouteMeansNoUrl() {
        new McpExtension().contribute(new FakeReportContext(Verbosity.SUMMARY), new RecordingSection());

        assertEquals(List.of(), McpEndpointLive.urls());
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `mvn -o -q -pl $MCP test -Dtest=McpEndpointPublishedTest`
Expected: compilation FAILURE, `cannot find symbol: class McpEndpointLive`.

- [ ] **Step 3: Write the implementation**

`MCP_SRC/live/McpEndpointLive.java`:

```java
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.live;

import java.util.List;

/**
 * The absolute URLs of {@code /mcp} the startup report resolved, for the MCP inspector of the {@code -dev} module
 * only: a live panel's {@code start} gets an {@code ExtensionContext}, which cannot resolve a route to a URL.
 * Published when the {@code mcp} section is written, which is before the console starts its live panels, and cleared
 * first when the extension stops, so that a dev reload never calls the previous boot's address.
 */
public final class McpEndpointLive {

    private static volatile List<String> urls = List.of();

    private McpEndpointLive() {}

    /** The URLs of this boot, each once, without the internal listen route; empty before the report is written. */
    public static List<String> urls() {
        return urls;
    }

    /** Publishes the URLs of this boot. */
    public static void publish(List<String> resolved) {
        urls = List.copyOf(resolved);
    }

    /** Forgets them. */
    public static void clear() {
        urls = List.of();
    }
}
```

In `MCP_SRC/McpExtension.java` (with `import io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.live.McpEndpointLive;`):

```java
    @Override
    public void contribute(StartupReportContext context, StartupReportSection section) {
        McpStartupSection.write(inspection, mapped != null, McpEndpoint.class.getModule(), context, section);
        // The MCP inspector of the -dev module calls the URL the report prints: it cannot resolve routes itself.
        McpEndpointLive.publish(McpStartupSection.endpointUrls(context.routeUrls(McpStartupSection.ENDPOINT)));
    }
```

and in `onStop`, first line: `McpEndpointLive.clear();`. Add to the class Javadoc's list: `{@link #contribute} also publishes the URLs of {@code /mcp} in {@link McpEndpointLive}, for the MCP inspector of the {@code -dev} module.`

- [ ] **Step 4: Run it to see it pass, and install**

Run: `mvn -o -q -pl $MCP test`, then `mvn -o -q -pl $MCP install -DskipTests`
Expected: `BUILD SUCCESS` both times.

- [ ] **Step 5: Commit** — `feat(mcp): publish the URL of /mcp for the dev console's inspector`.
  Files: `MCP_SRC/live/McpEndpointLive.java`, `MCP_SRC/McpExtension.java`, `MCP_TEST/McpEndpointPublishedTest.java`.

---

### Task 6: The catalogue: items, ids and the confirmation rule

**Files:**
- Create: `DEV_SRC/ActionIds.java`, `DEV_SRC/McpCatalogue.java`, `DEV_TEST/InspectorFixtures.java`,
  `DEV_TEST/ActionIdsTest.java`, `DEV_TEST/McpCatalogueTest.java`
- Modify: `$DEV/src/main/java/module-info.java`, `$DEV/pom.xml`

**Interfaces:**
- Produces:
  - `final class ActionIds`: `String named(String prefix, String name)`, `String hashed(String prefix, String
    uri)`, `static String slug(String)`, `static String sha8(String)`, `static final int MAX = 40`;
  - `record McpCatalogue(List<Item> items, String absent)` with `EMPTY`, `NO_REGISTRY`, `CONFIRMATION`,
    `OPEN_SCHEMA`; `static McpCatalogue read(BeanManager)`; `static McpCatalogue of(Collection<McpToolDescriptor>,
    Collection<McpPromptDescriptor>, Collection<McpResourceDescriptor>, Collection<McpResourceTemplateDescriptor>)`;
    `String summary()`; `static String confirmation(McpToolDescriptor)`;
  - `enum McpCatalogue.Kind { TOOL, PROMPT, RESOURCE, TEMPLATE }` with `group()`, `argument()` (`arguments`,
    `arguments`, `null`, `variables`), `argumentLabel()`;
  - `record McpCatalogue.Item(Kind kind, String id, String target, String label, String description, String schema,
    String confirmation, Map<String, String> headerDesignations)`;
  - test fixture `InspectorFixtures` with `CATALOGUE_BEANS`, `tool(String)`, `prompt(String)`, `resource(String)`,
    `template(String)`, `fill(BeanManager)`.

- [ ] **Step 1: Prepare the module**

`$DEV/pom.xml`: in `<dependencies>`, after the runtime extension, add (already on the path through it; declared
because the module now uses it directly):

```xml
        <!-- The MCP inspector reads and writes JSON-RPC with JSON-P, which the runtime extension already brings. -->
        <dependency>
            <groupId>jakarta.json</groupId>
            <artifactId>jakarta.json-api</artifactId>
            <version>2.1.3</version>
        </dependency>
```

and change its `<description>` to `The live mcp panel of the dev console, and its MCP inspector. Only vidocq:dev adds
it; no binary contains it (Vidocq/vidocq#143).`

`$DEV/src/main/java/module-info.java`: Javadoc `The live mcp panel of the dev console and its MCP inspector, which
only vidocq:dev adds (Vidocq/vidocq#143).`; after `requires dev.langchain4j.cdi.mcp.invoker.cdi41;` add:

```java
    // The MCP inspector: JSON-RPC with the JSON-P langchain4j-cdi already brings, over the JDK's HTTP client.
    requires jakarta.json;
    requires java.net.http;
```

- [ ] **Step 2: Write the failing tests**

`DEV_TEST/InspectorFixtures.java`:

```java
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import dev.langchain4j.cdi.mcp.server.registry.McpPromptDescriptor;
import dev.langchain4j.cdi.mcp.server.registry.McpPromptRegistry;
import dev.langchain4j.cdi.mcp.server.registry.McpResourceDescriptor;
import dev.langchain4j.cdi.mcp.server.registry.McpResourceRegistry;
import dev.langchain4j.cdi.mcp.server.registry.McpResourceTemplateDescriptor;
import dev.langchain4j.cdi.mcp.server.registry.McpToolDescriptor;
import dev.langchain4j.cdi.mcp.server.registry.McpToolRegistry;
import dev.langchain4j.cdi.mcp.server.transport.McpNotificationBroadcaster;
import dev.langchain4j.cdi.mcp.server.transport.McpResourceSubscriptionManager;
import dev.langchain4j.cdi.mcp.server.transport.McpRootsManager;
import dev.langchain4j.cdi.mcp.server.transport.McpServerRequestManager;
import dev.langchain4j.cdi.mcp.server.transport.McpSessionManager;
import dev.langchain4j.cdi.mcp.server.transport.McpSubscriptionRegistry;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import org.mcpjava.server.prompts.Prompt;
import org.mcpjava.server.prompts.PromptArg;
import org.mcpjava.server.resources.Resource;
import org.mcpjava.server.resources.ResourceTemplate;
import org.mcpjava.server.resources.ResourceTemplateArg;
import org.mcpjava.server.tools.Tool;
import org.mcpjava.server.tools.ToolArg;

import java.lang.reflect.Method;
import java.util.List;

/**
 * MCP methods and registries for the inspector's tests. This module compiles without {@code -parameters}: every
 * argument is named explicitly.
 */
final class InspectorFixtures {

    /** The registries, and every bean their injection points need. */
    static final Class<?>[] CATALOGUE_BEANS = {
        McpToolRegistry.class, McpPromptRegistry.class, McpResourceRegistry.class,
        McpSessionManager.class, McpNotificationBroadcaster.class, McpSubscriptionRegistry.class,
        McpServerRequestManager.class, McpResourceSubscriptionManager.class, McpRootsManager.class
    };

    private InspectorFixtures() {}

    static final class Tools {

        @Tool(name = "current_time", title = "Current time", description = "The current time in a zone.",
                annotations = @Tool.Annotations(readOnlyHint = true))
        public String currentTime(@ToolArg(name = "zone", description = "IANA zone id") String zone) {
            return zone;
        }

        @Tool(name = "current-time", description = "The same key as current_time once turned into an id.",
                annotations = @Tool.Annotations(readOnlyHint = true))
        public String currentTimeHyphen() {
            return "now";
        }

        @Tool(name = "reset_counter", description = "Resets the counter.")
        public String resetCounter() {
            return "0";
        }

        @Tool(name = "a_tool_whose_name_is_far_longer_than_forty_characters", description = "Long.",
                annotations = @Tool.Annotations(destructiveHint = false))
        public String longName() {
            return "long";
        }

        @Tool(name = "search", description = "Searches with a key.")
        public String search(@ToolArg(name = "query") String query, @ToolArg(name = "apiKey") String apiKey) {
            return query;
        }
    }

    static final class Prompts {

        @Prompt(name = "plan_meeting", description = "Plans a meeting.")
        public String planMeeting(@PromptArg(name = "zones", description = "Comma-separated zones") String zones,
                                  @PromptArg(name = "note", required = false) String note) {
            return zones;
        }
    }

    static final class Resources {

        @Resource(uri = "time://utc", name = "utc", description = "UTC.")
        public String utc() {
            return "UTC";
        }

        @ResourceTemplate(uriTemplate = "time://zone/{zone}", name = "time-in-zone", description = "A zone.")
        public String timeInZone(@ResourceTemplateArg(name = "zone") String zone) {
            return zone;
        }
    }

    static McpToolDescriptor tool(String method) {
        return McpToolDescriptor.fromMethod(Tools.class, method(Tools.class, method));
    }

    static McpPromptDescriptor prompt(String method) {
        return McpPromptDescriptor.fromMethod(Prompts.class, method(Prompts.class, method));
    }

    static McpResourceDescriptor resource(String method) {
        return McpResourceDescriptor.fromMethod(Resources.class, method(Resources.class, method));
    }

    static McpResourceTemplateDescriptor template(String method) {
        return McpResourceTemplateDescriptor.fromMethod(Resources.class, method(Resources.class, method));
    }

    /** Creates the three registries through their client proxies, as the server does, and fills them. */
    static void fill(BeanManager beans) {
        McpToolRegistry tools = reference(beans, McpToolRegistry.class);
        for (String method : List.of("currentTime", "currentTimeHyphen", "resetCounter", "longName", "search")) {
            tools.register(tool(method));
        }
        reference(beans, McpPromptRegistry.class).register(prompt("planMeeting"));
        McpResourceRegistry resources = reference(beans, McpResourceRegistry.class);
        resources.register(resource("utc"));
        resources.registerTemplate(template("timeInZone"));
    }

    static <T> T reference(BeanManager beans, Class<T> type) {
        Bean<?> bean = beans.resolve(beans.getBeans(type));
        return type.cast(beans.getReference(bean, type, beans.createCreationalContext(bean)));
    }

    private static Method method(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new IllegalArgumentException(name);
    }
}
```

`DEV_TEST/ActionIdsTest.java`:

```java
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import io.vidocq.runtime.spi.devconsole.PanelSample;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Action ids follow the console's key rule, are cut to fit, and never collide. */
class ActionIdsTest {

    @Test
    void aNameIsLowercasedAndEveryOtherCharacterBecomesAHyphen() {
        ActionIds ids = new ActionIds();

        assertEquals("tool.current-time", ids.named("tool.", "current_time"));
        assertEquals("prompt.m-t-o", ids.named("prompt.", "Météo"));
        assertEquals("tool.a.b-c", ids.named("tool.", "a.b c"));
    }

    @Test
    void aLongNameIsCutToFortyCharacters() {
        String id = new ActionIds().named("tool.", "a_tool_whose_name_is_far_longer_than_forty_characters");

        assertEquals("tool.a-tool-whose-name-is-far-longer-tha", id);
        assertEquals(40, id.length());
        PanelSample.requireKey(id);
    }

    @Test
    void aCollisionGetsASuffixThatStillFits() {
        ActionIds ids = new ActionIds();
        String name = "x".repeat(60);

        assertEquals("tool.current-time", ids.named("tool.", "current-time"));
        assertEquals("tool.current-time-2", ids.named("tool.", "current_time"));
        assertEquals("tool.current-time-3", ids.named("tool.", "Current Time"));
        String first = ids.named("tool.", name);
        String second = ids.named("tool.", name + "y");
        assertEquals(40, first.length());
        assertEquals(40, second.length());
        assertEquals(first.substring(0, 38) + "-2", second);
    }

    @Test
    void aUriIsTheFirstEightHexCharactersOfItsSha256() throws Exception {
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("time://utc".getBytes(StandardCharsets.UTF_8))).substring(0, 8);

        assertEquals("res." + expected, new ActionIds().hashed("res.", "time://utc"));
        assertEquals("res.860cd4a5", new ActionIds().hashed("res.", "time://utc"));
        assertEquals("tpl.72bba68d", new ActionIds().hashed("tpl.", "time://zone/{zone}"));
    }
}
```

`DEV_TEST/McpCatalogueTest.java`:

```java
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import dev.langchain4j.cdi.mcp.server.protocol.McpToolAnnotationsModel;
import dev.langchain4j.cdi.mcp.server.registry.McpToolDescriptor;
import dev.langchain4j.cdi.mcp.server.registry.McpToolRegistry;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the inspector offers, read from langchain4j-cdi's registries without creating any. */
class McpCatalogueTest {

    private static McpCatalogue all() {
        return McpCatalogue.of(
                List.of(InspectorFixtures.tool("resetCounter"), InspectorFixtures.tool("currentTime"),
                        InspectorFixtures.tool("currentTimeHyphen"), InspectorFixtures.tool("longName"),
                        InspectorFixtures.tool("search")),
                List.of(InspectorFixtures.prompt("planMeeting")),
                List.of(InspectorFixtures.resource("utc")),
                List.of(InspectorFixtures.template("timeInZone")));
    }

    private static McpCatalogue.Item item(McpCatalogue catalogue, String id) {
        return catalogue.items().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void eachToolPromptResourceAndTemplateIsAnItemOfItsGroupInNameOrder() {
        McpCatalogue catalogue = all();

        assertEquals(List.of("tool.a-tool-whose-name-is-far-longer-tha", "tool.current-time", "tool.current-time-2",
                "tool.reset-counter", "tool.search", "prompt.plan-meeting", "res.860cd4a5", "tpl.72bba68d"),
                catalogue.items().stream().map(McpCatalogue.Item::id).toList());
        assertEquals(List.of("Tools", "Tools", "Tools", "Tools", "Tools", "Prompts", "Resources", "Resources"),
                catalogue.items().stream().map(item -> item.kind().group()).toList());
        catalogue.items().forEach(item -> PanelSample.requireKey(item.id()));
        assertEquals("5 tools, 1 prompt, 1 resource, 1 resource template", catalogue.summary());
        assertNull(catalogue.absent());
    }

    @Test
    void theIdsAreTheSameOnTheNextReload() {
        assertEquals(all().items().stream().map(McpCatalogue.Item::id).toList(),
                all().items().stream().map(McpCatalogue.Item::id).toList());
    }

    @Test
    void aToolIsLabelledByItsTitleElseItsNameAndCarriesItsSchema() {
        McpCatalogue.Item titled = item(all(), "tool.current-time-2");
        McpCatalogue.Item untitled = item(all(), "tool.reset-counter");

        assertEquals("current_time", titled.target());
        assertEquals("Current time", titled.label());
        assertEquals("The current time in a zone.", titled.description());
        assertTrue(titled.schema().contains("\"zone\""), titled.schema());
        assertEquals("reset_counter", untitled.label());
        assertEquals("time://utc", item(all(), "res.860cd4a5").label());
        assertEquals("time://zone/{zone}", item(all(), "tpl.72bba68d").label());
        assertNull(item(all(), "res.860cd4a5").schema(), "a fixed resource takes no argument");
    }

    @Test
    void aToolAsksForConfirmationUnlessItIsReadOnlyAndNotDestructive() {
        assertNull(McpCatalogue.confirmation(InspectorFixtures.tool("currentTime")), "read-only");
        assertEquals("Call reset_counter? It runs the application's code, and may change data.",
                McpCatalogue.confirmation(InspectorFixtures.tool("resetCounter")), "no annotation");
        assertEquals("Call a_tool_whose_name_is_far_longer_than_forty_characters? It runs the application's code, "
                + "and may change data.", McpCatalogue.confirmation(InspectorFixtures.tool("longName")),
                "not destructive, but not read-only either");
        McpToolDescriptor plain = InspectorFixtures.tool("currentTime");
        McpToolDescriptor destructive = new McpToolDescriptor(plain.getName(), plain.getDescription(),
                plain.getInputSchema(), plain.getModernInputSchema(), plain.getBeanType(), plain.getMethod(), null,
                null, new McpToolAnnotationsModel(null, Boolean.TRUE, Boolean.TRUE, null, null));
        assertEquals("Call current_time? It runs the application's code, and may change data.",
                McpCatalogue.confirmation(destructive), "read-only, yet destructive");
        assertNull(item(all(), "prompt.plan-meeting").confirmation(), "a prompt never asks");
        assertNull(item(all(), "tpl.72bba68d").confirmation(), "a resource never asks");
    }

    @Test
    void aPromptSchemaHasAStringPerArgumentRequiredAsDeclared() {
        assertEquals("{\"type\":\"object\",\"properties\":{\"zones\":{\"type\":\"string\","
                + "\"description\":\"Comma-separated zones\"},\"note\":{\"type\":\"string\"}},"
                + "\"required\":[\"zones\"]}", item(all(), "prompt.plan-meeting").schema());
    }

    @Test
    void aTemplateSchemaHasARequiredStringPerVariable() {
        assertEquals("{\"type\":\"object\",\"properties\":{\"zone\":{\"type\":\"string\"}},"
                + "\"required\":[\"zone\"]}", item(all(), "tpl.72bba68d").schema());
    }

    @Test
    void readTakesTheRegistriesThatExistAndCreatesNone() {
        try (VaubanContainer container = McpTestContainers.container(InspectorFixtures.CATALOGUE_BEANS)) {
            BeanManager beans = container.getBeanManager();
            Bean<?> tools = beans.resolve(beans.getBeans(McpToolRegistry.class));

            McpCatalogue before = McpCatalogue.read(beans);

            assertEquals(McpCatalogue.NO_REGISTRY, before.absent());
            assertEquals(List.of(), before.items());
            assertNull(beans.getContext(tools.getScope()).get(tools), "read() created the tool registry");

            InspectorFixtures.fill(beans);
            McpCatalogue after = McpCatalogue.read(beans);

            assertNull(after.absent());
            assertEquals(8, after.items().size());
        }
    }

    @Test
    void noRegistryBeanMeansNoMcpServer() {
        try (VaubanContainer container = McpTestContainers.container()) {
            assertEquals(McpLiveBeans.NOT_DEPLOYED, McpCatalogue.read(container.getBeanManager()).absent());
        }
    }

    @Test
    void aToolCarriesTheArgumentsItMirrorsIntoHeaders() {
        assertEquals(Map.of(), item(all(), "tool.search").headerDesignations());
    }
}
```

- [ ] **Step 3: Run them to see them fail**

Run: `mvn -o -q -pl $DEV test -Dtest='ActionIdsTest,McpCatalogueTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAILURE, `cannot find symbol: class ActionIds`.

- [ ] **Step 4: Write the implementation**

`DEV_SRC/ActionIds.java`:

```java
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/**
 * The ids of the inspector's actions, one instance per catalogue. An id follows the console's key rule, a lowercase
 * letter then at most 39 lowercase letters, digits, dots or hyphens: a prefix, then the name lowercased with every
 * other character turned into {@code -}, or the first 8 hex characters of the SHA-256 of a URI. It is cut to fit,
 * and a collision gets {@code -2}, {@code -3} and so on. Built in name order, ids are the same on every reload as
 * long as the names do not change.
 */
final class ActionIds {

    /** The longest id the console accepts. */
    static final int MAX = 40;

    private final Set<String> taken = new HashSet<>();

    /** The id of a tool or a prompt: {@code prefix}, then the slug of {@code name}. */
    String named(String prefix, String name) {
        return unique(prefix + slug(name));
    }

    /** The id of a resource or a template: {@code prefix}, then the first 8 hex characters of the SHA-256 of it. */
    String hashed(String prefix, String uri) {
        return unique(prefix + sha8(uri));
    }

    /** {@code name} lowercased, every character but {@code a-z}, {@code 0-9}, {@code .} and {@code -} a hyphen. */
    static String slug(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            boolean kept = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-';
            out.append(kept ? c : '-');
        }
        return out.toString();
    }

    /** The first 8 hex characters of the SHA-256 of {@code text}, in UTF-8. */
    static String sha8(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("every JDK has SHA-256", impossible);
        }
    }

    private String unique(String wanted) {
        String base = cut(wanted, MAX);
        if (taken.add(base)) {
            return base;
        }
        for (int n = 2; ; n++) {
            String suffix = "-" + n;
            String candidate = cut(wanted, MAX - suffix.length()) + suffix;
            if (taken.add(candidate)) {
                return candidate;
            }
        }
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
```

`DEV_SRC/McpCatalogue.java`:

```java
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import dev.langchain4j.cdi.mcp.server.protocol.McpToolAnnotationsModel;
import dev.langchain4j.cdi.mcp.server.registry.McpPromptDescriptor;
import dev.langchain4j.cdi.mcp.server.registry.McpPromptRegistry;
import dev.langchain4j.cdi.mcp.server.registry.McpResourceDescriptor;
import dev.langchain4j.cdi.mcp.server.registry.McpResourceRegistry;
import dev.langchain4j.cdi.mcp.server.registry.McpResourceTemplateDescriptor;
import dev.langchain4j.cdi.mcp.server.registry.McpToolDescriptor;
import dev.langchain4j.cdi.mcp.server.registry.McpToolRegistry;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * What the MCP inspector offers (spec §3.1): one item per tool, prompt, resource and resource template of
 * langchain4j-cdi's registries, read once per boot at the panel's {@code start}, each with the id of its action, what
 * the page shows of it, the JSON Schema of its argument and, for a tool, the question asked before a call.
 *
 * <p>The registries are read the way the rest of the panel reads beans: only an instance that already exists,
 * through {@link Context#get(jakarta.enterprise.context.spi.Contextual)}, never one created for the occasion.
 *
 * @param items  tools, then prompts, resources and resource templates, each kind in name or URI order
 * @param absent why the catalogue is empty, or {@code null} when it could be read
 */
record McpCatalogue(List<Item> items, String absent) {

    static final McpCatalogue EMPTY = new McpCatalogue(List.of(), null);
    /** Registry beans exist, but the server has not created any of them yet. */
    static final String NO_REGISTRY = "no MCP registry created yet";
    /** What a tool that may change something asks before a call. */
    static final String CONFIRMATION = "Call %s? It runs the application's code, and may change data.";
    /** The schema of an argument whose own schema is missing or too large: the page offers a raw JSON editor. */
    static final String OPEN_SCHEMA = "{\"type\":\"object\"}";

    /** What an item is, the group its action goes in, and the name of its json argument. */
    enum Kind {
        TOOL("Tools", "arguments", "Arguments"),
        PROMPT("Prompts", "arguments", "Arguments"),
        RESOURCE("Resources", null, null),
        TEMPLATE("Resources", "variables", "Variables");

        private final String group;
        private final String argument;
        private final String argumentLabel;

        Kind(String group, String argument, String argumentLabel) {
            this.group = group;
            this.argument = argument;
            this.argumentLabel = argumentLabel;
        }

        /** The group its actions are shown in. */
        String group() {
            return group;
        }

        /** The name of its json argument; {@code null} for a fixed resource, which takes none. */
        String argument() {
            return argument;
        }

        /** What the page writes next to that argument. */
        String argumentLabel() {
            return argumentLabel;
        }
    }

    /**
     * One thing the inspector can call.
     *
     * @param kind               what it is
     * @param id                 the id of its action, by {@link ActionIds}
     * @param target             the tool or prompt name, the resource URI or the URI template
     * @param label              the tool's title, else its name; the prompt's name; the URI or URI template
     * @param description        its description, at most {@link PanelAction#MAX_DESCRIPTION} characters, or
     *                           {@code null}
     * @param schema             the JSON Schema of its argument, {@code null} for a fixed resource
     * @param confirmation       the question asked before a call, or {@code null}
     * @param headerDesignations for a tool, the arguments it mirrors into {@code Mcp-Param-*} headers, by name
     */
    record Item(Kind kind, String id, String target, String label, String description, String schema,
                String confirmation, Map<String, String> headerDesignations) {}

    McpCatalogue {
        items = List.copyOf(items);
    }

    /**
     * Reads the catalogue from the registries that exist in the container, creating none. A registry with no
     * instance yet counts as empty; none at all is {@link #NO_REGISTRY}, and no registry bean at all
     * {@link McpLiveBeans#NOT_DEPLOYED}. Never throws: a registry that fails makes a catalogue that says so.
     *
     * @param beans the bean manager of the started container
     * @return the catalogue
     */
    static McpCatalogue read(BeanManager beans) {
        try {
            Bean<?> toolBean = bean(beans, McpToolRegistry.class);
            Bean<?> promptBean = bean(beans, McpPromptRegistry.class);
            Bean<?> resourceBean = bean(beans, McpResourceRegistry.class);
            if (toolBean == null && promptBean == null && resourceBean == null) {
                return new McpCatalogue(List.of(), McpLiveBeans.NOT_DEPLOYED);
            }
            McpToolRegistry tools = instance(beans, toolBean, McpToolRegistry.class);
            McpPromptRegistry prompts = instance(beans, promptBean, McpPromptRegistry.class);
            McpResourceRegistry resources = instance(beans, resourceBean, McpResourceRegistry.class);
            if (tools == null && prompts == null && resources == null) {
                return new McpCatalogue(List.of(), NO_REGISTRY);
            }
            return of(tools == null ? List.of() : tools.listTools(),
                    prompts == null ? List.of() : prompts.listPrompts(),
                    resources == null ? List.of() : resources.listResources(),
                    resources == null ? List.of() : resources.listTemplates());
        } catch (RuntimeException | LinkageError unreadable) {
            return new McpCatalogue(List.of(), "catalogue unreadable: " + unreadable.getClass().getSimpleName());
        }
    }

    /**
     * The catalogue of these descriptors, each kind in name or URI order, so that ids are stable across reloads.
     */
    static McpCatalogue of(Collection<McpToolDescriptor> tools, Collection<McpPromptDescriptor> prompts,
                           Collection<McpResourceDescriptor> resources,
                           Collection<McpResourceTemplateDescriptor> templates) {
        ActionIds ids = new ActionIds();
        List<Item> items = new ArrayList<>();
        for (McpToolDescriptor tool : sorted(tools, McpToolDescriptor::getName)) {
            String title = tool.getTitle();
            items.add(new Item(Kind.TOOL, ids.named("tool.", tool.getName()), tool.getName(),
                    title == null || title.isBlank() ? tool.getName() : title, description(tool.getDescription()),
                    schema(tool), confirmation(tool), Map.copyOf(tool.getHeaderDesignations())));
        }
        for (McpPromptDescriptor prompt : sorted(prompts, McpPromptDescriptor::getName)) {
            items.add(new Item(Kind.PROMPT, ids.named("prompt.", prompt.getName()), prompt.getName(),
                    prompt.getName(), description(prompt.getDescription()), promptSchema(prompt.getArguments()),
                    null, Map.of()));
        }
        for (McpResourceDescriptor resource : sorted(resources, McpResourceDescriptor::getUri)) {
            items.add(new Item(Kind.RESOURCE, ids.hashed("res.", resource.getUri()), resource.getUri(),
                    resource.getUri(), description(resource.getDescription()), null, null, Map.of()));
        }
        for (McpResourceTemplateDescriptor template
                : sorted(templates, McpResourceTemplateDescriptor::getUriTemplate)) {
            items.add(new Item(Kind.TEMPLATE, ids.hashed("tpl.", template.getUriTemplate()),
                    template.getUriTemplate(), template.getUriTemplate(), description(template.getDescription()),
                    templateSchema(template.getVariableNames()), null, Map.of()));
        }
        return new McpCatalogue(items, null);
    }

    /** {@code 5 tools, 1 prompt, 1 resource, 1 resource template}. */
    String summary() {
        return count(Kind.TOOL, "tool") + ", " + count(Kind.PROMPT, "prompt") + ", "
                + count(Kind.RESOURCE, "resource") + ", " + count(Kind.TEMPLATE, "resource template");
    }

    /**
     * The question a tool asks before a call: none when it is {@code readOnlyHint: true} and not
     * {@code destructiveHint: true}. langchain4j-cdi keeps an annotation member only when it differs from its
     * default, so a {@code @Tool} never carries {@code destructiveHint: true}; a descriptor built by hand may.
     */
    static String confirmation(McpToolDescriptor tool) {
        McpToolAnnotationsModel annotations = tool.getAnnotations();
        boolean readOnly = annotations != null && Boolean.TRUE.equals(annotations.readOnlyHint());
        boolean destructive = annotations != null && Boolean.TRUE.equals(annotations.destructiveHint());
        return readOnly && !destructive ? null : CONFIRMATION.formatted(tool.getName());
    }

    private String count(Kind kind, String noun) {
        long n = items.stream().filter(item -> item.kind() == kind).count();
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    /** The modern era's input schema, else the legacy one; an open object when missing or too large. */
    private static String schema(McpToolDescriptor tool) {
        JsonObject schema = tool.getModernInputSchema() != null ? tool.getModernInputSchema() : tool.getInputSchema();
        String text = schema == null ? OPEN_SCHEMA : schema.toString();
        return text.length() > PanelAction.Argument.MAX_SCHEMA ? OPEN_SCHEMA : text;
    }

    /** A flat schema: a {@code string} per argument, {@code required} as declared. */
    private static String promptSchema(List<McpPromptDescriptor.PromptArgument> arguments) {
        JsonObjectBuilder properties = Json.createObjectBuilder();
        JsonArrayBuilder required = Json.createArrayBuilder();
        for (McpPromptDescriptor.PromptArgument argument : arguments == null
                ? List.<McpPromptDescriptor.PromptArgument>of() : arguments) {
            JsonObjectBuilder property = Json.createObjectBuilder().add("type", "string");
            if (argument.description() != null && !argument.description().isBlank()) {
                property.add("description", argument.description());
            }
            properties.add(argument.name(), property);
            if (argument.required()) {
                required.add(argument.name());
            }
        }
        return Json.createObjectBuilder().add("type", "object").add("properties", properties)
                .add("required", required).build().toString();
    }

    /** A flat schema: a required {@code string} per variable. */
    private static String templateSchema(List<String> variables) {
        JsonObjectBuilder properties = Json.createObjectBuilder();
        JsonArrayBuilder required = Json.createArrayBuilder();
        for (String variable : variables) {
            properties.add(variable, Json.createObjectBuilder().add("type", "string"));
            required.add(variable);
        }
        return Json.createObjectBuilder().add("type", "object").add("properties", properties)
                .add("required", required).build().toString();
    }

    /** A description, stripped, cut to {@link PanelAction#MAX_DESCRIPTION} characters; {@code null} when blank. */
    private static String description(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String stripped = text.strip();
        return stripped.length() <= PanelAction.MAX_DESCRIPTION ? stripped
                : stripped.substring(0, PanelAction.MAX_DESCRIPTION - 3) + "...";
    }

    private static <T> List<T> sorted(Collection<T> items, Function<T, String> key) {
        return items.stream().sorted(Comparator.comparing(key, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    /** The one bean of {@code type}, when it is that very class: the twin guard of {@code McpInspection}. */
    private static Bean<?> bean(BeanManager beans, Class<?> type) {
        Bean<?> bean = beans.resolve(beans.getBeans(type));
        return bean != null && bean.getBeanClass() == type ? bean : null;
    }

    /** The instance {@code bean} already has, or {@code null}: the one-argument {@code Context.get} creates none. */
    private static <T> T instance(BeanManager beans, Bean<?> bean, Class<T> type) {
        if (bean == null) {
            return null;
        }
        Object existing = beans.getContext(bean.getScope()).get(bean);
        return type.isInstance(existing) ? type.cast(existing) : null;
    }
}
```

- [ ] **Step 5: Run them to see them pass**

Run: `mvn -o -q -pl $MCP install -DskipTests`, then `mvn -o -q -pl $DEV test`
Expected: `BUILD SUCCESS`; `ActionIdsTest`, `McpCatalogueTest` and the existing `McpLivePanelTest` pass.

- [ ] **Step 6: Commit** — `feat(mcp-dev): the MCP inspector's catalogue, action ids and confirmation rule`.
  Files: `DEV_SRC/ActionIds.java`, `DEV_SRC/McpCatalogue.java`, `$DEV/src/main/java/module-info.java`,
  `$DEV/pom.xml`, `DEV_TEST/InspectorFixtures.java`, `DEV_TEST/ActionIdsTest.java`,
  `DEV_TEST/McpCatalogueTest.java`.

---

### Task 7: The MCP client

**Files:**
- Create: `DEV_SRC/McpClient.java`, `DEV_SRC/McpTransportException.java`, `DEV_SRC/UriTemplates.java`,
  `DEV_TEST/StubMcp.java`, `DEV_TEST/McpClientTest.java`, `DEV_TEST/UriTemplatesTest.java`

**Interfaces:**
- Produces:
  - `final class McpClient`: `McpClient(URI endpoint, Duration timeout)`; `URI endpoint()`;
    `JsonObject request(String method, JsonObject params)`;
    `Exchange send(JsonObject request, String name, Map<String, String> paramHeaders) throws McpTransportException`;
    `static Map<String, String> paramHeaders(Map<String, String> designations, JsonObject arguments)`;
    `static String headerValue(String)`; `static List<JsonObject> events(String)`;
    `record Exchange(JsonObject request, JsonObject response, List<JsonObject> events, int status, long millis)`;
    constants `PROTOCOL`, `CLIENT_NAME`;
  - `final class McpTransportException extends Exception` whose message is the summary line;
  - `final class UriTemplates`: `static String expand(String template, JsonObject values)`,
    `static String encode(String)`;
  - test fixture `StubMcp` (`start()`, `uri()`, `respond(Function<JsonObject, Answer>)`, `received()`,
    `rejected()`, `result(...)`, `error(...)`, `close()`).

- [ ] **Step 1: Write the failing tests**

`DEV_TEST/StubMcp.java`:

```java
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.cdi.mcp.server.transport.McpEraDetector;
import dev.langchain4j.cdi.mcp.server.transport.McpJsonRpcParser;
import jakarta.json.Json;
import jakarta.json.JsonObject;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * A stand-in for the application's {@code /mcp}, on the loopback and an ephemeral port: it records every request,
 * checks its headers and {@code _meta} with langchain4j-cdi's own {@link McpEraDetector}, as the real endpoint does
 * before anything else, and answers what the test asked for.
 */
final class StubMcp implements AutoCloseable {

    /** What the stub answers. */
    record Answer(int status, String contentType, String body, long delayMillis) {}

    /** A request as it arrived: its headers, case-insensitive, and its body. */
    record Received(Map<String, String> headers, String body) {}

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Function<JsonObject, Answer> responder = request -> result(request, "{}");
    private volatile String rejected;

    private StubMcp(HttpServer server) {
        this.server = server;
    }

    static StubMcp start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        StubMcp stub = new StubMcp(server);
        server.createContext("/mcp", stub::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return stub;
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    }

    void respond(Function<JsonObject, Answer> next) {
        responder = next;
    }

    List<Received> received() {
        return List.copyOf(received);
    }

    /** Why {@code McpEraDetector} refused the last request, or {@code null} when it accepted every one. */
    String rejected() {
        return rejected;
    }

    /** A JSON answer holding {@code result} for {@code request}. */
    static Answer result(JsonObject request, String result) {
        return new Answer(200, "application/json",
                "{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id") + ",\"result\":" + result + "}", 0);
    }

    /** A JSON-RPC error for {@code request}, with the HTTP status the real endpoint uses for it. */
    static Answer error(JsonObject request, int status, int code, String message) {
        return new Answer(status, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id")
                + ",\"error\":{\"code\":" + code + ",\"message\":" + Json.createValue(message) + "}}", 0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, values.getFirst()));
        received.add(new Received(headers, body));
        JsonObject request = Json.createReader(new StringReader(body)).readObject();
        try {
            McpEraDetector.detect(McpJsonRpcParser.parseRequest(request), headers::get);
        } catch (RuntimeException refused) {
            rejected = refused.getClass().getSimpleName() + ": " + refused.getMessage();
        }
        Answer answer = responder.apply(request);
        if (answer.delayMillis() > 0) {
            try {
                Thread.sleep(answer.delayMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", answer.contentType());
        exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
```

`DEV_TEST/McpClientTest.java`:

```java
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One stateless modern-era POST per call, as langchain4j-cdi's endpoint expects it. */
class McpClientTest {

    private StubMcp stub;
    private McpClient client;

    @BeforeEach
    void start() throws Exception {
        stub = StubMcp.start();
        client = new McpClient(stub.uri(), Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private static JsonObject object(String json) {
        return Json.createReader(new StringReader(json)).readObject();
    }

    private McpClient.Exchange callTool(String name) throws McpTransportException {
        JsonObject request = client.request("tools/call", Json.createObjectBuilder().add("name", name)
                .add("arguments", object("{\"zone\":\"Europe/Paris\"}")).build());
        return client.send(request, name, Map.of());
    }

    @Test
    void aCallIsOneModernPostTheServerAcceptsWithoutSessionOrOrigin() throws Exception {
        stub.respond(request -> StubMcp.result(request, "{\"content\":[{\"type\":\"text\",\"text\":\"12:00\"}]}"));

        McpClient.Exchange exchange = callTool("current_time");

        assertNull(stub.rejected(), "McpEraDetector refused the request");
        StubMcp.Received received = stub.received().getFirst();
        assertEquals("2026-07-28", received.headers().get("MCP-Protocol-Version"));
        assertEquals("tools/call", received.headers().get("Mcp-Method"));
        assertEquals("current_time", received.headers().get("Mcp-Name"));
        assertEquals("application/json, text/event-stream", received.headers().get("Accept"));
        assertTrue(received.headers().get("Content-Type").startsWith("application/json"));
        assertFalse(received.headers().containsKey("Origin"), "no Origin: a client that is no browser");
        assertFalse(received.headers().containsKey("Mcp-Session-Id"), "stateless: no session");
        JsonObject meta = object(received.body()).getJsonObject("params").getJsonObject("_meta");
        assertEquals("2026-07-28", meta.getString("io.modelcontextprotocol/protocolVersion"));
        assertEquals("vidocq-dev-console",
                meta.getJsonObject("io.modelcontextprotocol/clientInfo").getString("name"));
        assertEquals(object("{\"elicitation\":{},\"sampling\":{},\"roots\":{}}"),
                meta.getJsonObject("io.modelcontextprotocol/clientCapabilities"));
        assertEquals("12:00", exchange.response().getJsonObject("result").getJsonArray("content")
                .getJsonObject(0).getString("text"));
        assertEquals(200, exchange.status());
        assertTrue(exchange.events().isEmpty());
    }

    @Test
    void aNameThatIsNotPlainAsciiTravelsInBase64() throws Exception {
        callTool("météo");

        assertNull(stub.rejected(), "McpEraDetector refused the request");
        assertEquals("=?base64?" + Base64.getEncoder().encodeToString("météo".getBytes(StandardCharsets.UTF_8))
                + "?=", stub.received().getFirst().headers().get("Mcp-Name"));
        assertEquals("plain", McpClient.headerValue("plain"));
        assertTrue(McpClient.headerValue(" padded").startsWith("=?base64?"), "HTTP would strip the space");
    }

    @Test
    void aDesignatedArgumentIsMirroredIntoItsHeader() throws Exception {
        JsonObject arguments = object("{\"tenant\":\"acme\",\"count\":3,\"flag\":true,\"nested\":{},\"none\":null}");

        Map<String, String> headers = McpClient.paramHeaders(Map.of("tenant", "Tenant-Id", "count", "Count",
                "flag", "Flag", "nested", "Nested", "none", "None", "missing", "Missing"), arguments);

        assertEquals(Map.of("Tenant-Id", "acme", "Count", "3", "Flag", "true"), headers);
        JsonObject request = client.request("tools/call", Json.createObjectBuilder().add("name", "t")
                .add("arguments", arguments).build());
        client.send(request, "t", headers);
        assertEquals("acme", stub.received().getFirst().headers().get("Mcp-Param-Tenant-Id"));
    }

    @Test
    void anSseAnswerKeepsTheFinalResponseAndTheEventsBeforeIt() throws Exception {
        stub.respond(request -> new StubMcp.Answer(200, "text/event-stream",
                "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n"
                        + "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":" + request.get("id")
                        + ",\"result\":{\"content\":[]}}\n\n", 0));

        McpClient.Exchange exchange = callTool("current_time");

        assertEquals(2, exchange.events().size());
        assertTrue(exchange.response().containsKey("result"), exchange.response().toString());
    }

    @Test
    void aJsonRpcErrorOnA400IsStillAnAnswer() throws Exception {
        stub.respond(request -> StubMcp.error(request, 400, -32602, "Invalid params: zone"));

        McpClient.Exchange exchange = callTool("current_time");

        assertEquals(400, exchange.status());
        assertEquals(-32602, exchange.response().getJsonObject("error").getInt("code"));
    }

    @Test
    void anHttpStatusWithoutAJsonRpcBodyIsATransportFailure() {
        stub.respond(request -> new StubMcp.Answer(502, "text/plain", "Bad gateway", 0));

        McpTransportException failure = assertThrows(McpTransportException.class, () -> callTool("current_time"));

        assertEquals("HTTP 502", failure.getMessage());
    }

    @Test
    void aClosedPortIsUnreachable() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        URI closed = URI.create("http://127.0.0.1:" + port + "/mcp");
        McpClient nowhere = new McpClient(closed, Duration.ofSeconds(5));
        JsonObject request = nowhere.request("tools/call", Json.createObjectBuilder().add("name", "t")
                .add("arguments", Json.createObjectBuilder()).build());

        McpTransportException failure = assertThrows(McpTransportException.class,
                () -> nowhere.send(request, "t", Map.of()));

        assertEquals("/mcp unreachable at " + closed, failure.getMessage());
    }

    @Test
    void aSlowAnswerTimesOut() {
        client = new McpClient(stub.uri(), Duration.ofSeconds(1));
        stub.respond(request -> new StubMcp.Answer(200, "application/json", "{}", 2_500));

        McpTransportException failure = assertThrows(McpTransportException.class, () -> callTool("current_time"));

        assertEquals("timed out after 1 s", failure.getMessage());
    }
}
```

`DEV_TEST/UriTemplatesTest.java`:

```java
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import jakarta.json.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** RFC 6570 level 1, which langchain4j-cdi's template matcher decodes. */
class UriTemplatesTest {

    @Test
    void aVariableIsPercentEncodedButItsUnreservedCharacters() {
        assertEquals("time://zone/Europe%2FParis", UriTemplates.expand("time://zone/{zone}",
                Json.createObjectBuilder().add("zone", "Europe/Paris").build()));
        assertEquals("q/a%20b-c.d_e~f%C3%A9", UriTemplates.expand("q/{q}",
                Json.createObjectBuilder().add("q", "a b-c.d_e~fé").build()));
    }

    @Test
    void aMissingVariableIsEmptyAndANumberIsItsText() {
        assertEquals("a//7", UriTemplates.expand("a/{x}/{n}", Json.createObjectBuilder().add("n", 7).build()));
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -o -q -pl $DEV test -Dtest='McpClientTest,UriTemplatesTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAILURE, `cannot find symbol: class McpClient`.

- [ ] **Step 3: Write the implementation**

`DEV_SRC/McpTransportException.java`:

```java
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

/**
 * A call that never got a JSON-RPC answer: {@code /mcp} unreachable, too slow, or an HTTP status without a JSON-RPC
 * body. Its message is the summary line the page shows (spec §3.3); it carries no cause and no stack trace.
 */
final class McpTransportException extends Exception {

    private static final long serialVersionUID = 1L;

    McpTransportException(String summary) {
        super(summary, null, false, false);
    }
}
```

`DEV_SRC/UriTemplates.java`:

```java
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands a resource template's URI for a read (RFC 6570, level 1): each {@code {name}} replaced by its value, every
 * byte of its UTF-8 form but the unreserved characters percent-encoded, which langchain4j-cdi's matcher decodes.
 */
final class UriTemplates {

    private static final Pattern VARIABLE = Pattern.compile("\\{([^}]+)}");
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private UriTemplates() {}

    /**
     * @param template the URI template, such as {@code time://zone/{zone}}
     * @param values   the variables by name; a missing one expands to nothing, a non-string one to its JSON text
     * @return the URI
     */
    static String expand(String template, jakarta.json.JsonObject values) {
        Matcher variable = VARIABLE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (variable.find()) {
            JsonValue value = values.get(variable.group(1));
            String text = value == null || value.getValueType() == JsonValue.ValueType.NULL ? ""
                    : value instanceof JsonString string ? string.getString() : value.toString();
            variable.appendReplacement(out, Matcher.quoteReplacement(encode(text)));
        }
        variable.appendTail(out);
        return out.toString();
    }

    /** {@code text} with every byte of its UTF-8 form but {@code A-Z a-z 0-9 - . _ ~} percent-encoded. */
    static String encode(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~';
            if (unreserved) {
                out.append((char) c);
            } else {
                out.append('%').append(HEX[c >> 4]).append(HEX[c & 0xf]);
            }
        }
        return out.toString();
    }
}
```

`DEV_SRC/McpClient.java`:

```java
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import dev.langchain4j.cdi.mcp.server.protocol.McpHttpHeaders;
import dev.langchain4j.cdi.mcp.server.protocol.McpMetaKeys;
import dev.langchain4j.cdi.mcp.server.protocol.McpProtocolVersions;
import dev.langchain4j.cdi.mcp.server.transport.McpParamHeaderValidator;
import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The MCP inspector's client (spec §3.2): one stateless {@code POST} per call to the application's own {@code /mcp},
 * in protocol {@value #PROTOCOL}. No {@code initialize} and no session, so that {@code McpSessionManager} and its
 * cleanup thread are never created; no {@code Origin}, which langchain4j-cdi's origin check lets through as a client
 * that is no browser.
 *
 * <p>Each request carries what {@code McpEraDetector} requires of the modern era: the protocol version in the
 * {@code MCP-Protocol-Version} header and in {@code _meta}, the method in {@code Mcp-Method}, the name or URI in
 * {@code Mcp-Name}, Base64-wrapped when it is not plain ASCII, the client's capabilities, and for a tool the
 * {@code Mcp-Param-*} headers of its designated arguments. It declares elicitation, sampling and roots, so that a
 * tool that needs them answers {@code input_required}, which the inspector reports, rather than a missing
 * capability. It accepts JSON and SSE; from a stream it keeps the final response and the events before it.
 */
final class McpClient {

    /** The stateless era of MCP. */
    static final String PROTOCOL = McpProtocolVersions.MODERN_2026_07_28;
    /** What the inspector calls itself in {@code clientInfo}. */
    static final String CLIENT_NAME = "vidocq-dev-console";

    private static final String BASE64_PREFIX = "=?base64?";
    private static final String BASE64_SUFFIX = "?=";

    /**
     * One call.
     *
     * @param request  the JSON-RPC request sent
     * @param response the final JSON-RPC response, with {@code result} or {@code error}
     * @param events   the events of an SSE answer, the response included; empty for a JSON answer
     * @param status   the HTTP status
     * @param millis   how long the call took
     */
    record Exchange(JsonObject request, JsonObject response, List<JsonObject> events, int status, long millis) {}

    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient http;
    private final AtomicLong ids = new AtomicLong();

    /**
     * @param endpoint the absolute URL of {@code /mcp}
     * @param timeout  how long a call may take, under the console's 60 s action limit
     */
    McpClient(URI endpoint, Duration timeout) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** The URL of {@code /mcp} this client calls. */
    URI endpoint() {
        return endpoint;
    }

    /** A JSON-RPC request of {@code method} with a new id, and {@code params} with the {@code _meta} of the era. */
    JsonObject request(String method, JsonObject params) {
        return Json.createObjectBuilder()
                .add("jsonrpc", "2.0")
                .add("id", ids.incrementAndGet())
                .add("method", method)
                .add("params", Json.createObjectBuilder(params).add(McpMetaKeys.META, meta()))
                .build();
    }

    /**
     * Sends {@code request} and waits for its answer.
     *
     * @param request      a request of {@link #request}
     * @param name         the tool or prompt name, or the resource URI, for {@code Mcp-Name}
     * @param paramHeaders the {@code Mcp-Param-*} headers, by designation, from {@link #paramHeaders}
     * @return the exchange
     * @throws McpTransportException when no JSON-RPC answer came back
     */
    Exchange send(JsonObject request, String name, Map<String, String> paramHeaders) throws McpTransportException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header(McpHttpHeaders.PROTOCOL_VERSION, PROTOCOL)
                .header(McpHttpHeaders.METHOD, request.getString("method"))
                .header(McpHttpHeaders.NAME, headerValue(name))
                .POST(HttpRequest.BodyPublishers.ofString(request.toString(), StandardCharsets.UTF_8));
        paramHeaders.forEach((designation, value) ->
                builder.header(McpParamHeaderValidator.HEADER_PREFIX + designation, headerValue(value)));
        long start = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException unreachable) {
            throw new McpTransportException("/mcp unreachable at " + endpoint);
        } catch (HttpTimeoutException slow) {
            throw new McpTransportException("timed out after " + timeout.toSeconds() + " s");
        } catch (IOException unreachable) {
            throw new McpTransportException("/mcp unreachable at " + endpoint);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new McpTransportException("interrupted while waiting for /mcp");
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        String type = response.headers().firstValue("Content-Type").orElse("");
        List<JsonObject> events = List.of();
        JsonObject answer;
        if (type.regionMatches(true, 0, "text/event-stream", 0, "text/event-stream".length())) {
            events = events(response.body());
            answer = finalResponse(events, request.get("id"));
        } else {
            answer = object(response.body());
        }
        if (answer == null || !(answer.containsKey("result") || answer.containsKey("error"))) {
            throw new McpTransportException("HTTP " + response.statusCode());
        }
        return new Exchange(request, answer, events, response.statusCode(), millis);
    }

    /**
     * The {@code Mcp-Param-*} headers a tool's designated arguments need (SEP-2243): a string as itself, an integer
     * as its decimal text, a boolean as {@code true} or {@code false}; a {@code null}, absent or non-primitive value
     * is left out, as langchain4j-cdi expects.
     *
     * @param designations the tool's designations, argument name to header suffix
     * @param arguments    the call's arguments
     * @return the header values by suffix
     */
    static Map<String, String> paramHeaders(Map<String, String> designations, JsonObject arguments) {
        Map<String, String> headers = new LinkedHashMap<>();
        designations.forEach((argument, designation) -> {
            JsonValue value = arguments.get(argument);
            String mirrored = null;
            if (value instanceof JsonString string) {
                mirrored = string.getString();
            } else if (value instanceof JsonNumber number) {
                mirrored = number.isIntegral() ? number.bigIntegerValue().toString() : number.toString();
            } else if (value != null && value.getValueType() == JsonValue.ValueType.TRUE) {
                mirrored = "true";
            } else if (value != null && value.getValueType() == JsonValue.ValueType.FALSE) {
                mirrored = "false";
            }
            if (mirrored != null) {
                headers.put(designation, mirrored);
            }
        });
        return headers;
    }

    /**
     * {@code value} as a header carries it: as it is when it is printable ASCII without a space at either end,
     * {@code =?base64?…?=} otherwise, which langchain4j-cdi decodes.
     */
    static String headerValue(String value) {
        boolean plain = !value.startsWith(BASE64_PREFIX) && value.equals(value.strip());
        for (int i = 0; plain && i < value.length(); i++) {
            char c = value.charAt(i);
            plain = c >= 0x20 && c <= 0x7e;
        }
        return plain ? value : BASE64_PREFIX
                + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + BASE64_SUFFIX;
    }

    /** The JSON objects the {@code data} lines of an SSE body carry, one per event, in order. */
    static List<JsonObject> events(String body) {
        List<JsonObject> events = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\r?\n", -1)) {
            if (line.isEmpty()) {
                flush(data, events);
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(line.startsWith("data: ") ? line.substring(6) : line.substring(5));
            }
        }
        flush(data, events);
        return List.copyOf(events);
    }

    private static void flush(StringBuilder data, List<JsonObject> events) {
        if (data.isEmpty()) {
            return;
        }
        JsonObject event = object(data.toString());
        if (event != null) {
            events.add(event);
        }
        data.setLength(0);
    }

    /** The last event that answers {@code id}: the final response; notifications before it are left out. */
    private static JsonObject finalResponse(List<JsonObject> events, JsonValue id) {
        JsonObject last = null;
        for (JsonObject event : events) {
            if (id.equals(event.get("id")) && (event.containsKey("result") || event.containsKey("error"))) {
                last = event;
            }
        }
        return last;
    }

    /** {@code text} as a JSON object, or {@code null} when it is none. */
    private static JsonObject object(String text) {
        try (JsonReader reader = Json.createReader(new StringReader(text))) {
            return reader.readObject();
        } catch (JsonException | IllegalStateException notAnObject) {
            return null;
        }
    }

    private static JsonObject meta() {
        return Json.createObjectBuilder()
                .add(McpMetaKeys.PROTOCOL_VERSION, PROTOCOL)
                .add(McpMetaKeys.CLIENT_INFO, Json.createObjectBuilder().add("name", CLIENT_NAME).add("version", "1"))
                .add(McpMetaKeys.CLIENT_CAPABILITIES, Json.createObjectBuilder()
                        .add("elicitation", JsonValue.EMPTY_JSON_OBJECT)
                        .add("sampling", JsonValue.EMPTY_JSON_OBJECT)
                        .add("roots", JsonValue.EMPTY_JSON_OBJECT))
                .build();
    }
}
```

- [ ] **Step 4: Run them to see them pass**

Run: `mvn -o -q -pl $DEV test -Dtest='McpClientTest,UriTemplatesTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `BUILD SUCCESS`; every test passes, `stub.rejected()` staying `null` (langchain4j-cdi's own detector
accepted each request).

- [ ] **Step 5: Commit** — `feat(mcp-dev): a stateless MCP client for the inspector`.
  Files: `DEV_SRC/McpClient.java`, `DEV_SRC/McpTransportException.java`, `DEV_SRC/UriTemplates.java`,
  `DEV_TEST/StubMcp.java`, `DEV_TEST/McpClientTest.java`, `DEV_TEST/UriTemplatesTest.java`.

---

### Task 8: The inspector's actions, their results, and the panel

**Files:**
- Create: `DEV_SRC/McpResults.java`, `DEV_SRC/McpInspector.java`, `DEV_TEST/McpResultsTest.java`,
  `DEV_TEST/McpInspectorTest.java`
- Modify: `DEV_SRC/McpLivePanel.java`, `DEV_TEST/McpLivePanelTest.java`

**Interfaces:**
- Consumes: `McpCatalogue`, `McpCatalogue.Kind`, `McpCatalogue.Item` (Task 6); `McpClient`, `McpClient.Exchange`,
  `McpTransportException`, `UriTemplates` (Task 7); `McpEndpointLive.urls()` (Task 5); `PanelAction`
  (7-argument constructor), `PanelAction.Argument.json`, `PanelAction.ActionResult` (Tasks 1-2).
- Produces:
  - `final class McpResults`: `static ActionResult of(McpCatalogue.Kind kind, McpClient.Exchange exchange,
    String details)`, `static ActionResult transport(String summary, String details)`, constant `UNSUPPORTED`;
  - `final class McpInspector`: `static final McpInspector OFF`; `NO_ADDRESS`, `TWIN`;
    `static McpInspector start(BeanManager, McpInspection, List<String> urls, Duration timeout)`;
    `static McpInspector of(McpCatalogue, URI endpoint, Duration timeout)`; `static Optional<URI>
    preferred(List<String>)`; `List<PanelAction> actions()`; `void sample(PanelSample)`;
    `PanelAction.ActionResult call(McpCatalogue.Item, Map<String, String>)`;
  - `McpLivePanel.CALL_TIMEOUT = Duration.ofSeconds(55)`; `McpLivePanel.actions()`; the sample key `inspector`.

- [ ] **Step 1: Write the failing tests**

`DEV_TEST/McpResultsTest.java`:

```java
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec §3.3, one outcome per test. */
class McpResultsTest {

    private static final JsonObject REQUEST = object("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"}");

    private static JsonObject object(String json) {
        return Json.createReader(new StringReader(json)).readObject();
    }

    private static McpClient.Exchange answered(String response) {
        return new McpClient.Exchange(REQUEST, object(response), List.of(), 200, 12);
    }

    private static ActionResult of(McpCatalogue.Kind kind, String result) {
        return McpResults.of(kind, answered("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + result + "}"), "{}");
    }

    @Test
    void aToolResultShowsItsTextItemsJoined() {
        ActionResult result = of(McpCatalogue.Kind.TOOL, "{\"content\":[{\"type\":\"text\",\"text\":\"a\"},"
                + "{\"type\":\"image\",\"data\":\"x\"},{\"type\":\"text\",\"text\":\"b\"}],\"isError\":false}");

        assertEquals("ok in 12 ms", result.summary());
        assertEquals("text/plain", result.contentType());
        assertEquals("a\nb", result.body());
        assertFalse(result.error());
        assertEquals("{}", result.details());
    }

    @Test
    void aToolResultWithStructuredContentShowsItsJson() {
        ActionResult result = of(McpCatalogue.Kind.TOOL,
                "{\"content\":[{\"type\":\"text\",\"text\":\"21\"}],\"structuredContent\":{\"temp\":21}}");

        assertEquals("application/json", result.contentType());
        assertEquals("{\"temp\":21}", result.body());
    }

    @Test
    void aToolResultWithOnlyNonTextContentShowsTheContentAsJson() {
        ActionResult result = of(McpCatalogue.Kind.TOOL, "{\"content\":[{\"type\":\"image\",\"data\":\"x\"}]}");

        assertEquals("application/json", result.contentType());
        assertEquals("[{\"type\":\"image\",\"data\":\"x\"}]", result.body());
    }

    @Test
    void isErrorFlagsTheResult() {
        assertTrue(of(McpCatalogue.Kind.TOOL, "{\"content\":[],\"isError\":true}").error());
    }

    @Test
    void aPromptShowsItsMessages() {
        ActionResult result = of(McpCatalogue.Kind.PROMPT,
                "{\"messages\":[{\"role\":\"user\",\"content\":{\"type\":\"text\",\"text\":\"hi\"}}]}");

        assertEquals("1 message(s) in 12 ms", result.summary());
        assertEquals("application/json", result.contentType());
        assertTrue(result.body().startsWith("[{\"role\":\"user\""), result.body());
    }

    @Test
    void aResourceShowsItsFirstTextElseItsContents() {
        ActionResult text = of(McpCatalogue.Kind.RESOURCE,
                "{\"contents\":[{\"uri\":\"time://utc\",\"text\":\"UTC\"},{\"uri\":\"b\",\"text\":\"B\"}]}");
        ActionResult blob = of(McpCatalogue.Kind.TEMPLATE, "{\"contents\":[{\"uri\":\"x\",\"blob\":\"AA==\"}]}");

        assertEquals("2 content item(s) in 12 ms", text.summary());
        assertEquals("UTC", text.body());
        assertEquals("application/json", blob.contentType());
        assertEquals("[{\"uri\":\"x\",\"blob\":\"AA==\"}]", blob.body());
    }

    @Test
    void aJsonRpcErrorShowsItsCodeAndMessage() {
        ActionResult result = McpResults.of(McpCatalogue.Kind.TOOL,
                answered("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602,\"message\":\"Invalid params\"}}"),
                "{}");

        assertEquals("error -32602: Invalid params", result.summary());
        assertTrue(result.error());
        assertEquals("{\"code\":-32602,\"message\":\"Invalid params\"}", result.body());
    }

    @Test
    void anInputRequestIsRefusedWithItsKinds() {
        ActionResult result = of(McpCatalogue.Kind.TOOL, "{\"resultType\":\"input_required\",\"inputRequests\":{"
                + "\"input-1\":{\"method\":\"elicitation/create\",\"params\":{}},"
                + "\"input-2\":{\"method\":\"sampling/createMessage\",\"params\":{}},"
                + "\"input-3\":{\"method\":\"elicitation/create\",\"params\":{}}},\"requestState\":\"s\"}");

        assertEquals("this tool asks the client for input (elicitation, sampling): not supported by the dev console "
                + "inspector yet", result.summary());
        assertTrue(result.error());
        assertTrue(result.body().contains("\"input-2\""), result.body());
    }

    @Test
    void aTransportFailureHasNoBody() {
        ActionResult result = McpResults.transport("timed out after 55 s", "{\"request\":{}}");

        assertEquals("timed out after 55 s", result.summary());
        assertTrue(result.error());
        assertNull(result.body());
        assertEquals("{\"request\":{}}", result.details());
    }
}
```

`DEV_TEST/McpInspectorTest.java`:

```java
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import io.vidocq.runtime.spi.devconsole.PanelAction;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The inspector's actions, called against a stub {@code /mcp} that checks each request as the server does. */
class McpInspectorTest {

    private StubMcp stub;

    @BeforeEach
    void start() throws Exception {
        stub = StubMcp.start();
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private static JsonObject object(String json) {
        return Json.createReader(new StringReader(json)).readObject();
    }

    private McpInspector inspector() {
        McpCatalogue catalogue = McpCatalogue.of(List.of(InspectorFixtures.tool("currentTime")),
                List.of(InspectorFixtures.prompt("planMeeting")), List.of(InspectorFixtures.resource("utc")),
                List.of(InspectorFixtures.template("timeInZone")));
        return McpInspector.of(catalogue, stub.uri(), Duration.ofSeconds(5));
    }

    private static PanelAction action(McpInspector inspector, String id) {
        return inspector.actions().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void eachItemIsAnActionOfItsGroupWithItsJsonArgument() {
        McpInspector inspector = inspector();

        assertEquals(List.of("tool.current-time", "prompt.plan-meeting", "res.860cd4a5", "tpl.72bba68d"),
                inspector.actions().stream().map(PanelAction::id).toList());
        PanelAction tool = action(inspector, "tool.current-time");
        assertEquals("Tools", tool.group());
        assertEquals("Current time", tool.label());
        assertEquals("The current time in a zone.", tool.description());
        assertNull(tool.confirmation(), "read-only");
        assertEquals("arguments", tool.arguments().getFirst().name());
        assertTrue(tool.arguments().getFirst().schema().contains("\"zone\""));
        assertEquals(List.of(), action(inspector, "res.860cd4a5").arguments(), "a fixed resource takes none");
        assertEquals("variables", action(inspector, "tpl.72bba68d").arguments().getFirst().name());
        assertEquals("Resources", action(inspector, "tpl.72bba68d").group());
    }

    @Test
    void aToolCallThatSucceedsShowsItsTextAndTheExchange() {
        stub.respond(request -> StubMcp.result(request,
                "{\"content\":[{\"type\":\"text\",\"text\":\"12:00 Paris\"}],\"isError\":false}"));

        PanelAction.ActionResult result = action(inspector(), "tool.current-time").call()
                .apply(Map.of("arguments", "{\"zone\":\"Europe/Paris\"}"));

        assertNull(stub.rejected(), "McpEraDetector refused the request");
        assertTrue(result.summary().matches("ok in [0-9]+ ms"), result.summary());
        assertEquals("12:00 Paris", result.body());
        assertFalse(result.error());
        JsonObject details = object(result.details());
        assertEquals("tools/call", details.getJsonObject("request").getString("method"));
        assertEquals(object("{\"zone\":\"Europe/Paris\"}"),
                details.getJsonObject("request").getJsonObject("params").getJsonObject("arguments"));
        assertEquals(200, details.getInt("status"));
        assertTrue(details.getJsonObject("response").containsKey("result"));
    }

    @Test
    void aToolWithIsErrorAJsonRpcErrorAndAnInputRequestAreErrors() {
        PanelAction tool = action(inspector(), "tool.current-time");
        Map<String, String> paris = Map.of("arguments", "{\"zone\":\"Europe/Paris\"}");

        stub.respond(request -> StubMcp.result(request, "{\"content\":[],\"isError\":true}"));
        assertTrue(tool.call().apply(paris).error());

        stub.respond(request -> StubMcp.error(request, 400, -32602, "Invalid params: zone"));
        assertEquals("error -32602: Invalid params: zone", tool.call().apply(paris).summary());

        stub.respond(request -> StubMcp.result(request, "{\"resultType\":\"input_required\",\"inputRequests\":"
                + "{\"input-1\":{\"method\":\"roots/list\",\"params\":{}}},\"requestState\":\"s\"}"));
        assertEquals("this tool asks the client for input (roots): not supported by the dev console inspector yet",
                tool.call().apply(paris).summary());
    }

    @Test
    void anUnreachableMcpIsATransportLineWithTheRequestInTheDetails() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        URI closed = URI.create("http://127.0.0.1:" + port + "/mcp");
        McpInspector nowhere = McpInspector.of(McpCatalogue.of(List.of(InspectorFixtures.tool("currentTime")),
                List.of(), List.of(), List.of()), closed, Duration.ofSeconds(5));

        PanelAction.ActionResult result = action(nowhere, "tool.current-time").call()
                .apply(Map.of("arguments", "{\"zone\":\"UTC\"}"));

        assertEquals("/mcp unreachable at " + closed, result.summary());
        assertTrue(result.error());
        assertNull(result.body());
        assertTrue(object(result.details()).containsKey("request"));
    }

    @Test
    void aBodyPastTheLimitIsTruncated() {
        stub.respond(request -> StubMcp.result(request,
                "{\"content\":[{\"type\":\"text\",\"text\":\"" + "a".repeat(300_000) + "\"}]}"));

        PanelAction.ActionResult result = action(inspector(), "tool.current-time").call()
                .apply(Map.of("arguments", "{\"zone\":\"UTC\"}"));

        assertEquals(PanelAction.ActionResult.MAX_CONTENT, result.body().length());
        assertTrue(result.body().endsWith("… truncated at 256 KiB"));
    }

    @Test
    void aPromptGetAResourceReadAndATemplateRead() {
        stub.respond(request -> switch (request.getString("method")) {
            case "prompts/get" -> StubMcp.result(request, "{\"messages\":[{\"role\":\"user\","
                    + "\"content\":{\"type\":\"text\",\"text\":\"Plan it\"}}]}");
            default -> StubMcp.result(request, "{\"contents\":[{\"uri\":\"u\",\"text\":\"UTC\"}]}");
        });
        McpInspector inspector = inspector();

        PanelAction.ActionResult prompt = action(inspector, "prompt.plan-meeting").call()
                .apply(Map.of("arguments", "{\"zones\":\"Europe/Paris\"}"));
        PanelAction.ActionResult resource = action(inspector, "res.860cd4a5").call().apply(Map.of());
        PanelAction.ActionResult template = action(inspector, "tpl.72bba68d").call()
                .apply(Map.of("variables", "{\"zone\":\"Europe/Paris\"}"));

        assertNull(stub.rejected(), "McpEraDetector refused a request");
        assertTrue(prompt.summary().matches("1 message\\(s\\) in [0-9]+ ms"), prompt.summary());
        assertTrue(resource.summary().matches("1 content item\\(s\\) in [0-9]+ ms"), resource.summary());
        assertEquals("UTC", template.body());
        List<StubMcp.Received> received = stub.received();
        assertEquals("plan_meeting", received.get(0).headers().get("Mcp-Name"));
        assertEquals("time://utc", received.get(1).headers().get("Mcp-Name"));
        assertEquals("time://zone/Europe%2FParis", received.get(2).headers().get("Mcp-Name"));
        assertEquals("time://zone/Europe%2FParis",
                object(received.get(2).body()).getJsonObject("params").getString("uri"));
    }

    @Test
    void aLoopbackUrlIsPreferredAndNoneMeansNoAddress() {
        assertEquals(Optional.of(URI.create("http://127.0.0.1:18090/mcp")),
                McpInspector.preferred(List.of("http://192.168.1.5:18090/mcp", "http://127.0.0.1:18090/mcp")));
        assertEquals(Optional.of(URI.create("http://192.168.1.5:18090/mcp")),
                McpInspector.preferred(List.of("http://192.168.1.5:18090/mcp")));
        assertEquals(Optional.empty(), McpInspector.preferred(List.of()));
    }
}
```

Add to `DEV_TEST/McpLivePanelTest.java` (with `import
io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.live.McpEndpointLive;` and `import
io.vidocq.runtime.spi.devconsole.PanelAction;`):

- a constant `private static final List<String> PANEL_KEYS = List.of("sessions", "streams", "listens", "pending",
  "invoker.methods", "invoker.matches", "invoker.misses", "inspector");`;
- in `beforeAnyRequestSessionsIsAbsentWithItsReason`, replace `assertEquals(ALL_KEYS, List.copyOf(sample.keys()));`
  with `assertEquals(PANEL_KEYS, List.copyOf(sample.keys()));` and add
  `assertEquals("no MCP server in this container", sample.text("inspector"));` (the container holds no registry);
- in `stop()` (the `@AfterEach`), add `McpEndpointLive.clear();`;
- new tests:

```java
    @Test
    void aStartedInspectorOffersAnActionPerItemAndSaysWhereItCalls() {
        McpEndpointLive.publish(List.of("http://127.0.0.1:18097/mcp"));
        try (VaubanContainer container = McpTestContainers.container(InspectorFixtures.CATALOGUE_BEANS)) {
            InspectorFixtures.fill(container.getBeanManager());
            panel.start(new FakeExtensionContext(container));
            RecordingSample sample = new RecordingSample();

            panel.sample(sample);

            assertEquals(8, panel.actions().size());
            assertEquals("tool.a-tool-whose-name-is-far-longer-tha", panel.actions().getFirst().id());
            assertEquals("5 tools, 1 prompt, 1 resource, 1 resource template at http://127.0.0.1:18097/mcp",
                    sample.text("inspector"));
            assertEquals("gauge", sample.kind("streams"), "the live values keep working");
        }
    }

    @Test
    void withoutABoundAddressThePanelOffersNoActionAndSaysWhy() {
        try (VaubanContainer container = McpTestContainers.container(InspectorFixtures.CATALOGUE_BEANS)) {
            InspectorFixtures.fill(container.getBeanManager());
            panel.start(new FakeExtensionContext(container));
            RecordingSample sample = new RecordingSample();

            panel.sample(sample);

            assertEquals(List.<PanelAction>of(), panel.actions());
            assertEquals("absent", sample.kind("inspector"));
            assertEquals("/mcp has no bound address", sample.text("inspector"));
        }
    }

    @Test
    void stopDropsTheActions() {
        McpEndpointLive.publish(List.of("http://127.0.0.1:18097/mcp"));
        try (VaubanContainer container = McpTestContainers.container(InspectorFixtures.CATALOGUE_BEANS)) {
            InspectorFixtures.fill(container.getBeanManager());
            panel.start(new FakeExtensionContext(container));
            assertFalse(panel.actions().isEmpty());

            panel.stop();

            assertEquals(List.<PanelAction>of(), panel.actions());
        }
    }
```

(`streams` is a gauge here: registering a tool asked the broadcaster how many streams it holds, which created it.)

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -o -q -pl $DEV test -Dtest='McpResultsTest,McpInspectorTest,McpLivePanelTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAILURE, `cannot find symbol: class McpResults`.

- [ ] **Step 3: Write the implementation**

`DEV_SRC/McpResults.java`:

```java
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** What the page shows of an MCP answer (spec §3.3): a summary line, a body, and whether it is an error. */
final class McpResults {

    /** The refusal of a tool that asks the client for input, with the kinds it asks for. */
    static final String UNSUPPORTED =
            "this tool asks the client for input (%s): not supported by the dev console inspector yet";

    private McpResults() {}

    /**
     * The result of a call that got a JSON-RPC answer.
     *
     * @param kind     what was called
     * @param exchange the call
     * @param details  the JSON of the exchange, as the page shows it under "Exchange"
     * @return the result
     */
    static ActionResult of(McpCatalogue.Kind kind, McpClient.Exchange exchange, String details) {
        JsonObject response = exchange.response();
        long ms = exchange.millis();
        if (response.get("error") instanceof JsonObject error) {
            return new ActionResult("error " + error.get("code") + ": " + error.getString("message", ""),
                    ActionResult.JSON, error.toString(), true, details);
        }
        JsonObject result = response.get("result") instanceof JsonObject object ? object
                : JsonValue.EMPTY_JSON_OBJECT;
        if ("input_required".equals(result.getString("resultType", null))) {
            JsonObject requests = result.get("inputRequests") instanceof JsonObject object ? object
                    : JsonValue.EMPTY_JSON_OBJECT;
            return new ActionResult(UNSUPPORTED.formatted(kinds(requests)), ActionResult.JSON, requests.toString(),
                    true, details);
        }
        return switch (kind) {
            case TOOL -> tool(result, ms, details);
            case PROMPT -> {
                JsonArray messages = array(result, "messages");
                yield new ActionResult(messages.size() + " message(s) in " + ms + " ms", ActionResult.JSON,
                        messages.toString(), false, details);
            }
            case RESOURCE, TEMPLATE -> resource(result, ms, details);
        };
    }

    /**
     * The result of a call that got no JSON-RPC answer: its line, no body.
     *
     * @param summary such as {@code timed out after 55 s}
     * @param details the JSON of the request
     */
    static ActionResult transport(String summary, String details) {
        return new ActionResult(summary, null, null, true, details);
    }

    /** The JSON of {@code structuredContent} when present; else the text items joined; else the content as JSON. */
    private static ActionResult tool(JsonObject result, long ms, String details) {
        boolean isError = result.getBoolean("isError", false);
        String summary = "ok in " + ms + " ms";
        JsonValue structured = result.get("structuredContent");
        if (structured != null && structured.getValueType() != JsonValue.ValueType.NULL) {
            return new ActionResult(summary, ActionResult.JSON, structured.toString(), isError, details);
        }
        JsonArray content = array(result, "content");
        List<String> texts = new ArrayList<>();
        for (JsonValue item : content) {
            if (item instanceof JsonObject object && "text".equals(object.getString("type", null))
                    && object.get("text") instanceof JsonString text) {
                texts.add(text.getString());
            }
        }
        if (!texts.isEmpty() || content.isEmpty()) {
            return new ActionResult(summary, ActionResult.TEXT, String.join("\n", texts), isError, details);
        }
        return new ActionResult(summary, ActionResult.JSON, content.toString(), isError, details);
    }

    /** The first text content, else the contents as JSON. */
    private static ActionResult resource(JsonObject result, long ms, String details) {
        JsonArray contents = array(result, "contents");
        String summary = contents.size() + " content item(s) in " + ms + " ms";
        for (JsonValue item : contents) {
            if (item instanceof JsonObject object && object.get("text") instanceof JsonString text) {
                return new ActionResult(summary, ActionResult.TEXT, text.getString(), false, details);
            }
        }
        return new ActionResult(summary, ActionResult.JSON, contents.toString(), false, details);
    }

    /** {@code elicitation, sampling}: the kinds of the input requests, each once, in order. */
    private static String kinds(JsonObject requests) {
        Set<String> kinds = new LinkedHashSet<>();
        for (JsonValue request : requests.values()) {
            String method = request instanceof JsonObject object ? object.getString("method", "") : "";
            int slash = method.indexOf('/');
            kinds.add(slash > 0 ? method.substring(0, slash) : method);
        }
        return String.join(", ", kinds);
    }

    private static JsonArray array(JsonObject object, String name) {
        return object.get(name) instanceof JsonArray array ? array : JsonValue.EMPTY_JSON_ARRAY;
    }
}
```

`DEV_SRC/McpInspector.java`:

```java
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.live.McpInspection;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import java.io.StringReader;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The MCP inspector of the {@code mcp} panel (spec §3): an action per item of the {@link McpCatalogue}, which calls
 * the application's own {@code /mcp} through {@link McpClient} and returns what came back as a
 * {@link PanelAction.ActionResult}. Built once per boot by {@link McpLivePanel#start}, dropped by its {@code stop}.
 *
 * <p>When it cannot work it offers no action and says why in the {@code inspector} value: a twin MCP server, a
 * catalogue that cannot be read, or no bound address for {@code /mcp}. The panel's live values keep working.
 */
final class McpInspector {

    /** Before {@code start} and after {@code stop}: no action, nothing sampled. */
    static final McpInspector OFF = new McpInspector(null, McpCatalogue.EMPTY, null);
    /** The route of {@code /mcp} has no URL: no listener declared it. */
    static final String NO_ADDRESS = "/mcp has no bound address";
    /** The MCP server is loaded twice ({@code VIDOCQ-MCP-003}): its registries cannot be read. */
    static final String TWIN = "loaded twice";

    private static final System.Logger LOG = System.getLogger(McpInspector.class.getName());

    private final McpClient client;
    private final McpCatalogue catalogue;
    private final String absent;
    private final List<PanelAction> actions;

    private McpInspector(McpClient client, McpCatalogue catalogue, String absent) {
        this.client = client;
        this.catalogue = catalogue;
        this.absent = absent;
        List<PanelAction> built = new ArrayList<>();
        if (client != null) {
            for (McpCatalogue.Item item : catalogue.items()) {
                PanelAction action = action(item);
                if (action != null) {
                    built.add(action);
                }
            }
        }
        this.actions = List.copyOf(built);
    }

    /**
     * The inspector of this boot. Never throws: whatever fails makes an inspector without actions that says why.
     *
     * @param beans      the bean manager of the started container
     * @param inspection what {@code McpInspection} read of it
     * @param urls       the URLs of {@code /mcp} the runtime extension published
     * @param timeout    how long a call may take
     */
    static McpInspector start(BeanManager beans, McpInspection inspection, List<String> urls, Duration timeout) {
        try {
            if (beans == null || inspection == null) {
                return OFF;
            }
            if (inspection.twin()) {
                return new McpInspector(null, McpCatalogue.EMPTY, TWIN);
            }
            McpCatalogue catalogue = McpCatalogue.read(beans);
            if (catalogue.absent() != null) {
                return new McpInspector(null, catalogue, catalogue.absent());
            }
            Optional<URI> endpoint = preferred(urls);
            if (endpoint.isEmpty()) {
                return new McpInspector(null, catalogue, NO_ADDRESS);
            }
            return of(catalogue, endpoint.get(), timeout);
        } catch (RuntimeException | LinkageError unreadable) {
            return new McpInspector(null, McpCatalogue.EMPTY,
                    "catalogue unreadable: " + unreadable.getClass().getSimpleName());
        }
    }

    /** An inspector of {@code catalogue} calling {@code endpoint}. */
    static McpInspector of(McpCatalogue catalogue, URI endpoint, Duration timeout) {
        return new McpInspector(new McpClient(endpoint, timeout), catalogue, null);
    }

    /** A loopback URL when there is one ({@code localhost}, {@code 127.*}, {@code [::1]}), else the first. */
    static Optional<URI> preferred(List<String> urls) {
        List<URI> uris = new ArrayList<>();
        for (String url : urls) {
            try {
                uris.add(URI.create(url));
            } catch (IllegalArgumentException malformed) {
                // a URL the report printed but no client could call: skipped
            }
        }
        return uris.stream().filter(McpInspector::loopback).findFirst().or(() -> uris.stream().findFirst());
    }

    private static boolean loopback(URI uri) {
        String host = uri.getHost();
        return host != null && (host.equals("localhost") || host.startsWith("127.") || host.equals("[::1]"));
    }

    /** The actions, in catalogue order; none when the inspector cannot work. */
    List<PanelAction> actions() {
        return actions;
    }

    /** Writes {@code inspector}: what it offers and where it calls, or why it offers nothing. */
    void sample(PanelSample out) {
        if (client == null) {
            if (absent != null) {
                out.absent("inspector", absent);
            }
            return;
        }
        out.text("inspector", catalogue.summary() + " at " + client.endpoint());
    }

    /** The action of {@code item}; {@code null}, and logged at DEBUG, when the SPI refuses what it would declare. */
    private PanelAction action(McpCatalogue.Item item) {
        try {
            McpCatalogue.Kind kind = item.kind();
            List<PanelAction.Argument> arguments = kind.argument() == null ? List.of()
                    : List.of(argument(kind, item.schema()));
            return new PanelAction(item.id(), item.label(), item.confirmation(), arguments,
                    given -> call(item, given), kind.group(), item.description());
        } catch (IllegalArgumentException refused) {
            LOG.log(System.Logger.Level.DEBUG, "MCP inspector: no action for " + item.target(), refused);
            return null;
        }
    }

    /** Its json argument; an open object when the SPI refuses the item's own schema. */
    private static PanelAction.Argument argument(McpCatalogue.Kind kind, String schema) {
        try {
            return PanelAction.Argument.json(kind.argument(), kind.argumentLabel(), schema);
        } catch (IllegalArgumentException unusable) {
            return PanelAction.Argument.json(kind.argument(), kind.argumentLabel(), McpCatalogue.OPEN_SCHEMA);
        }
    }

    /**
     * Calls {@code item} with the arguments the console checked.
     *
     * @param item  what to call
     * @param given its json argument's text by name, or nothing for a fixed resource
     * @return what came back
     */
    PanelAction.ActionResult call(McpCatalogue.Item item, Map<String, String> given) {
        String argumentName = item.kind().argument();
        JsonObject values = argumentName == null ? JsonValue.EMPTY_JSON_OBJECT : parse(given.get(argumentName));
        return send(item, values);
    }

    private PanelAction.ActionResult send(McpCatalogue.Item item, JsonObject values) {
        JsonObjectBuilder params = Json.createObjectBuilder();
        String method;
        String name;
        Map<String, String> headers = Map.of();
        switch (item.kind()) {
            case TOOL -> {
                method = "tools/call";
                name = item.target();
                params.add("name", name).add("arguments", values);
                headers = McpClient.paramHeaders(item.headerDesignations(), values);
            }
            case PROMPT -> {
                method = "prompts/get";
                name = item.target();
                params.add("name", name).add("arguments", values);
            }
            case RESOURCE -> {
                method = "resources/read";
                name = item.target();
                params.add("uri", name);
            }
            default -> {
                method = "resources/read";
                name = UriTemplates.expand(item.target(), values);
                params.add("uri", name);
            }
        }
        JsonObject request = client.request(method, params.build());
        try {
            McpClient.Exchange exchange = client.send(request, name, headers);
            return McpResults.of(item.kind(), exchange, details(request, exchange));
        } catch (McpTransportException failed) {
            return McpResults.transport(failed.getMessage(), details(request, null));
        } catch (RuntimeException unexpected) {
            return McpResults.transport("/mcp call failed: " + unexpected.getClass().getSimpleName(),
                    details(request, null));
        }
    }

    /** The exchange as the page shows it: the request, and the HTTP status, the SSE events and the response. */
    private static String details(JsonObject request, McpClient.Exchange exchange) {
        JsonObjectBuilder out = Json.createObjectBuilder().add("request", request);
        if (exchange != null) {
            out.add("status", exchange.status());
            if (!exchange.events().isEmpty()) {
                JsonArrayBuilder events = Json.createArrayBuilder();
                exchange.events().forEach(events::add);
                out.add("events", events);
            }
            out.add("response", exchange.response());
        }
        return out.build().toString();
    }

    /** The console checked that {@code text} is one JSON object; {@code null} reads as an empty one. */
    private static JsonObject parse(String text) {
        if (text == null) {
            return JsonValue.EMPTY_JSON_OBJECT;
        }
        try (JsonReader reader = Json.createReader(new StringReader(text))) {
            return reader.readObject();
        }
    }
}
```

`DEV_SRC/McpLivePanel.java` — the class Javadoc gains `It is also the MCP inspector: an action per tool, prompt, resource and resource template, which calls the application's own {@code /mcp} (see {@link McpInspector}).`; add imports `io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.live.McpEndpointLive`, `io.vidocq.runtime.spi.devconsole.PanelAction`, `java.time.Duration`; then:

```java
    /** How long the inspector waits for {@code /mcp}: under the console's 60 s limit of an action. */
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(55);

    private volatile McpLiveBeans live = McpLiveBeans.NONE;
    private volatile McpInspector inspector = McpInspector.OFF;
```

```java
    /**
     * Resolves the beans of the MCP server, once, without creating any of them, and builds the inspector from the
     * registries that exist and the URL of {@code /mcp} the runtime extension published.
     */
    @Override
    public void start(ExtensionContext context) {
        McpInspection inspection = McpInspection.of(context.beanManager());
        live = McpLiveBeans.of(inspection, context.beanManager());
        inspector = McpInspector.start(context.beanManager(), inspection, McpEndpointLive.urls(), CALL_TIMEOUT);
    }

    /** Drops the beans, the catalogue and the inspector's history: a dev reload builds them again. */
    @Override
    public void stop() {
        live = McpLiveBeans.NONE;
        inspector = McpInspector.OFF;
    }
```

```java
    /** The inspector's actions: one per tool, prompt, resource and resource template; none before {@link #start}. */
    @Override
    public List<PanelAction> actions() {
        return inspector.actions();
    }
```

and `sample` becomes:

```java
    @Override
    public void sample(PanelSample sample) {
        live.sample(sample);
        inspector.sample(sample);
    }
```

- [ ] **Step 4: Run them to see them pass**

Run: `mvn -o -q -pl $DEV test`
Expected: `BUILD SUCCESS`; every `-dev` test passes, `samplingCreatesNoBean` and `aSampleTakesUnderFiveMilliseconds`
included.

- [ ] **Step 5: Commit** — `feat(mcp-dev): the MCP inspector calls tools, prompts and resources from the dev console`.
  Files: `DEV_SRC/McpResults.java`, `DEV_SRC/McpInspector.java`, `DEV_SRC/McpLivePanel.java`,
  `DEV_TEST/McpResultsTest.java`, `DEV_TEST/McpInspectorTest.java`, `DEV_TEST/McpLivePanelTest.java`.

---

### Task 9: History, secrets and replay

**Files:**
- Create: `DEV_SRC/Secrets.java`, `DEV_SRC/CallHistory.java`, `DEV_TEST/SecretsTest.java`,
  `DEV_TEST/CallHistoryTest.java`
- Modify: `DEV_SRC/McpInspector.java` (`call`, `send`, `details`, `sample`, a `history` field and accessor, new
  `replay`), `DEV_TEST/McpInspectorTest.java`, `DEV_TEST/McpLivePanelTest.java`

**Interfaces:**
- Consumes: `McpInspector` (Task 8); `PanelSample.REPLAY_COLUMN`, `PanelSample.MAX_REPLAY_CELL` (Task 2).
- Produces:
  - `final class Secrets`: `MASK = "***"`, `MIN_SCRUBBED = 4`, `static boolean secret(String name)`,
    `static JsonValue mask(JsonValue)`, `static Set<String> values(JsonValue)`,
    `static String scrub(String text, Collection<String> secrets)`;
  - `final class CallHistory`: `MAX = 20`, `COLUMNS`, `record Call(long time, String actionId, String label,
    String arguments, String summary, boolean error, long millis, String details, String replay)`,
    `void add(Call)`, `List<Call> calls()`, `void writeTo(PanelSample)`;
  - `McpInspector.history()`; the sample table `calls`.

- [ ] **Step 1: Write the failing tests**

`DEV_TEST/SecretsTest.java`:

```java
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec §3.5: a secret's name is masked wherever the inspector keeps or shows arguments. */
class SecretsTest {

    private static JsonObject object(String json) {
        return Json.createReader(new StringReader(json)).readObject();
    }

    @Test
    void aNameIsASecretsWhenItHoldsAMarkerIgnoringCase() {
        for (String name : List.of("password", "dbPasswd", "clientSecret", "accessToken", "apikey", "x-api-key",
                "api_key", "Credentials", "Authorization")) {
            assertTrue(Secrets.secret(name), name);
        }
        assertFalse(Secrets.secret("zone"));
        assertFalse(Secrets.secret("keyword"));
    }

    @Test
    void maskReplacesASecretMemberAtAnyDepth() {
        assertEquals(object("{\"zone\":\"UTC\",\"apiKey\":\"***\",\"auth\":{\"password\":\"***\"},"
                        + "\"list\":[{\"token\":\"***\"}]}"),
                Secrets.mask(object("{\"zone\":\"UTC\",\"apiKey\":\"k-123\",\"auth\":{\"password\":\"hunter22\"},"
                        + "\"list\":[{\"token\":{\"a\":1}}]}")));
    }

    @Test
    void theSecretValuesAreScrubbedFromAText() {
        Set<String> secrets = Secrets.values(object("{\"apiKey\":\"hunter22\",\"pin\":\"abc\",\"token\":\"abc\"}"));

        assertEquals(Set.of("hunter22", "abc"), secrets);
        assertEquals("Invalid key *** for abc", Secrets.scrub("Invalid key hunter22 for abc", secrets),
                "a value shorter than 4 characters would mask ordinary text");
    }
}
```

`DEV_TEST/CallHistoryTest.java`:

```java
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import io.vidocq.runtime.spi.devconsole.PanelSample;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec §3.4: the last 20 calls of the boot, newest first. */
class CallHistoryTest {

    private static CallHistory.Call call(int n) {
        return new CallHistory.Call(1_789_740_602_114L + n, "tool.t", "T", "{\"n\":" + n + "}", "ok in 1 ms",
                false, 1, "{}", "tool.t {\"arguments\":{\"n\":" + n + "}}");
    }

    @Test
    void itKeepsTheLastTwentyNewestFirst() {
        CallHistory history = new CallHistory();

        for (int n = 1; n <= 25; n++) {
            history.add(call(n));
        }

        assertEquals(20, history.calls().size());
        assertEquals("{\"n\":25}", history.calls().getFirst().arguments());
        assertEquals("{\"n\":6}", history.calls().getLast().arguments());
    }

    @Test
    void itIsATableWithAReplayColumn() {
        assertEquals(List.of("time", "action", "outcome", "result", "ms", "arguments", PanelSample.REPLAY_COLUMN),
                CallHistory.COLUMNS);
        RecordingSample sample = new RecordingSample();
        CallHistory history = new CallHistory();
        history.add(call(1));

        history.writeTo(sample);

        assertEquals("table", sample.kind("calls"));
        assertTrue(sample.text("calls").contains("tool.t {\"arguments\":{\"n\":1}}"), sample.text("calls"));
    }
}
```

Add to `DEV_TEST/McpInspectorTest.java` (tool fixture `search` has the argument `apiKey`):

```java
    @Test
    void aSecretIsMaskedInTheHistoryTheDetailsAndTheSummaryTheConsoleLogs() {
        stub.respond(request -> StubMcp.error(request, 200, -32602, "Invalid key hunter22 for query"));
        McpInspector inspector = McpInspector.of(McpCatalogue.of(List.of(InspectorFixtures.tool("search")),
                List.of(), List.of(), List.of()), stub.uri(), Duration.ofSeconds(5));

        PanelAction.ActionResult result = action(inspector, "tool.search").call()
                .apply(Map.of("arguments", "{\"query\":\"cats\",\"apiKey\":\"hunter22\"}"));

        assertTrue(stub.received().getFirst().body().contains("hunter22"), "the server receives the real value");
        assertEquals("error -32602: Invalid key *** for query", result.summary(), "the console's INFO line");
        assertFalse(result.details().contains("hunter22"), result.details());
        assertTrue(result.details().contains("\"apiKey\":\"***\""), result.details());
        CallHistory.Call call = inspector.history().calls().getFirst();
        assertEquals("{\"query\":\"cats\",\"apiKey\":\"***\"}", call.arguments());
        assertEquals("tool.search {\"arguments\":{\"query\":\"cats\",\"apiKey\":\"***\"}}", call.replay());
        assertFalse(call.details().contains("hunter22"));
        assertTrue(call.error());
    }

    @Test
    void everyCallIsRecordedWithWhatReplaysIt() {
        stub.respond(request -> StubMcp.result(request, "{\"contents\":[{\"uri\":\"time://utc\",\"text\":\"UTC\"}]}"));
        McpInspector inspector = inspector();

        action(inspector, "res.860cd4a5").call().apply(Map.of());
        action(inspector, "tpl.72bba68d").call().apply(Map.of("variables", "{\"zone\":\"UTC\"}"));

        List<CallHistory.Call> calls = inspector.history().calls();
        assertEquals("tpl.72bba68d {\"variables\":{\"zone\":\"UTC\"}}", calls.get(0).replay());
        assertEquals("res.860cd4a5 {}", calls.get(1).replay());
        assertEquals("time://utc", calls.get(1).label());
        RecordingSample sample = new RecordingSample();
        inspector.sample(sample);
        assertEquals("table", sample.kind("calls"));
    }
```

Add to `DEV_TEST/McpLivePanelTest.java`:

```java
    @Test
    void theHistoryHoldsTwentyCallsAndStopClearsIt() throws Exception {
        try (StubMcp stub = StubMcp.start();
             VaubanContainer container = McpTestContainers.container(InspectorFixtures.CATALOGUE_BEANS)) {
            stub.respond(request -> StubMcp.result(request,
                    "{\"contents\":[{\"uri\":\"time://utc\",\"text\":\"UTC\"}]}"));
            McpEndpointLive.publish(List.of(stub.uri().toString()));
            InspectorFixtures.fill(container.getBeanManager());
            panel.start(new FakeExtensionContext(container));
            PanelAction utc = panel.actions().stream().filter(a -> a.id().equals("res.860cd4a5")).findFirst()
                    .orElseThrow();

            for (int n = 0; n < 21; n++) {
                utc.call().apply(Map.of());
            }
            RecordingSample full = new RecordingSample();
            panel.sample(full);

            assertEquals(20, full.text("calls").split("res\\.860cd4a5 \\{}", -1).length - 1,
                    "twenty rows, each with its replay cell: " + full.text("calls"));

            panel.stop();
            panel.start(new FakeExtensionContext(container));
            RecordingSample fresh = new RecordingSample();
            panel.sample(fresh);

            assertEquals("[]", fresh.text("calls"), "a new boot starts with no history");
        }
    }
```

(with `import java.util.Map;`).

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -o -q -pl $DEV test -Dtest='SecretsTest,CallHistoryTest,McpInspectorTest,McpLivePanelTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAILURE, `cannot find symbol: class Secrets`.

- [ ] **Step 3: Write the implementation**

`DEV_SRC/Secrets.java`:

```java
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Spec §3.5: an argument whose name holds, ignoring case, one of the {@link #MARKERS} is shown as {@value #MASK} in
 * the history, the details and the console's log line. The MCP server receives the real value, and a result's body
 * is shown as the server returned it. Since a server's error message may quote a value, the string values of such
 * arguments, {@value #MIN_SCRUBBED} characters or longer, are also replaced wherever they appear in the summary and
 * the details.
 */
final class Secrets {

    /** What stands for a secret. */
    static final String MASK = "***";
    /** The shortest value scrubbed from a text: a shorter one would mask ordinary words. */
    static final int MIN_SCRUBBED = 4;
    /** What makes a name a secret's, ignoring case. */
    static final List<String> MARKERS = List.of("password", "passwd", "secret", "token", "apikey", "api-key",
            "api_key", "credential", "authorization");

    private Secrets() {}

    /** Whether {@code name} is a secret's. */
    static boolean secret(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return MARKERS.stream().anyMatch(lower::contains);
    }

    /** {@code value} with every member whose name is a secret's replaced by {@value #MASK}, at any depth. */
    static JsonValue mask(JsonValue value) {
        if (value instanceof JsonObject object) {
            JsonObjectBuilder out = Json.createObjectBuilder();
            object.forEach((name, member) -> out.add(name, secret(name) ? Json.createValue(MASK) : mask(member)));
            return out.build();
        }
        if (value instanceof JsonArray array) {
            JsonArrayBuilder out = Json.createArrayBuilder();
            array.forEach(item -> out.add(mask(item)));
            return out.build();
        }
        return value;
    }

    /** The string values of the members of {@code value} whose name is a secret's, at any depth. */
    static Set<String> values(JsonValue value) {
        Set<String> found = new LinkedHashSet<>();
        collect(value, found);
        return found;
    }

    /** {@code text} with each of {@code secrets} of {@value #MIN_SCRUBBED} characters or more replaced. */
    static String scrub(String text, Collection<String> secrets) {
        if (text == null) {
            return null;
        }
        String out = text;
        for (String secret : secrets.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            if (secret.length() >= MIN_SCRUBBED) {
                out = out.replace(secret, MASK);
            }
        }
        return out;
    }

    private static void collect(JsonValue value, Set<String> found) {
        if (value instanceof JsonObject object) {
            object.forEach((name, member) -> {
                if (secret(name) && member instanceof JsonString string) {
                    found.add(string.getString());
                } else {
                    collect(member, found);
                }
            });
        } else if (value instanceof JsonArray array) {
            array.forEach(item -> collect(item, found));
        }
    }
}
```

`DEV_SRC/CallHistory.java`:

```java
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import io.vidocq.runtime.spi.devconsole.PanelSample;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The last {@value #MAX} calls of the boot (spec §3.4), newest first, in memory, published as the {@code calls}
 * table of the panel's sample with a {@link PanelSample#REPLAY_COLUMN replay} column. An immutable list behind a
 * {@code volatile}: {@code sample} reads it without a lock.
 */
final class CallHistory {

    /** How many calls are kept. */
    static final int MAX = 20;
    /** The columns of the {@code calls} table. */
    static final List<String> COLUMNS = List.of("time", "action", "outcome", "result", "ms", "arguments",
            PanelSample.REPLAY_COLUMN);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    /**
     * One call.
     *
     * @param time      when it ended, in epoch milliseconds
     * @param actionId  the id of its action
     * @param label     the label of its action
     * @param arguments its arguments, masked, as compact JSON; empty for a fixed resource
     * @param summary   its summary, scrubbed
     * @param error     whether its result is an error
     * @param millis    how long it took
     * @param details   its exchange, masked
     * @param replay    its replay cell, {@code <action id> <JSON of its arguments by name>}, or empty when too long
     */
    record Call(long time, String actionId, String label, String arguments, String summary, boolean error,
                long millis, String details, String replay) {}

    private volatile List<Call> calls = List.of();

    /** Adds {@code call} first, dropping the oldest past {@value #MAX}. */
    synchronized void add(Call call) {
        List<Call> next = new ArrayList<>(MAX);
        next.add(call);
        next.addAll(calls.subList(0, Math.min(calls.size(), MAX - 1)));
        calls = List.copyOf(next);
    }

    /** The calls, newest first. */
    List<Call> calls() {
        return calls;
    }

    /** Writes the {@code calls} table. */
    void writeTo(PanelSample out) {
        List<List<String>> rows = new ArrayList<>();
        for (Call call : calls) {
            rows.add(List.of(TIME.format(Instant.ofEpochMilli(call.time())), call.label(),
                    call.error() ? "error" : "ok", call.summary(), Long.toString(call.millis()), call.arguments(),
                    call.replay()));
        }
        out.table("calls", COLUMNS, rows);
    }
}
```

`DEV_SRC/McpInspector.java`:

- new imports `java.util.Set`;
- a field `private final CallHistory history = new CallHistory();` and the accessor
  `CallHistory history() { return history; }` (Javadoc: `The calls of this boot.`);
- `sample`: after the `out.text("inspector", …)` line, add `history.writeTo(out);`;
- replace `call`, `send` and `details` with:

```java
    /**
     * Calls {@code item} with the arguments the console checked, and records the call, its secrets masked.
     *
     * @param item  what to call
     * @param given its json argument's text by name, or nothing for a fixed resource
     * @return what came back, the summary and the details scrubbed of the secrets' values
     */
    PanelAction.ActionResult call(McpCatalogue.Item item, Map<String, String> given) {
        long start = System.nanoTime();
        String argumentName = item.kind().argument();
        JsonObject values = argumentName == null ? JsonValue.EMPTY_JSON_OBJECT : parse(given.get(argumentName));
        Set<String> secrets = Secrets.values(values);
        PanelAction.ActionResult sent = send(item, values, secrets);
        PanelAction.ActionResult result = new PanelAction.ActionResult(Secrets.scrub(sent.summary(), secrets),
                sent.contentType(), sent.body(), sent.error(), sent.details());
        JsonObject masked = (JsonObject) Secrets.mask(values);
        history.add(new CallHistory.Call(System.currentTimeMillis(), item.id(), item.label(),
                argumentName == null ? "" : masked.toString(), result.summary(), result.error(),
                (System.nanoTime() - start) / 1_000_000, result.details(), replay(item.id(), argumentName, masked)));
        return result;
    }

    private PanelAction.ActionResult send(McpCatalogue.Item item, JsonObject values, Set<String> secrets) {
        JsonObjectBuilder params = Json.createObjectBuilder();
        String method;
        String name;
        Map<String, String> headers = Map.of();
        switch (item.kind()) {
            case TOOL -> {
                method = "tools/call";
                name = item.target();
                params.add("name", name).add("arguments", values);
                headers = McpClient.paramHeaders(item.headerDesignations(), values);
            }
            case PROMPT -> {
                method = "prompts/get";
                name = item.target();
                params.add("name", name).add("arguments", values);
            }
            case RESOURCE -> {
                method = "resources/read";
                name = item.target();
                params.add("uri", name);
            }
            default -> {
                method = "resources/read";
                name = UriTemplates.expand(item.target(), values);
                params.add("uri", name);
            }
        }
        JsonObject request = client.request(method, params.build());
        try {
            McpClient.Exchange exchange = client.send(request, name, headers);
            return McpResults.of(item.kind(), exchange, details(request, exchange, secrets));
        } catch (McpTransportException failed) {
            return McpResults.transport(failed.getMessage(), details(request, null, secrets));
        } catch (RuntimeException unexpected) {
            return McpResults.transport("/mcp call failed: " + unexpected.getClass().getSimpleName(),
                    details(request, null, secrets));
        }
    }

    /**
     * The exchange as the page shows it, secrets masked by name and scrubbed by value: the request, and the HTTP
     * status, the SSE events and the response.
     */
    private static String details(JsonObject request, McpClient.Exchange exchange, Set<String> secrets) {
        JsonObjectBuilder out = Json.createObjectBuilder().add("request", Secrets.mask(request));
        if (exchange != null) {
            out.add("status", exchange.status());
            if (!exchange.events().isEmpty()) {
                JsonArrayBuilder events = Json.createArrayBuilder();
                exchange.events().forEach(event -> events.add(Secrets.mask(event)));
                out.add("events", events);
            }
            out.add("response", Secrets.mask(exchange.response()));
        }
        return Secrets.scrub(out.build().toString(), secrets);
    }

    /**
     * The replay cell of a call: its action id, a space, and its masked arguments by name, which the page puts back
     * in the form; empty past {@link PanelSample#MAX_REPLAY_CELL} characters.
     */
    private static String replay(String id, String argumentName, JsonObject masked) {
        JsonObject values = argumentName == null ? JsonValue.EMPTY_JSON_OBJECT
                : Json.createObjectBuilder().add(argumentName, masked).build();
        String cell = id + " " + values;
        return cell.length() > PanelSample.MAX_REPLAY_CELL ? "" : cell;
    }
```

  and the class Javadoc gains: `Each call is recorded in a {@link CallHistory} of 20, its secrets masked (see {@link Secrets}), which the sample publishes with what replays it.`

- [ ] **Step 4: Run them to see them pass**

Run: `mvn -o -q -pl $DEV test`
Expected: `BUILD SUCCESS`; every `-dev` test passes.

- [ ] **Step 5: Commit** — `feat(mcp-dev): the inspector's history of 20 calls, masked secrets and replay`.
  Files: `DEV_SRC/Secrets.java`, `DEV_SRC/CallHistory.java`, `DEV_SRC/McpInspector.java`,
  `DEV_TEST/SecretsTest.java`, `DEV_TEST/CallHistoryTest.java`, `DEV_TEST/McpInspectorTest.java`,
  `DEV_TEST/McpLivePanelTest.java`.

---

### Task 10: The inspector on a real server

**Files:**
- Create: `IT_TEST/McpInspectorConsoleTest.java`

**Interfaces:**
- Consumes: `LaunchedServer.start(Shape, String, String...)`, `server.snapshotUrl()`,
  `DevConsoleSnapshot.read(String)`, `snapshot.json()`, `snapshot.actionToken()`,
  `DevConsoleSnapshot.postAction(String snapshotUrl, String path, String origin, String token, String body)`; the
  fixture's `current_time`, `plan_meeting` and `time://zone/{zone}`.

- [ ] **Step 1: Write the test**

`IT_TEST/McpInspectorConsoleTest.java`:

```java
package io.vidocq.runtime.it.lc4jcdimcp;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MCP inspector of the dev console on a real server (spec §5): the catalogue among the {@code mcp} panel's
 * actions, a tool called through {@code POST /api/action/mcp/tool.<id>} with the snapshot's token, a prompt, a
 * resource template, and the history the next snapshot shows. Every call goes through the application's real
 * {@code /mcp}, in protocol 2026-07-28, without a session.
 */
class McpInspectorConsoleTest {

    @Test
    void aToolAPromptAndAResourceAreCalledFromTheConsoleAndListedInTheHistory() throws Exception {
        try (LaunchedServer server = LaunchedServer.start(LaunchedServer.Shape.FLAT, "inspector",
                "-Dvidocq.launch.mode=dev")) {
            DevConsoleSnapshot snapshot = DevConsoleSnapshot.read(server.snapshotUrl());
            String token = snapshot.actionToken();
            assertTrue(token != null && token.matches("[0-9a-f]{64}"), "no action token in a dev launch: " + token);
            String own = URI.create(server.snapshotUrl()).resolve("/").toString().replaceAll("/$", "");
            String template = "tpl." + sha8("time://zone/{zone}");
            String json = snapshot.json();

            for (String id : new String[] {"tool.current-time", "tool.convert-time", "prompt.plan-meeting", template}) {
                assertTrue(json.contains("\"id\":\"" + id + "\""), id + " missing from " + json);
            }
            assertTrue(json.contains("\"group\":\"Tools\""), json);
            assertTrue(json.contains("\"confirmation\":\"Call current_time? It runs the application's code, and may "
                    + "change data.\""), json);

            HttpResponse<String> tool = DevConsoleSnapshot.postAction(server.snapshotUrl(),
                    "/api/action/mcp/tool.current-time", own, token,
                    "{\"arguments\":\"{\\\"zone\\\":\\\"Europe/Paris\\\"}\"}");
            assertEquals(200, tool.statusCode(), tool.body());
            assertTrue(tool.body().matches("\\{\"result\":\"ok in [0-9]+ ms\",\"contentType\":\"text/plain\","
                    + "\"body\":\"[^\"]*Europe/Paris[^\"]*\",\"details\":.*"), tool.body());
            assertTrue(tool.body().contains("tools/call"), tool.body());
            assertFalse(tool.body().contains("\"error\":true"), tool.body());

            HttpResponse<String> prompt = DevConsoleSnapshot.postAction(server.snapshotUrl(),
                    "/api/action/mcp/prompt.plan-meeting", own, token,
                    "{\"arguments\":\"{\\\"zones\\\":\\\"Europe/Paris,Asia/Tokyo\\\","
                            + "\\\"durationMinutes\\\":\\\"30\\\"}\"}");
            assertEquals(200, prompt.statusCode(), prompt.body());
            assertTrue(prompt.body().matches("\\{\"result\":\"1 message\\(s\\) in [0-9]+ ms\".*"), prompt.body());
            assertTrue(prompt.body().contains("Propose a 30-minute meeting slot"), prompt.body());

            HttpResponse<String> resource = DevConsoleSnapshot.postAction(server.snapshotUrl(),
                    "/api/action/mcp/" + template, own, token,
                    "{\"variables\":\"{\\\"zone\\\":\\\"Europe/Paris\\\"}\"}");
            assertEquals(200, resource.statusCode(), resource.body());
            assertTrue(resource.body().matches("\\{\"result\":\"1 content item\\(s\\) in [0-9]+ ms\".*"),
                    resource.body());
            assertTrue(resource.body().contains("Europe/Paris"), resource.body());

            String after = DevConsoleSnapshot.read(server.snapshotUrl()).json();
            assertTrue(after.contains("\"key\":\"calls\""), "no history: " + after);
            assertTrue(after.contains("tool.current-time {\\\"arguments\\\":{\\\"zone\\\":\\\"Europe/Paris\\\"}}"),
                    "no replay of the tool call: " + after);
            assertTrue(after.contains(template + " {\\\"variables\\\":{\\\"zone\\\":\\\"Europe/Paris\\\"}}"),
                    "no replay of the resource read: " + after);
            assertTrue(after.contains("2 tools, 1 prompt, 0 resources, 1 resource template at http://127.0.0.1:"),
                    "the inspector does not say where it calls: " + after);
        }
    }

    private static String sha8(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)), 0, 4);
    }
}
```

- [ ] **Step 2: Install what the IT launches, then run it**

Run: `mvn -o -q -pl $SPI,$CONSOLE,$MCP,$DEV install -DskipTests`, then
`mvn -o -pl $IT test -Dtest=McpInspectorConsoleTest`
Expected: `Tests run: 1, Failures: 0`. On a failure, read `target/it-logs/inspector.log` (the `it.logs` directory
of the module) before changing anything.

- [ ] **Step 3: Run the whole IT module**

Run: `mvn -o -pl $IT test`
Expected: `BUILD SUCCESS`; `McpDevConsolePanelTest` and `McpLaunchShapesTest` unchanged (the inspector opens no
session and builds no invoker in their boots: it only calls when asked).

- [ ] **Step 4: Commit** — `test(it): the MCP inspector on a real server`.
  Files: `IT_TEST/McpInspectorConsoleTest.java`.

---

### Task 11: ADR amendment and docs

**Files:**
- Modify: `docs/adr/0001-dev-console-actions.md`, `DOCS/dev-console.adoc`, `DOCS/dev-console-panels.adoc`,
  `DOCS/modules/vidocq-runtime-extensions.adoc`, `DOCS/whats-new.adoc`

- [ ] **Step 1: Amend ADR 0001**

Append to `docs/adr/0001-dev-console-actions.md`:

```markdown
## Amendment 1 (2026-09-26) — JSON arguments, structured results, groups

* Status: Accepted
* Design: `docs/superpowers/specs/2026-09-26-mcp-inspector-design.md`

The MCP inspector of the `mcp` panel calls the application's tools with arguments a JSON Schema describes, and shows
what came back and the JSON-RPC exchange. The actions gain what it needs, in a form any panel can use.

### SPI

* `PanelAction.Argument.json(name, label, schema)`: the value is a JSON document; `schema` is a JSON Schema, as the
  text of a JSON object of at most 32 KiB, or the constructor throws. One action has at most one `json` argument,
  beside any `oneOf` and `matching` ones. The value reaches the action as its JSON text, in the same map.
  `accepts(String)` stays the only check: for `json`, "parses as a JSON object".
* `PanelAction.ActionResult(summary, contentType, body, error, details)`: `summary` is the line, at most 200
  characters, `done` for `null`; `body`, `text/plain` or `application/json`, and `details`, JSON shown folded, are at
  most 256 KiB each, truncated with `… truncated at 256 KiB`; `error` flags an outcome that is an error of the
  action's target, which is not an exception.
* A new constructor takes `Function<Map<String, String>, ActionResult> call`, a `group` (at most 40 characters) and a
  `description` (at most 2,000). The record keeps one internal form: an old `run` is wrapped as
  `args -> ActionResult.of(run.apply(args))`, and `run()` still returns the line, so existing panels change nothing.

### Transport

Check 5 becomes: the panel and the action exist (`404`); the body is at most **64 KiB for an action with a `json`
argument, 4 KiB otherwise**, checked before it is read (`413`); it is one JSON object of strings whose keys are
exactly the declared arguments, each value accepted, a `json` value parsing as a JSON object (`400`). The console
never validates a value against its schema: the action's target does.

### Running

* The answer to a request that returned is `200 {"result": summary, "error": true, "contentType": …, "body": …,
  "details": …}`, each of the last four only when set: an action returning one line still answers
  `{"result": "..."}`, and a page that reads `result` only still works.
* The logged result line is `summary`.
* The snapshot gives each action its `group` and `description` when set, each `json` argument its `schema` as a JSON
  object, and a last outcome that is an error `"error": true`. A panel keeps up to 128 actions.

### Unchanged

Everything else holds: dev launch only, the per-boot token, the same-origin check, `POST` of `application/json`, one
action at a time per panel, the 60 s limit, and the logging of every run.

### Consequences

* **+** A panel can take structured input and show structured output, which a tool inspector, a query runner or a
  message sender needs, with the same guards as every action.
* **−** A larger body and larger answers: the limits are fixed, and the console checks the body's size before it
  reads it.
```

- [ ] **Step 2: Write the pages**

- `DOCS/dev-console.adoc`:
  - a new section before `[#security]`: `[#mcp-inspector]`, `== MCP inspector [.tag-new]#NEW#`. It says, in prose
    and short lists:
    - in a `dev` launch of an application with `vidocq-runtime-langchain4j-cdi-mcp-extension`, the `mcp` tab lists
      its tools, prompts, resources and resource templates in three folded groups, with a filter past ten actions;
    - a tool's arguments are a form generated from its input schema when the schema is flat (the rule of spec
      §2.4), a JSON editor otherwise, with a "JSON" switch;
    - the call goes through the application's own `/mcp`, from the console's JVM, protocol 2026-07-28, no session;
      the URL the startup report prints, a loopback one preferred; `absent: /mcp has no bound address` otherwise;
    - the confirmation rule: a tool asks `Call <name>? It runs the application's code, and may change data.` unless
      it is `readOnlyHint: true` and not `destructiveHint: true`; prompts and resources never ask;
    - what a result shows: the summary, the body, and the JSON-RPC exchange under "Exchange"; the table of spec
      §3.3 as an AsciiDoc table;
    - the history: the last 20 calls of the boot, newest first, each with a "Replay" button that fills the form and
      sends nothing; a dev reload clears it;
    - secrets: the markers of spec §3.5, masked in the history, the exchange and the log line, the server receiving
      the real value, the body shown as returned; a masked value is typed again on a replay;
    - what v1 does not do: elicitation, sampling and roots (such a tool gets the refusal line), subscriptions,
      notifications and progress, completion, an external MCP server.
  - in `[#actions]` (under Security), say that an action with a `json` argument may send 64 KiB and link
    `xref:adr:0001-dev-console-actions.adoc` only if such an xref already exists on the page; otherwise name
    "ADR 0001, amendment 1" in plain text.
- `DOCS/dev-console-panels.adoc`, inside `== Actions`, after its bullet list, four subsections, each tagged
  `[.tag-new]#NEW#`:
  - `[#json-argument]` `=== A JSON argument`: `PanelAction.Argument.json(name, label, schema)`, the 32 KiB schema,
    one per action, the value as JSON text, the flat-schema form versus the JSON editor, no validation against the
    schema by the console; a short Java example taken from `TestPanels.InspectorPanel`'s `tool.weather`;
  - `[#action-result]` `=== A structured result`: the 7-argument constructor with `call`, `ActionResult` and its
    limits, `error` versus an exception, `details` under "Exchange", and that the old constructors keep working;
  - `[#action-groups]` `=== Groups and descriptions`: `group` (40), `description` (2,000, line breaks kept), folded
    sections in order of first appearance, the filter past ten actions, 128 actions per panel at most;
  - `[#replay-column]` `=== A replay column`: `PanelSample.REPLAY_COLUMN`, the cell format, the 4,096-character
    limit, `***` left for the user.
- `DOCS/modules/vidocq-runtime-extensions.adoc`, at the end of `[#langchain4j-cdi-mcp-live]`: a subsection
  `[#langchain4j-cdi-mcp-inspector]` `==== The MCP inspector [.tag-new]#NEW#`, three sentences and
  `xref:dev-console.adoc#mcp-inspector[MCP inspector]`; mention that the extension publishes the URL of `/mcp` for it.
- `DOCS/whats-new.adoc`, after the `mcp` panel's bullet in `== Runtime`:
  `* **An MCP inspector in the dev console** [.tag-new]#NEW# — in a `dev` launch, the `mcp` tab lists the
  application's MCP tools, prompts and resources and calls them through its own `/mcp`: a form from each tool's input
  schema, the result and the exact JSON-RPC exchange, the last 20 calls with a replay, secrets masked. Built on
  actions that now take a JSON argument and return a structured result (ADR 0001, amendment 1). See
  xref:dev-console.adoc#mcp-inspector[MCP inspector] and
  xref:dev-console-panels.adoc#json-argument[a JSON argument].`

- [ ] **Step 3: Check**

Run: `./check-doc-versions.sh`
Expected: no error.

Then check every new anchor and `xref:` target exists:
`grep -n 'mcp-inspector\|json-argument\|action-result\|action-groups\|replay-column\|langchain4j-cdi-mcp-inspector' docs/en/modules/ROOT/pages/*.adoc docs/en/modules/ROOT/pages/modules/*.adoc`
Expected: each anchor defined once, and every `xref:` pointing at one of them.

- [ ] **Step 4: Commit** — `docs: the MCP inspector, and ADR 0001 amendment 1`.
  Files: `docs/adr/0001-dev-console-actions.md`, `DOCS/dev-console.adoc`, `DOCS/dev-console-panels.adoc`,
  `DOCS/modules/vidocq-runtime-extensions.adoc`, `DOCS/whats-new.adoc`.

---

## After the last task

- Run the full check: `mvn -o install -DskipTests` at the root, then
  `mvn -o -pl $SPI,$CONSOLE,$MCP,$DEV,$IT test`, and the Migration `-dev` module's tests (it uses the old
  `PanelAction` constructors): `mvn -o -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-migration-extension-dev test`.
- Check the page by hand under `vidocq:dev`, on an application with MCP tools, such as
  `~/projects/perso/vidocq-tools/lc4jcdi-on-vidocq`:
  `mvn vidocq:dev -Dvidocq.chappe.listener.default.port=18093 -Dvidocq.devconsole.port=18094 -Dvidocq.dev.debug=false`.
  On `http://127.0.0.1:18094/`, `mcp` tab:
  - the three groups are folded, titled with their counts; with more than ten actions a filter narrows them and opens
    the groups that match;
  - a tool with a flat schema shows a form (required marked `*`, defaults filled, enums as lists); the "JSON" switch
    shows the same values as JSON, and switching back keeps them; a tool with a nested schema shows the JSON editor
    starting from its required properties;
  - a tool that is not read-only asks its confirmation inline; a read-only one does not;
  - a call shows `ok in … ms`, the body (JSON pretty-printed), and the exchange folded under "Exchange"; an error is
    red;
  - the `calls` table lists the calls newest first; "Replay" fills the form and sends nothing; a masked value is left
    empty with the note `masked values: type them again`;
  - a dev reload (edit a tool, save) shows the new catalogue and an empty history.
  Stop the process when done. Report what was seen, and any difference, in the final summary.
- Then finish with superpowers:finishing-a-development-branch; open the PR on the user's go.
