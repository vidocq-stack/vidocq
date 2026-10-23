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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code vidocq test} and {@code vidocq idea}, which run {@code vidocq:test} and {@code vidocq:idea} (Vidocq/vidocq#314). */
class CliParserTestIdeaTest {

    @Test
    void testTakesNoOption() {
        assertInstanceOf(Command.ContinuousTest.class, CliParser.parse(new String[]{"test"}));
        CliException ex = assertThrows(CliException.class, () -> CliParser.parse(new String[]{"test", "--bogus"}));
        assertTrue(ex.getMessage().contains("test"), ex.getMessage());
    }

    @Test
    void ideaWritesByDefault() {
        assertEquals(new Command.Idea(null, null), CliParser.parse(new String[]{"idea"}));
    }

    @Test
    void ideaChecksStrictlyOrNotAndPicksTheKind() {
        assertEquals(new Command.Idea("true", null), CliParser.parse(new String[]{"idea", "--check"}));
        assertEquals(new Command.Idea("strict", "application"),
                CliParser.parse(new String[]{"idea", "--strict", "--kind", "application"}));
    }

    @Test
    void ideaRejectsAnUnknownOption() {
        CliException ex = assertThrows(CliException.class, () -> CliParser.parse(new String[]{"idea", "--bogus"}));
        assertTrue(ex.getMessage().contains("idea"), ex.getMessage());
    }

    @Test
    void ideaKindNeedsAValue() {
        CliException ex = assertThrows(CliException.class, () -> CliParser.parse(new String[]{"idea", "--kind"}));
        assertTrue(ex.getMessage().contains("--kind"), ex.getMessage());
    }

    @Test
    void theHelpListsBothCommandsAndTheirPages() {
        String all = help(null);
        assertTrue(all.contains("mvn vidocq:test") && all.contains("mvn vidocq:idea"), all);
        assertTrue(help("test").contains("Keys: r runs every test"), help("test"));
        String idea = help("idea");
        assertTrue(idea.startsWith("vidocq idea — IntelliJ run configurations"), idea);
        assertTrue(idea.contains("Options:") && idea.contains("--strict") && idea.contains("--kind <kind>"), idea);
    }

    /** The topic pages factored with the test and idea ones keep their text. */
    @Test
    void theStartDevAndDoctorPagesKeepTheirOptions() {
        assertTrue(help("start").contains("--config, -c <file>"), help("start"));
        assertTrue(help("dev").contains("restarts on every source change."), help("dev"));
        assertTrue(help("doctor").contains("--verbose, -v"), help("doctor"));
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
