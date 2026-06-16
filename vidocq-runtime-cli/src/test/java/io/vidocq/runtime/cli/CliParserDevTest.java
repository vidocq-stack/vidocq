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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliParserDevTest {

    @Test
    void bareDevUsesDevProfileAndDefaultPort() {
        Command.Dev dev = assertInstanceOf(Command.Dev.class, CliParser.parse(new String[]{"dev"}));
        assertEquals(8080, dev.port());
        assertEquals("dev", dev.profile());
        assertFalse(dev.debug());
    }

    @Test
    void longProfileFlagIsParsed() {
        Command.Dev dev = (Command.Dev) CliParser.parse(new String[]{"dev", "--profile", "prod"});
        assertEquals("prod", dev.profile());
    }

    @Test
    void allDevOptionsCombine() {
        Command.Dev dev = (Command.Dev) CliParser.parse(
                new String[]{"dev", "-P", "test", "--port", "9090", "--debug"});
        assertEquals(9090, dev.port());
        assertEquals("test", dev.profile());
        assertTrue(dev.debug());
    }

    @Test
    void unknownDevOptionIsRejected() {
        CliException ex = assertThrows(CliException.class,
                () -> CliParser.parse(new String[]{"dev", "--bogus"}));
        assertTrue(ex.getMessage().contains("dev"));
    }

    @Test
    void devDefaultsMatchParserDefault() {
        assertEquals(Command.Dev.defaults(), CliParser.parse(new String[]{"dev"}));
    }
}
