/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.humboldt.exporter.otlp.http;

import com.sun.net.httpserver.HttpServer;
import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.log.SdkLoggerProvider;
import io.vidocq.humboldt.sdk.log.SimpleLogRecordProcessor;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;
import io.vidocq.humboldt.sdk.log.export.InMemoryLogRecordExporter;
import io.vidocq.humboldt.sdk.metric.PeriodicMetricReader;
import io.vidocq.humboldt.sdk.metric.SdkMeterProvider;
import io.vidocq.humboldt.sdk.metric.data.MetricData;
import io.vidocq.humboldt.sdk.metric.export.InMemoryMetricExporter;
import io.vidocq.humboldt.sdk.trace.SdkTracerProvider;
import io.vidocq.humboldt.sdk.trace.SimpleSpanProcessor;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.humboldt.sdk.trace.export.InMemorySpanExporter;
import io.vidocq.humboldt.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Delivery outcomes of the three OTLP/HTTP exporters: success, retries, permanent failures, shutdown. */
class OtlpHttpDeliveryTest {

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int[] statuses = {200};

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            int n = calls.getAndIncrement();
            int sc = statuses[Math.min(n, statuses.length - 1)];
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(sc, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static boolean await(CompletableResultCode code) {
        return code.join(10, TimeUnit.SECONDS).isSuccess();
    }

    private static List<SpanData> oneSpan() {
        InMemorySpanExporter memory = InMemorySpanExporter.create();
        try (SdkTracerProvider p = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(memory))
                .build()) {
            p.get("delivery").spanBuilder("span").startSpan().end();
            return memory.getFinishedSpans();
        }
    }

    private static List<LogRecordData> oneLog() {
        InMemoryLogRecordExporter memory = InMemoryLogRecordExporter.create();
        try (SdkLoggerProvider p = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(memory))
                .build()) {
            p.get("delivery").logRecordBuilder().setBody("log").emit();
            return memory.getCollected();
        }
    }

    private static List<MetricData> oneMetric() {
        InMemoryMetricExporter memory = InMemoryMetricExporter.create();
        try (SdkMeterProvider p = SdkMeterProvider.builder()
                .registerMetricReader(PeriodicMetricReader.builder(memory).setInterval(Duration.ofHours(1)).build())
                .build()) {
            p.get("delivery").counterBuilder("hits").build().add(1);
            p.flush().join(10, TimeUnit.SECONDS);
            return memory.getCollected();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void client_error_is_a_permanent_failure_without_retry() {
        statuses = new int[]{400};
        OtlpHttpLogExporter exporter = OtlpHttpLogExporter.builder()
                .setEndpoint(url("/v1/logs"))
                .setMaxRetries(3)
                .build();
        assertFalse(await(exporter.export(oneLog())));
        assertEquals(1, calls.get());
    }

    @Test
    void server_errors_are_retried_until_the_retries_run_out() {
        statuses = new int[]{503};
        OtlpHttpMetricExporter exporter = OtlpHttpMetricExporter.builder()
                .setEndpoint(url("/v1/metrics"))
                .setMaxRetries(1)
                .build();
        assertFalse(await(exporter.export(oneMetric())));
        assertEquals(2, calls.get());
    }

    @Test
    void server_error_then_success_succeeds() {
        statuses = new int[]{500, 204};
        OtlpHttpLogExporter exporter = OtlpHttpLogExporter.builder()
                .setEndpoint(url("/v1/logs"))
                .addHeader("x-a", "b")
                .addHeader(null, "ignored")
                .addHeader("ignored", null)
                .setRequestTimeout(Duration.ofSeconds(5))
                .setConnectTimeout(Duration.ofSeconds(5))
                .build();
        assertTrue(await(exporter.export(oneLog())));
        assertEquals(2, calls.get());
    }

    @Test
    void unreachable_collector_fails_after_the_retries() throws IOException {
        int port = freePort();
        OtlpHttpSpanExporter noRetry = OtlpHttpSpanExporter.builder()
                .setEndpoint("http://127.0.0.1:" + port + "/v1/traces")
                .setMaxRetries(0)
                .build();
        assertFalse(await(noRetry.export(oneSpan())));

        OtlpHttpSpanExporter oneRetry = OtlpHttpSpanExporter.builder()
                .setEndpoint("http://127.0.0.1:" + port + "/v1/traces")
                .setMaxRetries(1)
                .setConnectTimeout(Duration.ofSeconds(2))
                .build();
        assertFalse(await(oneRetry.export(oneSpan())));
    }

    @Test
    void invalid_builder_values_keep_the_defaults() {
        OtlpHttpSpanExporter spans = OtlpHttpSpanExporter.builder()
                .setEndpoint(url("/v1/traces"))
                .setMaxRetries(-1)
                .setRequestTimeout(Duration.ZERO)
                .setConnectTimeout(Duration.ofSeconds(-1))
                .setRequestTimeout(null)
                .setConnectTimeout(null)
                .addHeader(null, null)
                .build();
        OtlpHttpMetricExporter metrics = OtlpHttpMetricExporter.builder()
                .setEndpoint(url("/v1/metrics"))
                .setMaxRetries(-1)
                .setRequestTimeout(Duration.ZERO)
                .setConnectTimeout(Duration.ZERO)
                .addHeader(null, null)
                .build();
        OtlpHttpLogExporter logs = OtlpHttpLogExporter.builder()
                .setEndpoint(url("/v1/logs"))
                .setMaxRetries(-1)
                .setRequestTimeout(null)
                .setConnectTimeout(null)
                .build();
        assertTrue(await(spans.export(oneSpan())));
        assertTrue(await(metrics.export(oneMetric())));
        assertTrue(await(logs.export(oneLog())));
        assertEquals(3, calls.get());
    }

    @Test
    void empty_batches_succeed_without_a_request_and_shutdown_rejects_exports() {
        OtlpHttpSpanExporter spans = OtlpHttpSpanExporter.builder().setEndpoint(url("/v1/traces")).build();
        OtlpHttpMetricExporter metrics = OtlpHttpMetricExporter.builder().setEndpoint(url("/v1/metrics")).build();
        OtlpHttpLogExporter logs = OtlpHttpLogExporter.builder().setEndpoint(url("/v1/logs")).build();

        assertTrue(spans.export(List.of()).isSuccess());
        assertTrue(metrics.export(List.of()).isSuccess());
        assertTrue(logs.export(List.of()).isSuccess());
        assertTrue(spans.flush().isSuccess());
        assertTrue(metrics.flush().isSuccess());
        assertTrue(logs.flush().isSuccess());

        assertTrue(spans.shutdown().isSuccess());
        assertTrue(metrics.shutdown().isSuccess());
        assertTrue(logs.shutdown().isSuccess());
        assertFalse(spans.export(oneSpan()).isSuccess());
        assertFalse(metrics.export(oneMetric()).isSuccess());
        assertFalse(logs.export(oneLog()).isSuccess());
        assertEquals(0, calls.get());
    }
}
