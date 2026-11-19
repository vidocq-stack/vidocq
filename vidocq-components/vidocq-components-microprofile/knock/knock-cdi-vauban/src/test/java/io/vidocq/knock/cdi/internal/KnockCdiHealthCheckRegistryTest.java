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
package io.vidocq.knock.cdi.internal;

import io.vidocq.knock.runtime.KnockHealthService;
import io.vidocq.knock.spi.CheckResult;
import io.vidocq.knock.spi.ProbeType;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD — {@link KnockCdiHealthCheckRegistry} delegates the check names and the last results
 * to its core registry, so a reader of the CDI bean sees what the endpoints recorded.
 */
class KnockCdiHealthCheckRegistryTest {

    @Test
    void names_and_last_results_reach_the_delegate() {
        KnockCdiHealthCheckRegistry registry = new KnockCdiHealthCheckRegistry();
        registry.register(ProbeType.READINESS, "com.acme.Ready", () -> HealthCheckResponse.up("ready"));

        assertEquals(Set.of("com.acme.Ready"), registry.getCheckNames(ProbeType.READINESS));
        assertEquals(Set.of("com.acme.Ready"), registry.getNamedChecks(ProbeType.READINESS).keySet());
        assertTrue(registry.getLastResults().isEmpty());

        new KnockHealthService(registry).report(ProbeType.ALL);

        List<CheckResult> results = registry.getLastResults();
        assertEquals(1, results.size());
        assertEquals("ready", results.getFirst().responseName());
        assertEquals(Map.of(), results.getFirst().data());
    }
}
