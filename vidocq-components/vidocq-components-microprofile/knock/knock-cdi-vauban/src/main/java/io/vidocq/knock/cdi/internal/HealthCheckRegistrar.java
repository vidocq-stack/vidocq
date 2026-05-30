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

import io.vidocq.knock.spi.HealthCheckRegistry;
import io.vidocq.knock.spi.ProbeType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;
import org.eclipse.microprofile.health.Startup;

/**
 * Automatically registers CDI {@link HealthCheck} beans in the
 * {@link HealthCheckRegistry} when the application context starts.
 *
 * <p>MicroProfile Health 4.0 §4.1: "Health check procedures that implement the
 * HealthCheck interface and are annotated with one of the three qualifiers are
 * automatically discovered and registered."</p>
 *
 * <p>Registration is triggered by the
 * {@code @Initialized(ApplicationScoped.class)} event, which is fired when the CDI
 * application context starts — before any HTTP request. This ensures the registry is
 * populated before the {@code /health*} endpoints receive traffic.</p>
 *
 * <p>Registry key name: {@code check.getClass().getName()}.
 * For proxied beans (Vauban dynamic subclasses), the name includes the proxy suffix —
 * that suffix is deterministic and unique per bean class, so deduplication works correctly.</p>
 */
@ApplicationScoped
class HealthCheckRegistrar {

    @Inject
    private HealthCheckRegistry registry;

    @Inject @Liveness
    private Instance<HealthCheck> livenessChecks;

    @Inject @Readiness
    private Instance<HealthCheck> readinessChecks;

    @Inject @Startup
    private Instance<HealthCheck> startupChecks;

    /**
     * Registers all checks discovered when the application context starts.
     *
     * <p>Spec §4.1: qualified beans are registered by probe type.</p>
     *
     * @param ignored the CDI {@code @Initialized(ApplicationScoped.class)} event
     */
    void onApplicationStart(@Observes @Initialized(ApplicationScoped.class) Object ignored) {
        livenessChecks.forEach(c  -> registry.register(ProbeType.LIVENESS,  c.getClass().getName(), c));
        readinessChecks.forEach(c -> registry.register(ProbeType.READINESS, c.getClass().getName(), c));
        startupChecks.forEach(c   -> registry.register(ProbeType.STARTUP,   c.getClass().getName(), c));
    }
}