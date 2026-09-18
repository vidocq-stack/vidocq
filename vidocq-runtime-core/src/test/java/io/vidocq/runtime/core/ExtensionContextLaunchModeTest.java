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
package io.vidocq.runtime.core;

import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.runtime.core.banner.LaunchModeResolver;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.config.VidocqConfig;
import io.vidocq.runtime.spi.report.LaunchMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link ExtensionContext#launchMode()}: {@code prod} unless Vidocq knows better, the resolved mode when it does. */
class ExtensionContextLaunchModeTest {

    private String configuredMode;

    @BeforeEach
    void rememberTheConfiguredMode() {
        configuredMode = System.getProperty(LaunchModeResolver.MODE_KEY);
    }

    @AfterEach
    void restoreTheConfiguredMode() {
        if (configuredMode == null) {
            System.clearProperty(LaunchModeResolver.MODE_KEY);
        } else {
            System.setProperty(LaunchModeResolver.MODE_KEY, configuredMode);
        }
    }

    @Test
    void aContextWrittenBeforeTheModeExistedSaysProd() {
        // an extension's test double implements the three original methods only
        ExtensionContext context = new ExtensionContext() {
            @Override
            public VaubanContainer container() {
                return null;
            }

            @Override
            public VidocqConfiguration configuration() {
                return null;
            }

            @Override
            public VidocqConfig config() {
                return null;
            }
        };

        assertEquals(LaunchMode.PROD, context.launchMode());
    }

    @ParameterizedTest
    @EnumSource(LaunchMode.class)
    void theExtensionsAreGivenTheModeTheBootstrapResolved(LaunchMode mode) {
        System.setProperty(LaunchModeResolver.MODE_KEY, mode.label());

        VidocqBootstrap bootstrap = VidocqBootstrap.create().banner(BannerMode.OFF).configure();

        assertEquals(mode, bootstrap.extensionContext().launchMode());
    }

    @Test
    void everyBootOfTheJvmResolvesItsOwnMode() {
        // the dev reload loop boots again in the same JVM, where the banner is shown only once
        System.setProperty(LaunchModeResolver.MODE_KEY, "dev");
        VidocqBootstrap first = VidocqBootstrap.create().banner(BannerMode.OFF).configure();
        System.setProperty(LaunchModeResolver.MODE_KEY, "prod");
        VidocqBootstrap second = VidocqBootstrap.create().banner(BannerMode.OFF).configure();

        assertEquals(LaunchMode.DEV, first.extensionContext().launchMode());
        assertEquals(LaunchMode.PROD, second.extensionContext().launchMode());
    }

    @Test
    void aBootstrapNotYetConfiguredSaysProd() {
        assertEquals(LaunchMode.PROD, VidocqBootstrap.create().extensionContext().launchMode());
    }

    @Test
    void theContextOfTheBootstrapCarriesTheModeItIsGiven() {
        assertEquals(LaunchMode.DEV, new ExtensionContextImpl(null, null, null, LaunchMode.DEV).launchMode());
    }
}
