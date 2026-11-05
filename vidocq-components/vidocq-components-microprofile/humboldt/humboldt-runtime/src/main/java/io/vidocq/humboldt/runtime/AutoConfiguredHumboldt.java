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
import io.vidocq.humboldt.sdk.common.Releasable;
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
    private final InMemoryExporters inMemory;
    /** The configured exporters that hold connections (the OTLP ones), for {@link #release()}. */
    private final List<Releasable> releasables;

    AutoConfiguredHumboldt(
            SdkTracerProvider tracerProvider,
            SdkMeterProvider meterProvider,
            SdkLoggerProvider loggerProvider,
            ContextPropagators propagators,
            InMemoryExporters inMemory,
            List<Releasable> releasables) {
        this.tracerProvider = tracerProvider;
        this.meterProvider = meterProvider;
        this.loggerProvider = loggerProvider;
        this.propagators = propagators;
        this.inMemory = inMemory;
        this.releasables = List.copyOf(releasables);
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
        return inMemory.span();
    }

    public InMemoryMetricExporter inMemoryMetricExporter() {
        return inMemory.metric();
    }

    public InMemoryLogRecordExporter inMemoryLogRecordExporter() {
        return inMemory.log();
    }

    /** Forces a flush of the 3 SDK providers (useful to drain before export). */
    public CompletableResultCode flush() {
        return CompletableResultCode.ofAll(List.of(
                tracerProvider.flush(),
                meterProvider.flush(),
                loggerProvider.flush()));
    }

    /**
     * Lets the exporters' connections to the collector go, for a checkpoint of the process (CRaC): exports what the
     * processors and readers hold, then releases each exporter once what it sent is answered. The providers,
     * processors and exporters stay the same objects: until {@link #reopen()}, an export fails at once and its data is
     * dropped.
     */
    public void release() {
        flush().join(10, TimeUnit.SECONDS);
        releasables.forEach(Releasable::release);
    }

    /** Takes the exporters' connections back after {@link #release()}, once the process is restored. */
    public void reopen() {
        releasables.forEach(Releasable::reopen);
    }

    /** The configured exporters {@link #release()} lets go: the OTLP ones. */
    List<Releasable> releasables() {
        return releasables;
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
