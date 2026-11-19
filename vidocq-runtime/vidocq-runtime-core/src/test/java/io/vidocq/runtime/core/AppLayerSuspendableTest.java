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
package io.vidocq.runtime.core;

import io.vidocq.vauban.classloader.VaubanClassLoader;
import io.vidocq.vauban.classloader.spi.PluginContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The class loader of the application layer keeps the application jar open, which a process snapshot refuses:
 * {@link AppLayerSuspendable} lets go of it on suspend and takes it again on resume.
 */
class AppLayerSuspendableTest {

    private static Path jar(Path file, String entry, String content) throws IOException {
        try (var out = new JarOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new JarEntry(entry));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return file;
    }

    private static String read(URL url) throws IOException {
        try (var in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void suspendReleasesTheJar_resumeTakesItAgain(@TempDir Path dir) throws Exception {
        var path = jar(dir.resolve("app.jar"), "a.txt", "first");
        try (var loader = VaubanClassLoader.of(List.of(path), getClass().getClassLoader(), PluginContext.empty())) {
            var suspendable = new AppLayerSuspendable(() -> loader);
            assertEquals("first", read(loader.getResource("a.txt")));
            Files.move(jar(dir.resolve("next.jar"), "b.txt", "second"), path, StandardCopyOption.REPLACE_EXISTING);
            assertNull(loader.getResource("b.txt"), "control: the open jar is still the old file");

            suspendable.suspend();
            suspendable.resume();

            assertNotNull(loader.getResource("b.txt"), "the jar was let go of, then opened again from its path");
        }
    }

    @Test
    void withoutAnApplicationLayerThereIsNothingToDo() {
        var suspendable = new AppLayerSuspendable(() -> null);

        assertDoesNotThrow(suspendable::suspend);
        assertDoesNotThrow(suspendable::resume);
    }

    @Test
    void aFailureIsReportedAsAnUncheckedException(@TempDir Path dir) throws Exception {
        var path = jar(dir.resolve("app.jar"), "a.txt", "first");
        var loader = VaubanClassLoader.of(List.of(path), getClass().getClassLoader(), PluginContext.empty());
        loader.close();

        var suspendable = new AppLayerSuspendable(() -> loader);

        assertDoesNotThrow(suspendable::suspend);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, suspendable::resume);
    }
}
