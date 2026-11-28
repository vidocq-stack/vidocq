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
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseFilter;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A call that ends without its response filters having run tells the failure listener a request filter left in the
 * {@link Cyrano#CALL_FAILURE_LISTENER_PROPERTY} property (Vidocq/vidocq#686, Humboldt BUG-20261102-03): Jakarta REST
 * gives a client filter no callback for a call that fails, so a filter that opened something (a span) on the request
 * side could never close it.
 */
class CyranoCallFailureListenerTest {

    @Path("/api")
    interface PingService {
        @GET
        @Path("/ping")
        String ping();

        @GET
        @Path("/ping")
        CompletionStage<String> pingAsync();
    }

    private HttpServer server;
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();

    /** Registers the listener on the request side, as Humboldt's request filter does. */
    private final ClientRequestFilter listening = ctx ->
            ctx.setProperty(Cyrano.CALL_FAILURE_LISTENER_PROPERTY, (Consumer<Throwable>) failures::add);

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void property_name_is_the_one_the_telemetry_filters_set() {
        assertEquals("io.vidocq.rest.client.failure-listener", Cyrano.CALL_FAILURE_LISTENER_PROPERTY);
    }

    @Test
    void a_refused_connection_tells_the_listener_and_still_throws() throws IOException {
        PingService client = RestClientBuilder.newBuilder()
                .baseUri(refusingUri())
                .register(listening)
                .build(PingService.class);

        ProcessingException thrown = assertThrows(ProcessingException.class, client::ping);

        assertEquals(1, failures.size(), "the listener is told once");
        assertSame(thrown, failures.getFirst(), "it receives what the caller receives");
        assertInstanceOf(ConnectException.class, thrown.getCause());
    }

    @Test
    void a_read_timeout_tells_the_listener() throws IOException {
        CountDownLatch release = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/ping", ex -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        });
        server.start();
        try {
            PingService client = RestClientBuilder.newBuilder()
                    .baseUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                    .readTimeout(200, TimeUnit.MILLISECONDS)
                    .register(listening)
                    .build(PingService.class);

            ProcessingException thrown = assertThrows(ProcessingException.class, client::ping);

            assertEquals(1, failures.size());
            assertInstanceOf(HttpTimeoutException.class, thrown.getCause());
        } finally {
            release.countDown();
        }
    }

    @Test
    void an_asynchronous_call_that_fails_tells_the_listener() throws IOException {
        PingService client = RestClientBuilder.newBuilder()
                .baseUri(refusingUri())
                .register(listening)
                .build(PingService.class);

        var future = client.pingAsync().toCompletableFuture();
        CompletionException thrown = assertThrows(CompletionException.class, future::join);

        assertEquals(1, failures.size());
        assertInstanceOf(ConnectException.class, thrown.getCause());
        assertInstanceOf(ConnectException.class, failures.getFirst(), "the listener receives the cause, unwrapped");
    }

    @Test
    void a_response_filter_that_throws_tells_the_listener() throws IOException {
        startPong();
        IllegalStateException boom = new IllegalStateException("boom");
        PingService client = RestClientBuilder.newBuilder()
                .baseUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .register(listening)
                .register((ClientResponseFilter) (req, resp) -> { throw boom; })
                .build(PingService.class);

        assertSame(boom, assertThrows(IllegalStateException.class, client::ping));
        assertEquals(List.of(boom), failures);
    }

    @Test
    void a_call_whose_response_filters_ran_does_not_tell_the_listener() throws IOException {
        startPong();
        PingService client = RestClientBuilder.newBuilder()
                .baseUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .register(listening)
                .build(PingService.class);

        assertEquals("pong", client.ping());
        assertEquals("pong", client.pingAsync().toCompletableFuture().join());
        assertTrue(failures.isEmpty(), "no failure to tell: " + failures);
    }

    @Test
    void a_listener_that_throws_does_not_hide_the_failure() throws IOException {
        PingService client = RestClientBuilder.newBuilder()
                .baseUri(refusingUri())
                .register((ClientRequestFilter) ctx -> ctx.setProperty(Cyrano.CALL_FAILURE_LISTENER_PROPERTY,
                        (Consumer<Throwable>) t -> { throw new IllegalStateException("listener"); }))
                .build(PingService.class);

        ProcessingException thrown = assertThrows(ProcessingException.class, client::ping);
        assertInstanceOf(ConnectException.class, thrown.getCause());
    }

    @Test
    void a_property_that_is_not_a_listener_is_ignored() throws IOException {
        PingService client = RestClientBuilder.newBuilder()
                .baseUri(refusingUri())
                .register((ClientRequestFilter) ctx -> ctx.setProperty(Cyrano.CALL_FAILURE_LISTENER_PROPERTY, "x"))
                .build(PingService.class);

        ProcessingException thrown = assertThrows(ProcessingException.class, client::ping);
        assertInstanceOf(ConnectException.class, thrown.getCause());
    }

    private void startPong() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/ping", ex -> {
            byte[] b = "pong".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    /** A local port nothing listens on: the connection is refused at once. */
    private static URI refusingUri() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        return URI.create("http://127.0.0.1:" + port);
    }
}
