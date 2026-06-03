package io.vidocq.humboldt.tck;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.vidocq.humboldt.runtime.AutoConfiguredHumboldt;
import io.vidocq.humboldt.runtime.EnvConfig;
import io.vidocq.humboldt.runtime.HumboldtAutoConfigure;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * Smoke test M7 — verifies that:
 * <ul>
 *   <li>the Humboldt artifacts (humboldt-runtime, humboldt-cdi, humboldt-rest)
 *       are resolved in the local M2 from the parent reactor;</li>
 *   <li>the official MP Telemetry 2.1 TCK classes are on the classpath;</li>
 *   <li>{@code AutoConfiguredHumboldt} produces a valid {@code OpenTelemetry}
 *       that can be registered globally and used to create a span.</li>
 * </ul>
 *
 * <p>This test does NOT depend on Arquillian (so it passes without a container adapter).
 * The real official TCK tests require that adapter in M7b.</p>
 */
public class HumboldtTckSmokeTest {

    private AutoConfiguredHumboldt humboldt;

    @AfterMethod
    public void tearDown() {
        if (humboldt != null) humboldt.close();
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    public void humboldt_runtime_is_on_classpath_and_configurable() {
        humboldt = HumboldtAutoConfigure.configure(EnvConfig.of(
                Map.of("OTEL_SERVICE_NAME", "humboldt-tck-smoke",
                        "OTEL_TRACES_EXPORTER", "in-memory",
                        "OTEL_METRICS_EXPORTER", "none",
                        "OTEL_LOGS_EXPORTER", "none"),
                Map.of()));

        assertNotNull(humboldt, "AutoConfiguredHumboldt must be instantiable");
        assertNotNull(humboldt.getTracerProvider());
        assertNotNull(humboldt.getMeterProvider());
        assertNotNull(humboldt.getLogsBridge());
        assertNotNull(humboldt.getPropagators());
    }

    @Test
    public void humboldt_can_be_set_as_global_open_telemetry() {
        // MP Telemetry 2.1 §3.1 default = SDK disabled; the smoke explicitly
        // enables it so that the in-memory pipeline is actually wired.
        humboldt = HumboldtAutoConfigure.configure(EnvConfig.of(
                Map.of("OTEL_SDK_DISABLED", "false",
                        "OTEL_TRACES_EXPORTER", "in-memory",
                        "OTEL_METRICS_EXPORTER", "none",
                        "OTEL_LOGS_EXPORTER", "none"),
                Map.of()));
        GlobalOpenTelemetry.set(humboldt);

        OpenTelemetry global = GlobalOpenTelemetry.get();
        assertNotNull(global);

        Tracer t = global.getTracer("io.vidocq.tck.smoke");
        Span span = t.spanBuilder("tck-smoke-span").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            assertEquals(Span.current().getSpanContext().getTraceId().length(), 32,
                    "traceId must be 32-char hex");
        }
        span.end();

        List<SpanData> finished = humboldt.inMemorySpanExporter().getFinishedSpans();
        assertEquals(finished.size(), 1, "1 span expected");
        assertEquals(finished.getFirst().name(), "tck-smoke-span");
    }

    @Test
    public void mp_telemetry_tracing_tck_classes_are_on_classpath() {
        // Sanity check: if the TCK is not resolved as a dependency, these classes do not exist
        // and ClassNotFoundException is thrown
        try {
            Class<?> tckBase = Class.forName(
                    "org.eclipse.microprofile.telemetry.tracing.tck.TestApplication");
            assertNotNull(tckBase);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("TCK tracing class not found - missing dependency: " + e.getMessage());
        }
    }

    @Test
    public void official_with_span_annotation_is_on_classpath() {
        // The official TCK uses OpenTelemetry instrumentation's @WithSpan
        // annotation, NOT our humboldt-cdi WithSpan. M6c+ will need to alias it.
        try {
            Class<?> annot = Class.forName(
                    "io.opentelemetry.instrumentation.annotations.WithSpan");
            assertTrue(annot.isAnnotation());
        } catch (ClassNotFoundException e) {
            throw new AssertionError("@WithSpan OTel official annotation not found: " + e.getMessage());
        }
    }
}
