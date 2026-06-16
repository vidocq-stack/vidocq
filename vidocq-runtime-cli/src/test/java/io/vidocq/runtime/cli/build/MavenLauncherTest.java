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
package io.vidocq.runtime.cli.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenLauncherTest {

    @Test
    void wrapperNamePerPlatform() {
        assertEquals("mvnw", MavenLauncher.wrapperName(false));
        assertEquals("mvnw.cmd", MavenLauncher.wrapperName(true));
    }

    @Test
    void resolvesWrapperInStartDir(@TempDir Path dir) throws IOException {
        Path wrapper = Files.createFile(dir.resolve("mvnw"));
        String resolved = MavenLauncher.resolveExecutable(dir, false);
        assertEquals(wrapper.toAbsolutePath().toString(), resolved);
    }

    @Test
    void resolvesWrapperInAncestor(@TempDir Path dir) throws IOException {
        Path wrapper = Files.createFile(dir.resolve("mvnw"));
        Path nested = Files.createDirectories(dir.resolve("a").resolve("b"));
        String resolved = MavenLauncher.resolveExecutable(nested, false);
        assertEquals(wrapper.toAbsolutePath().toString(), resolved);
    }

    @Test
    void fallsBackToMvnWhenNoWrapper(@TempDir Path dir) {
        assertEquals("mvn", MavenLauncher.resolveExecutable(dir, false));
        assertEquals("mvn.cmd", MavenLauncher.resolveExecutable(dir, true));
    }

    @Test
    void windowsResolvesCmdWrapper(@TempDir Path dir) throws IOException {
        Path wrapper = Files.createFile(dir.resolve("mvnw.cmd"));
        String resolved = MavenLauncher.resolveExecutable(dir, true);
        assertEquals(wrapper.toAbsolutePath().toString(), resolved);
    }

    @Test
    void runReturnsExitCode(@TempDir Path dir) {
        // 'true' is a POSIX no-op that exits 0; skip the assertion on platforms
        // where it is unavailable rather than failing the suite.
        if (MavenLauncher.isWindows()) {
            return;
        }
        int code = MavenLauncher.run(java.util.List.of("true"), dir);
        assertEquals(0, code);
    }

    @Test
    void runReturnsOneWhenExecutableMissing(@TempDir Path dir) {
        int code = MavenLauncher.run(
                java.util.List.of("definitely-not-a-real-binary-xyz"), dir);
        assertTrue(code != 0);
    }
}
