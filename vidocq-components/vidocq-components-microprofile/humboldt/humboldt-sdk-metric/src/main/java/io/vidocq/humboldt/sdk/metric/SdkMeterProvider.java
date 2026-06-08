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
package io.vidocq.humboldt.sdk.metric;

import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterBuilder;
import io.opentelemetry.api.metrics.MeterProvider;
import io.vidocq.humboldt.sdk.common.Clock;
import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.metric.data.MetricData;
import io.vidocq.humboldt.sdk.metric.export.MetricReader;
import io.vidocq.humboldt.sdk.metric.internal.SdkMeter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Humboldt implementation of {@link MeterProvider} — entry point for the Metric SDK.
 *
 * <p>Built via {@link #builder()}. Immutable from a configuration standpoint. Readers
 * are registered via {@code register(CollectionRegistration)} so they can trigger
 * collection across all meters/instruments in the provider.</p>
 */
public final class SdkMeterProvider implements MeterProvider, AutoCloseable {

    private final Resource resource;
    private final Clock clock;
    private final long startEpochNanos;
    private final List<MetricReader> readers;
    private final Map<String, SdkMeter> meters = new ConcurrentHashMap<>();

    private SdkMeterProvider(Builder b) {
        this.resource = b.resource;
        this.clock = b.clock;
        this.startEpochNanos = clock.now();
        this.readers = Collections.unmodifiableList(new ArrayList<>(b.readers));
        for (MetricReader r : readers) {
            r.register(this::collectAllMetrics);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Meter get(String instrumentationScopeName) {
        String name = instrumentationScopeName == null ? "" : instrumentationScopeName;
        return meters.computeIfAbsent(name,
                n -> new SdkMeter(InstrumentationScope.of(n), resource, clock));
    }

    @Override
    public MeterBuilder meterBuilder(String instrumentationScopeName) {
        // M4 MVP: version, schemaUrl, and attributes are ignored (one SdkMeter per name).
        // M6 will add full support for versioned InstrumentationScope.
        return new MeterBuilder() {
            @Override public MeterBuilder setInstrumentationVersion(String v) { return this; }
            @Override public MeterBuilder setSchemaUrl(String url) { return this; }
            @Override public Meter build() { return get(instrumentationScopeName); }
        };
    }

    private Collection<MetricData> collectAllMetrics() {
        long now = clock.now();
        List<MetricData> out = new ArrayList<>();
        for (SdkMeter m : meters.values()) {
            out.addAll(m.collect(startEpochNanos, now));
        }
        return out;
    }

    public Resource getResource() {
        return resource;
    }

    public CompletableResultCode flush() {
        List<CompletableResultCode> codes = new ArrayList<>(readers.size());
        for (MetricReader r : readers) codes.add(r.flush());
        return CompletableResultCode.ofAll(codes);
    }

    public CompletableResultCode shutdown() {
        List<CompletableResultCode> codes = new ArrayList<>(readers.size());
        for (MetricReader r : readers) codes.add(r.shutdown());
        return CompletableResultCode.ofAll(codes);
    }

    @Override
    public void close() {
        shutdown().join(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    public static final class Builder {
        private Resource resource = Resource.empty();
        private Clock clock = Clock.system();
        private final List<MetricReader> readers = new ArrayList<>();

        public Builder setResource(Resource resource) {
            this.resource = resource != null ? resource : Resource.empty();
            return this;
        }

        public Builder setClock(Clock clock) {
            if (clock != null) this.clock = clock;
            return this;
        }

        public Builder registerMetricReader(MetricReader reader) {
            if (reader != null) readers.add(reader);
            return this;
        }

        public SdkMeterProvider build() {
            return new SdkMeterProvider(this);
        }
    }
}
