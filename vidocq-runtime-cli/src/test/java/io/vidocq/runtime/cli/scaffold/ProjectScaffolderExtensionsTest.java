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
package io.vidocq.runtime.cli.scaffold;

import io.vidocq.runtime.cli.Command;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectScaffolderExtensionsTest {

    private static String scaffoldPom(Path dir, Set<String> extensions) throws IOException {
        ProjectScaffolder.scaffold(
                new Command.Create("demo", "com.acme", "com.acme.demo", extensions, null), dir);
        return Files.readString(dir.resolve("demo/pom.xml"));
    }

    @Test
    void knownExtensionUsesItsCatalogGroupId(@TempDir Path dir) throws IOException {
        String pom = scaffoldPom(dir, Set.of("cassini-rest"));

        assertTrue(pom.contains("<groupId>io.vidocq.runtime.extensions.jakartaee.core</groupId>"),
                "cassini-rest must use its category groupId from the catalog, pom was:\n" + pom);
        assertTrue(pom.contains("<artifactId>vidocq-runtime-cassini-rest-extension</artifactId>"));
        assertFalse(pom.contains("""
                        <groupId>io.vidocq.runtime</groupId>
                        <artifactId>vidocq-runtime-cassini-rest-extension</artifactId>"""),
                "the generic io.vidocq.runtime groupId must not be emitted for a catalog extension");
    }

    @Test
    void unknownExtensionFallsBackToConvention(@TempDir Path dir) throws IOException {
        String pom = scaffoldPom(dir, Set.of("acme-widgets"));

        assertTrue(pom.contains("<artifactId>vidocq-runtime-acme-widgets-extension</artifactId>"),
                "unknown ids keep the io.vidocq.runtime:<convention> coordinate, pom was:\n" + pom);
    }

    @Test
    void explicitCoordinateIsEmittedVerbatim(@TempDir Path dir) throws IOException {
        String pom = scaffoldPom(dir, Set.of("com.acme:acme-extension"));

        assertTrue(pom.contains("<groupId>com.acme</groupId>"),
                "explicit groupId:artifactId ids must be emitted verbatim, pom was:\n" + pom);
        assertTrue(pom.contains("<artifactId>acme-extension</artifactId>"));
    }
}
