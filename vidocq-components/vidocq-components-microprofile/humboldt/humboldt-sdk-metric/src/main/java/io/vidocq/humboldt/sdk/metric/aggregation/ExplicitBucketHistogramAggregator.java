package io.vidocq.humboldt.sdk.metric.aggregation;

import io.opentelemetry.api.common.Attributes;
import io.vidocq.humboldt.sdk.metric.data.HistogramPointData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aggregation Histogram cumulative avec bornes explicites.
 *
 * <p>Bornes par défaut alignées sur la spec OTel : 0, 5, 10, 25, 50, 75, 100,
 * 250, 500, 750, 1000, 2500, 5000, 7500, 10000 (15 bornes → 16 buckets).</p>
 *
 * <p>Storage : {@link ConcurrentHashMap} par {@link Attributes}, chaque entrée
 * est un {@link BucketAccumulator} protégé par {@code synchronized} (recordDouble
 * doit muter count, sum, min, max et bucketCounts atomiquement vis-à-vis de collect).</p>
 */
public final class ExplicitBucketHistogramAggregator implements Aggregator<HistogramPointData> {

    public static final List<Double> DEFAULT_BOUNDARIES = List.of(
            0.0, 5.0, 10.0, 25.0, 50.0, 75.0, 100.0,
            250.0, 500.0, 750.0, 1000.0, 2500.0, 5000.0, 7500.0, 10000.0);

    private final double[] boundaries;
    private final List<Double> boundariesView;
    private final ConcurrentHashMap<Attributes, BucketAccumulator> accums = new ConcurrentHashMap<>();

    public ExplicitBucketHistogramAggregator() {
        this(DEFAULT_BOUNDARIES);
    }

    public ExplicitBucketHistogramAggregator(List<Double> boundaries) {
        if (boundaries == null || boundaries.isEmpty()) boundaries = DEFAULT_BOUNDARIES;
        this.boundaries = new double[boundaries.size()];
        for (int i = 0; i < boundaries.size(); i++) {
            this.boundaries[i] = boundaries.get(i);
        }
        this.boundariesView = List.copyOf(boundaries);
    }

    @Override
    public void recordDouble(double value, Attributes attributes) {
        Attributes key = attributes != null ? attributes : Attributes.empty();
        accums.computeIfAbsent(key, k -> new BucketAccumulator(boundaries.length + 1))
                .record(value, bucketIndex(value));
    }

    private int bucketIndex(double value) {
        // Bucket linéaire : retourne i si value ≤ boundaries[i], sinon boundaries.length
        for (int i = 0; i < boundaries.length; i++) {
            if (value <= boundaries[i]) return i;
        }
        return boundaries.length;
    }

    @Override
    public List<HistogramPointData> collect(long startEpochNanos, long epochNanos) {
        List<HistogramPointData> out = new ArrayList<>(accums.size());
        accums.forEach((attrs, acc) -> {
            BucketAccumulator.Snapshot snap = acc.snapshot();
            out.add(new HistogramPointData(
                    startEpochNanos, epochNanos, attrs,
                    snap.sum, snap.count, snap.min, snap.max,
                    boundariesView, snap.bucketCounts));
        });
        return List.copyOf(out);
    }

    private static final class BucketAccumulator {
        private final long[] bucketCounts;
        private double sum = 0.0;
        private long count = 0L;
        private double min = Double.NaN;
        private double max = Double.NaN;

        BucketAccumulator(int nBuckets) {
            this.bucketCounts = new long[nBuckets];
        }

        synchronized void record(double value, int idx) {
            sum += value;
            count++;
            bucketCounts[idx]++;
            if (Double.isNaN(min) || value < min) min = value;
            if (Double.isNaN(max) || value > max) max = value;
        }

        synchronized Snapshot snapshot() {
            List<Long> bc = new ArrayList<>(bucketCounts.length);
            for (long c : bucketCounts) bc.add(c);
            return new Snapshot(sum, count, min, max, List.copyOf(bc));
        }

        record Snapshot(double sum, long count, double min, double max, List<Long> bucketCounts) {}
    }
}
