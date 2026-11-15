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
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BUG-20261116-02: the {@code -dist.zip} kept no Unix mode, so {@code bin/<name>.sh} came out of {@code unzip}
 * without its execute bit and answered {@code Permission denied}.
 */
class DistributionZipTest {

    @TempDir
    Path dir;

    /** Each entry keeps the mode of its staged file: the launcher vidocq:package made executable stays so. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void eachEntryKeepsTheModeOfItsStagedFile() throws Exception {
        Path root = distribution();
        Files.setPosixFilePermissions(root.resolve("bin/app.sh"), PosixFilePermissions.fromString("rwxr-x---"));
        Files.setPosixFilePermissions(root.resolve("bin/app.cmd"), PosixFilePermissions.fromString("rw-r-----"));
        Files.setPosixFilePermissions(root.resolve("lib/app.jar"), PosixFilePermissions.fromString("r--------"));
        Path zip = dir.resolve("app-1.0-dist.zip");

        DistributionZip.write(root, zip);

        try (FileSystem archive = FileSystems.newFileSystem(zip, Map.of("enablePosixFileAttributes", "true"))) {
            assertEquals("rwxr-x---", mode(archive.getPath("app-1.0/bin/app.sh")));
            assertEquals("rw-r-----", mode(archive.getPath("app-1.0/bin/app.cmd")));
            assertEquals("r--------", mode(archive.getPath("app-1.0/lib/app.jar")));
            assertEquals("#!/bin/sh\n", Files.readString(archive.getPath("app-1.0/bin/app.sh")));
        }
    }

    /** Without POSIX permissions to copy (a Windows build), the shell launcher still gets an execute bit. */
    @Test
    void withoutPosixPermissionsTheLauncherIsExecutableByItsOwner() throws Exception {
        Path zip = dir.resolve("app-1.0-dist.zip");

        DistributionZip.write(distribution(), zip, false);

        try (FileSystem archive = FileSystems.newFileSystem(zip, Map.of("enablePosixFileAttributes", "true"))) {
            assertEquals("rwx------", mode(archive.getPath("app-1.0/bin/app.sh")));
        }
    }

    private Path distribution() throws Exception {
        Path root = dir.resolve("app-1.0");
        Files.createDirectories(root.resolve("bin"));
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("bin/app.sh"), "#!/bin/sh\n");
        Files.writeString(root.resolve("bin/app.cmd"), "@echo off\r\n");
        Files.writeString(root.resolve("lib/app.jar"), "jar");
        return root;
    }

    /** The entries keep the layout the plain archive had: one per file, under the distribution's directory. */
    @Test
    void theArchiveHoldsEveryFileUnderTheDistributionDirectory() throws Exception {
        Path root = dir.resolve("app-1.0");
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib/a.jar"), "a");
        Files.writeString(root.resolve("app-1.0-cyclonedx.json"), "{}");
        Path zip = dir.resolve("app-1.0-dist.zip");

        DistributionZip.write(root, zip);

        List<String> files = new ArrayList<>();
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            Collections.list(archive.entries()).stream().filter(e -> !e.isDirectory())
                    .forEach(e -> files.add(e.getName()));
        }
        Collections.sort(files);
        assertEquals(List.of("app-1.0/app-1.0-cyclonedx.json", "app-1.0/lib/a.jar"), files);
    }

    private static String mode(Path entry) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(entry));
    }
}
