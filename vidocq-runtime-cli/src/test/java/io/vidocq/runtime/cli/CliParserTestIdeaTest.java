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
}
