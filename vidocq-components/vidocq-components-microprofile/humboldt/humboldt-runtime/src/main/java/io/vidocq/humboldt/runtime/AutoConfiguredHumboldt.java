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
package io.vidocq.humboldt.runtime;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.logs.LoggerProvider;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.log.SdkLoggerProvider;
import io.vidocq.humboldt.sdk.metric.SdkMeterProvider;
import io.vidocq.humboldt.sdk.trace.SdkTracerProvider;
import io.vidocq.humboldt.sdk.trace.export.InMemorySpanExporter;
import io.vidocq.humboldt.sdk.metric.export.InMemoryMetricExporter;
import io.vidocq.humboldt.sdk.log.export.InMemoryLogRecordExporter;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Handle produced by {@link HumboldtAutoConfigure#configure()}.
 *
 * <p>Implements {@link OpenTelemetry} so it can be passed directly to
 * {@code GlobalOpenTelemetry.set(...)} or to Humboldt interceptors / filters.</p>
 *
 * <p>Also exposes the concrete SDK providers for E2E tests and for
 * in-memory exporters (retrieve contents via {@link #inMemorySpanExporter()}
 * etc.).</p>
 */
public final class AutoConfiguredHumboldt implements OpenTelemetry, AutoCloseable {

    private final SdkTracerProvider tracerProvider;
    private final SdkMeterProvider meterProvider;
    private final SdkLoggerProvider loggerProvider;
    private final ContextPropagators propagators;
    private final InMemorySpanExporter inMemorySpanExporter;
    private final InMemoryMetricExporter inMemoryMetricExporter;
    private final InMemoryLogRecordExporter inMemoryLogRecordExporter;

    AutoConfiguredHumboldt(
            SdkTracerProvider tracerProvider,
            SdkMeterProvider meterProvider,
            SdkLoggerProvider loggerProvider,
            ContextPropagators propagators,
            InMemorySpanExporter inMemorySpanExporter,
            InMemoryMetricExporter inMemoryMetricExporter,
            InMemoryLogRecordExporter inMemoryLogRecordExporter) {
        this.tracerProvider = tracerProvider;
        this.meterProvider = meterProvider;
        this.loggerProvider = loggerProvider;
        this.propagators = propagators;
        this.inMemorySpanExporter = inMemorySpanExporter;
        this.inMemoryMetricExporter = inMemoryMetricExporter;
        this.inMemoryLogRecordExporter = inMemoryLogRecordExporter;
    }

    @Override
    public TracerProvider getTracerProvider() {
        return tracerProvider;
    }

    @Override
    public MeterProvider getMeterProvider() {
        return meterProvider;
    }

    @Override
    public LoggerProvider getLogsBridge() {
        return loggerProvider;
    }

    @Override
    public ContextPropagators getPropagators() {
        return propagators;
    }

    public SdkTracerProvider sdkTracerProvider() {
        return tracerProvider;
    }

    public SdkMeterProvider sdkMeterProvider() {
        return meterProvider;
    }

    public SdkLoggerProvider sdkLoggerProvider() {
        return loggerProvider;
    }

    /** @return the in-memory span exporter if autoconfig installed one, otherwise {@code null}. */
    public InMemorySpanExporter inMemorySpanExporter() {
        return inMemorySpanExporter;
    }

    public InMemoryMetricExporter inMemoryMetricExporter() {
        return inMemoryMetricExporter;
    }

    public InMemoryLogRecordExporter inMemoryLogRecordExporter() {
        return inMemoryLogRecordExporter;
    }

    /** Forces a flush of the 3 SDK providers (useful to drain before export). */
    public CompletableResultCode flush() {
        return CompletableResultCode.ofAll(List.of(
                tracerProvider.flush(),
                meterProvider.flush(),
                loggerProvider.flush()));
    }

    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofAll(List.of(
                tracerProvider.shutdown(),
                meterProvider.shutdown(),
                loggerProvider.shutdown()));
    }

    @Override
    public void close() {
        shutdown().join(10, TimeUnit.SECONDS);
    }
}
