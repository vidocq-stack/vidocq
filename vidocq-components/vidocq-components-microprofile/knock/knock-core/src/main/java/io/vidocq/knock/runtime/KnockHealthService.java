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

import io.vidocq.knock.internal.HealthSnapshot;
import io.vidocq.knock.internal.KnockAggregator;
import io.vidocq.knock.internal.KnockJsonSerializer;
import io.vidocq.knock.spi.HealthCheckRegistry;
import io.vidocq.knock.spi.ProbeType;

import java.util.Objects;

/**
 * Knock runtime facade — orchestrates {@link KnockAggregator} and
 * {@link KnockJsonSerializer} to produce a {@link HealthReport} (HTTP code + JSON body)
 * ready to serve from an HTTP transport (Jakarta REST via {@code knock-cassini}, or other).
 *
 * <p>MicroProfile Health 4.0 §3: "The result of executing a health check procedure
 * is a HealthCheckResponse. The result of the aggregation of these responses is reported
 * via an HTTP endpoint."</p>
 *
 * <p>This class is exported (package {@code io.vidocq.knock.runtime}) and serves as
 * the stable entry point for transport adapters. Integration modules must not depend
 * directly on {@code io.vidocq.knock.internal}.</p>
 *
 * <p>Thread-safe and virtual-thread-friendly: {@link KnockAggregator} parallelizes
 * {@code .call()} invocations on a {@code VirtualThreadPerTaskExecutor}.</p>
 */
public final class KnockHealthService {

    private final HealthCheckRegistry registry;
    private final KnockAggregator aggregator;
    private final KnockJsonSerializer serializer;

    /**
     * Creates a service backed by the given registry.
     *
     * @param registry source registry (non-null)
     */
    public KnockHealthService(HealthCheckRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.aggregator = new KnockAggregator();
        this.serializer = new KnockJsonSerializer();
    }

    /**
     * Produces the report for the requested {@link ProbeType}.
     *
     * <p>For {@link ProbeType#ALL}, aggregates LIVENESS + READINESS + STARTUP (spec §3).
     * HTTP 200 if status is UP, 503 if DOWN (spec §3).</p>
     *
     * @param type probe type to query
     * @return report (httpStatus + JSON spec §3.1)
     */
    public HealthReport report(ProbeType type) {
        Objects.requireNonNull(type, "type");
        HealthSnapshot snapshot = aggregator.aggregate(registry, type);
        return new HealthReport(serializer.httpStatus(snapshot), serializer.serialize(snapshot));
    }
}
