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

    // Package-private (not private): Vauban injects these statically via the generated
    // _VaubanComponents.injectField (in-module putfield). A private field is excluded from that
    // generation and would force `opens … to io.vidocq.vauban.core` (runtime reflection), which this
    // module forbids by design — hence package-private, eligible for zero-reflection field injection.
    @Inject
    HealthCheckRegistry registry;

    @Inject @Liveness
    Instance<HealthCheck> livenessChecks;

    @Inject @Readiness
    Instance<HealthCheck> readinessChecks;

    @Inject @Startup
    Instance<HealthCheck> startupChecks;

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