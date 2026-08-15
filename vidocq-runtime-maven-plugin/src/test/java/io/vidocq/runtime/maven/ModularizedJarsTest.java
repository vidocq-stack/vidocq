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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModularizedJarsTest {

    @TempDir
    Path tmp;

    @Test
    void resolveReturnsOriginalWhenNoModularizedCopyExists() throws IOException {
        Path buildDir = tmp.resolve("target");
        Path original = Files.createFile(tmp.resolve("langchain4j-open-ai-1.17.1.jar"));

        assertEquals(original, ModularizedJars.resolve(buildDir, original));
        assertFalse(ModularizedJars.isModularized(buildDir, original));
    }

    @Test
    void resolvePrefersModularizedCopyWithSameFileName() throws IOException {
        Path buildDir = tmp.resolve("target");
        Path original = Files.createFile(tmp.resolve("langchain4j-open-ai-1.17.1.jar"));
        Path patched = ModularizedJars.root(buildDir).resolve("langchain4j-open-ai-1.17.1.jar");
        Files.createDirectories(patched.getParent());
        Files.createFile(patched);

        assertEquals(patched, ModularizedJars.resolve(buildDir, original));
        assertTrue(ModularizedJars.isModularized(buildDir, original));
        assertEquals(buildDir.resolve("vidocq-modularized"), ModularizedJars.root(buildDir));
    }
}
