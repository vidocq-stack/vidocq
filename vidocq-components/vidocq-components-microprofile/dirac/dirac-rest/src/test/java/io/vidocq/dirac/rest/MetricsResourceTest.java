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
package io.vidocq.dirac.rest;

import io.vidocq.dirac.internal.MetricRegistryImpl;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M7 unit tests — REST endpoint /metrics formatting logic (§2.3).
 * No CDI container or JAX-RS RuntimeDelegate required: formatAll/formatScope/formatMetric
 * methods are tested directly.
 */
class MetricsResourceTest {

    private MetricRegistry appRegistry;
    private MetricRegistry baseRegistry;
    private MetricRegistry vendorRegistry;
    private MetricsResource resource;

    @BeforeEach
    void setUp() {
        appRegistry = new MetricRegistryImpl(MetricRegistry.APPLICATION_SCOPE);
        baseRegistry = new MetricRegistryImpl(MetricRegistry.BASE_SCOPE);
        vendorRegistry = new MetricRegistryImpl(MetricRegistry.VENDOR_SCOPE);
        resource = new MetricsResource(appRegistry, baseRegistry, vendorRegistry);
    }

    // --- formatAll ---

    @Test
    void formatAllDefaultsToOpenMetrics() {
        appRegistry.counter("hits").inc(3);
        var fmt = resource.formatAll(null);
        assertEquals(MetricsResource.OPENMETRICS_TYPE, fmt.mediaType());
        assertTrue(fmt.body().contains("# TYPE hits counter"), "body: " + fmt.body());
        assertTrue(fmt.body().endsWith("# EOF\n"), "must end with # EOF\\n, body: " + fmt.body());
    }

    @Test
    void formatAllTextPlainReturnsOpenMetrics() {
        var fmt = resource.formatAll(MediaType.TEXT_PLAIN);
        assertEquals(MetricsResource.OPENMETRICS_TYPE, fmt.mediaType());
    }

    @Test
    void formatAllJsonReturnsAllScopesWrapped() {
        appRegistry.counter("hits").inc(7);
        var fmt = resource.formatAll(MediaType.APPLICATION_JSON);
        assertEquals(MediaType.APPLICATION_JSON, fmt.mediaType());
        assertTrue(fmt.body().contains("\"application\":{"), "body: " + fmt.body());
        assertTrue(fmt.body().contains("\"base\":{"), "body: " + fmt.body());
        assertTrue(fmt.body().contains("\"vendor\":{"), "body: " + fmt.body());
        assertTrue(fmt.body().contains("\"hits\":7"), "body: " + fmt.body());
    }

    @Test
    void formatAllTextIncludesAllScopes() {
        appRegistry.counter("app.req").inc(1);
        baseRegistry.gauge("jvm.uptime", () -> 42L);
        var body = resource.formatAll(null).body();
        assertTrue(body.contains("app_req"), "app scope missing, body: " + body);
        assertTrue(body.contains("jvm_uptime"), "base scope missing, body: " + body);
    }

    @Test
    void formatAllTextHasSingleEof() {
        appRegistry.counter("a").inc(1);
        baseRegistry.counter("b").inc(2);
        var body = resource.formatAll(null).body();
        // Only one # EOF at the very end
        assertEquals(1, countOccurrences(body, "# EOF\n"), "expected exactly one # EOF, body: " + body);
        assertTrue(body.endsWith("# EOF\n"), "body: " + body);
    }

    // --- formatScope ---

    @Test
    void formatScopeApplicationReturnsMetrics() {
        appRegistry.counter("hits").inc(1);
        var fmt = resource.formatScope("application", null);
        assertNotNull(fmt);
        assertTrue(fmt.body().contains("hits_total 1"), "body: " + fmt.body());
    }

    @Test
    void formatScopeBaseReturnsMetrics() {
        var fmt = resource.formatScope("base", null);
        assertNotNull(fmt);
    }

    @Test
    void formatScopeVendorReturnsMetrics() {
        var fmt = resource.formatScope("vendor", null);
        assertNotNull(fmt);
    }

    @Test
    void formatScopeUnknownReturnsNull() {
        assertNull(resource.formatScope("unknown", null));
    }

    @Test
    void formatScopeApplicationJsonContainsMetrics() {
        appRegistry.counter("req").inc(2);
        var fmt = resource.formatScope("application", MediaType.APPLICATION_JSON);
        assertNotNull(fmt);
        assertEquals(MediaType.APPLICATION_JSON, fmt.mediaType());
        assertTrue(fmt.body().contains("\"req\":2"), "body: " + fmt.body());
    }

    @Test
    void formatScopeWithTagsRendersCorrectly() {
        appRegistry.counter("req", new Tag("method", "GET")).inc(5);
        var body = resource.formatScope("application", null).body();
        assertTrue(body.contains("req_total{method=\"GET\"} 5"), "body: " + body);
    }

    // --- formatMetric ---

    @Test
    void formatMetricReturnsOnlyNamedMetric() {
        appRegistry.counter("hits").inc(3);
        appRegistry.counter("errors").inc(1);
        var fmt = resource.formatMetric("application", "hits", null);
        assertNotNull(fmt);
        assertTrue(fmt.body().contains("hits_total 3"), "body: " + fmt.body());
        assertFalse(fmt.body().contains("errors"), "should not contain errors, body: " + fmt.body());
    }

    @Test
    void formatMetricJsonReturnsOnlyNamedMetric() {
        appRegistry.counter("hits").inc(4);
        appRegistry.counter("errors").inc(1);
        var fmt = resource.formatMetric("application", "hits", MediaType.APPLICATION_JSON);
        assertNotNull(fmt);
        assertTrue(fmt.body().contains("\"hits\":4"), "body: " + fmt.body());
        assertFalse(fmt.body().contains("errors"), "body: " + fmt.body());
    }

    @Test
    void formatMetricUnknownNameReturnsNull() {
        assertNull(resource.formatMetric("application", "missing", null));
    }

    @Test
    void formatMetricUnknownScopeReturnsNull() {
        assertNull(resource.formatMetric("unknown", "hits", null));
    }

    @Test
    void formatMetricReturnsAllTaggedInstances() {
        appRegistry.counter("req", new Tag("method", "GET")).inc(1);
        appRegistry.counter("req", new Tag("method", "POST")).inc(2);
        var body = resource.formatMetric("application", "req", null).body();
        assertTrue(body.contains("method=\"GET\""), "body: " + body);
        assertTrue(body.contains("method=\"POST\""), "body: " + body);
    }

    // --- helpers ---

    private static int countOccurrences(String text, String substring) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(substring, idx)) != -1) {
            count++;
            idx += substring.length();
        }
        return count;
    }
}
