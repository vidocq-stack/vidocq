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

import io.vidocq.runtime.tooling.PomConverter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vidocq/vidocq#380: {@code vidocq pom convert --to parentless|parent [--check]}. */
class CliParserPomTest {

    @Test
    void convertsToTheParentlessStyle() {
        Command.PomConvert c = (Command.PomConvert) CliParser.parse(
                new String[]{"pom", "convert", "--to", "parentless"});

        assertEquals(PomConverter.Target.PARENTLESS, c.target());
        assertFalse(c.check());
    }

    @Test
    void convertsToTheParentStyleAndOnlyChecks() {
        Command.PomConvert c = (Command.PomConvert) CliParser.parse(
                new String[]{"pom", "convert", "--to", "parent", "--check"});

        assertEquals(PomConverter.Target.PARENT, c.target());
        assertTrue(c.check());
    }

    @Test
    void theTargetIsRequiredAndNamed() {
        CliException missing = assertThrows(CliException.class,
                () -> CliParser.parse(new String[]{"pom", "convert"}));
        CliException unknown = assertThrows(CliException.class,
                () -> CliParser.parse(new String[]{"pom", "convert", "--to", "sideways"}));
        CliException noSubCommand = assertThrows(CliException.class, () -> CliParser.parse(new String[]{"pom"}));
        CliException otherSubCommand = assertThrows(CliException.class,
                () -> CliParser.parse(new String[]{"pom", "upgrade"}));

        assertTrue(missing.getMessage().contains("--to"), missing.getMessage());
        assertTrue(unknown.getMessage().contains("parentless") && unknown.getMessage().contains("parent"),
                unknown.getMessage());
        assertTrue(noSubCommand.getMessage().contains("convert"), noSubCommand.getMessage());
        assertTrue(otherSubCommand.getMessage().contains("convert"), otherSubCommand.getMessage());
    }

    @Test
    void theHelpDescribesTheConversion() {
        String help = help("pom");

        assertTrue(help.contains("vidocq pom convert"), help);
        assertTrue(help.contains("--to <parentless|parent>"), help);
        assertTrue(help.contains("--check"), help);
    }

    private static String help(String topic) {
        PrintStream out = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            CliParser.printHelp(topic);
        } finally {
            System.setOut(out);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
