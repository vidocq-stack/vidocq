package io.vidocq.humboldt.sdk.metric.internal;

import io.opentelemetry.api.metrics.DoubleGaugeBuilder;
import io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import io.opentelemetry.api.metrics.LongCounterBuilder;
import io.opentelemetry.api.metrics.LongUpDownCounterBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.vidocq.humboldt.sdk.common.Clock;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.metric.data.MetricData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Implémentation Humboldt de {@link Meter}.
 *
 * <p>M4 MVP : {@link #counterBuilder(String)} et {@link #histogramBuilder(String)} fonctionnels.
 * Les autres builders ({@code upDownCounterBuilder}, {@code gaugeBuilder}, observables, batchCallback)
 * lancent {@code UnsupportedOperationException} — couverts en M4b.</p>
 */
public final class SdkMeter implements Meter {

    private final InstrumentationScope scope;
    private final Resource resource;
    private final Clock clock;
    private final List<InstrumentEntry> instruments = new CopyOnWriteArrayList<>();

    public SdkMeter(InstrumentationScope scope, Resource resource, Clock clock) {
        this.scope = scope;
        this.resource = resource;
        this.clock = clock;
    }

    @Override
    public LongCounterBuilder counterBuilder(String name) {
        return new SdkLongCounterBuilder(name, this);
    }

    @Override
    public LongUpDownCounterBuilder upDownCounterBuilder(String name) {
        throw new UnsupportedOperationException(
                "M4 MVP : UpDownCounter pas encore supporté (différé en M4b)");
    }

    @Override
    public DoubleHistogramBuilder histogramBuilder(String name) {
        return new SdkDoubleHistogramBuilder(name, this);
    }

    @Override
    public DoubleGaugeBuilder gaugeBuilder(String name) {
        throw new UnsupportedOperationException(
                "M4 MVP : Observable Gauge pas encore supporté (différé en M4b)");
    }

    void register(InstrumentEntry entry) {
        instruments.add(entry);
    }

    public Clock clock() {
        return clock;
    }

    public Collection<MetricData> collect(long startEpochNanos, long epochNanos) {
        List<MetricData> out = new ArrayList<>(instruments.size());
        for (InstrumentEntry e : instruments) {
            out.add(new MetricData(
                    resource, scope,
                    e.name(), e.description(), e.unit(),
                    e.type(), e.temporality(), e.monotonic(),
                    e.aggregator().collect(startEpochNanos, epochNanos)));
        }
        return out;
    }
}
