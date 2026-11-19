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
package io.vidocq.runtime.cli;

import io.vidocq.runtime.tooling.DoctorFacts;
import io.vidocq.runtime.tooling.ProjectSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vidocq/vidocq#339 (evolution 3): {@code vidocq doctor} checks the project, and {@code --fix} repairs it. */
class CommandRunnerDoctorTest {

    private static Path project(Path dir) throws IOException {
        CommandRunner.runCreate(new Command.Create(new ProjectSpec("demo", "com.acme", "com.acme.demo",
                Set.of("cassini-rest"), null, false)), dir);
        Path project = dir.resolve("demo");
        Path moduleInfo = project.resolve("src/main/java/module-info.java");
        Files.writeString(moduleInfo, Files.readString(moduleInfo).lines()
                .filter(l -> !l.contains("jakarta.ws.rs")).collect(Collectors.joining("\n", "", "\n")));
        return project;
    }

    private static int doctor(Path project, boolean fix, StringBuilder out) {
        out.append(captured(() -> out.append("exit=").append(CommandRunner.runDoctor(new Command.Doctor(false, fix),
                project, () -> DoctorFacts.Extensions.found(1))).append('\n')));
        return out.toString().contains("exit=0") ? 0 : 1;
    }

    @Test
    void aBrokenProjectFailsAndTheReportNamesTheFix(@TempDir Path dir) throws IOException {
        StringBuilder out = new StringBuilder();

        assertEquals(1, doctor(project(dir), false, out));

        assertTrue(out.toString().contains("Module directives"), out.toString());
        assertTrue(out.toString().contains("cassini-rest: requires jakarta.ws.rs"), out.toString());
        assertTrue(out.toString().contains("Fix: vidocq doctor --fix"), out.toString());
    }

    @Test
    void fixRepairsTheProjectThenReportsIt(@TempDir Path dir) throws IOException {
        Path project = project(dir);
        StringBuilder out = new StringBuilder();

        assertEquals(0, doctor(project, true, out));

        assertTrue(Files.readString(project.resolve("src/main/java/module-info.java"))
                .contains("requires jakarta.ws.rs; // vidocq:cassini-rest"));
        assertTrue(out.toString().contains("module-info.java updated"), out.toString());
        assertFalse(out.toString().contains("Fix: vidocq doctor --fix"), out.toString());
    }

    @Test
    void aHealthyProjectHasNothingToFix(@TempDir Path dir) {
        CommandRunner.runCreate(new Command.Create(new ProjectSpec("demo", "com.acme", "com.acme.demo",
                Set.of("cassini-rest"), null, false)), dir);
        StringBuilder out = new StringBuilder();

        assertEquals(0, doctor(dir.resolve("demo"), true, out));

        assertTrue(out.toString().contains("Nothing to fix."), out.toString());
    }

    private static String captured(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
