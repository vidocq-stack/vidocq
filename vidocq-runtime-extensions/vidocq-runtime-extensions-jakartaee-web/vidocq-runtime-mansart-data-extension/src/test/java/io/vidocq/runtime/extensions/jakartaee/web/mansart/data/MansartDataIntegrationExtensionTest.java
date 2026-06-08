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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VID-2 — minimal isolated checks. The end-to-end behavior (DataSource probe + repository
 * inventory at boot) is exercised by the bout-en-bout example {@code vidocq-runtime-mansart-h2-example}
 * because it requires a real Vauban container.
 */
class MansartDataIntegrationExtensionTest {

    private final MansartDataIntegrationExtension ext = new MansartDataIntegrationExtension();

    @Test
    void identityAndPriority() {
        assertEquals("mansart-data", ext.name());
        // Must run after the pool (200) and before transports (Cassini = 500).
        assertTrue(ext.priority() > 200, "must boot after the pool extension");
        assertTrue(ext.priority() < 500, "must boot before HTTP transports");
    }
}
