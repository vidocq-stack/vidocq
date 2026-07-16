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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpmsPatchesTest {

    @TempDir
    Path tmp;

    @Test
    void relocateMovesCrossModuleClassesAndPrunesEmptyPackages() throws IOException {
        Path depJar = jar("dep.jar", "com/acme/lib/Bean.class");
        Path classesDir = tmp.resolve("classes");
        // generated cross-module proxy (package owned by dep.jar) + a project-owned class
        write(classesDir.resolve("com/acme/lib/Bean_ClientProxy.class"));
        write(classesDir.resolve("com/acme/lib/Bean_ClientProxy$1.class"));
        write(classesDir.resolve("com/acme/app/Own_ClientProxy.class"));
        Path buildDir = tmp.resolve("target");

        Map<String, Integer> relocated = JpmsPatches.relocate(classesDir, buildDir,
                Map.of(depJar, "my-lib"),
                List.of("com.acme.lib.Bean_ClientProxy", "com.acme.app.Own_ClientProxy"));

        assertEquals(Map.of("my-lib", 2), relocated);
        Path patchDir = JpmsPatches.patchDirFor(buildDir, "my-lib");
        assertTrue(Files.isRegularFile(patchDir.resolve("com/acme/lib/Bean_ClientProxy.class")));
        assertTrue(Files.isRegularFile(patchDir.resolve("com/acme/lib/Bean_ClientProxy$1.class")));
        // project-owned class untouched, foreign package pruned from classes
        assertTrue(Files.isRegularFile(classesDir.resolve("com/acme/app/Own_ClientProxy.class")));
        assertFalse(Files.exists(classesDir.resolve("com/acme/lib")));
    }

    @Test
    void enrichAddsPatchClassesWithoutTouchingExistingEntries() throws IOException {
        Path srcJar = jar("lib.jar", "module-info.class", "com/acme/lib/Bean.class");
        Path patchDir = tmp.resolve("patch");
        write(patchDir.resolve("com/acme/lib/Bean_ClientProxy.class"));
        Path outJar = tmp.resolve("out/lib.jar");

        JpmsPatches.enrich(srcJar, patchDir, outJar);

        Set<String> entries = entriesOf(outJar);
        assertEquals(Set.of("module-info.class", "com/acme/lib/Bean.class",
                "com/acme/lib/Bean_ClientProxy.class"), entries);
    }

    @Test
    void packagesOfReadsJarPackages() throws IOException {
        Path depJar = jar("dep.jar", "com/acme/lib/Bean.class", "com/acme/lib/sub/Other.class");
        assertEquals(Set.of("com.acme.lib", "com.acme.lib.sub"), JpmsPatches.packagesOf(depJar));
    }

    private Path jar(String name, String... entries) throws IOException {
        Path jar = tmp.resolve(name);
        try (var zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (String entry : entries) {
                zos.putNextEntry(new ZipEntry(entry));
                zos.write(new byte[] {(byte) 0xCA, (byte) 0xFE});
                zos.closeEntry();
            }
        }
        return jar;
    }

    private static void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {(byte) 0xCA, (byte) 0xFE});
    }

    private static Set<String> entriesOf(Path jar) throws IOException {
        Set<String> names = new HashSet<>();
        try (var zin = new ZipInputStream(Files.newInputStream(jar))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                names.add(e.getName());
            }
        }
        return names;
    }
}
