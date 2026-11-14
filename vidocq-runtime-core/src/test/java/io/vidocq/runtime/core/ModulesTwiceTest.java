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

import io.vidocq.runtime.core.report.StartupAnomalies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** VIDOCQ-LAY-001: which modules a trampoline from the module path holds twice, and what the start says. */
@DisplayName("BUG-20261114-10 — the modules loaded twice")
class ModulesTwiceTest {

    @Test
    @DisplayName("the modules of the layer that the other layer holds too, sorted")
    void theModulesTheBootLayerHoldsToo() {
        assertEquals(List.of("java.base", "java.logging"),
                VidocqAppLayer.twice(Set.of("java.logging", "acme.absent", "java.base"), ModuleLayer.boot()));
    }

    @Test
    @DisplayName("an installation records none unless told")
    void anInstallationRecordsNoneUnlessTold() {
        assertEquals(List.of(), new VidocqAppLayer.Installation("vidocq.app.path", List.of(), true, Map.of()).twice());
    }

    @Test
    @DisplayName("the message counts and names the modules")
    void theMessageNamesTheModules() {
        assertTrue(StartupAnomalies.modulesTwice(List.of("acme.app"))
                .startsWith("1 application module is in the boot layer and in the application layer: acme.app."));
        assertTrue(StartupAnomalies.modulesTwice(List.of("acme.app", "acme.lib"))
                .startsWith("2 application modules are in the boot layer and in the application layer: acme.app,"
                        + " acme.lib."));
        assertTrue(StartupAnomalies.MODULES_TWICE_HINT.contains("vidocq:dev or vidocq:run"));
    }
}
