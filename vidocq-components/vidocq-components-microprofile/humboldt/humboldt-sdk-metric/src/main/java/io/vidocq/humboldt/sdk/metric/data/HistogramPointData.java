package io.vidocq.humboldt.sdk.metric.data;

import io.opentelemetry.api.common.Attributes;

import java.util.List;

/**
 * Data point for a histogram with explicit buckets.
 *
 * @param startEpochNanos       start timestamp of the cumulative window
 * @param epochNanos            collection timestamp
 * @param attributes            point labels
 * @param sum                   cumulative sum of recorded values
 * @param count                 number of recorded values
 * @param min                   observed minimum (NaN if nothing was ever recorded)
 * @param max                   observed maximum (NaN if nothing was ever recorded)
 * @param boundaries            explicit boundaries (size n)
 * @param bucketCounts          counts per bucket (size n+1)
 */
public record HistogramPointData(
        long startEpochNanos,
        long epochNanos,
        Attributes attributes,
        double sum,
        long count,
        double min,
        double max,
        List<Double> boundaries,
        List<Long> bucketCounts) implements PointData {

    public HistogramPointData {
        if (attributes == null) attributes = Attributes.empty();
        if (boundaries == null) boundaries = List.of();
        if (bucketCounts == null) bucketCounts = List.of();
        boundaries = List.copyOf(boundaries);
        bucketCounts = List.copyOf(bucketCounts);
        if (bucketCounts.size() != boundaries.size() + 1) {
            throw new IllegalArgumentException(
                    "bucketCounts.size() must be boundaries.size()+1: "
                            + bucketCounts.size() + " vs " + boundaries.size());
        }
    }
}
