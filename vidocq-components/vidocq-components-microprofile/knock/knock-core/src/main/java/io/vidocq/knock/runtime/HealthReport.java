/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.knock.runtime;

import io.vidocq.knock.spi.HealthCheckRegistry;
import io.vidocq.knock.spi.ProbeType;

/**
 * Rapport produit par {@link KnockHealthService} pour un {@link ProbeType} donné.
 *
 * <p>Encapsule le résultat prêt à servir à un transport HTTP (Jakarta REST via
 * {@code knock-cassini}, ou autre) : code HTTP MicroProfile Health 4.0 (200 / 503,
 * spec §3) et corps JSON conforme à la spec §3.1.</p>
 *
 * @param httpStatus code HTTP MicroProfile Health 4.0 (200 si UP, 503 si DOWN)
 * @param json       corps JSON spec §3.1 (jamais {@code null})
 */
public record HealthReport(int httpStatus, String json) {

    public HealthReport {
        if (json == null) {
            throw new IllegalArgumentException("json must not be null");
        }
    }
}

