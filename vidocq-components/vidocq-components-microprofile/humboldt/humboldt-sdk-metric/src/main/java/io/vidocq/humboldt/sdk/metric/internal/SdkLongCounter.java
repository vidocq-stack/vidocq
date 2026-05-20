package io.vidocq.humboldt.sdk.metric.internal;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.metric.aggregation.SumAggregator;

/**
 * Implémentation interne de {@link LongCounter} — délègue à {@link SumAggregator}.
 *
 * <p>Refuse les valeurs négatives (counter monotonic, alignement spec OTel).</p>
 */
public final class SdkLongCounter implements LongCounter {

    private final SumAggregator aggregator;

    SdkLongCounter(SumAggregator aggregator) {
        this.aggregator = aggregator;
    }

    @Override
    public void add(long value) {
        add(value, Attributes.empty());
    }

    @Override
    public void add(long value, Attributes attributes) {
        if (value < 0L) return; // counter monotonic — ignore négatif (alignement OTel)
        aggregator.recordLong(value, attributes != null ? attributes : Attributes.empty());
    }

    @Override
    public void add(long value, Attributes attributes, Context context) {
        add(value, attributes);
    }
}
