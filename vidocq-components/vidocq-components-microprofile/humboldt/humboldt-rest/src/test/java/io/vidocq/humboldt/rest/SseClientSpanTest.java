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
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CLIENT span of a MicroProfile Rest Client method that returns a {@code Publisher} (server-sent events) ends
 * once the call has its response, with the response status, while the stream stays open: the OpenTelemetry HTTP
 * semantic conventions end a client span "after the HTTP response headers are fully read (or when they fail to be
 * read)". A stream that fails before any response ends it in error (Vidocq/vidocq#822). The filters are registered
 * the way an application gets them, by {@link HumboldtMpRestClientListener}.
 */
@Timeout(30)
class SseClientSpanTest {

    @Path("/sse")
    public interface EventsClient {
        @GET
        @Path("/events")
        @Produces(MediaType.SERVER_SENT_EVENTS)
        Publisher<String> events();

        @GET
        @Path("/missing")
        @Produces(MediaType.SERVER_SENT_EVENTS)
        Publisher<String> missing();
    }

    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;
    private SseServer server;
    private final CountDownLatch releaseStream = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws IOException {
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

        server = new SseServer(releaseStream);
    }

    @AfterEach
    void tearDown() throws IOException {
        releaseStream.countDown();
        server.close();
        GlobalOpenTelemetry.resetForTest();
        provider.close();
    }

    @Test
    void an_sse_call_ends_its_span_once_the_stream_opens() throws Exception {
        EventsClient client = RestClientBuilder.newBuilder().baseUri(serverUri()).build(EventsClient.class);

        Collecting subscriber = new Collecting();
        client.events().subscribe(subscriber);

        // the response filters, and so the end of the span, run before the first event is delivered
        assertTrue(subscriber.first.await(10, TimeUnit.SECONDS), "no event received");
        SpanData span = oneSpan();
        assertEquals(1, subscriber.done.getCount(), "the span ends while the stream is still open");
        assertEquals(SpanKind.CLIENT, span.kind());
        assertEquals("GET", span.name());
        assertTrue(span.hasEnded());
        assertEquals(200L, span.attributes().get(HumboldtClientResponseFilter.HTTP_RESPONSE_STATUS_CODE));
        assertEquals(StatusCode.UNSET, span.status().code());
        assertNull(span.attributes().get(HumboldtClientRequestFilter.ERROR_TYPE));

        releaseStream.countDown();
        subscriber.await();
        assertNull(subscriber.error.get());
        assertEquals(List.of("one", "two"), subscriber.items);
        assertEquals(1, exporter.getFinishedSpans().size(), "exactly one CLIENT span");
    }

    @Test
    void an_sse_call_answered_with_an_error_status_ends_its_span_in_error() throws Exception {
        EventsClient client = RestClientBuilder.newBuilder().baseUri(serverUri()).build(EventsClient.class);

        Collecting subscriber = new Collecting();
        client.missing().subscribe(subscriber);
        subscriber.await();

        assertNotNull(subscriber.error.get());
        SpanData span = oneSpan();
        assertEquals(404L, span.attributes().get(HumboldtClientResponseFilter.HTTP_RESPONSE_STATUS_CODE));
        assertEquals(StatusCode.ERROR, span.status().code());
        assertEquals(1, exporter.getFinishedSpans().size(), "exactly one CLIENT span");
    }

    @Test
    void an_sse_call_that_gets_no_response_ends_its_span_in_error() throws Exception {
        EventsClient client = RestClientBuilder.newBuilder().baseUri(closedPortUri()).build(EventsClient.class);

        Collecting subscriber = new Collecting();
        client.events().subscribe(subscriber);
        subscriber.await();

        assertNotNull(subscriber.error.get());
        SpanData span = oneSpan();
        assertEquals(SpanKind.CLIENT, span.kind());
        assertEquals(StatusCode.ERROR, span.status().code());
        assertEquals("java.net.ConnectException", span.attributes().get(HumboldtClientRequestFilter.ERROR_TYPE));
        assertNull(span.attributes().get(HumboldtClientResponseFilter.HTTP_RESPONSE_STATUS_CODE));
        assertEquals("exception", span.events().getFirst().name());
        assertEquals(1, exporter.getFinishedSpans().size(), "exactly one CLIENT span");
    }

    /** The span is exported when it ends, synchronously ({@link SimpleSpanProcessor}). */
    private SpanData oneSpan() {
        List<SpanData> spans = exporter.getFinishedSpans();
        assertEquals(1, spans.size(), "one CLIENT span is exported: " + spans);
        return spans.getFirst();
    }

    private URI serverUri() {
        return server.uri();
    }

    /** A local port nothing listens on: the connection is refused at once. */
    private static URI closedPortUri() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }
    }

    /**
     * A minimal HTTP/1.1 server on a raw socket (this module does not read {@code jdk.httpserver}): {@code /sse/events}
     * sends one event, waits for the test, sends a second and closes; any other path answers 404.
     */
    private static final class SseServer implements AutoCloseable {
        private final ServerSocket socket;
        private final CountDownLatch release;
        private final Thread acceptor;

        SseServer(CountDownLatch release) throws IOException {
            this.socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            this.release = release;
            this.acceptor = Thread.ofVirtual().start(this::accept);
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }

        private void accept() {
            while (!socket.isClosed()) {
                try {
                    Socket connection = socket.accept();
                    Thread.ofVirtual().start(() -> serve(connection));
                } catch (IOException _) {
                    return;
                }
            }
        }

        private void serve(Socket connection) {
            try (connection) {
                var in = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
                String requestLine = in.readLine();
                String line;
                do {
                    line = in.readLine();
                } while (line != null && !line.isEmpty());
                OutputStream out = connection.getOutputStream();
                if (requestLine != null && requestLine.contains(" /sse/events ")) {
                    write(out, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n");
                    write(out, "data: one\n\n");
                    release.await(10, TimeUnit.SECONDS);
                    write(out, "data: two\n\n");
                } else {
                    write(out, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                }
            } catch (IOException _) {
                // the client went away
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }

        private static void write(OutputStream out, String text) throws IOException {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        @Override
        public void close() throws IOException {
            socket.close();
            acceptor.interrupt();
        }
    }

    /** Requests every event and records how the stream ends. */
    private static final class Collecting implements Subscriber<String> {
        final List<String> items = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final CountDownLatch first = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);

        @Override public void onSubscribe(Subscription s) { s.request(Long.MAX_VALUE); }
        @Override public void onNext(String item) { items.add(item); first.countDown(); }
        @Override public void onError(Throwable t) { error.set(t); done.countDown(); }
        @Override public void onComplete() { done.countDown(); }

        void await() throws InterruptedException {
            assertTrue(done.await(15, TimeUnit.SECONDS), "the stream did not end");
        }
    }
}
