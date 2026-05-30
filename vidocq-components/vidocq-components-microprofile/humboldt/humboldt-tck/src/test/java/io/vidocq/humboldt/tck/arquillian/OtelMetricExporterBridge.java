/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.humboldt.tck.arquillian;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.metric.data.MetricData;
import io.vidocq.humboldt.sdk.metric.export.MetricExporter;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Adapts an {@link io.opentelemetry.sdk.metrics.export.MetricExporter OTel MetricExporter}
 * into a {@link MetricExporter humboldt MetricExporter}. This lets Arquillian harnesses
 * retrieve their TCK {@code InMemoryMetricExporter} (provided through the
 * {@code ConfigurableMetricExporterProvider} SPI) without re-implementing collection on the Humboldt side.
 *
 * <p>Symmetric to {@code OtelSpanExporterBridge} (M7b.4b.3). Humboldt
 * {@code MetricData} → OTel {@code MetricData} mapping is performed via {@link MetricDataMapper}.</p>
 */
final class OtelMetricExporterBridge implements MetricExporter {

    private final io.opentelemetry.sdk.metrics.export.MetricExporter delegate;

    OtelMetricExporterBridge(io.opentelemetry.sdk.metrics.export.MetricExporter delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompletableResultCode export(Collection<MetricData> metrics) {
        var otelMetrics = new ArrayList<io.opentelemetry.sdk.metrics.data.MetricData>(metrics.size());
        for (MetricData m : metrics) {
            try {
                otelMetrics.add(MetricDataMapper.toOtel(m));
            } catch (RuntimeException ignored) {
                // Skip metrics that cannot be mapped (types unsupported by the mapper)
            }
        }
        var otelResult = delegate.export(otelMetrics);
        return adapt(otelResult);
    }

    @Override
    public CompletableResultCode flush() {
        return adapt(delegate.flush());
    }

    @Override
    public CompletableResultCode shutdown() {
        return adapt(delegate.shutdown());
    }

    private static CompletableResultCode adapt(io.opentelemetry.sdk.common.CompletableResultCode otelResult) {
        // If the OTel result is already completed (synchronous case — the TCK's InMemoryMetricExporter
        // marshals immediately), take the shortcut without an async Humboldt wrapper.
        if (otelResult.isDone()) {
            return otelResult.isSuccess() ? CompletableResultCode.ofSuccess() : CompletableResultCode.ofFailure();
        }
        // Otherwise, attach an OTel whenComplete that would propagate the result to a
        // Humboldt result initially created as "pending".
        // The humboldt.CompletableResultCode API does not expose a public constructor for
        // a pending result — so return aggregated success/failure via join.
        otelResult.join(10, java.util.concurrent.TimeUnit.SECONDS);
        return otelResult.isSuccess() ? CompletableResultCode.ofSuccess() : CompletableResultCode.ofFailure();
    }
}
