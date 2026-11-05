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
import io.vidocq.humboldt.sdk.common.Releasable;
import io.vidocq.humboldt.sdk.trace.SdkTracerProvider;
import io.vidocq.humboldt.sdk.trace.SimpleSpanProcessor;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.humboldt.sdk.trace.export.InMemorySpanExporter;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The OTLP exporters let their HTTP connections go for a CRaC checkpoint and take them back after the restore: the
 * JDK HttpClient keeps its connections to the collector in a pool, and a checkpoint refuses a process with an open
 * socket or a selector with registered keys.
 */
class OtlpHttpReleaseTest {

    private HttpServer server;
    private final AtomicInteger received = new AtomicInteger();
    private volatile long answerDelayMillis;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/traces", ex -> {
            ex.getRequestBody().readAllBytes();
            try {
                Thread.sleep(answerDelayMillis);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            received.incrementAndGet();
            byte[] resp = "{}".getBytes(StandardCharsets.UTF_8);
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
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces";
    }

    private OtlpHttpSpanExporter exporter() {
        return OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint())
                .setRequestTimeout(Duration.ofSeconds(5))
                .setMaxRetries(0)
                .build();
    }

    private static List<SpanData> oneSpan() {
        InMemorySpanExporter memory = InMemorySpanExporter.create();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(memory))
                .build()) {
            provider.get("io.vidocq.test").spanBuilder("GET /todos").startSpan().end();
        }
        return memory.getFinishedSpans();
    }

    private static CompletableResultCode export(OtlpHttpSpanExporter exporter) {
        return exporter.export(oneSpan()).join(5, TimeUnit.SECONDS);
    }

    @Test
    void aReleasedExporterSendsNothingAndAReopenedOneSendsAgain() {
        OtlpHttpSpanExporter exporter = exporter();
        assertTrue(export(exporter).isSuccess());
        assertEquals(1, received.get());

        exporter.release();
        CompletableResultCode whileReleased = export(exporter);
        assertTrue(whileReleased.isDone(), "a released exporter answers at once");
        assertFalse(whileReleased.isSuccess(), "a released exporter sends nothing");
        assertEquals(1, received.get());

        exporter.reopen();
        assertTrue(export(exporter).isSuccess());
        assertEquals(2, received.get());
    }

    @Test
    void releaseWaitsForTheRequestsInFlight() {
        OtlpHttpSpanExporter exporter = exporter();
        answerDelayMillis = 300;
        CompletableResultCode pending = exporter.export(oneSpan());

        exporter.release();

        assertTrue(pending.isDone(), "release returns once what was sent is answered");
        assertTrue(pending.isSuccess());
        assertEquals(1, received.get());
    }

    @Test
    void releaseAndReopenAreIdempotent() {
        OtlpHttpSpanExporter exporter = exporter();
        exporter.reopen();
        exporter.release();
        exporter.release();
        exporter.reopen();
        exporter.reopen();
        assertTrue(export(exporter).isSuccess());
        assertEquals(1, received.get());
    }

    @Test
    void everyOtlpExporterIsReleasable() {
        assertInstanceOf(Releasable.class, exporter());
        assertInstanceOf(Releasable.class, OtlpHttpMetricExporter.builder().setEndpoint(endpoint()).build());
        assertInstanceOf(Releasable.class, OtlpHttpLogExporter.builder().setEndpoint(endpoint()).build());
    }
}
