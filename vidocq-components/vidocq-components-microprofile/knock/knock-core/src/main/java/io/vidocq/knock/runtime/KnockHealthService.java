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
