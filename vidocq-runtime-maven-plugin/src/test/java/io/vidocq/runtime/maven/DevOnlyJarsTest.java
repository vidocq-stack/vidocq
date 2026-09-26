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

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevOnlyJarsTest {

    static Path jar(Path dir, String name, String devOnly) throws Exception {
        Path jar = dir.resolve(name);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (devOnly != null) {
            manifest.getMainAttributes().putValue(DevOnlyJars.MANIFEST_ENTRY, devOnly);
        }
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jarOut = new JarOutputStream(out, manifest)) {
            jarOut.putNextEntry(new ZipEntry("a/A.class"));
            jarOut.closeEntry();
        }
        return jar;
    }

    @Test
    void aMarkedJarIsDevOnlyInAnyCase(@TempDir Path dir) throws Exception {
        assertTrue(DevOnlyJars.isDevOnly(jar(dir, "a.jar", "true")));
        assertTrue(DevOnlyJars.isDevOnly(jar(dir, "b.jar", "TRUE")));
        assertFalse(DevOnlyJars.isDevOnly(jar(dir, "c.jar", "false")));
        assertFalse(DevOnlyJars.isDevOnly(jar(dir, "d.jar", null)));
    }

    @Test
    void aJarWithoutManifestOrUnreadableIsNotDevOnly(@TempDir Path dir) throws Exception {
        Path noManifest = dir.resolve("e.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(noManifest))) {
            zip.putNextEntry(new ZipEntry("a/A.class"));
            zip.closeEntry();
        }
        Path garbage = Files.writeString(dir.resolve("f.jar"), "not a zip");

        assertFalse(DevOnlyJars.isDevOnly(noManifest));
        assertFalse(DevOnlyJars.isDevOnly(garbage));
        assertFalse(DevOnlyJars.isDevOnly(dir.resolve("absent.jar")));
        assertFalse(DevOnlyJars.isDevOnly(dir), "a directory, such as target/classes");
    }

    @Test
    void theWarningNamesTheArtifact() {
        assertEquals("vidocq-runtime-devconsole-extension is dev-only: not packaged; remove the dependency,"
                + " vidocq:dev brings it", DevOnlyJars.droppedWarning("vidocq-runtime-devconsole-extension"));
    }
}
