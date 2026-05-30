/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.knock.cdi.internal;

import io.vidocq.knock.runtime.HealthReport;
import io.vidocq.knock.runtime.KnockHealthService;
import io.vidocq.knock.spi.HealthCheckRegistry;
import io.vidocq.knock.spi.ProbeType;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.spi.DeploymentException;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;
import org.eclipse.microprofile.health.Startup;
import org.junit.jupiter.api.Test;

import jakarta.enterprise.context.ApplicationScoped;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CDI integration tests — {@link HealthCheckCdiExtension}, {@link HealthCheckRegistrar},
 * {@link KnockCdiHealthCheckRegistry}.
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

    /** Bean without a probe qualifier — must trigger a deployment error. */
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
    // §4.2 — HealthCheck bean without a probe qualifier -> DeploymentException
    // -----------------------------------------------------------------------

    @Test
    void health_check_without_probe_annotation_fails_deployment_spec_section4_2() {
        // Spec §4.2: "Health check procedures that do not carry one of the three
        // qualifiers result in a deployment error."
        assertThrows(
                DeploymentException.class,
                () -> {
                    try (var container = buildContainer(NoProbeCheck.class)) {
                        // The deployment is expected to fail before this container is used.
                    }
                },
                "A HealthCheck bean without @Liveness/@Readiness/@Startup must fail deployment");
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
                .addBeanClass(HealthCheckCdiExtension.class)
                .addBeanClass(KnockCdiHealthCheckRegistry.class)
                .addBeanClass(HealthCheckRegistrar.class);
        for (Class<?> checkBean : checkBeans) {
            builder.addBeanClass(checkBean);
        }
        return builder.build();
    }
}
