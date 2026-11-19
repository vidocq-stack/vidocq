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

import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests M6 - format OpenMetrics/Prometheus text.
 */
class OpenMetricsFormatterTest {

    @Test
    void formatsCounterGaugeHistogramAndTimer() {
        var registry = new MetricRegistryImpl(MetricRegistry.APPLICATION_SCOPE);

        var counterMetadata = Metadata.builder()
                .withName("http.requests")
                .withDescription("Total HTTP requests")
                .withUnit(MetricUnits.NONE)
                .build();
        registry.counter(counterMetadata, new Tag("method", "GET")).inc(3);

        registry.gauge("memory.live", () -> 42L, new Tag("area", "heap"));

        var histogram = registry.histogram("payload.size", new Tag("channel", "http"));
        histogram.update(100);
        histogram.update(200);

        var timer = registry.timer("request.latency", new Tag("route", "/health"));
        timer.update(Duration.ofNanos(1_000_000));
        timer.update(Duration.ofNanos(2_000_000));

        var output = new OpenMetricsFormatter().format(registry);

        assertTrue(output.contains("# HELP http_requests Total HTTP requests"));
        assertTrue(output.contains("# TYPE http_requests counter"));
        assertTrue(output.contains("http_requests_total{method=\"GET\"} 3"));

        assertTrue(output.contains("# TYPE memory_live gauge"));
        assertTrue(output.contains("memory_live{area=\"heap\"} 42"));

        assertTrue(output.contains("# TYPE payload_size histogram"));
        assertTrue(output.contains("payload_size_bucket{channel=\"http\",le=\"+Inf\"} 2"));
        assertTrue(output.contains("payload_size_count{channel=\"http\"} 2"));
        assertTrue(output.contains("payload_size_sum{channel=\"http\"} 300"));

        assertTrue(output.contains("# TYPE request_latency summary"));
        assertTrue(output.contains("request_latency_seconds_count{route=\"/health\"} 2"));
        assertTrue(output.contains("request_latency_seconds_sum{route=\"/health\"} 0.003"));

        assertTrue(output.endsWith("# EOF\n"));
    }

    @Test
    void normalizesMetricAndLabelNames() {
        var registry = new MetricRegistryImpl(MetricRegistry.APPLICATION_SCOPE);
        registry.counter("9xx.errors", new Tag("status_code", "503")).inc();

        var output = new OpenMetricsFormatter().format(registry);

        assertTrue(output.contains("# TYPE _9xx_errors counter"));
        assertTrue(output.contains("_9xx_errors_total{status_code=\"503\"} 1"));
    }
}


