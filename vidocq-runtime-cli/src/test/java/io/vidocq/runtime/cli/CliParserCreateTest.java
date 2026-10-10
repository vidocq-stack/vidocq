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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliParserCreateTest {

    @Test
    void parentVersionDefaultsToNull() {
        Command.Create c = assertInstanceOf(Command.Create.class,
                CliParser.parse(new String[]{"create", "--name", "demo"}));
        assertNull(c.parentVersion());
    }

    @Test
    void parsesParentVersionOption() {
        Command.Create c = assertInstanceOf(Command.Create.class,
                CliParser.parse(new String[]{"create", "--name", "demo",
                        "--parent-version", "1.2.3"}));
        assertEquals("1.2.3", c.parentVersion());
    }

    @Test
    void rejectsANameThatMakesAnInvalidPackage() {
        // "ft-030" would give the package and module io.example.ft.030, which does not compile.
        var e = assertThrows(CliException.class,
                () -> CliParser.parse(new String[]{"create", "--name", "ft-030"}));
        assertTrue(e.getMessage().contains("io.example.ft.030"), e.getMessage());
        assertTrue(e.getMessage().contains("--package"), e.getMessage());
    }

    @Test
    void rejectsAnInvalidExplicitPackage() {
        var e = assertThrows(CliException.class,
                () -> CliParser.parse(new String[]{"create", "--name", "demo", "--package", "com.acme.class"}));
        assertTrue(e.getMessage().contains("com.acme.class"), e.getMessage());
    }

    @Test
    void acceptsAValidPackageForANameWithDigits() {
        Command.Create c = assertInstanceOf(Command.Create.class,
                CliParser.parse(new String[]{"create", "--name", "ft-030", "--package", "io.example.ft030"}));
        assertEquals("io.example.ft030", c.pkg());
    }
}
