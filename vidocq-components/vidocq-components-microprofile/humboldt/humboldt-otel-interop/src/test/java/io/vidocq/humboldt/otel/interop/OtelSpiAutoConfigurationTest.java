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
package io.vidocq.humboldt.otel.interop;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link OtelSpiAutoConfiguration#discover(Map, ClassLoader)} finds
 * OTel autoconfigure SPI providers registered through {@code META-INF/services}
 * on the supplied ClassLoader — the same registration mechanism the MicroProfile
 * Telemetry TCK deployments use — and bridges them to the Humboldt SDK.
 */
class OtelSpiAutoConfigurationTest {

    /** Fake SPI provider registered via META-INF/services in the test ClassLoader. */
    public static final class FakeSpanExporterProvider implements ConfigurableSpanExporterProvider {
        @Override
        public SpanExporter createExporter(ConfigProperties config) {
            return new NoOpOtelSpanExporter();
        }

        @Override
        public String getName() {
            return "fake";
        }
    }

    /** No-op OTel SDK SpanExporter returned by the fake provider. */
    static final class NoOpOtelSpanExporter implements SpanExporter {
        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    @Test
    void discoversSpanExporterProviderAndNeutralizesTracesExporter(@TempDir Path dir) throws Exception {
        Path services = dir.resolve("META-INF").resolve("services");
        Files.createDirectories(services);
        Files.writeString(
                services.resolve("io.opentelemetry.sdk.autoconfigure.spi.traces.ConfigurableSpanExporterProvider"),
                FakeSpanExporterProvider.class.getName() + "\n");

        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {dir.toUri().toURL()}, getClass().getClassLoader())) {
            OtelSpiAutoConfiguration.Result result = OtelSpiAutoConfiguration.discover(
                    Map.of("OTEL_TRACES_EXPORTER", "fake"), loader);

            // The provider named "fake" matched otel.traces.exporter → bridged exporter.
            assertFalse(result.extraSpanExporters().isEmpty(),
                    "the SPI span exporter should be discovered and bridged");
            assertTrue(result.extraSpanExporters().get(0) instanceof OtelSpanExporterBridge);

            // An external bridge is in place → Humboldt must not add its own exporter.
            assertEquals("none", result.env().get("OTEL_TRACES_EXPORTER"));

            // No sampler/propagator SPI registered → no overrides.
            assertNull(result.samplerOverride());
            assertNull(result.propagatorsOverride());
            assertTrue(result.extraMetricExporters().isEmpty());
        }
    }

    @Test
    void noProvidersMeansNoBridgesAndDefaultedEnv() {
        OtelSpiAutoConfiguration.Result result = OtelSpiAutoConfiguration.discover(
                Map.of(), new URLClassLoader(new URL[0], null));

        assertTrue(result.extraSpanExporters().isEmpty());
        assertTrue(result.extraMetricExporters().isEmpty());
        assertNull(result.samplerOverride());
        assertNull(result.propagatorsOverride());
        // Faithful to the reference harness: exporters default to "none" when unset.
        assertEquals("none", result.env().get("OTEL_TRACES_EXPORTER"));
        assertEquals("none", result.env().get("OTEL_METRICS_EXPORTER"));
        assertEquals("none", result.env().get("OTEL_LOGS_EXPORTER"));
        assertEquals("always_on", result.env().get("OTEL_TRACES_SAMPLER"));
    }
}
