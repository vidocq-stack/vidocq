package io.vidocq.humboldt.sdk.metric.internal;

import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleCounterBuilder;
import io.opentelemetry.api.metrics.ObservableDoubleCounter;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import io.vidocq.humboldt.sdk.metric.aggregation.DoubleSumAggregator;
import io.vidocq.humboldt.sdk.metric.data.AggregationTemporality;
import io.vidocq.humboldt.sdk.metric.data.InstrumentType;

import java.util.function.Consumer;

public final class SdkDoubleCounterBuilder implements DoubleCounterBuilder {

    private final String name;
    private final SdkMeter meter;
    private String description = "";
    private String unit = "";

    SdkDoubleCounterBuilder(String name, SdkMeter meter) { this.name = name; this.meter = meter; }

    @Override public DoubleCounterBuilder setDescription(String d) { this.description = d != null ? d : ""; return this; }
    @Override public DoubleCounterBuilder setUnit(String u) { this.unit = u != null ? u : ""; return this; }

    @Override
    public DoubleCounter build() {
        DoubleSumAggregator agg = new DoubleSumAggregator();
        SdkDoubleCounter counter = new SdkDoubleCounter(agg);
        meter.register(new InstrumentEntry(name, description, unit,
                InstrumentType.COUNTER, AggregationTemporality.CUMULATIVE, true, agg));
        return counter;
    }

    @Override
    public ObservableDoubleCounter buildWithCallback(Consumer<ObservableDoubleMeasurement> callback) {
        DoubleSumAggregator agg = new DoubleSumAggregator();
        meter.register(new InstrumentEntry(name, description, unit,
                InstrumentType.OBSERVABLE_COUNTER, AggregationTemporality.CUMULATIVE, true, agg));
        var measurement = new ObservableDoubleMeasurementImpl(agg);
        meter.registerObservableCallback(() -> callback.accept(measurement));
        return new ObservableDoubleCounter() {};
    }

    @Override
    public ObservableDoubleMeasurement buildObserver() {
        DoubleSumAggregator agg = new DoubleSumAggregator();
        meter.register(new InstrumentEntry(name, description, unit,
                InstrumentType.OBSERVABLE_COUNTER, AggregationTemporality.CUMULATIVE, true, agg));
        return new ObservableDoubleMeasurementImpl(agg);
    }
}
