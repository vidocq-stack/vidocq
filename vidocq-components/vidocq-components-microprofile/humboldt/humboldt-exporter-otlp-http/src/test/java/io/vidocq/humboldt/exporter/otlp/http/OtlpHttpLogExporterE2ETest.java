package io.vidocq.humboldt.exporter.otlp.http;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.log.SdkLoggerProvider;
import io.vidocq.humboldt.sdk.log.SimpleLogRecordProcessor;
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

import static org.junit.jupiter.api.Assertions.assertTrue;

class OtlpHttpLogExporterE2ETest {

    private HttpServer server;
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger callCount = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/logs", ex -> {
            callCount.incrementAndGet();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            receivedBodies.add(body);
            byte[] resp = "OK".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, resp.length);
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
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/logs";
    }

    @Test
    void e2e_log_record_post_to_collector() {
        OtlpHttpLogExporter exporter = OtlpHttpLogExporter.builder()
                .setEndpoint(endpoint())
                .setRequestTimeout(Duration.ofSeconds(5))
                .build();

        try (SdkLoggerProvider p = SdkLoggerProvider.builder()
                .setResource(Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), "humboldt-log-e2e")))
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                .build()) {
            p.get("io.vidocq.test").logRecordBuilder()
                    .setSeverity(Severity.INFO)
                    .setSeverityText("INFO")
                    .setBody("hello over OTLP")
                    .setAttribute(AttributeKey.stringKey("http.status"), "200")
                    .emit();
            waitForCallCount(1);
        }

        assertTrue(receivedBodies.size() >= 1, "au moins un POST attendu");
        String body = receivedBodies.getFirst();
        assertTrue(body.startsWith("{\"resourceLogs\":["), "format OTLP/JSON logs : " + body);
        assertTrue(body.contains("\"severityNumber\":9"), "INFO = 9 : " + body);
        assertTrue(body.contains("\"severityText\":\"INFO\""));
        assertTrue(body.contains("\"body\":{\"stringValue\":\"hello over OTLP\"}"));
        assertTrue(body.contains("\"service.name\""));
        assertTrue(body.contains("\"http.status\""));
        assertTrue(body.contains("\"observedTimeUnixNano\""));
    }

    private void waitForCallCount(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && callCount.get() < expected) {
            try { Thread.sleep(20); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); return;
            }
        }
    }
}
