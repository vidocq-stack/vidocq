package io.vidocq.humboldt.sdk.metric.export;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.metric.data.MetricData;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Exporter qui accumule les {@link MetricData} en mémoire — exclusivement pour
 * les tests. {@code shutdown()} ne purge pas le contenu, voir
 * {@link #reset()} pour vider explicitement.
 */
public final class InMemoryMetricExporter implements MetricExporter {

    private final List<MetricData> collected = new CopyOnWriteArrayList<>();
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public static InMemoryMetricExporter create() {
        return new InMemoryMetricExporter();
    }

    public List<MetricData> getCollected() {
        return List.copyOf(collected);
    }

    public void reset() {
        collected.clear();
    }

    @Override
    public CompletableResultCode export(Collection<MetricData> metrics) {
        if (stopped.get()) return CompletableResultCode.ofFailure();
        collected.addAll(metrics);
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        stopped.set(true);
        return CompletableResultCode.ofSuccess();
    }
}
