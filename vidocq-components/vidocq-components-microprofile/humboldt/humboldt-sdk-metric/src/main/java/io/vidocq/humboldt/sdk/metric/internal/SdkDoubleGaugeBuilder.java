package io.vidocq.humboldt.sdk.metric.internal;

import io.opentelemetry.api.metrics.DoubleGauge;
import io.opentelemetry.api.metrics.DoubleGaugeBuilder;
import io.opentelemetry.api.metrics.LongGaugeBuilder;
import io.opentelemetry.api.metrics.ObservableDoubleGauge;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import io.vidocq.humboldt.sdk.metric.aggregation.DoubleLastValueAggregator;
import io.vidocq.humboldt.sdk.metric.data.AggregationTemporality;
import io.vidocq.humboldt.sdk.metric.data.InstrumentType;

import java.util.function.Consumer;

public final class SdkDoubleGaugeBuilder implements DoubleGaugeBuilder {

    private final String name;
    private final SdkMeter meter;
    private String description = "";
    private String unit = "";

    SdkDoubleGaugeBuilder(String name, SdkMeter meter) { this.name = name; this.meter = meter; }

    @Override public DoubleGaugeBuilder setDescription(String d) { this.description = d != null ? d : ""; return this; }
    @Override public DoubleGaugeBuilder setUnit(String u) { this.unit = u != null ? u : ""; return this; }

    @Override public LongGaugeBuilder ofLongs() {
        return new SdkLongGaugeBuilder(name, meter, description, unit);
    }

    @Override
    public DoubleGauge build() {
        DoubleLastValueAggregator agg = new DoubleLastValueAggregator();
        SdkDoubleGauge gauge = new SdkDoubleGauge(agg);
        meter.register(new InstrumentEntry(name, description, unit,
                InstrumentType.GAUGE, AggregationTemporality.CUMULATIVE, false, agg));
        return gauge;
    }

    @Override
    public ObservableDoubleGauge buildWithCallback(Consumer<ObservableDoubleMeasurement> callback) {
        DoubleLastValueAggregator agg = new DoubleLastValueAggregator();
        meter.register(new InstrumentEntry(name, description, unit,
                InstrumentType.OBSERVABLE_GAUGE, AggregationTemporality.CUMULATIVE, false, agg));
        var measurement = new ObservableDoubleMeasurementImpl(agg);
        meter.registerObservableCallback(() -> callback.accept(measurement));
        return new ObservableDoubleGauge() {};
    }

    @Override
    public ObservableDoubleMeasurement buildObserver() {
        DoubleLastValueAggregator agg = new DoubleLastValueAggregator();
        meter.register(new InstrumentEntry(name, description, unit,
                InstrumentType.OBSERVABLE_GAUGE, AggregationTemporality.CUMULATIVE, false, agg));
        return new ObservableDoubleMeasurementImpl(agg);
    }
}
