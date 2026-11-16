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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The files {@code vidocq:package} writes into a distribution get the modes of any file the build writes: a jar it
 * rewrites is readable by whoever can read a plain file, and the launcher is executable by whoever can read it
 * (found on vaadin-on-vidocq: enriched jars came out {@code rw-------}, the launcher {@code rwxr--r--}).
 */
class StagedFilesTest {

    @TempDir
    Path dir;

    /** A temporary sibling has the mode of a plain new file of the same directory, not {@code rw-------}. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aTemporarySiblingHasTheModeOfAPlainNewFile() throws Exception {
        Path plain = Files.createFile(dir.resolve("plain.jar"));
        Path target = dir.resolve("app.jar");

        Path tmp = StagedFiles.temporarySibling(target);

        assertEquals(dir, tmp.getParent());
        assertTrue(tmp.getFileName().toString().startsWith("app.jar."), tmp.toString());
        assertEquals(mode(plain), mode(tmp));
    }

    /** Two temporary siblings of one target never collide. */
    @Test
    void twoTemporarySiblingsDiffer() throws Exception {
        Path target = dir.resolve("app.jar");

        assertNotEquals(StagedFiles.temporarySibling(target), StagedFiles.temporarySibling(target));
    }

    /** Each class of user that can read the launcher can run it: {@code rw-r--r--} becomes {@code rwxr-xr-x}. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theLauncherIsExecutableByWhoeverCanReadIt() throws Exception {
        Path launcher = Files.createFile(dir.resolve("app.sh"));
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rw-r--r--"));

        assertTrue(StagedFiles.makeExecutable(launcher));

        assertEquals("rwxr-xr-x", mode(launcher));
    }

    /** No class of user gains a right it did not have: an unreadable launcher stays unrunnable for them. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theLauncherGivesNoRightToWhoCannotReadIt() throws Exception {
        Path launcher = Files.createFile(dir.resolve("app.sh"));
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rw-r-----"));

        assertTrue(StagedFiles.makeExecutable(launcher));

        assertEquals("rwxr-x---", mode(launcher));
    }

    private static String mode(Path file) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
    }
}
