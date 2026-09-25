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
