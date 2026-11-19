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

import io.vidocq.runtime.cli.update.UpdateNotice;
import io.vidocq.runtime.tooling.ProjectSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vidocq/vidocq#353: a CLI that says less and helps more. */
class CommandRunnerSaysLessTest {

    @Test
    void infoSummarisesTheProjectWithoutRunningMaven(@TempDir Path dir) throws IOException {
        CommandRunner.runCreate(new Command.Create(new ProjectSpec("demo", "com.acme", "com.acme.demo",
                Set.of("cassini-rest"), "0.4.0", false)), dir);
        Path project = dir.resolve("demo");
        Files.writeString(project.resolve("src/main/resources/vidocq.properties"), "vidocq.profile = staging\n");

        String out = captured(() -> assertEquals(0, CommandRunner.runInfo(project)));

        assertTrue(out.contains("Java version:"), out);
        assertTrue(out.lines().anyMatch(l -> l.matches("\\s+Main module:\\s+com\\.acme\\.demo")), out);
        assertTrue(out.lines().anyMatch(l -> l.matches("\\s+Runtime version:\\s+0\\.4\\.0")), out);
        assertTrue(out.lines().anyMatch(l -> l.matches("\\s+Extensions:\\s+1 declared.*vidocq extension list.*")),
                out);
        assertTrue(out.lines().anyMatch(l -> l.matches("\\s+Profile:\\s+staging.*vidocq\\.properties.*")), out);
        assertFalse(out.contains("resolving the project's runtime dependencies"), out);
    }

    @Test
    void infoOutsideAProjectSaysSo(@TempDir Path dir) {
        String out = captured(() -> assertEquals(0, CommandRunner.runInfo(dir)));

        assertTrue(out.contains("Java version:"), out);
        assertTrue(out.contains("(no pom.xml in this directory)"), out);
    }

    @Test
    void anUnknownKeyIsSetWithAWarningAndTheKeyMeant(@TempDir Path dir) throws IOException {
        String out = captured(() -> assertEquals(0,
                CommandRunner.runConfigSet(new Command.Config.Set("vidocq.htpp.port", "8081"), dir)));

        assertTrue(out.contains("No Vidocq module reads 'vidocq.htpp.port'. Did you mean 'vidocq.http.port'?"), out);
        assertEquals("vidocq.htpp.port=8081", Files.readString(dir.resolve("vidocq.properties")).strip());
    }

    @Test
    void aKnownOrAnApplicationKeyIsSetSilently(@TempDir Path dir) {
        String out = captured(() -> {
            CommandRunner.runConfigSet(new Command.Config.Set("vidocq.http.port", "8081"), dir);
            CommandRunner.runConfigSet(new Command.Config.Set("greeting", "hi"), dir);
        });

        assertFalse(out.contains("No Vidocq module reads"), out);
    }

    @Test
    void theCatalogIsListedByFamilyWithoutTheWordOffline() {
        String out = captured(() -> assertEquals(0,
                CommandRunner.run(CliParser.parse(new String[] {"extension", "list", "--available"}))));

        List<String> headings = out.lines().filter(l -> l.matches("  [A-Z][\\w ]+")).map(String::strip).toList();
        assertEquals(List.of("Jakarta EE", "MicroProfile", "Data", "Web"), headings, out);
        assertTrue(out.indexOf("cassini-rest") > out.indexOf("Jakarta EE")
                && out.indexOf("cassini-rest") < out.indexOf("MicroProfile"), out);
        assertTrue(out.contains("knock-health"), out);
        assertFalse(out.toLowerCase().contains("offline"), out);
        assertFalse(out.contains("registry"), out);
    }

    /** Commands whose output a script reads, and the update itself, never carry the notice. */
    @Test
    void noUpdateNoticeForMachineReadOutputOrTheUpdateItself() {
        for (String[] args : new String[][] {{"version"}, {"completion", "bash"}, {"config", "get", "a"},
                {"update"}, {"update", "--check"}}) {
            assertSame(UpdateNotice.NONE, CommandRunner.updateNotice(CliParser.parse(args)), String.join(" ", args));
        }
    }

    /** Surefire runs the CLI from its classes, not from an installation: there is nothing to update. */
    @Test
    void noUpdateNoticeForACliThatWasNotInstalled() {
        assertSame(UpdateNotice.NONE, CommandRunner.updateNotice(CliParser.parse(new String[] {"info"})));
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
