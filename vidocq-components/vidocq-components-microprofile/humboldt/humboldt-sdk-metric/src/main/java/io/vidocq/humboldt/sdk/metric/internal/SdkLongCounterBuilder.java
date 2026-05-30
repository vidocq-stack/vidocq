package io.vidocq.humboldt.sdk.metric.internal;

import io.opentelemetry.api.metrics.DoubleCounterBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongCounterBuilder;
import io.opentelemetry.api.metrics.ObservableLongCounter;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import io.vidocq.humboldt.sdk.metric.aggregation.SumAggregator;
import io.vidocq.humboldt.sdk.metric.data.AggregationTemporality;
import io.vidocq.humboldt.sdk.metric.data.InstrumentType;

import java.util.function.Consumer;

/**
 * Builder for {@link LongCounter} — produces an {@link SdkLongCounter} backed
 * by a cumulative {@link SumAggregator}.
 */
public final class SdkLongCounterBuilder implements LongCounterBuilder {

    private final String name;
    private final SdkMeter meter;
    private String description = "";
    private String unit = "";

    SdkLongCounterBuilder(String name, SdkMeter meter) {
        this.name = name;
        this.meter = meter;
    }

    @Override
    public LongCounterBuilder setDescription(String d) {
        this.description = d != null ? d : "";
        return this;
    }

    @Override
    public LongCounterBuilder setUnit(String u) {
        this.unit = u != null ? u : "";
        return this;
    }

    @Override
    public DoubleCounterBuilder ofDoubles() {
        SdkDoubleCounterBuilder dcb = new SdkDoubleCounterBuilder(name, meter);
        dcb.setDescription(description);
        dcb.setUnit(unit);
        return dcb;
    }

    @Override
    public LongCounter build() {
        SumAggregator agg = new SumAggregator();
        SdkLongCounter counter = new SdkLongCounter(agg);
        meter.register(new InstrumentEntry(
                name, description, unit,
                InstrumentType.COUNTER, AggregationTemporality.CUMULATIVE,
                /* monotonic */ true, agg));
        return counter;
    }

    @Override
    public ObservableLongCounter buildWithCallback(Consumer<ObservableLongMeasurement> callback) {
        SumAggregator agg = new SumAggregator();
        meter.register(new InstrumentEntry(name, description, unit,
                InstrumentType.OBSERVABLE_COUNTER, AggregationTemporality.CUMULATIVE, true, agg));
        var measurement = new ObservableLongMeasurementImpl(agg);
        meter.registerObservableCallback(() -> callback.accept(measurement));
        return new ObservableLongCounter() {};
    }

    @Override
    public ObservableLongMeasurement buildObserver() {
        SumAggregator agg = new SumAggregator();
        meter.register(new InstrumentEntry(name, description, unit,
                InstrumentType.OBSERVABLE_COUNTER, AggregationTemporality.CUMULATIVE, true, agg));
        return new ObservableLongMeasurementImpl(agg);
    }
}
