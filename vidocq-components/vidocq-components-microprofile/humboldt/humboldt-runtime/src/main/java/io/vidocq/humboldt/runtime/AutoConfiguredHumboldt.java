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
 * Handle produit par {@link HumboldtAutoConfigure#configure()}.
 *
 * <p>Implémente {@link OpenTelemetry} pour pouvoir être passé directement à
 * {@code GlobalOpenTelemetry.set(...)} ou aux interceptors / filters Humboldt.</p>
 *
 * <p>Expose aussi les providers SDK concrets pour les tests E2E et pour les
 * exporters in-memory (récupération du contenu via {@link #inMemorySpanExporter()}
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

    /** @return l'exporter in-memory de spans si l'autoconfig en a installé un, sinon {@code null}. */
    public InMemorySpanExporter inMemorySpanExporter() {
        return inMemorySpanExporter;
    }

    public InMemoryMetricExporter inMemoryMetricExporter() {
        return inMemoryMetricExporter;
    }

    public InMemoryLogRecordExporter inMemoryLogRecordExporter() {
        return inMemoryLogRecordExporter;
    }

    /** Force le flush des 3 SDK providers (utile pour drainer avant export). */
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
