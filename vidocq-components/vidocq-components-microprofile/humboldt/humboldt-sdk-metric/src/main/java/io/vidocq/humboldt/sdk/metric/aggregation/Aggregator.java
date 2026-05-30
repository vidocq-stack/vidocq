package io.vidocq.humboldt.sdk.metric.aggregation;

import io.opentelemetry.api.common.Attributes;
import io.vidocq.humboldt.sdk.metric.data.PointData;

import java.util.List;

/**
 * Per-instrument storage: receives hot-path recordings
 * ({@link #recordLong(long, Attributes)} / {@link #recordDouble(double, Attributes)})
 * and produces a snapshot at collection time ({@link #collect(long, long)}).
 *
 * <p>CUMULATIVE implementations — accumulated state persists across collections,
 * and the exported value is cumulative since startup.</p>
 *
 * @param <P> produced point type (LongPointData for Sum, HistogramPointData for Histogram, ...)
 */
public interface Aggregator<P extends PointData> {

    /** Records a long value with its associated attributes. */
    default void recordLong(long value, Attributes attributes) {
        recordDouble((double) value, attributes);
    }

    /** Records a double value with its associated attributes. */
    default void recordDouble(double value, Attributes attributes) {
        recordLong((long) value, attributes);
    }

    /**
     * Produces a snapshot of the points accumulated since startup.
     *
     * @param startEpochNanos start time of the SdkMeterProvider (fixed for CUMULATIVE)
     * @param epochNanos      timestamp of the current collection
     * @return immutable list of points per attribute set
     */
    List<P> collect(long startEpochNanos, long epochNanos);
}
