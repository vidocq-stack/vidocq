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
package io.vidocq.runtime.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The package {@code vidocq extension add} may open: one the JVM will find in the module. */
class ExtensionEditorApplicationPackageTest {

    private static final String MODULE_INFO = "module com.acme.demo {\n}\n";

    @Test
    void aPackageWithAClassIsTheApplicationPackage(@TempDir Path javaRoot) throws IOException {
        Path pkg = Files.createDirectories(javaRoot.resolve("com/acme/demo"));
        Files.writeString(pkg.resolve("HelloResource.java"), "package com.acme.demo; class HelloResource {}");

        assertEquals("com.acme.demo", ExtensionEditor.applicationPackage(MODULE_INFO, javaRoot));
    }

    @Test
    void anEmptyPackageDirectoryIsNot(@TempDir Path javaRoot) throws IOException {
        // A main-less scaffold leaves the directory empty: opening it compiles with a warning,
        // then the JVM refuses the module ("Package com.acme.demo not found in module").
        Files.createDirectories(javaRoot.resolve("com/acme/demo"));

        assertNull(ExtensionEditor.applicationPackage(MODULE_INFO, javaRoot));
    }

    @Test
    void aPackageInfoAloneIsNot(@TempDir Path javaRoot) throws IOException {
        Path pkg = Files.createDirectories(javaRoot.resolve("com/acme/demo"));
        Files.writeString(pkg.resolve("package-info.java"), "package com.acme.demo;");

        assertNull(ExtensionEditor.applicationPackage(MODULE_INFO, javaRoot));
    }

    @Test
    void aModuleInfoWithoutModuleDeclarationHasNoApplicationPackage(@TempDir Path javaRoot) throws IOException {
        assertNull(ExtensionEditor.applicationPackage("// not a module declaration\n", javaRoot));
    }
}
