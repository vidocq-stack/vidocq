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
package io.vidocq.runtime.cli.ext;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Temporary files readable and writable by their owner only. NIO already creates them so on a POSIX file system;
 * the permissions are spelled out so the shared temporary directory is visibly used safely. Elsewhere (Windows) the
 * file system's own defaults apply.
 */
public final class TempFiles {

    private TempFiles() {
    }

    /** Whether the default file system has POSIX permissions, which owner-only files then spell out. */
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    /** {@link Files#createTempFile(String, String, FileAttribute[])}, owner-only. */
    public static Path createFile(String prefix, String suffix) throws IOException {
        return POSIX
                ? Files.createTempFile(prefix, suffix, OWNER_ONLY)
                : Files.createTempFile(prefix, suffix, new FileAttribute<?>[0]);
    }
}
