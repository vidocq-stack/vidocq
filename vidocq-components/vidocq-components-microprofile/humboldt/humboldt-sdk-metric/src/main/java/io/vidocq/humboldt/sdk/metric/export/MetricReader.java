package io.vidocq.humboldt.sdk.metric.export;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;

/**
 * Reader de métriques — pull-based ou push-based. Branché sur un
 * {@link io.vidocq.humboldt.sdk.metric.SdkMeterProvider} via {@code register()}.
 *
 * <p>Implémentations standard : {@code PeriodicMetricReader}.</p>
 */
public interface MetricReader extends AutoCloseable {

    /** Le provider appelle ceci une fois pour permettre au reader d'invoquer {@code provider.collectMetrics()}. */
    void register(CollectionRegistration registration);

    /** Force une collecte + export immédiats. */
    CompletableResultCode flush();

    CompletableResultCode shutdown();

    @Override
    default void close() {
        shutdown().join(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Callback fourni par le SdkMeterProvider au reader (pour appeler la collecte). */
    @FunctionalInterface
    interface CollectionRegistration {
        java.util.Collection<io.vidocq.humboldt.sdk.metric.data.MetricData> collectAllMetrics();
    }
}
