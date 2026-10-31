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

import io.vidocq.runtime.tooling.KnownExtensions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliParserCreateTest {

    @Test
    void parentVersionDefaultsToNull() {
        Command.Create c = assertInstanceOf(Command.Create.class,
                CliParser.parse(new String[]{"create", "--name", "demo"}));
        assertNull(c.spec().parentVersion());
    }

    @Test
    void parsesParentVersionOption() {
        Command.Create c = assertInstanceOf(Command.Create.class,
                CliParser.parse(new String[]{"create", "--name", "demo",
                        "--parent-version", "1.2.3"}));
        assertEquals("1.2.3", c.spec().parentVersion());
    }

    @Test
    void noApplicationMainClassByDefault() {
        Command.Create c = assertInstanceOf(Command.Create.class,
                CliParser.parse(new String[]{"create", "--name", "demo"}));
        assertFalse(c.spec().main());
    }

    @Test
    void mainOptionAsksForAVidocqMainTrampoline() {
        Command.Create c = assertInstanceOf(Command.Create.class,
                CliParser.parse(new String[]{"create", "--name", "demo", "--main"}));
        assertTrue(c.spec().main());
    }

    @Test
    void openModuleOptionAsksForAnOpenModule() {
        Command.Create c = assertInstanceOf(Command.Create.class,
                CliParser.parse(new String[]{"create", "--name", "demo", "--open-module"}));
        assertTrue(c.spec().openModule());
        Command.Create plain = assertInstanceOf(Command.Create.class,
                CliParser.parse(new String[]{"create", "--name", "demo"}));
        assertFalse(plain.spec().openModule());
    }

    @Test
    void openModuleOptionReachesTheQuestionsToo() {
        var asked = assertInstanceOf(Command.CreateInteractively.class,
                CliParser.parse(new String[]{"create", "--open-module"}));
        assertTrue(asked.openModule());
    }

    @Test
    void theHelpListsTheOpenModuleOption() {
        assertTrue(help("create").contains("--open-module"), help("create"));
    }

    @Test
    void theHelpListsTheMainOption() {
        assertTrue(help("create").contains("--main"), help("create"));
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
        assertEquals("io.example.ft030", c.spec().pkg());
    }

    /** BUG-20261023-04: "-x rest" resolved by convention to an artifact that does not exist. */
    @Test
    void theHelpExampleEnablesCatalogExtensions() {
        String help = help("create");
        Matcher example = Pattern.compile("vidocq create --name [^\\n]*").matcher(help);
        assertTrue(example.find(), help);
        Matcher ids = Pattern.compile("-x (\\S+)").matcher(example.group());

        assertTrue(ids.find(), example.group());
        do {
            assertTrue(KnownExtensions.isKnown(ids.group(1)), ids.group(1) + " is not a catalog id");
        } while (ids.find());
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
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
