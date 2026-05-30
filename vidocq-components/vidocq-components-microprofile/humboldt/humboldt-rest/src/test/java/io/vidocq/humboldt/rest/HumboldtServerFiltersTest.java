package io.vidocq.humboldt.rest;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.vidocq.humboldt.propagator.w3c.W3CPropagators;
import io.vidocq.humboldt.sdk.trace.SdkTracerProvider;
import io.vidocq.humboldt.sdk.trace.SimpleSpanProcessor;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.humboldt.sdk.trace.export.InMemorySpanExporter;
import io.vidocq.humboldt.sdk.trace.samplers.Sampler;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.opentelemetry.api.trace.SpanKind.SERVER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests without a CDI/JAX-RS container: build {@link ContainerRequestContext}
 * and {@link ContainerResponseContext} through {@link Proxy java.lang.reflect.Proxy} to
 * route only the ~6 methods actually consumed by the filters, without
 * having to implement the ~40 other abstract methods from the JAX-RS 4.0 API
 * (which change between versions, see lessons.md).
 */
class HumboldtServerFiltersTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;
    private TestableRequestFilter reqFilter;
    private HumboldtServerResponseFilter respFilter;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        provider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        reqFilter = new TestableRequestFilter(provider);
        respFilter = new HumboldtServerResponseFilter();
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    @Test
    void start_span_with_method_and_path() {
        ContainerRequestContext req = req("GET", "/users/42", null);
        reqFilter.filter(req);
        respFilter.filter(req, resp(200));

        SpanData s = exporter.getFinishedSpans().getFirst();
        assertEquals("GET /users/42", s.name());
        assertEquals(SERVER, s.kind());
        assertEquals("GET", s.attributes().get(AttributeKey.stringKey("http.request.method")));
        assertEquals("/users/42", s.attributes().get(AttributeKey.stringKey("url.path")));
        assertEquals("http", s.attributes().get(AttributeKey.stringKey("url.scheme")));
        assertEquals(200L, s.attributes().get(AttributeKey.longKey("http.response.status_code")));
    }

    @Test
    void extracts_w3c_traceparent_as_parent() {
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        String spanId = "b7ad6b7169203331";
        ContainerRequestContext req = req("POST", "/orders",
                "00-" + traceId + "-" + spanId + "-01");

        reqFilter.filter(req);
        respFilter.filter(req, resp(201));

        SpanData s = exporter.getFinishedSpans().getFirst();
        assertEquals(traceId, s.spanContext().getTraceId(),
                "SERVER span must inherit traceId from W3C traceparent");
        assertEquals(spanId, s.parentSpanContext().getSpanId(),
                "parent must point to traceparent span ID");
    }

    @Test
    void status_500_sets_error_status() {
        ContainerRequestContext req = req("GET", "/boom", null);
        reqFilter.filter(req);
        respFilter.filter(req, resp(500));

        SpanData s = exporter.getFinishedSpans().getFirst();
        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, s.status().code());
        assertEquals("HTTP 500", s.status().description());
        assertEquals(500L, s.attributes().get(AttributeKey.longKey("http.response.status_code")));
    }

    @Test
    void status_4xx_does_not_set_error() {
        ContainerRequestContext req = req("GET", "/missing", null);
        reqFilter.filter(req);
        respFilter.filter(req, resp(404));

        SpanData s = exporter.getFinishedSpans().getFirst();
        assertEquals(io.opentelemetry.api.trace.StatusCode.UNSET, s.status().code(),
                "4xx = client error, must NOT mark ERROR (aligned with OTel HTTP semantic)");
        assertEquals(404L, s.attributes().get(AttributeKey.longKey("http.response.status_code")));
    }

    @Test
    void response_filter_is_idempotent_on_missing_span_property() {
        ContainerRequestContext req = req("GET", "/x", null);
        respFilter.filter(req, resp(200)); // no reqFilter beforehand: must not blow up
        assertEquals(0, exporter.getFinishedSpans().size());
    }

    @Test
    void span_property_is_cleared_after_response() {
        ContainerRequestContext req = req("GET", "/x", null);
        reqFilter.filter(req);
        assertTrue(req.getProperty(HumboldtServerRequestFilter.SPAN_PROPERTY) != null,
                "request filter must set SPAN property");
        respFilter.filter(req, resp(200));
        assertNull(req.getProperty(HumboldtServerRequestFilter.SPAN_PROPERTY),
                "response filter must clean up property");
    }

    // ============================================================
    //  Helpers: ContainerRequestContext / ResponseContext via Proxy
    // ============================================================

    private ContainerRequestContext req(String method, String path, String traceparent) {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        if (traceparent != null) headers.add("traceparent", traceparent);
        Map<String, Object> props = new HashMap<>();
        UriInfo uriInfo = uriInfo(path);

        return (ContainerRequestContext) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ContainerRequestContext.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "getMethod" -> method;
                    case "getHeaders" -> headers;
                    case "getUriInfo" -> uriInfo;
                    case "getProperty" -> props.get(args[0]);
                    case "setProperty" -> { props.put((String) args[0], args[1]); yield null; }
                    case "removeProperty" -> { props.remove(args[0]); yield null; }
                    case "getPropertyNames" -> props.keySet();
                    default -> defaultForReturnType(m);
                });
    }

    private ContainerResponseContext resp(int status) {
        return (ContainerResponseContext) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ContainerResponseContext.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "getStatus" -> status;
                    default -> defaultForReturnType(m);
                });
    }

    private UriInfo uriInfo(String path) {
        String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        URI requestUri = URI.create("http://localhost/" + normalizedPath);
        return (UriInfo) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{UriInfo.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "getPath" -> normalizedPath;
                    case "getRequestUri" -> requestUri;
                    default -> defaultForReturnType(m);
                });
    }

    /** Safe default value based on the return type — avoids NPEs on uncovered methods. */
    private static Object defaultForReturnType(Method m) {
        Class<?> r = m.getReturnType();
        if (r == void.class) return null;
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        if (r == List.class) return List.of();
        if (r == Map.class) return Map.of();
        if (r == java.util.Set.class) return java.util.Set.of();
        if (MultivaluedMap.class.isAssignableFrom(r)) return new MultivaluedHashMap<>();
        return null;
    }

    /** Testable filter subclass — points to the local SdkTracerProvider + W3C propagators. */
    static final class TestableRequestFilter extends HumboldtServerRequestFilter {
        private final OpenTelemetry otel;

        TestableRequestFilter(SdkTracerProvider tp) {
            this.otel = new OpenTelemetry() {
                @Override public TracerProvider getTracerProvider() { return tp; }
                @Override public ContextPropagators getPropagators() { return W3CPropagators.get(); }
            };
        }

        @Override protected OpenTelemetry openTelemetry() { return otel; }
    }
}
