# Continuous testing: tests behind `vidocq:dev`, and a `vidocq:test` goal

- **Date**: 2026-09-25
- **Status**: Approved design (brainstorming 2026-09-25): ready for implementation planning
- **Issue**: Vidocq/vidocq#122
- **Scope**: `vidocq` Maven plugin, dev console extension, docs. No brick changes. Builds on the dev services work of
  #123 (`vidocq-runtime-devservices-host`) and on dev console actions (ADR 0001).

## 1. Context

`vidocq:dev` watches `src/main/java` and `src/main/resources`. On a change, `RecompileRunner` runs
`mvn -ntp -q process-classes -DskipTests` in a subprocess, and the application reloads in its child JVM.

Tests never run in that loop:

- `src/test` is not watched;
- no test classes and no JUnit Platform are on any path of the plugin or the child JVM.

The dev console can already display results (`DevConsolePanel.sample`) and offer buttons (`PanelAction`, ADR 0001,
DEV only).

The maintainer's decisions (brainstorming):

- **What to run:** every test of the application, after every change. There is no affected-test tracking.
- **How to run:** Surefire in a subprocess (approach A), so that tests run exactly as `mvn test` runs them: the same
  module path, `argLine`, and dev services test launcher (#123). Considered and rejected:
  - driving the JUnit `Launcher` from the plugin, which re-implements Surefire and risks drift from `mvn test`;
  - running tests in the application's JVM, which conflicts with the running app: ports, static state, module
    layers.
- **Dev services:** tests reuse the dev session's running containers. A test that empties tables empties the dev
  database; this is documented.
- **Two goals:**
  - `vidocq:dev` gains background testing and a `tests` dev console panel;
  - a new `vidocq:test` goal runs the same loop **without the application**, with a terminal interface. Quarkus has
    `quarkus:test` for the same purpose.
- **Documentation is part of the deliverable**, in full (§9).

## 2. Behaviour

### 2.1 Switch

`vidocq.dev.continuousTesting` turns tests on or off in `vidocq:dev`. It defaults to `true` when
`src/test/java` exists, and `false` otherwise. It follows the same resolution rule as `vidocq.dev.devServices`
(#123): an explicit value (`-D` or the goal configuration) beats the application's files, which beat the default.
It reuses `DevServicesFlag` or a sibling helper with the same contract, case-insensitive, and fails on a value that
is neither `true` nor `false`.

`vidocq:test` always runs tests, since that is its purpose. The switch does not apply to it.

### 2.2 Triggers

Both goals watch `src/main/java`, `src/main/resources`, `src/test/java` and `src/test/resources`, with the existing
250 ms debounce. `vidocq.dev.watchDirs` keeps its meaning for the main directories. A new parameter,
`vidocq.dev.testWatchDirs` (default `src/test/java,src/test/resources`), names the test directories.

| Change in | `vidocq:dev` | `vidocq:test` |
|---|---|---|
| main directories | recompile (`process-classes`), reload the app, **then** run the tests | recompile, then run the tests |
| test directories only | run the tests; **no** app reload | run the tests |

A test run never overlaps an app reload in `vidocq:dev`: it starts after the reload completed.

### 2.3 A run

- **Command.** One subprocess: `<maven> -ntp -o -q test-compile surefire:test`, with:
  - `-Dtest=<Class#method,…>` for a rerun of the failures;
  - `-DfailIfNoTests=false` and `-Dsurefire.failIfNoSpecifiedTests=false`;
  - the dev session's injected keys as `-D` values;
  - `-Dvidocq.dev.devServices=false`, so that the test JVM uses the dev containers instead of starting new ones
    (§2.5).

  The Maven executable is resolved as `RecompileRunner` resolves it.
- **Output.** stdout and stderr go to `target/vidocq-dev-tests.log`, overwritten per run. The terminal gets one
  summary line (§4.3).
- **Cancelling.** A new change while a run is in flight cancels it: the subprocess tree is destroyed
  (`ProcessHandle.descendants()` first, then the process). The next run starts after the new recompile or reload.
- **Serialisation.** At most one run at a time. A request (§3.3) received during a run is queued: the last request
  wins, and it runs after the current one, without cancelling it.
- **Threading.** Runs happen on a dedicated thread. The reload loop of `vidocq:dev` never waits on a test run.

### 2.4 Results

When the subprocess ends, `SurefireReports` reads `target/surefire-reports/TEST-*.xml`, keeping only files
modified at or after the run's start. It produces counts (run, failures, errors, skipped) and, for each failure or
error:

- class and method;
- exception type;
- the first line of the message, cut to 200 characters and passed through `SecretMasking.withoutCredentials`.

`TestResultsFile` then writes `target/vidocq-dev-tests.json` atomically, as `StateFile` does (#123):

```json
{
  "state": "failed",
  "trigger": "change",
  "startedAt": "2026-09-25T10:12:03Z",
  "durationMillis": 3210,
  "counts": {"run": 42, "failures": 1, "errors": 0, "skipped": 0},
  "failures": [
    {"test": "com.acme.OrderServiceTest#rejectsEmptyCart", "type": "org.opentest4j.AssertionFailedError",
     "message": "expected: <400> but was: <200>"}
  ],
  "log": "target/vidocq-dev-tests.log"
}
```

The fields take these values:

- `state` is one of:
  - `running`, written when a run starts, with counts from the previous run kept under `previous`;
  - `passed` or `failed`;
  - `compile-error`: non-zero exit and no fresh report;
  - `no-tests`;
  - `cancelled`, when the previous complete result is kept under `previous`.
- `trigger` is one of `change`, `test-change`, `run-all`, `rerun-failed`.

### 2.5 Dev services

- **`vidocq:dev`** hosts the dev services session, as today. The test subprocess receives the session's injected
  keys and `vidocq.dev.devServices=false`, so the tests use the running containers.
- **`vidocq:test`** opens a `DevServicesSession` itself, host `vidocq:test`, once for its whole life. It closes it on
  exit (`q`, Ctrl+C, the end of the goal), with the synchronized close pattern of #123. Every run reuses it the same
  way.
- **Shared data.** The tests share the database with the running application (`vidocq:dev`) or with the other runs
  (`vidocq:test`). A test that deletes rows deletes the dev data. This is documented, and it is the maintainer's
  decision.

## 3. `vidocq:dev`: the dev console

### 3.1 The `tests` panel

`TestsPanel` is one of the console's own panels, like `logs` and `cdi`: DEV launches only, placed before `jvm`.
`tests` is added to the core's reserved ids.

- **Reading.** A small daemon thread watches `target/vidocq-dev-tests.json`, polling its mtime every 500 ms. When the
  file changes, the thread parses it into an immutable snapshot of strings held in a `volatile` field. `sample()`
  reads that field only: no I/O. The thread stops in `onStop`.
- **File location.** The panel learns the file's path from `-Dvidocq.dev.tests.results=<absolute path>`, which the
  plugin passes to the child. With no property, the panel is not shown.
- **Boot facts.** The state, the trigger, the time and duration of the last run, and the log path.
- **Values:**
  - `run`, `failures`, `errors` and `skipped` as gauges;
  - `state` as text;
  - a `failures` table (test, type, message), capped at 100 rows;
  - while a run is in progress, `running` shown next to the previous counts.
- **Chart.** "Tests" plots the failures and errors gauges over time.

### 3.2 Actions (ADR 0001)

- `run-all`, labelled "Run all tests", with no arguments.
- `rerun-failed`, labelled "Rerun failed tests", with no arguments. When the last result has no failure it returns
  `no failed test to rerun` and writes no request.

Both return at once, with `queued`. The outcome shows in the panel once the plugin has run.

### 3.3 The request file

The action writes `target/vidocq-dev-tests.request` atomically. Its content is one word, `run-all` or
`rerun-failed`. Its location is derived from the results file path, `<same dir>/vidocq-dev-tests.request`.

The plugin polls for the file every 250 ms, on the same loop as the watcher, reads it, deletes it, and queues the
request. For `rerun-failed`, the plugin computes the test list itself, from the last results. The console never
sends a test name, which keeps with ADR 0001: an action takes no path, class or URL from the request. An unknown
content is ignored and logged once.

## 4. `vidocq:test`: the terminal

### 4.1 The goal

`VidocqTestMojo` is the goal `test`:

- it has the same project parameters as `vidocq:dev` where relevant: base dir, watch directories, debounce;
- no application JVM, no dev console, no debug agent;
- on start, it opens the dev services session, runs the tests once, then watches;
- its descriptor entries go in the hand-written `META-INF/maven/plugin.xml`, with the descriptor test updated.

### 4.2 Keys

`TerminalControls` reads single keys from standard input when `System.console() != null`, in line mode:

| Key | Effect |
|---|---|
| `r` | Rerun all tests |
| `f` | Rerun the failed tests |
| `q` | Quit |
| Enter | Rerun all tests |

A line whose first character is one of these runs that command, so that a key followed by Enter works too. Without
a console (CI, pipes, an IDE's run window), no key is read and the goal only watches. Ctrl+C always stops the goal
and its session.

### 4.3 Output

`TestSummaryPrinter` prints, after each run:

```
Tests: 41 passed, 1 failed, 0 skipped in 3.2 s (change)
  FAILED com.acme.OrderServiceTest#rejectsEmptyCart — AssertionFailedError: expected: <400> but was: <200>
Log: target/vidocq-dev-tests.log   [r] run all  [f] rerun failed  [q] quit
```

- `vidocq:dev` prints the first line only, with the `Tests:` prefix.
- `vidocq:test` prints all three lines, and the key hints only when a console is attached.

`compile-error`, `no-tests` and `cancelled` each get a one-line equivalent.

## 5. Units

| Unit | Module / package | Responsibility |
|---|---|---|
| `TestRunner` | plugin, `io.vidocq.runtime.maven.dev` | Build and start the Surefire subprocess (all, or a test list); cancel it; wait for its exit code |
| `SurefireReports` | plugin | Parse the fresh `TEST-*.xml` into counts and failures |
| `TestResults` (record) + `TestResultsFile` | plugin | The result model; write the JSON atomically, masking messages |
| `TestRequestFile` | plugin + devconsole (the contract is two strings and a file name; each side has its own small class) | Write, poll, consume the request |
| `ContinuousTesting` | plugin | Orchestrate: triggers, serialisation, cancel, queued request, the test list for reruns; used by both goals |
| `VidocqTestMojo` | plugin | The `test` goal |
| `TerminalControls` | plugin | Keys from standard input |
| `TestSummaryPrinter` | plugin | Terminal lines |
| `TestsPanel` | devconsole extension | The panel, its reader thread, its actions |

`VidocqDevMojo` gains:

- the test watch directories;
- the continuous testing switch;
- the `-Dvidocq.dev.tests.results` property for the child;
- the hook that starts a run after each reload.

## 6. Error handling

| Situation | Behaviour |
|---|---|
| Test sources do not compile | `compile-error`; the panel and the terminal point to the log; the app keeps running |
| No test at all | `no-tests`, no error |
| A change during a run | Cancel the run and its subprocess tree, `cancelled`, run again after the reload |
| A request during a run | Queued (last wins), runs after, never cancels |
| Unknown request content | Ignored, logged once |
| Surefire reports unreadable | `compile-error` if the exit code is non-zero, else `passed` with a warning in the log; never an exception out of the loop |
| Results file unreadable in the panel | The panel keeps its previous snapshot and shows `results unreadable` as text |
| `vidocq:test` without a console | No key reading; watching only |
| The goal ends while a run is in flight | Cancel the run, close the dev services session once |

## 7. Testing

- **Unit, plugin:**
  - `SurefireReports` on real Surefire XML fixtures: pass, failure, error, skipped, a stale report older than the
    run, a malformed file;
  - `TestResultsFile`: JSON shape, message masking, atomic write;
  - `TestRunner` command line (all, rerun list, the injected `-D`s, `vidocq.dev.devServices=false`);
  - `ContinuousTesting` with a fake runner: a main change reloads then tests, a test change only tests, a change
    cancels a run, a request queued during a run runs after it, a rerun uses the last failures;
  - `TerminalControls` on a fake input stream;
  - `TestSummaryPrinter` lines;
  - the switch resolution;
  - the `plugin.xml` descriptor for the new goal and parameters.
- **Unit, devconsole:** `TestsPanel`, covering reading, snapshot swap, sample values, both actions (the request file
  written or not), and no panel without the property.
- **Integration, `vidocq-runtime-it-continuous-testing`** (new, no Docker):
  - a tiny app with one passing and one failing test;
  - **`vidocq:dev`:** the dev console snapshot shows the `tests` panel with one failure. Rewriting the failing test
    to pass turns the panel green **without an app reload** (the app's boot time does not change). The
    `rerun-failed` action runs only that test. Assert on the JSON's counts.
  - **`vidocq:test`:** the summary line appears in the log. Fixing the test gives `passed` in the JSON.
  - Ports 18093-18099, checked free; every process started is stopped.

## 8. Limits

- Every run is a full `mvn` start: several seconds, and more on a big suite. The docs say so, and name
  `vidocq.dev.continuousTesting=false` for the dev loop.
- Tests share the dev database (§2.5).
- `failsafe` integration tests are not run, only Surefire's.
- Keys need a real console: an IDE's run window may not deliver single keys.

## 9. Documentation

The documentation is part of the deliverable:

- **New page `docs/en/modules/ROOT/pages/continuous-testing.adoc`**, with an entry in `nav.adoc`, covering:
  - what it does and when it runs;
  - `vidocq:dev` versus `vidocq:test`;
  - the switch and its resolution;
  - the watch directories;
  - the results and log files;
  - the terminal keys;
  - the dev services sharing, as a WARNING box;
  - the limits;
  - how to turn it off.
- **`modules/vidocq-runtime-maven-plugin.adoc`:** a `[#test]` section for the `vidocq:test` goal (parameters table,
  example session output) in the Goals list, and the new `vidocq:dev` parameters.
- **`dev-console.adoc`:** the `tests` panel subsection (values, actions, what it never shows), and a row in the
  panels table.
- **`dev-console-panels.adoc` and every other list of reserved ids:** `tests` added.
- **`dev-services.adoc` / `DEV_SERVICES.md`:** a short "Continuous testing" note: the tests reuse the dev
  containers.
- **`whats-new.adoc`:** one bullet, linking the new page.

Every new section carries `[.tag-new]#NEW#`. English only.
