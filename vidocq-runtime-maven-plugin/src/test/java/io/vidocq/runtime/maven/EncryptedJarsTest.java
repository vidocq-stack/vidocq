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
package io.vidocq.runtime.maven;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("vidocq:jlink — an encrypted jar cannot go into an image")
class EncryptedJarsTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("the encrypted jars of a directory are found by their header, sorted by name")
    void theEncryptedJarsAreFoundByTheirHeader() throws Exception {
        jar("plain.jar", "acme/Plain.class");
        jar("secret-b.jar", "META-INF/vauban.header", "META-INF/vauban/1f0e");
        jar("secret-a.jar", "META-INF/vauban.header");
        Files.writeString(dir.resolve("notes.txt"), "not a jar");
        Files.createDirectories(dir.resolve("exploded").resolve("META-INF"));

        assertEquals(List.of("secret-a.jar", "secret-b.jar"), EncryptedJars.find(dir));
    }

    @Test
    @DisplayName("a directory without an encrypted jar finds none")
    void aDirectoryWithoutAnEncryptedJarFindsNone() throws Exception {
        jar("plain.jar", "acme/Plain.class");

        assertEquals(List.of(), EncryptedJars.find(dir));
    }

    @Test
    @DisplayName("the failure names the jars, the reason, and the distribution that runs them")
    void theFailureNamesTheJarsAndTheWayOut() {
        String message = EncryptedJars.failureMessage(List.of("secret-a.jar", "secret-b.jar"));

        assertTrue(message.contains("secret-a.jar, secret-b.jar"), message);
        assertTrue(message.contains("application layer"), message);
        assertTrue(message.contains("vidocq:package"), message);
        assertTrue(message.contains("-Dvidocq.docker.kind=jdk"), message);
    }

    private void jar(String name, String... entries) throws IOException {
        try (var out = new JarOutputStream(Files.newOutputStream(dir.resolve(name)))) {
            for (String entry : entries) {
                out.putNextEntry(new JarEntry(entry));
                out.write(1);
                out.closeEntry();
            }
        }
    }
}
