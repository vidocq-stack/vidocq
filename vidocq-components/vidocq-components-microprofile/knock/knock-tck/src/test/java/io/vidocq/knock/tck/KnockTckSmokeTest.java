/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
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
