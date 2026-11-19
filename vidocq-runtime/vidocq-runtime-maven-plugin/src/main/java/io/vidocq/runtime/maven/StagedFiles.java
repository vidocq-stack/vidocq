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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * The modes of the files {@code vidocq:package} stages into a distribution. They are those of any file the build
 * writes, as the user's umask gives them, so the distribution keeps them once unpacked by another user: a jar is
 * readable, the launcher executable, by whoever can read a plain file.
 */
final class StagedFiles {

    private StagedFiles() {
    }

    /**
     * A new, empty file next to {@code target}, to write it through and move it in place. Unlike
     * {@link Files#createTempFile}, which gives {@code rw-------}, it has the mode of a plain new file.
     */
    static Path temporarySibling(Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        while (true) {
            try {
                return Files.createFile(parent.resolve(target.getFileName() + "." + UUID.randomUUID() + ".tmp"));
            } catch (FileAlreadyExistsException _) {
                // another name
            }
        }
    }

    /**
     * Makes {@code launcher} executable by each class of user that can read it, as {@code chmod +x} does under the
     * usual umask: {@code rw-r--r--} becomes {@code rwxr-xr-x}. {@code false} when the file system refused.
     */
    static boolean makeExecutable(Path launcher) throws IOException {
        if (!launcher.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return launcher.toFile().setExecutable(true);
        }
        Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        permissions.addAll(Files.getPosixFilePermissions(launcher));
        for (PosixFilePermission permission : Set.copyOf(permissions)) {
            String name = permission.name();
            if (name.endsWith("_READ")) {
                permissions.add(PosixFilePermission.valueOf(name.replace("_READ", "_EXECUTE")));
            }
        }
        Files.setPosixFilePermissions(launcher, permissions);
        return true;
    }
}
