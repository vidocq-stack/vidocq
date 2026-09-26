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
package io.vidocq.runtime.extensions.microprofile.dirac;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Vidocq/vidocq#143: the runtime extension writes its section, and is no dev console panel any more. */
class DiracSectionWithoutPanelTest {

    @Test
    void theExtensionWritesItsSectionAndIsNoPanel() {
        DiracMetricsExtension extension = new DiracMetricsExtension();

        assertEquals("metrics", extension.id());
        assertFalse(java.util.Arrays.stream(DiracMetricsExtension.class.getInterfaces())
                .anyMatch(type -> type.getName().startsWith("io.vidocq.runtime.spi.devconsole")));
    }

    @Test
    void theJarNamesItsDevCompanion() throws Exception {
        try (var in = DiracMetricsExtension.class.getClassLoader().getResourceAsStream("META-INF/vidocq/dev-module")) {
            assertEquals("vidocq-runtime-dirac-metrics-extension-dev", new String(in.readAllBytes()).strip());
        }
    }
}
