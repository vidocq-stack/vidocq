package io.vidocq.humboldt.sdk.metric.export;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;

/**
 * Metrics reader — pull-based or push-based. Connected to an
 * {@link io.vidocq.humboldt.sdk.metric.SdkMeterProvider} via {@code register()}.
 *
 * <p>Standard implementation: {@code PeriodicMetricReader}.</p>
 */
public interface MetricReader extends AutoCloseable {

    /** The provider calls this once so the reader can invoke {@code provider.collectMetrics()}. */
    void register(CollectionRegistration registration);

    /** Forces immediate collection + export. */
    CompletableResultCode flush();

    CompletableResultCode shutdown();

    @Override
    default void close() {
        shutdown().join(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Callback provided by SdkMeterProvider to the reader (to trigger collection). */
    @FunctionalInterface
    interface CollectionRegistration {
        java.util.Collection<io.vidocq.humboldt.sdk.metric.data.MetricData> collectAllMetrics();
    }
}
