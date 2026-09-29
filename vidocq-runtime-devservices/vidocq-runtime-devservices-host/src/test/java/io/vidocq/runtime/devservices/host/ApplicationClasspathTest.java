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
package io.vidocq.runtime.devservices.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A class looked up in directories and jars of the application's class path, never loaded (spec §3). */
class ApplicationClasspathTest {

    @Test
    void findsAClassInADirectory(@TempDir Path dir) throws Exception {
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes.resolve("org/postgresql"));
        Files.write(classes.resolve("org/postgresql/Driver.class"), new byte[0]);

        ApplicationClasspath classpath = new ApplicationClasspath(List.of(classes));

        assertTrue(classpath.contains("org.postgresql.Driver"));
        assertFalse(classpath.contains("org.h2.Driver"));
    }

    @Test
    void findsAClassInAJarWithoutLoadingIt(@TempDir Path dir) throws Exception {
        Path jar = driverJar(dir.resolve("postgresql.jar"));

        ApplicationClasspath classpath = new ApplicationClasspath(List.of(dir.resolve("classes"), jar));

        assertTrue(classpath.contains("org.postgresql.Driver"), "found although its bytes are not a class");
        assertFalse(classpath.contains("org.postgresql.Missing"));
    }

    @Test
    void aJarsEntriesAreReadOnceAndRemembered(@TempDir Path dir) throws Exception {
        Path jar = driverJar(dir.resolve("postgresql.jar"));
        ApplicationClasspath classpath = new ApplicationClasspath(List.of(jar));
        assertTrue(classpath.contains("org.postgresql.Driver"));

        Files.delete(jar);

        assertTrue(classpath.contains("org.postgresql.Driver"), "the names read the first time are kept");
    }

    @Test
    void aMissingPathOrAFileThatIsNoJarHoldsNothing(@TempDir Path dir) throws Exception {
        Path notAJar = dir.resolve("broken.jar");
        Files.writeString(notAJar, "not a zip");

        ApplicationClasspath classpath = new ApplicationClasspath(
                List.of(dir.resolve("missing"), dir.resolve("missing.jar"), notAJar));

        assertFalse(classpath.contains("org.postgresql.Driver"));
    }

    /** A jar holding {@code org/postgresql/Driver.class}, whose content is not a class file: it is never loaded. */
    private static Path driverJar(Path jar) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("org/postgresql/Driver.class"));
            out.write(new byte[] {1, 2, 3});
            out.closeEntry();
        }
        return jar;
    }
}
