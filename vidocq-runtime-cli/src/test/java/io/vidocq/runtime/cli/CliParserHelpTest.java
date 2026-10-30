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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * {@code vidocq <command> --help} shows that command's help, as {@code vidocq help <command>} does, instead of
 * refusing {@code --help} as an unknown option (BUG-20261030-17, item 5).
 */
class CliParserHelpTest {

    @ParameterizedTest
    @CsvSource({
            "build,--help,build",
            "build,-h,build",
            "start,--help,start",
            "dev,--help,dev",
            "create,--help,create",
            "extension,--help,extension",
            "ext,--help,extension",
            "doctor,-h,doctor",
            "update,--help,update",
    })
    void theHelpOptionOfACommandShowsItsHelp(String command, String option, String topic) {
        Command.Help help = assertInstanceOf(Command.Help.class, CliParser.parse(new String[]{command, option}));

        assertEquals(topic, help.topic());
    }

    @Test
    void theHelpOptionWinsAfterOtherOptions() {
        Command.Help help = assertInstanceOf(Command.Help.class,
                CliParser.parse(new String[]{"extension", "add", "cassini-rest", "--help"}));

        assertEquals("extension", help.topic());
    }

    /** After {@code --}, the arguments are Maven's: {@code vidocq build -- --help} asks Maven for its help. */
    @Test
    void aHelpOptionPassedThroughToMavenStaysThere() {
        Command.Build build = assertInstanceOf(Command.Build.class,
                CliParser.parse(new String[]{"build", "--", "--help"}));

        assertEquals(java.util.List.of("--help"), build.passthrough());
    }
}
