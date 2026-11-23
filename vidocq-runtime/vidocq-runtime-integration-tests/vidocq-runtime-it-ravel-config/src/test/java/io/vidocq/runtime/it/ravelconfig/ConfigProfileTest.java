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
import io.vidocq.runtime.core.config.VidocqConfigImpl;
import io.vidocq.runtime.spi.config.VidocqConfig;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BUG-20261123-46: {@code vidocq:dev} sets {@code -Dvidocq.profile=dev}. With Ravel present, the {@code %dev.} keys of
 * {@code vidocq.properties} and of {@code microprofile-config.properties} apply, through Vidocq's configuration and
 * through MicroProfile Config alike.
 */
class ConfigProfileTest {

    private static final String PROFILE = "vidocq.profile";

    @AfterEach
    void clearProfile() {
        System.clearProperty(PROFILE);
    }

    @Test
    void theVidocqProfileAppliesToTheVidocqAndMicroProfileFiles() {
        System.setProperty(PROFILE, "dev");
        VidocqBootstrap bootstrap = VidocqBootstrap.create().configure().start();
        try {
            MenuService menu = CDI.current().select(MenuService.class).get();
            assertEquals("GaufreStack Dev", menu.shopName());
            assertEquals("Crisp, in dev", menu.config().getValue("shop.motto", String.class));

            VidocqConfig vidocq = new VidocqConfigImpl();
            assertEquals("GaufreStack Dev", vidocq.getValue("shop.name").orElseThrow());
            assertEquals("Crisp, in dev", vidocq.getValue("shop.motto").orElseThrow());
        } finally {
            bootstrap.shutdown();
        }
    }

    @Test
    void withoutAProfileThePlainKeysApply() {
        VidocqBootstrap bootstrap = VidocqBootstrap.create().configure().start();
        try {
            MenuService menu = CDI.current().select(MenuService.class).get();
            assertEquals("GaufreStack", menu.shopName());
            assertEquals("Crisp", menu.config().getValue("shop.motto", String.class));
        } finally {
            bootstrap.shutdown();
        }
    }
}
