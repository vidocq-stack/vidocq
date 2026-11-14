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
package io.vidocq.runtime.it.noetherunits;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One {@code persistence.xml} per Maven module, so per Java module, under {@code vidocq:run}: the library declares the
 * unit {@code ledger} next to its entity, the application declares {@code archive} next to its own, and each unit
 * manages the entities of its own module only. Two units of one name fail (Noether BUG-20261113-06). The
 * {@code vidocq.persistence.*} keys declare units too, read at build from the application's {@code vidocq.properties}
 * for the beans the build freezes, and at start from the whole configuration (Vidocq/vidocq#551).
 */
class UnitsPerModuleIT {

    private static final String DUPLICATE = "Persistence unit ledger is declared 2 times";

    @Test
    void eachUnitManagesTheEntitiesOfItsOwnModule(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        String cache = Fixture.cacheDir("units");
        Path install = Fixture.logs().resolve("units-install.log");
        Fixture.install(project, install, cache);
        // Noether's extension and Mansart Transactions' ran at build: the start loads what they did.
        assertEquals(1, Fixture.lines(install, "Vauban: froze 2 build compatible extension(s)").size(),
                Fixture.read(install));

        Path log = Fixture.logs().resolve("units-run.log");
        Process mvn = Fixture.start(project.resolve("app"), log, "vidocq:run", cache);
        try {
            // A Startup observer writes through both units, in a @Transactional method.
            Fixture.awaitLog(mvn, log, "Vidocq - Started in");
            assertEquals(List.of("UNITS ledger=[acme.ledger.Entry] archive=[acme.app.Note] entries=1 notes=1"),
                    Fixture.lines(log, "UNITS "), Fixture.read(log));
            // Each unit once, though noether-jpa-cdi gives each two factory beans on one factory.
            assertEquals(1, Fixture.lines(log, "Noether Persistence: persistence units [archive, ledger] started")
                    .size(), Fixture.read(log));
            // The EntityManager and EntityManagerFactory synthetic beans are proxied by classes the build generated,
            // not by java.lang.reflect.Proxy (Vauban BUG-20261113-19).
            assertEquals(List.of(), Fixture.lines(log, "RUNTIME CODE GENERATION FALLBACK"), Fixture.read(log));
        } finally {
            Fixture.stop(mvn);
        }
    }

    /** The keys of {@code vidocq.properties} reach the build: the frozen beans are those of the unit they declare. */
    @Test
    void aUnitTheKeysDeclareIsFrozenWithTheOthers(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        Fixture.addApplicationKeys(project, "vidocq.persistence.journal.module=acme.ledger");
        String cache = Fixture.cacheDir("keys");
        Path install = Fixture.logs().resolve("keys-install.log");
        Fixture.install(project, install, cache);
        assertEquals(1, Fixture.lines(install, "Vauban: froze 2 build compatible extension(s)").size(),
                Fixture.read(install));

        Path log = Fixture.logs().resolve("keys-run.log");
        Process mvn = Fixture.start(project.resolve("app"), log, "vidocq:run", cache);
        try {
            Fixture.awaitLog(mvn, log, "Vidocq - Started in");
            assertEquals(1, Fixture.lines(log,
                    "Noether Persistence: persistence units [archive, journal, ledger] started").size(),
                    Fixture.read(log));
        } finally {
            Fixture.stop(mvn);
        }
    }

    /**
     * Decision 3 of Vidocq/vidocq#551: a key the build did not see that would change the beans fails the start, and
     * asks for a rebuild. A {@code -Dvidocq.*} of {@code vidocq:run} reaches the application, not the build.
     */
    @Test
    void aUnitDeclaredAtStartOnlyFailsTheStart(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        String cache = Fixture.cacheDir("start-only");
        Fixture.install(project, Fixture.logs().resolve("start-only-install.log"), cache);

        Path log = Fixture.logs().resolve("start-only-run.log");
        Process mvn = Fixture.start(project.resolve("app"), log, "vidocq:run", cache,
                "-Dvidocq.persistence.extra.classes=acme.app.Note");
        try {
            assertTrue(mvn.waitFor(Fixture.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), Fixture.read(log));
            assertNotEquals(0, mvn.exitValue(), Fixture.read(log));
            String run = Fixture.read(log);
            assertTrue(run.contains("persistence unit extra is declared at start, not when the beans were built"),
                    run);
            assertTrue(run.contains("rebuild the application"), run);
        } finally {
            Fixture.stop(mvn);
        }
    }

    @Test
    void twoUnitsOfOneNameFailTheStart(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        Fixture.renameApplicationUnit(project, "archive", "ledger");
        String cache = Fixture.cacheDir("duplicate");
        Path install = Fixture.logs().resolve("duplicate-install.log");
        Fixture.install(project, install, cache);
        // Noether's extension fails at build, so the build leaves the extensions to the start, and says why.
        List<String> unfrozen = Fixture.lines(install, "Vauban: the build compatible extensions are not frozen");
        assertEquals(1, unfrozen.size(), Fixture.read(install));
        assertTrue(Fixture.read(install).contains(DUPLICATE), Fixture.read(install));

        Path log = Fixture.logs().resolve("duplicate-run.log");
        Process mvn = Fixture.start(project.resolve("app"), log, "vidocq:run", cache);
        try {
            assertTrue(mvn.waitFor(Fixture.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), Fixture.read(log));
            assertNotEquals(0, mvn.exitValue(), Fixture.read(log));
            // What the application printed, after the build vidocq:run forked, which warned again.
            String all = Fixture.read(log);
            int started = all.indexOf("Vidocq run: started");
            assertTrue(started >= 0, all);
            String run = all.substring(started);
            assertTrue(run.contains(DUPLICATE), all);
            assertTrue(run.contains("app/target/classes/META-INF/persistence.xml"), run);
            assertTrue(run.contains("units-per-module-ledger-1.0.jar!/META-INF/persistence.xml"), run);
        } finally {
            Fixture.stop(mvn);
        }
    }
}
