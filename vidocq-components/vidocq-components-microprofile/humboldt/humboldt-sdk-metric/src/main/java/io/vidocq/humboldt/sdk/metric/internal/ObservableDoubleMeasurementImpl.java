package io.vidocq.humboldt.sdk.metric.internal;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import io.vidocq.humboldt.sdk.metric.aggregation.Aggregator;

final class ObservableDoubleMeasurementImpl implements ObservableDoubleMeasurement {

    private final Aggregator<?> aggregator;

    ObservableDoubleMeasurementImpl(Aggregator<?> aggregator) { this.aggregator = aggregator; }

    @Override public void record(double value) { aggregator.recordDouble(value, Attributes.empty()); }
    @Override public void record(double value, Attributes attributes) {
        aggregator.recordDouble(value, attributes != null ? attributes : Attributes.empty());
    }
}
