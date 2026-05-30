package io.vidocq.humboldt.sdk.metric.data;

/**
 * OTel temporality — {@link #CUMULATIVE} sends the total state since startup,
 * {@link #DELTA} sends the delta since the last collection.
 *
 * <p>M4 MVP: Humboldt only emits CUMULATIVE. DELTA = M4b.</p>
 */
public enum AggregationTemporality {
    DELTA,
    CUMULATIVE
}
