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

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** vidocq:run: the library sees the application's resource under all, not under classes. */
class AppLayerRunIT {

    @Test
    void allLetsTheLibrarySeeTheApplicationsResource(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        Fixture.install(project, Fixture.logs().resolve("run-install.log"));
        Path log = Fixture.logs().resolve("run-all.log");

        Process mvn = Fixture.start(project.resolve("app"), log, "vidocq:run", "-Dvidocq.app.layer=all");
        try {
            Fixture.awaitLog(mvn, log, "LOOKUP-FOUND");
            assertTrue(Fixture.read(log).contains("application layer: 1 libraries"), Fixture.read(log));
        } finally {
            Fixture.stop(mvn);
        }
    }

    @Test
    void classesIsTheControl(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        Fixture.install(project, Fixture.logs().resolve("run-classes-install.log"));
        Path log = Fixture.logs().resolve("run-classes.log");

        Process mvn = Fixture.start(project.resolve("app"), log, "vidocq:run");
        try {
            Fixture.awaitLog(mvn, log, "LOOKUP-MISSING");
            assertFalse(Fixture.read(log).contains("LOOKUP-FOUND"));
        } finally {
            Fixture.stop(mvn);
        }
    }
}
