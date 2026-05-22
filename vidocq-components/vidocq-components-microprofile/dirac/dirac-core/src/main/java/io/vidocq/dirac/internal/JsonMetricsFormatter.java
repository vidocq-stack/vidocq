package io.vidocq.dirac.internal;

import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.Metric;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Snapshot;
import org.eclipse.microprofile.metrics.Timer;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Sérialise un registre en format JSON spec MicroProfile Metrics §3.2.
 *
 * <p>Implémenté sans bibliothèque JSON externe — {@code StringBuilder} uniquement.
 * Clés des métriques : {@code metricName[;tagKey=tagVal]*} (tags triés par nom).
 * Toutes les durées des timers sont converties nanos → secondes.</p>
 */
public final class JsonMetricsFormatter {

    public String format(MetricRegistry registry) {
        return format(Objects.requireNonNull(registry, "registry must not be null").getMetrics());
    }

    public String format(Map<MetricID, Metric> metrics) {
        Objects.requireNonNull(metrics, "metrics must not be null");
        var builder = new StringBuilder("{");
        var first = true;

        for (Map.Entry<MetricID, Metric> entry : metrics.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;

            builder.append('"').append(jsonKey(entry.getKey())).append("\":");
            appendValue(builder, entry.getValue());
        }

        return builder.append('}').toString();
    }

    private static String jsonKey(MetricID metricId) {
        var tags = new TreeMap<String, String>();
        for (var tag : metricId.getTagsAsArray()) {
            tags.put(tag.getTagName(), tag.getTagValue());
        }
        if (tags.isEmpty()) {
            return metricId.getName();
        }
        var builder = new StringBuilder(metricId.getName());
        tags.forEach((k, v) -> builder.append(';').append(k).append('=').append(v));
        return builder.toString();
    }

    private static void appendValue(StringBuilder builder, Metric metric) {
        if (metric instanceof Counter counter) {
            builder.append(counter.getCount());
            return;
        }
        if (metric instanceof Gauge<?> gauge) {
            appendNumber(builder, toDouble(gauge.getValue()));
            return;
        }
        if (metric instanceof Histogram histogram) {
            appendHistogram(builder, histogram);
            return;
        }
        if (metric instanceof Timer timer) {
            appendTimer(builder, timer);
        }
    }

    private static void appendHistogram(StringBuilder builder, Histogram histogram) {
        builder.append('{');
        builder.append("\"count\":").append(histogram.getCount());
        builder.append(",\"sum\":").append(histogram.getSum());
        for (Snapshot.PercentileValue pv : histogram.getSnapshot().percentileValues()) {
            builder.append(",\"").append(percentileKey(pv.getPercentile())).append("\":");
            appendNumber(builder, pv.getValue());
        }
        builder.append('}');
    }

    private static void appendTimer(StringBuilder builder, Timer timer) {
        builder.append('{');
        builder.append("\"count\":").append(timer.getCount());
        builder.append(",\"elapsedTime\":");
        appendNumber(builder, nanosToSeconds(timer.getElapsedTime().toNanos()));
        for (Snapshot.PercentileValue pv : timer.getSnapshot().percentileValues()) {
            builder.append(",\"").append(percentileKey(pv.getPercentile())).append("\":");
            appendNumber(builder, nanosToSeconds(pv.getValue()));
        }
        builder.append('}');
    }

    /**
     * Converts a percentile double (0.5, 0.75, 0.95, 0.98, 0.99, 0.999) to its JSON key.
     * Multiples of 0.01 produce pNN (e.g. p50); finer values keep three digits (e.g. p999).
     */
    private static String percentileKey(double percentile) {
        int thousandths = (int) Math.round(percentile * 1000);
        if (thousandths % 10 == 0) {
            return "p" + (thousandths / 10);
        }
        return "p" + thousandths;
    }

    private static void appendNumber(StringBuilder builder, double value) {
        if (!Double.isFinite(value)) {
            builder.append("null");
            return;
        }
        if (Math.rint(value) == value && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE) {
            builder.append((long) value);
        } else {
            builder.append(value);
        }
    }

    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.NaN;
    }

    private static double nanosToSeconds(double nanos) {
        return nanos / 1_000_000_000d;
    }
}
