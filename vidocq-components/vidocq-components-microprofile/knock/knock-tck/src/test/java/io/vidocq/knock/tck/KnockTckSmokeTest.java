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
 * Smoke test du bootstrap Knock — vérifie que les modules knock-core et knock-api
 * sont correctement chargés sans Arquillian.
 *
 * <p>À M0 (bootstrap), seul le chargement des classes spec est validé. La construction
 * de {@link HealthCheckResponse} via le builder nécessite un {@code HealthCheckResponseProvider}
 * (SPI) qui sera implémenté en M1. Les tests correspondants sont marqués {@code @Disabled}
 * jusqu'à M1.</p>
 *
 * <p>Exécuté par le profil Maven {@code smoke} (actif par défaut) via le script
 * {@code run-official-tck-mp-health-4.0.sh}.</p>
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
        // Vérifie que les classes MicroProfile Health 4.0 sont bien sur le module-path
        // (ne nécessite pas de HealthCheckResponseProvider — vérification de chargement seul)
        assertNotNull(HealthCheck.class);
        assertNotNull(HealthCheckResponse.class);
        assertNotNull(HealthCheckResponse.Status.UP);
        assertNotNull(HealthCheckResponse.Status.DOWN);
    }

    @Test
    void health_probe_annotations_are_loadable() {
        // Vérifie que les annotations de qualification sont bien chargées depuis le module
        assertNotNull(Liveness.class);
        assertNotNull(Readiness.class);
        assertNotNull(Startup.class);
    }
}
