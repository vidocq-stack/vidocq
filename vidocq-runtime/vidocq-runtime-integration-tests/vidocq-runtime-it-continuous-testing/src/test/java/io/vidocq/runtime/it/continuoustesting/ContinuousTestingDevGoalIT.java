/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.it.continuoustesting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A real {@code mvn vidocq:dev} on the fixture (spec §7): continuous testing starts paused (#798), and the dev
 * console's <i>Run all tests</i> starts it; the first run fails one test, and the {@code tests} panel shows it;
 * {@code rerun-failed} runs that test alone; fixing the test turns the run green without an application reload.
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
        Path log = logs.resolve("vidocq-dev.log");

        Process mvn = Fixture.start(project, log, "vidocq:dev", "-Dvidocq.dev.debug=false",
                // Both bind port 0, never 8080/8888, and the console's port is read from its log line.
                "-Dvidocq.chappe.listener.default.port=0", "-Dvidocq.devconsole.port=0");
        try {
            Fixture.awaitLog(mvn, log, STARTED);
            // Vidocq/vidocq#143: the fixture no longer declares the console — this proves vidocq:dev added it by
            // itself, because the application has Chappe. Asserting the log for "Dev tools: ..." would not work
            // here: DevModules only logs that line when a runtime extension's own -dev companion is added
            // (DevModules.collect, non-empty `names`), which this fixture has none of — it depends on Chappe and
            // vidocq-runtime-core only. DevConsoleJars, which adds the console itself, logs nothing on success
            // (only "Dev tools: no dev console, ..." when Chappe is absent). The console actually starting is
            // what proves the point, so this asserts its own boot line instead.
            Fixture.awaitLog(mvn, log, "Vidocq dev console: http://");
            // #798: paused by default, with one line that says how to start the tests, and no run before that.
            assertTrue(Fixture.read(log).contains("Continuous testing: paused. Use Run all tests in the dev"
                    + " console's tests panel to start it"), "the paused line, without keys since no console:\n"
                    + Fixture.read(log));
            assertFalse(Files.exists(project.resolve("target/vidocq-dev-tests.json")), "no run while paused");
            String console = Fixture.consoleUrl(log);
            String token = (String) ((Map<?, ?>) Fixture.json(Fixture.get(console + "/api/snapshot"))
                    .get("console")).get("actionToken");
            assertTrue(Fixture.postAction(console, "tests/run-all", token, "{}").contains("queued"));
            Fixture.awaitLog(mvn, log, "Tests: continuous testing started");
            Map<String, Object> first = Fixture.awaitResults(project, r -> Fixture.is(r, "failed", "run-all"));
            assertEquals(3, Fixture.count(first, "run"));
            assertEquals(1, Fixture.count(first, "failures"));

            Map<String, Object> snapshot = awaitPanelFailures(console, 1);
            Map<?, ?> panel = Fixture.panel(snapshot, "tests");
            List<?> rows = (List<?>) Fixture.value(panel, "failed-tests").get("rows");
            assertEquals(FAILING, ((List<?>) rows.getFirst()).getFirst(), panel.toString());

            String answer = Fixture.postAction(console, "tests/rerun-failed", token, "{}");
            assertTrue(answer.contains("queued"), answer);
            Map<String, Object> rerun = Fixture.awaitResults(project,
                    r -> Fixture.is(r, "failed", "rerun-failed"));
            assertEquals(1, Fixture.count(rerun, "run"), "only the failed test ran");

            // BUG-20261117-01: under vidocq:dev the recompile builds the main output, and a test run leaves it
            // alone. A main class older than its source is what a run's own Maven would compile again.
            Path source = project.resolve("src/main/java/io/vidocq/it/ct/Greeter.java");
            Path compiled = project.resolve("target/classes/io/vidocq/it/ct/Greeter.class");
            Files.setLastModifiedTime(compiled,
                    FileTime.from(Files.getLastModifiedTime(source).toInstant().minus(Duration.ofHours(1))));
            FileTime older = Files.getLastModifiedTime(compiled);

            Fixture.fixExpectation(project);
            Map<String, Object> green = Fixture.awaitResults(project, r -> Fixture.is(r, "passed", "test-change"));
            assertEquals(3, Fixture.count(green, "run"));
            assertEquals(1, Fixture.occurrences(log, STARTED), "a test change never reloads the application");
            assertEquals(older, Files.getLastModifiedTime(compiled),
                    "a test run of vidocq:dev compiled the main sources: the recompile alone builds them");

            // BUG-20261117-03: a source saved while the recompile compiles it, after javac read it, gets a class
            // that is not older, and the compiler plugin takes it for compiled. A first save gives the dev loop a
            // recompile to compare with. The second one bears the time of the class that recompile left, which
            // is what javac leaves when it writes the class after the save.
            Fixture.addGreeterMethod(project, "first", null);
            Fixture.awaitLog(mvn, log, "Greeter gained a method first");
            Fixture.awaitResults(project, r -> Fixture.is(r, "passed", "change"));
            int before = Fixture.read(log).length();

            Fixture.addGreeterMethod(project, "second", Files.getLastModifiedTime(compiled));
            String after = Fixture.poll(Duration.ofMinutes(2), () -> Fixture.read(log).substring(before),
                    said -> said.contains("Greeter gained a method second") || said.contains("nothing reloaded"));
            assertTrue(after.contains("Greeter.java was saved since the previous recompile started and its class is"
                    + " not older: compiling it again."), "the recompile names the source it compiles again:\n" + after);
            assertTrue(after.contains("Greeter gained a method second"),
                    "the save is compiled, and the application reloads with it:\n" + after);
        } finally {
            Fixture.stop(mvn);
        }
    }

    /**
     * BUG-20261117-05: the dev loop watches the sources last, so a save made while {@code vidocq:dev} starts begins no
     * recompile, and a source saved while the build compiled it, after javac read it, gets a class that is not older.
     * Such a save leaves a source and a class that are not older than Maven's start: here both are stamped ahead of
     * it, the class last.
     */
    @Test
    void aSourceSavedWhileVidocqDevStartsIsCompiledBeforeTheApplicationStarts(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        Path logs = Fixture.logs();
        Fixture.warmUp(project, logs.resolve("dev-start-warm-up.log"));
        Path log = logs.resolve("vidocq-dev-start.log");
        Path compiled = project.resolve("target/classes/io/vidocq/it/ct/Greeter.class");
        Instant saved = Instant.now().plus(Duration.ofMinutes(10));
        Fixture.addGreeterMethod(project, "savedAtStart", FileTime.from(saved));
        Files.setLastModifiedTime(compiled, FileTime.from(saved.plusSeconds(1)));

        Process mvn = Fixture.start(project, log, "vidocq:dev", "-Dvidocq.dev.debug=false",
                "-Dvidocq.chappe.listener.default.port=0", "-Dvidocq.devconsole.port=0");
        try {
            Fixture.awaitLog(mvn, log, STARTED);
            String said = Fixture.read(log);
            int compiling = said.indexOf("Greeter.java was saved while vidocq:dev started: compiling it before the"
                    + " application starts.");
            assertTrue(compiling >= 0 && compiling < said.indexOf("Child JVM started"),
                    "the save is compiled before the application starts:\n" + said);
            assertTrue(said.contains("Greeter.java was saved since the build started and its class is not older:"
                    + " compiling it again."), "the class the build left is not taken for compiled:\n" + said);
            assertTrue(Files.readString(compiled, StandardCharsets.ISO_8859_1).contains("savedAtStart"),
                    "the class the application loads has the method that was saved:\n" + said);
        } finally {
            Fixture.stop(mvn);
        }
    }

    /**
     * BUG-20261117-07: a bare {@code vidocq:dev} runs no phase of the lifecycle, so nothing compiled a source edited
     * while it did not run. That source is newer than everything the last build left: the project is built before
     * the application starts.
     */
    @Test
    void aSourceEditedWhileVidocqDevDidNotRunIsBuiltBeforeTheApplicationStarts(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        Path logs = Fixture.logs();
        Fixture.warmUp(project, logs.resolve("dev-stopped-warm-up.log"));
        Path log = logs.resolve("vidocq-dev-stopped.log");
        Path compiled = project.resolve("target/classes/io/vidocq/it/ct/Greeter.class");
        Fixture.addGreeterMethod(project, "editedWhileStopped", null);

        Process mvn = Fixture.start(project, log, "vidocq:dev", "-Dvidocq.dev.debug=false",
                "-Dvidocq.chappe.listener.default.port=0", "-Dvidocq.devconsole.port=0");
        try {
            Fixture.awaitLog(mvn, log, STARTED);
            String said = Fixture.read(log);
            int building = said.indexOf("Since the last build, Greeter.java changed: building the project first"
                    + " (mvn process-classes).");
            assertTrue(building >= 0 && building < said.indexOf("Child JVM started"),
                    "the project is built before the application starts:\n" + said);
            assertFalse(said.contains("was saved while vidocq:dev started"),
                    "edited before Maven started, and built since: the start has nothing more to compile:\n" + said);
            assertTrue(Files.readString(compiled, StandardCharsets.ISO_8859_1).contains("editedWhileStopped"),
                    "the class the application loads has the method that was added:\n" + said);
        } finally {
            Fixture.stop(mvn);
        }
    }

    /**
     * BUG-20261117-08: a source saved while an earlier build compiled it, after javac read it. Its class was written
     * after the save, from the text before it: the compiler plugin takes the source for compiled, in the build a bare
     * {@code vidocq:dev} starts too. Here the source bears the time of its class, which the build wrote after the
     * compiler listed its sources: the latest save that class could hide.
     */
    @Test
    void aSourceSavedWhileTheLastBuildCompiledItIsCompiledBeforeTheApplicationStarts(@TempDir Path dir)
            throws Exception {
        Path project = Fixture.copy(dir);
        Path logs = Fixture.logs();
        Fixture.warmUp(project, logs.resolve("dev-in-doubt-warm-up.log"));
        Path log = logs.resolve("vidocq-dev-in-doubt.log");
        Path compiled = project.resolve("target/classes/io/vidocq/it/ct/Greeter.class");
        Path listed = project.resolve("target/maven-status/maven-compiler-plugin/compile/default-compile/inputFiles.lst");
        FileTime saved = Files.getLastModifiedTime(compiled);
        assertTrue(saved.toMillis() > Files.getLastModifiedTime(listed).toMillis(),
                "the premise of this test: the build wrote the class after the compiler listed its sources");
        Fixture.addGreeterMethod(project, "savedWhileBuilding", saved);

        Process mvn = Fixture.start(project, log, "vidocq:dev", "-Dvidocq.dev.debug=false",
                "-Dvidocq.chappe.listener.default.port=0", "-Dvidocq.devconsole.port=0");
        try {
            Fixture.awaitLog(mvn, log, STARTED);
            String said = Fixture.read(log);
            assertTrue(Files.readString(compiled, StandardCharsets.ISO_8859_1).contains("savedWhileBuilding"),
                    "the class the application loads has the method that was saved:\n" + said);
            int again = said.indexOf("Greeter.java was saved since the last build started and its class is not older:"
                    + " compiling it again.");
            assertTrue(again >= 0 && again < said.indexOf("Child JVM started"),
                    "the start says which class the last build left in doubt, before the application starts:\n"
                            + said);
        } finally {
            Fixture.stop(mvn);
        }
    }

    /** The snapshot, once the panel's reader thread has seen {@code failures} failures. */
    private static Map<String, Object> awaitPanelFailures(String console, int failures) throws Exception {
        Predicate<Map<String, Object>> shows = shown -> {
            Map<?, ?> value = Fixture.value(Fixture.panel(shown, "tests"), "failures");
            return value != null && ((Number) value.get("value")).intValue() == failures;
        };
        Map<String, Object> snapshot = Fixture.poll(Duration.ofSeconds(10),
                () -> Fixture.json(Fixture.get(console + "/api/snapshot")), shows);
        if (shows.test(snapshot)) {
            return snapshot;
        }
        return fail("the tests panel never showed " + failures + " failure(s): " + snapshot);
    }
}
