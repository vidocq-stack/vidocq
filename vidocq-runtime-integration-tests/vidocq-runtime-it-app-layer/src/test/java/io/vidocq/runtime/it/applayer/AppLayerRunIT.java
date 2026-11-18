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

/** vidocq:run: the library sees the application's resource under all, the default, and not under classes. */
class AppLayerRunIT {

    /** The start of the last line the fixture's {@code placed} application prints, about its client proxy. */
    static final String PLACED_PROXY = "GADGET-PROXY ";

    @Test
    void allIsTheDefaultAndLetsTheLibrarySeeTheApplicationsResource(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        Fixture.install(project, Fixture.logs().resolve("run-install.log"));
        Path log = Fixture.logs().resolve("run-all.log");

        Process mvn = Fixture.start(project.resolve("app"), log, "vidocq:run");
        try {
            Fixture.awaitLog(mvn, log, "LOOKUP-FOUND");
            assertTrue(Fixture.read(log).contains("application layer: 2 libraries,"), Fixture.read(log));
            Fixture.awaitLog(mvn, log, "SQL-OK"); // acme.sql requires java.sql, which only it needs (vidocq#254)
        } finally {
            Fixture.stop(mvn);
        }
    }

    @Test
    void classesIsTheControl(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        Fixture.install(project, Fixture.logs().resolve("run-classes-install.log"));
        Path log = Fixture.logs().resolve("run-classes.log");

        Process mvn = Fixture.start(project.resolve("app"), log, "vidocq:run", "-Dvidocq.app.layer=classes");
        try {
            Fixture.awaitLog(mvn, log, "LOOKUP-MISSING");
            assertFalse(Fixture.read(log).contains("LOOKUP-FOUND"));
        } finally {
            Fixture.stop(mvn);
        }
    }

    /**
     * A producer of a third-party class in a normal scope, when that class has a package-private member: the
     * application layer defines the client proxy in the library's own package (Vauban's placed proxy), the only
     * place from which a class overrides that member. Vauban's loader gives the class a constructor for the proxy,
     * so the library's own constructors run for the produced gadget only.
     */
    @Test
    void theApplicationLayerHoldsTheProxyOfAThirdPartyClassInItsPackage(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        Fixture.install(project, Fixture.logs().resolve("run-placed-install.log"));
        Path log = Fixture.logs().resolve("run-placed.log");

        Process mvn = Fixture.start(project.resolve("placed"), log, "vidocq:run");
        try {
            Fixture.awaitLog(mvn, log, PLACED_PROXY);
            String out = Fixture.read(log);
            assertPlacedProxy(out);
            assertTrue(out.contains("GADGET-CONSTRUCTED 1"), "no constructor of the library runs for the proxy:\n"
                    + out);
        } finally {
            Fixture.stop(mvn);
        }
    }

    /**
     * What the fixture's {@code placed} application printed: the library called its package-private member on the
     * proxy and got the answer of the produced instance, and the proxy is a class of the library's package.
     */
    static void assertPlacedProxy(String out) {
        assertTrue(out.contains("GADGET-LABEL placed:x"), "the proxy forwards the package-private member:\n" + out);
        assertTrue(out.contains(PLACED_PROXY + "acme.gadget.Gadget_ClientProxy in acme.gadget"),
                "the proxy is a class of the library's package:\n" + out);
        // A class whose only constructor refuses null: its proxy runs no constructor of it (BUG-20261118-91).
        assertTrue(out.contains("STRICT-NAME strict constructed 1"), "the proxy runs no constructor of Strict:\n"
                + out);
    }
}
