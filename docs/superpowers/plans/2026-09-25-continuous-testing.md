# Continuous testing: `vidocq:dev` tests and a `vidocq:test` goal — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run the application's Surefire tests on every change, in the background of `vidocq:dev` (with a `tests`
dev console panel and its actions) and in the foreground of a new `vidocq:test` goal (with a terminal summary and
keys), and document both.

**Architecture:** The Maven plugin owns the loop. `ContinuousTesting` serialises the runs on one worker thread. It
cancels a run on each change and queues requests. `TestRunner` starts `mvn test-compile surefire:test` as a
subprocess. `SurefireReports` reads the `TEST-*.xml` files, and `TestResultsFile` writes
`target/vidocq-dev-tests.json`.

The dev console extension never talks to the plugin. Its `tests` panel reads that JSON file. Its actions write a
one-word request file, which the plugin polls.

A small core change tells the plugin when a hot reload finished booting. The plugin needs this to start the tests
after the reload, as spec §2.2 requires.

**Tech Stack:** Java 25, Maven 3.9 (hand-written `plugin.xml`), JUnit 5, Surefire 3.x as a subprocess, JPMS,
Vidocq dev console SPI (ADR 0001 actions), `vidocq-runtime-devservices-host` (#123). The integration test uses
maven-invoker-plugin (`install` only) and Failsafe.

**Spec:** `docs/superpowers/specs/2026-09-25-continuous-testing-design.md` (read it first; section numbers below
refer to it).

## Global Constraints

- Java 25. Every Maven call starts with
  `export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH;`. Use `mvn`,
  never `./mvnw`. Add `-o` unless a download is needed. A long Maven run may be blocked by the shell hook: run it
  through the context-mode execute tool or a wrapper script, as in #123.
- Work on branch `feat/122-continuous-testing`, created from `docs/122-continuous-testing-design` (the spec and
  this plan travel with it), in `~/projects/perso/vidocq/vidocq`. Never in a hidden worktree.
- File names and keys, verbatim (spec §2–§3):
  - results `target/vidocq-dev-tests.json`, log `target/vidocq-dev-tests.log`, request
    `target/vidocq-dev-tests.request` (a sibling of the results file);
  - the child property `vidocq.dev.tests.results=<absolute path>`;
  - the switch `vidocq.dev.continuousTesting`, the parameter `vidocq.dev.testWatchDirs` (default
    `src/test/java,src/test/resources`).
- Wire values, verbatim:
  - states `running`, `passed`, `failed`, `compile-error`, `no-tests`, `cancelled`;
  - triggers `change`, `test-change`, `run-all`, `rerun-failed`;
  - request words `run-all` and `rerun-failed`;
  - action answers `queued` and `no failed test to rerun`.
- The test command, in this order:
  `<maven> -ntp -o -q test-compile surefire:test -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
  [-Dtest=<A#m,B#n>] [-D<dev key>=<value>…] -Dvidocq.dev.devServices=false`.
- A failure message is its first line, masked by `SecretMasking.withoutCredentials`, then cut to 200 characters.
  Nothing unmasked reaches the JSON, the terminal or the panel. The test command line, which may hold a dev
  password, is never logged.
- The dev console never sends a test name. An action takes no argument, and the plugin computes the rerun list
  itself (ADR 0001).
- Code, Javadoc, comments, commit messages and docs are in English. Lines are at most 120 characters.
- Every new Java file starts with the 19-line license header copied verbatim from
  `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/RecompileRunner.java` (lines 1-19). The
  code blocks below omit it.
- Commits: write the message to a file in the scratchpad, then `git commit -S -F <msgfile>`, never `-m`, never
  `-s`. The message ends with exactly:
  ```
  Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
  ```
- Ports for anything started, by hand or by a test: 18093-18099, checked free first. Never 8080 or 8888, never
  5005 (pass `-Dvidocq.dev.debug=false` to every `vidocq:dev` a test starts). Stop every process you start.
- Docs: every new section carries `[.tag-new]#NEW#`, and `whats-new.adoc` gets one bullet.

## Rulings (where this plan settles what the spec leaves open)

1. **Reload readiness (spec §2.2).** Today the plugin touches the reload file and learns nothing more. So
   `VidocqDevReloadLoop` (core) now writes `<reload file>.ready`, containing the signal's stamp, after each
   successful boot. `ReloadAck` in the plugin waits for it, for 2 minutes at most, and then runs the tests anyway
   with a warning. This is the one change outside the plugin and the console.

   In respawn mode (`vidocq.dev.hotReload=false` or `vidocq.dev.layer=false`) there is no reload file. The tests
   start once the new child process has started.
2. **A first run at start.** Both goals run all the tests once at start: `vidocq:dev` after the first boot,
   `vidocq:test` immediately. The trigger is `run-all`. Without this, the panel is empty until the first edit, and
   the IT (spec §7) expects a failure without any edit.
3. **`vidocq:test` does not call `RecompileRunner`.** Its `test-compile` phase already compiles and indexes the
   main sources, so a main change is recompiled by the test run itself.
4. **The request file is polled on its own daemon thread**, `vidocq-tests-requests`, every 250 ms. The watcher's
   thread blocks in `WatchService.take()`, and a request must be seen during a run.
5. **Stale reports.** `TestRunner` deletes `target/surefire-reports/TEST-*.xml` before each run. `SurefireReports`
   still filters by modification time, with a 2 s slack for file systems that store whole seconds. Without the
   deletion, a rerun of one test would count the reports of every other class.
6. **"A console is attached" means `System.console() != null && System.console().isTerminal()`.** Since JDK 22,
   `System.console()` returns a `Console` even when stdin is a pipe.
7. **A main change whose recompile fails** cancels the run in flight and starts no run. The recompile output
   already shows the error.
8. **Readable reports with no failure, but a non-zero exit** (a forked JVM that crashed) give `failed` with a
   warning, never `passed`.
9. **`vidocq:test` honours `vidocq.dev.devServices`** by the same rule as `vidocq:dev`: explicit value, then the
   application's files, then on.
10. **Masking happens in `SurefireReports`**, where the message is read. `TestResultsFile` and
    `TestSummaryPrinter` only handle masked text. The masking tests live in `SurefireReportsTest`.
11. **Panel value keys.** The gauges are `run`, `failures`, `errors` and `skipped`, and the table is
    `failed-tests`. One panel scope cannot hold two values with the key `failures`, so the table cannot reuse it.
12. **The IT module** runs only under a profile `it`, active on CI (`env.CI`) or with `-Pit`, like the plugin's
    invoker tests. `maven-invoker-plugin:install` stages the fresh plugin in `target/it-repo`, and the fixture
    project runs from a temp directory, so no parent `mvnw` is picked up. The tests run with Failsafe.

## Review Focus

1. **The test JVM must see `-Dvidocq.dev.devServices=false` and the dev keys.** Surefire has to pass Maven user
   properties to its forked JVM; otherwise the JUnit host of #123 starts a second set of containers. Pinned by
   the fixture test `GreeterTest.runsWithoutDevServicesOfItsOwn` (Task 9).
2. **Parameterised and repeated tests in a rerun.** Surefire names them `adds(int)[1]`, which `-Dtest` never
   matches. The rerun list must cut the name to `Class#method`, once per method. Test in Task 1.
3. **A forked JVM crash.** The reports are readable and show no failure, but the exit code is non-zero. That must
   read as `failed`, never `passed`. Test in Task 1.
4. **`vidocq:test` with piped stdin**, as in CI or an IDE's run window: no key thread, and no `[r] run all` hint.
   Test in Task 5 (surefire's own fork has a piped stdin) and Task 9.
5. **A request file left by a killed session** must be deleted when the goal starts, never run. Test in Task 3.

---

## File structure

| File | Responsibility |
|---|---|
| `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/TestResults.java` | Result record, states, triggers, counts; the state decision; the rerun list |
| `…/maven/dev/SurefireReports.java` | Parse fresh `TEST-*.xml` into counts and masked failures |
| `…/maven/dev/TestResultsFile.java` | Names and property; the JSON, written atomically |
| `…/maven/dev/TestRunner.java` | The Surefire subprocess: command, launch, cancel its tree |
| `…/maven/dev/TestControl.java` | What the dev loop asks of the tests: `changed`, `interrupt`; `ReadyGate` |
| `…/maven/dev/ContinuousTesting.java` | Worker thread: serialise, cancel, queue, rerun list, write, publish |
| `…/maven/dev/TestRequestFile.java` | Plugin side of the request file: take, poll, delete a stale one |
| `…/maven/dev/ReloadAck.java` | Plugin side of the reload acknowledgement: the gate |
| `…/maven/dev/SourceWatcher.java` | Modify: watch test dirs too, tell main from test changes |
| `…/maven/dev/TestSummaryPrinter.java` | Terminal lines |
| `…/maven/dev/TerminalControls.java` | `r`/`f`/`q`/Enter from standard input |
| `…/maven/dev/VidocqDevMojo.java` | Modify: switch, test dirs, results property, per-change dispatch, first run |
| `…/maven/dev/VidocqTestMojo.java` | The `test` goal |
| `…/src/main/resources/META-INF/maven/plugin.xml` | Modify: two `dev` parameters, the `test` mojo |
| `vidocq-runtime-core/src/main/java/io/vidocq/runtime/core/VidocqDevReloadLoop.java` | Modify: write `<reload file>.ready` after each boot |
| `vidocq-runtime-core/src/main/java/io/vidocq/runtime/core/report/StartupContributors.java` | Modify: reserve `tests` |
| `vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/main/java/io/vidocq/runtime/devservices/host/DevServicesFlag.java` | Modify: the same rule for any key |
| `vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/java/io/vidocq/runtime/extensions/essentials/devconsole/TestsPanel.java` | The panel, its reader thread, its actions |
| `…/devconsole/TestRequestFile.java` | Console side of the request file: write it atomically |
| `…/devconsole/DevConsoleExtension.java` | Modify: start, place and stop the panel |
| `vidocq-runtime-integration-tests/vidocq-runtime-it-continuous-testing/**` | New IT module and its fixture project |
| `vidocq-runtime-integration-tests/pom.xml` | Modify: the `it` profile listing the module |
| `docs/en/modules/ROOT/pages/continuous-testing.adoc` + `nav.adoc` + other pages, `DEV_SERVICES.md`, `CLAUDE.md` | Docs (§9) |

Paths below abbreviate `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev` as `PLUGIN/`,
its test twin as `PLUGIN_TEST/`, and
`vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/{main,test}/java/io/vidocq/runtime/extensions/essentials/devconsole`
as `CONSOLE/` and `CONSOLE_TEST/`.

---

### Task 1: The result model, the Surefire reports and the results file

**Files:**
- Create: `PLUGIN/TestResults.java`, `PLUGIN/SurefireReports.java`, `PLUGIN/TestResultsFile.java`
- Test: `PLUGIN_TEST/TestResultsTest.java`, `PLUGIN_TEST/SurefireReportsTest.java`, `PLUGIN_TEST/TestResultsFileTest.java`

**Interfaces:**
- Consumes: `io.vidocq.runtime.devservices.host.SecretMasking.withoutCredentials(String)`,
  `io.vidocq.runtime.devservices.host.StateFile.write(Path, String)` (atomic write).
- Produces (package `io.vidocq.runtime.maven.dev`, all package-private):
  - `record TestResults(State state, Trigger trigger, Instant startedAt, long durationMillis, Counts counts,
    List<Failure> failures, String log, TestResults previous)`:
    - `enum State {RUNNING, PASSED, FAILED, COMPILE_ERROR, NO_TESTS, CANCELLED}`, with `String wire()` and
      `boolean complete()`;
    - `enum Trigger {CHANGE, TEST_CHANGE, RUN_ALL, RERUN_FAILED}`, with `String wire()` and
      `static Optional<Trigger> ofRequest(String word)`;
    - `record Counts(int run, int failures, int errors, int skipped)`, with `Counts.NONE` and `int passed()`;
    - `record Failure(String test, String type, String message)`;
    - `static TestResults running(Trigger, Instant, String log, TestResults previous)`;
    - `static TestResults cancelled(Trigger, Instant, long durationMillis, String log, TestResults previous)`;
    - `static TestResults completed(Trigger, Instant, long durationMillis, int exitCode, SurefireReports.Reports,
      String log, Consumer<String> warn)`;
    - `List<String> rerunList()`.
  - `final class SurefireReports`:
    - `record Reports(TestResults.Counts counts, List<TestResults.Failure> failures, int readable, int unreadable)`,
      with `Reports.NONE`;
    - `static Reports read(Path reportsDir, Instant since)`;
    - `static boolean isReport(Path file)`;
    - `static String firstLine(String text)`;
    - `static final int MAX_MESSAGE = 200`.
  - `final class TestResultsFile`:
    - constants `FILE_NAME = "vidocq-dev-tests.json"`, `LOG_NAME = "vidocq-dev-tests.log"` and
      `PROPERTY = "vidocq.dev.tests.results"`;
    - `static String json(TestResults)`;
    - `static void write(Path, TestResults) throws IOException`.

- [ ] **Step 1: Write the failing tests**

`PLUGIN_TEST/SurefireReportsTest.java`:

```java
package io.vidocq.runtime.maven.dev;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurefireReportsTest {

    private static final String PASSING = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.acme.CartTest" time="0.12" tests="2" errors="0" skipped="0" failures="0">
              <properties><property name="java.version" value="25"/></properties>
              <testcase name="addsAnItem" classname="com.acme.CartTest" time="0.01"/>
              <testcase name="removesAnItem" classname="com.acme.CartTest" time="0.01"/>
            </testsuite>
            """;

    private static final String FAILING = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.acme.OrderServiceTest" tests="3" errors="1" skipped="1" failures="1">
              <testcase name="rejectsEmptyCart" classname="com.acme.OrderServiceTest" time="0.02">
                <failure message="expected: &lt;400&gt; but was: &lt;200&gt;" \
            type="org.opentest4j.AssertionFailedError"><![CDATA[org.opentest4j.AssertionFailedError: expected: <400>
            	at com.acme.OrderServiceTest.rejectsEmptyCart(OrderServiceTest.java:31)
            ]]></failure>
              </testcase>
              <testcase name="connects" classname="com.acme.OrderServiceTest" time="0.01">
                <error message="refused by jdbc:postgresql://dev:s3cret@localhost:5432/db&#10;second line" \
            type="java.sql.SQLException"><![CDATA[java.sql.SQLException: refused]]></error>
              </testcase>
              <testcase name="later" classname="com.acme.OrderServiceTest" time="0">
                <skipped message="not yet"/>
              </testcase>
            </testsuite>
            """;

    private static final Instant SINCE = Instant.parse("2026-09-25T10:12:03Z");

    private static Path report(Path dir, String name, String xml, Instant modified) throws Exception {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.writeString(file, xml);
        Files.setLastModifiedTime(file, FileTime.from(modified));
        return file;
    }

    @Test
    void countsEveryTestcaseOfTheFreshReports(@TempDir Path dir) throws Exception {
        report(dir, "TEST-com.acme.CartTest.xml", PASSING, SINCE.plusSeconds(3));
        report(dir, "TEST-com.acme.OrderServiceTest.xml", FAILING, SINCE.plusSeconds(3));

        SurefireReports.Reports reports = SurefireReports.read(dir, SINCE);

        assertEquals(new TestResults.Counts(5, 1, 1, 1), reports.counts());
        assertEquals(2, reports.readable());
        assertEquals(0, reports.unreadable());
        assertEquals(List.of("com.acme.OrderServiceTest#rejectsEmptyCart", "com.acme.OrderServiceTest#connects"),
                reports.failures().stream().map(TestResults.Failure::test).toList());
        TestResults.Failure failure = reports.failures().getFirst();
        assertEquals("org.opentest4j.AssertionFailedError", failure.type());
        assertEquals("expected: <400> but was: <200>", failure.message());
    }

    @Test
    void aMessageKeepsItsFirstLineWithoutCredentials(@TempDir Path dir) throws Exception {
        report(dir, "TEST-com.acme.OrderServiceTest.xml", FAILING, SINCE.plusSeconds(3));

        String message = SurefireReports.read(dir, SINCE).failures().get(1).message();

        assertTrue(message.startsWith("refused by jdbc:postgresql://"), message);
        assertFalse(message.contains("s3cret"), "the password of the URL: " + message);
        assertFalse(message.contains("second line"), message);
    }

    @Test
    void aLongMessageIsCutTo200Characters() {
        assertEquals(SurefireReports.MAX_MESSAGE, SurefireReports.firstLine("x".repeat(500)).length());
        assertEquals("", SurefireReports.firstLine(null));
    }

    @Test
    void aReportOlderThanTheRunIsIgnored(@TempDir Path dir) throws Exception {
        report(dir, "TEST-com.acme.CartTest.xml", PASSING, SINCE.minus(Duration.ofHours(1)));

        SurefireReports.Reports reports = SurefireReports.read(dir, SINCE);

        assertEquals(0, reports.readable());
        assertEquals(TestResults.Counts.NONE, reports.counts());
    }

    @Test
    void aMalformedReportIsCountedUnreadable(@TempDir Path dir) throws Exception {
        report(dir, "TEST-com.acme.CartTest.xml", PASSING, SINCE.plusSeconds(1));
        report(dir, "TEST-com.acme.Broken.xml", "<testsuite><testcase", SINCE.plusSeconds(1));

        SurefireReports.Reports reports = SurefireReports.read(dir, SINCE);

        assertEquals(1, reports.readable());
        assertEquals(1, reports.unreadable());
        assertEquals(2, reports.counts().run());
    }

    @Test
    void noDirectoryMeansNoReport(@TempDir Path dir) {
        assertEquals(SurefireReports.Reports.NONE, SurefireReports.read(dir.resolve("absent"), SINCE));
    }

    @Test
    void onlyTestXmlFilesAreReports() {
        assertTrue(SurefireReports.isReport(Path.of("TEST-a.B.xml")));
        assertFalse(SurefireReports.isReport(Path.of("a.B.txt")));
        assertFalse(SurefireReports.isReport(Path.of("TEST-a.B-jvmRun1.dump")));
    }
}
```

`PLUGIN_TEST/TestResultsTest.java`:

```java
package io.vidocq.runtime.maven.dev;

import io.vidocq.runtime.maven.dev.TestResults.Counts;
import io.vidocq.runtime.maven.dev.TestResults.Failure;
import io.vidocq.runtime.maven.dev.TestResults.State;
import io.vidocq.runtime.maven.dev.TestResults.Trigger;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestResultsTest {

    private static final Instant AT = Instant.parse("2026-09-25T10:12:03Z");
    private static final String LOG = "target/vidocq-dev-tests.log";

    private final List<String> warnings = new ArrayList<>();

    private TestResults completed(int exit, SurefireReports.Reports reports) {
        return TestResults.completed(Trigger.CHANGE, AT, 3210, exit, reports, LOG, warnings::add);
    }

    private static SurefireReports.Reports reports(Counts counts, List<Failure> failures, int readable,
            int unreadable) {
        return new SurefireReports.Reports(counts, failures, readable, unreadable);
    }

    private static final Failure FAILURE =
            new Failure("com.acme.OrderServiceTest#rejectsEmptyCart", "org.opentest4j.AssertionFailedError", "no");

    @Test
    void allGreenIsPassed() {
        assertEquals(State.PASSED, completed(0, reports(new Counts(3, 0, 0, 1), List.of(), 1, 0)).state());
        assertEquals(List.of(), warnings);
    }

    @Test
    void aFailureOrAnErrorIsFailed() {
        assertEquals(State.FAILED, completed(1, reports(new Counts(3, 1, 0, 0), List.of(FAILURE), 1, 0)).state());
        assertEquals(State.FAILED, completed(1, reports(new Counts(3, 0, 1, 0), List.of(FAILURE), 1, 0)).state());
    }

    @Test
    void noReportAndANonZeroExitIsACompileError() {
        assertEquals(State.COMPILE_ERROR, completed(1, SurefireReports.Reports.NONE).state());
    }

    @Test
    void noReportAndExitZeroIsNoTests() {
        assertEquals(State.NO_TESTS, completed(0, SurefireReports.Reports.NONE).state());
        assertEquals(State.NO_TESTS, completed(0, reports(Counts.NONE, List.of(), 1, 0)).state());
    }

    @Test
    void unreadableReportsAndExitZeroArePassedWithAWarning() {
        assertEquals(State.PASSED, completed(0, reports(Counts.NONE, List.of(), 0, 2)).state());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains(LOG), warnings.getFirst());
    }

    @Test
    void aCrashedForkWithNoFailureIsFailedNeverPassed() {
        assertEquals(State.FAILED, completed(1, reports(new Counts(3, 0, 0, 0), List.of(), 1, 0)).state());
        assertTrue(warnings.getFirst().contains("exited with 1"), warnings.getFirst());
    }

    @Test
    void theRerunListCutsParameterisedNamesAndKeepsEachMethodOnce() {
        TestResults failed = completed(1, reports(new Counts(4, 3, 0, 0), List.of(
                new Failure("com.acme.MathTest#adds(int)[1]", "T", "m"),
                new Failure("com.acme.MathTest#adds(int)[2]", "T", "m"),
                new Failure("com.acme.MathTest$Nested#divides", "T", "m")), 1, 0));

        assertEquals(List.of("com.acme.MathTest#adds", "com.acme.MathTest$Nested#divides"), failed.rerunList());
    }

    @Test
    void runningAndCancelledKeepAFlatPrevious() {
        TestResults passed = completed(0, reports(new Counts(2, 0, 0, 0), List.of(), 1, 0));
        TestResults running = TestResults.running(Trigger.TEST_CHANGE, AT, LOG, passed);
        TestResults cancelled = TestResults.cancelled(Trigger.TEST_CHANGE, AT, 10, LOG, running);

        assertEquals(State.RUNNING, running.state());
        assertEquals(passed, running.previous());
        assertEquals(State.RUNNING, cancelled.previous().state());
        assertNull(cancelled.previous().previous(), "the file never nests deeper than one level");
    }

    @Test
    void onlyTheTwoRequestWordsAreTriggers() {
        assertEquals(Optional.of(Trigger.RUN_ALL), Trigger.ofRequest("run-all"));
        assertEquals(Optional.of(Trigger.RERUN_FAILED), Trigger.ofRequest("rerun-failed"));
        assertEquals(Optional.empty(), Trigger.ofRequest("change"));
        assertEquals(Optional.empty(), Trigger.ofRequest("rm -rf"));
    }

    @Test
    void theWireValuesAreTheSpecs() {
        assertEquals(List.of("running", "passed", "failed", "compile-error", "no-tests", "cancelled"),
                List.of(State.values()).stream().map(State::wire).toList());
        assertEquals(List.of("change", "test-change", "run-all", "rerun-failed"),
                List.of(Trigger.values()).stream().map(Trigger::wire).toList());
    }
}
```

`PLUGIN_TEST/TestResultsFileTest.java`:

```java
package io.vidocq.runtime.maven.dev;

import io.vidocq.runtime.maven.dev.TestResults.Counts;
import io.vidocq.runtime.maven.dev.TestResults.Failure;
import io.vidocq.runtime.maven.dev.TestResults.Trigger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestResultsFileTest {

    private static final Instant AT = Instant.parse("2026-09-25T10:12:03.456Z");
    private static final String LOG = "target/vidocq-dev-tests.log";

    private static TestResults failed() {
        return TestResults.completed(Trigger.CHANGE, AT, 3210, 1, new SurefireReports.Reports(
                new Counts(42, 1, 0, 0), List.of(new Failure("com.acme.OrderServiceTest#rejectsEmptyCart",
                        "org.opentest4j.AssertionFailedError", "expected: <400> but was: <200>")), 1, 0),
                LOG, warning -> {});
    }

    @Test
    void theDocumentHasTheSpecsShape() {
        assertEquals("{\"state\":\"failed\",\"trigger\":\"change\",\"startedAt\":\"2026-09-25T10:12:03Z\","
                + "\"durationMillis\":3210,\"counts\":{\"run\":42,\"failures\":1,\"errors\":0,\"skipped\":0},"
                + "\"failures\":[{\"test\":\"com.acme.OrderServiceTest#rejectsEmptyCart\","
                + "\"type\":\"org.opentest4j.AssertionFailedError\",\"message\":\"expected: <400> but was: <200>\"}],"
                + "\"log\":\"target/vidocq-dev-tests.log\"}", TestResultsFile.json(failed()));
    }

    @Test
    void runningNestsThePreviousResultOnce() {
        String json = TestResultsFile.json(TestResults.running(Trigger.TEST_CHANGE, AT, LOG, failed()));

        assertTrue(json.startsWith("{\"state\":\"running\",\"trigger\":\"test-change\""), json);
        assertTrue(json.contains(",\"previous\":{\"state\":\"failed\""), json);
        assertEquals(json.indexOf("\"previous\""), json.lastIndexOf("\"previous\""), json);
    }

    @Test
    void stringsAreEscaped() {
        TestResults odd = TestResults.completed(Trigger.CHANGE, AT, 1, 1, new SurefireReports.Reports(
                new Counts(1, 1, 0, 0), List.of(new Failure("a.B#c", "T", "say \"hi\" \\ \t\u0001")), 1, 0),
                LOG, warning -> {});

        assertTrue(TestResultsFile.json(odd).contains("\"message\":\"say \\\"hi\\\" \\\\ \\t\\u0001\""),
                TestResultsFile.json(odd));
    }

    @Test
    void writingReplacesTheFileAndLeavesNoTemporaryFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(TestResultsFile.FILE_NAME);
        TestResultsFile.write(file, TestResults.running(Trigger.RUN_ALL, AT, LOG, null));
        TestResultsFile.write(file, failed());

        assertEquals(TestResultsFile.json(failed()), Files.readString(file));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(TestResultsFile.FILE_NAME), files.map(p -> p.getFileName().toString()).toList());
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -q -pl vidocq-runtime-maven-plugin test -Dtest='SurefireReportsTest,TestResultsTest,TestResultsFileTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAILURE, `cannot find symbol: class TestResults`.

- [ ] **Step 3: Write the implementation**

`PLUGIN/TestResults.java`:

```java
package io.vidocq.runtime.maven.dev;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One continuous-testing run, as {@code target/vidocq-dev-tests.json} holds it (spec §2.4): its state, what
 * triggered it, when it started and how long it took, its counts, its failures and errors, the log, and — for a run
 * still {@code running} or {@code cancelled} — the previous complete result, never nested deeper than that.
 *
 * @param previous the previous complete result for {@code running} and {@code cancelled}, else {@code null}
 */
record TestResults(State state, Trigger trigger, Instant startedAt, long durationMillis, Counts counts,
        List<Failure> failures, String log, TestResults previous) {

    /** {@code state} in the results file. */
    enum State {
        RUNNING("running"),
        PASSED("passed"),
        FAILED("failed"),
        COMPILE_ERROR("compile-error"),
        NO_TESTS("no-tests"),
        CANCELLED("cancelled");

        private final String wire;

        State(String wire) {
            this.wire = wire;
        }

        String wire() {
            return wire;
        }

        /** Whether the run is over and whole: only such a result is kept as {@code previous} and for a rerun. */
        boolean complete() {
            return this != RUNNING && this != CANCELLED;
        }
    }

    /** {@code trigger} in the results file: what started the run. */
    enum Trigger {
        CHANGE("change"),
        TEST_CHANGE("test-change"),
        RUN_ALL("run-all"),
        RERUN_FAILED("rerun-failed");

        private final String wire;

        Trigger(String wire) {
            this.wire = wire;
        }

        String wire() {
            return wire;
        }

        /** The trigger a request names: {@code run-all} or {@code rerun-failed}, nothing else (spec §3.3). */
        static Optional<Trigger> ofRequest(String word) {
            return switch (word) {
                case "run-all" -> Optional.of(RUN_ALL);
                case "rerun-failed" -> Optional.of(RERUN_FAILED);
                default -> Optional.empty();
            };
        }
    }

    /** The testcases of a run: all of them, the failed, the in error, and the skipped. */
    record Counts(int run, int failures, int errors, int skipped) {

        static final Counts NONE = new Counts(0, 0, 0, 0);

        int passed() {
            return run - failures - errors - skipped;
        }
    }

    /**
     * A failed or erroneous test.
     *
     * @param test    {@code <class>#<method>}, as Surefire names the testcase
     * @param type    the exception's class
     * @param message the first line of its message, masked and cut (see {@link SurefireReports#firstLine})
     */
    record Failure(String test, String type, String message) {}

    TestResults {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(counts, "counts");
        Objects.requireNonNull(log, "log");
        failures = List.copyOf(failures);
    }

    /** A run that has just started; the previous complete result is kept for the panel and the terminal. */
    static TestResults running(Trigger trigger, Instant startedAt, String log, TestResults previous) {
        return new TestResults(State.RUNNING, trigger, startedAt, 0, Counts.NONE, List.of(), log, flat(previous));
    }

    /** A run a newer change stopped, or the goal's end; its previous result is kept. */
    static TestResults cancelled(Trigger trigger, Instant startedAt, long durationMillis, String log,
            TestResults previous) {
        return new TestResults(State.CANCELLED, trigger, startedAt, durationMillis, Counts.NONE, List.of(), log,
                flat(previous));
    }

    /**
     * A run whose subprocess exited (spec §2.4, §6): no report at all is {@code compile-error} after a non-zero exit
     * and {@code no-tests} after a zero one, unless reports were there but unreadable ({@code passed}, with a
     * warning); a failure or an error is {@code failed}; so is a non-zero exit without either, such as a forked JVM
     * that crashed, with a warning.
     */
    static TestResults completed(Trigger trigger, Instant startedAt, long durationMillis, int exitCode,
            SurefireReports.Reports reports, String log, Consumer<String> warn) {
        if (reports.unreadable() > 0) {
            warn.accept("Tests: " + reports.unreadable() + " Surefire report(s) could not be read; see " + log);
        }
        Counts counts = reports.counts();
        State state;
        if (reports.readable() == 0 || counts.run() == 0) {
            state = exitCode != 0 ? State.COMPILE_ERROR
                    : reports.unreadable() > 0 ? State.PASSED : State.NO_TESTS;
        } else if (counts.failures() + counts.errors() > 0) {
            state = State.FAILED;
        } else if (exitCode != 0) {
            warn.accept("Tests: Maven exited with " + exitCode + " although no test failed; see " + log);
            state = State.FAILED;
        } else {
            state = State.PASSED;
        }
        return new TestResults(state, trigger, startedAt, durationMillis, counts, reports.failures(), log, null);
    }

    /**
     * The tests to run again, as Surefire's {@code -Dtest} takes them: {@code <class>#<method>}, each once, a
     * parameterised or repeated name such as {@code adds(int)[1]} cut to its method, which is what {@code -Dtest}
     * matches.
     */
    List<String> rerunList() {
        LinkedHashSet<String> tests = new LinkedHashSet<>();
        for (Failure failure : failures) {
            String test = failure.test();
            int cut = firstOf(test, '(', '[');
            tests.add(cut < 0 ? test : test.substring(0, cut));
        }
        return List.copyOf(tests);
    }

    private static int firstOf(String text, char a, char b) {
        int i = text.indexOf(a);
        int j = text.indexOf(b);
        return i < 0 ? j : j < 0 ? i : Math.min(i, j);
    }

    private static TestResults flat(TestResults previous) {
        if (previous == null || previous.previous() == null) {
            return previous;
        }
        return new TestResults(previous.state(), previous.trigger(), previous.startedAt(), previous.durationMillis(),
                previous.counts(), previous.failures(), previous.log(), null);
    }
}
```

`PLUGIN/SurefireReports.java`:

```java
package io.vidocq.runtime.maven.dev;

import io.vidocq.runtime.devservices.host.SecretMasking;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads what one Surefire run wrote in {@code target/surefire-reports} (spec §2.4): every {@code TEST-*.xml} modified
 * at or after the run's start — with {@link #SLACK} for the file systems that keep whole seconds — counted from its
 * {@code <testcase>} elements, and each failure or error with its class, method, exception type and the first line
 * of its message. A report that cannot be parsed is counted, never thrown.
 */
final class SurefireReports {

    /** The longest message kept, in characters. */
    static final int MAX_MESSAGE = 200;
    /** How much older than the run a report may look, for a file system that stores whole seconds. */
    static final Duration SLACK = Duration.ofSeconds(2);

    /**
     * The reports of a run.
     *
     * @param readable   the fresh reports parsed
     * @param unreadable the fresh reports that could not be read
     */
    record Reports(TestResults.Counts counts, List<TestResults.Failure> failures, int readable, int unreadable) {

        static final Reports NONE = new Reports(TestResults.Counts.NONE, List.of(), 0, 0);

        Reports {
            failures = List.copyOf(failures);
        }
    }

    private SurefireReports() {}

    /** Whether {@code file} is a Surefire XML report: {@code TEST-<class>.xml}. */
    static boolean isReport(Path file) {
        String name = file.getFileName().toString();
        return name.startsWith("TEST-") && name.endsWith(".xml");
    }

    /** The reports of {@code reportsDir} written since {@code since}; {@link Reports#NONE} when there is none. */
    static Reports read(Path reportsDir, Instant since) {
        List<Path> files;
        try (Stream<Path> listing = Files.list(reportsDir)) {
            files = listing.filter(SurefireReports::isReport).sorted().toList();
        } catch (IOException noDirectory) {
            return Reports.NONE;
        }
        Instant cutoff = since.minus(SLACK);
        int run = 0;
        int failed = 0;
        int errors = 0;
        int skipped = 0;
        int readable = 0;
        int unreadable = 0;
        List<TestResults.Failure> failures = new ArrayList<>();
        for (Path file : files) {
            try {
                if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                    continue;
                }
                Suite suite = parse(file);
                run += suite.counts().run();
                failed += suite.counts().failures();
                errors += suite.counts().errors();
                skipped += suite.counts().skipped();
                failures.addAll(suite.failures());
                readable++;
            } catch (Exception unreadableReport) {
                unreadable++;
            }
        }
        return new Reports(new TestResults.Counts(run, failed, errors, skipped), failures, readable, unreadable);
    }

    /** One report, counted. */
    private record Suite(TestResults.Counts counts, List<TestResults.Failure> failures) {}

    private static Suite parse(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Element root = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
        NodeList cases = root.getElementsByTagName("testcase");
        int run = 0;
        int failed = 0;
        int errors = 0;
        int skipped = 0;
        List<TestResults.Failure> failures = new ArrayList<>();
        for (int i = 0; i < cases.getLength(); i++) {
            Element testcase = (Element) cases.item(i);
            run++;
            Element failure = child(testcase, "failure");
            Element error = child(testcase, "error");
            if (failure != null || error != null) {
                Element problem = failure != null ? failure : error;
                if (failure != null) {
                    failed++;
                } else {
                    errors++;
                }
                failures.add(new TestResults.Failure(
                        testcase.getAttribute("classname") + "#" + testcase.getAttribute("name"),
                        problem.getAttribute("type"), message(problem)));
            } else if (child(testcase, "skipped") != null) {
                skipped++;
            }
        }
        return new Suite(new TestResults.Counts(run, failed, errors, skipped), failures);
    }

    private static Element child(Element parent, String tag) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals(tag)) {
                return element;
            }
        }
        return null;
    }

    private static String message(Element problem) {
        String message = problem.getAttribute("message");
        return firstLine(message.isEmpty() ? problem.getTextContent() : message);
    }

    /**
     * The first line of {@code text}, without the credentials of a URL ({@link SecretMasking#withoutCredentials}),
     * cut to {@value #MAX_MESSAGE} characters; empty for {@code null}.
     */
    static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        String line = text.strip();
        int end = line.length();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\n' || c == '\r') {
                end = i;
                break;
            }
        }
        String masked = SecretMasking.withoutCredentials(line.substring(0, end).strip());
        return masked.length() <= MAX_MESSAGE ? masked : masked.substring(0, MAX_MESSAGE);
    }
}
```

`PLUGIN/TestResultsFile.java`:

```java
package io.vidocq.runtime.maven.dev;

import io.vidocq.runtime.devservices.host.StateFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;

/**
 * Writes {@code target/vidocq-dev-tests.json} (spec §2.4) by hand, atomically, the way {@link StateFile} writes the
 * dev services state: {@code state}, {@code trigger}, {@code startedAt} (to the second), {@code durationMillis},
 * {@code counts}, {@code failures}, {@code log}, and {@code previous} when there is one. The dev console's
 * {@code tests} panel reads it.
 */
final class TestResultsFile {

    /** The results file's name, under the build directory. */
    static final String FILE_NAME = "vidocq-dev-tests.json";
    /** The test runs' log, overwritten on every run. */
    static final String LOG_NAME = "vidocq-dev-tests.log";
    /** The system property that tells the application's dev console where the results file is. */
    static final String PROPERTY = "vidocq.dev.tests.results";

    private TestResultsFile() {}

    static String json(TestResults results) {
        StringBuilder b = new StringBuilder(256);
        object(b, results);
        return b.toString();
    }

    /** Writes {@code results} to {@code file} atomically: never half a document for the panel to read. */
    static void write(Path file, TestResults results) throws IOException {
        StateFile.write(file, json(results));
    }

    private static void object(StringBuilder b, TestResults r) {
        TestResults.Counts c = r.counts();
        b.append("{\"state\":").append(str(r.state().wire()))
                .append(",\"trigger\":").append(str(r.trigger().wire()))
                .append(",\"startedAt\":").append(str(r.startedAt().truncatedTo(ChronoUnit.SECONDS).toString()))
                .append(",\"durationMillis\":").append(r.durationMillis())
                .append(",\"counts\":{\"run\":").append(c.run())
                .append(",\"failures\":").append(c.failures())
                .append(",\"errors\":").append(c.errors())
                .append(",\"skipped\":").append(c.skipped())
                .append("},\"failures\":[");
        for (int i = 0; i < r.failures().size(); i++) {
            TestResults.Failure f = r.failures().get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"test\":").append(str(f.test()))
                    .append(",\"type\":").append(str(f.type()))
                    .append(",\"message\":").append(str(f.message())).append('}');
        }
        b.append("],\"log\":").append(str(r.log()));
        if (r.previous() != null) {
            b.append(",\"previous\":");
            object(b, r.previous());
        }
        b.append('}');
    }

    private static String str(String v) {
        StringBuilder b = new StringBuilder(v.length() + 2).append('"');
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: the command of Step 2.
Expected: `Tests run: 21, Failures: 0, Errors: 0`, BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/{TestResults,SurefireReports,TestResultsFile}.java \
        vidocq-runtime-maven-plugin/src/test/java/io/vidocq/runtime/maven/dev/{TestResultsTest,SurefireReportsTest,TestResultsFileTest}.java
git commit -S -F <msgfile>   # "feat(plugin): read Surefire reports into continuous-testing results (#122)"
```

---

### Task 2: The Surefire subprocess

**Files:**
- Create: `PLUGIN/TestControl.java`, `PLUGIN/TestRunner.java`
- Create (interfaces only, completed in Task 3): `PLUGIN/ContinuousTesting.java`
- Test: `PLUGIN_TEST/TestRunnerTest.java`

**Interfaces:**
- Consumes: `SurefireReports.isReport(Path)` (Task 1),
  `io.vidocq.runtime.devservices.host.DevServicesFlag.KEY` (`"vidocq.dev.devServices"`).
- Produces:
  - `interface TestControl { void changed(TestResults.Trigger, ReadyGate); void interrupt(); }`, with the nested
    `@FunctionalInterface interface ReadyGate { ReadyGate NOW; boolean await(BooleanSupplier abandoned) throws
    InterruptedException; }`;
  - in `ContinuousTesting`:
    - `interface Launcher { Launched launch(List<String> tests) throws IOException; }`;
    - `interface Launched { int waitFor() throws InterruptedException; void cancel(); }`, where `cancel()` returns
      once the processes are gone;
  - `final class TestRunner implements ContinuousTesting.Launcher`:
    - `TestRunner(Path projectDir, String maven, Map<String,String> properties, Path logFile, Path reportsDir)`;
    - `static List<String> command(String maven, List<String> tests, Map<String,String> properties)`;
    - `static void destroyTree(ProcessHandle root, Duration grace)`.

- [ ] **Step 1: Write the failing test**

`PLUGIN_TEST/TestRunnerTest.java`:

```java
package io.vidocq.runtime.maven.dev;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRunnerTest {

    @Test
    void allTestsRunWithoutDevServicesOfTheirOwn() {
        assertEquals(List.of("mvn", "-ntp", "-o", "-q", "test-compile", "surefire:test",
                        "-DfailIfNoTests=false", "-Dsurefire.failIfNoSpecifiedTests=false",
                        "-Dvidocq.dev.devServices=false"),
                TestRunner.command("mvn", List.of(), Map.of()));
    }

    @Test
    void aRerunNamesItsTestsAndTheDevKeysFollowInOrder() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("vidocq.pool.url", "jdbc:postgresql://localhost:5432/dev");
        keys.put("vidocq.dev.provided.vidocq.pool.url", "postgres");
        keys.put("vidocq.dev.devServices", "true");

        assertEquals(List.of("/w/mvnw", "-ntp", "-o", "-q", "test-compile", "surefire:test",
                        "-DfailIfNoTests=false", "-Dsurefire.failIfNoSpecifiedTests=false",
                        "-Dtest=com.acme.ATest#x,com.acme.BTest#y",
                        "-Dvidocq.pool.url=jdbc:postgresql://localhost:5432/dev",
                        "-Dvidocq.dev.provided.vidocq.pool.url=postgres",
                        "-Dvidocq.dev.devServices=false"),
                TestRunner.command("/w/mvnw", List.of("com.acme.ATest#x", "com.acme.BTest#y"), keys),
                "the dev session's own switch never reaches the tests: they use its containers");
    }

    private static Path script(Path dir, String body) throws Exception {
        Path maven = dir.resolve("fake-mvn");
        Files.writeString(maven, "#!/bin/sh\n" + body);
        assertTrue(maven.toFile().setExecutable(true));
        return maven;
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @Timeout(30)
    void aRunDeletesTheStaleReportsAndLogsItsOutput(@TempDir Path dir) throws Exception {
        Path reports = Files.createDirectories(dir.resolve("target/surefire-reports"));
        Files.writeString(reports.resolve("TEST-com.acme.Old.xml"), "<testsuite/>");
        Files.writeString(reports.resolve("notes.txt"), "kept");
        Path log = dir.resolve("target/vidocq-dev-tests.log");
        TestRunner runner = new TestRunner(dir, script(dir, "echo \"args: $*\"\n").toString(), Map.of(), log,
                reports);

        assertEquals(0, runner.launch(List.of()).waitFor());

        assertFalse(Files.exists(reports.resolve("TEST-com.acme.Old.xml")), "a stale report");
        assertTrue(Files.exists(reports.resolve("notes.txt")), "only the reports are deleted");
        assertTrue(Files.readString(log).contains("args: -ntp -o -q test-compile surefire:test"),
                Files.readString(log));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @Timeout(30)
    void cancellingKillsTheWholeProcessTree(@TempDir Path dir) throws Exception {
        Path maven = script(dir, "sleep 60 &\necho $! > child.pid\nwait\n");
        TestRunner runner = new TestRunner(dir, maven.toString(), Map.of(), dir.resolve("target/t.log"),
                dir.resolve("target/surefire-reports"));
        ContinuousTesting.Launched launched = runner.launch(List.of());
        Path pidFile = dir.resolve("child.pid");
        while (!Files.exists(pidFile) || Files.readString(pidFile).isBlank()) {
            Thread.sleep(50);
        }
        long child = Long.parseLong(Files.readString(pidFile).strip());

        launched.cancel();

        assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false), "the forked test JVM");
        assertNotEquals(0, launched.waitFor());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -o -q -pl vidocq-runtime-maven-plugin test -Dtest=TestRunnerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAILURE, `cannot find symbol: class TestRunner`.

- [ ] **Step 3: Write the implementation**

`PLUGIN/TestControl.java`:

```java
package io.vidocq.runtime.maven.dev;

import java.util.function.BooleanSupplier;

/**
 * What the source loop of {@code vidocq:dev} and {@code vidocq:test} asks of continuous testing (spec §2.2);
 * {@link ContinuousTesting} is the implementation, and the loop's tests use a recording one.
 */
interface TestControl {

    /**
     * A change: the run in flight is cancelled, and a run of every test is due once {@code gate} opens. It
     * replaces any run waiting to start.
     */
    void changed(TestResults.Trigger trigger, ReadyGate gate);

    /** Cancels the run in flight and drops the run waiting to start; returns once its processes are gone. */
    void interrupt();

    /** What a run waits for before starting, such as the end of an application reload. */
    @FunctionalInterface
    interface ReadyGate {

        /** Open at once. */
        ReadyGate NOW = abandoned -> true;

        /**
         * Blocks until the run may start.
         *
         * @param abandoned {@code true} once a newer change or the goal's end made the wait pointless
         * @return {@code true} to run, {@code false} to give up
         */
        boolean await(BooleanSupplier abandoned) throws InterruptedException;
    }
}
```

`PLUGIN/ContinuousTesting.java` (Task 3 fills in the class; for now only the two nested interfaces):

```java
package io.vidocq.runtime.maven.dev;

import java.io.IOException;
import java.util.List;

/** Continuous testing (spec §2); completed in the next step of the plan. */
final class ContinuousTesting {

    /** Starts one run of the tests: all of them, or the listed {@code Class#method}s. */
    interface Launcher {
        Launched launch(List<String> tests) throws IOException;
    }

    /** A run in flight. */
    interface Launched {

        /** Waits for the run to end and returns its exit code. */
        int waitFor() throws InterruptedException;

        /** Stops the run and every process it started; returns once they are gone. */
        void cancel();
    }
}
```

`PLUGIN/TestRunner.java`:

```java
package io.vidocq.runtime.maven.dev;

import io.vidocq.runtime.devservices.host.DevServicesFlag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * One Surefire run as a subprocess (spec §2.3): {@code mvn test-compile surefire:test}, offline and quiet, so the
 * tests run exactly as {@code mvn test} runs them — the same module path, {@code argLine} and JUnit launcher
 * listeners. Its output goes to the log file, overwritten per run; the stale {@code TEST-*.xml} are deleted first,
 * so that the reports found afterwards are this run's. The command line is never logged: it may hold a dev
 * service's password.
 */
final class TestRunner implements ContinuousTesting.Launcher {

    /** How long a cancelled run may take to stop before it is killed. */
    static final Duration GRACE = Duration.ofSeconds(10);

    private final Path projectDir;
    private final String maven;
    private final Map<String, String> properties;
    private final Path logFile;
    private final Path reportsDir;

    /**
     * @param maven      the Maven executable, as {@link RecompileRunner#detectMavenExecutable} finds it
     * @param properties the dev session's keys, passed as {@code -D} in this order
     */
    TestRunner(Path projectDir, String maven, Map<String, String> properties, Path logFile, Path reportsDir) {
        this.projectDir = projectDir;
        this.maven = maven;
        this.properties = new LinkedHashMap<>(properties);
        this.logFile = logFile;
        this.reportsDir = reportsDir;
    }

    /**
     * The command (spec §2.3). {@code vidocq.dev.devServices=false} comes last and a key of that name in
     * {@code properties} is dropped: the tests use the dev session's containers, and never start their own.
     */
    static List<String> command(String maven, List<String> tests, Map<String, String> properties) {
        List<String> cmd = new ArrayList<>(List.of(maven, "-ntp", "-o", "-q", "test-compile", "surefire:test",
                "-DfailIfNoTests=false", "-Dsurefire.failIfNoSpecifiedTests=false"));
        if (!tests.isEmpty()) {
            cmd.add("-Dtest=" + String.join(",", tests));
        }
        properties.forEach((key, value) -> {
            if (!DevServicesFlag.KEY.equals(key)) {
                cmd.add("-D" + key + "=" + value);
            }
        });
        cmd.add("-D" + DevServicesFlag.KEY + "=false");
        return cmd;
    }

    @Override
    public ContinuousTesting.Launched launch(List<String> tests) throws IOException {
        deleteReports(reportsDir);
        Files.createDirectories(logFile.toAbsolutePath().getParent());
        Process process = new ProcessBuilder(command(maven, tests, properties))
                .directory(projectDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();
        return new Running(process);
    }

    /** Deletes the {@code TEST-*.xml} of {@code dir}, and nothing else. */
    static void deleteReports(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> listing = Files.list(dir)) {
            for (Path report : listing.filter(SurefireReports::isReport).toList()) {
                Files.deleteIfExists(report);
            }
        }
    }

    /**
     * Destroys {@code root} and every process it started (spec §2.3): the descendants are listed first, while they
     * are still attached to it, then each gets a {@code SIGTERM}, and whatever is alive after {@code grace} a
     * {@code SIGKILL}.
     */
    static void destroyTree(ProcessHandle root, Duration grace) {
        List<ProcessHandle> tree = new ArrayList<>(root.descendants().toList());
        tree.add(root);
        tree.forEach(ProcessHandle::destroy);
        long deadline = System.nanoTime() + grace.toNanos();
        for (ProcessHandle handle : tree) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                break;
            }
            try {
                handle.onExit().get(left, TimeUnit.NANOSECONDS);
            } catch (TimeoutException | ExecutionException stillThere) {
                // killed below
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }

    private record Running(Process process) implements ContinuousTesting.Launched {

        @Override
        public int waitFor() throws InterruptedException {
            return process.waitFor();
        }

        @Override
        public void cancel() {
            destroyTree(process.toHandle(), GRACE);
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: the command of Step 2.
Expected: `Tests run: 4, Failures: 0, Errors: 0`, BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/{TestControl,TestRunner,ContinuousTesting}.java \
        vidocq-runtime-maven-plugin/src/test/java/io/vidocq/runtime/maven/dev/TestRunnerTest.java
git commit -S -F <msgfile>   # "feat(plugin): run Surefire as a cancellable subprocess (#122)"
```

---

### Task 3: The orchestrator and the plugin side of the request file

**Files:**
- Modify (replace the whole file): `PLUGIN/ContinuousTesting.java`
- Create: `PLUGIN/TestRequestFile.java`
- Test: `PLUGIN_TEST/ContinuousTestingTest.java`, `PLUGIN_TEST/TestRequestFileTest.java`

**Interfaces:**
- Consumes:
  - from Task 1: `TestResults`, `SurefireReports.Reports` and `read`, `TestResultsFile`;
  - from Task 2: `TestRunner`, `TestControl`, `ContinuousTesting.Launcher/Launched`;
  - `RecompileRunner.detectMavenExecutable(Path)`.
- Produces:
  - `final class ContinuousTesting implements TestControl, AutoCloseable`:
    - `ContinuousTesting(Launcher, Function<Instant, SurefireReports.Reports> reports, Path resultsFile,
      String log, Consumer<TestResults> onResult, Consumer<String> warn, Clock clock)`;
    - `static ContinuousTesting forProject(Path projectDir, Path buildDir, Map<String,String> testProperties,
      Consumer<TestResults> onResult, Consumer<String> warn)`;
    - `void start()`, `changed(...)`, `interrupt()`, `String request(TestResults.Trigger)`,
      `TestResults lastComplete()`, `close()`;
    - `QUEUED = "queued"`, `NOTHING_TO_RERUN = "no failed test to rerun"`.
  - `final class TestRequestFile implements AutoCloseable`:
    - `FILE_NAME = "vidocq-dev-tests.request"`, `POLL_MILLIS = 250`;
    - `TestRequestFile(Path file, Consumer<String> warn)`;
    - `Optional<TestResults.Trigger> take()`;
    - `static TestRequestFile poll(Path file, Consumer<TestResults.Trigger> onRequest, Consumer<String> warn)
      throws IOException`;
    - `close()`.

- [ ] **Step 1: Write the failing tests**

`PLUGIN_TEST/ContinuousTestingTest.java`:

```java
package io.vidocq.runtime.maven.dev;

import io.vidocq.runtime.maven.dev.TestResults.Counts;
import io.vidocq.runtime.maven.dev.TestResults.Failure;
import io.vidocq.runtime.maven.dev.TestResults.State;
import io.vidocq.runtime.maven.dev.TestResults.Trigger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class ContinuousTestingTest {

    /** A run the test ends by hand. */
    static final class FakeRun implements ContinuousTesting.Launched {
        final List<String> tests;
        final CompletableFuture<Integer> exit = new CompletableFuture<>();
        volatile boolean cancelled;

        FakeRun(List<String> tests) {
            this.tests = tests;
        }

        @Override
        public int waitFor() throws InterruptedException {
            try {
                return exit.get();
            } catch (ExecutionException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            exit.complete(143);
        }

        void finish(int code) {
            exit.complete(code);
        }
    }

    private final BlockingQueue<FakeRun> launches = new LinkedBlockingQueue<>();
    private final BlockingQueue<TestResults> results = new LinkedBlockingQueue<>();
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final AtomicReference<SurefireReports.Reports> reports = new AtomicReference<>(passed(2));
    private Path resultsFile;
    private ContinuousTesting testing;

    @BeforeEach
    void start(@TempDir Path dir) {
        resultsFile = dir.resolve(TestResultsFile.FILE_NAME);
        testing = new ContinuousTesting(tests -> {
            FakeRun run = new FakeRun(tests);
            launches.add(run);
            return run;
        }, since -> reports.get(), resultsFile, "target/vidocq-dev-tests.log", results::add, warnings::add,
                Clock.systemUTC());
        testing.start();
    }

    @AfterEach
    void close() {
        testing.close();
    }

    private static SurefireReports.Reports passed(int run) {
        return new SurefireReports.Reports(new Counts(run, 0, 0, 0), List.of(), 1, 0);
    }

    private static SurefireReports.Reports failed(String... tests) {
        List<Failure> failures = new ArrayList<>();
        for (String test : tests) {
            failures.add(new Failure(test, "org.opentest4j.AssertionFailedError", "no"));
        }
        return new SurefireReports.Reports(new Counts(tests.length + 1, tests.length, 0, 0), failures, 1, 0);
    }

    private FakeRun nextLaunch() throws InterruptedException {
        FakeRun run = launches.poll(5, TimeUnit.SECONDS);
        assertNotNull(run, "a run should have started");
        return run;
    }

    private TestResults nextResult() throws InterruptedException {
        TestResults result = results.poll(5, TimeUnit.SECONDS);
        assertNotNull(result, "a run should have ended");
        return result;
    }

    /** Ends a first run with {@code outcome}, so that the next ones have a previous result. */
    private void firstRun(SurefireReports.Reports outcome) throws InterruptedException {
        reports.set(outcome);
        testing.changed(Trigger.RUN_ALL, TestControl.ReadyGate.NOW);
        nextLaunch().finish(outcome.counts().failures() > 0 ? 1 : 0);
        nextResult();
    }

    @Test
    void aChangeRunsEveryTestOnceItsGateOpens() throws Exception {
        CountDownLatch reloaded = new CountDownLatch(1);
        testing.changed(Trigger.CHANGE, abandoned -> {
            reloaded.await();
            return true;
        });

        assertNull(launches.poll(300, TimeUnit.MILLISECONDS), "no run before the reload completed");
        reloaded.countDown();
        FakeRun run = nextLaunch();
        assertEquals(List.of(), run.tests);
        run.finish(0);

        TestResults result = nextResult();
        assertEquals(State.PASSED, result.state());
        assertEquals(Trigger.CHANGE, result.trigger());
        assertTrue(Files.readString(resultsFile).startsWith("{\"state\":\"passed\",\"trigger\":\"change\""));
    }

    @Test
    void aChangeCancelsTheRunInFlightAndRunsAgain() throws Exception {
        testing.changed(Trigger.CHANGE, TestControl.ReadyGate.NOW);
        FakeRun first = nextLaunch();

        testing.changed(Trigger.TEST_CHANGE, TestControl.ReadyGate.NOW);

        assertTrue(first.cancelled);
        TestResults cancelled = nextResult();
        assertEquals(State.CANCELLED, cancelled.state());
        assertEquals(Trigger.CHANGE, cancelled.trigger());
        nextLaunch().finish(0);
        assertEquals(Trigger.TEST_CHANGE, nextResult().trigger());
    }

    @Test
    void aRequestDuringARunIsQueuedLastWinsAndNeverCancels() throws Exception {
        firstRun(failed("com.acme.ATest#x"));
        testing.changed(Trigger.TEST_CHANGE, TestControl.ReadyGate.NOW);
        FakeRun inFlight = nextLaunch();

        assertEquals(ContinuousTesting.QUEUED, testing.request(Trigger.RERUN_FAILED));
        assertEquals(ContinuousTesting.QUEUED, testing.request(Trigger.RUN_ALL));

        assertFalse(inFlight.cancelled, "a request never cancels");
        assertNull(launches.poll(200, TimeUnit.MILLISECONDS), "one run at a time");
        inFlight.finish(1);
        nextResult();
        FakeRun queued = nextLaunch();
        assertEquals(List.of(), queued.tests, "run-all, the last request, won");
        queued.finish(0);
        assertEquals(Trigger.RUN_ALL, nextResult().trigger());
    }

    @Test
    void rerunFailedRunsOnlyTheLastFailures() throws Exception {
        firstRun(failed("com.acme.ATest#x", "com.acme.BTest#y(int)[1]", "com.acme.BTest#y(int)[2]"));

        assertEquals(ContinuousTesting.QUEUED, testing.request(Trigger.RERUN_FAILED));

        FakeRun rerun = nextLaunch();
        assertEquals(List.of("com.acme.ATest#x", "com.acme.BTest#y"), rerun.tests);
        rerun.finish(0);
        assertEquals(Trigger.RERUN_FAILED, nextResult().trigger());
    }

    @Test
    void rerunFailedWithNothingFailedStartsNothing() throws Exception {
        firstRun(passed(3));

        assertEquals(ContinuousTesting.NOTHING_TO_RERUN, testing.request(Trigger.RERUN_FAILED));

        assertNull(launches.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void interruptDropsTheChangeWaitingForItsGate() throws Exception {
        testing.changed(Trigger.CHANGE, abandoned -> {
            while (!abandoned.getAsBoolean()) {
                Thread.sleep(10);
            }
            return false;
        });
        Thread.sleep(100);

        testing.interrupt();

        assertNull(launches.poll(300, TimeUnit.MILLISECONDS), "a failed recompile runs no test");
    }

    @Test
    void theRunningStateKeepsThePreviousResult() throws Exception {
        firstRun(passed(2));
        testing.changed(Trigger.TEST_CHANGE, TestControl.ReadyGate.NOW);
        FakeRun run = nextLaunch();

        String json = Files.readString(resultsFile);

        assertTrue(json.startsWith("{\"state\":\"running\",\"trigger\":\"test-change\""), json);
        assertTrue(json.contains("\"previous\":{\"state\":\"passed\""), json);
        run.finish(0);
    }

    @Test
    void closingCancelsTheRunInFlight() throws Exception {
        testing.changed(Trigger.RUN_ALL, TestControl.ReadyGate.NOW);
        FakeRun run = nextLaunch();

        testing.close();

        assertTrue(run.cancelled);
        assertEquals(State.CANCELLED, nextResult().state());
        testing.close();
    }

    @Test
    void aLauncherThatCannotStartIsACompileErrorNotACrash(@TempDir Path dir) throws Exception {
        testing.close();
        testing = new ContinuousTesting(tests -> {
            throw new java.io.IOException("mvn: not found");
        }, since -> SurefireReports.Reports.NONE, dir.resolve("r.json"), "log", results::add, warnings::add,
                Clock.systemUTC());
        testing.start();

        testing.changed(Trigger.RUN_ALL, TestControl.ReadyGate.NOW);

        assertEquals(State.COMPILE_ERROR, nextResult().state());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("mvn: not found")), warnings.toString());
    }
}
```

`PLUGIN_TEST/TestRequestFileTest.java`:

```java
package io.vidocq.runtime.maven.dev;

import io.vidocq.runtime.maven.dev.TestResults.Trigger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class TestRequestFileTest {

    private final List<String> warnings = new ArrayList<>();

    @Test
    void aRequestIsReadOnceAndDeleted(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(TestRequestFile.FILE_NAME);
        TestRequestFile requests = new TestRequestFile(file, warnings::add);
        Files.writeString(file, "rerun-failed\n");

        assertEquals(Optional.of(Trigger.RERUN_FAILED), requests.take());
        assertFalse(Files.exists(file));
        assertEquals(Optional.empty(), requests.take());
    }

    @Test
    void anUnknownRequestIsIgnoredAndLoggedOnce(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(TestRequestFile.FILE_NAME);
        TestRequestFile requests = new TestRequestFile(file, warnings::add);

        Files.writeString(file, "com.acme.Evil#test");
        assertEquals(Optional.empty(), requests.take());
        Files.writeString(file, "change");
        assertEquals(Optional.empty(), requests.take());

        assertEquals(1, warnings.size(), warnings.toString());
        assertFalse(warnings.getFirst().contains("Evil"), "the content is never quoted: " + warnings);
    }

    @Test
    @Timeout(10)
    void pollingDeletesAStaleRequestThenDeliversNewOnes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(TestRequestFile.FILE_NAME);
        Files.writeString(file, "run-all");
        BlockingQueue<Trigger> seen = new LinkedBlockingQueue<>();

        try (TestRequestFile ignored = TestRequestFile.poll(file, seen::add, warnings::add)) {
            assertNull(seen.poll(600, TimeUnit.MILLISECONDS), "a request left by a killed session never runs");
            Files.writeString(file, "run-all");
            assertEquals(Trigger.RUN_ALL, seen.poll(5, TimeUnit.SECONDS));
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -q -pl vidocq-runtime-maven-plugin test -Dtest='ContinuousTestingTest,TestRequestFileTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAILURE (`ContinuousTesting` has no such constructor; `TestRequestFile` not found).

- [ ] **Step 3: Write the implementation**

`PLUGIN/ContinuousTesting.java` (whole file):

```java
package io.vidocq.runtime.maven.dev;

import io.vidocq.runtime.maven.dev.TestResults.Trigger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Continuous testing (spec §2), shared by {@code vidocq:dev} and {@code vidocq:test}: one worker thread,
 * {@code vidocq-tests}, runs the tests one run at a time.
 *
 * <ul>
 *   <li>A {@linkplain #changed change} cancels the run in flight and replaces the run waiting to start; that run
 *       starts once its {@link ReadyGate} opens, after the application reloaded.</li>
 *   <li>A {@linkplain #request request} ({@code run-all}, {@code rerun-failed}) never cancels: it waits for the run
 *       in flight, the last one wins, and it never replaces a change waiting to start, whose run covers it.</li>
 *   <li>{@code rerun-failed} runs the failures of the last complete result, which this class computes itself.</li>
 * </ul>
 * Each run writes {@code running}, then its outcome, to the results file, and hands the outcome to
 * {@code onResult}. Nothing a run does escapes the worker as an exception: it becomes a warning.
 */
final class ContinuousTesting implements TestControl, AutoCloseable {

    /** Starts one run of the tests: all of them, or the listed {@code Class#method}s. */
    interface Launcher {
        Launched launch(List<String> tests) throws IOException;
    }

    /** A run in flight. */
    interface Launched {

        /** Waits for the run to end and returns its exit code. */
        int waitFor() throws InterruptedException;

        /** Stops the run and every process it started; returns once they are gone. */
        void cancel();
    }

    /** What a request answers when it will run. */
    static final String QUEUED = "queued";
    /** What {@code rerun-failed} answers when the last result has no failure. */
    static final String NOTHING_TO_RERUN = "no failed test to rerun";
    /** How long {@link #close} waits for the worker. */
    static final Duration CLOSE_WAIT = Duration.ofSeconds(15);

    /** The run waiting to start. {@code change} is {@code false} for a request. */
    private record Pending(Trigger trigger, ReadyGate gate, boolean change) {}

    private final Launcher launcher;
    private final Function<Instant, SurefireReports.Reports> reports;
    private final Path resultsFile;
    private final String log;
    private final Consumer<TestResults> onResult;
    private final Consumer<String> warn;
    private final Clock clock;

    private final Object lock = new Object();
    // Guarded by lock.
    private Pending pending;
    private Launched current;
    private boolean cancelled;
    private boolean closed;
    /** Bumped by every change, interrupt and close: a run taken under an older value is abandoned. */
    private long generation;
    private TestResults lastComplete;
    private Thread worker;

    /**
     * @param reports    the reports of the run that started at the given instant
     * @param log        the log file as the results show it, such as {@code target/vidocq-dev-tests.log}
     * @param onResult   called on the worker with each run's outcome, never with {@code running}
     */
    ContinuousTesting(Launcher launcher, Function<Instant, SurefireReports.Reports> reports, Path resultsFile,
            String log, Consumer<TestResults> onResult, Consumer<String> warn, Clock clock) {
        this.launcher = launcher;
        this.reports = reports;
        this.resultsFile = resultsFile;
        this.log = log;
        this.onResult = onResult;
        this.warn = warn;
        this.clock = clock;
    }

    /**
     * The continuous testing of a project: Surefire run by {@link TestRunner} with the Maven the recompile uses,
     * its reports read from {@code <buildDir>/surefire-reports}, the results in {@code <buildDir>/}{@value
     * TestResultsFile#FILE_NAME} and the log in {@code <buildDir>/}{@value TestResultsFile#LOG_NAME}.
     *
     * @param testProperties the dev session's keys, handed to every run as {@code -D}
     */
    static ContinuousTesting forProject(Path projectDir, Path buildDir, Map<String, String> testProperties,
            Consumer<TestResults> onResult, Consumer<String> warn) {
        Path logFile = buildDir.resolve(TestResultsFile.LOG_NAME);
        Path reportsDir = buildDir.resolve("surefire-reports");
        TestRunner runner = new TestRunner(projectDir, RecompileRunner.detectMavenExecutable(projectDir),
                testProperties, logFile, reportsDir);
        return new ContinuousTesting(runner, since -> SurefireReports.read(reportsDir, since),
                buildDir.resolve(TestResultsFile.FILE_NAME), shown(projectDir, logFile), onResult, warn,
                Clock.systemUTC());
    }

    /** {@code file} relative to the project, with forward slashes, when it is inside it. */
    static String shown(Path projectDir, Path file) {
        Path base = projectDir.toAbsolutePath().normalize();
        Path absolute = file.toAbsolutePath().normalize();
        return absolute.startsWith(base) ? base.relativize(absolute).toString().replace('\\', '/')
                : absolute.toString();
    }

    /** Starts the worker thread; idempotent. */
    void start() {
        synchronized (lock) {
            if (worker == null && !closed) {
                worker = Thread.ofPlatform().name("vidocq-tests").daemon(true).start(this::work);
            }
        }
    }

    @Override
    public void changed(Trigger trigger, ReadyGate gate) {
        Launched running;
        synchronized (lock) {
            if (closed) {
                return;
            }
            generation++;
            pending = new Pending(trigger, gate, true);
            running = markCancelled();
            lock.notifyAll();
        }
        if (running != null) {
            running.cancel();
        }
    }

    @Override
    public void interrupt() {
        Launched running;
        synchronized (lock) {
            generation++;
            pending = null;
            running = markCancelled();
            lock.notifyAll();
        }
        if (running != null) {
            running.cancel();
        }
    }

    /**
     * A request from the dev console or the terminal (spec §3.2, §4.2).
     *
     * @return {@value #QUEUED}, or {@value #NOTHING_TO_RERUN} for {@code rerun-failed} after a result without
     *         failure, in which case nothing runs
     */
    String request(Trigger trigger) {
        synchronized (lock) {
            if (closed) {
                return "continuous testing is stopped";
            }
            if (trigger == Trigger.RERUN_FAILED && rerunTests().isEmpty()) {
                return NOTHING_TO_RERUN;
            }
            if (pending == null || !pending.change()) {
                pending = new Pending(trigger, ReadyGate.NOW, false);
            }
            lock.notifyAll();
            return QUEUED;
        }
    }

    /** The last result that is neither {@code running} nor {@code cancelled}, or {@code null}. */
    TestResults lastComplete() {
        synchronized (lock) {
            return lastComplete;
        }
    }

    /** Cancels the run in flight, which is written {@code cancelled}, and stops the worker; idempotent. */
    @Override
    public void close() {
        Launched running;
        Thread thread;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            generation++;
            pending = null;
            running = markCancelled();
            thread = worker;
            lock.notifyAll();
        }
        if (running != null) {
            running.cancel();
        }
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(CLOSE_WAIT.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Guarded by lock: marks the run in flight cancelled and returns it, for the caller to cancel unlocked. */
    private Launched markCancelled() {
        if (current != null) {
            cancelled = true;
        }
        return current;
    }

    /** Guarded by lock. */
    private List<String> rerunTests() {
        return lastComplete == null ? List.of() : lastComplete.rerunList();
    }

    private boolean abandoned(long taken) {
        synchronized (lock) {
            return closed || generation != taken;
        }
    }

    private void work() {
        while (true) {
            Pending next;
            long taken;
            synchronized (lock) {
                while (pending == null && !closed) {
                    try {
                        lock.wait();
                    } catch (InterruptedException stopping) {
                        return;
                    }
                }
                if (closed) {
                    return;
                }
                next = pending;
                pending = null;
                taken = generation;
            }
            try {
                if (next.gate().await(() -> abandoned(taken))) {
                    runOnce(next.trigger(), taken);
                }
            } catch (InterruptedException stopping) {
                return;
            } catch (RuntimeException failed) {
                warn.accept("Tests: the run failed: " + failed.getClass().getName());
            }
        }
    }

    private void runOnce(Trigger trigger, long taken) throws InterruptedException {
        List<String> tests;
        TestResults previous;
        synchronized (lock) {
            if (closed || generation != taken) {
                return;
            }
            tests = trigger == Trigger.RERUN_FAILED ? rerunTests() : List.of();
            previous = lastComplete;
        }
        if (trigger == Trigger.RERUN_FAILED && tests.isEmpty()) {
            return;
        }
        Instant startedAt = clock.instant();
        long t0 = System.nanoTime();
        write(TestResults.running(trigger, startedAt, log, previous));
        Launched launched = null;
        boolean abandonedBeforeLaunch;
        synchronized (lock) {
            abandonedBeforeLaunch = closed || generation != taken;
            if (!abandonedBeforeLaunch) {
                try {
                    launched = launcher.launch(tests);
                } catch (IOException cannotStart) {
                    warn.accept("Tests: cannot start Maven: " + cannotStart.getMessage());
                }
                current = launched;
                cancelled = false;
            }
        }
        if (abandonedBeforeLaunch) {
            write(TestResults.cancelled(trigger, startedAt, 0, log, previous));
            return;
        }
        int exit = launched == null ? -1 : launched.waitFor();
        boolean wasCancelled;
        synchronized (lock) {
            wasCancelled = cancelled;
            current = null;
            cancelled = false;
        }
        long duration = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        TestResults result = wasCancelled
                ? TestResults.cancelled(trigger, startedAt, duration, log, previous)
                : TestResults.completed(trigger, startedAt, duration, exit, reports.apply(startedAt), log, warn);
        if (result.state().complete()) {
            synchronized (lock) {
                lastComplete = result;
            }
        }
        write(result);
        onResult.accept(result);
    }

    private void write(TestResults results) {
        try {
            TestResultsFile.write(resultsFile, results);
        } catch (IOException | RuntimeException failed) {
            warn.accept("Tests: cannot write " + resultsFile + ": " + failed.getMessage());
        }
    }
}
```

`PLUGIN/TestRequestFile.java`:

```java
package io.vidocq.runtime.maven.dev;

import io.vidocq.runtime.maven.dev.TestResults.Trigger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The plugin's side of {@code target/vidocq-dev-tests.request} (spec §3.3): the dev console writes one word there,
 * {@code run-all} or {@code rerun-failed}; the plugin reads it, deletes it, and queues the run. The console never
 * sends a test name: the plugin computes a rerun itself. Anything else is ignored, logged once, and never quoted.
 */
final class TestRequestFile implements AutoCloseable {

    /** The request file's name, next to the results file. */
    static final String FILE_NAME = "vidocq-dev-tests.request";
    /** How often the file is looked for. */
    static final long POLL_MILLIS = 250;

    private final Path file;
    private final Consumer<String> warn;
    private boolean warnedUnknown;
    private volatile Thread poller;

    TestRequestFile(Path file, Consumer<String> warn) {
        this.file = file;
        this.warn = warn;
    }

    /** Reads and deletes a pending request; empty when there is none, or when it is unknown. */
    synchronized Optional<Trigger> take() {
        String word;
        try {
            word = Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (NoSuchFileException none) {
            return Optional.empty();
        } catch (IOException unreadable) {
            return Optional.empty();
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // read again on the next poll, and ignored again if unknown
        }
        Optional<Trigger> trigger = Trigger.ofRequest(word);
        if (trigger.isEmpty() && !warnedUnknown) {
            warnedUnknown = true;
            warn.accept("Tests: ignoring an unknown request in " + file.getFileName()
                    + "; only run-all and rerun-failed are known");
        }
        return trigger;
    }

    /**
     * Deletes a request an earlier session left, then looks for one every {@value #POLL_MILLIS} ms on the daemon
     * thread {@code vidocq-tests-requests}, and hands each to {@code onRequest}.
     */
    static TestRequestFile poll(Path file, Consumer<Trigger> onRequest, Consumer<String> warn) throws IOException {
        Files.deleteIfExists(file);
        TestRequestFile requests = new TestRequestFile(file, warn);
        requests.poller = Thread.ofPlatform().name("vidocq-tests-requests").daemon(true).start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    requests.take().ifPresent(onRequest);
                } catch (RuntimeException failed) {
                    warn.accept("Tests: a request failed: " + failed.getClass().getName());
                }
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (InterruptedException stopping) {
                    return;
                }
            }
        });
        return requests;
    }

    /** Stops polling; idempotent. */
    @Override
    public void close() {
        Thread running = poller;
        poller = null;
        if (running != null) {
            running.interrupt();
            try {
                running.join(1_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: the command of Step 2, then the whole module once: `mvn -o -q -pl vidocq-runtime-maven-plugin test`.
Expected: all green; `ContinuousTestingTest` 9 tests, `TestRequestFileTest` 3 tests.

- [ ] **Step 5: Commit**

```bash
git add vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/{ContinuousTesting,TestRequestFile}.java \
        vidocq-runtime-maven-plugin/src/test/java/io/vidocq/runtime/maven/dev/{ContinuousTestingTest,TestRequestFileTest}.java
git commit -S -F <msgfile>   # "feat(plugin): serialise, cancel and queue continuous test runs (#122)"
```

---

### Task 4: Test directories in the watcher, and the reload acknowledgement

**Files:**
- Modify: `PLUGIN/SourceWatcher.java`
- Create: `PLUGIN/ReloadAck.java`
- Modify: `vidocq-runtime-core/src/main/java/io/vidocq/runtime/core/VidocqDevReloadLoop.java`
- Test: `PLUGIN_TEST/SourceWatcherTest.java` (add), `PLUGIN_TEST/ReloadAckTest.java`,
  `vidocq-runtime-core/src/test/java/io/vidocq/runtime/core/VidocqDevReloadLoopTest.java`

**Interfaces:**
- Consumes: `TestControl.ReadyGate` (Task 2).
- Produces:
  - `SourceWatcher`:
    - `record Change(boolean main, boolean test)`;
    - `static SourceWatcher on(List<Path> mainDirs, List<Path> testDirs, Duration debounce)`;
    - `Change awaitChanges()`, which returns `null` once the watcher is closed;
    - `static boolean under(Path file, List<Path> roots)`;
    - `on(List, Duration)` and `awaitChange()` are kept.
  - `ReloadAck`:
    - `SUFFIX = ".ready"`;
    - `static Path readyFile(Path reloadFile)`;
    - `static long stamp(Path reloadFile) throws IOException`;
    - `static boolean acknowledged(Path reloadFile, long stamp)`;
    - `static TestControl.ReadyGate gate(Path reloadFile, long stamp, Duration timeout,
      BooleanSupplier childAlive, Consumer<String> warn)`.
  - Core, package-private in `io.vidocq.runtime.core`: `VidocqDevReloadLoop.READY_SUFFIX = ".ready"`,
    `static Path readyFile(Path)`, `static void acknowledge(Path reloadFile, long stamp)`.
  - Contract between the two sides: the file `<reload file name>.ready` holds the decimal stamp (the reload file's
    mtime in milliseconds that the boot answered), then a newline.

- [ ] **Step 1: Write the failing tests**

Add to `PLUGIN_TEST/SourceWatcherTest.java` (new imports: `org.junit.jupiter.api.io.TempDir` is already there;
add `java.util.concurrent.atomic.AtomicReference`):

```java
    @Test
    void aFileUnderATestRootIsATestChange(@TempDir Path tmp) {
        Path testRoot = tmp.resolve("src/test/java").toAbsolutePath().normalize();

        assertTrue(SourceWatcher.under(tmp.resolve("src/test/java/a/BTest.java"), List.of(testRoot)));
        assertFalse(SourceWatcher.under(tmp.resolve("src/main/java/a/B.java"), List.of(testRoot)));
        assertFalse(SourceWatcher.under(tmp.resolve("src/test/javax/C.java"), List.of(testRoot)));
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void awaitChanges_tells_a_test_change_from_a_main_one(@TempDir Path tmp) throws Exception {
        Path main = Files.createDirectories(tmp.resolve("src/main/java"));
        Path test = Files.createDirectories(tmp.resolve("src/test/java"));
        Path testFile = test.resolve("HelloTest.java");
        Files.writeString(main.resolve("Hello.java"), "class Hello {}");
        Files.writeString(testFile, "class HelloTest {}");

        try (SourceWatcher watcher = SourceWatcher.on(List.of(main), List.of(test), Duration.ofMillis(50))) {
            AtomicReference<SourceWatcher.Change> change = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try {
                    change.set(watcher.awaitChanges());
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            t.start();
            Thread.sleep(500);
            Files.writeString(testFile, "class HelloTest { /* edit */ }");

            t.join(TimeUnit.SECONDS.toMillis(15));
            assertFalse(t.isAlive(), "watcher should have returned by now");
            assertEquals(new SourceWatcher.Change(false, true), change.get());
        }
    }
```

`PLUGIN_TEST/ReloadAckTest.java`:

```java
package io.vidocq.runtime.maven.dev;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class ReloadAckTest {

    private final List<String> warnings = new CopyOnWriteArrayList<>();

    @Test
    void theReadyFileIsTheReloadFilesSibling() {
        assertEquals(Path.of("target/.vidocq-dev-reload.ready"),
                ReloadAck.readyFile(Path.of("target/.vidocq-dev-reload")));
    }

    @Test
    void theGateOpensWhenTheBootAnswersThisStamp(@TempDir Path dir) throws Exception {
        Path reload = dir.resolve(".vidocq-dev-reload");
        Files.writeString(ReloadAck.readyFile(reload), "41\n");
        Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(300);
                Files.writeString(ReloadAck.readyFile(reload), "42\n");
            } catch (Exception ignored) {
                // the test fails on its timeout
            }
        });

        assertTrue(ReloadAck.gate(reload, 42, Duration.ofSeconds(5), () -> true, warnings::add)
                .await(() -> false));
        assertEquals(List.of(), warnings);
    }

    @Test
    void aNewerChangeAbandonsTheWait(@TempDir Path dir) throws Exception {
        assertFalse(ReloadAck.gate(dir.resolve(".vidocq-dev-reload"), 42, Duration.ofSeconds(5), () -> true,
                warnings::add).await(() -> true));
    }

    @Test
    void noAnswerInTimeRunsTheTestsAnywayWithAWarning(@TempDir Path dir) throws Exception {
        assertTrue(ReloadAck.gate(dir.resolve(".vidocq-dev-reload"), 42, Duration.ofMillis(300), () -> true,
                warnings::add).await(() -> false));
        assertEquals(1, warnings.size());
    }

    @Test
    void aChildThatDiedRunsTheTestsAnywayWithAWarning(@TempDir Path dir) throws Exception {
        assertTrue(ReloadAck.gate(dir.resolve(".vidocq-dev-reload"), 42, Duration.ofSeconds(5), () -> false,
                warnings::add).await(() -> false));
        assertTrue(warnings.getFirst().contains("stopped"), warnings.toString());
    }
}
```

`vidocq-runtime-core/src/test/java/io/vidocq/runtime/core/VidocqDevReloadLoopTest.java` (license header first):

```java
package io.vidocq.runtime.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VidocqDevReloadLoopTest {

    @Test
    void theReadyFileIsTheReloadFileWithReady(@TempDir Path dir) {
        assertEquals(dir.resolve(".vidocq-dev-reload.ready"),
                VidocqDevReloadLoop.readyFile(dir.resolve(".vidocq-dev-reload")));
    }

    @Test
    void acknowledgingWritesTheStampAndLeavesNoTemporaryFile(@TempDir Path dir) throws Exception {
        Path reload = dir.resolve(".vidocq-dev-reload");

        VidocqDevReloadLoop.acknowledge(reload, 1234L);
        VidocqDevReloadLoop.acknowledge(reload, 5678L);

        assertEquals("5678\n", Files.readString(dir.resolve(".vidocq-dev-reload.ready")));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(".vidocq-dev-reload.ready"),
                    files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void aFailureNeverStopsTheApplication(@TempDir Path dir) {
        assertDoesNotThrow(() -> VidocqDevReloadLoop.acknowledge(dir.resolve("absent/.vidocq-dev-reload"), 1L));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -q -pl vidocq-runtime-core test -Dtest=VidocqDevReloadLoopTest -Dsurefire.failIfNoSpecifiedTests=false`
and `mvn -o -q -pl vidocq-runtime-maven-plugin test -Dtest='SourceWatcherTest,ReloadAckTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAILURE in both (`readyFile`, `under`, `ReloadAck` not found).

- [ ] **Step 3: Write the implementation**

In `VidocqDevReloadLoop.java`:

- Add the imports `java.nio.file.StandardCopyOption`.
- Add after `POLL_MILLIS`:

```java
    /**
     * Suffix of the file the loop writes once a boot completed, next to the reload file: it holds the stamp of the
     * signal that boot answered, so that the host starts the tests of that change after it (#122).
     */
    static final String READY_SUFFIX = ".ready";
```

- In `run`, right after `.start();` (the statement that ends `VidocqBootstrap bootstrap = …`), add:

```java
            acknowledge(reloadFile, lastSignal);
```

- Add before `signalStamp`:

```java
    /** {@code <reload file>.ready}, next to it. */
    static Path readyFile(Path reloadFile) {
        return reloadFile.resolveSibling(reloadFile.getFileName() + READY_SUFFIX);
    }

    /**
     * Tells the host that the boot answering the signal {@code stamp} completed: the stamp is written to
     * {@link #readyFile}, atomically. Never fails the application: a failure is logged at DEBUG, and the host then
     * runs the tests after its own timeout.
     */
    static void acknowledge(Path reloadFile, long stamp) {
        Path ready = readyFile(reloadFile);
        try {
            Path tmp = Files.createTempFile(ready.toAbsolutePath().getParent(), ".vidocq-dev-reload", ".tmp");
            try {
                Files.writeString(tmp, stamp + "\n");
                Files.move(tmp, ready, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | RuntimeException failed) {
            LOG.log(System.Logger.Level.DEBUG, "Cannot write " + ready + ": " + failed.getMessage());
        }
    }
```

`PLUGIN/ReloadAck.java`:

```java
package io.vidocq.runtime.maven.dev;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * When a hot reload completed (spec §2.2): after each boot the child writes the stamp of the signal it answered to
 * {@code <reload file>.ready} ({@code VidocqDevReloadLoop.acknowledge} in the core). The tests of a main change wait
 * for that stamp, so they never overlap the reload. A reload that never answers — a boot that failed, a child that
 * died — never blocks the tests: they run after the timeout, or at once when the child is gone, with a warning.
 */
final class ReloadAck {

    /** The acknowledgement file's suffix; the core writes the same name. */
    static final String SUFFIX = ".ready";
    /** How often the acknowledgement is looked for. */
    static final long POLL_MILLIS = 100;

    private ReloadAck() {}

    static Path readyFile(Path reloadFile) {
        return reloadFile.resolveSibling(reloadFile.getFileName() + SUFFIX);
    }

    /** The stamp the child sees for the signal just written: the reload file's modification time. */
    static long stamp(Path reloadFile) throws IOException {
        return Files.getLastModifiedTime(reloadFile).toMillis();
    }

    static boolean acknowledged(Path reloadFile, long stamp) {
        try {
            return Files.readString(readyFile(reloadFile), StandardCharsets.UTF_8).strip()
                    .equals(Long.toString(stamp));
        } catch (IOException notYet) {
            return false;
        }
    }

    /** The gate of the tests that follow the signal {@code stamp}. */
    static TestControl.ReadyGate gate(Path reloadFile, long stamp, Duration timeout, BooleanSupplier childAlive,
            Consumer<String> warn) {
        return abandoned -> {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (abandoned.getAsBoolean()) {
                    return false;
                }
                if (acknowledged(reloadFile, stamp)) {
                    return true;
                }
                if (!childAlive.getAsBoolean()) {
                    warn.accept("Tests: the application stopped before its reload completed; running the tests"
                            + " anyway");
                    return true;
                }
                Thread.sleep(POLL_MILLIS);
            }
            warn.accept("Tests: the reload was not confirmed within " + timeout.toSeconds()
                    + " s; running the tests anyway");
            return true;
        };
    }
}
```

In `PLUGIN/SourceWatcher.java`:

- Class Javadoc, first sentence: replace "to detect source modifications under {@code src/main/{java,resources}}"
  with "and {@code vidocq:test} to detect source modifications under the main directories and, for continuous
  testing, the test directories; {@link #awaitChanges} says which of the two a burst touched".
- New imports: `java.util.ArrayList`.
- Add the field and change the constructor:

```java
    /** A burst of events: whether it touched the main directories, the test directories, or both. */
    record Change(boolean main, boolean test) {}

    private final List<Path> testRoots;

    private SourceWatcher(WatchService service, long debounceMillis, List<Path> testRoots) {
        this.service = service;
        this.debounceMillis = debounceMillis;
        this.testRoots = testRoots;
    }
```

- Replace `on(...)` with:

```java
    /** A watcher of main directories only. */
    static SourceWatcher on(List<Path> dirs, Duration debounce) throws IOException {
        return on(dirs, List.of(), debounce);
    }

    /**
     * Registers the main and test directories (recursively). A directory that does not exist is skipped.
     */
    static SourceWatcher on(List<Path> mainDirs, List<Path> testDirs, Duration debounce) throws IOException {
        List<Path> all = new ArrayList<>(mainDirs);
        all.addAll(testDirs);
        WatchService ws = all.get(0).getFileSystem().newWatchService();
        SourceWatcher watcher = new SourceWatcher(ws, debounce.toMillis(),
                testDirs.stream().map(dir -> dir.toAbsolutePath().normalize()).toList());
        for (Path dir : all) {
            if (Files.isDirectory(dir)) {
                watcher.registerRecursive(dir);
            }
        }
        return watcher;
    }
```

- Replace `awaitChange()` and `drainKey(...)` with:

```java
    /** {@link #awaitChanges}, as a yes/no: {@code false} once the watcher is closed. */
    boolean awaitChange() throws InterruptedException {
        return awaitChanges() != null;
    }

    /**
     * Blocks until at least one event on a watched file extension arrives, then drains any further event within
     * the debounce window. Returns what the burst touched, or {@code null} once the watcher is closed.
     */
    Change awaitChanges() throws InterruptedException {
        while (true) {
            WatchKey key;
            try {
                key = service.take();
            } catch (ClosedWatchServiceException e) {
                return null;
            }
            Touched touched = new Touched();
            drainKey(key, touched);
            if (!key.reset()) {
                registered.remove((Path) key.watchable());
            }
            if (!touched.any()) {
                continue;
            }
            // Debounce: keep draining events for `debounceMillis` so a burst
            // of editor saves collapses into one reload cycle.
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(debounceMillis);
            long remaining;
            while ((remaining = deadline - System.nanoTime()) > 0) {
                WatchKey more;
                try {
                    more = service.poll(remaining, TimeUnit.NANOSECONDS);
                } catch (ClosedWatchServiceException e) {
                    return touched.change();
                }
                if (more == null) {
                    break;
                }
                drainKey(more, touched);
                if (!more.reset()) {
                    registered.remove((Path) more.watchable());
                }
            }
            return touched.change();
        }
    }

    /** What the events drained so far touched. */
    private static final class Touched {
        boolean main;
        boolean test;

        boolean any() {
            return main || test;
        }

        Change change() {
            return new Change(main, test);
        }
    }

    /**
     * Pull all events for {@code key} and note whether they concern a watched file of a main or a test directory;
     * an overflow counts as both. Also auto-registers any newly created sub-directory so the recursive watch stays
     * in sync.
     */
    private void drainKey(WatchKey key, Touched touched) {
        Path parent = (Path) key.watchable();
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                touched.main = true;
                touched.test = !testRoots.isEmpty();
                continue;
            }
            Object ctx = event.context();
            if (!(ctx instanceof Path child)) {
                continue;
            }
            Path resolved = parent.resolve(child);
            if (Files.isDirectory(resolved)
                    && event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                    && !registered.contains(resolved)) {
                try {
                    registerRecursive(resolved);
                } catch (IOException ignored) {
                    // a new subdir we cannot register is not fatal
                }
            }
            if (isWatchedExtension(child.toString())) {
                if (under(resolved, testRoots)) {
                    touched.test = true;
                } else {
                    touched.main = true;
                }
            }
        }
    }

    /** Whether {@code file} lies under one of {@code roots} (absolute, normalized). */
    static boolean under(Path file, List<Path> roots) {
        Path normalized = file.toAbsolutePath().normalize();
        for (Path root : roots) {
            if (normalized.startsWith(root)) {
                return true;
            }
        }
        return false;
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: the two commands of Step 2, then `mvn -o -q -pl vidocq-runtime-core install -DskipTests` (the plugin's IT
and later tasks use the new core).
Expected: all green, including the two older `SourceWatcherTest` tests.

- [ ] **Step 5: Commit**

```bash
git add vidocq-runtime-core/src/main/java/io/vidocq/runtime/core/VidocqDevReloadLoop.java \
        vidocq-runtime-core/src/test/java/io/vidocq/runtime/core/VidocqDevReloadLoopTest.java \
        vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/{SourceWatcher,ReloadAck}.java \
        vidocq-runtime-maven-plugin/src/test/java/io/vidocq/runtime/maven/dev/{SourceWatcherTest,ReloadAckTest}.java
git commit -S -F <msgfile>   # "feat(dev): watch test sources and acknowledge each hot reload (#122)"
```

---

### Task 5: Terminal output and keys

**Files:**
- Create: `PLUGIN/TestSummaryPrinter.java`, `PLUGIN/TerminalControls.java`
- Test: `PLUGIN_TEST/TestSummaryPrinterTest.java`, `PLUGIN_TEST/TerminalControlsTest.java`

**Interfaces:**
- Consumes: `TestResults` (Task 1).
- Produces:
  - `TestSummaryPrinter`:
    - `static String headline(TestResults)`;
    - `static List<String> lines(TestResults, boolean full, boolean keys)`;
    - `static String failureLine(TestResults.Failure)`;
    - `KEYS`, `MAX_FAILURE_LINES = 10`.
  - `TerminalControls implements Runnable`:
    - `enum Command {RUN_ALL, RERUN_FAILED, QUIT}`;
    - `TerminalControls(InputStream, Consumer<Command>)`;
    - `static boolean attached()`;
    - `static Optional<Command> parse(String line)`.

- [ ] **Step 1: Write the failing tests**

`PLUGIN_TEST/TestSummaryPrinterTest.java`:

```java
package io.vidocq.runtime.maven.dev;

import io.vidocq.runtime.maven.dev.TestResults.Counts;
import io.vidocq.runtime.maven.dev.TestResults.Failure;
import io.vidocq.runtime.maven.dev.TestResults.Trigger;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSummaryPrinterTest {

    private static final Instant AT = Instant.parse("2026-09-25T10:12:03Z");
    private static final String LOG = "target/vidocq-dev-tests.log";
    private static final Failure FAILURE = new Failure("com.acme.OrderServiceTest#rejectsEmptyCart",
            "org.opentest4j.AssertionFailedError", "expected: <400> but was: <200>");

    private static TestResults completed(int exit, Counts counts, List<Failure> failures) {
        return TestResults.completed(Trigger.CHANGE, AT, 3210, exit,
                new SurefireReports.Reports(counts, failures, 1, 0), LOG, warning -> {});
    }

    @Test
    void theSpecsThreeLines() {
        TestResults failed = completed(1, new Counts(42, 1, 0, 0), List.of(FAILURE));

        assertEquals(List.of(
                "Tests: 41 passed, 1 failed, 0 skipped in 3.2 s (change)",
                "  FAILED com.acme.OrderServiceTest#rejectsEmptyCart — AssertionFailedError: expected: <400> but"
                        + " was: <200>",
                "Log: target/vidocq-dev-tests.log   [r] run all  [f] rerun failed  [q] quit"),
                TestSummaryPrinter.lines(failed, true, true));
    }

    @Test
    void vidocqDevPrintsTheFirstLineOnlyAndNoKeysWithoutAConsole() {
        TestResults failed = completed(1, new Counts(42, 1, 0, 0), List.of(FAILURE));

        assertEquals(List.of("Tests: 41 passed, 1 failed, 0 skipped in 3.2 s (change)"),
                TestSummaryPrinter.lines(failed, false, true));
        assertEquals("Log: target/vidocq-dev-tests.log", TestSummaryPrinter.lines(failed, true, false).getLast());
    }

    @Test
    void errorsCountAsFailedAndSkippedAsSkipped() {
        assertEquals("Tests: 1 passed, 2 failed, 1 skipped in 3.2 s (change)",
                TestSummaryPrinter.headline(completed(1, new Counts(4, 1, 1, 1), List.of(FAILURE, FAILURE))));
    }

    @Test
    void theOtherStatesHaveOneLine() {
        assertEquals("Tests: compilation failed (change), see target/vidocq-dev-tests.log",
                TestSummaryPrinter.headline(completed(1, Counts.NONE, List.of())));
        assertEquals("Tests: no test to run (change)",
                TestSummaryPrinter.headline(completed(0, Counts.NONE, List.of())));
        assertEquals("Tests: cancelled (test-change), a newer change runs next", TestSummaryPrinter.headline(
                TestResults.cancelled(Trigger.TEST_CHANGE, AT, 10, LOG, null)));
    }

    @Test
    void atMostTenFailuresAreListed() {
        List<Failure> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(FAILURE);
        }
        List<String> lines = TestSummaryPrinter.lines(completed(1, new Counts(12, 12, 0, 0), many), true, false);

        assertEquals(1 + 10 + 1 + 1, lines.size());
        assertEquals("  … and 2 more in target/vidocq-dev-tests.log", lines.get(11));
    }

    @Test
    void aFailureWithoutMessageShowsItsType() {
        assertEquals("  FAILED a.B#c — NullPointerException",
                TestSummaryPrinter.failureLine(new Failure("a.B#c", "java.lang.NullPointerException", "")));
    }
}
```

`PLUGIN_TEST/TerminalControlsTest.java`:

```java
package io.vidocq.runtime.maven.dev;

import io.vidocq.runtime.maven.dev.TerminalControls.Command;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TerminalControlsTest {

    @Test
    void eachKeyAndEnter() {
        assertEquals(Optional.of(Command.RUN_ALL), TerminalControls.parse("r"));
        assertEquals(Optional.of(Command.RERUN_FAILED), TerminalControls.parse("F"));
        assertEquals(Optional.of(Command.QUIT), TerminalControls.parse("q"));
        assertEquals(Optional.of(Command.RUN_ALL), TerminalControls.parse(""), "Enter alone");
        assertEquals(Optional.of(Command.RERUN_FAILED), TerminalControls.parse("  failed please"),
                "the first character decides");
        assertEquals(Optional.empty(), TerminalControls.parse("x"));
    }

    @Test
    void linesAreReadUntilQuit() {
        List<Command> seen = new ArrayList<>();
        byte[] typed = "r\nzzz\nf\nq\nr\n".getBytes(StandardCharsets.UTF_8);

        new TerminalControls(new ByteArrayInputStream(typed), seen::add).run();

        assertEquals(List.of(Command.RUN_ALL, Command.RERUN_FAILED, Command.QUIT), seen);
    }

    @Test
    void theEndOfTheInputEndsTheReading() {
        List<Command> seen = new ArrayList<>();

        new TerminalControls(new ByteArrayInputStream(new byte[0]), seen::add).run();

        assertEquals(List.of(), seen);
    }

    /** Surefire's forked JVM reads a pipe: on JDK 22+ System.console() is not null there, but it is no terminal. */
    @Test
    void aPipedStandardInputIsNoConsole() {
        assertFalse(TerminalControls.attached());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -q -pl vidocq-runtime-maven-plugin test -Dtest='TestSummaryPrinterTest,TerminalControlsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAILURE.

- [ ] **Step 3: Write the implementation**

`PLUGIN/TestSummaryPrinter.java`:

```java
package io.vidocq.runtime.maven.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The terminal lines of a run (spec §4.3): {@code vidocq:dev} prints the headline only, {@code vidocq:test} the
 * headline, one line per failure — {@value #MAX_FAILURE_LINES} at most — and the log, with the keys when a console
 * is attached. Messages come masked from {@link SurefireReports}.
 */
final class TestSummaryPrinter {

    /** The most failures listed; the others are counted. */
    static final int MAX_FAILURE_LINES = 10;
    /** The key hints, after the log. */
    static final String KEYS = "   [r] run all  [f] rerun failed  [q] quit";

    private TestSummaryPrinter() {}

    static String headline(TestResults r) {
        String trigger = r.trigger().wire();
        return switch (r.state()) {
            case PASSED, FAILED -> String.format(Locale.ROOT, "Tests: %d passed, %d failed, %d skipped in %.1f s (%s)",
                    r.counts().passed(), r.counts().failures() + r.counts().errors(), r.counts().skipped(),
                    r.durationMillis() / 1000.0, trigger);
            case COMPILE_ERROR -> "Tests: compilation failed (" + trigger + "), see " + r.log();
            case NO_TESTS -> "Tests: no test to run (" + trigger + ")";
            case CANCELLED -> "Tests: cancelled (" + trigger + "), a newer change runs next";
            case RUNNING -> "Tests: running (" + trigger + ")";
        };
    }

    /**
     * @param full {@code false} for the headline alone ({@code vidocq:dev})
     * @param keys whether to add the key hints to the log line
     */
    static List<String> lines(TestResults r, boolean full, boolean keys) {
        List<String> lines = new ArrayList<>();
        lines.add(headline(r));
        if (!full) {
            return lines;
        }
        List<TestResults.Failure> failures = r.failures();
        for (int i = 0; i < Math.min(failures.size(), MAX_FAILURE_LINES); i++) {
            lines.add(failureLine(failures.get(i)));
        }
        if (failures.size() > MAX_FAILURE_LINES) {
            lines.add("  … and " + (failures.size() - MAX_FAILURE_LINES) + " more in " + r.log());
        }
        lines.add("Log: " + r.log() + (keys ? KEYS : ""));
        return lines;
    }

    /** {@code  FAILED <test> — <simple type>: <message>}. */
    static String failureLine(TestResults.Failure failure) {
        String type = failure.type().substring(failure.type().lastIndexOf('.') + 1);
        String what = type.isEmpty() ? failure.message()
                : failure.message().isEmpty() ? type : type + ": " + failure.message();
        return "  FAILED " + failure.test() + (what.isEmpty() ? "" : " — " + what);
    }
}
```

`PLUGIN/TerminalControls.java`:

```java
package io.vidocq.runtime.maven.dev;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The keys of {@code vidocq:test} (spec §4.2), read a line at a time: the first character of a line decides —
 * {@code r} runs every test, {@code f} the failed ones, {@code q} quits — and Enter alone runs every test. Reading
 * stops at {@code q} or at the end of the input.
 */
final class TerminalControls implements Runnable {

    enum Command { RUN_ALL, RERUN_FAILED, QUIT }

    private final InputStream in;
    private final Consumer<Command> commands;

    TerminalControls(InputStream in, Consumer<Command> commands) {
        this.in = in;
        this.commands = commands;
    }

    /**
     * Whether a person can type keys: a console that is a terminal. Since JDK 22 {@link System#console()} returns
     * a {@code Console} even when standard input is a pipe (CI, an IDE's run window), so its {@code isTerminal()}
     * decides.
     */
    static boolean attached() {
        Console console = System.console();
        return console != null && console.isTerminal();
    }

    static Optional<Command> parse(String line) {
        String key = line.strip();
        if (key.isEmpty()) {
            return Optional.of(Command.RUN_ALL);
        }
        return switch (Character.toLowerCase(key.charAt(0))) {
            case 'r' -> Optional.of(Command.RUN_ALL);
            case 'f' -> Optional.of(Command.RERUN_FAILED);
            case 'q' -> Optional.of(Command.QUIT);
            default -> Optional.empty();
        };
    }

    @Override
    public void run() {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                Optional<Command> command = parse(line);
                if (command.isEmpty()) {
                    continue;
                }
                commands.accept(command.get());
                if (command.get() == Command.QUIT) {
                    return;
                }
            }
        } catch (IOException closed) {
            // the goal is stopping
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: the command of Step 2.
Expected: `Tests run: 10, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/{TestSummaryPrinter,TerminalControls}.java \
        vidocq-runtime-maven-plugin/src/test/java/io/vidocq/runtime/maven/dev/{TestSummaryPrinterTest,TerminalControlsTest}.java
git commit -S -F <msgfile>   # "feat(plugin): terminal summary and keys for continuous testing (#122)"
```

---

### Task 6: `vidocq:dev` runs the tests

**Files:**
- Modify: `vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/main/java/io/vidocq/runtime/devservices/host/DevServicesFlag.java`
- Modify: `PLUGIN/VidocqDevMojo.java`
- Modify: `vidocq-runtime-maven-plugin/src/main/resources/META-INF/maven/plugin.xml` (the `dev` mojo)
- Test: `vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/test/java/io/vidocq/runtime/devservices/host/DevServicesFlagTest.java` (add),
  `PLUGIN_TEST/VidocqDevMojoTest.java` (add), `PLUGIN_TEST/PluginDescriptorContinuousTestingTest.java` (new)

**Interfaces:**
- Consumes:
  - Tasks 1-5: `ContinuousTesting.forProject`, `start`, `changed`, `interrupt`, `request`, `close`,
    `ContinuousTesting.QUEUED`, `TestControl`, `TestControl.ReadyGate`, `TestRequestFile.poll`/`FILE_NAME`,
    `TestResultsFile.FILE_NAME`/`PROPERTY`, `TestSummaryPrinter.headline`;
  - `SourceWatcher.on(main, test, debounce)`/`awaitChanges`/`Change`;
  - `ReloadAck.stamp`/`gate`.
- Produces:
  - `DevServicesFlag.enabled(String key, Optional<String> explicit, Function<String, Optional<String>> files,
    boolean defaultValue)`;
  - in `VidocqDevMojo`:
    - `static final String CONTINUOUS_TESTING_KEY = "vidocq.dev.continuousTesting"`;
    - `static List<Path> parseDirs(Path projectDir, String csv)`, used by Task 7;
    - `boolean continuousTestingEnabled(Path projectDir, Function<String, Optional<String>> files)`;
    - `interface Reload { Optional<TestControl.ReadyGate> run() throws IOException, InterruptedException; }`;
    - `static void onChange(SourceWatcher.Change, TestControl, Reload)`;
    - `void setContinuousTesting(Boolean)`.

- [ ] **Step 1: Write the failing tests**

Add to `DevServicesFlagTest`:

```java
    @Test
    void anotherSwitchReadsItsOwnKeyByTheSameRule() {
        String key = "vidocq.dev.continuousTesting";
        Map<String, String> map = Map.of(key, "false", DevServicesFlag.KEY, "true");
        Function<String, Optional<String>> both = k -> Optional.ofNullable(map.get(k));

        assertFalse(DevServicesFlag.enabled(key, Optional.empty(), both, true));
        assertTrue(DevServicesFlag.enabled(key, Optional.of("TRUE"), both, false));
        assertTrue(DevServicesFlag.enabled(key, Optional.empty(), NO_FILES, true));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DevServicesFlag.enabled(key, Optional.of("maybe"), NO_FILES, true));
        assertTrue(e.getMessage().contains(key), e.getMessage());
    }
```

Add to `PLUGIN_TEST/VidocqDevMojoTest.java` (imports as needed: `java.util.ArrayList`, `java.util.Optional`,
`java.util.Map`, `java.util.function.Function`, `org.apache.maven.plugin.MojoExecutionException`,
`org.junit.jupiter.api.io.TempDir`, `assertThrows`):

```java
    private static Function<String, Optional<String>> files(String key, String value) {
        return k -> k.equals(key) ? Optional.of(value) : Optional.empty();
    }

    private static final Function<String, Optional<String>> NO_FILES = k -> Optional.empty();

    @Test
    void continuousTestingIsOnWhenTheProjectHasTestSources(@TempDir Path dir) throws Exception {
        VidocqDevMojo mojo = new VidocqDevMojo();

        assertFalse(mojo.continuousTestingEnabled(dir, NO_FILES), "no src/test/java");
        Files.createDirectories(dir.resolve("src/test/java"));
        assertTrue(mojo.continuousTestingEnabled(dir, NO_FILES));
    }

    @Test
    void theFilesBeatTheDefaultAndAnExplicitValueBeatsTheFiles(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/test/java"));
        VidocqDevMojo mojo = new VidocqDevMojo();

        assertFalse(mojo.continuousTestingEnabled(dir,
                files(VidocqDevMojo.CONTINUOUS_TESTING_KEY, "false")));
        mojo.setContinuousTesting(true);
        assertTrue(mojo.continuousTestingEnabled(dir, files(VidocqDevMojo.CONTINUOUS_TESTING_KEY, "false")));
    }

    @Test
    void anInvalidSwitchFailsTheGoalNamingTheKey(@TempDir Path dir) {
        MojoExecutionException e = assertThrows(MojoExecutionException.class, () -> new VidocqDevMojo()
                .continuousTestingEnabled(dir, files(VidocqDevMojo.CONTINUOUS_TESTING_KEY, "sometimes")));
        assertTrue(e.getMessage().contains(VidocqDevMojo.CONTINUOUS_TESTING_KEY), e.getMessage());
    }

    /** Records what the loop asks of the tests, and when the reload ran, in one list. */
    private static final class Recording implements TestControl {
        final List<String> calls = new ArrayList<>();

        @Override
        public void changed(TestResults.Trigger trigger, ReadyGate gate) {
            calls.add("changed " + trigger.wire());
        }

        @Override
        public void interrupt() {
            calls.add("interrupt");
        }
    }

    @Test
    void aTestChangeRunsTheTestsWithoutAReload() throws Exception {
        Recording tests = new Recording();

        VidocqDevMojo.onChange(new SourceWatcher.Change(false, true), tests, () -> {
            tests.calls.add("reload");
            return Optional.of(TestControl.ReadyGate.NOW);
        });

        assertEquals(List.of("changed test-change"), tests.calls);
    }

    @Test
    void aMainChangeStopsTheTestsReloadsThenRunsThem() throws Exception {
        Recording tests = new Recording();

        VidocqDevMojo.onChange(new SourceWatcher.Change(true, true), tests, () -> {
            tests.calls.add("reload");
            return Optional.of(TestControl.ReadyGate.NOW);
        });

        assertEquals(List.of("interrupt", "reload", "changed change"), tests.calls);
    }

    @Test
    void aFailedRecompileRunsNoTest() throws Exception {
        Recording tests = new Recording();

        VidocqDevMojo.onChange(new SourceWatcher.Change(true, false), tests, () -> {
            tests.calls.add("reload");
            return Optional.empty();
        });

        assertEquals(List.of("interrupt", "reload"), tests.calls);
    }

    @Test
    void withoutContinuousTestingAMainChangeOnlyReloads() throws Exception {
        List<String> calls = new ArrayList<>();

        VidocqDevMojo.onChange(new SourceWatcher.Change(true, false), null, () -> {
            calls.add("reload");
            return Optional.of(TestControl.ReadyGate.NOW);
        });

        assertEquals(List.of("reload"), calls);
    }
```

`PLUGIN_TEST/PluginDescriptorContinuousTestingTest.java` (the XML helpers are copied from
`PluginDescriptorDevDebugTest`, as each descriptor test keeps its own):

```java
package io.vidocq.runtime.maven.dev;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the continuous-testing entries (#122) of the hand-written {@code META-INF/maven/plugin.xml}: a typo there
 * leaves a field unset and the property silently ignored, since the unit tests call the mojos directly.
 */
class PluginDescriptorContinuousTestingTest {

    private static final Map<String, Element> MOJOS = new HashMap<>();

    @BeforeAll
    static void readDescriptor() throws Exception {
        Path file = Path.of("target", "classes", "META-INF", "maven", "plugin.xml");
        assertTrue(Files.isRegularFile(file),
                "run from the module directory after process-resources: " + file.toAbsolutePath());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Element root = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
        for (Element mojo : children(child(root, "mojos"))) {
            MOJOS.put(text(mojo, "goal"), mojo);
        }
    }

    @Test
    void devWatchesTheTestDirectories() throws Exception {
        assertConfigured("dev", "testWatchDirs", "java.lang.String", "${vidocq.dev.testWatchDirs}",
                "src/test/java,src/test/resources");
        assertEquals(String.class, VidocqDevMojo.class.getDeclaredField("testWatchDirs").getType());
    }

    /** No default, so that the application's files are read when neither -D nor the configuration sets it. */
    @Test
    void devHasAnUnsetContinuousTestingSwitch() throws Exception {
        assertConfigured("dev", "continuousTesting", "java.lang.Boolean", "${vidocq.dev.continuousTesting}", "");
        assertEquals(Boolean.class, VidocqDevMojo.class.getDeclaredField("continuousTesting").getType());
    }

    @Test
    void everyDevParameterIsAFieldOfTheMojo() throws Exception {
        assertParametersAreFields("dev", VidocqDevMojo.class);
    }

    static void assertConfigured(String goal, String name, String type, String expression, String defaultValue) {
        Element mojo = MOJOS.get(goal);
        assertNotNull(mojo, "no <mojo> with <goal>" + goal + "</goal>");
        assertEquals(type, text(parameter(mojo, name), "type"));
        Element entry = child(child(mojo, "configuration"), name);
        assertNotNull(entry, "<configuration> of " + goal + " has no " + name + " entry");
        assertEquals(expression, entry.getTextContent().strip());
        assertEquals(defaultValue, entry.getAttribute("default-value"));
        assertEquals(type, entry.getAttribute("implementation"));
    }

    static void assertParametersAreFields(String goal, Class<?> mojo) throws Exception {
        for (Element parameter : children(child(MOJOS.get(goal), "parameters"))) {
            String name = text(parameter, "name");
            assertNotNull(mojo.getDeclaredField(name), goal + " declares " + name);
        }
    }

    static Element mojo(String goal) {
        return MOJOS.get(goal);
    }

    static Element parameter(Element mojo, String name) {
        for (Element element : children(child(mojo, "parameters"))) {
            if (name.equals(text(element, "name"))) {
                return element;
            }
        }
        throw new AssertionError("no parameter " + name);
    }

    static String text(Element parent, String name) {
        Element child = child(parent, name);
        return child == null ? null : child.getTextContent().strip();
    }

    static Element child(Element parent, String name) {
        for (Element element : children(parent)) {
            if (element.getTagName().equals(name)) {
                return element;
            }
        }
        return null;
    }

    static List<Element> children(Element parent) {
        List<Element> elements = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -q -pl vidocq-runtime-devservices/vidocq-runtime-devservices-host,vidocq-runtime-maven-plugin -am test -Dtest='DevServicesFlagTest,VidocqDevMojoTest,PluginDescriptorContinuousTestingTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAILURE (`enabled(String, …)`, `continuousTestingEnabled`, `onChange` not found).

- [ ] **Step 3: Write the implementation**

`DevServicesFlag.java`: replace the body of the public `enabled` and `parse`:

```java
    public static boolean enabled(Optional<String> explicit, Function<String, Optional<String>> files,
            boolean defaultValue) {
        return enabled(KEY, explicit, files, defaultValue);
    }

    /**
     * The same rule for another switch of the {@code vidocq.dev.*} family, such as
     * {@code vidocq.dev.continuousTesting} (#122): the explicit value, then {@code key} in the application's files,
     * then {@code defaultValue}.
     *
     * @throws IllegalArgumentException when the value that wins is neither {@code true} nor {@code false}; the
     *                                  message names {@code key} and the value
     */
    public static boolean enabled(String key, Optional<String> explicit, Function<String, Optional<String>> files,
            boolean defaultValue) {
        Optional<String> value = explicit.filter(v -> !v.isBlank())
                .or(() -> files.apply(key).filter(v -> !v.isBlank()));
        return value.map(v -> parse(key, v)).orElse(defaultValue);
    }

    private static boolean parse(String key, String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(
                    key + " must be true or false, not '" + value + "'");
        };
    }
```

`VidocqDevMojo.java`:

1. Class Javadoc: after the `<li>` "Watch {@code src/main/{java,resources}} for changes." add
   `<li>With continuous testing (#122), watch {@code src/test/{java,resources}} too and run the tests after every
   reload, or alone after a test-only change; see {@link ContinuousTesting}.</li>`.
2. New imports: `io.vidocq.runtime.maven.dev.TestResults.Trigger`, `java.util.function.Function` (already there),
   `java.util.Optional` (already there).
3. After the `watchDirs` field add:

```java
    /**
     * Test directories to watch, relative to the project base dir: a change there runs the tests again, without an
     * application reload. Only with continuous testing.
     */
    @Parameter(property = "vidocq.dev.testWatchDirs", defaultValue = "src/test/java,src/test/resources")
    private String testWatchDirs;

    /**
     * Continuous testing (#122): run the application's tests after every reload, results in the dev console's
     * {@code tests} panel. Unset by default, so that {@code vidocq.dev.continuousTesting} in the application's files
     * is read; then on when {@code src/test/java} exists. See {@link #continuousTestingEnabled}.
     */
    @Parameter(property = "vidocq.dev.continuousTesting")
    private Boolean continuousTesting;
```

4. Add the constants after `DEV_PROVIDED_PREFIX`:

```java
    /** The continuous-testing switch, in every source. */
    static final String CONTINUOUS_TESTING_KEY = "vidocq.dev.continuousTesting";

    /** How long the tests of a main change wait for the hot reload to answer. */
    static final Duration RELOAD_ACK_TIMEOUT = Duration.ofMinutes(2);
```

5. Replace the whole `execute()` with:

```java
    @Override
    public void execute() throws MojoExecutionException {
        Path projectDir = baseDir.toPath();
        List<Path> watch = parseDirs(projectDir, watchDirs);
        List<Path> modulePath = buildModulePath();
        List<Path> appPath = buildAppPath();
        Map<String, String> sysProps = buildSystemProperties();
        List<String> jvmArgs = buildJvmArgs();

        // In-JVM hot reload: the child watches this file's mtime and swaps its
        // application layer when we touch it (after a successful recompile).
        boolean inJvmReload = layerMode && hotReload;
        Path reloadFile = buildDir.toPath().resolve(".vidocq-dev-reload");
        if (inJvmReload) {
            try {
                Files.createDirectories(reloadFile.getParent());
                Files.writeString(reloadFile, "0\n");
            } catch (IOException e) {
                throw new MojoExecutionException("Cannot create the reload signal file "
                        + reloadFile, e);
            }
            sysProps.put("vidocq.dev.reload.file", reloadFile.toString());
        }

        // The dev module path uses the original dependency jars: re-attach the classes
        // that vidocq:generate parked for scanned dependencies (target/vidocq-patches)
        // to their owning module, exactly like the packaging goals do by enrichment.
        try {
            List<String> patchArgs = ApplicationLaunch.patchModuleArgs(project, buildDirPath());
            if (!patchArgs.isEmpty()) {
                jvmArgs = new ArrayList<>(jvmArgs);
                jvmArgs.addAll(patchArgs);
                getLog().info("JPMS: " + patchArgs.size() / 2
                        + " --patch-module option(s) added for generated classes");
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to compute --patch-module options", e);
        }

        getLog().info("Vidocq dev — main module : " + mainModule
                + (mainClass != null && !mainClass.isBlank() ? ("/" + mainClass) : ""));
        if (layerMode) {
            getLog().info("Universal-loader mode: app classes boot in a Vauban-defined"
                    + " module layer (-Dvidocq.dev.layer=false for the legacy launch)");
        }
        getLog().info("Watching: " + watch);
        getLog().info("Module path entries: " + modulePath.size());
        logDebugAgent();

        // Provision dev-mode services (Postgres, Keycloak, …) ONCE, before the first fork. Their
        // connection coordinates are folded into the child's system properties; an explicit -D or a
        // vidocq.dev.systemProperties entry always wins (putIfAbsent), and each key the child does get from
        // a provider is marked vidocq.dev.provided.<key>=<id>. The containers live for the whole session —
        // source reloads respawn the child but never touch them.
        DevServicesSession devs = null;
        Function<String, Optional<String>> applicationFiles = ApplicationFiles.of(classesDir.toPath());
        if (devServicesEnabled(applicationFiles)) {
            // Resolved before open(): a missing extension/devconsole-spi jar must abort before any
            // container is started, never leave a running session with nothing left to close it.
            List<Path> extensionJars = DevServicesExtensionJar.resolve(pluginArtifactMap, project.getArtifacts());
            try {
                devs = DevServicesSession.open("vidocq:dev", projectDir, sysProps, applicationFiles,
                        System.getLogger("vidocq.dev.devservices"));
            } catch (DevServicesException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
            foldDevServiceProperties(sysProps, devs.injected(), devs.providers());
            sysProps.putIfAbsent(StateFile.PROPERTY, devs.stateFile().toAbsolutePath().toString());
            modulePath.addAll(extensionJars);
        }
        final DevServicesSession devServicesRef = devs;

        // Continuous testing (#122): the test directories are watched too, the child's dev console learns where
        // the results are, and every run gets the dev session's keys, so the tests use its containers.
        boolean testsOn = continuousTestingEnabled(projectDir, applicationFiles);
        List<Path> testWatch = testsOn ? parseDirs(projectDir, testWatchDirs) : List.of();
        ContinuousTesting testing = null;
        TestRequestFile requests = null;
        if (testsOn) {
            Path results = buildDirPath().resolve(TestResultsFile.FILE_NAME);
            sysProps.put(TestResultsFile.PROPERTY, results.toAbsolutePath().toString());
            Map<String, String> testProps = new LinkedHashMap<>();
            if (devs != null) {
                devs.foldInto(testProps);
            }
            ContinuousTesting started = ContinuousTesting.forProject(projectDir, buildDirPath(), testProps,
                    result -> getLog().info(TestSummaryPrinter.headline(result)), getLog()::warn);
            testing = started;
            try {
                requests = TestRequestFile.poll(results.resolveSibling(TestRequestFile.FILE_NAME), trigger -> {
                    String outcome = started.request(trigger);
                    if (!ContinuousTesting.QUEUED.equals(outcome)) {
                        getLog().info("Tests: " + outcome);
                    }
                }, getLog()::warn);
            } catch (IOException e) {
                closeDevServices(devServicesRef);
                throw new MojoExecutionException("Cannot watch the test request file", e);
            }
            getLog().info("Continuous testing: on, watching " + testWatch
                    + " (-D" + CONTINUOUS_TESTING_KEY + "=false to turn it off)");
        }
        final ContinuousTesting testingRef = testing;
        final TestRequestFile requestsRef = requests;

        // The atomic reference lets the shutdown hook (running on a separate
        // thread) see the latest spawned child, no matter how many reload
        // cycles we have been through.
        AtomicReference<ChildJvm> currentChild = new AtomicReference<>();
        Thread mainThread = Thread.currentThread();

        Thread hook = new Thread(() -> {
            mainThread.interrupt();
            closeTesting(testingRef, requestsRef);
            ChildJvm c = currentChild.get();
            if (c != null) {
                try {
                    c.stop(Duration.ofMillis(gracePeriodMillis));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            closeDevServices(devServicesRef);
        }, "vidocq-dev-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);

        RecompileRunner recompile = new RecompileRunner(projectDir);
        ChildLaunch launch = new ChildLaunch(modulePath, appPath, jvmArgs, sysProps, projectDir);

        try (SourceWatcher watcher = SourceWatcher.on(watch, testWatch, Duration.ofMillis(debounceMillis))) {
            ChildJvm child = newChild(launch);
            long pid = child.start();
            currentChild.set(child);
            getLog().info("Child JVM started, pid=" + pid + ". Listening for changes (Ctrl+C to stop).");
            if (testingRef != null) {
                // The first run, once the first boot completed (spec §7: the panel shows a result at once).
                testingRef.start();
                testingRef.changed(Trigger.RUN_ALL, inJvmReload
                        ? ReloadAck.gate(reloadFile, ReloadAck.stamp(reloadFile), RELOAD_ACK_TIMEOUT,
                                child::isAlive, getLog()::warn)
                        : TestControl.ReadyGate.NOW);
            }

            while (!Thread.currentThread().isInterrupted()) {
                SourceWatcher.Change change = watcher.awaitChanges();
                if (change == null) {
                    break; // watcher closed
                }
                onChange(change, testingRef,
                        () -> recompileAndReload(recompile, launch, currentChild, reloadFile, inJvmReload));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            getLog().info("Dev mode interrupted — shutting down.");
        } catch (IOException e) {
            throw new MojoExecutionException("Dev mode initialisation failed", e);
        } finally {
            closeTesting(testingRef, requestsRef);
            // The shutdown hook will have done it already on Ctrl+C, but if we
            // fell through because the watcher closed normally we still need to
            // tear the child down here.
            ChildJvm c = currentChild.getAndSet(null);
            if (c != null && c.isAlive()) {
                try {
                    c.stop(Duration.ofMillis(gracePeriodMillis));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            // Stop the dev-mode containers (idempotent — the hook may already have run on Ctrl+C).
            closeDevServices(devServicesRef);
            // Avoid IllegalStateException if the JVM is mid-shutdown.
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // shutdown already in progress
            }
        }
    }

    /** What every child JVM of this session is started with. */
    private record ChildLaunch(List<Path> modulePath, List<Path> appPath, List<String> jvmArgs,
            Map<String, String> sysProps, Path projectDir) {}

    private ChildJvm newChild(ChildLaunch launch) {
        return ChildJvm.of(launch.modulePath(), launch.appPath(), mainModule, mainClass, launch.jvmArgs(),
                launch.sysProps(), launch.projectDir());
    }

    /** Recompiles and reloads the application: what the tests of this change wait for. */
    @FunctionalInterface
    interface Reload {

        /** @return the gate that opens once the application booted again, or empty when nothing was reloaded */
        Optional<TestControl.ReadyGate> run() throws IOException, InterruptedException;
    }

    /**
     * One change of the source loop (spec §2.2). A test-only change runs the tests, and nothing is reloaded. A main
     * change first stops the run in flight, so that its Maven never races the recompile. It then recompiles and
     * reloads, and runs the tests once the reload completed; a failed recompile runs none.
     *
     * @param tests {@code null} without continuous testing
     */
    static void onChange(SourceWatcher.Change change, TestControl tests, Reload reload)
            throws IOException, InterruptedException {
        if (!change.main()) {
            if (tests != null && change.test()) {
                tests.changed(Trigger.TEST_CHANGE, TestControl.ReadyGate.NOW);
            }
            return;
        }
        if (tests != null) {
            tests.interrupt();
        }
        Optional<TestControl.ReadyGate> booted = reload.run();
        if (tests != null && booted.isPresent()) {
            tests.changed(Trigger.CHANGE, booted.get());
        }
    }

    private Optional<TestControl.ReadyGate> recompileAndReload(RecompileRunner recompile, ChildLaunch launch,
            AtomicReference<ChildJvm> currentChild, Path reloadFile, boolean inJvmReload)
            throws IOException, InterruptedException {
        long t0 = System.nanoTime();
        getLog().info("Changes detected — recompiling...");
        int rc;
        try {
            rc = recompile.run();
        } catch (IOException e) {
            getLog().warn("Recompile invocation failed: " + e.getMessage());
            return Optional.empty();
        }
        if (rc != 0) {
            getLog().warn("Compile failed (exit " + rc + "); keeping previous JVM up.");
            return Optional.empty();
        }
        ChildJvm child = currentChild.get();
        if (inJvmReload && child.isAlive()) {
            // Signal the child: it re-creates its application layer in place.
            try {
                Files.writeString(reloadFile, System.nanoTime() + "\n");
                long stamp = ReloadAck.stamp(reloadFile);
                getLog().info("Hot reload signalled after " + (System.nanoTime() - t0) / 1_000_000
                        + " ms (in-JVM layer swap).");
                return Optional.of(ReloadAck.gate(reloadFile, stamp, RELOAD_ACK_TIMEOUT, child::isAlive,
                        getLog()::warn));
            } catch (IOException e) {
                getLog().warn("Cannot signal hot reload (" + e.getMessage()
                        + ") — falling back to a respawn.");
            }
        }
        child.stop(Duration.ofMillis(gracePeriodMillis));
        ChildJvm next = newChild(launch);
        long pid = next.start();
        currentChild.set(next);
        getLog().info("Reloaded in " + (System.nanoTime() - t0) / 1_000_000 + " ms (pid=" + pid + ").");
        return Optional.of(TestControl.ReadyGate.NOW);
    }
```

6. Replace `parseWatchDirs` with the static:

```java
    /** Comma-separated directories, relative to {@code projectDir}; blanks skipped. Shared with {@code vidocq:test}. */
    static List<Path> parseDirs(Path projectDir, String csv) {
        List<Path> result = new ArrayList<>();
        for (String s : csv.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                result.add(projectDir.resolve(trimmed));
            }
        }
        return result;
    }
```

7. After `devServicesEnabled(...)` add:

```java
    /**
     * {@code vidocq.dev.continuousTesting}, first match wins (spec §2.1), as {@link #devServicesEnabled}: the explicit
     * value, then the application's own files, then on when the project has {@code src/test/java}.
     */
    // package-private for the unit test.
    boolean continuousTestingEnabled(Path projectDir, Function<String, Optional<String>> files)
            throws MojoExecutionException {
        try {
            return DevServicesFlag.enabled(CONTINUOUS_TESTING_KEY,
                    Optional.ofNullable(continuousTesting).map(String::valueOf), files,
                    Files.isDirectory(projectDir.resolve("src/test/java")));
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    /**
     * Stops continuous testing: the request polling, then the run in flight, cancelled. Both are idempotent; the
     * lock makes the shutdown hook and {@code finally} wait for each other, as {@link #closeDevServices} does.
     */
    synchronized void closeTesting(ContinuousTesting testing, TestRequestFile requests) {
        if (requests != null) {
            requests.close();
        }
        if (testing != null) {
            testing.close();
        }
    }
```

8. Add the setter next to the others: `void setContinuousTesting(Boolean continuousTesting) { this.continuousTesting = continuousTesting; }`.

`plugin.xml`, the `dev` mojo:
- In `<parameters>`, after the `watchDirs` line, add:

```xml
                <parameter><name>testWatchDirs</name><type>java.lang.String</type><required>false</required><editable>true</editable><description>Comma-separated test directories to watch with continuous testing, relative to the project base dir: a change there runs the tests again without an application reload.</description></parameter>
                <parameter><name>continuousTesting</name><type>java.lang.Boolean</type><required>false</required><editable>true</editable><description>Run the application's tests after every reload (continuous testing). Unset by default: then vidocq.dev.continuousTesting in the application's files decides, else on when src/test/java exists.</description></parameter>
```

- In `<configuration>`, after the `watchDirs` entry, add:

```xml
                <testWatchDirs implementation="java.lang.String" default-value="src/test/java,src/test/resources">${vidocq.dev.testWatchDirs}</testWatchDirs>
                <continuousTesting implementation="java.lang.Boolean">${vidocq.dev.continuousTesting}</continuousTesting>
```

- The `dev` mojo `<description>`: append ` Runs the tests after every reload (continuous testing).`

- [ ] **Step 4: Run the tests to verify they pass**

Run: the command of Step 2, then `mvn -o -q -pl vidocq-runtime-devservices/vidocq-runtime-devservices-host,vidocq-runtime-maven-plugin -am install -DskipTests` and `mvn -o -q -pl vidocq-runtime-maven-plugin test`.
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add vidocq-runtime-devservices/vidocq-runtime-devservices-host/src \
        vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/VidocqDevMojo.java \
        vidocq-runtime-maven-plugin/src/main/resources/META-INF/maven/plugin.xml \
        vidocq-runtime-maven-plugin/src/test/java/io/vidocq/runtime/maven/dev/{VidocqDevMojoTest,PluginDescriptorContinuousTestingTest}.java
git commit -S -F <msgfile>   # "feat(dev): run the tests after every vidocq:dev reload (#122)"
```

---

### Task 7: The `vidocq:test` goal

**Files:**
- Create: `PLUGIN/VidocqTestMojo.java`
- Modify: `vidocq-runtime-maven-plugin/src/main/resources/META-INF/maven/plugin.xml` (a new `<mojo>` after `dev`)
- Test: `PLUGIN_TEST/VidocqTestMojoTest.java`, `PLUGIN_TEST/PluginDescriptorContinuousTestingTest.java` (add)

**Interfaces:**
- Consumes:
  - `VidocqDevMojo.parseDirs`;
  - `ContinuousTesting.forProject`/`start`/`changed`/`request`/`close`/`QUEUED`;
  - `TestSummaryPrinter.lines`;
  - `TerminalControls` (`attached`, `Command`);
  - `SourceWatcher.on(main, test, debounce)`/`awaitChanges`;
  - `DevServicesSession.open`/`foldInto`, `DevServicesFlag.enabled`, `ApplicationFiles.of`.
- Produces: goal `test`, class `io.vidocq.runtime.maven.dev.VidocqTestMojo`:
  - `HOST = "vidocq:test"`;
  - package-private `boolean devServicesEnabled(Function<String, Optional<String>>)`;
  - `synchronized void closeDevServices(DevServicesSession)`;
  - `void setDevServices(Boolean)`.

- [ ] **Step 1: Write the failing tests**

`PLUGIN_TEST/VidocqTestMojoTest.java`:

```java
package io.vidocq.runtime.maven.dev;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VidocqTestMojoTest {

    private static final Function<String, Optional<String>> NO_FILES = key -> Optional.empty();
    private static final Function<String, Optional<String>> OFF_IN_FILES =
            key -> key.equals("vidocq.dev.devServices") ? Optional.of("false") : Optional.empty();

    @Test
    void devServicesAreOnByDefaultOffInTheFilesAndTheExplicitValueWins() throws Exception {
        VidocqTestMojo mojo = new VidocqTestMojo();

        assertTrue(mojo.devServicesEnabled(NO_FILES));
        assertFalse(mojo.devServicesEnabled(OFF_IN_FILES));
        mojo.setDevServices(true);
        assertTrue(mojo.devServicesEnabled(OFF_IN_FILES));
    }

    @Test
    void closingNoSessionIsHarmless() {
        assertDoesNotThrow(() -> new VidocqTestMojo().closeDevServices(null));
    }
}
```

Add to `PluginDescriptorContinuousTestingTest`:

```java
    @Test
    void theTestGoalIsDeclaredForItsMojo() throws Exception {
        Element mojo = mojo("test");
        assertNotNull(mojo, "no <mojo> with <goal>test</goal>");
        assertEquals(VidocqTestMojo.class.getName(), text(mojo, "implementation"));
        assertEquals("true", text(mojo, "requiresDirectInvocation"));
        assertEquals("true", text(mojo, "requiresProject"));
        assertParametersAreFields("test", VidocqTestMojo.class);
    }

    @Test
    void theTestGoalReadsTheDevWatchProperties() {
        assertConfigured("test", "watchDirs", "java.lang.String", "${vidocq.dev.watchDirs}",
                "src/main/java,src/main/resources");
        assertConfigured("test", "testWatchDirs", "java.lang.String", "${vidocq.dev.testWatchDirs}",
                "src/test/java,src/test/resources");
        assertConfigured("test", "debounceMillis", "long", "${vidocq.dev.debounceMillis}", "250");
        assertConfigured("test", "devServices", "java.lang.Boolean", "${vidocq.dev.devServices}", "");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -q -pl vidocq-runtime-maven-plugin test -Dtest='VidocqTestMojoTest,PluginDescriptorContinuousTestingTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAILURE (`VidocqTestMojo` not found).

- [ ] **Step 3: Write the implementation**

`PLUGIN/VidocqTestMojo.java`:

```java
package io.vidocq.runtime.maven.dev;

import io.vidocq.runtime.devservices.host.ApplicationFiles;
import io.vidocq.runtime.devservices.host.DevServicesException;
import io.vidocq.runtime.devservices.host.DevServicesFlag;
import io.vidocq.runtime.devservices.host.DevServicesSession;
import io.vidocq.runtime.maven.dev.TestResults.Trigger;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@code vidocq:test} (#122, spec §4): the application's tests on every change, without the application. No child
 * JVM, no dev console, no debug agent: the goal opens the dev services session once, runs every test, then watches
 * the main and test directories and runs them again on each change, a new change cancelling the run in flight. A
 * main change needs no separate recompile: the run's {@code test-compile} compiles and indexes the main sources.
 *
 * <p>Each run prints a summary; with a console, {@code r} runs every test, {@code f} the failed ones and {@code q}
 * quits. Ctrl+C always stops the goal, its run and its dev services.
 */
@Mojo(name = "test", defaultPhase = LifecyclePhase.NONE, threadSafe = false)
public class VidocqTestMojo extends AbstractMojo {

    /** The dev services host this goal is. */
    static final String HOST = "vidocq:test";

    /** Main directories to watch, relative to the project base dir. */
    @Parameter(property = "vidocq.dev.watchDirs", defaultValue = "src/main/java,src/main/resources")
    private String watchDirs;

    /** Test directories to watch, relative to the project base dir. */
    @Parameter(property = "vidocq.dev.testWatchDirs", defaultValue = "src/test/java,src/test/resources")
    private String testWatchDirs;

    /** Debounce window in milliseconds — collapses bursts of editor saves. */
    @Parameter(property = "vidocq.dev.debounceMillis", defaultValue = "250")
    private long debounceMillis;

    /**
     * Provision dev-mode services for the tests, as {@code vidocq:dev} does. Unset by default: then
     * {@code vidocq.dev.devServices} in the application's files decides, else on.
     */
    @Parameter(property = "vidocq.dev.devServices")
    private Boolean devServices;

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private File classesDir;

    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File baseDir;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File buildDir;

    @Override
    public void execute() throws MojoExecutionException {
        Path projectDir = baseDir.toPath();
        List<Path> mainDirs = VidocqDevMojo.parseDirs(projectDir, watchDirs);
        List<Path> testDirs = VidocqDevMojo.parseDirs(projectDir, testWatchDirs);
        Function<String, Optional<String>> files = ApplicationFiles.of(classesDir.toPath());
        DevServicesSession devs = null;
        if (devServicesEnabled(files)) {
            try {
                devs = DevServicesSession.open(HOST, projectDir, new LinkedHashMap<>(), files,
                        System.getLogger("vidocq.test.devservices"));
            } catch (DevServicesException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }
        final DevServicesSession session = devs;
        Map<String, String> testProps = new LinkedHashMap<>();
        if (session != null) {
            session.foldInto(testProps);
        }
        boolean keys = TerminalControls.attached();
        ContinuousTesting testing = ContinuousTesting.forProject(projectDir, buildDir.toPath(), testProps,
                result -> TestSummaryPrinter.lines(result, true, keys).forEach(getLog()::info), getLog()::warn);

        Thread mainThread = Thread.currentThread();
        Thread hook = new Thread(() -> {
            mainThread.interrupt();
            testing.close();
            closeDevServices(session);
        }, "vidocq-test-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);

        try (SourceWatcher watcher = SourceWatcher.on(mainDirs, testDirs, Duration.ofMillis(debounceMillis))) {
            getLog().info("Vidocq test — watching " + mainDirs + " and " + testDirs
                    + (keys ? "" : "; no console, so no keys: Ctrl+C to stop"));
            testing.start();
            testing.changed(Trigger.RUN_ALL, TestControl.ReadyGate.NOW);
            if (keys) {
                listenToKeys(testing, watcher);
            }
            while (!Thread.currentThread().isInterrupted()) {
                SourceWatcher.Change change = watcher.awaitChanges();
                if (change == null) {
                    break; // watcher closed: q, or the end
                }
                testing.changed(change.main() ? Trigger.CHANGE : Trigger.TEST_CHANGE, TestControl.ReadyGate.NOW);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            getLog().info("Vidocq test interrupted — shutting down.");
        } catch (IOException e) {
            throw new MojoExecutionException("Continuous testing initialisation failed", e);
        } finally {
            testing.close();
            closeDevServices(session);
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // shutdown already in progress
            }
        }
    }

    /** Reads the keys on the daemon thread {@code vidocq-test-keys}; {@code q} closes the watcher. */
    private void listenToKeys(ContinuousTesting testing, SourceWatcher watcher) {
        TerminalControls controls = new TerminalControls(System.in, command -> {
            switch (command) {
                case RUN_ALL -> testing.request(Trigger.RUN_ALL);
                case RERUN_FAILED -> {
                    String outcome = testing.request(Trigger.RERUN_FAILED);
                    if (!ContinuousTesting.QUEUED.equals(outcome)) {
                        getLog().info("Tests: " + outcome);
                    }
                }
                case QUIT -> {
                    try {
                        watcher.close();
                    } catch (IOException ignored) {
                        // the loop ends anyway
                    }
                }
            }
        });
        Thread.ofPlatform().name("vidocq-test-keys").daemon(true).start(controls);
    }

    /** {@code vidocq.dev.devServices}: the explicit value, then the application's files, then on. */
    // package-private for the unit test.
    boolean devServicesEnabled(Function<String, Optional<String>> files) throws MojoExecutionException {
        try {
            return DevServicesFlag.enabled(Optional.ofNullable(devServices).map(String::valueOf), files, true);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    /** Closes {@code session} once, the shutdown hook and {@code finally} waiting for each other (#123). */
    synchronized void closeDevServices(DevServicesSession session) {
        if (session != null) {
            session.close();
        }
    }

    void setDevServices(Boolean devServices) {
        this.devServices = devServices;
    }
}
```

`plugin.xml`: after the closing `</mojo>` of `dev`, add:

```xml
        <!-- vidocq:test (#122) — continuous testing without the application: runs the tests, then again on
             every change of the main or test sources. Blocking goal; r/f/q with a console. -->
        <mojo>
            <goal>test</goal>
            <description>Continuous testing: run the application's tests on every change, without starting the application</description>
            <requiresDirectInvocation>true</requiresDirectInvocation>
            <requiresProject>true</requiresProject>
            <requiresOnline>false</requiresOnline>
            <aggregator>false</aggregator>
            <implementation>io.vidocq.runtime.maven.dev.VidocqTestMojo</implementation>
            <language>java</language>
            <instantiationStrategy>per-lookup</instantiationStrategy>
            <threadSafe>false</threadSafe>
            <parameters>
                <parameter><name>watchDirs</name><type>java.lang.String</type><required>false</required><editable>true</editable><description>Comma-separated main directories to watch, relative to the project base dir.</description></parameter>
                <parameter><name>testWatchDirs</name><type>java.lang.String</type><required>false</required><editable>true</editable><description>Comma-separated test directories to watch, relative to the project base dir.</description></parameter>
                <parameter><name>debounceMillis</name><type>long</type><required>false</required><editable>true</editable><description>Debounce window in milliseconds; collapses bursts of editor saves into a single run.</description></parameter>
                <parameter><name>devServices</name><type>java.lang.Boolean</type><required>false</required><editable>true</editable><description>Provision dev-mode services for the tests. Unset by default: then vidocq.dev.devServices in the application's files decides, else on.</description></parameter>
                <parameter><name>classesDir</name><type>java.io.File</type><required>false</required><editable>false</editable><description>Compiled classes directory of the project.</description></parameter>
                <parameter><name>baseDir</name><type>java.io.File</type><required>false</required><editable>false</editable><description>Project base directory.</description></parameter>
                <parameter><name>buildDir</name><type>java.io.File</type><required>false</required><editable>false</editable><description>Build directory, where the results, the log and the Surefire reports are.</description></parameter>
            </parameters>
            <configuration>
                <watchDirs implementation="java.lang.String" default-value="src/main/java,src/main/resources">${vidocq.dev.watchDirs}</watchDirs>
                <testWatchDirs implementation="java.lang.String" default-value="src/test/java,src/test/resources">${vidocq.dev.testWatchDirs}</testWatchDirs>
                <debounceMillis implementation="long" default-value="250">${vidocq.dev.debounceMillis}</debounceMillis>
                <devServices implementation="java.lang.Boolean">${vidocq.dev.devServices}</devServices>
                <classesDir implementation="java.io.File" default-value="${project.build.outputDirectory}"/>
                <baseDir implementation="java.io.File" default-value="${project.basedir}"/>
                <buildDir implementation="java.io.File" default-value="${project.build.directory}"/>
            </configuration>
        </mojo>
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: the command of Step 2, then `mvn -o -q -pl vidocq-runtime-maven-plugin install` (the whole module and its
descriptor; installs the plugin for Task 9's manual check).
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/VidocqTestMojo.java \
        vidocq-runtime-maven-plugin/src/main/resources/META-INF/maven/plugin.xml \
        vidocq-runtime-maven-plugin/src/test/java/io/vidocq/runtime/maven/dev/{VidocqTestMojoTest,PluginDescriptorContinuousTestingTest}.java
git commit -S -F <msgfile>   # "feat(plugin): add the vidocq:test continuous testing goal (#122)"
```

---

### Task 8: The `tests` dev console panel

**Files:**
- Create: `CONSOLE/TestsPanel.java`, `CONSOLE/TestRequestFile.java`
- Modify: `CONSOLE/DevConsoleExtension.java`
- Modify: `vidocq-runtime-core/src/main/java/io/vidocq/runtime/core/report/StartupContributors.java`
- Test: `CONSOLE_TEST/TestsPanelTest.java`, `CONSOLE_TEST/DevConsoleExtensionTest.java` (add),
  `vidocq-runtime-core/src/test/java/io/vidocq/runtime/core/report/StartupContributorsTest.java` (modify)

**Interfaces:**
- Consumes:
  - the file contract of Task 1: the JSON shape, states and triggers, `vidocq.dev.tests.results`, and the request
    file name `vidocq-dev-tests.request` next to the results;
  - `JsonValues.parse(String, int)`, `PanelEntry.builtIn`, `DevConsoleExtension.LOGGER_NAME`;
  - the SPI: `DevConsolePanel`, `PanelSample`, `PanelAction`, `Chart`, `Series.line`, `Unit.COUNT`.
- Produces:
  - `TestsPanel`:
    - `ID = "tests"`, `PROPERTY`, `QUEUED`, `NOTHING_TO_RERUN`, `UNREADABLE = "results unreadable"`;
    - `static Optional<TestsPanel> start(String property)`;
    - `void refresh()`, `void stop()`, `Thread reader()`;
    - value keys `state` (text), `run`, `failures`, `errors` and `skipped` (gauges), `failed-tests` (table);
    - actions `run-all` and `rerun-failed`.
  - The id `tests`, reserved by the core.

- [ ] **Step 1: Write the failing tests**

`CONSOLE_TEST/TestsPanelTest.java`:

```java
package io.vidocq.runtime.extensions.essentials.devconsole;

import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.report.LaunchMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestsPanelTest {

    private static final String FAILED = "{\"state\":\"failed\",\"trigger\":\"change\","
            + "\"startedAt\":\"2026-09-25T10:12:03Z\",\"durationMillis\":3210,"
            + "\"counts\":{\"run\":42,\"failures\":1,\"errors\":0,\"skipped\":2},"
            + "\"failures\":[{\"test\":\"com.acme.OrderServiceTest#rejectsEmptyCart\","
            + "\"type\":\"org.opentest4j.AssertionFailedError\",\"message\":\"expected: <400> but was: <200>\"}],"
            + "\"log\":\"target/vidocq-dev-tests.log\"}";
    private static final String PASSED = "{\"state\":\"passed\",\"trigger\":\"test-change\","
            + "\"startedAt\":\"2026-09-25T10:13:00Z\",\"durationMillis\":2900,"
            + "\"counts\":{\"run\":42,\"failures\":0,\"errors\":0,\"skipped\":2},\"failures\":[],"
            + "\"log\":\"target/vidocq-dev-tests.log\"}";
    private static final String RUNNING = "{\"state\":\"running\",\"trigger\":\"rerun-failed\","
            + "\"startedAt\":\"2026-09-25T10:14:00Z\",\"durationMillis\":0,"
            + "\"counts\":{\"run\":0,\"failures\":0,\"errors\":0,\"skipped\":0},\"failures\":[],"
            + "\"log\":\"target/vidocq-dev-tests.log\",\"previous\":" + FAILED + "}";

    private final List<TestsPanel> panels = new ArrayList<>();
    private long modified = 1_000_000_000_000L;

    @AfterEach
    void stopEveryPanel() {
        panels.forEach(TestsPanel::stop);
    }

    private TestsPanel panel(Path results) {
        TestsPanel panel = new TestsPanel(results);
        panels.add(panel);
        return panel;
    }

    /** Writes the results with a new modification time, as each run does. */
    private void write(Path results, String json) throws Exception {
        Files.writeString(results, json);
        modified += 2_000;
        Files.setLastModifiedTime(results, FileTime.fromMillis(modified));
    }

    private static Map<String, Object> sampled(TestsPanel panel) {
        RecordingSample sample = new RecordingSample();
        panel.sample(sample);
        JsonWriter out = new JsonWriter().beginObject();
        sample.writeTo(out);
        return Json.object(out.endObject().toString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> value(Map<String, Object> sample, String key) {
        return ((List<Map<String, Object>>) sample.getOrDefault("values", List.of())).stream()
                .filter(v -> key.equals(v.get("key"))).findFirst().orElse(null);
    }

    private static long number(Map<String, Object> sample, String key) {
        return ((Number) value(sample, key).get("value")).longValue();
    }

    @Test
    void noPropertyNoPanel() {
        assertEquals(Optional.empty(), TestsPanel.start(null));
        assertEquals(Optional.empty(), TestsPanel.start(" "));
    }

    @Test
    void itIsTheConsolesOwnPanelWithItsChartAndTwoActions(@TempDir Path dir) {
        TestsPanel panel = panel(dir.resolve("vidocq-dev-tests.json"));

        PanelEntry entry = PanelEntry.builtIn(panel, LaunchMode.DEV);
        assertEquals("tests", entry.id());
        assertEquals("Tests", panel.title());
        assertEquals(List.of("failures", "errors"),
                entry.charts().getFirst().series().stream().map(series -> series.key()).toList());
        assertEquals(List.of("run-all", "rerun-failed"), entry.actions().stream().map(PanelAction::id).toList());
        assertEquals(List.of("Run all tests", "Rerun failed tests"),
                entry.actions().stream().map(PanelAction::label).toList());
        assertTrue(entry.actions().stream().allMatch(action -> action.confirmation() == null
                && action.arguments().isEmpty()), "harmless, and no argument: the console never names a test");
    }

    @Test
    void beforeAnyRunItSaysSo(@TempDir Path dir) {
        TestsPanel panel = panel(dir.resolve("vidocq-dev-tests.json"));
        panel.refresh();

        assertEquals("no run yet", value(sampled(panel), "state").get("value"));
    }

    @Test
    void aResultShowsItsCountsAndFailures(@TempDir Path dir) throws Exception {
        Path results = dir.resolve("vidocq-dev-tests.json");
        write(results, FAILED);
        TestsPanel panel = panel(results);
        panel.refresh();

        Map<String, Object> sample = sampled(panel);
        assertEquals("failed (change)", value(sample, "state").get("value"));
        assertEquals(42, number(sample, "run"));
        assertEquals(1, number(sample, "failures"));
        assertEquals(0, number(sample, "errors"));
        assertEquals(2, number(sample, "skipped"));
        assertEquals(List.of(List.of("com.acme.OrderServiceTest#rejectsEmptyCart",
                        "org.opentest4j.AssertionFailedError", "expected: <400> but was: <200>")),
                value(sample, "failed-tests").get("rows"));
    }

    @Test
    void aNewerFileReplacesTheSnapshot(@TempDir Path dir) throws Exception {
        Path results = dir.resolve("vidocq-dev-tests.json");
        write(results, FAILED);
        TestsPanel panel = panel(results);
        panel.refresh();
        write(results, PASSED);
        panel.refresh();

        Map<String, Object> sample = sampled(panel);
        assertEquals("passed (test-change)", value(sample, "state").get("value"));
        assertEquals(0, number(sample, "failures"));
    }

    @Test
    void aRunningRunShowsThePreviousCounts(@TempDir Path dir) throws Exception {
        Path results = dir.resolve("vidocq-dev-tests.json");
        write(results, RUNNING);
        TestsPanel panel = panel(results);
        panel.refresh();

        Map<String, Object> sample = sampled(panel);
        assertEquals("running (rerun-failed)", value(sample, "state").get("value"));
        assertEquals(42, number(sample, "run"));
        assertEquals(1, number(sample, "failures"));
    }

    @Test
    void anUnreadableFileKeepsThePreviousSnapshot(@TempDir Path dir) throws Exception {
        Path results = dir.resolve("vidocq-dev-tests.json");
        write(results, FAILED);
        TestsPanel panel = panel(results);
        panel.refresh();
        write(results, "{\"state\":");
        panel.refresh();

        Map<String, Object> sample = sampled(panel);
        assertEquals(TestsPanel.UNREADABLE, value(sample, "state").get("value"));
        assertEquals(42, number(sample, "run"));
    }

    @Test
    void runAllWritesTheRequest(@TempDir Path dir) throws Exception {
        TestsPanel panel = panel(dir.resolve("vidocq-dev-tests.json"));

        assertEquals("queued", panel.actions().getFirst().run().apply(Map.of()));

        assertEquals("run-all", Files.readString(dir.resolve("vidocq-dev-tests.request")).strip());
    }

    @Test
    void rerunFailedWithoutFailureWritesNothing(@TempDir Path dir) throws Exception {
        Path results = dir.resolve("vidocq-dev-tests.json");
        write(results, PASSED);
        TestsPanel panel = panel(results);
        panel.refresh();

        assertEquals("no failed test to rerun", panel.actions().get(1).run().apply(Map.of()));
        assertFalse(Files.exists(dir.resolve("vidocq-dev-tests.request")));
    }

    @Test
    void rerunFailedWithFailuresWritesTheRequest(@TempDir Path dir) throws Exception {
        Path results = dir.resolve("vidocq-dev-tests.json");
        write(results, FAILED);
        TestsPanel panel = panel(results);
        panel.refresh();

        assertEquals("queued", panel.actions().get(1).run().apply(Map.of()));
        assertEquals("rerun-failed", Files.readString(dir.resolve("vidocq-dev-tests.request")).strip());
    }

    @Test
    void theReaderThreadStartsAndStops(@TempDir Path dir) {
        TestsPanel panel = TestsPanel.start(dir.resolve("vidocq-dev-tests.json").toString()).orElseThrow();
        panels.add(panel);
        Thread reader = panel.reader();

        assertEquals("vidocq-devconsole-tests", reader.getName());
        assertTrue(reader.isDaemon());
        panel.stop();
        assertFalse(reader.isAlive());
        assertNull(panel.reader());
    }
}
```

Add to `CONSOLE_TEST/DevConsoleExtensionTest.java` (imports: `org.junit.jupiter.api.io.TempDir`,
`java.nio.file.Path` if absent):

```java
    @Test
    void aDevBootWithTestResultsShowsTheTestsPanelBeforeTheJvm(@TempDir Path dir) throws Exception {
        System.setProperty(TestsPanel.PROPERTY, dir.resolve("vidocq-dev-tests.json").toString());
        try {
            Boot boot = boot(context(LaunchMode.DEV, DevConsoleSettings.PORT_KEY, "0"));

            Map<String, Object> first = Json.object(get(url(boot.console().boundPort()) + "api/snapshot").body());

            assertEquals(List.of("config", "cdi", "logs", "tests", "jvm"), panelIds(first));
        } finally {
            System.clearProperty(TestsPanel.PROPERTY);
        }
    }
```

In `StartupContributorsTest.theDevConsolePanelsOfTheCoreAreReservedToo`, add `tests`:

```java
        Contributing tests = new Contributing("tests") {};
```

and change the call and the assertions:

```java
        List<Contributor> found = StartupContributors.discover(List.of(startup, config, cdi, logs, tests, jvm),
                loader(), recorder);

        assertEquals(List.of(), ids(found),
                "the dev console shows its own 'startup', 'config', 'cdi', 'logs', 'tests' and 'jvm' panels");
        assertEquals(List.of(RPT_002, RPT_002, RPT_002, RPT_002, RPT_002, RPT_002), codes());
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -o -q -pl vidocq-runtime-core test -Dtest=StartupContributorsTest -Dsurefire.failIfNoSpecifiedTests=false`
(expected: FAIL, 5 codes instead of 6) and
`mvn -o -q -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest='TestsPanelTest,DevConsoleExtensionTest' -Dsurefire.failIfNoSpecifiedTests=false`
(expected: compilation FAILURE, `TestsPanel` not found).

- [ ] **Step 3: Write the implementation**

`StartupContributors.java`: in `CORE_IDS`, add `"tests"` after `"logs"`, and in its Javadoc write
"`{@code startup}, {@code config}, {@code cdi}, {@code logs}, {@code tests} and {@code jvm}`".

`CONSOLE/TestRequestFile.java`:

```java
package io.vidocq.runtime.extensions.essentials.devconsole;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The console's side of {@code target/vidocq-dev-tests.request} (#122, spec §3.3): an action writes one word,
 * {@code run-all} or {@code rerun-failed}, atomically; the Maven plugin reads it, deletes it, and runs the tests. The
 * plugin has its own reader: the contract is this file name and these two words.
 */
final class TestRequestFile {

    /** The request file's name, next to the results file. */
    static final String FILE_NAME = "vidocq-dev-tests.request";

    private TestRequestFile() {}

    static void write(Path file, String word) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, ".vidocq-dev-tests", ".tmp");
        try {
            Files.writeString(tmp, word + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
```

`CONSOLE/TestsPanel.java`:

```java
package io.vidocq.runtime.extensions.essentials.devconsole;

import io.vidocq.runtime.spi.devconsole.Chart;
import io.vidocq.runtime.spi.devconsole.DevConsolePanel;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.devconsole.Series;
import io.vidocq.runtime.spi.devconsole.Unit;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportSection;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The console's own {@code tests} panel (#122, spec §3), in a dev launch run by {@code vidocq:dev} with continuous
 * testing: the last test run the Maven plugin wrote to {@code target/vidocq-dev-tests.json}, and two actions.
 *
 * <ul>
 *   <li><b>Reading:</b> the daemon thread {@code vidocq-devconsole-tests} looks at the file's modification time every
 *       {@value #POLL_MILLIS} ms and, when it changed, parses it into an immutable {@link View} held in a volatile
 *       field; {@link #sample} reads that field only, never the disk. The plugin names the file with
 *       {@code -Dvidocq.dev.tests.results}; without it there is no panel.</li>
 *   <li><b>Values:</b> {@code state} as text, such as {@code failed (change)}; {@code run}, {@code failures},
 *       {@code errors} and {@code skipped} as gauges, those of the previous run while one is {@code running}; the
 *       table {@code failed-tests}, {@value #MAX_ROWS} rows at most. The chart <i>Tests</i> plots failures and
 *       errors.</li>
 *   <li><b>Actions:</b> {@code run-all} and {@code rerun-failed}, which write the one-word request file the plugin
 *       polls and answer {@value #QUEUED}. Neither takes an argument: the console never names a test (ADR 0001),
 *       the plugin computes the rerun itself.</li>
 * </ul>
 * A file that cannot be read keeps the previous view, and {@code state} says {@value #UNREADABLE}. Messages come
 * masked from the plugin.
 */
final class TestsPanel implements DevConsolePanel {

    /** The panel's id, reserved by the core for the console. */
    static final String ID = "tests";
    /** The system property that names the results file. */
    static final String PROPERTY = "vidocq.dev.tests.results";
    /** How often the file's modification time is looked at. */
    static final long POLL_MILLIS = 500;
    /** The rows of the failures table: the console's own limit. */
    static final int MAX_ROWS = 100;
    static final String QUEUED = "queued";
    static final String NOTHING_TO_RERUN = "no failed test to rerun";
    static final String UNREADABLE = "results unreadable";

    private static final List<Chart> CHARTS = List.of(
            new Chart("tests", "Tests", List.of(Series.line("failures"), Series.line("errors"))));
    private static final List<String> FAILURE_COLUMNS = List.of("test", "type", "message");
    private static final System.Logger LOG = System.getLogger(DevConsoleExtension.LOGGER_NAME);

    /** The counts shown. */
    record Counts(long run, long failures, long errors, long skipped) {}

    /**
     * What the panel shows of the file: its state and trigger, and the counts and failures of the run it shows —
     * the previous one while a run is {@code running} or after one was {@code cancelled}.
     */
    record View(String state, String trigger, String startedAt, long durationMillis, Counts counts,
            List<List<String>> failures, String log) {}

    private final Path results;
    private final Path request;
    private volatile View view;
    private volatile boolean unreadable;
    private volatile Thread reader;
    /** Guarded by this: the modification time last read, or -1. */
    private long lastModified = -1;

    TestsPanel(Path results) {
        this.results = Objects.requireNonNull(results, "results");
        this.request = results.resolveSibling(TestRequestFile.FILE_NAME);
    }

    /**
     * The panel of this JVM, reading already, when {@code property} — {@link #PROPERTY}'s value — names a file;
     * empty otherwise.
     */
    static Optional<TestsPanel> start(String property) {
        if (property == null || property.isBlank()) {
            return Optional.empty();
        }
        TestsPanel panel = new TestsPanel(Path.of(property.strip()));
        panel.refresh();
        panel.startReading();
        return Optional.of(panel);
    }

    private void startReading() {
        reader = Thread.ofPlatform().name("vidocq-devconsole-tests").daemon(true).start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                refresh();
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (InterruptedException stopping) {
                    return;
                }
            }
        });
    }

    /** Stops the reader thread; idempotent. */
    void stop() {
        Thread running = reader;
        reader = null;
        if (running != null) {
            running.interrupt();
            try {
                running.join(1_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The reader thread, or {@code null} once stopped. */
    Thread reader() {
        return reader;
    }

    /** Reads the file when its modification time changed; the reader thread calls it, and so do the tests. */
    synchronized void refresh() {
        long modified;
        try {
            modified = Files.getLastModifiedTime(results).toMillis();
        } catch (IOException noFileYet) {
            return;
        }
        if (modified == lastModified) {
            return;
        }
        lastModified = modified;
        try {
            view = parse(Files.readString(results, StandardCharsets.UTF_8));
            unreadable = false;
        } catch (IOException | RuntimeException failed) {
            unreadable = true;
            LOG.log(System.Logger.Level.DEBUG, "Dev console panel 'tests' cannot read " + results + ": "
                    + failed.getClass().getName());
        }
    }

    static View parse(String text) {
        if (!(JsonValues.parse(text, 8) instanceof Map<?, ?> document)) {
            throw new IllegalArgumentException("not a JSON object");
        }
        String state = string(document, "state");
        if (state.isEmpty()) {
            throw new IllegalArgumentException("no state");
        }
        Map<?, ?> shown = document;
        if (("running".equals(state) || "cancelled".equals(state))
                && document.get("previous") instanceof Map<?, ?> previous) {
            shown = previous;
        }
        Map<?, ?> counts = shown.get("counts") instanceof Map<?, ?> c ? c : Map.of();
        List<List<String>> rows = new ArrayList<>();
        if (shown.get("failures") instanceof List<?> failures) {
            for (Object each : failures) {
                if (rows.size() >= MAX_ROWS) {
                    break;
                }
                if (each instanceof Map<?, ?> failure) {
                    rows.add(List.of(string(failure, "test"), string(failure, "type"), string(failure, "message")));
                }
            }
        }
        return new View(state, string(document, "trigger"), string(shown, "startedAt"),
                number(shown, "durationMillis"), new Counts(number(counts, "run"), number(counts, "failures"),
                number(counts, "errors"), number(counts, "skipped")), List.copyOf(rows), string(document, "log"));
    }

    private static String string(Map<?, ?> map, String key) {
        return map.get(key) instanceof String value ? value : "";
    }

    private static long number(Map<?, ?> map, String key) {
        return map.get(key) instanceof Number value ? value.longValue() : 0;
    }

    private static String stateText(View view) {
        return view.state() + (view.trigger().isEmpty() ? "" : " (" + view.trigger() + ")");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Tests";
    }

    /** The last run when the console starts, where its results and log are. */
    @Override
    public void contribute(StartupReportContext context, StartupReportSection section) {
        View current = view;
        section.summary(current == null ? "continuous testing, no run yet" : "last run " + stateText(current))
                .row("results", results.toString())
                .row("log", current == null || current.log().isEmpty() ? "target/vidocq-dev-tests.log"
                        : current.log());
        if (current != null) {
            section.row("last run", current.startedAt() + ", " + current.durationMillis() + " ms");
        }
    }

    @Override
    public List<Chart> charts() {
        return CHARTS;
    }

    @Override
    public List<PanelAction> actions() {
        return List.of(
                new PanelAction("run-all", "Run all tests", null, arguments -> request("run-all")),
                new PanelAction("rerun-failed", "Rerun failed tests", null, arguments -> rerunFailed()));
    }

    private String rerunFailed() {
        View current = view;
        if (current == null || current.failures().isEmpty()) {
            return NOTHING_TO_RERUN;
        }
        return request("rerun-failed");
    }

    private String request(String word) {
        try {
            TestRequestFile.write(request, word);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return QUEUED;
    }

    @Override
    public void sample(PanelSample sample) {
        View current = view;
        if (current == null) {
            sample.text("state", unreadable ? UNREADABLE : "no run yet");
            return;
        }
        sample.text("state", unreadable ? UNREADABLE : stateText(current))
                .gauge("run", current.counts().run(), Unit.COUNT)
                .gauge("failures", current.counts().failures(), Unit.COUNT)
                .gauge("errors", current.counts().errors(), Unit.COUNT)
                .gauge("skipped", current.counts().skipped(), Unit.COUNT)
                .table("failed-tests", FAILURE_COLUMNS, current.failures());
    }
}
```

`DevConsoleExtension.java`:
1. Class Javadoc: in the list of the console's own panels, add `{@linkplain TestsPanel tests} (in a dev launch run
   with continuous testing)` after the logs.
2. Field after `logs`: `private volatile TestsPanel tests;`.
3. In `onStart`, after `logs = logged;`:

```java
        // The tests panel shows vidocq:dev's continuous testing: in a dev launch only, when the plugin named the
        // results file; its reader thread stops in onStop.
        TestsPanel tested = resolved.launchMode() == LaunchMode.DEV
                ? TestsPanel.start(System.getProperty(TestsPanel.PROPERTY)).orElse(null)
                : null;
        tests = tested;
```

   and pass it on: `ownPanels(context, resolved.launchMode(), logged, tested)`.
4. `ownPanels` gets a fourth parameter `TestsPanel tests` (Javadoc: `@param tests the tests panel of a dev launch
   with continuous testing, or {@code null}`), and after the `logs` entry:

```java
        if (tests != null) {
            panels.add(PanelEntry.builtIn(tests, mode));
        }
```

5. In `onStop`, after the `logged.stop()` block:

```java
        TestsPanel tested = tests;
        tests = null;
        if (tested != null) {
            tested.stop();
        }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: the two commands of Step 2, then the whole console module:
`mvn -o -q -pl vidocq-runtime-core,vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension install`.
Expected: all green, including the existing `DevConsoleExtensionTest`, which still reads
`config, cdi, logs, jvm` without the property.

- [ ] **Step 5: Commit**

```bash
git add vidocq-runtime-core/src/main/java/io/vidocq/runtime/core/report/StartupContributors.java \
        vidocq-runtime-core/src/test/java/io/vidocq/runtime/core/report/StartupContributorsTest.java \
        vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src
git commit -S -F <msgfile>   # "feat(devconsole): add the tests panel and its run actions (#122)"
```

---

### Task 9: End-to-end tests of both goals

**Files:**
- Create the module `vidocq-runtime-integration-tests/vidocq-runtime-it-continuous-testing/`:
  - `pom.xml`
  - `src/it/it-settings.xml`
  - `src/it/fixture/pom.xml`, `src/it/fixture/.mvn/maven.config`
  - `src/it/fixture/src/main/java/module-info.java`
  - `src/it/fixture/src/main/java/io/vidocq/it/ct/FixtureApp.java`, `…/Greeter.java`
  - `src/it/fixture/src/test/java/io/vidocq/it/ct/GreeterTest.java`, `…/ExpectationTest.java`
  - `src/test/java/io/vidocq/runtime/it/continuoustesting/Fixture.java`
  - `…/ContinuousTestingDevGoalIT.java`, `…/ContinuousTestingTestGoalIT.java`
- Modify: `vidocq-runtime-integration-tests/pom.xml` (the `it` profile)

**Interfaces:**
- Consumes (black box):
  - the goals `vidocq:dev` and `vidocq:test`;
  - `target/vidocq-dev-tests.json`, with the states and triggers;
  - the console's `/api/snapshot`, with the panel `tests`, its values `failures` and `failed-tests`, and
    `console.actionToken`;
  - the action `POST /api/action/tests/rerun-failed`;
  - the terminal headline format.
- Produces: nothing other tasks use.

- [ ] **Step 1: Write the module and the fixture**

`vidocq-runtime-integration-tests/pom.xml`: add, next to the existing `docker` profile:

```xml
        <!-- Continuous testing end to end (#122): real mvn subprocesses running vidocq:dev and vidocq:test on a
             fixture project. On any CI runner (Forgejo sets CI=true) and with -Pit, like the plugin's invoker
             tests: it stages the fresh plugin with maven-invoker-plugin, which a plain offline build may lack. -->
        <profile>
            <id>it</id>
            <activation>
                <property>
                    <name>env.CI</name>
                </property>
            </activation>
            <modules>
                <module>vidocq-runtime-it-continuous-testing</module>
            </modules>
        </profile>
```

`vidocq-runtime-it-continuous-testing/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!--
    Vidocq/vidocq#122: continuous testing end to end. Each IT copies the fixture project of src/it/fixture (one
    passing test class, one failing test) to a temp directory, outside this repository so that no parent mvnw is
    picked up, and runs a real `mvn vidocq:dev` or `mvn vidocq:test` there.

    The fixture resolves this build's artifacts from target/it-repo, which maven-invoker-plugin:install stages at
    pre-integration-test; anything else comes from the outer local repository through it-settings.xml. Its
    .mvn/maven.config points both the ITs' mvn and the plugin's own test runs at them.
-->
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.vidocq.runtime</groupId>
        <artifactId>vidocq-runtime-integration-tests</artifactId>
        <version>0.4.0-SNAPSHOT</version>
    </parent>

    <artifactId>vidocq-runtime-it-continuous-testing</artifactId>
    <name>Vidocq Runtime :: IT :: Continuous testing (vidocq:dev, vidocq:test)</name>

    <properties>
        <it.repo>${project.build.directory}/it-repo</it.repo>
        <it.settings>${project.build.directory}/it-settings.xml</it.settings>
        <localRepositoryUrl>file://${settings.localRepository}</localRepositoryUrl>
        <!-- maven-invoker-plugin reads invoker.skip only; -DskipTests skips the staging too. -->
        <invoker.skip>${skipTests}</invoker.skip>
    </properties>

    <dependencies>
        <!-- What the fixture uses from this build: invoker:install stages them, and every reactor module and
             parent POM they need, in target/it-repo. -->
        <dependency>
            <groupId>io.vidocq.runtime</groupId>
            <artifactId>vidocq-runtime-maven-plugin</artifactId>
            <version>${project.version}</version>
            <type>maven-plugin</type>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.vidocq.runtime</groupId>
            <artifactId>vidocq-runtime-core</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.vidocq.runtime.extensions.essentials</groupId>
            <artifactId>vidocq-runtime-devconsole-extension</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.vidocq.runtime</groupId>
            <artifactId>vidocq-runtime-core-codegen</artifactId>
            <version>${project.version}</version>
            <type>pom</type>
            <scope>test</scope>
        </dependency>

        <!-- The ITs parse the results file and the dev console snapshot, as DevServicesDevGoalIT does. -->
        <dependency>
            <groupId>jakarta.json.bind</groupId>
            <artifactId>jakarta.json.bind-api</artifactId>
            <version>3.0.1</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.eclipse</groupId>
            <artifactId>yasson</artifactId>
            <version>3.0.4</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.eclipse.parsson</groupId>
            <artifactId>parsson</artifactId>
            <version>1.1.7</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-resources-plugin</artifactId>
                <executions>
                    <!-- The fixture project, its pom.xml and .mvn/ filtered with @…@ only. -->
                    <execution>
                        <id>fixture</id>
                        <phase>process-test-resources</phase>
                        <goals>
                            <goal>copy-resources</goal>
                        </goals>
                        <configuration>
                            <outputDirectory>${project.build.directory}/fixture</outputDirectory>
                            <useDefaultDelimiters>false</useDefaultDelimiters>
                            <delimiters>
                                <delimiter>@</delimiter>
                            </delimiters>
                            <resources>
                                <resource>
                                    <directory>src/it/fixture</directory>
                                    <filtering>true</filtering>
                                    <includes>
                                        <include>pom.xml</include>
                                        <include>.mvn/**</include>
                                    </includes>
                                </resource>
                                <resource>
                                    <directory>src/it/fixture</directory>
                                    <filtering>false</filtering>
                                    <excludes>
                                        <exclude>pom.xml</exclude>
                                        <exclude>.mvn/**</exclude>
                                    </excludes>
                                </resource>
                            </resources>
                        </configuration>
                    </execution>
                    <execution>
                        <id>it-settings</id>
                        <phase>process-test-resources</phase>
                        <goals>
                            <goal>copy-resources</goal>
                        </goals>
                        <configuration>
                            <outputDirectory>${project.build.directory}</outputDirectory>
                            <useDefaultDelimiters>false</useDefaultDelimiters>
                            <delimiters>
                                <delimiter>@</delimiter>
                            </delimiters>
                            <resources>
                                <resource>
                                    <directory>src/it</directory>
                                    <filtering>true</filtering>
                                    <includes>
                                        <include>it-settings.xml</include>
                                    </includes>
                                </resource>
                            </resources>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-invoker-plugin</artifactId>
                <version>3.10.1</version>
                <configuration>
                    <localRepositoryPath>${it.repo}</localRepositoryPath>
                    <!-- The fixture's artifacts are test-scope dependencies here. -->
                    <scope>test</scope>
                </configuration>
                <executions>
                    <execution>
                        <id>stage</id>
                        <phase>pre-integration-test</phase>
                        <goals>
                            <goal>install</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-failsafe-plugin</artifactId>
                <version>3.5.4</version>
                <configuration>
                    <systemPropertyVariables>
                        <!-- Origin is a restricted header of HttpURLConnection; an action needs it (ADR 0001). -->
                        <sun.net.http.allowRestrictedHeaders>true</sun.net.http.allowRestrictedHeaders>
                        <it.fixture>${project.build.directory}/fixture</it.fixture>
                        <it.logs>${project.build.directory}/it-logs</it.logs>
                    </systemPropertyVariables>
                </configuration>
                <executions>
                    <execution>
                        <goals>
                            <goal>integration-test</goal>
                            <goal>verify</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

`src/it/it-settings.xml` (the same as `vidocq-runtime-maven-plugin/src/it/settings.xml`):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<settings>
    <profiles>
        <profile>
            <id>it-repo</id>
            <repositories>
                <repository>
                    <id>local.central</id>
                    <url>@localRepositoryUrl@</url>
                    <releases><enabled>true</enabled></releases>
                    <snapshots><enabled>true</enabled></snapshots>
                </repository>
            </repositories>
            <pluginRepositories>
                <pluginRepository>
                    <id>local.central</id>
                    <url>@localRepositoryUrl@</url>
                    <releases><enabled>true</enabled></releases>
                    <snapshots><enabled>true</enabled></snapshots>
                </pluginRepository>
            </pluginRepositories>
        </profile>
    </profiles>
    <activeProfiles>
        <activeProfile>it-repo</activeProfile>
    </activeProfiles>
</settings>
```

`src/it/fixture/.mvn/maven.config` (Maven 3.9 reads one argument per line):

```
-Dmaven.repo.local=@it.repo@
--settings
@it.settings@
```

`src/it/fixture/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <!-- The runtime's own parent: its dependencyManagement and its Vauban indexer APT, as an example has. -->
    <parent>
        <groupId>io.vidocq.runtime</groupId>
        <artifactId>vidocq-runtime-parent</artifactId>
        <version>@project.version@</version>
        <relativePath/>
    </parent>

    <groupId>io.vidocq.it</groupId>
    <artifactId>continuous-testing-fixture</artifactId>
    <version>1.0.0-SNAPSHOT</version>

    <properties>
        <vidocq.mainModule>io.vidocq.it.ct</vidocq.mainModule>
        <vidocq.mainClass>io.vidocq.it.ct.FixtureApp</vidocq.mainClass>
    </properties>

    <dependencies>
        <dependency>
            <groupId>io.vidocq.runtime</groupId>
            <artifactId>vidocq-runtime-core</artifactId>
        </dependency>
        <dependency>
            <groupId>io.vidocq.runtime.extensions.essentials</groupId>
            <artifactId>vidocq-runtime-devconsole-extension</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>io.vidocq.runtime</groupId>
                <artifactId>vidocq-runtime-maven-plugin</artifactId>
                <version>@project.version@</version>
            </plugin>
        </plugins>
    </build>
</project>
```

`src/it/fixture/src/main/java/module-info.java`:

```java
module io.vidocq.it.ct {
    requires io.vidocq.runtime.core;

    exports io.vidocq.it.ct;
}
```

`src/it/fixture/src/main/java/io/vidocq/it/ct/FixtureApp.java`:

```java
package io.vidocq.it.ct;

import io.vidocq.runtime.core.Vidocq;
import io.vidocq.runtime.spi.VidocqMain;

/** The application vidocq:dev launches: a boot for the dev console to run in. */
@VidocqMain
public final class FixtureApp {

    private FixtureApp() {}

    public static void main(String[] args) {
        Vidocq.run(args);
    }
}
```

`src/it/fixture/src/main/java/io/vidocq/it/ct/Greeter.java`:

```java
package io.vidocq.it.ct;

/** What the fixture's tests test. */
public final class Greeter {

    public String greet(String name) {
        return "Hello, " + name;
    }
}
```

`src/it/fixture/src/test/java/io/vidocq/it/ct/GreeterTest.java`:

```java
package io.vidocq.it.ct;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GreeterTest {

    @Test
    void greetsByName() {
        assertEquals("Hello, Ada", new Greeter().greet("Ada"));
    }

    /**
     * vidocq:dev and vidocq:test hand every run -Dvidocq.dev.devServices=false (spec §2.5): Surefire must pass it
     * to this JVM, or the JUnit dev services host would start containers of its own.
     */
    @Test
    void runsWithoutDevServicesOfItsOwn() {
        assertEquals("false", System.getProperty("vidocq.dev.devServices"));
    }
}
```

`src/it/fixture/src/test/java/io/vidocq/it/ct/ExpectationTest.java`:

```java
package io.vidocq.it.ct;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Fails until the IT rewrites its expectation, which must turn the run green without an application reload. */
class ExpectationTest {

    private static final String EXPECTED = "Goodbye, Ada";

    @Test
    void matchesTheGreeting() {
        assertEquals(EXPECTED, new Greeter().greet("Ada"));
    }
}
```

- [ ] **Step 2: Write the ITs**

`src/test/java/io/vidocq/runtime/it/continuoustesting/Fixture.java`:

```java
package io.vidocq.runtime.it.continuoustesting;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/** The fixture project and the subprocesses of the continuous-testing ITs. */
final class Fixture {

    /** Never 8080/8888; 18093-18099, as the other dev-mode ITs. */
    static final int FIRST_PORT = 18093;
    static final int LAST_PORT = 18099;
    static final Duration TIMEOUT = Duration.ofMinutes(3);
    static final String EXPECTATION = "src/test/java/io/vidocq/it/ct/ExpectationTest.java";

    private Fixture() {}

    /** A copy of the fixture project under {@code dir}, outside this repository and its mvnw. */
    static Path copy(Path dir) throws IOException {
        Path template = Path.of(System.getProperty("it.fixture"));
        Path project = dir.resolve("fixture");
        try (Stream<Path> files = Files.walk(template)) {
            for (Path source : files.toList()) {
                Path target = project.resolve(template.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return project;
    }

    /** Where an IT writes the logs of its subprocesses, kept after the build for a post-mortem. */
    static Path logs() throws IOException {
        return Files.createDirectories(Path.of(System.getProperty("it.logs")));
    }

    /**
     * One online build that fills target/it-repo with what the plugin's offline runs need later (Surefire's JUnit
     * provider, the compiler); the fixture's own failures are ignored.
     */
    static void warmUp(Path project, Path log) throws Exception {
        Process process = start(project, log, "test-compile", "surefire:test", "-Dmaven.test.failure.ignore=true");
        if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            stop(process);
            fail("the warm-up build did not end within " + TIMEOUT + ":\n" + read(log));
        }
        assertEquals(0, process.exitValue(), () -> "the warm-up build failed:\n" + read(log));
    }

    static Process start(Path project, Path log, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of("mvn", "-ntp", "-B"));
        command.addAll(List.of(args));
        return new ProcessBuilder(command)
                .directory(project.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
    }

    static String read(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "(no file)";
        } catch (IOException e) {
            return "(unreadable: " + e.getMessage() + ")";
        }
    }

    /** Polls {@code log} for {@code text}; fails fast if the subprocess dies first. */
    static void awaitLog(Process process, Path log, String text) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (read(log).contains(text)) {
                return;
            }
            if (!process.isAlive()) {
                fail("mvn exited with " + process.exitValue() + " before '" + text + "':\n" + read(log));
            }
            Thread.sleep(250);
        }
        fail("no '" + text + "' within " + TIMEOUT + ":\n" + read(log));
    }

    /** Polls the results file until {@code done} accepts it. */
    static Map<String, Object> awaitResults(Path project, Predicate<Map<String, Object>> done) throws Exception {
        Path file = project.resolve("target/vidocq-dev-tests.json");
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
        Map<String, Object> last = null;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(file)) {
                last = json(read(file));
                if (done.test(last)) {
                    return last;
                }
            }
            Thread.sleep(250);
        }
        return fail("no matching results within " + TIMEOUT + "; the last: " + last);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> json(String text) {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            return jsonb.fromJson(text, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + text, e);
        }
    }

    static boolean is(Map<String, Object> results, String state, String trigger) {
        return state.equals(results.get("state")) && trigger.equals(results.get("trigger"));
    }

    static int count(Map<String, Object> results, String key) {
        return ((Number) ((Map<?, ?>) results.get("counts")).get(key)).intValue();
    }

    /** Rewrites the failing expectation so that the test passes. */
    static void fixExpectation(Path project) throws IOException {
        Path test = project.resolve(EXPECTATION);
        Files.writeString(test, Files.readString(test).replace("\"Goodbye, Ada\"", "\"Hello, Ada\""));
    }

    static int occurrences(Path log, String text) {
        String all = read(log);
        int count = 0;
        for (int i = all.indexOf(text); i >= 0; i = all.indexOf(text, i + text.length())) {
            count++;
        }
        return count;
    }

    /** The panel {@code id} of a snapshot. */
    static Map<?, ?> panel(Map<String, Object> snapshot, String id) {
        for (Object each : (List<?>) snapshot.get("panels")) {
            if (id.equals(((Map<?, ?>) each).get("id"))) {
                return (Map<?, ?>) each;
            }
        }
        return fail("no panel " + id + " in " + snapshot);
    }

    /** The value {@code key} of a panel's sample, or {@code null}. */
    static Map<?, ?> value(Map<?, ?> panel, String key) {
        Map<?, ?> sample = (Map<?, ?>) panel.get("sample");
        if (sample == null) {
            return null;
        }
        for (Object each : (List<?>) sample.get("values")) {
            if (key.equals(((Map<?, ?>) each).get("key"))) {
                return (Map<?, ?>) each;
            }
        }
        return null;
    }

    static String get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(10_000);
        try {
            assertEquals(200, connection.getResponseCode(), url);
            try (InputStream in = connection.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    /** An action, sent as the console's own page sends it (ADR 0001); its answer must be 200. */
    static String postAction(String console, String action, String token, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(console + "/api/action/" + action).toURL()
                .openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(30_000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Origin", console);
        connection.setRequestProperty("X-Vidocq-Console-Token", token);
        try {
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(200, connection.getResponseCode(), action);
            try (InputStream in = connection.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * SIGTERM to {@code mvn}, as Ctrl+C does: its shutdown hook stops the test run and the child. The descendants
     * are looked up first, so that a forced kill of {@code mvn} can never leave one running.
     */
    static void stop(Process process) throws Exception {
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroy();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
        for (ProcessHandle descendant : descendants) {
            if (descendant.isAlive()) {
                descendant.destroyForcibly();
            }
        }
    }

    /** The first free port of {@value #FIRST_PORT}-{@value #LAST_PORT} not in {@code taken}. */
    static int freePort(Set<Integer> taken) throws IOException {
        for (int port = FIRST_PORT; port <= LAST_PORT; port++) {
            if (taken.contains(port)) {
                continue;
            }
            try (ServerSocket probe = new ServerSocket()) {
                probe.setReuseAddress(false);
                probe.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                return port;
            } catch (IOException inUse) {
                // try the next one
            }
        }
        throw new IOException("no free port in " + FIRST_PORT + "-" + LAST_PORT);
    }
}
```

`src/test/java/io/vidocq/runtime/it/continuoustesting/ContinuousTestingDevGoalIT.java`:

```java
package io.vidocq.runtime.it.continuoustesting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A real {@code mvn vidocq:dev} on the fixture (spec §7): the first run fails one test, and the dev console's
 * {@code tests} panel shows it; {@code rerun-failed} runs that test alone; fixing the test turns the run green
 * without an application reload.
 */
@DisabledOnOs(OS.WINDOWS)
class ContinuousTestingDevGoalIT {

    private static final String STARTED = "Vidocq - Started in";
    private static final String FAILING = "io.vidocq.it.ct.ExpectationTest#matchesTheGreeting";

    @Test
    void theTestsPanelFollowsTheRunsAndATestChangeNeverReloadsTheApplication(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        Path logs = Fixture.logs();
        Fixture.warmUp(project, logs.resolve("dev-warm-up.log"));
        int appPort = Fixture.freePort(Set.of());
        int consolePort = Fixture.freePort(Set.of(appPort));
        Path log = logs.resolve("vidocq-dev.log");

        Process mvn = Fixture.start(project, log, "vidocq:dev", "-Dvidocq.dev.debug=false",
                "-Dvidocq.chappe.listener.default.port=" + appPort, "-Dvidocq.devconsole.port=" + consolePort);
        try {
            Fixture.awaitLog(mvn, log, STARTED);
            Map<String, Object> first = Fixture.awaitResults(project, r -> Fixture.is(r, "failed", "run-all"));
            assertEquals(3, Fixture.count(first, "run"));
            assertEquals(1, Fixture.count(first, "failures"));

            String console = "http://127.0.0.1:" + consolePort;
            Map<String, Object> snapshot = awaitPanelFailures(console, 1);
            Map<?, ?> panel = Fixture.panel(snapshot, "tests");
            List<?> rows = (List<?>) Fixture.value(panel, "failed-tests").get("rows");
            assertEquals(FAILING, ((List<?>) rows.getFirst()).getFirst(), panel.toString());

            String token = (String) ((Map<?, ?>) snapshot.get("console")).get("actionToken");
            String answer = Fixture.postAction(console, "tests/rerun-failed", token, "{}");
            assertTrue(answer.contains("queued"), answer);
            Map<String, Object> rerun = Fixture.awaitResults(project,
                    r -> Fixture.is(r, "failed", "rerun-failed"));
            assertEquals(1, Fixture.count(rerun, "run"), "only the failed test ran");

            Fixture.fixExpectation(project);
            Map<String, Object> green = Fixture.awaitResults(project, r -> Fixture.is(r, "passed", "test-change"));
            assertEquals(3, Fixture.count(green, "run"));
            assertEquals(1, Fixture.occurrences(log, STARTED), "a test change never reloads the application");
        } finally {
            Fixture.stop(mvn);
        }
    }

    /** The snapshot, once the panel's reader thread has seen {@code failures} failures. */
    private static Map<String, Object> awaitPanelFailures(String console, int failures) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        Map<String, Object> snapshot = null;
        while (System.currentTimeMillis() < deadline) {
            snapshot = Fixture.json(Fixture.get(console + "/api/snapshot"));
            Map<?, ?> value = Fixture.value(Fixture.panel(snapshot, "tests"), "failures");
            if (value != null && ((Number) value.get("value")).intValue() == failures) {
                return snapshot;
            }
            Thread.sleep(250);
        }
        return fail("the tests panel never showed " + failures + " failure(s): " + snapshot);
    }
}
```

`src/test/java/io/vidocq/runtime/it/continuoustesting/ContinuousTestingTestGoalIT.java`:

```java
package io.vidocq.runtime.it.continuoustesting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real {@code mvn vidocq:test} on the fixture (spec §7): the summary of the first run, no key hints since
 * standard input is a pipe, and a fixed test gives {@code passed}.
 */
@DisabledOnOs(OS.WINDOWS)
class ContinuousTestingTestGoalIT {

    @Test
    void eachRunIsSummarisedAndAFixedTestTurnsItGreen(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        Path logs = Fixture.logs();
        Fixture.warmUp(project, logs.resolve("test-warm-up.log"));
        Path log = logs.resolve("vidocq-test.log");

        Process mvn = Fixture.start(project, log, "vidocq:test");
        try {
            Fixture.awaitLog(mvn, log, "Tests: 2 passed, 1 failed, 0 skipped");
            String out = Fixture.read(log);
            assertTrue(out.contains("FAILED io.vidocq.it.ct.ExpectationTest#matchesTheGreeting"), out);
            assertTrue(out.contains("AssertionFailedError: expected: <Goodbye, Ada> but was: <Hello, Ada>"), out);
            assertFalse(out.contains("[r] run all"), "a pipe is no console: no keys");

            Fixture.fixExpectation(project);
            Fixture.awaitResults(project, r -> Fixture.is(r, "passed", "test-change"));
            Fixture.awaitLog(mvn, log, "Tests: 3 passed, 0 failed, 0 skipped");
        } finally {
            Fixture.stop(mvn);
        }
    }
}
```

- [ ] **Step 3: Run the ITs**

Run, so that the fixture gets this branch's artifacts: first `mvn -o install -DskipTests` at the root, then
`mvn -o -Pit -pl vidocq-runtime-integration-tests/vidocq-runtime-it-continuous-testing verify`.
Expected: `ContinuousTestingDevGoalIT` and `ContinuousTestingTestGoalIT` pass: `Tests run: 2, Failures: 0`.
`-o` only works when `maven-invoker-plugin` 3.10.1 and `maven-failsafe-plugin` 3.5.4 are already in the local
repository; drop `-o` otherwise.

If the fixture fails to build, read `target/it-logs/*-warm-up.log`. Possible causes:
- the `maven.config` arguments;
- the parent resolution in `target/it-repo`;
- a check of `vidocq-runtime-parent` that does not suit the fixture.

Fix the fixture, never the plugin, for such causes. Record each fix as a ruling in the ledger.

- [ ] **Step 4: Check that every process is gone**

Run: `pgrep -fl 'vidocq:dev|vidocq:test|continuous-testing-fixture' || echo none`
Expected: `none`.

- [ ] **Step 5: Commit**

```bash
git add vidocq-runtime-integration-tests/pom.xml vidocq-runtime-integration-tests/vidocq-runtime-it-continuous-testing
git commit -S -F <msgfile>   # "test(it): continuous testing end to end under vidocq:dev and vidocq:test (#122)"
```

---

### Task 10: Documentation

**Files:**
- Create: `docs/en/modules/ROOT/pages/continuous-testing.adoc`
- Modify:
  - `docs/en/modules/ROOT/nav.adoc`;
  - `docs/en/modules/ROOT/pages/modules/vidocq-runtime-maven-plugin.adoc`;
  - `docs/en/modules/ROOT/pages/dev-console.adoc`;
  - `docs/en/modules/ROOT/pages/dev-console-panels.adoc`;
  - `docs/en/modules/ROOT/pages/reference.adoc`;
  - `docs/en/modules/ROOT/pages/modules/vidocq-runtime-spi.adoc`;
  - `docs/en/modules/ROOT/pages/modules/vidocq-runtime-core.adoc`;
  - `docs/en/modules/ROOT/pages/dev-services.adoc`;
  - `DEV_SERVICES.md`;
  - `docs/en/modules/ROOT/pages/whats-new.adoc`;
  - `CLAUDE.md` (the goal list).

**Interfaces:**
- Consumes: the behaviour of Tasks 1-9, as built. Where the code and this text disagree, the code wins: fix the
  text.

- [ ] **Step 1: Write the new page**

`docs/en/modules/ROOT/pages/continuous-testing.adoc`:

```asciidoc
= Continuous testing
:description: Run the application's tests on every change, beside vidocq:dev or on their own with vidocq:test.

[.lead]
[.tag-new]#NEW# Vidocq runs the application's tests while you code: `vidocq:dev` runs them in the background after
every reload and shows the result in the xref:dev-console.adoc#tests-panel[dev console], and `vidocq:test` runs them
on their own, without the application, with a summary in the terminal.

[#what-it-does]
== What it does [.tag-new]#NEW#

Every test of the application runs after every change: there is no tracking of the tests a change affects. A run is
Surefire itself, started as `mvn test-compile surefire:test`, so the tests run exactly as `mvn test` runs them: the
same module path, the same `argLine`, the same JUnit launcher listeners, the
xref:dev-services.adoc#in-tests[dev services host] included. Integration tests run by Failsafe are not run.

[#two-goals]
== `vidocq:dev` or `vidocq:test` [.tag-new]#NEW#

[cols="1,2,2"]
|===
| | `vidocq:dev` | `vidocq:test`

| The application
| Runs, and reloads on a main change
| Does not run

| When tests run
| After the first boot, then after every reload, or alone after a test-only change
| At start, then after every change

| Where the result shows
| The xref:dev-console.adoc#tests-panel[`tests` panel], and one line in the terminal
| Three lines in the terminal

| Keys
| None
| `r`, `f`, `q`, Enter, when a console is attached

| Dev services
| The session of `vidocq:dev`
| A session of its own, host `vidocq:test`
|===

[source,bash]
----
mvn vidocq:dev                                        # the application, and its tests behind it
mvn vidocq:dev -Dvidocq.dev.continuousTesting=false   # the application only
mvn vidocq:test                                       # the tests only
----

[#when-tests-run]
== When tests run [.tag-new]#NEW#

Both goals watch the main directories, `src/main/java` and `src/main/resources`, and the test directories,
`src/test/java` and `src/test/resources`, with the same 250 ms debounce.

[cols="1,2,2"]
|===
| Change in | `vidocq:dev` | `vidocq:test`

| The main directories
| Recompile, reload the application, *then* run the tests
| Run the tests: their `test-compile` recompiles the main sources

| The test directories only
| Run the tests; the application is *not* reloaded
| Run the tests
|===

In `vidocq:dev`, a run never overlaps a reload: after a hot reload, the tests wait until the new application layer
has booted — two minutes at most, then they run anyway, with a warning. A recompile that fails runs no test: its
errors are already on screen.

One run at a time:

* A new change *cancels* the run in flight — its Maven and the test JVM it forked are stopped — and the next run
  starts after the recompile or the reload.
* A request, a button of the dev console or a key of `vidocq:test`, *never cancels*: it waits for the run in flight,
  and when several arrive meanwhile, the last one wins.

[#switch]
== Turning it on and off [.tag-new]#NEW#

`vidocq.dev.continuousTesting` turns the tests of `vidocq:dev` on or off. It is `true` when the project has a
`src/test/java` directory and `false` otherwise. The first source that sets it wins:

. the explicit value: `-Dvidocq.dev.continuousTesting=false`, or the goal's `<configuration>`;
. the application's own files, `vidocq.properties` or `application.properties`, the way the
  xref:dev-services.adoc#configuration-sources[`vidocq.dev.*` keys of dev services] are read;
. the default.

The value is `true` or `false`, in any case; anything else stops the goal with a message that names the key.
`vidocq:test` always runs the tests: the switch does not apply to it.

[#watch-directories]
== The watched directories [.tag-new]#NEW#

[cols="2,2,3"]
|===
| Property | Default | Meaning

| `vidocq.dev.watchDirs`
| `src/main/java,src/main/resources`
| The main directories. A change recompiles, reloads the application in `vidocq:dev`, and runs the tests.

| `vidocq.dev.testWatchDirs`
| `src/test/java,src/test/resources`
| The test directories. A change runs the tests, without reloading the application.

| `vidocq.dev.debounceMillis`
| `250`
| How long a burst of saves is collected into one change.
|===

Only `.java`, `.properties`, `.xml`, `.yml` and `.yaml` files count.

[#results]
== Results and log [.tag-new]#NEW#

Each run writes its output to `target/vidocq-dev-tests.log`, overwritten on the next run, and its result to
`target/vidocq-dev-tests.json`, replaced atomically:

[source,json]
----
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
----

[cols="1,3"]
|===
| `state` | Meaning

| `running` | A run has started; the previous complete result is under `previous`.
| `passed` | Every test passed, or was skipped.
| `failed` | A test failed or ended in error — or Maven exited with an error although none did, such as a test JVM that crashed; the log says why.
| `compile-error` | Maven failed before any test ran: the main or test sources do not compile. The application keeps running.
| `no-tests` | There was no test to run.
| `cancelled` | A newer change stopped the run; the previous complete result is under `previous`.
|===

`trigger` says what started the run: `change` (a main change), `test-change`, `run-all` (the first run, a button or
a key) or `rerun-failed`. A message is the first line of the exception's message, without the credentials of a URL,
200 characters at most; the whole stack trace is in the log.

[#terminal]
== The terminal [.tag-new]#NEW#

`vidocq:dev` prints one line per run, among the application's own output:

----
[INFO] Tests: 41 passed, 1 failed, 0 skipped in 3.2 s (change)
----

`vidocq:test` prints the failures and the log too, ten failures at most:

----
Tests: 41 passed, 1 failed, 0 skipped in 3.2 s (change)
  FAILED com.acme.OrderServiceTest#rejectsEmptyCart — AssertionFailedError: expected: <400> but was: <200>
Log: target/vidocq-dev-tests.log   [r] run all  [f] rerun failed  [q] quit
----

A compilation failure, a run with no test and a cancelled run each print one line, such as
`Tests: compilation failed (change), see target/vidocq-dev-tests.log`.

With a console attached, `vidocq:test` reads a key per line — type the key, then Enter:

[cols="1,3"]
|===
| Key | Effect

| `r` | Run every test.
| `f` | Run the tests that failed in the last result; `no failed test to rerun` when there is none.
| `q` | Quit: the run in flight is cancelled and the dev services stop.
| Enter | Run every test.
|===

Without a console — a CI job, a pipe, an IDE's run window that is no terminal — no key is read, the hints are not
printed, and the goal only watches. Ctrl+C always stops it.

[#dev-console]
== In the dev console [.tag-new]#NEW#

Under `vidocq:dev`, the console's own xref:dev-console.adoc#tests-panel[`tests` panel] shows the last run, its
failures and a chart of the failures over time, with two buttons: *Run all tests* and *Rerun failed tests*.

[#dev-services]
== Dev services [.tag-new]#NEW#

The tests use the same containers as the application: `vidocq:dev` hands every run its session's connection keys,
and `vidocq:test` opens a session of its own, host `vidocq:test`, once for its whole life. Every run gets
`-Dvidocq.dev.devServices=false`, so the tests never start containers of their own.

WARNING: The tests share the database with the running application (`vidocq:dev`) or with the other runs
(`vidocq:test`). A test that empties a table empties it in your dev database. Keep such tests on data they create
themselves, or turn continuous testing off for that project.

[#limits]
== Limits [.tag-new]#NEW#

* Every run starts Maven: a few seconds before the first test, more on a large suite. Turn it off for the dev loop
  with `-Dvidocq.dev.continuousTesting=false` when that is too slow.
* Every test runs, every time: a change is not mapped to the tests it affects.
* Surefire's tests only: Failsafe's integration tests are not run.
* A run is offline (`mvn -o`): the dependencies the tests need must already be in the local repository, as they are
  after one `mvn test`.
* The keys need a real console: an IDE's run window may not deliver them.
* The dev session's keys, a password included, are on the command line of the test run: other users of the machine
  can see it in the process list, as they can the application's.

[#turning-it-off]
== Turning it off [.tag-new]#NEW#

For one run, `mvn vidocq:dev -Dvidocq.dev.continuousTesting=false`. For the project, in `vidocq.properties`:

[source,properties]
----
vidocq.dev.continuousTesting=false
----

A project without `src/test/java` has it off already.
```

`nav.adoc`: after `*** xref:dev-console-panels.adoc[Writing a dev console panel]` add
`*** xref:continuous-testing.adoc[Continuous testing]`.

- [ ] **Step 2: Update the other pages**

1. `modules/vidocq-runtime-maven-plugin.adoc`:
   - In the Goals table, after the `vidocq:run` row:

     ```asciidoc
     | `vidocq:test` [.tag-new]#NEW#
     | Continuous testing without the application: runs the tests, then again on every change of the main or test sources, with a summary and keys in the terminal. See <<test,the `vidocq:test` goal>>.
     ```

   - In the `vidocq:dev` row, append: ` With continuous testing, it also runs the tests after every reload; see
     xref:continuous-testing.adoc[Continuous testing].`
   - Before the `[#debugger]` anchor, add:

     ````asciidoc
     [#test]
     == `vidocq:test` goal [.tag-new]#NEW#

     `vidocq:test` runs the application's tests on every change, without starting the application: no child JVM,
     no dev console, no debug agent. It opens the dev services session, runs every test, then watches the main and
     test directories and runs them again on each change. A run is `mvn test-compile surefire:test`, so a main
     change is recompiled by the run itself. See xref:continuous-testing.adoc[Continuous testing] for the whole
     behaviour, the results file and the dev services it shares.

     [source,bash]
     ----
     mvn vidocq:test
     ----

     ----
     [INFO] Vidocq test — watching [src/main/java, src/main/resources] and [src/test/java, src/test/resources]
     [INFO] Tests: 41 passed, 1 failed, 0 skipped in 3.2 s (run-all)
     [INFO]   FAILED com.acme.OrderServiceTest#rejectsEmptyCart — AssertionFailedError: expected: <400> but was: <200>
     [INFO] Log: target/vidocq-dev-tests.log   [r] run all  [f] rerun failed  [q] quit
     ----

     With a console, `r` and Enter run every test, `f` the failed ones, `q` quits; Ctrl+C always stops the goal,
     its run and its dev services.

     [cols="2,1,3"]
     |===
     | Property | Default | Meaning

     | `vidocq.dev.watchDirs`
     | `src/main/java,src/main/resources`
     | The main directories to watch.

     | `vidocq.dev.testWatchDirs`
     | `src/test/java,src/test/resources`
     | The test directories to watch.

     | `vidocq.dev.debounceMillis`
     | `250`
     | How long a burst of saves is collected into one change.

     | `vidocq.dev.devServices`
     | _(unset)_
     | Whether the goal opens a dev services session for the tests: the explicit value, then the application's files, then on.
     |===

     [#dev-continuous-testing]
     === Continuous testing in `vidocq:dev` [.tag-new]#NEW#

     `vidocq:dev` gains two parameters:

     [cols="2,1,3"]
     |===
     | Property | Default | Meaning

     | `vidocq.dev.continuousTesting`
     | _(unset)_
     | Run the tests after every reload: the explicit value, then the application's files, then on when `src/test/java` exists.

     | `vidocq.dev.testWatchDirs`
     | `src/test/java,src/test/resources`
     | The test directories to watch: a change there runs the tests without reloading the application.
     |===
     ````

2. `dev-console.adoc`:
   - After the `[#logs-panel]` subsection, before the next anchor, add:

     ```asciidoc
     [#tests-panel]
     === Tests [.tag-new]#NEW#

     The console's own panel, `tests`, between the logs and the JVM, in a `dev` launch run by `vidocq:dev` with
     xref:continuous-testing.adoc[continuous testing] only: the last test run. The Maven plugin writes it to
     `target/vidocq-dev-tests.json` and names that file with `-Dvidocq.dev.tests.results`; a thread of the console,
     `vidocq-devconsole-tests`, reads it again whenever it changes, and a poll never touches the disk.

     * **Boot facts:** the last run when the console started, the results file and the log.
     * **Text** `state`: the state and the trigger of the last run, such as `failed (change)` or `running (test-change)`; `results unreadable` when the file could not be read, the previous values staying.
     * **Gauges** `run`, `failures`, `errors` and `skipped`: those of the last complete run, the previous one while a run is in progress. The chart *Tests* plots the failures and the errors.
     * **Table** `failed-tests`, 100 rows at most: the test as `Class#method`, the exception's class, and the first line of its message.
     * **Buttons** *Run all tests* and *Rerun failed tests*: each answers `queued` and the run shows in the panel once it ends; *Rerun failed tests* answers `no failed test to rerun` when the last run had none. A button never cancels the run in flight: it waits for it.

     What is never shown: a stack trace — it is in the log — or the credentials of a URL in a message. The buttons
     never send a test name: they write the word `run-all` or `rerun-failed` to `target/vidocq-dev-tests.request`,
     and the plugin works out which tests failed.
     ```

   - In the "panels shipped today" table, add the last row:

     ```asciidoc
     | *Tests* [.tag-new]#NEW#
     | The console's own panel under `vidocq:dev` with continuous testing: the last test run, its failures, and buttons to run every test or the failed ones. See <<tests-panel>>.
     ```

3. The reserved ids, in each list the id `tests` is added:

   | Page and line | Old text | New text |
   |---|---|---|
   | `dev-console-panels.adoc` (§ ids) | `` `logs` [.tag-new]#NEW#, `jvm` and `devconsole` are the console's `` | `` `logs` [.tag-new]#NEW#, `tests` [.tag-new]#NEW#, `jvm` and `devconsole` are the console's `` |
   | `dev-console-panels.adoc` (*Why:*) | `` `cdi`, `logs` or `jvm` `` | `` `cdi`, `logs`, `tests` or `jvm` `` |
   | `dev-console-panels.adoc` (checklist) | `` `logs`, `jvm` or `devconsole` `` | `` `logs`, `tests`, `jvm` or `devconsole` `` |
   | `reference.adoc` | `` `cdi`, `logs` or `jvm` [.tag-new]#NEW# `` | `` `cdi`, `logs`, `tests` or `jvm` [.tag-new]#NEW# `` |
   | `modules/vidocq-runtime-spi.adoc` | `` `cdi`, `logs` and `jvm` are taken too `` | `` `cdi`, `logs`, `tests` and `jvm` are taken too `` |
   | `modules/vidocq-runtime-core.adoc` | `` `cdi`, `logs` and `jvm` [.tag-new]#NEW# `` | `` `cdi`, `logs`, `tests` and `jvm` [.tag-new]#NEW# `` |

   Then run `grep -rn '`logs`' docs/en` and add `tests` to any other list of the console's own panels it shows.

4. `dev-services.adoc`: before the `[#no-secrets]` anchor, add:

   ```asciidoc
   [#continuous-testing]
   == Continuous testing [.tag-new]#NEW#

   xref:continuous-testing.adoc[Continuous testing] reuses the dev containers: under `vidocq:dev` every test run
   gets the session's connection keys, and `vidocq:test` opens a session of its own, host `vidocq:test`. Each run
   gets `-Dvidocq.dev.devServices=false`, so the tests never start a second set of containers.

   WARNING: The tests then share the dev database: a test that deletes rows deletes your dev data.
   ```

5. `DEV_SERVICES.md`: append a section:

   ```markdown
   ## Continuous testing (#122)

   `vidocq:dev` (with `vidocq.dev.continuousTesting`, on when `src/test/java` exists) and `vidocq:test` run the
   application's tests on every change. The tests reuse the dev containers: `vidocq:dev` hands every run its
   session's keys, `vidocq:test` opens its own session (host `vidocq:test`), and every run gets
   `-Dvidocq.dev.devServices=false`, so the JUnit host starts nothing. The tests share the dev database. See
   `docs/en/modules/ROOT/pages/continuous-testing.adoc`.
   ```

6. `whats-new.adoc`: after the dev services bullet (`**Dev services are visible, …**`), add:

   ```asciidoc
   * **Continuous testing, in `vidocq:dev` and in a new `vidocq:test` goal** [.tag-new]#NEW# — `vidocq:dev` now runs the application's tests after every reload, or alone after a test-only change, without reloading the application, and the dev console's new `tests` panel shows the last run: its counts, its failures with the first line of their message, a chart, and the buttons *Run all tests* and *Rerun failed tests*, which never name a test. `vidocq:test` runs the same loop without the application, with a summary in the terminal and `r`, `f` and `q` when a console is attached. A run is Surefire itself (`mvn test-compile surefire:test`), a new change cancels the run in flight, and the result is in `target/vidocq-dev-tests.json`, the output in `target/vidocq-dev-tests.log`. The tests reuse the dev services containers, and share their database. `vidocq.dev.continuousTesting=false` turns it off; it is on when the project has `src/test/java` (Vidocq/vidocq#122). xref:continuous-testing.adoc[Continuous testing].
   ```

7. `CLAUDE.md`, section "Maven plugin goals": after `` `run` (one forked run, …),`` add
   `` `test` (continuous testing without the application: the tests on every change, keys `r`/`f`/`q`), ``.

- [ ] **Step 3: Check the docs**

Run: `./check-doc-versions.sh` and
`grep -rn 'continuous-testing.adoc\|tests-panel\|<<test,' docs/en | sort`.
Expected: the script passes. Every `xref:` and `<<…>>` above resolves to one of the anchors of Step 1 and Step 2:
`#tests-panel`, `#test`, `#dev-continuous-testing`, `#continuous-testing`, and `#in-tests` and
`#configuration-sources` in `dev-services.adoc`, which exist already.

- [ ] **Step 4: Commit**

```bash
git add docs/en DEV_SERVICES.md CLAUDE.md
git commit -S -F <msgfile>   # "docs: continuous testing, vidocq:test and the tests panel (#122)"
```

---

## After the last task

- Full check: `mvn -o install` at the root (every unit test), then
  `mvn -o -Pit -pl vidocq-runtime-integration-tests/vidocq-runtime-it-continuous-testing verify`.
- Manual check, in the Mansart H2 example or any example with tests:
  - `mvn vidocq:dev -Dvidocq.devconsole.port=18094`: open the console, see the `tests` panel, press both
    buttons, edit a test;
  - `mvn vidocq:test` in a real terminal: `f`, `r`, `q`.

  Check the ports are free first, and stop what you start.
- Then superpowers:finishing-a-development-branch. Open the PR from `feat/122-continuous-testing` to `main` only
  on the user's go.
