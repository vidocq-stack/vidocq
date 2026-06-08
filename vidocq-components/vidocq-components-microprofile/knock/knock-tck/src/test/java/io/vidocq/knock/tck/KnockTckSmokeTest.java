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
package io.vidocq.knock.tck;

import io.vidocq.knock.spi.Knock;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;
import org.eclipse.microprofile.health.Startup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Knock bootstrap smoke test — verifies that the `knock-core` and `knock-api`
 * modules are loaded correctly without Arquillian.
 *
 * <p>At M0 (bootstrap), only the loading of the spec classes is validated. Building
 * {@link HealthCheckResponse} through the builder requires a {@code HealthCheckResponseProvider}
 * (SPI) that will be implemented in M1. The corresponding tests are marked {@code @Disabled}
 * until M1.</p>
 *
 * <p>Executed by the Maven {@code smoke} profile (active by default) via the
 * {@code run-official-tck-mp-health-4.0.sh} script.</p>
 */
class KnockTckSmokeTest {

    @Test
    void knock_metadata_is_accessible() {
        assertEquals("knock", Knock.IMPLEMENTATION_NAME);
        assertEquals("4.0", Knock.SPEC_VERSION);
        assertNotNull(Knock.IMPLEMENTATION_VERSION);
    }

    @Test
    void health_check_api_classes_are_loadable() {
        // Verifies that the MicroProfile Health 4.0 classes are on the module path
        // (does not require a HealthCheckResponseProvider — load check only)
        assertNotNull(HealthCheck.class);
        assertNotNull(HealthCheckResponse.class);
        assertNotNull(HealthCheckResponse.Status.UP);
        assertNotNull(HealthCheckResponse.Status.DOWN);
    }

    @Test
    void health_probe_annotations_are_loadable() {
        // Verifies that the probe qualifier annotations are loaded from the module
        assertNotNull(Liveness.class);
        assertNotNull(Readiness.class);
        assertNotNull(Startup.class);
    }
}
