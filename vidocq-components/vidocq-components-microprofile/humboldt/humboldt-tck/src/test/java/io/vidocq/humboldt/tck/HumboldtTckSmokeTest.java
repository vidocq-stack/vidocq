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
 * Smoke test M7 — vérifie que :
 * <ul>
 *   <li>les artefacts Humboldt (humboldt-runtime, humboldt-cdi, humboldt-rest)
 *       sont résolus dans le M2 local depuis le reactor parent ;</li>
 *   <li>les classes du TCK officiel MP Telemetry 2.1 sont sur le classpath ;</li>
 *   <li>{@code AutoConfiguredHumboldt} produit un {@code OpenTelemetry} valide
 *       qu'on peut enregistrer comme global et utiliser pour créer un span.</li>
 * </ul>
 *
 * <p>Ce test ne dépend PAS d'Arquillian (donc passe sans adapter container).
 * Les vrais tests TCK officiels nécessitent l'adapter en M7b.</p>
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

        assertNotNull(humboldt, "AutoConfiguredHumboldt doit être instanciable");
        assertNotNull(humboldt.getTracerProvider());
        assertNotNull(humboldt.getMeterProvider());
        assertNotNull(humboldt.getLogsBridge());
        assertNotNull(humboldt.getPropagators());
    }

    @Test
    public void humboldt_can_be_set_as_global_open_telemetry() {
        humboldt = HumboldtAutoConfigure.configure(EnvConfig.of(
                Map.of("OTEL_TRACES_EXPORTER", "in-memory",
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
                    "traceId doit être hex 32 chars");
        }
        span.end();

        List<SpanData> finished = humboldt.inMemorySpanExporter().getFinishedSpans();
        assertEquals(finished.size(), 1, "1 span attendu");
        assertEquals(finished.getFirst().name(), "tck-smoke-span");
    }

    @Test
    public void mp_telemetry_tracing_tck_classes_are_on_classpath() {
        // Sanity check : si le TCK n'est pas résolu en dep, ces classes n'existent
        // pas et ClassNotFoundException remonte
        try {
            Class<?> tckBase = Class.forName(
                    "org.eclipse.microprofile.telemetry.tracing.tck.TestApplication");
            assertNotNull(tckBase);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("TCK tracing class introuvable — dependency manquante : " + e.getMessage());
        }
    }

    @Test
    public void official_with_span_annotation_is_on_classpath() {
        // Le TCK officiel utilise l'annotation @WithSpan d'OpenTelemetry
        // instrumentation, PAS notre humboldt-cdi WithSpan. M6c+ devra aliaser.
        try {
            Class<?> annot = Class.forName(
                    "io.opentelemetry.instrumentation.annotations.WithSpan");
            assertTrue(annot.isAnnotation());
        } catch (ClassNotFoundException e) {
            throw new AssertionError("@WithSpan OTel official introuvable : " + e.getMessage());
        }
    }
}
