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
package io.vidocq.runtime.cli.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliInstallerTest {

    @TempDir
    Path dir;

    @Test
    void installsAVersionWithAnExecutableLauncherAndItsBuild() throws IOException {
        Path root = dir.resolve("cli");
        Path zip = zip(Map.of("bin/vidocq", "#!/bin/sh\n", "modules/a.jar", "v1"));

        Path installed = CliInstaller.install(zip, root, "0.4.0-SNAPSHOT", Optional.of("0.4.0-20261006.1-63"));

        assertEquals(root.resolve("0.4.0-SNAPSHOT"), installed);
        assertTrue(Files.isExecutable(installed.resolve("bin/vidocq")));
        assertEquals(Optional.of("0.4.0-20261006.1-63"), CliInstaller.installedBuild(installed));
    }

    @Test
    void reinstallingAVersionReplacesItsContentAndLeavesNothingBehind() throws IOException {
        Path root = dir.resolve("cli");
        CliInstaller.install(zip(Map.of("bin/vidocq", "", "modules/old.jar", "v1")), root, "0.4.0-SNAPSHOT",
                Optional.of("b1"));

        Path installed = CliInstaller.install(zip(Map.of("bin/vidocq", "", "modules/new.jar", "v2")), root,
                "0.4.0-SNAPSHOT", Optional.of("b2"));

        assertFalse(Files.exists(installed.resolve("modules/old.jar")));
        assertTrue(Files.exists(installed.resolve("modules/new.jar")));
        assertEquals(Optional.of("b2"), CliInstaller.installedBuild(installed));
        try (var entries = Files.list(root)) {
            assertEquals(1, entries.count(), "no staging or backup directory may remain");
        }
    }

    @Test
    void releaseHasNoBuildMarker() throws IOException {
        Path installed = CliInstaller.install(zip(Map.of("bin/vidocq", "")), dir.resolve("cli"), "0.4.0",
                Optional.empty());

        assertEquals(Optional.empty(), CliInstaller.installedBuild(installed));
    }

    @Test
    void entryEscapingTheTargetIsRejected() throws IOException {
        Path zip = zip(Map.of("bin/vidocq", "", "../evil", "x"));

        assertThrows(IOException.class, () -> CliInstaller.install(zip, dir.resolve("cli"), "0.4.0", Optional.empty()));
        assertFalse(Files.exists(dir.resolve("evil")));
        assertFalse(Files.exists(dir.resolve("cli/0.4.0")), "a failed install leaves no version behind");
    }

    @Test
    void archiveWithoutLauncherIsRejected() throws IOException {
        Path zip = zip(Map.of("modules/a.jar", ""));

        assertThrows(IOException.class, () -> CliInstaller.install(zip, dir.resolve("cli"), "0.4.0", Optional.empty()));
    }

    @Test
    void launcherIsRetargetedOnlyWhenItIsTheInstallersWrapper() {
        String wrapper = "#!/bin/sh\nexec \"/home/me/.vidocq/cli/0.3.0/bin/vidocq\" \"$@\"\n";

        assertEquals(Optional.of("#!/bin/sh\nexec \"/home/me/.vidocq/cli/0.4.0/bin/vidocq\" \"$@\"\n"),
                CliInstaller.retargetLauncher(wrapper, Path.of("/home/me/.vidocq/cli/0.4.0")));
        assertEquals(Optional.empty(), CliInstaller.retargetLauncher("#!/bin/sh\necho custom\n",
                Path.of("/x")));
    }

    private Path zip(Map<String, String> entries) throws IOException {
        Path zip = Files.createTempFile(dir, "cli", ".zip");
        try (OutputStream out = Files.newOutputStream(zip); var zos = new ZipOutputStream(out)) {
            for (var e : new LinkedHashMap<>(entries).entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return zip;
    }
}
