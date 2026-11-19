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

import io.vidocq.runtime.cli.build.RunGoals;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code vidocq eclipse}, which runs {@code vidocq:eclipse} (Vidocq/vidocq#339). */
class CliParserEclipseTest {

    @Test
    void eclipseWritesByDefaultAndChecksOnDemand() {
        assertEquals(new Command.Eclipse(false), CliParser.parse(new String[]{"eclipse"}));
        assertEquals(new Command.Eclipse(true), CliParser.parse(new String[]{"eclipse", "--check"}));
    }

    @Test
    void eclipseRejectsAnUnknownOption() {
        CliException ex = assertThrows(CliException.class, () -> CliParser.parse(new String[]{"eclipse", "--strict"}));
        assertTrue(ex.getMessage().contains("eclipse"), ex.getMessage());
    }

    @Test
    void theCommandRunsTheGoal() {
        assertEquals(List.of("vidocq:eclipse"), RunGoals.eclipse(false));
        assertEquals(List.of("vidocq:eclipse", "-Dvidocq.eclipse.check=true"), RunGoals.eclipse(true));
    }

    @Test
    void theCommandSaysWhetherItWritesOrChecks() {
        assertEquals("Writing the Eclipse configurations in .eclipse/…",
                CommandRunner.progress(false, "the Eclipse configurations in .eclipse/"));
        assertEquals("Checking the Eclipse configurations in .eclipse/…",
                CommandRunner.progress(true, "the Eclipse configurations in .eclipse/"));
    }

    @Test
    void theHelpListsTheCommandAndItsPage() {
        assertTrue(help(null).contains("mvn vidocq:eclipse"), help(null));
        String page = help("eclipse");
        assertTrue(page.startsWith("vidocq eclipse — Eclipse run and debug configurations"), page);
        assertTrue(page.contains("--check"), page);
    }

    private static String help(String command) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            CliParser.printHelp(command);
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8).replaceAll("\u001B\\[[0-9;]*m", "");
    }
}
