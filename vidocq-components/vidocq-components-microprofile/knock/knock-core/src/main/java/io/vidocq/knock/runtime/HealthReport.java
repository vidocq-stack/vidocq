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
 * Report produced by {@link KnockHealthService} for a given {@link ProbeType}.
 *
 * <p>Encapsulates the result ready to serve to an HTTP transport (Jakarta REST via
 * {@code knock-cassini}, or otherwise): MicroProfile Health 4.0 HTTP code (200 / 503,
 * spec §3) and JSON body compliant with spec §3.1.</p>
 *
 * @param httpStatus MicroProfile Health 4.0 HTTP code (200 if UP, 503 if DOWN)
 * @param json       JSON body for spec §3.1 (never {@code null})
 */
public record HealthReport(int httpStatus, String json) {

    public HealthReport {
        if (json == null) {
            throw new IllegalArgumentException("json must not be null");
        }
    }
}
