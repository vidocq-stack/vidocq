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

import io.vidocq.runtime.tooling.PomConverter.Target;
import io.vidocq.runtime.tooling.ProjectScaffolder;
import io.vidocq.runtime.tooling.ProjectSpec;
import io.vidocq.runtime.tooling.Reporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vidocq/vidocq#380: {@code vidocq pom convert} on a project: the diff is printed, the pom is written unless
 * {@code --check}, which exits with 1 when a conversion is needed; an unrecognised pom is refused and left alone.
 */
class CommandRunnerPomTest {

    private static final Reporter SILENT = new Reporter() {
        @Override public void success(String message, String detail) { /* quiet */ }
        @Override public void note(String message) { /* quiet */ }
        @Override public void warning(String message) { /* quiet */ }
        @Override public void error(String message) { /* quiet */ }
    };

    @Test
    void convertsThePomInPlaceAndPrintsTheDiff(@TempDir Path dir) throws IOException {
        Path project = withParent(dir);
        StringBuilder out = new StringBuilder();

        int status = capture(out, () -> CommandRunner.runPomConvert(new Command.PomConvert(Target.PARENTLESS, false),
                project));

        assertEquals(0, status, out.toString());
        String pom = Files.readString(project.resolve("pom.xml"));
        assertFalse(pom.contains("<parent>"), pom);
        assertTrue(pom.contains("<extensions>true</extensions>"), pom);
        assertTrue(out.toString().contains("-        <artifactId>vidocq-runtime-parent</artifactId>"), out.toString());
        assertTrue(out.toString().contains("+                <extensions>true</extensions>"), out.toString());
    }

    @Test
    void checkReportsWithoutWriting(@TempDir Path dir) throws IOException {
        Path project = withParent(dir);
        String before = Files.readString(project.resolve("pom.xml"));
        StringBuilder out = new StringBuilder();

        int needed = capture(out, () -> CommandRunner.runPomConvert(new Command.PomConvert(Target.PARENTLESS, true),
                project));
        int already = capture(out, () -> CommandRunner.runPomConvert(new Command.PomConvert(Target.PARENT, true),
                project));

        assertEquals(1, needed, out.toString());
        assertEquals(0, already, out.toString());
        assertEquals(before, Files.readString(project.resolve("pom.xml")), "--check writes nothing");
    }

    @Test
    void anUnrecognisedPomIsRefusedAndLeftAlone(@TempDir Path dir) throws IOException {
        String plain = "<project><modelVersion>4.0.0</modelVersion><groupId>a</groupId><artifactId>b</artifactId>"
                + "<version>1</version></project>\n";
        Files.writeString(dir.resolve("pom.xml"), plain);
        StringBuilder out = new StringBuilder();

        int status = capture(out, () -> CommandRunner.runPomConvert(new Command.PomConvert(Target.PARENTLESS, false), dir));

        assertEquals(1, status);
        assertEquals(plain, Files.readString(dir.resolve("pom.xml")));
    }

    @Test
    void aDirectoryWithoutAPomIsRefused(@TempDir Path dir) {
        StringBuilder out = new StringBuilder();

        int status = capture(out, () -> CommandRunner.runPomConvert(new Command.PomConvert(Target.PARENT, false), dir));

        assertEquals(1, status);
    }

    private static Path withParent(Path dir) throws IOException {
        ProjectScaffolder.scaffold(new ProjectSpec("demo", "com.acme", "com.acme.demo", Set.of("cassini-rest"), null,
                false, false, true), dir, "0.4.0", SILENT);
        return dir.resolve("demo");
    }

    /** Runs {@code action} with stdout and stderr captured into {@code out}. */
    private static int capture(StringBuilder out, java.util.function.IntSupplier action) {
        PrintStream stdout = System.out;
        PrintStream stderr = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(stream);
        System.setErr(stream);
        try {
            return action.getAsInt();
        } finally {
            System.setOut(stdout);
            System.setErr(stderr);
            out.append(buffer.toString(StandardCharsets.UTF_8));
        }
    }
}
