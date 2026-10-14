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
package io.vidocq.runtime.it.depgen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code @WebListener} in a plain dependency jar runs under {@code vidocq:run}, with no subclass of it in the
 * application, because {@code vidocq:generate} ran the Foy codegen bundle's processor over the jar.
 */
class ListenerFromADependencyRunsIT {

    /** The startup report counts the archives of the application layer: the library is in it under all. */
    private static final String LAYER = "-Dvidocq.app.layer=all";

    private static final String NO_CHECKPOM = "-Dvidocq.checkpom.failOnMissing=false";

    @Test
    void theListenerRunsAndTheReportCountsTheEnrichedJar(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        String cache = Fixture.cacheDir("runs");
        Path install = Fixture.logs().resolve("runs-install.log");
        Fixture.install(project, install, cache);
        List<String> generated = Fixture.lines(install, "vidocq:generate: ", "weblib", "— Foy (");
        assertEquals(1, generated.size(), Fixture.read(install));
        assertTrue(generated.getFirst().contains("generated)"), generated.getFirst());

        Path log = Fixture.logs().resolve("runs-run.log");
        Process mvn = Fixture.start(project.resolve("app"), log, "vidocq:run", LAYER, cache,
                "-Dvidocq.chappe.listener.default.port=" + Fixture.freePort());
        try {
            // Foy starts the listeners before Vidocq reports it started.
            Fixture.awaitLog(mvn, log, "Vidocq - Started in");
            assertTrue(Fixture.read(log).contains("LISTENER-RAN"), Fixture.read(log));
            assertEquals(1, Fixture.lines(log, "layer ", "1 enriched (Foy 1)").size(), Fixture.read(log));
        } finally {
            Fixture.stop(mvn);
        }
    }

    @Test
    void aSecondBuildHitsTheCache(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        String cache = Fixture.cacheDir("cache");
        Fixture.install(project, Fixture.logs().resolve("cache-install.log"), cache);

        Path log = Fixture.logs().resolve("cache-second.log");
        Fixture.awaitSuccess(Fixture.start(project.resolve("app"), log, "process-classes", cache), log,
                "the second build");
        List<String> lines = Fixture.lines(log, "vidocq:generate: ", "weblib", "— Foy (");
        assertEquals(1, lines.size(), Fixture.read(log));
        assertTrue(lines.getFirst().contains("cached)"), lines.getFirst());
    }

    @Test
    void withoutTheCodegenBundleTheListenerNeverRuns(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        Fixture.withoutCodegenBundle(project);
        String cache = Fixture.cacheDir("control");
        Path install = Fixture.logs().resolve("control-install.log");
        // vidocq:checkpom fails a build that declares the extension without its codegen bundle: the point here.
        Fixture.awaitSuccess(Fixture.start(project, install, "install", "-DskipTests", cache, NO_CHECKPOM), install,
                "the fixture build");
        assertEquals(List.of(), Fixture.lines(install, "vidocq:generate: ", "— Foy ("));

        Path log = Fixture.logs().resolve("control-run.log");
        Process mvn = Fixture.start(project.resolve("app"), log, "vidocq:run", LAYER, cache, NO_CHECKPOM,
                "-Dvidocq.chappe.listener.default.port=" + Fixture.freePort());
        try {
            Fixture.awaitLog(mvn, log, "Vidocq - Started in");
            assertFalse(Fixture.read(log).contains("LISTENER-RAN"), Fixture.read(log));
            assertEquals(List.of(), Fixture.lines(log, "enriched ("));
        } finally {
            Fixture.stop(mvn);
        }
    }
}
