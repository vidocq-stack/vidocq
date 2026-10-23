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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the CLI tells the user about a project without a main class of its own. */
class CommandRunnerMainlessTest {

    private static Command.Create create(boolean main) {
        return new Command.Create("demo", "com.acme", "com.acme.demo", Set.of(), null, main);
    }

    @Test
    void aMainlessCreatePointsToTheMainOption(@TempDir Path dir) {
        String out = captured(() -> assertEquals(0, CommandRunner.runCreate(create(false), dir)));

        assertTrue(out.contains("No main class"), out);
        assertTrue(out.contains("--main"), out);
    }

    @Test
    void aCreateWithMainSaysNothingAboutIt(@TempDir Path dir) {
        String out = captured(() -> assertEquals(0, CommandRunner.runCreate(create(true), dir)));

        assertFalse(out.contains("No main class"), out);
    }

    @Test
    void anExtensionThatOpensTheEmptyApplicationPackageWarnsInstead(@TempDir Path dir) throws IOException {
        // The main-less scaffold leaves com/acme/demo empty: opening it would make the JVM refuse
        // the module, so the CLI says to add the opens once the package has a class.
        captured(() -> CommandRunner.runCreate(create(false), dir));
        Path moduleInfo = dir.resolve("demo/src/main/java/module-info.java");

        String out = captured(() -> CommandRunner.updateModuleInfo(dir.resolve("demo"), List.of("cassini-rest"), true));

        assertTrue(out.contains("has no class yet"), out);
        assertTrue(out.contains("opens com.acme.demo;"), out);
        assertFalse(Files.readString(moduleInfo).contains("opens com.acme.demo"), Files.readString(moduleInfo));
    }

    @Test
    void anExtensionOpensTheApplicationPackageOnceItHasAClass(@TempDir Path dir) throws IOException {
        captured(() -> CommandRunner.runCreate(create(false), dir));
        Path pkg = dir.resolve("demo/src/main/java/com/acme/demo");
        Files.writeString(pkg.resolve("HelloResource.java"), "package com.acme.demo; class HelloResource {}");

        String out = captured(() -> CommandRunner.updateModuleInfo(dir.resolve("demo"), List.of("cassini-rest"), true));

        assertFalse(out.contains("has no class yet"), out);
        assertTrue(Files.readString(dir.resolve("demo/src/main/java/module-info.java")).contains("opens com.acme.demo"));
    }

    @Test
    void aModuleInfoWithoutModuleDeclarationHasNoApplicationPackage(@TempDir Path javaRoot) throws IOException {
        assertEquals(null, CommandRunner.applicationPackage("// not a module declaration\n", javaRoot));
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
