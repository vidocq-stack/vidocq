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

import io.vidocq.knock.runtime.HealthReport;
import io.vidocq.knock.runtime.KnockHealthService;
import io.vidocq.knock.spi.HealthCheckRegistry;
import io.vidocq.knock.spi.ProbeType;
import io.vidocq.vauban.core.container.VaubanContainer;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;
import org.eclipse.microprofile.health.Startup;
import org.junit.jupiter.api.Test;

import jakarta.enterprise.context.ApplicationScoped;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CDI integration tests — {@link HealthCheckRegistrar}, {@link KnockCdiHealthCheckRegistry}.
 *
 * <p>MicroProfile Health 4.0 spec §4.1: "Health check procedures annotated with one
 * of the three qualifiers are automatically discovered and registered."</p>
 *
 * <p>Bootstrapped via {@code VaubanContainer.builder().addBeanClass(...)} — minimal SE
 * container, tests on the classpath ({@code useModulePath=false}).</p>
 */
class HealthCheckCdiIntegrationTest {

    // -----------------------------------------------------------------------
    // Inline test beans
    // -----------------------------------------------------------------------

    @Liveness @ApplicationScoped
    static class LivenessUpCheck implements HealthCheck {
        @Override public HealthCheckResponse call() {
            return HealthCheckResponse.up("liveness-up");
        }
    }

    @Readiness @ApplicationScoped
    static class ReadinessUpCheck implements HealthCheck {
        @Override public HealthCheckResponse call() {
            return HealthCheckResponse.up("readiness-up");
        }
    }

    @Startup @ApplicationScoped
    static class StartupUpCheck implements HealthCheck {
        @Override public HealthCheckResponse call() {
            return HealthCheckResponse.up("startup-up");
        }
    }

    @Liveness @ApplicationScoped
    static class LivenessDownCheck implements HealthCheck {
        @Override public HealthCheckResponse call() {
            return HealthCheckResponse.down("liveness-down");
        }
    }

    /** Bean without a probe qualifier — not a health-check procedure; must be silently ignored. */
    @ApplicationScoped
    static class NoProbeCheck implements HealthCheck {
        @Override public HealthCheckResponse call() {
            return HealthCheckResponse.up("no-probe");
        }
    }

    // -----------------------------------------------------------------------
    // §4.1 — automatic discovery and registration of @Liveness
    // -----------------------------------------------------------------------

    @Test
    void liveness_check_auto_registered_spec_section4_1() {
        // Spec §4.1: @Liveness bean discovered and registered automatically
        try (var container = buildContainer(LivenessUpCheck.class)) {
            HealthCheckRegistry registry = container.select(HealthCheckRegistry.class);
            assertFalse(registry.getChecks(ProbeType.LIVENESS).isEmpty(),
                    "The @Liveness check must be auto-registered");
            assertEquals(0, registry.getChecks(ProbeType.READINESS).size());
            assertEquals(0, registry.getChecks(ProbeType.STARTUP).size());
        }
    }

    // -----------------------------------------------------------------------
    // §4.1 — discovery by probe type (READINESS, STARTUP)
    // -----------------------------------------------------------------------

    @Test
    void all_probe_types_auto_registered_spec_section4_1() {
        try (var container = buildContainer(
                LivenessUpCheck.class, ReadinessUpCheck.class, StartupUpCheck.class)) {
            HealthCheckRegistry registry = container.select(HealthCheckRegistry.class);
            assertEquals(1, registry.getChecks(ProbeType.LIVENESS).size());
            assertEquals(1, registry.getChecks(ProbeType.READINESS).size());
            assertEquals(1, registry.getChecks(ProbeType.STARTUP).size());
        }
    }

    // -----------------------------------------------------------------------
    // §3.2 + §4.1 — aggregation from the registry populated by CDI
    // -----------------------------------------------------------------------

    @Test
    void liveness_down_yields_DOWN_aggregate_spec_section3_2() {
        // Spec §3.2: DOWN as soon as one check is DOWN
        try (var container = buildContainer(LivenessDownCheck.class)) {
            HealthCheckRegistry registry = container.select(HealthCheckRegistry.class);
            HealthReport report = new KnockHealthService(registry).report(ProbeType.LIVENESS);
            assertEquals(503, report.httpStatus(),
                    "Spec §3: status=DOWN -> HTTP 503");
            assertTrue(report.json().contains("\"status\":\"DOWN\"")
                    || report.json().contains("\"status\": \"DOWN\""),
                    "JSON must contain status DOWN, got: " + report.json());
        }
    }

    // -----------------------------------------------------------------------
    // §4.1 — HealthCheckRegistry injectable via CDI
    // -----------------------------------------------------------------------

    @Test
    void registry_injectable_via_cdi_spec_section4_1() {
        try (var container = buildContainer(LivenessUpCheck.class)) {
            HealthCheckRegistry registry = container.select(HealthCheckRegistry.class);
            assertNotNull(registry, "@Inject HealthCheckRegistry must be satisfied by the producer");
        }
    }

    // -----------------------------------------------------------------------
    // §4.2 — HealthCheck bean without a probe qualifier is silently ignored
    // -----------------------------------------------------------------------

    @Test
    void health_check_without_probe_annotation_is_ignored_spec_section4_2() {
        // Spec §4.2: a HealthCheck implementation that carries none of the three
        // qualifiers is NOT a health-check procedure. It is an ordinary CDI bean and
        // must be silently ignored — the deployment succeeds and the check is not
        // registered under any probe type (this is what the MP Health TCK
        // EnforceQualifierTest asserts: deployment OK + empty checks array).
        try (var container = buildContainer(NoProbeCheck.class)) {
            HealthCheckRegistry registry = container.select(HealthCheckRegistry.class);

            assertEquals(0, registry.getChecks(ProbeType.LIVENESS).size(),
                    "An unqualified HealthCheck must not be registered as a liveness procedure");
            assertEquals(0, registry.getChecks(ProbeType.READINESS).size(),
                    "An unqualified HealthCheck must not be registered as a readiness procedure");
            assertEquals(0, registry.getChecks(ProbeType.STARTUP).size(),
                    "An unqualified HealthCheck must not be registered as a startup procedure");

            // Aggregated /health: overall UP with an empty checks array.
            HealthReport report = new KnockHealthService(registry).report(ProbeType.ALL);
            assertEquals(200, report.httpStatus(),
                    "Spec §3: an empty aggregate reports status=UP -> HTTP 200");
            assertTrue(report.json().contains("\"status\":\"UP\"")
                    || report.json().contains("\"status\": \"UP\""),
                    "JSON must report overall UP, got: " + report.json());
            assertFalse(report.json().contains("no-probe"),
                    "The unqualified check must not appear in the aggregated response, got: " + report.json());
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a minimal Vauban container with the Knock CDI infrastructure
     * and the provided check beans.
     */
    private static VaubanContainer buildContainer(Class<?>... checkBeans) {
        var builder = VaubanContainer.builder()
                .addBeanClass(KnockCdiHealthCheckRegistry.class)
                .addBeanClass(HealthCheckRegistrar.class);
        for (Class<?> checkBean : checkBeans) {
            builder.addBeanClass(checkBean);
        }
        return builder.build();
    }
}
