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
package io.vidocq.humboldt.rest;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.vidocq.humboldt.propagator.w3c.W3CPropagators;
import io.vidocq.humboldt.sdk.trace.SdkTracerProvider;
import io.vidocq.humboldt.sdk.trace.SimpleSpanProcessor;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.humboldt.sdk.trace.export.InMemorySpanExporter;
import io.vidocq.humboldt.sdk.trace.samplers.Sampler;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A REST client call that gets no response still exports its CLIENT span, ended in error, with {@code error.type} and
 * the exception recorded: the Vidocq clients (Cyrano for MicroProfile Rest Client, Cassini for Jakarta REST) hand the
 * failure to the listener {@link HumboldtClientRequestFilter} leaves (Vidocq/vidocq#686, BUG-20261102-03). The filters
 * are registered the way an application gets them, by {@link HumboldtMpRestClientListener} and
 * {@link HumboldtClientTracingFeature}.
 */
class FailedClientCallSpanTest {

    private static final AttributeKey<String> EXCEPTION_TYPE = AttributeKey.stringKey("exception.type");

    @Path("/api")
    public interface PingClient {
        @GET
        @Path("/ping")
        String ping();

        @GET
        @Path("/ping")
        CompletionStage<String> pingAsync();
    }

    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        provider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        SdkTracerProvider tracerProvider = provider;
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(new OpenTelemetry() {
            @Override public TracerProvider getTracerProvider() { return tracerProvider; }
            @Override public ContextPropagators getPropagators() { return W3CPropagators.get(); }
        });
    }

    @AfterEach
    void tearDown() {
        GlobalOpenTelemetry.resetForTest();
        provider.close();
    }

    @Test
    void a_rest_client_call_that_times_out_exports_its_span_in_error() throws IOException {
        try (SilentServer server = new SilentServer()) {
            PingClient client = RestClientBuilder.newBuilder()
                    .baseUri(server.uri())
                    .readTimeout(200, TimeUnit.MILLISECONDS)
                    .build(PingClient.class);

            assertThrows(ProcessingException.class, client::ping);

            assertFailedSpan("java.net.http.HttpTimeoutException");
        }
    }

    @Test
    void a_rest_client_call_to_a_closed_port_exports_its_span_in_error() throws IOException {
        PingClient client = RestClientBuilder.newBuilder()
                .baseUri(closedPortUri())
                .build(PingClient.class);

        assertThrows(ProcessingException.class, client::ping);

        assertFailedSpan("java.net.ConnectException");
    }

    @Test
    void an_asynchronous_rest_client_call_that_fails_exports_its_span_in_error() throws IOException {
        PingClient client = RestClientBuilder.newBuilder()
                .baseUri(closedPortUri())
                .build(PingClient.class);

        var future = client.pingAsync().toCompletableFuture();
        assertThrows(CompletionException.class, future::join);

        assertFailedSpan("java.net.ConnectException");
    }

    @Test
    void a_jakarta_rest_client_call_to_a_closed_port_exports_its_span_in_error() throws IOException {
        URI uri = closedPortUri();
        try (Client client = ClientBuilder.newClient()) {
            var request = client.target(uri).path("api/ping").request();
            assertThrows(ProcessingException.class, request::get);
        }

        assertFailedSpan("java.net.ConnectException");
    }

    private void assertFailedSpan(String errorType) {
        List<SpanData> spans = exporter.getFinishedSpans();
        assertEquals(1, spans.size(), "one CLIENT span is exported: " + spans);
        SpanData span = spans.getFirst();
        assertEquals(SpanKind.CLIENT, span.kind());
        assertEquals("GET", span.name());
        assertEquals(StatusCode.ERROR, span.status().code());
        assertEquals(errorType, span.attributes().get(HumboldtClientRequestFilter.ERROR_TYPE));
        assertNull(span.attributes().get(HumboldtClientResponseFilter.HTTP_RESPONSE_STATUS_CODE));
        assertEquals("exception", span.events().getFirst().name());
        assertEquals(errorType, span.events().getFirst().attributes().get(EXCEPTION_TYPE));
    }

    /** A local port nothing listens on: the connection is refused at once. */
    private static URI closedPortUri() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }
    }

    /** Accepts connections, reads the request and never answers. */
    private static final class SilentServer implements AutoCloseable {
        private final ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        private final Thread acceptor = Thread.ofVirtual().start(this::accept);

        SilentServer() throws IOException {
            // the socket is bound by its initializer
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }

        private void accept() {
            while (!socket.isClosed()) {
                try {
                    Socket connection = socket.accept();
                    Thread.ofVirtual().start(() -> drain(connection));
                } catch (IOException _) {
                    return;
                }
            }
        }

        private static void drain(Socket connection) {
            try (connection; InputStream in = connection.getInputStream()) {
                in.transferTo(OutputStreamDiscard.INSTANCE);
            } catch (IOException _) {
                // the client gave up
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
            acceptor.interrupt();
        }
    }

    private static final class OutputStreamDiscard extends java.io.OutputStream {
        static final OutputStreamDiscard INSTANCE = new OutputStreamDiscard();
        @Override public void write(int b) { /* discarded */ }
        @Override public void write(byte[] b, int off, int len) { /* discarded */ }
    }
}
