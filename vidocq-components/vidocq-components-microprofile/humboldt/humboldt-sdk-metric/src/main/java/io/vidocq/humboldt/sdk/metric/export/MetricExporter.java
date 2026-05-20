package io.vidocq.humboldt.sdk.metric.export;

import io.vidocq.humboldt.sdk.metric.data.MetricData;
import io.vidocq.humboldt.sdk.common.CompletableResultCode;

import java.util.Collection;

/**
 * Exporter de métriques collectées (InMemory, Logging, OTLP/HTTP-JSON, ...).
 *
 * <p>Thread-safe — peut être partagé entre plusieurs {@link MetricReader}.</p>
 */
public interface MetricExporter extends AutoCloseable {

    CompletableResultCode export(Collection<MetricData> metrics);

    CompletableResultCode flush();

    CompletableResultCode shutdown();

    @Override
    default void close() {
        shutdown().join(10, java.util.concurrent.TimeUnit.SECONDS);
    }
}
