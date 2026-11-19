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
package io.vidocq.runtime.it.ravelconfig;

import io.vidocq.runtime.core.VidocqBootstrap;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ravel#21: {@code @Inject @ConfigProperty String} compiled with the Vauban processor's
 * validation on, then injected at boot from the deployment's {@code vidocq.properties}.
 * That the main sources compiled at all is the first half of the assertion.
 */
class ConfigPropertyInjectionTest {

    @Test
    void runsAsNamedModule() {
        // Applications are modules: a silent fallback to the class path would test something else.
        assertTrue(MenuService.class.getModule().isNamed(),
                "this IT must run on the module path, not the class path");
    }

    @Test
    void injectsTheConfigAtBoot() {
        VidocqBootstrap bootstrap = VidocqBootstrap.create().configure().start();
        try {
            assertEquals("GaufreStack",
                    CDI.current().select(MenuService.class).get().config().getValue("shop.name", String.class));
        } finally {
            bootstrap.shutdown();
        }
    }

    @Test
    void injectsTheConfiguredValueAtBoot() {
        VidocqBootstrap bootstrap = VidocqBootstrap.create().configure().start();
        try {
            assertEquals("GaufreStack", CDI.current().select(MenuService.class).get().shopName());
        } finally {
            bootstrap.shutdown();
        }
    }
}
