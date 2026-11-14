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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** vidocq:dev under all: the lookup works, and again after a reload, which names the library's leaked thread. */
class AppLayerDevIT {

    private static final String FOUND = "LOOKUP-FOUND";

    @Test
    void theLookupSurvivesAReloadAndTheLeakedThreadIsNamed(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        Fixture.install(project, Fixture.logs().resolve("dev-install.log"));
        Path log = Fixture.logs().resolve("dev-all.log");

        Process mvn = Fixture.start(project.resolve("app"), log, "vidocq:dev"); // all is the default
        try {
            Fixture.awaitLog(mvn, log, FOUND);
            Fixture.awaitLog(mvn, log, "SQL-OK");
            Path probe = project.resolve("app/src/main/java/acme/app/Probe.java");
            Files.writeString(probe, Files.readString(probe) + "\n// changed by AppLayerDevIT\n");
            Fixture.awaitLog(mvn, log, "acme-pages-poller");
            long deadline = System.currentTimeMillis() + Fixture.TIMEOUT.toMillis();
            while (Fixture.occurrences(log, FOUND) < 2) {
                if (System.currentTimeMillis() > deadline) {
                    fail("no second LOOKUP-FOUND after the reload:\n" + Fixture.read(log));
                }
                if (mvn.waitFor(250, TimeUnit.MILLISECONDS) && Fixture.occurrences(log, FOUND) < 2) {
                    fail("vidocq:dev exited with " + mvn.exitValue() + " before a second LOOKUP-FOUND:\n"
                            + Fixture.read(log));
                }
            }
            assertEquals(0, Fixture.occurrences(log, "LOOKUP-MISSING"), Fixture.read(log));
            assertEquals(0, Fixture.occurrences(log, "TOOL-PROVIDERS 2"), Fixture.read(log));
        } finally {
            Fixture.stop(mvn);
        }
    }

    /**
     * vidocq.dev.layer=false runs the fixture's trampoline main from the module path: no application layer, as the
     * switch promises, so the application module is loaded once (BUG-20261114-10).
     */
    @Test
    void theLegacyLaunchRunsTheTrampolineWithoutALayer(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        Fixture.install(project, Fixture.logs().resolve("dev-legacy-install.log"));
        Path log = Fixture.logs().resolve("dev-legacy.log");

        Process mvn = Fixture.start(project.resolve("app"), log, "vidocq:dev", "-Dvidocq.dev.layer=false");
        try {
            Fixture.awaitLog(mvn, log, "TOOL-PROVIDERS ");
            Fixture.awaitLog(mvn, log, "Vidocq - Started in");
            String out = Fixture.read(log);
            assertEquals(1, Fixture.occurrences(log, AppLayerPackageIT.ONE_COPY), out);
            assertFalse(out.contains("Application layer ready"), out);
            assertTrue(out.contains("no application layer"), out);
        } finally {
            Fixture.stop(mvn);
        }
    }
}
