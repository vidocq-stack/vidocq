package io.vidocq.humboldt.propagator.w3c;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class W3CPropagatorsTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String SPAN_ID = "b7ad6b7169203331";

    private final TextMapPropagator propagator = W3CPropagators.textMap();

    private static final TextMapSetter<Map<String, String>> SETTER =
            (carrier, key, value) -> carrier.put(key, value);

    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    @Test
    void composite_advertises_both_field_sets() {
        Set<String> fields = Set.copyOf(propagator.fields());
        assertTrue(fields.contains("traceparent"), "doit publier le header traceparent");
        assertTrue(fields.contains("tracestate"), "doit publier le header tracestate");
        assertTrue(fields.contains("baggage"), "doit publier le header baggage");
    }

    @Test
    void inject_traceparent_w3c_format() {
        SpanContext ctx = SpanContext.create(TRACE_ID, SPAN_ID,
                TraceFlags.getSampled(), TraceState.getDefault());
        Context withSpan = Context.root().with(Span.wrap(ctx));
        Map<String, String> headers = new HashMap<>();

        propagator.inject(withSpan, headers, SETTER);

        String tp = headers.get("traceparent");
        assertNotNull(tp);
        assertEquals("00-" + TRACE_ID + "-" + SPAN_ID + "-01", tp,
                "W3C traceparent doit être version-traceid-spanid-flags (55 chars)");
    }

    @Test
    void extract_traceparent_restores_span_context() {
        Map<String, String> headers = new HashMap<>();
        headers.put("traceparent", "00-" + TRACE_ID + "-" + SPAN_ID + "-01");

        Context extracted = propagator.extract(Context.root(), headers, GETTER);
        SpanContext sc = Span.fromContext(extracted).getSpanContext();
        assertEquals(TRACE_ID, sc.getTraceId());
        assertEquals(SPAN_ID, sc.getSpanId());
        assertTrue(sc.isSampled());
        assertTrue(sc.isRemote(), "le SpanContext extrait doit être marqué remote");
    }

    @Test
    void baggage_roundtrip() {
        Baggage b = Baggage.builder().put("user.id", "u-42").put("tenant", "vidocq").build();
        Context source = Context.root().with(b);

        Map<String, String> headers = new HashMap<>();
        propagator.inject(source, headers, SETTER);
        assertNotNull(headers.get("baggage"));

        Context target = propagator.extract(Context.root(), headers, GETTER);
        Baggage restored = Baggage.fromContext(target);
        assertEquals("u-42", restored.getEntryValue("user.id"));
        assertEquals("vidocq", restored.getEntryValue("tenant"));
    }

    @Test
    void get_singleton_is_stable() {
        org.junit.jupiter.api.Assertions.assertSame(
                W3CPropagators.get(), W3CPropagators.get());
        org.junit.jupiter.api.Assertions.assertSame(
                W3CPropagators.textMap(), W3CPropagators.textMap());
    }
}
