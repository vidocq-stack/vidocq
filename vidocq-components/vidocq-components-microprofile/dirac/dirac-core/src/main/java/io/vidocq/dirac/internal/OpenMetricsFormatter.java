package io.vidocq.dirac.internal;

import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Gauge;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.Metric;
import org.eclipse.microprofile.metrics.MetricID;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Snapshot;
import org.eclipse.microprofile.metrics.Timer;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Formatte un registre en exposition texte OpenMetrics/Prometheus.
 */
public final class OpenMetricsFormatter {

    public String format(MetricRegistry registry) {
        return format(
                Objects.requireNonNull(registry, "registry must not be null").getMetrics(),
                registry);
    }

    public String format(Map<MetricID, Metric> metrics, MetricRegistry metadataSource) {
        Objects.requireNonNull(metrics, "metrics must not be null");
        Objects.requireNonNull(metadataSource, "metadataSource must not be null");
        var builder = new StringBuilder();
        var helped = new java.util.HashSet<String>();

        for (Map.Entry<MetricID, Metric> entry : metrics.entrySet()) {
            var metricId = entry.getKey();
            var metric = entry.getValue();
            var normalizedName = normalizeName(metricId.getName());
            var metadata = metadataSource.getMetadata(metricId.getName());

            if (helped.add(metricId.getName())) {
                appendHeader(builder, normalizedName, metric, metadata);
            }

            appendMetricValue(builder, normalizedName, metricId, metric);
        }

        builder.append("# EOF\n");
        return builder.toString();
    }

    private static void appendHeader(StringBuilder builder, String normalizedName, Metric metric, Metadata metadata) {
        var description = metadata != null && metadata.getDescription() != null && !metadata.getDescription().isBlank()
                ? metadata.getDescription()
                : normalizedName;
        builder.append("# HELP ").append(normalizedName).append(' ').append(escapeHelp(description)).append('\n');
        builder.append("# TYPE ").append(normalizedName).append(' ').append(prometheusType(metric)).append('\n');
    }

    private static void appendMetricValue(StringBuilder builder, String normalizedName, MetricID metricId, Metric metric) {
        if (metric instanceof Counter counter) {
            appendSample(builder, counterSampleName(normalizedName), metricId, Map.of(), counter.getCount());
            return;
        }

        if (metric instanceof Gauge<?> gauge) {
            appendSample(builder, normalizedName, metricId, Map.of(), toDouble(gauge.getValue()));
            return;
        }

        if (metric instanceof Histogram histogram) {
            appendHistogram(builder, normalizedName, metricId, histogram);
            return;
        }

        if (metric instanceof Timer timer) {
            appendTimer(builder, normalizedName, metricId, timer);
        }
    }

    private static void appendHistogram(StringBuilder builder, String normalizedName, MetricID metricId, Histogram histogram) {
        var snapshot = histogram.getSnapshot();
        for (Snapshot.HistogramBucket bucket : snapshot.bucketValues()) {
            appendSample(builder, normalizedName + "_bucket", metricId,
                    Map.of("le", formatNumber(bucket.getBucket())), bucket.getCount());
        }
        appendSample(builder, normalizedName + "_bucket", metricId, Map.of("le", "+Inf"), histogram.getCount());
        appendSample(builder, normalizedName + "_count", metricId, Map.of(), histogram.getCount());
        appendSample(builder, normalizedName + "_sum", metricId, Map.of(), histogram.getSum());
    }

    private static void appendTimer(StringBuilder builder, String normalizedName, MetricID metricId, Timer timer) {
        var secondsName = normalizedName + "_seconds";
        var snapshot = timer.getSnapshot();
        for (Snapshot.PercentileValue percentile : snapshot.percentileValues()) {
            appendSample(builder, secondsName, metricId,
                    Map.of("quantile", formatNumber(percentile.getPercentile())), nanosToSeconds(percentile.getValue()));
        }
        appendSample(builder, secondsName + "_count", metricId, Map.of(), timer.getCount());
        appendSample(builder, secondsName + "_sum", metricId, Map.of(), nanosToSeconds(timer.getElapsedTime().toNanos()));
    }

    private static void appendSample(StringBuilder builder,
                                     String sampleName,
                                     MetricID metricId,
                                     Map<String, String> extraLabels,
                                     double value) {
        builder.append(sampleName).append(renderLabels(metricId, extraLabels)).append(' ').append(formatNumber(value)).append('\n');
    }

    private static String renderLabels(MetricID metricId, Map<String, String> extraLabels) {
        var labels = new TreeMap<String, String>();
        for (var tag : metricId.getTagsAsArray()) {
            labels.put(tag.getTagName(), tag.getTagValue());
        }
        labels.putAll(extraLabels);

        if (labels.isEmpty()) {
            return "";
        }

        var builder = new StringBuilder("{");
        var first = true;
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append(normalizeName(entry.getKey()))
                    .append("=\"")
                    .append(escapeLabel(entry.getValue()))
                    .append('"');
        }
        return builder.append('}').toString();
    }

    private static String normalizeName(String input) {
        var source = Objects.requireNonNull(input, "input must not be null");
        var builder = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            var c = source.charAt(i);
            if (Character.isAlphabetic(c) || Character.isDigit(c) || c == '_' || c == ':') {
                builder.append(c);
            } else {
                builder.append('_');
            }
        }
        if (builder.isEmpty()) {
            return "metric";
        }
        var first = builder.charAt(0);
        if (!(Character.isAlphabetic(first) || first == '_' || first == ':')) {
            builder.insert(0, '_');
        }
        return builder.toString();
    }

    private static String prometheusType(Metric metric) {
        if (metric instanceof Counter) {
            return "counter";
        }
        if (metric instanceof Gauge) {
            return "gauge";
        }
        if (metric instanceof Histogram) {
            return "histogram";
        }
        if (metric instanceof Timer) {
            return "summary";
        }
        return "untyped";
    }

    private static String counterSampleName(String normalizedName) {
        return normalizedName.endsWith("_total") ? normalizedName : normalizedName + "_total";
    }

    private static String formatNumber(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "+Inf" : "-Inf";
        }
        if (Math.rint(value) == value) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static double toDouble(Object value) {
        if (value == null) {
            return Double.NaN;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new IllegalArgumentException("Gauge value must be a Number but was " + value.getClass().getName());
    }

    private static double nanosToSeconds(double nanos) {
        return nanos / 1_000_000_000d;
    }

    private static String escapeHelp(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n");
    }

    private static String escapeLabel(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}


