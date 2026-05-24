package io.vidocq.humboldt.sdk.metric.internal;

import io.opentelemetry.api.metrics.LongGauge;
import io.opentelemetry.api.metrics.LongGaugeBuilder;
import io.opentelemetry.api.metrics.ObservableLongGauge;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import io.vidocq.humboldt.sdk.metric.aggregation.LongLastValueAggregator;
import io.vidocq.humboldt.sdk.metric.data.AggregationTemporality;
import io.vidocq.humboldt.sdk.metric.data.InstrumentType;

import java.util.function.Consumer;

public final class SdkLongGaugeBuilder implements LongGaugeBuilder {

    private final String name;
    private final SdkMeter meter;
    private String description;
    private String unit;

    SdkLongGaugeBuilder(String name, SdkMeter meter, String description, String unit) {
        this.name = name; this.meter = meter;
        this.description = description != null ? description : "";
        this.unit = unit != null ? unit : "";
    }

    @Override public LongGaugeBuilder setDescription(String d) { this.description = d != null ? d : ""; return this; }
    @Override public LongGaugeBuilder setUnit(String u) { this.unit = u != null ? u : ""; return this; }

    @Override
    public LongGauge build() {
        LongLastValueAggregator agg = new LongLastValueAggregator();
        SdkLongGauge gauge = new SdkLongGauge(agg);
        meter.register(new InstrumentEntry(name, description, unit,
                InstrumentType.GAUGE, AggregationTemporality.CUMULATIVE, false, agg));
        return gauge;
    }

    @Override
    public ObservableLongGauge buildWithCallback(Consumer<ObservableLongMeasurement> callback) {
        LongLastValueAggregator agg = new LongLastValueAggregator();
        meter.register(new InstrumentEntry(name, description, unit,
                InstrumentType.OBSERVABLE_GAUGE, AggregationTemporality.CUMULATIVE, false, agg));
        var measurement = new ObservableLongMeasurementImpl(agg);
        meter.registerObservableCallback(() -> callback.accept(measurement));
        return new ObservableLongGauge() {};
    }

    @Override
    public ObservableLongMeasurement buildObserver() {
        LongLastValueAggregator agg = new LongLastValueAggregator();
        meter.register(new InstrumentEntry(name, description, unit,
                InstrumentType.OBSERVABLE_GAUGE, AggregationTemporality.CUMULATIVE, false, agg));
        return new ObservableLongMeasurementImpl(agg);
    }
}
