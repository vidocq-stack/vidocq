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
 * Humboldt implementation of {@link Meter}.
 *
 * <p>M4 MVP: {@link #counterBuilder(String)} and {@link #histogramBuilder(String)} are functional.
 * The other builders ({@code upDownCounterBuilder}, {@code gaugeBuilder}, observables, batchCallback)
 * throw {@code UnsupportedOperationException} — covered in M4b.</p>
 */
public final class SdkMeter implements Meter {

    private final InstrumentationScope scope;
    private final Resource resource;
    private final Clock clock;
    private final List<InstrumentEntry> instruments = new CopyOnWriteArrayList<>();
    private final List<Runnable> observableCallbacks = new CopyOnWriteArrayList<>();

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
        return new SdkLongUpDownCounterBuilder(name, this);
    }

    @Override
    public DoubleHistogramBuilder histogramBuilder(String name) {
        return new SdkDoubleHistogramBuilder(name, this);
    }

    @Override
    public DoubleGaugeBuilder gaugeBuilder(String name) {
        return new SdkDoubleGaugeBuilder(name, this);
    }

    void register(InstrumentEntry entry) {
        instruments.add(entry);
    }

    /** Registers an observable callback invoked on each {@link #collect(long, long)}. */
    void registerObservableCallback(Runnable callback) {
        observableCallbacks.add(callback);
    }

    public Clock clock() {
        return clock;
    }

    public Collection<MetricData> collect(long startEpochNanos, long epochNanos) {
        // Invoke all observable callbacks BEFORE collecting — each callback
        // updates its aggregator via the provided Measurement. The snapshot below
        // therefore reflects the most recent values.
        for (Runnable cb : observableCallbacks) {
            try { cb.run(); }
            catch (RuntimeException ignored) { /* erratic observable callback — silently ignored */ }
        }
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
