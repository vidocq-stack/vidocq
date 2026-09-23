# ADR 0001 — Actions in the dev console

* Status: Proposed
* Date: 2026-09-23
* Deciders: Yann Blazart
* Issue: Vidocq/vidocq#118

## Context

The dev console is read-only by design. Its Security section says so: `GET` and `HEAD` only, "no endpoint that
changes anything". That choice kept its attack surface small while it was being built: a page on the loopback that
shows what the application is made of, and nothing a hostile page could make it do.

Quarkus's Dev UI shows what developers expect from such a page, and much of it changes something: set a log level,
rerun or clean the migrations, clear a cache, trigger a scheduled job, rerun the tests. Three of our own issues need it:
the logs panel (#119, a runtime log level), the migration panel (#120, migrate and clean-and-migrate), and
continuous testing (#122, rerun the tests).

The console already defends its reads:

* it listens on `127.0.0.1` by default, and warns (`VIDOCQ-DEVC-002`) on any other address;
* `HostGuard` answers only a request whose `Host` names the console, which stops DNS rebinding;
* no CORS header, so another site's page can send a request but never read the answer;
* `Content-Security-Policy: default-src 'self'; frame-ancestors 'none'`, `nosniff`, `no-referrer`;
* it is on in a `dev` launch only, and warns (`VIDOCQ-DEVC-001`) when forced on elsewhere.

A write is a different threat. An attacker does not need to read the answer to a `POST`: a page of any site the
developer visits can send one to `http://127.0.0.1:8888/…` (a "simple" cross-origin request, form-encoded, needs no
preflight). `HostGuard` does not stop it, since the browser sends `Host: 127.0.0.1:8888`. So an action needs a
check a cross-site request cannot pass.

## Decision

### SPI

A panel declares its actions with `DevConsolePanel.actions()`, a list of `PanelAction`:

```java
public record PanelAction(String id, String label, String confirmation, List<Argument> arguments,
                          Function<Map<String, String>, String> run) {

    public record Argument(String name, String label, List<String> allowedValues, String pattern) {}
}
```

* `id` follows the key rule; `label` is the button; `confirmation`, when not `null`, is the question the page asks
  before sending ("Drop every table of @Default and migrate again?"); `run` does the work and returns one short line
  of text for the page and the log.
* An action receives only the string arguments its panel declared (e.g. a logger name and a level for #119). Each
  `Argument` says what it accepts, a list of values (`Argument.oneOf`) or a regular expression the value matches as
  a whole (`Argument.matching`), 200 characters at most, and the console checks it before calling `run`: the panel
  declares, the console refuses. It never receives a class name, a path or a URL to act on.
* Actions exist in a `dev` launch only: in any other mode `actions()` is not even called, the snapshot carries
  neither the token nor an action, and the endpoint is absent: a `POST` there gets `405`, as a `POST` anywhere on
  a read-only console.

### Transport

`POST /api/action/{panel}/{action}` on the console's own listener, never the application's. A request is run only
when every check passes, in this order, each failure answering without running anything:

1. `HostGuard`, as today (`403`).
2. The method is `POST` (`405`), and the body is `application/json` (`415`). A cross-site form cannot send
   `application/json` without a CORS preflight, which the console never answers.
3. `Origin` is present and equals the console's own origin (`403`). Browsers send it on every `POST`. The console's
   own origin is `http://` followed by the `Host` the request was let in with by check 1: the origin of the page the
   console served there. So `localhost`, `127.0.0.1` and `[::1]` each work for the page opened under that name, but
   an `Origin` never passes with another name, scheme or port than its request's `Host`, and `null` never passes.
4. The header `X-Vidocq-Console-Token` equals a random token drawn once per boot from `SecureRandom` (32 bytes,
   hex), which the page reads from `GET /api/snapshot` (`403`). A cross-site page can send the header only through a
   preflight, and cannot read the snapshot to learn the token anyway.
5. The panel and the action exist (`404`), the body is at most 4 KiB (`413`), and it is one JSON object of strings
   whose keys are exactly the declared arguments, each value accepted (`400`).

Checks 2, 3 and 4 each suffice against a cross-site request; they are kept together because each is cheap and each
covers a different browser bug or proxy.

### Running

* An action runs on a virtual thread of the console, one at a time per panel; a second request while one runs
  answers `409`. The request waits for it: `200 {"result": "..."}`, or `500 {"error": "<exception class>"}`. The
  snapshot lists each action with whether it is running and its last outcome of the boot, so the page shows it
  after a reload too.
* Every run is logged at INFO: `Vidocq dev console: action migration/migrate by 127.0.0.1: <result>`,
  and shown in the page. A failure is logged at WARNING with its exception class, and the page shows the class, never
  the message, as for a failed sample.
* An action has a time limit (60 s by default); past it the request answers `202 {"state": "running"}`, the page
  says so, and the action keeps running; its outcome shows in the snapshot when it ends.

### Documentation

The Security section of `dev-console.adoc` replaces "Read-only" with the rules above. A new code,
`VIDOCQ-DEVC-006`, names an action request refused by checks 3 or 4: a cross-site attempt, logged at WARNING with
the origin it came from, once per origin, reason and boot (64 at most, since a client that is no browser can vary
its origin).

## Consequences

* **+** The console can do what the Dev UI does, behind a check a hostile page cannot pass, in `dev` only.
* **+** The rules live in one place, the console's handler. A panel declares what it offers and does the work, and
  never parses a request.
* **−** The console is no longer read-only: an action is code that changes the running application. A panel author
  must keep an action harmless outside development, which the `dev`-only rule enforces, and must never take a path,
  a class or a URL from the request.
* **−** One more thing to test: every check has a test that sends the request a hostile page could send, and shows it
  refused.

## Considered options

* **Stay read-only, act through the IDE or the CLI.** It is the safest, and it leaves #119, #120 and #122 out.
* **A separate admin listener with its own credentials.** Heavier to set up, for no gain on the loopback of a
  developer machine; it adds a secret to manage.
* **Only a `SameSite` cookie as CSRF token.** Works in current browsers, but a header token read from the snapshot
  also covers clients that are not browsers (the Dev MCP of #121) with the same rule.
