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

/** {@code vidocq vscode}, which runs {@code vidocq:vscode} (Vidocq/vidocq#339). */
class CliParserVscodeTest {

    @Test
    void vscodeWritesByDefaultAndChecksOnDemand() {
        assertEquals(new Command.Vscode(false), CliParser.parse(new String[]{"vscode"}));
        assertEquals(new Command.Vscode(true), CliParser.parse(new String[]{"vscode", "--check"}));
    }

    @Test
    void vscodeRejectsAnUnknownOption() {
        CliException ex = assertThrows(CliException.class, () -> CliParser.parse(new String[]{"vscode", "--strict"}));
        assertTrue(ex.getMessage().contains("vscode"), ex.getMessage());
    }

    @Test
    void theCommandRunsTheGoal() {
        assertEquals(List.of("vidocq:vscode"), RunGoals.vscode(false));
        assertEquals(List.of("vidocq:vscode", "-Dvidocq.vscode.check=true"), RunGoals.vscode(true));
    }

    @Test
    void theHelpListsTheCommandAndItsPage() {
        assertTrue(help(null).contains("mvn vidocq:vscode"), help(null));
        String page = help("vscode");
        assertTrue(page.startsWith("vidocq vscode — VS Code settings, tasks and debug"), page);
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
