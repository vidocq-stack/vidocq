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
package io.vidocq.humboldt.runtime;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Tracer;
import io.vidocq.humboldt.sdk.trace.export.InMemorySpanExporter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumboldtAutoConfigureTest {

    @Test
    void service_name_default_and_override() {
        try (AutoConfiguredHumboldt def = HumboldtAutoConfigure.configure(EnvConfig.of(
                Map.of("OTEL_SDK_DISABLED", "false",
                        "OTEL_TRACES_EXPORTER", "in-memory",
                        "OTEL_METRICS_EXPORTER", "none",
                        "OTEL_LOGS_EXPORTER", "none"),
                Map.of()))) {
            assertEquals("humboldt",
                    def.sdkTracerProvider().getResource().attributes()
                            .get(AttributeKey.stringKey("service.name")));
        }

        try (AutoConfiguredHumboldt custom = HumboldtAutoConfigure.configure(EnvConfig.of(
                Map.of("OTEL_SDK_DISABLED", "false",
                        "OTEL_SERVICE_NAME", "my-app",
                        "OTEL_TRACES_EXPORTER", "in-memory",
                        "OTEL_METRICS_EXPORTER", "none",
                        "OTEL_LOGS_EXPORTER", "none"),
                Map.of()))) {
            assertEquals("my-app",
                    custom.sdkTracerProvider().getResource().attributes()
                            .get(AttributeKey.stringKey("service.name")));
        }
    }

    @Test
    void resource_attributes_parsed() {
        try (AutoConfiguredHumboldt h = HumboldtAutoConfigure.configure(EnvConfig.of(
                Map.of("OTEL_SDK_DISABLED", "false",
                        "OTEL_SERVICE_NAME", "svc",
                        "OTEL_RESOURCE_ATTRIBUTES", "env=prod,team=platform,region=eu-west-1",
                        "OTEL_TRACES_EXPORTER", "in-memory",
                        "OTEL_METRICS_EXPORTER", "none",
                        "OTEL_LOGS_EXPORTER", "none"),
                Map.of()))) {
            var attrs = h.sdkTracerProvider().getResource().attributes();
            assertEquals("svc", attrs.get(AttributeKey.stringKey("service.name")));
            assertEquals("prod", attrs.get(AttributeKey.stringKey("env")));
            assertEquals("platform", attrs.get(AttributeKey.stringKey("team")));
            assertEquals("eu-west-1", attrs.get(AttributeKey.stringKey("region")));
        }
    }

    @Test
    void in_memory_pipeline_exports_traces_metrics_logs_end_to_end() {
        try (AutoConfiguredHumboldt h = HumboldtAutoConfigure.configure(EnvConfig.of(
                Map.of("OTEL_SDK_DISABLED", "false",
                        "OTEL_TRACES_EXPORTER", "in-memory",
                        "OTEL_METRICS_EXPORTER", "in-memory",
                        "OTEL_LOGS_EXPORTER", "in-memory",
                        "OTEL_TRACES_SAMPLER", "always_on"),
                Map.of()))) {

            // Trace
            Tracer t = h.getTracerProvider().get("test.trace");
            t.spanBuilder("auto-cfg-test").startSpan().end();

            // Metric
            Meter m = h.getMeterProvider().get("test.metric");
            LongCounter c = m.counterBuilder("requests").build();
            c.add(3L);

            // Log
            Logger l = h.getLogsBridge().get("test.log");
            l.logRecordBuilder().setBody("autoconfig works").emit();

            // Synchronous flush for spans/logs (SimpleSpanProcessor + SimpleLogRecordProcessor),
            // explicit flush required for metrics (PeriodicMetricReader)
            h.flush().join(2, TimeUnit.SECONDS);

            assertNotNull(h.inMemorySpanExporter());
            assertEquals(1, h.inMemorySpanExporter().getFinishedSpans().size());
            assertEquals("auto-cfg-test", h.inMemorySpanExporter().getFinishedSpans().getFirst().name());

            assertNotNull(h.inMemoryMetricExporter());
            assertTrue(h.inMemoryMetricExporter().getCollected().size() >= 1);

            assertNotNull(h.inMemoryLogRecordExporter());
            assertEquals(1, h.inMemoryLogRecordExporter().getCollected().size());
            assertEquals("autoconfig works",
                    h.inMemoryLogRecordExporter().getCollected().getFirst().body());
        }
    }

    @Test
    void sampler_always_off_produces_no_spans() {
        try (AutoConfiguredHumboldt h = HumboldtAutoConfigure.configure(EnvConfig.of(
                Map.of("OTEL_SDK_DISABLED", "false",
                        "OTEL_TRACES_EXPORTER", "in-memory",
                        "OTEL_METRICS_EXPORTER", "none",
                        "OTEL_LOGS_EXPORTER", "none",
                        "OTEL_TRACES_SAMPLER", "always_off"),
                Map.of()))) {
            for (int i = 0; i < 5; i++) {
                h.getTracerProvider().get("x").spanBuilder("dropped").startSpan().end();
            }
            h.flush().join(2, TimeUnit.SECONDS);
            assertEquals(0, h.inMemorySpanExporter().getFinishedSpans().size(),
                    "always_off must prevent all export");
        }
    }

    @Test
    void traceidratio_sampler_parsed_with_arg() {
        try (AutoConfiguredHumboldt h = HumboldtAutoConfigure.configure(EnvConfig.of(
                Map.of("OTEL_SDK_DISABLED", "false",
                        "OTEL_TRACES_EXPORTER", "in-memory",
                        "OTEL_METRICS_EXPORTER", "none",
                        "OTEL_LOGS_EXPORTER", "none",
                        "OTEL_TRACES_SAMPLER", "traceidratio",
                        "OTEL_TRACES_SAMPLER_ARG", "0.5"),
                Map.of()))) {
            assertTrue(h.sdkTracerProvider().getSampler().description().contains("0.500000"),
                    "Sampler description must contain ratio 0.5");
        }
    }

    @Test
    void exporter_none_disables_pipelines() {
        try (AutoConfiguredHumboldt h = HumboldtAutoConfigure.configure(EnvConfig.of(
                Map.of("OTEL_SDK_DISABLED", "false",
                        "OTEL_TRACES_EXPORTER", "none",
                        "OTEL_METRICS_EXPORTER", "none",
                        "OTEL_LOGS_EXPORTER", "none"),
                Map.of()))) {
            assertNull(h.inMemorySpanExporter());
            assertNull(h.inMemoryMetricExporter());
            assertNull(h.inMemoryLogRecordExporter());
            assertEquals(0, h.sdkTracerProvider().getSpanProcessors().size(),
                    "exporter=none -> no span processor");
        }
    }

    @Test
    void extra_span_exporters_attached_via_simple_processor() {
        // M7b.3 extension point: external harnesses (Arquillian TCK)
        // can inject an additional SpanExporter without touching env vars.
        InMemorySpanExporter extra = InMemorySpanExporter.create();
        try (AutoConfiguredHumboldt h = HumboldtAutoConfigure.configure(EnvConfig.of(
                Map.of("OTEL_SDK_DISABLED", "false",
                        "OTEL_TRACES_EXPORTER", "none",
                        "OTEL_METRICS_EXPORTER", "none",
                        "OTEL_LOGS_EXPORTER", "none",
                        "OTEL_TRACES_SAMPLER", "always_on"),
                Map.of()),
                List.of(extra))) {

            Tracer t = h.getTracerProvider().get("test.extra");
            t.spanBuilder("via-extra-exporter").startSpan().end();
            h.flush().join(2, TimeUnit.SECONDS);

            assertEquals(1, extra.getFinishedSpans().size(),
                    "The exporter injected via the hook must receive spans");
            assertEquals("via-extra-exporter", extra.getFinishedSpans().getFirst().name());
            assertNull(h.inMemorySpanExporter(),
                    "OTEL_TRACES_EXPORTER=none → no in-memory exporter provided by autoconfig");
        }
    }

    @Test
    void w3c_propagators_installed_by_default() {
        try (AutoConfiguredHumboldt h = HumboldtAutoConfigure.configure(EnvConfig.of(
                Map.of("OTEL_SDK_DISABLED", "false",
                        "OTEL_TRACES_EXPORTER", "in-memory",
                        "OTEL_METRICS_EXPORTER", "none",
                        "OTEL_LOGS_EXPORTER", "none"),
                Map.of()))) {
            var fields = h.getPropagators().getTextMapPropagator().fields();
            assertTrue(fields.contains("traceparent"));
            assertTrue(fields.contains("baggage"));
        }
    }

    @Test
    void sdk_disabled_by_default_per_mp_telemetry_spec() {
        // MP Telemetry 2.1 §3.1: OTEL_SDK_DISABLED defaults to true.
        // Without explicit config, providers are built without processors -> 0 export.
        try (AutoConfiguredHumboldt h = HumboldtAutoConfigure.configure(EnvConfig.of(
                Map.of("OTEL_TRACES_EXPORTER", "in-memory"),
                Map.of()))) {
            assertNull(h.inMemorySpanExporter(),
                    "SDK disabled by default -> no in-memory exporter installed");
            h.getTracerProvider().get("x").spanBuilder("ignored").startSpan().end();
            assertEquals(0, h.sdkTracerProvider().getSpanProcessors().size(),
                    "SDK disabled -> no span processor");
        }
    }

    @Test
    void sdk_disabled_true_explicit_disables_all_pipelines() {
        try (AutoConfiguredHumboldt h = HumboldtAutoConfigure.configure(EnvConfig.of(
                Map.of("OTEL_SDK_DISABLED", "true",
                        "OTEL_TRACES_EXPORTER", "in-memory",
                        "OTEL_METRICS_EXPORTER", "in-memory",
                        "OTEL_LOGS_EXPORTER", "in-memory"),
                Map.of()))) {
            assertNull(h.inMemorySpanExporter());
            assertNull(h.inMemoryMetricExporter());
            assertNull(h.inMemoryLogRecordExporter());
        }
    }
}
