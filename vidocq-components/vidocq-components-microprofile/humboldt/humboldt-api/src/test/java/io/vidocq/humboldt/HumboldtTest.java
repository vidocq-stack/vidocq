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
package io.vidocq.humboldt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HumboldtTest {

    @Test
    void version_is_published() {
        String v = Humboldt.version();
        assertFalse(v.isBlank(), "version() must return a non-empty string");
        assertFalse(v.contains("${"), "version.properties must be filtered by the build");
        // project.version is injected by surefire (systemPropertyVariables) — the
        // published 0.2.0 artifact reported a hardcoded 0.1.0-SNAPSHOT.
        assertEquals(System.getProperty("project.version"), v,
                "version() must be the Maven build version, not a hardcoded constant");
    }

    @Test
    void start_does_not_throw() {
        Humboldt.start();
    }
}
