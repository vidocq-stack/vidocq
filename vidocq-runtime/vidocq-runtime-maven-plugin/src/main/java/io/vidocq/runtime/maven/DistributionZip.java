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

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes the {@code -dist.zip} of {@code vidocq:package} with the Unix mode of each entry, through the JDK's
 * {@code jdk.zipfs} file system: {@code java.util.zip.ZipOutputStream} has no API for it, and an archive without a
 * mode gave {@code unzip} a launcher it could not execute (BUG-20261116-02).
 */
final class DistributionZip {

    /** The launcher's mode when the build machine has no POSIX permissions to copy: its owner runs it. */
    private static final Set<PosixFilePermission> OWNER_EXECUTABLE = PosixFilePermissions.fromString("rwx------");

    private DistributionZip() {
    }

    /**
     * Archives every regular file below {@code sourceDir} under an entry named after its path from the parent of
     * {@code sourceDir}, so that the archive unpacks into one directory. Each entry keeps the POSIX permissions of
     * its staged file, so the archive unpacks as the staged directory is: the launcher {@code vidocq:package} made
     * executable stays executable. On a file system without POSIX permissions (Windows), only the shell launchers
     * of {@code bin/} get a mode, {@code rwx------}, and the other entries take {@code unzip}'s default.
     */
    static void write(Path sourceDir, Path zipFile) throws IOException {
        write(sourceDir, zipFile, sourceDir.getFileSystem().supportedFileAttributeViews().contains("posix"));
    }

    /** {@link #write(Path, Path)}, told whether the build machine's file system has POSIX permissions. */
    static void write(Path sourceDir, Path zipFile, boolean posix) throws IOException {
        Files.deleteIfExists(zipFile);
        Path parent = sourceDir.toAbsolutePath().getParent();
        List<Path> files;
        try (var walker = Files.walk(sourceDir)) {
            files = walker.filter(Files::isRegularFile).sorted().toList();
        }
        try (FileSystem archive = FileSystems.newFileSystem(zipFile,
                Map.of("create", "true", "enablePosixFileAttributes", "true"))) {
            for (Path file : files) {
                Path relative = parent.relativize(file.toAbsolutePath());
                Path entry = archive.getPath(relative.toString().replace('\\', '/'));
                if (entry.getParent() != null) {
                    Files.createDirectories(entry.getParent());
                }
                Files.copy(file, entry);
                if (posix) {
                    Files.setPosixFilePermissions(entry, Files.getPosixFilePermissions(file));
                } else if (isLauncher(relative)) {
                    Files.setPosixFilePermissions(entry, OWNER_EXECUTABLE);
                }
            }
        }
    }

    /** A {@code .sh} file directly in the distribution's {@code bin/} directory. */
    private static boolean isLauncher(Path relative) {
        return relative.getNameCount() == 3 && "bin".equals(relative.getName(1).toString())
                && relative.getFileName().toString().endsWith(".sh");
    }
}
