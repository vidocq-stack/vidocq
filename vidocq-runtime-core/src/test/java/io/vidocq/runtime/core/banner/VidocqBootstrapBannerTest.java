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
package io.vidocq.runtime.core.banner;

import io.vidocq.runtime.core.BannerMode;
import io.vidocq.runtime.core.VidocqBootstrap;
import io.vidocq.runtime.core.banner.BannerTestSupport.Out;
import io.vidocq.runtime.core.banner.BannerTestSupport.Records;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintStream;
import java.util.List;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The banner as {@link VidocqBootstrap#configure()} emits it. */
class VidocqBootstrapBannerTest {

    private PrintStream stdout;

    @BeforeEach
    void forgetTheBanner() {
        StartupBanner.reset();
        stdout = System.out;
    }

    @AfterEach
    void restore() {
        System.setOut(stdout);
        StartupBanner.reset();
    }

    @Test
    void offWritesNothing() {
        // create() installs the console log handler on the real standard output first
        VidocqBootstrap bootstrap = VidocqBootstrap.create();
        Out out = new Out();
        try (Records records = new Records()) {
            System.setOut(out.stream);

            bootstrap.banner(BannerMode.OFF).configure();

            System.setOut(stdout);
            assertEquals("", out.text());
            assertTrue(records.messages(Level.INFO).isEmpty(), records.messages(Level.INFO).toString());
            assertTrue(StartupBanner.emittedIdentity().isEmpty());
        }
    }

    @Test
    void anEmbeddedDeploymentGetsOneInfoLineAndNothingOnStandardOutput() {
        VidocqBootstrap bootstrap = VidocqBootstrap.create();
        Out out = new Out();
        try (Records records = new Records()) {
            System.setOut(out.stream);

            bootstrap.configure(List.of());

            System.setOut(stdout);
            assertEquals("", out.text());
            List<String> identity = records.identityRecords();
            assertEquals(1, identity.size(), identity.toString());
            assertTrue(identity.getFirst().startsWith("Vidocq "), identity.getFirst());
            assertTrue(identity.getFirst().contains(" | Java " + Runtime.version()), identity.getFirst());
        }
    }

    @Test
    void theDevReloadLoopConfiguresAgainWithoutASecondBanner() {
        try (Records records = new Records()) {
            VidocqBootstrap.create().configure();
            VidocqBootstrap.create().configure();

            assertEquals(1, records.identityRecords().size(), records.identityRecords().toString());
        }
    }
}
