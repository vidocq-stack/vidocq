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
package io.vidocq.knock.it.weld;

import io.vidocq.knock.jaxrs.KnockHealthResource;
import io.vidocq.knock.runtime.HealthReport;
import io.vidocq.knock.runtime.KnockHealthService;
import io.vidocq.knock.spi.HealthCheckRegistry;
import io.vidocq.knock.spi.ProbeType;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Knock jars, unchanged, under Weld SE on a class path (vidocq-workspace#15): bean discovery
 * goes through each jar's {@code META-INF/beans.xml}, services through {@code META-INF/services},
 * and nothing generated for Vauban is used. MicroProfile Health 4.0 §4.1: qualified
 * {@code HealthCheck} beans are discovered and registered by probe type.
 */
class WeldPortabilityTest {

    private static WeldContainer container;

    @BeforeAll
    static void start() {
        // Default discovery: every bean archive of the class path, as an application would boot.
        container = new Weld().initialize();
    }

    @AfterAll
    static void stop() {
        if (container != null) {
            container.close();
        }
    }

    @Test
    void vaubanIsNotOnTheClassPath() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.vidocq.vauban.core.container.VaubanContainer"));
    }

    @Test
    void registryIsABean() {
        assertTrue(container.select(HealthCheckRegistry.class).isResolvable(),
                "knock-cdi-vauban must contribute the HealthCheckRegistry bean");
    }

    @Test
    void checksAreRegisteredByProbeType() {
        HealthCheckRegistry registry = container.select(HealthCheckRegistry.class).get();
        assertEquals(1, registry.getChecks(ProbeType.LIVENESS).size(), "liveness checks");
        assertEquals(1, registry.getChecks(ProbeType.READINESS).size(), "readiness checks");
        assertEquals(1, registry.getChecks(ProbeType.STARTUP).size(), "startup checks");
    }

    @Test
    void reportsAggregateTheRegisteredChecks() {
        var service = new KnockHealthService(container.select(HealthCheckRegistry.class).get());

        HealthReport live = service.report(ProbeType.LIVENESS);
        assertEquals(200, live.httpStatus());
        assertTrue(live.json().contains("\"weld-live\""), live.json());
        assertTrue(live.json().contains("\"container\""), live.json());

        HealthReport ready = service.report(ProbeType.READINESS);
        assertEquals(503, ready.httpStatus());
        assertTrue(ready.json().contains("\"weld-not-ready\""), ready.json());

        HealthReport started = service.report(ProbeType.STARTUP);
        assertEquals(200, started.httpStatus());
        assertTrue(started.json().contains("\"weld-started\""), started.json());

        assertEquals(503, service.report(ProbeType.ALL).httpStatus());
    }

    @Test
    void healthResourceIsABean() {
        assertTrue(container.select(KnockHealthResource.class).isResolvable(),
                "knock-jaxrs must be a bean archive so the /health resource gets its registry injected");
    }
}
