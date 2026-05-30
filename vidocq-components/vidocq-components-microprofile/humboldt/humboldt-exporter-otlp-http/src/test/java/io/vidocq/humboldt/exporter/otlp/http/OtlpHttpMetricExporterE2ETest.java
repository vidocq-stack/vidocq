package io.vidocq.humboldt.exporter.otlp.http;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.metric.PeriodicMetricReader;
import io.vidocq.humboldt.sdk.metric.SdkMeterProvider;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtlpHttpMetricExporterE2ETest {

    private HttpServer server;
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger callCount = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/metrics", ex -> {
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
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/metrics";
    }

    @Test
    void e2e_counter_and_histogram_post_to_collector() {
        OtlpHttpMetricExporter exporter = OtlpHttpMetricExporter.builder()
                .setEndpoint(endpoint())
                .setRequestTimeout(Duration.ofSeconds(5))
                .build();

        try (SdkMeterProvider p = SdkMeterProvider.builder()
                .setResource(Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), "humboldt-metric-e2e")))
                .registerMetricReader(PeriodicMetricReader.builder(exporter)
                        .setInterval(Duration.ofSeconds(60)).build())
                .build()) {
            LongCounter c = p.get("io.vidocq.test").counterBuilder("requests").build();
            c.add(7L, Attributes.of(AttributeKey.stringKey("status"), "ok"));

            DoubleHistogram h = p.get("io.vidocq.test").histogramBuilder("latency")
                    .setUnit("ms").build();
            h.record(42.0);

            // flush triggers an immediate collect+export cycle
            p.flush().join(3, TimeUnit.SECONDS);
            waitForCallCount(1);
        }

        assertTrue(receivedBodies.size() >= 1, "at least one POST expected");
        String body = receivedBodies.getFirst();
        assertTrue(body.startsWith("{\"resourceMetrics\":["), "format OTLP/JSON metrics : " + body);
        assertTrue(body.contains("\"name\":\"requests\""));
        assertTrue(body.contains("\"name\":\"latency\""));
        assertTrue(body.contains("\"asInt\":\"7\""), "Counter value asInt string : " + body);
        assertTrue(body.contains("\"sum\":42.0"), "Histogram sum : " + body);
        assertTrue(body.contains("\"explicitBounds\""));
        assertTrue(body.contains("\"isMonotonic\":true"), "Counter must be monotonic");
        assertTrue(body.contains("\"aggregationTemporality\":2"), "CUMULATIVE = 2");
        assertTrue(body.contains("\"service.name\""));
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
