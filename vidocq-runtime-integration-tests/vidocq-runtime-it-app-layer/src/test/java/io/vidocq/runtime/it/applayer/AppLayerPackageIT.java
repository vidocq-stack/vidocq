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
package io.vidocq.runtime.it.applayer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** vidocq:package under all: the library ships in app/, and the script's launch finds the resource. */
class AppLayerPackageIT {

    @Test
    void theDistributionShipsTheLibraryInApp(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        Fixture.install(project, Fixture.logs().resolve("package-install.log"));
        Path log = Fixture.logs().resolve("package-all.log");

        Process mvn = Fixture.start(project.resolve("app"), log, "package", "vidocq:package",
                "-Dvidocq.app.layer=all", "-DskipTests");
        assertEquals(0, mvn.waitFor(), () -> Fixture.read(log));

        Path script = script(project.resolve("app/target"));
        Path dist = script.getParent().getParent();
        assertTrue(Files.exists(dist.resolve("app/app-layer-pages-1.0.jar")), "the library is the application's");
        assertTrue(Files.notExists(dist.resolve("lib/app-layer-pages-1.0.jar")));

        Path runLog = Fixture.logs().resolve("package-run.log");
        Process run = new ProcessBuilder("sh", script.toString()).redirectErrorStream(true)
                .redirectOutput(runLog.toFile()).start();
        try {
            Fixture.awaitLog(run, runLog, "LOOKUP-FOUND");
        } finally {
            Fixture.stop(run);
        }
    }

    private static Path script(Path target) throws Exception {
        try (Stream<Path> files = Files.walk(target, 3)) {
            List<Path> scripts = files.filter(p -> p.getFileName().toString().endsWith(".sh")
                    && p.getParent().getFileName().toString().equals("bin")).toList();
            assertEquals(1, scripts.size(), () -> "one launch script expected under " + target + ": " + scripts);
            return scripts.get(0);
        }
    }
}
