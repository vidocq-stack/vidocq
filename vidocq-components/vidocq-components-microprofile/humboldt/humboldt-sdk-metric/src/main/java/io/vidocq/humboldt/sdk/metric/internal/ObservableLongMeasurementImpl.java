package io.vidocq.humboldt.sdk.metric.internal;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import io.vidocq.humboldt.sdk.metric.aggregation.Aggregator;

/** Internal implementation — delegates each {@code record(...)} to an {@link Aggregator}. */
final class ObservableLongMeasurementImpl implements ObservableLongMeasurement {

    private final Aggregator<?> aggregator;

    ObservableLongMeasurementImpl(Aggregator<?> aggregator) { this.aggregator = aggregator; }

    @Override public void record(long value) { aggregator.recordLong(value, Attributes.empty()); }
    @Override public void record(long value, Attributes attributes) {
        aggregator.recordLong(value, attributes != null ? attributes : Attributes.empty());
    }
}
