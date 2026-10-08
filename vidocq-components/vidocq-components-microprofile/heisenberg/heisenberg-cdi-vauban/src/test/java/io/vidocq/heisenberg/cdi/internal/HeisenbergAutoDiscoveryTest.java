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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
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
}
