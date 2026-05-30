package io.vidocq.runtime.it.humboldt;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E tests that validate the full Humboldt integration in Vidocq:
 * <ol>
 *   <li><b>BCE @WithSpan</b> — the {@code BuildCompatibleExtension} of
 *       {@code humboldt-cdi} adds {@code @SpanBinding} to the methods
 *       annotated {@code @WithSpan} OTel → the interceptor is activated.
 *       (resolution of risk PLAN.md §15.1: Vauban CDI Lite supports BCE)</li>
 *   <li><b>Span SERVER via humboldt-rest filters</b> — each HTTP request
 *       produces a SERVER span with OTel HTTP semantic attrs.</li>
 *   <li><b>Inbound W3C propagation</b> — a header {@code traceparent}
 *       inherits the traceId from the SERVER span.</li>
 *   <li><b>Status ERROR set to 500</b> — when the endpoint throw, the SERVER span
 *       receives {@code status=ERROR}.</li>
 * </ol>
 *
 * <p>The whole stack is started by Arquillian: Chappe (HTTP) → Cassini
 * (JAX-RS) → Vauban (CDI) → humboldt-runtime auto-config → humboldt-cdi BCE +
 * interceptor + humboldt-rest filters.</p>
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
class HumboldtIntegrationTest {

    @ArquillianResource
    private URL baseUrl;

    // The OTEL_* system properties are set by surefire (see pom.xml of the module)
    // so that they arrive BEFORE HumboldtExtension boots.

    @Deployment
    public static JavaArchive createDeployment() {
        return ShrinkWrap.create(JavaArchive.class, "humboldt-it.jar")
                .addClass(TraceTestService.class)
                .addClass(TraceTestResource.class)
                .addClass(SpansResource.class)
                // Humboldt-rest/cdi classes to include so that Cassini/Vauban
                // scans them in this isolated Arquillian Deployment (the classes
                // exist on the classpath via Maven deps, but scoping
                // Arquillian Vidocq requires their explicit presence).
                .addClass(io.vidocq.humboldt.rest.HumboldtServerRequestFilter.class)
                .addClass(io.vidocq.humboldt.rest.HumboldtServerResponseFilter.class)
                .addClass(io.vidocq.humboldt.rest.HumboldtSpanFinalizer.class)
                .addClass(io.vidocq.humboldt.cdi.WithSpanInterceptor.class)
                .addClass(io.vidocq.humboldt.cdi.SpanBinding.class)
                .addClass(io.vidocq.humboldt.cdi.HumboldtBuildCompatibleExtension.class);
    }

    @BeforeEach
    void resetSpansBeforeEachTest() throws IOException {
        httpRequest("DELETE", "/__spans", null);
    }

    // ============================================================
    //  Test 1 — BCE @WithSpan (risk PLAN §15.1 confirmation)
    // ============================================================
    @Test
    void with_span_bce_intercepts_cdi_method() throws IOException {
        var resp = httpGet("/trace/work");
        assertEquals(200, resp.statusCode);
        assertEquals("work-result", resp.body);

        String spans = httpGet("/__spans").body;
        // At least 2 spans: SERVER (GET /trace/work) + INTERNAL (traced.work)
        assertTrue(spans.contains("\"name\":\"traced.work\""),
                "The BCE should have activated the interceptor on @WithSpan(\"traced.work\") - spans: " + spans);
        assertTrue(spans.contains("\"kind\":\"INTERNAL\""));
        assertTrue(spans.contains("\"name\":\"GET /trace/work\""),
                "The humboldt-rest filter should have created a SERVER span");
    }

    // ============================================================
    //  Test 2 — Span SERVER via humboldt-rest filter
    // ============================================================
    @Test
    void rest_filter_creates_server_span_with_otel_http_attrs() throws IOException {
        var resp = httpGet("/trace/plain");
        assertEquals(200, resp.statusCode);
        assertEquals("plain-result", resp.body);

        String spans = httpGet("/__spans").body;
        // Span SERVER expected for /trace/plain with attrs OTel HTTP semantic
        assertTrue(spans.contains("\"name\":\"GET /trace/plain\""),
                "Expected SERVER span: " + spans);
        assertTrue(spans.contains("\"kind\":\"SERVER\""));
        assertTrue(spans.contains("\"urlPath\":\"/trace/plain\""),
                "Expected url.path attribute: " + spans);
        assertTrue(spans.contains("\"httpStatus\":200"),
                "Expected http.response.status_code=200 attribute: " + spans);
        // Note: the ECB applies binding at the class level (all methods of the bean
        // that has @WithSpan somewhere are intercepted). plain() therefore also receives an
        // span INTERNAL — Vauban CDI Lite behavior consistent with the pattern
        // SmallRye/Quarkus. No strict assertion about absence.
    }

    // ============================================================
    //  Test 3 — Inbound W3C Propagation
    // ============================================================
    @Test
    void w3c_traceparent_header_propagates_trace_id() throws IOException {
        String parentTraceId = "0af7651916cd43dd8448eb211c80319c";
        String parentSpanId = "b7ad6b7169203331";
        String traceparent = "00-" + parentTraceId + "-" + parentSpanId + "-01";

        var resp = httpGetWithHeader("/trace/plain", "traceparent", traceparent);
        assertEquals(200, resp.statusCode);

        String spans = httpGet("/__spans").body;
        assertTrue(spans.contains("\"traceId\":\"" + parentTraceId + "\""),
                "The SERVER span should inherit the W3C traceId: " + spans);
        assertTrue(spans.contains("\"parentSpanId\":\"" + parentSpanId + "\""),
                "The SERVER span parentSpanId should point to the traceparent spanId: " + spans);
    }

    // ============================================================
    //  Test 4 - Status ERROR on 500
    // ============================================================
    @Test
    void server_span_status_error_when_endpoint_throws() throws IOException {
        var resp = httpGet("/trace/boom");
        assertEquals(500, resp.statusCode, "The /boom endpoint should return 500");

        String spans = httpGet("/__spans").body;
        // The humboldt-cdi interceptor @WithSpan("traced.boom") must have captured
        // the exception and set status=ERROR on the INTERNAL span.
        assertTrue(spans.contains("\"name\":\"traced.boom\""),
                "Expected INTERNAL span @WithSpan(\"traced.boom\"): " + spans);
        assertTrue(spans.contains("IllegalStateException: boom from traced.boom"),
                "The exception message should be in status.description: " + spans);

        // M6d.6 — The SERVER span for /trace/boom should now be present
        // thanks to HumboldtSpanFinalizer (ExceptionMapper<Throwable> which ends
        // the span when Cassini short-circuits the response filter — Cassini bug
        // documented in HumboldtSpanFinalizer javadoc, workaround on humboldt-rest side).
        assertTrue(spans.contains("\"name\":\"GET /trace/boom\""),
                "Expected SERVER span /trace/boom (completed by HumboldtSpanFinalizer): " + spans);
        assertTrue(spans.contains("\"httpStatus\":500"),
                "Expected http.response.status_code=500 on the SERVER span: " + spans);
        assertTrue(spans.contains("\"status\":\"ERROR\""),
                "At least one span should be ERROR (SERVER and/or INTERNAL): " + spans);
    }

    // ============================================================
    //  HTTP helpers
    // ============================================================

    private SimpleResponse httpGet(String path) throws IOException {
        return httpRequest("GET", path, null);
    }

    private SimpleResponse httpGetWithHeader(String path, String headerName, String headerValue) throws IOException {
        URL url = new URL(baseUrl, path.startsWith("/") ? path.substring(1) : path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty(headerName, headerValue);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        int statusCode = conn.getResponseCode();
        String body = readBody(conn, statusCode);
        conn.disconnect();
        return new SimpleResponse(statusCode, body);
    }

    private SimpleResponse httpRequest(String method, String path, String headerValueOrNull) throws IOException {
        URL url = new URL(baseUrl, path.startsWith("/") ? path.substring(1) : path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        int statusCode = conn.getResponseCode();
        String body = readBody(conn, statusCode);
        conn.disconnect();
        return new SimpleResponse(statusCode, body);
    }

    private static String readBody(HttpURLConnection conn, int statusCode) throws IOException {
        InputStream is = statusCode < 400 ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) return "";
        try (is) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record SimpleResponse(int statusCode, String body) {}
}
