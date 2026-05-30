/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.knock.internal;

import io.vidocq.knock.spi.ProbeType;
import org.eclipse.microprofile.health.HealthCheckResponse;

import java.util.List;

/**
 * Aggregated result of a registry call for a given {@link ProbeType}.
 *
 * <p>Produced by {@link KnockAggregator}; consumed by {@link KnockJsonSerializer}
 * to produce the spec §3.1 JSON response.</p>
 *
 * @param type   the aggregated probe type
 * @param status the global status (DOWN if at least 1 check is DOWN)
 * @param checks the immutable list of individual responses
 */
public record HealthSnapshot(
        ProbeType type,
        HealthCheckResponse.Status status,
        List<HealthCheckResponse> checks) {

    public HealthSnapshot {
        checks = List.copyOf(checks);
    }
}
