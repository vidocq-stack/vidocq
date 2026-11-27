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
package io.vidocq.cyrano.internal;

import com.sun.net.httpserver.HttpServer;
import io.vidocq.cyrano.spi.Cyrano;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.reactivestreams.Publisher;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code Publisher} (SSE) method runs the {@link ClientResponseFilter}s once the response headers arrive: MP Rest
 * Client 4.0 §"Provider Declaration" invokes them "when a response is received", with no exception for server-sent
 * events. A connection that fails before any response tells the failure listener a request filter left under
 * {@link Cyrano#CALL_FAILURE_LISTENER_PROPERTY}, as a synchronous or asynchronous call does (Vidocq/vidocq#822,
 * BUG-20261127-41).
 */
@Timeout(30)
class CyranoSseResponseFiltersTest {

    @Path("/sse")
    public interface SseService {
        @GET
        @Path("/events")
        @Produces(MediaType.SERVER_SENT_EVENTS)
        Publisher<String> events();

        @GET
        @Path("/broken")
        @Produces(MediaType.SERVER_SENT_EVENTS)
        Publisher<String> broken();
    }

    /** What a response filter saw: status, Content-Type, and whether the stream had ended at that time. */
    record Seen(int status, String contentType, long eventsBefore) {
    }

    private HttpServer server;
    private URI baseUri;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();
    private final CountDownLatch releaseStream = new CountDownLatch(1);
    private final CountDownLatch filtered = new CountDownLatch(1);
    private CyranoSseTransportTest.CollectingSubscriber<String> subscriber;

    private final ClientRequestFilter listening = ctx ->
            ctx.setProperty(Cyrano.CALL_FAILURE_LISTENER_PROPERTY, (Consumer<Throwable>) failures::add);
    private final ClientResponseFilter recording = (req, resp) -> {
        seen.add(new Seen(resp.getStatus(), resp.getHeaderString("Content-Type"),
                subscriber == null ? -1 : subscriber.items.size()));
        filtered.countDown();
    };

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sse/events", ex -> {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                writeAndFlush(os, "data: one\n\n");
                // the stream stays open until the test has checked the filters ran
                releaseStream.await(10, TimeUnit.SECONDS);
                writeAndFlush(os, "data: two\n\n");
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        });
        server.createContext("/sse/broken", ex -> {
            byte[] payload = "boom".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(503, payload.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(payload);
            }
        });
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        releaseStream.countDown();
        if (server != null) server.stop(0);
    }

    @Test
    void the_response_filters_run_once_the_stream_opens_not_when_it_ends() throws Exception {
        SseService client = RestClientBuilder.newBuilder()
                .baseUri(baseUri)
                .register(listening)
                .register(recording)
                .build(SseService.class);

        subscriber = new CyranoSseTransportTest.CollectingSubscriber<>(0);
        client.events().subscribe(subscriber);

        assertTrue(filtered.await(10, TimeUnit.SECONDS), "the response filters did not run");
        assertEquals(1, seen.size());
        assertEquals(200, seen.getFirst().status());
        assertEquals("text/event-stream", seen.getFirst().contentType());
        assertEquals(0, seen.getFirst().eventsBefore(), "the filters run before the first event is delivered");
        assertEquals(1, subscriber.done.getCount(), "the stream is still open");

        releaseStream.countDown();
        subscriber.await();
        assertNull(subscriber.error.get());
        assertEquals(List.of("one", "two"), subscriber.items);
        assertEquals(1, seen.size(), "the filters run once");
        assertTrue(failures.isEmpty(), "a stream that opened tells no failure: " + failures);
    }

    @Test
    void an_error_status_runs_the_response_filters_then_fails_the_stream() throws Exception {
        SseService client = RestClientBuilder.newBuilder()
                .baseUri(baseUri)
                .register(listening)
                .register(recording)
                .build(SseService.class);

        subscriber = new CyranoSseTransportTest.CollectingSubscriber<>(0);
        client.broken().subscribe(subscriber);
        subscriber.await();

        assertEquals(List.of(503), seen.stream().map(Seen::status).toList());
        WebApplicationException error = assertInstanceOf(WebApplicationException.class, subscriber.error.get());
        assertEquals(503, error.getResponse().getStatus());
        assertTrue(failures.isEmpty(), "the call got a response: " + failures);
    }

    @Test
    void a_status_a_response_filter_sets_decides_the_outcome() throws Exception {
        SseService client = RestClientBuilder.newBuilder()
                .baseUri(baseUri)
                .register((ClientResponseFilter) (req, resp) -> resp.setStatus(500))
                .build(SseService.class);

        subscriber = new CyranoSseTransportTest.CollectingSubscriber<>(0);
        client.events().subscribe(subscriber);
        subscriber.await();

        WebApplicationException error = assertInstanceOf(WebApplicationException.class, subscriber.error.get());
        assertEquals(500, error.getResponse().getStatus());
    }

    @Test
    void a_refused_connection_tells_the_failure_listener_and_fails_the_stream() throws Exception {
        SseService client = RestClientBuilder.newBuilder()
                .baseUri(closedPortUri())
                .register(listening)
                .register(recording)
                .build(SseService.class);

        subscriber = new CyranoSseTransportTest.CollectingSubscriber<>(0);
        client.events().subscribe(subscriber);
        subscriber.await();

        ProcessingException error = assertInstanceOf(ProcessingException.class, subscriber.error.get());
        assertInstanceOf(ConnectException.class, error.getCause());
        assertEquals(1, failures.size(), "the listener is told once: " + failures);
        assertInstanceOf(ConnectException.class, failures.getFirst(), "the listener receives the cause, unwrapped");
        assertTrue(seen.isEmpty(), "no response, no response filter");
    }

    @Test
    void a_response_filter_that_throws_fails_the_stream_and_tells_the_listener() throws Exception {
        IllegalStateException boom = new IllegalStateException("boom");
        SseService client = RestClientBuilder.newBuilder()
                .baseUri(baseUri)
                .register(listening)
                .register((ClientResponseFilter) (req, resp) -> { throw boom; })
                .build(SseService.class);

        subscriber = new CyranoSseTransportTest.CollectingSubscriber<>(0);
        client.events().subscribe(subscriber);
        subscriber.await();

        assertSame(boom, subscriber.error.get());
        assertEquals(List.of(boom), failures);
    }

    @Test
    void an_aborted_request_runs_the_response_filters_on_the_abort_response() throws Exception {
        SseService client = RestClientBuilder.newBuilder()
                .baseUri(closedPortUri())
                .register(listening)
                .register((ClientRequestFilter) ctx -> ctx.abortWith(CyranoLightResponse.ofStatus(204)))
                .register(recording)
                .build(SseService.class);

        subscriber = new CyranoSseTransportTest.CollectingSubscriber<>(0);
        client.events().subscribe(subscriber);
        subscriber.await();

        assertNull(subscriber.error.get());
        assertEquals(List.of(204), seen.stream().map(Seen::status).toList());
        assertTrue(failures.isEmpty(), "" + failures);
    }

    @Test
    void the_former_publisher_factory_still_streams_without_callbacks() throws Exception {
        try (CyranoHttpTransport transport = new CyranoHttpTransport()) {
            var request = java.net.http.HttpRequest.newBuilder(closedPortUri().resolve("/sse/events")).GET().build();
            @SuppressWarnings("unchecked")
            var publisher = (Publisher<String>) io.vidocq.cyrano.internal.sse.SseReturnSupport.createPublisher(
                    transport, request, String.class, new CyranoClientConfiguration());

            subscriber = new CyranoSseTransportTest.CollectingSubscriber<>(0);
            publisher.subscribe(subscriber);
            subscriber.await();

            ProcessingException error = assertInstanceOf(ProcessingException.class, subscriber.error.get());
            assertInstanceOf(ConnectException.class, error.getCause());
        }
    }

    @Test
    void the_former_publisher_constructor_fails_the_stream_on_a_failed_connection() throws Exception {
        IOException refused = new IOException("refused");
        var publisher = new io.vidocq.cyrano.internal.sse.SseEventPublisher<String>(
                () -> java.util.concurrent.CompletableFuture.failedFuture(refused), raw -> null, event -> null);

        var flowSubscriber = new CyranoSseTransportTest.CollectingSubscriber<String>(0);
        publisher.subscribe(org.reactivestreams.FlowAdapters.toFlowSubscriber(flowSubscriber));
        flowSubscriber.await();

        ProcessingException error = assertInstanceOf(ProcessingException.class, flowSubscriber.error.get());
        assertSame(refused, error.getCause());
    }

    private static void writeAndFlush(OutputStream os, String event) throws IOException {
        os.write(event.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    /** A local port nothing listens on: the connection is refused at once. */
    private static URI closedPortUri() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }
    }
}
