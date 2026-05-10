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
 * Tests d'intégration CDI — {@link HealthCheckCdiExtension}, {@link HealthCheckRegistrar},
 * {@link HealthCheckRegistryProducer}.
 *
 * <p>Spec MicroProfile Health 4.0 §4.1 : « Health check procedures annotated with one
 * of the three qualifiers are automatically discovered and registered. »</p>
 *
 * <p>Bootstrap via {@code VaubanContainer.builder().addBeanClass(...)} — container SE
 * minimal, tests sur classpath ({@code useModulePath=false}).</p>
 */
class HealthCheckCdiIntegrationTest {

    // -----------------------------------------------------------------------
    // Beans de test inline
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

    /** Bean sans qualifieur de probe — doit déclencher une erreur de déploiement. */
    @ApplicationScoped
    static class NoProbeCheck implements HealthCheck {
        @Override public HealthCheckResponse call() {
            return HealthCheckResponse.up("no-probe");
        }
    }

    // -----------------------------------------------------------------------
    // §4.1 — découverte et enregistrement automatique @Liveness
    // -----------------------------------------------------------------------

    @Test
    void liveness_check_auto_registered_spec_section4_1() {
        // Spec §4.1 : bean @Liveness découvert et enregistré automatiquement
        try (var container = buildContainer(LivenessUpCheck.class)) {
            HealthCheckRegistry registry = container.select(HealthCheckRegistry.class);
            assertFalse(registry.getChecks(ProbeType.LIVENESS).isEmpty(),
                    "Le check @Liveness doit être auto-enregistré");
            assertEquals(0, registry.getChecks(ProbeType.READINESS).size());
            assertEquals(0, registry.getChecks(ProbeType.STARTUP).size());
        }
    }

    // -----------------------------------------------------------------------
    // §4.1 — découverte par type de probe (READINESS, STARTUP)
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
    // §3.2 + §4.1 — agrégation depuis le registry peuplé par CDI
    // -----------------------------------------------------------------------

    @Test
    void liveness_down_yields_DOWN_aggregate_spec_section3_2() {
        // Spec §3.2 : DOWN dès qu'un check est DOWN
        try (var container = buildContainer(LivenessDownCheck.class)) {
            HealthCheckRegistry registry = container.select(HealthCheckRegistry.class);
            HealthReport report = new KnockHealthService(registry).report(ProbeType.LIVENESS);
            assertEquals(503, report.httpStatus(),
                    "Spec §3 : status=DOWN → HTTP 503");
            assertTrue(report.json().contains("\"status\":\"DOWN\"")
                    || report.json().contains("\"status\": \"DOWN\""),
                    "JSON doit contenir status DOWN, got: " + report.json());
        }
    }

    // -----------------------------------------------------------------------
    // §4.1 — HealthCheckRegistry injectable via CDI
    // -----------------------------------------------------------------------

    @Test
    void registry_injectable_via_cdi_spec_section4_1() {
        try (var container = buildContainer(LivenessUpCheck.class)) {
            HealthCheckRegistry registry = container.select(HealthCheckRegistry.class);
            assertNotNull(registry, "@Inject HealthCheckRegistry doit être satisfait par le producer");
        }
    }

    // -----------------------------------------------------------------------
    // §4.2 — bean HealthCheck sans qualifieur de probe → DeploymentException
    // -----------------------------------------------------------------------

    @Test
    void health_check_without_probe_annotation_fails_deployment_spec_section4_2() {
        // Spec §4.2 : « Health check procedures that do not carry one of the three
        // qualifiers result in a deployment error. »
        assertThrows(
                DeploymentException.class,
                () -> buildContainer(NoProbeCheck.class),
                "Un bean HealthCheck sans @Liveness/@Readiness/@Startup doit echouer au deploiement");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Construit un container Vauban minimal avec l'infrastructure Knock CDI
     * et les beans de check passés en argument.
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
