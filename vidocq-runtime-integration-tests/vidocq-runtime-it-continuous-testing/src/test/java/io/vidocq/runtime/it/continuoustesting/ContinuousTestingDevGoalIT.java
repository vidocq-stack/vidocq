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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

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
            Map<String, Object> first = Fixture.awaitResults(project, r -> Fixture.is(r, "failed", "run-all"));
            assertEquals(3, Fixture.count(first, "run"));
            assertEquals(1, Fixture.count(first, "failures"));

            String console = Fixture.consoleUrl(log);
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
