package io.vidocq.humboldt.exporter.otlp.http;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.trace.SdkTracerProvider;
import io.vidocq.humboldt.sdk.trace.SimpleSpanProcessor;
import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtlpHttpSpanExporterE2ETest {

    private HttpServer server;
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();
    private final List<String> receivedAuthHeaders = new CopyOnWriteArrayList<>();
    private final AtomicInteger callCount = new AtomicInteger();
    private volatile int[] responseCodesPerCall = new int[]{200};

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", ex -> {
            int n = callCount.getAndIncrement();
            int sc = responseCodesPerCall[Math.min(n, responseCodesPerCall.length - 1)];
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth != null) receivedAuthHeaders.add(auth);
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            receivedBodies.add(body);
            byte[] resp = ("status=" + sc).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(sc, resp.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces";
    }

    @Test
    void simple_export_post_otlp_json_payload() {
        responseCodesPerCall = new int[]{200};

        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint())
                .setRequestTimeout(Duration.ofSeconds(5))
                .build();
        try (SdkTracerProvider p = SdkTracerProvider.builder()
                .setResource(Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), "humboldt-e2e")))
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            Tracer t = p.get("io.vidocq.test");
            Span s = t.spanBuilder("GET /health").startSpan();
            s.setAttribute("http.method", "GET");
            s.end();

            // SimpleSpanProcessor exports synchronously; the HTTP round-trip happens
            // on an exporter-side VT — we wait until the fake server counts 1 call.
            waitForCallCount(1);
        }

        assertEquals(1, receivedBodies.size());
        String body = receivedBodies.getFirst();
        assertTrue(body.contains("\"name\":\"GET /health\""), "payload : " + body);
        assertTrue(body.contains("\"service.name\""));
        assertTrue(body.contains("\"http.method\""));
        assertTrue(body.startsWith("{\"resourceSpans\":["));
    }

    @Test
    void retry_on_503_then_succeeds() {
        responseCodesPerCall = new int[]{503, 503, 200};

        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint())
                .setMaxRetries(3)
                .setRequestTimeout(Duration.ofSeconds(2))
                .build();

        CompletableResultCode rc;
        try (SdkTracerProvider p = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            Tracer t = p.get("io.vidocq.test");
            Span s = t.spanBuilder("retry-test").startSpan();
            s.end();
            // At least 3 expected calls (503 + 503 + 200)
            waitForCallCount(3);
        }
        assertTrue(callCount.get() >= 3, "callCount = " + callCount.get());
    }

    @Test
    void custom_headers_are_sent() {
        responseCodesPerCall = new int[]{200};

        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint())
                .addHeader("Authorization", "Bearer secret-token-42")
                .build();
        try (SdkTracerProvider p = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            p.get("x").spanBuilder("hdr").startSpan().end();
            waitForCallCount(1);
        }
        assertNotNull(receivedAuthHeaders);
        assertEquals(1, receivedAuthHeaders.size());
        assertEquals("Bearer secret-token-42", receivedAuthHeaders.getFirst());
    }

    @Test
    void backoff_is_exponential_capped() {
        // Sanity check of the helper function (without depending on E2E timing)
        assertEquals(100L, OtlpHttpSpanExporter.computeBackoffMillis(0));
        assertEquals(200L, OtlpHttpSpanExporter.computeBackoffMillis(1));
        assertEquals(400L, OtlpHttpSpanExporter.computeBackoffMillis(2));
        assertEquals(5000L, OtlpHttpSpanExporter.computeBackoffMillis(10),
                "5s ceiling reached for large attempts");
    }

    private void waitForCallCount(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && callCount.get() < expected) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
