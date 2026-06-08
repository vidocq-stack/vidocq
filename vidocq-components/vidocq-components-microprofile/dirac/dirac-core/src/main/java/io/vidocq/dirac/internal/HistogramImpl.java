/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.dirac.internal;

import io.vidocq.dirac.api.HistogramSnapshot;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.Snapshot;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * M3 in-memory histogram implementation.
 */
final class HistogramImpl implements Histogram {
    private static final double[] DEFAULT_PERCENTILES = {0.5d, 0.75d, 0.95d, 0.98d, 0.99d, 0.999d};

    private final LongAdder count = new LongAdder();
    private final LongAdder sum = new LongAdder();
    private final LongAccumulator max = new LongAccumulator(Long::max, Long.MIN_VALUE);
    private final ConcurrentLinkedQueue<Long> values = new ConcurrentLinkedQueue<>();
    private final double[] configuredPercentiles;
    private final double[] configuredBuckets;

    HistogramImpl() {
        this(null, false);
    }

    HistogramImpl(String metricName, boolean timerMetric) {
        this.configuredPercentiles = DistributionConfig.resolvePercentiles(metricName, DEFAULT_PERCENTILES);
        this.configuredBuckets = timerMetric
                ? DistributionConfig.resolveTimerBuckets(metricName)
                : DistributionConfig.resolveHistogramBuckets(metricName);
    }

    @Override
    public void update(int value) {
        update((long) value);
    }

    @Override
    public void update(long value) {
        values.add(value);
        count.increment();
        sum.add(value);
        max.accumulate(value);
    }

    @Override
    public long getCount() {
        return count.sum();
    }

    @Override
    public long getSum() {
        return sum.sum();
    }

    @Override
    public Snapshot getSnapshot() {
        var snapshotValues = values.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(snapshotValues);
        return new HistogramSnapshotImpl(snapshotValues, configuredPercentiles, configuredBuckets);
    }

    HistogramSnapshot snapshot() {
        var snapshot = (HistogramSnapshotImpl) getSnapshot();
        var percentileValues = snapshot.percentileValues();
        var min = snapshot.sortedValues.length == 0 ? 0 : snapshot.sortedValues[0];
        var max = snapshot.sortedValues.length == 0 ? 0 : snapshot.sortedValues[snapshot.sortedValues.length - 1];
        double p50 = percentileValueAt(percentileValues, 0);
        double p75 = percentileValueAt(percentileValues, 1);
        double p95 = percentileValueAt(percentileValues, 2);
        double p98 = percentileValueAt(percentileValues, 3);
        double p99 = percentileValueAt(percentileValues, 4);
        double p999 = percentileValueAt(percentileValues, 5);
        return new HistogramSnapshot(
                snapshot.size(),
                getSum(),
                min,
                max,
                snapshot.getMean(),
                p50,
                p75,
                p95,
                p98,
                p99,
                p999
        );
    }

    private static double percentileValueAt(Snapshot.PercentileValue[] values, int index) {
        if (index < 0 || index >= values.length) {
            return 0d;
        }
        return values[index].getValue();
    }

    private static final class HistogramSnapshotImpl extends Snapshot {
        private final long[] sortedValues;
        private final double[] percentiles;
        private final double[] buckets;

        private HistogramSnapshotImpl(long[] sortedValues, double[] percentiles, double[] buckets) {
            this.sortedValues = sortedValues;
            this.percentiles = percentiles;
            this.buckets = buckets;
        }

        @Override
        public long size() {
            return sortedValues.length;
        }

        @Override
        public double getMax() {
            if (sortedValues.length == 0) {
                return 0d;
            }
            return sortedValues[sortedValues.length - 1];
        }

        @Override
        public double getMean() {
            if (sortedValues.length == 0) {
                return 0d;
            }
            long total = 0;
            for (var value : sortedValues) {
                total += value;
            }
            return ((double) total) / sortedValues.length;
        }

        @Override
        public PercentileValue[] percentileValues() {
            var result = new PercentileValue[percentiles.length];
            for (int index = 0; index < percentiles.length; index++) {
                var percentile = percentiles[index];
                result[index] = new PercentileValue(percentile, valueAtPercentile(percentile));
            }
            return result;
        }

        @Override
        public HistogramBucket[] bucketValues() {
            var result = new HistogramBucket[buckets.length];
            for (int index = 0; index < buckets.length; index++) {
                var bucket = buckets[index];
                long bucketCount = 0L;
                for (var value : sortedValues) {
                    if (value <= bucket) {
                        bucketCount++;
                    }
                }
                result[index] = new HistogramBucket(bucket, bucketCount);
            }
            return result;
        }

        @Override
        public void dump(OutputStream output) {
            var builder = new StringBuilder();
            for (var value : sortedValues) {
                builder.append(value).append('\n');
            }
            try {
                output.write(builder.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to dump histogram snapshot", exception);
            }
        }

        private double valueAtPercentile(double percentile) {
            if (sortedValues.length == 0) {
                return 0d;
            }
            var rank = (int) Math.ceil(percentile * sortedValues.length);
            var index = Math.clamp(rank - 1, 0, sortedValues.length - 1);
            return sortedValues[index];
        }
    }
}
