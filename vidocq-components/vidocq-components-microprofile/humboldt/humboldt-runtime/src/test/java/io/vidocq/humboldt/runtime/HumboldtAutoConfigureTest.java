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
                Map.of("OTEL_TRACES_EXPORTER", "in-memory",
                        "OTEL_METRICS_EXPORTER", "none",
                        "OTEL_LOGS_EXPORTER", "none"),
                Map.of()))) {
            assertEquals("humboldt",
                    def.sdkTracerProvider().getResource().attributes()
                            .get(AttributeKey.stringKey("service.name")));
        }

        try (AutoConfiguredHumboldt custom = HumboldtAutoConfigure.configure(EnvConfig.of(
                Map.of("OTEL_SERVICE_NAME", "my-app",
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
                Map.of("OTEL_SERVICE_NAME", "svc",
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
                Map.of("OTEL_TRACES_EXPORTER", "in-memory",
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

            // Flush sync pour spans/logs (SimpleSpanProcessor + SimpleLogRecordProcessor),
            // explicit flush nécessaire pour metrics (PeriodicMetricReader)
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
                Map.of("OTEL_TRACES_EXPORTER", "in-memory",
                        "OTEL_METRICS_EXPORTER", "none",
                        "OTEL_LOGS_EXPORTER", "none",
                        "OTEL_TRACES_SAMPLER", "always_off"),
                Map.of()))) {
            for (int i = 0; i < 5; i++) {
                h.getTracerProvider().get("x").spanBuilder("dropped").startSpan().end();
            }
            h.flush().join(2, TimeUnit.SECONDS);
            assertEquals(0, h.inMemorySpanExporter().getFinishedSpans().size(),
                    "always_off doit empêcher tout export");
        }
    }

    @Test
    void traceidratio_sampler_parsed_with_arg() {
        try (AutoConfiguredHumboldt h = HumboldtAutoConfigure.configure(EnvConfig.of(
                Map.of("OTEL_TRACES_EXPORTER", "in-memory",
                        "OTEL_METRICS_EXPORTER", "none",
                        "OTEL_LOGS_EXPORTER", "none",
                        "OTEL_TRACES_SAMPLER", "traceidratio",
                        "OTEL_TRACES_SAMPLER_ARG", "0.5"),
                Map.of()))) {
            assertTrue(h.sdkTracerProvider().getSampler().description().contains("0.500000"),
                    "Sampler description doit contenir le ratio 0.5");
        }
    }

    @Test
    void exporter_none_disables_pipelines() {
        try (AutoConfiguredHumboldt h = HumboldtAutoConfigure.configure(EnvConfig.of(
                Map.of("OTEL_TRACES_EXPORTER", "none",
                        "OTEL_METRICS_EXPORTER", "none",
                        "OTEL_LOGS_EXPORTER", "none"),
                Map.of()))) {
            assertNull(h.inMemorySpanExporter());
            assertNull(h.inMemoryMetricExporter());
            assertNull(h.inMemoryLogRecordExporter());
            assertEquals(0, h.sdkTracerProvider().getSpanProcessors().size(),
                    "exporter=none → aucun span processor");
        }
    }

    @Test
    void extra_span_exporters_attached_via_simple_processor() {
        // Point d'extension M7b.3 : harness externes (TCK Arquillian)
        // peuvent injecter un SpanExporter additionnel sans toucher aux env vars.
        InMemorySpanExporter extra = InMemorySpanExporter.create();
        try (AutoConfiguredHumboldt h = HumboldtAutoConfigure.configure(EnvConfig.of(
                Map.of("OTEL_TRACES_EXPORTER", "none",
                        "OTEL_METRICS_EXPORTER", "none",
                        "OTEL_LOGS_EXPORTER", "none",
                        "OTEL_TRACES_SAMPLER", "always_on"),
                Map.of()),
                List.of(extra))) {

            Tracer t = h.getTracerProvider().get("test.extra");
            t.spanBuilder("via-extra-exporter").startSpan().end();
            h.flush().join(2, TimeUnit.SECONDS);

            assertEquals(1, extra.getFinishedSpans().size(),
                    "L'exporter injecté via le hook doit recevoir les spans");
            assertEquals("via-extra-exporter", extra.getFinishedSpans().getFirst().name());
            assertNull(h.inMemorySpanExporter(),
                    "OTEL_TRACES_EXPORTER=none → pas d'inMemory géré par l'autoconfig");
        }
    }

    @Test
    void w3c_propagators_installed_by_default() {
        try (AutoConfiguredHumboldt h = HumboldtAutoConfigure.configure(EnvConfig.of(
                Map.of("OTEL_TRACES_EXPORTER", "in-memory",
                        "OTEL_METRICS_EXPORTER", "none",
                        "OTEL_LOGS_EXPORTER", "none"),
                Map.of()))) {
            var fields = h.getPropagators().getTextMapPropagator().fields();
            assertTrue(fields.contains("traceparent"));
            assertTrue(fields.contains("baggage"));
        }
    }
}
