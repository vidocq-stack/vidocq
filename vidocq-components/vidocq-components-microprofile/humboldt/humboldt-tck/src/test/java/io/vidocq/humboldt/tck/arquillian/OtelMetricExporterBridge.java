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
 * Adapte un {@link io.opentelemetry.sdk.metrics.export.MetricExporter OTel MetricExporter}
 * en {@link MetricExporter humboldt MetricExporter}. Permet aux harness Arquillian de
 * récupérer leur {@code InMemoryMetricExporter} TCK (fourni via SPI
 * {@code ConfigurableMetricExporterProvider}) sans réimplémenter la collecte côté humboldt.
 *
 * <p>Symétrique à {@code OtelSpanExporterBridge} (M7b.4b.3). Le mapping humboldt
 * {@code MetricData} → OTel {@code MetricData} se fait via {@link MetricDataMapper}.</p>
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
                // Skip metrics qui ne peuvent pas être mappés (types non supportés par le mapper)
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
        // Si l'OTel result est déjà completed (cas synchrone — InMemoryMetricExporter
        // du TCK marshalle immédiatement), on shortcut sans wrapper async humboldt.
        if (otelResult.isDone()) {
            return otelResult.isSuccess() ? CompletableResultCode.ofSuccess() : CompletableResultCode.ofFailure();
        }
        // Sinon : on attache un whenComplete OTel qui propagera le résultat à un
        // résultat humboldt initialement créé en "pending".
        // L'API humboldt.CompletableResultCode n'expose pas de constructeur public pour
        // un résultat pending — on retourne success/failure agrégé via join.
        otelResult.join(10, java.util.concurrent.TimeUnit.SECONDS);
        return otelResult.isSuccess() ? CompletableResultCode.ofSuccess() : CompletableResultCode.ofFailure();
    }
}
