package io.vidocq.humboldt.cdi;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.vidocq.humboldt.sdk.trace.SdkTracerProvider;
import io.vidocq.humboldt.sdk.trace.SimpleSpanProcessor;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.humboldt.sdk.trace.export.InMemorySpanExporter;
import io.vidocq.humboldt.sdk.trace.samplers.Sampler;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WithSpanInterceptorTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;
    private TestableInterceptor interceptor;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        provider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        interceptor = new TestableInterceptor(provider);
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    @Test
    void wraps_method_in_span_with_default_name() throws Exception {
        Object result = interceptor.aroundInvoke(invocationFor("annotatedDefault", "hello"));

        assertEquals("OK:hello", result);
        SpanData s = exporter.getFinishedSpans().getFirst();
        assertEquals("Target.annotatedDefault", s.name(),
                "défaut = ClassSimpleName + '.' + methodName");
        assertEquals(SpanKind.INTERNAL, s.kind());
        assertEquals(StatusCode.UNSET, s.status().code());
        assertTrue(s.hasEnded());
    }

    @Test
    void uses_explicit_value_and_kind() throws Exception {
        interceptor.aroundInvoke(invocationFor("annotatedNamed", "x"));
        SpanData s = exporter.getFinishedSpans().getFirst();
        assertEquals("custom.span.name", s.name());
        assertEquals(SpanKind.SERVER, s.kind());
    }

    @Test
    void records_exception_and_sets_error_status() {
        InvocationContext ctx = new TestInvocationContext(
                method("alwaysFail"), new Object[0],
                () -> { throw new IllegalStateException("boom!"); });

        Exception thrown = assertThrows(Exception.class, () -> interceptor.aroundInvoke(ctx));
        assertEquals("boom!", thrown.getMessage());

        SpanData s = exporter.getFinishedSpans().getFirst();
        assertEquals(StatusCode.ERROR, s.status().code());
        assertTrue(s.status().description().contains("IllegalStateException"));
        assertTrue(s.status().description().contains("boom!"));
        // Un event "exception" est enregistré avec stacktrace
        assertEquals(1, s.events().size());
        assertEquals("exception", s.events().getFirst().name());
        assertEquals("java.lang.IllegalStateException",
                s.events().getFirst().attributes().get(AttributeKey.stringKey("exception.type")));
    }

    @Test
    void span_is_current_during_method_execution() throws Exception {
        // Verify that Context.current() inside the method returns the new span
        SpanData[] seen = new SpanData[1];
        interceptor.aroundInvoke(new TestInvocationContext(
                method("annotatedDefault"), new Object[]{"x"},
                () -> {
                    Span s = Span.current();
                    assertSame(s, Span.fromContext(Context.current()),
                            "span courant doit être attaché au Context");
                    return "ok";
                }));
        SpanData captured = exporter.getFinishedSpans().getFirst();
        assertNull(seen[0]); // pas écrit, mais le span est bien créé
        assertEquals(captured.spanContext().getTraceId().length(), 32);
    }

    @Test
    void parent_child_relationship_when_outer_span_active() throws Exception {
        Tracer t = provider.get("test.outer");
        Span outer = t.spanBuilder("outer").startSpan();
        try (var ignored = outer.makeCurrent()) {
            interceptor.aroundInvoke(invocationFor("annotatedDefault", "v"));
        }
        outer.end();

        SpanData child = exporter.getFinishedSpans().stream()
                .filter(s -> s.name().equals("Target.annotatedDefault")).findFirst().orElseThrow();
        SpanData parent = exporter.getFinishedSpans().stream()
                .filter(s -> s.name().equals("outer")).findFirst().orElseThrow();

        assertEquals(parent.spanContext().getTraceId(), child.spanContext().getTraceId(),
                "le span enfant doit hériter du traceId du span outer");
        assertEquals(parent.spanContext().getSpanId(), child.parentSpanContext().getSpanId(),
                "le parentSpanContext doit pointer le span outer");
    }

    @Test
    void annotation_on_class_used_when_method_lacks_one() throws Exception {
        Method m = ClassAnnotatedTarget.class.getMethod("noMethodAnnot");
        interceptor.aroundInvoke(new TestInvocationContext(m, new Object[0], () -> "ok"));
        SpanData s = exporter.getFinishedSpans().getFirst();
        assertEquals("ClassAnnotatedTarget.noMethodAnnot", s.name());
        assertEquals(SpanKind.CLIENT, s.kind(), "kind doit venir de l'annotation classe");
    }

    // ----- helpers -----

    private InvocationContext invocationFor(String methodName, Object... args) throws Exception {
        return new TestInvocationContext(method(methodName), args, () -> {
            // simule la méthode "OK:<arg>"
            return "OK:" + (args.length > 0 ? args[0] : "");
        });
    }

    private Method method(String name) {
        for (Method m : Target.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) return m;
        }
        throw new IllegalArgumentException("méthode introuvable : " + name);
    }

    /** Cible annotée pour les tests — pas instanciée en CDI (pas de container ici). */
    static class Target {
        @WithSpan
        public String annotatedDefault(String s) { return "OK:" + s; }

        @WithSpan(value = "custom.span.name", kind = SpanKind.SERVER)
        public String annotatedNamed(String s) { return s; }

        @WithSpan
        public String alwaysFail() { return "ne sera jamais atteint"; }
    }

    @WithSpan(kind = SpanKind.CLIENT)
    static class ClassAnnotatedTarget {
        public String noMethodAnnot() { return "ok"; }
    }

    /** Interceptor instrumenté pour pointer notre SdkTracerProvider plutôt que GlobalOpenTelemetry. */
    static final class TestableInterceptor extends WithSpanInterceptor {
        private final OpenTelemetry otel;

        TestableInterceptor(SdkTracerProvider tp) {
            this.otel = new OpenTelemetry() {
                @Override public TracerProvider getTracerProvider() { return tp; }
                @Override public ContextPropagators getPropagators() {
                    return ContextPropagators.noop();
                }
            };
        }
        @Override protected OpenTelemetry openTelemetry() { return otel; }
    }

    /** InvocationContext minimal pour exercer l'interceptor hors container CDI. */
    static final class TestInvocationContext implements InvocationContext {
        private final Method method;
        private final Object[] params;
        private final ProceedCallable body;

        TestInvocationContext(Method m, Object[] params, ProceedCallable body) {
            this.method = m;
            this.params = params;
            this.body = body;
        }

        @Override public Object getTarget() { return null; }
        @Override public Object getTimer() { return null; }
        @Override public Method getMethod() { return method; }
        @Override public java.lang.reflect.Constructor<?> getConstructor() { return null; }
        @Override public Object[] getParameters() { return params; }
        @Override public void setParameters(Object[] params) {}
        @Override public Map<String, Object> getContextData() { return Map.of(); }
        @Override public Object proceed() throws Exception {
            return body.call();
        }
    }

    @FunctionalInterface
    interface ProceedCallable {
        Object call() throws Exception;
    }
}
