package io.vidocq.humboldt.sdk.metric.export;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.common.InMemoryExporterBase;
import io.vidocq.humboldt.sdk.metric.data.MetricData;

import java.util.Collection;

/**
 * Exporter that accumulates {@link MetricData} in memory — for tests.
 * Delegates the shared skeleton to {@link InMemoryExporterBase}.
 */
public final class InMemoryMetricExporter extends InMemoryExporterBase<MetricData> implements MetricExporter {

    public static InMemoryMetricExporter create() {
        return new InMemoryMetricExporter();
    }

    @Override
    public CompletableResultCode export(Collection<MetricData> metrics) {
        return addAll(metrics);
    }

    @Override
    public CompletableResultCode flush() {
        return flushBase();
    }

    @Override
    public CompletableResultCode shutdown() {
        return shutdownBase();
    }
}
