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
package io.vidocq.heisenberg.cdi.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

class HeisenbergAutoDiscoveryTest {

    @Test
    void registersConfigProviderResolverInServiceDescriptor() throws Exception {
        // Read the descriptor from this module's own jar: a class-loader-wide lookup returns the first
        // match among every module on the path (ravel-core ships the same service file).
        String resourcePath = "/META-INF/services/org.eclipse.microprofile.config.spi.ConfigProviderResolver";
        try (var input = HeisenbergAutoDiscovery.class.getResourceAsStream(resourcePath)) {
            assertNotNull(input);
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(content.contains(HeisenbergAutoDiscovery.class.getName()));
        }
    }

    @Test
    void delegatesToTheOtherConfigProviderResolver() throws Exception {
        HeisenbergAutoDiscovery discovery = new HeisenbergAutoDiscovery();
        ClassLoader current = Thread.currentThread().getContextClassLoader();

        assertSame(discovery.getConfig(current), discovery.getConfig());
        assertFalse(discovery.getConfig().getOptionalValue("heisenberg.test.absent", String.class).isPresent());

        // A config registered through the discovery is the one returned for its class loader,
        // until it is released.
        try (URLClassLoader loader = new URLClassLoader(new URL[0], current)) {
            Config built = discovery.getBuilder().build();
            discovery.registerConfig(built, loader);
            assertSame(built, discovery.getConfig(loader));

            discovery.releaseConfig(built);
            Config rebuilt = discovery.getConfig(loader);
            assertNotSame(built, rebuilt);
            discovery.releaseConfig(rebuilt);
        }
    }
}
