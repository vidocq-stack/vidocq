package io.vidocq.humboldt.sdk.metric.internal;

import io.opentelemetry.api.metrics.DoubleUpDownCounter;
import io.opentelemetry.api.metrics.DoubleUpDownCounterBuilder;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import io.opentelemetry.api.metrics.ObservableDoubleUpDownCounter;
import io.vidocq.humboldt.sdk.metric.aggregation.DoubleSumAggregator;
import io.vidocq.humboldt.sdk.metric.data.AggregationTemporality;
import io.vidocq.humboldt.sdk.metric.data.InstrumentType;

import java.util.function.Consumer;

public final class SdkDoubleUpDownCounterBuilder implements DoubleUpDownCounterBuilder {

    private final String name;
    private final SdkMeter meter;
    private String description;
    private String unit;

    SdkDoubleUpDownCounterBuilder(String name, SdkMeter meter, String description, String unit) {
        this.name = name; this.meter = meter;
        this.description = description != null ? description : "";
        this.unit = unit != null ? unit : "";
    }

    @Override public DoubleUpDownCounterBuilder setDescription(String d) { this.description = d != null ? d : ""; return this; }
    @Override public DoubleUpDownCounterBuilder setUnit(String u) { this.unit = u != null ? u : ""; return this; }

    @Override
    public DoubleUpDownCounter build() {
        DoubleSumAggregator agg = new DoubleSumAggregator();
        SdkDoubleUpDownCounter counter = new SdkDoubleUpDownCounter(agg);
        meter.register(new InstrumentEntry(name, description, unit,
                InstrumentType.UP_DOWN_COUNTER, AggregationTemporality.CUMULATIVE, false, agg));
        return counter;
    }

    @Override
    public ObservableDoubleUpDownCounter buildWithCallback(Consumer<ObservableDoubleMeasurement> callback) {
        DoubleSumAggregator agg = new DoubleSumAggregator();
        meter.register(new InstrumentEntry(name, description, unit,
                InstrumentType.OBSERVABLE_UP_DOWN_COUNTER, AggregationTemporality.CUMULATIVE, false, agg));
        var measurement = new ObservableDoubleMeasurementImpl(agg);
        meter.registerObservableCallback(() -> callback.accept(measurement));
        return new ObservableDoubleUpDownCounter() {};
    }

    @Override
    public ObservableDoubleMeasurement buildObserver() {
        DoubleSumAggregator agg = new DoubleSumAggregator();
        meter.register(new InstrumentEntry(name, description, unit,
                InstrumentType.OBSERVABLE_UP_DOWN_COUNTER, AggregationTemporality.CUMULATIVE, false, agg));
        return new ObservableDoubleMeasurementImpl(agg);
    }
}
