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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * vidocq:package: under all the library ships in app/ and the packaged launch resolves it into the application
 * layer, where it sees the application's resource; under classes it stays in lib/, outside that layer. The fixture
 * sets vidocq.mainModule and vidocq.mainClass, the shape most applications have.
 */
class AppLayerPackageIT {

    private static final String STARTED = "Vidocq - Started in";
    /** The name of the loader Vauban defines the application layer's modules with. */
    private static final String LAYER_LOADER = "vauban-app-layer";

    @Test
    void allShipsTheLibraryInAppAndLaunchesItInTheApplicationLayer(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        Fixture.install(project, Fixture.logs().resolve("package-install.log"));
        Path log = Fixture.logs().resolve("package-all.log");

        Fixture.awaitSuccess(Fixture.start(project.resolve("app"), log, "package", "vidocq:package",
                "-Dvidocq.app.layer=all", "-DskipTests"), log, "vidocq:package");

        Path script = script(project.resolve("app/target"));
        Path dist = script.getParent().getParent();
        assertTrue(Files.exists(dist.resolve("app/app-layer-pages-1.0.jar")), "the library is the application's");
        assertTrue(Files.notExists(dist.resolve("lib/app-layer-pages-1.0.jar")));
        assertTrue(Files.readString(script).contains("-Dvidocq.app.path="), Files.readString(script));

        Path runLog = Fixture.logs().resolve("package-run.log");
        String out = run(script, runLog);
        // The loader's name, not the URL's scheme: Vauban gives a plain jar a jar: URL (Vidocq/vauban#178)
        assertTrue(out.contains("LOOKUP-LOADER " + LAYER_LOADER), "the library's loader is the application layer's:\n" + out);
        assertTrue(out.contains("LOOKUP-FOUND "), "it sees the application's resource:\n" + out);
        assertTrue(out.contains("3 modules, 2 libraries"), "the report counts the libraries in the layer:\n" + out);
        assertTrue(out.contains("SQL-OK"), "a JDK module only an app/ module requires is resolved (vidocq#254):\n" + out);
    }

    @Test
    void classesIsTheControl(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        Fixture.install(project, Fixture.logs().resolve("package-classes-install.log"));
        Path log = Fixture.logs().resolve("package-classes.log");

        Fixture.awaitSuccess(Fixture.start(project.resolve("app"), log, "package", "vidocq:package", "-DskipTests"),
                log, "vidocq:package");

        Path script = script(project.resolve("app/target"));
        Path dist = script.getParent().getParent();
        assertTrue(Files.exists(dist.resolve("lib/app-layer-pages-1.0.jar")), "the library stays in lib/");
        assertTrue(Files.notExists(dist.resolve("app/app-layer-pages-1.0.jar")));

        Path runLog = Fixture.logs().resolve("package-classes-run.log");
        String out = run(script, runLog);
        assertFalse(out.contains("LOOKUP-LOADER " + LAYER_LOADER), out);
        // acme.sql, an explicit module the application reads, moves with it; the automatic library stays out.
        assertTrue(out.contains("2 modules, 1 library (boot-layer"), "the automatic library is outside the layer:\n" + out);
        assertTrue(out.contains("SQL-OK"), out);
    }

    /** Launches the distribution's script until the runtime has started; returns what it printed. */
    private static String run(Path script, Path runLog) throws Exception {
        Process run = new ProcessBuilder("sh", script.toString()).redirectErrorStream(true)
                .redirectOutput(runLog.toFile()).start();
        try {
            Fixture.awaitLog(run, runLog, STARTED);
            return Fixture.read(runLog);
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
