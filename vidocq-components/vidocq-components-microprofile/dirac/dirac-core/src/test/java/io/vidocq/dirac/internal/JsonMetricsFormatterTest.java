package io.vidocq.dirac.internal;

import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests M6 — format JSON spec MP Metrics 5.1.1 §3.2.
 */
class JsonMetricsFormatterTest {

    @Test
    void emptyRegistryProducesEmptyObject() {
        var registry = new MetricRegistryImpl(MetricRegistry.APPLICATION_SCOPE);

        var output = new JsonMetricsFormatter().format(registry);

        assertEquals("{}", output);
    }

    @Test
    void outputIsValidJsonStructure() {
        var registry = new MetricRegistryImpl(MetricRegistry.APPLICATION_SCOPE);
        registry.counter("hits").inc(1);

        var output = new JsonMetricsFormatter().format(registry);

        assertTrue(output.startsWith("{"), "must start with {");
        assertTrue(output.endsWith("}"), "must end with }");
    }

    @Test
    void formatsCounterWithoutTags() {
        var registry = new MetricRegistryImpl(MetricRegistry.APPLICATION_SCOPE);
        registry.counter("hits").inc(5);

        var output = new JsonMetricsFormatter().format(registry);

        // §3.2: counter key is the metric name, value is the count
        assertTrue(output.contains("\"hits\":5"), "output was: " + output);
    }

    @Test
    void formatsCounterWithTags() {
        var registry = new MetricRegistryImpl(MetricRegistry.APPLICATION_SCOPE);
        registry.counter("http.requests", new Tag("method", "GET")).inc(3);

        var output = new JsonMetricsFormatter().format(registry);

        // §3.2: tags appended as ;tagKey=tagValue
        assertTrue(output.contains("\"http.requests;method=GET\":3"), "output was: " + output);
    }

    @Test
    void formatsCounterTagsAreSorted() {
        var registry = new MetricRegistryImpl(MetricRegistry.APPLICATION_SCOPE);
        registry.counter("req", new Tag("status", "200"), new Tag("method", "GET")).inc(1);

        var output = new JsonMetricsFormatter().format(registry);

        // tags must appear in alphabetical order (method before status)
        assertTrue(output.contains("\"req;method=GET;status=200\":1"), "output was: " + output);
    }

    @Test
    void formatsGaugeWithTags() {
        var registry = new MetricRegistryImpl(MetricRegistry.APPLICATION_SCOPE);
        registry.gauge("memory.live", () -> 42L, new Tag("area", "heap"));

        var output = new JsonMetricsFormatter().format(registry);

        assertTrue(output.contains("\"memory.live;area=heap\":42"), "output was: " + output);
    }

    @Test
    void formatsHistogramWithCountSumAndPercentiles() {
        var registry = new MetricRegistryImpl(MetricRegistry.APPLICATION_SCOPE);
        var histogram = registry.histogram("payload.size");
        histogram.update(100);
        histogram.update(200);

        var output = new JsonMetricsFormatter().format(registry);

        // §3.2: histogram is a JSON object
        assertTrue(output.contains("\"payload.size\":{"), "output was: " + output);
        assertTrue(output.contains("\"count\":2"), "output was: " + output);
        assertTrue(output.contains("\"sum\":300"), "output was: " + output);
        // percentile keys p50, p75, p95, p98, p99, p999 must be present
        assertTrue(output.contains("\"p50\""), "output was: " + output);
        assertTrue(output.contains("\"p75\""), "output was: " + output);
        assertTrue(output.contains("\"p95\""), "output was: " + output);
        assertTrue(output.contains("\"p98\""), "output was: " + output);
        assertTrue(output.contains("\"p99\""), "output was: " + output);
        assertTrue(output.contains("\"p999\""), "output was: " + output);
    }

    @Test
    void formatsHistogramWithTags() {
        var registry = new MetricRegistryImpl(MetricRegistry.APPLICATION_SCOPE);
        registry.histogram("payload.size", new Tag("channel", "http")).update(150);

        var output = new JsonMetricsFormatter().format(registry);

        assertTrue(output.contains("\"payload.size;channel=http\":{"), "output was: " + output);
    }

    @Test
    void formatsTimerWithCountElapsedTimeAndPercentiles() {
        var registry = new MetricRegistryImpl(MetricRegistry.APPLICATION_SCOPE);
        var timer = registry.timer("request.latency");
        timer.update(Duration.ofNanos(1_000_000));
        timer.update(Duration.ofNanos(2_000_000));

        var output = new JsonMetricsFormatter().format(registry);

        // §3.2: timer is a JSON object; durations in seconds
        assertTrue(output.contains("\"request.latency\":{"), "output was: " + output);
        assertTrue(output.contains("\"count\":2"), "output was: " + output);
        // elapsedTime = 3ms = 0.003s
        assertTrue(output.contains("\"elapsedTime\":0.003"), "output was: " + output);
        // percentile keys in seconds
        assertTrue(output.contains("\"p50\""), "output was: " + output);
        assertTrue(output.contains("\"p999\""), "output was: " + output);
    }

    @Test
    void multipleMetricsSeparatedByCommas() {
        var registry = new MetricRegistryImpl(MetricRegistry.APPLICATION_SCOPE);
        registry.counter("a").inc(1);
        registry.counter("b").inc(2);

        var output = new JsonMetricsFormatter().format(registry);

        // both keys present and output has exactly one outer object
        assertTrue(output.contains("\"a\":1"), "output was: " + output);
        assertTrue(output.contains("\"b\":2"), "output was: " + output);
        // a single comma separates them (no trailing comma)
        assertFalse(output.contains(",}"), "trailing comma in: " + output);
    }
}
