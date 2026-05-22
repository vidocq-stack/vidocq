package io.vidocq.dirac.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Properties;

/**
 * Resolves MP Metrics distribution configuration from microprofile-config.properties.
 */
final class DistributionConfig {
    private static final String PERCENTILES = "mp.metrics.distribution.percentiles";
    private static final String HISTOGRAM_BUCKETS = "mp.metrics.distribution.histogram.buckets";
    private static final String TIMER_BUCKETS = "mp.metrics.distribution.timer.buckets";

    private DistributionConfig() {
    }

    static double[] resolvePercentiles(String metricName, double[] defaults) {
        var raw = resolveMetricEntry(readConfigValue(PERCENTILES), metricName);
        if (raw == null) {
            return defaults.clone();
        }
        if (raw.isBlank()) {
            return new double[0];
        }
        var values = parseCsv(raw, token -> {
            try {
                var value = Double.parseDouble(token);
                return value > 0d && value < 1d ? value : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        });
        return values.length == 0 ? new double[0] : values;
    }

    static double[] resolveHistogramBuckets(String metricName) {
        var raw = resolveMetricEntry(readConfigValue(HISTOGRAM_BUCKETS), metricName);
        if (raw == null || raw.isBlank()) {
            return new double[0];
        }
        return parseCsv(raw, token -> {
            try {
                var value = Double.parseDouble(token);
                return value > 0d ? value : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        });
    }

    static double[] resolveTimerBuckets(String metricName) {
        var raw = resolveMetricEntry(readConfigValue(TIMER_BUCKETS), metricName);
        if (raw == null || raw.isBlank()) {
            return new double[0];
        }
        return parseCsv(raw, DistributionConfig::parseTimerBucketToNanos);
    }

    private static Double parseTimerBucketToNanos(String token) {
        var normalized = token.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return null;
        }

        try {
            if (normalized.endsWith("ns")) {
                return positive(Double.parseDouble(normalized.substring(0, normalized.length() - 2)));
            }
            if (normalized.endsWith("us")) {
                return positive(Double.parseDouble(normalized.substring(0, normalized.length() - 2)) * 1_000d);
            }
            if (normalized.endsWith("ms")) {
                return positive(Double.parseDouble(normalized.substring(0, normalized.length() - 2)) * 1_000_000d);
            }
            if (normalized.endsWith("s")) {
                return positive(Double.parseDouble(normalized.substring(0, normalized.length() - 1)) * 1_000_000_000d);
            }
            if (normalized.endsWith("m")) {
                return positive(Double.parseDouble(normalized.substring(0, normalized.length() - 1)) * 60_000_000_000d);
            }
            if (normalized.endsWith("h")) {
                return positive(Double.parseDouble(normalized.substring(0, normalized.length() - 1)) * 3_600_000_000_000d);
            }
            // Bare number defaults to milliseconds and must be integer-like.
            if (!normalized.matches("\\d+")) {
                return null;
            }
            return positive(Double.parseDouble(normalized) * 1_000_000d);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Double positive(double value) {
        return value > 0d ? value : null;
    }

    private static String readConfigValue(String key) {
        var systemValue = System.getProperty(key);
        if (systemValue != null) {
            return systemValue;
        }

        var classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = DistributionConfig.class.getClassLoader();
        }

        try {
            Enumeration<java.net.URL> resources = classLoader.getResources("META-INF/microprofile-config.properties");
            while (resources.hasMoreElements()) {
                var resource = resources.nextElement();
                try (InputStream input = resource.openStream()) {
                    var properties = new Properties();
                    properties.load(input);
                    var value = properties.getProperty(key);
                    if (value != null) {
                        return value;
                    }
                }
            }
        } catch (IOException ignored) {
            // Ignore and use defaults.
        }

        return null;
    }

    private static String resolveMetricEntry(String source, String metricName) {
        if (source == null || metricName == null || metricName.isBlank()) {
            return null;
        }

        String bestValue = null;
        int bestScore = -1;

        for (var entry : source.split(";")) {
            var separator = entry.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            var pattern = entry.substring(0, separator).trim();
            var value = entry.substring(separator + 1).trim();

            int score = matchScore(pattern, metricName);
            if (score > bestScore) {
                bestScore = score;
                bestValue = value;
            }
        }

        return bestValue;
    }

    private static int matchScore(String pattern, String metricName) {
        if (pattern.equals(metricName)) {
            return 10_000;
        }
        if (!pattern.contains("*")) {
            return -1;
        }

        var regex = patternToRegex(pattern);
        if (!metricName.matches(regex)) {
            return -1;
        }

        int specificity = 0;
        for (int i = 0; i < pattern.length(); i++) {
            if (pattern.charAt(i) != '*') {
                specificity++;
            }
        }
        return specificity;
    }

    private static String patternToRegex(String pattern) {
        var builder = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            var c = pattern.charAt(i);
            if (c == '*') {
                builder.append(".*");
            } else {
                if ("\\.^$|?+()[]{}".indexOf(c) >= 0) {
                    builder.append('\\');
                }
                builder.append(c);
            }
        }
        return builder.append('$').toString();
    }

    private static double[] parseCsv(String source, java.util.function.Function<String, Double> parser) {
        var values = new ArrayList<Double>();
        Arrays.stream(source.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .forEach(token -> {
                    var parsed = parser.apply(token);
                    if (parsed != null) {
                        values.add(parsed);
                    }
                });

        var result = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i);
        }
        return result;
    }
}


