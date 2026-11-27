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
package io.vidocq.chappe.grpc.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.vidocq.chappe.api.GrpcStatus;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.http.h2.Http2ErrorCode;
import io.vidocq.chappe.http.h2.Http2Frame;
import io.vidocq.chappe.http.h2.Http2Settings;

import org.junit.jupiter.api.Test;

/**
 * The client against a scripted HTTP/2 server: what it writes on the wire, and how it reads the answers a real
 * gRPC server gives only in failure cases.
 */
class GrpcClientProtocolTest {

    private static final byte[] PING = "ping".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PONG = "pong".getBytes(StandardCharsets.UTF_8);

    private static GrpcClient client(FakeH2Server server) {
        return GrpcClient.builder(server.uri()).deadline(Duration.ofSeconds(10)).build();
    }

    @Test
    void the_request_carries_the_grpc_fields_the_metadata_and_the_prefixed_message() throws Exception {
        var seen = new java.util.concurrent.atomic.AtomicReference<FakeH2Server.Request>();
        try (var server = new FakeH2Server(conn -> {
                    var request = conn.request();
                    seen.set(request);
                    conn.ok(request.streamId(), PONG);
                    conn.drain();
                });
                var client = client(server)) {
            GrpcResponse response = client.unary(GrpcRequest.builder("pkg.Echo", "Say")
                    .message(PING)
                    .metadata("x-tenant", "acme")
                    .deadline(Duration.ofSeconds(5))
                    .build());

            assertTrue(response.isOk(), response.toString());
            assertArrayEquals(PONG, response.message().orElseThrow());
            var fields = seen.get().fields();
            assertEquals("POST", fields.get(":method"));
            assertEquals("http", fields.get(":scheme"));
            assertEquals(server.uri().getAuthority(), fields.get(":authority"));
            assertEquals("/pkg.Echo/Say", fields.get(":path"));
            assertEquals("application/grpc", fields.get("content-type"));
            assertEquals("trailers", fields.get("te"));
            assertEquals("identity", fields.get("grpc-accept-encoding"));
            assertEquals("acme", fields.get("x-tenant"));
            String timeout = fields.get("grpc-timeout");
            assertTrue(timeout.matches("\\d{1,8}[numSMH]"), timeout);
            assertTrue(
                    timeout.endsWith("u") && Long.parseLong(timeout.substring(0, timeout.length() - 1)) <= 5_000_000L);
            assertArrayEquals(GrpcResponses.frame(PING), seen.get().body());
        }
    }

    @Test
    void the_client_preface_disables_push_and_opens_the_windows() throws Exception {
        try (var server = new FakeH2Server(conn -> {
                    var request = conn.request();
                    conn.ok(request.streamId(), PONG);
                    conn.drain();
                });
                var client = client(server)) {
            assertTrue(client.unary("s.S", "M", PING).isOk());
            FakeH2Server.Conn conn = server.connections.poll(5, TimeUnit.SECONDS);
            var settings = conn.seen.stream()
                    .filter(f -> f instanceof Http2Frame.SettingsFrame s && !s.ack())
                    .map(f -> ((Http2Frame.SettingsFrame) f).payload())
                    .findFirst()
                    .orElseThrow();
            Http2Settings applied = new Http2Settings(4096, true, 100, 65_535, 16_384, 8_192)
                    .applyFrom(settings.duplicate(), settings.remaining());
            assertEquals(false, applied.enablePush());
            assertEquals(ClientConnection.LOCAL_WINDOW, applied.initialWindowSize());
            assertTrue(conn.seen.stream()
                    .anyMatch(f -> f instanceof Http2Frame.WindowUpdateFrame w
                            && w.streamId() == 0
                            && w.windowIncrement() == ClientConnection.LOCAL_WINDOW - 65_535));
            assertTrue(conn.seen.stream().anyMatch(f -> f instanceof Http2Frame.SettingsFrame s && s.ack()));
        }
    }

    @Test
    void a_header_block_split_over_continuation_frames_and_a_ping_are_handled() throws Exception {
        try (var server = new FakeH2Server(conn -> {
                    var request = conn.request();
                    int id = request.streamId();
                    conn.writer.writeFrame(Http2Frame.TYPE_PING, 0, 0, new byte[8], 0, 8);
                    byte[] block = conn.encoder.encode(
                            200,
                            Headers.builder()
                                    .add("content-type", "application/grpc")
                                    .add("x-big", "v".repeat(40))
                                    .build());
                    int half = block.length / 2;
                    conn.writer.writeFrame(Http2Frame.TYPE_HEADERS, 0, id, block, 0, half);
                    conn.writer.writeFrame(
                            Http2Frame.TYPE_CONTINUATION,
                            Http2Frame.FLAG_END_HEADERS,
                            id,
                            block,
                            half,
                            block.length - half);
                    conn.data(id, GrpcResponses.frame(PONG), false);
                    conn.trailers(id, Headers.of("grpc-status", "0"));
                    conn.drain();
                });
                var client = client(server)) {
            GrpcResponse response = client.unary("s.S", "M", PING);

            assertTrue(response.isOk(), response.toString());
            assertEquals("v".repeat(40), response.headers().firstOrNull("x-big"));
            FakeH2Server.Conn conn = server.connections.poll(5, TimeUnit.SECONDS);
            assertTrue(conn.awaitSeen(f -> f instanceof Http2Frame.PingFrame p && p.ack(), 5), "PING acknowledged");
        }
    }

    @Test
    void a_reset_stream_maps_to_a_grpc_status() throws Exception {
        try (var server = new FakeH2Server(conn -> {
                    int id = conn.request().streamId();
                    conn.writer.writeRstStream(id, Http2ErrorCode.REFUSED_STREAM);
                    id = conn.request().streamId();
                    conn.writer.writeRstStream(id, Http2ErrorCode.ENHANCE_YOUR_CALM);
                    id = conn.request().streamId();
                    conn.writer.writeRstStream(id, Http2ErrorCode.PROTOCOL_ERROR);
                    conn.drain();
                });
                var client = client(server)) {
            GrpcResponse refused = client.unary("s.S", "M", PING);
            assertEquals(GrpcStatus.UNAVAILABLE, refused.status());
            assertTrue(refused.statusMessage().contains("REFUSED_STREAM"), refused.statusMessage());
            assertEquals(
                    GrpcStatus.RESOURCE_EXHAUSTED,
                    client.unary("s.S", "M", PING).status());
            assertEquals(GrpcStatus.INTERNAL, client.unary("s.S", "M", PING).status());
        }
    }

    @Test
    void a_goaway_fails_the_unprocessed_call_and_the_next_call_opens_a_new_connection() throws Exception {
        var accepted = new AtomicInteger();
        try (var server = new FakeH2Server(conn -> {
                    if (accepted.incrementAndGet() == 1) {
                        conn.request();
                        conn.writer.writeGoaway(0, Http2ErrorCode.NO_ERROR);
                    } else {
                        conn.ok(conn.request().streamId(), PONG);
                    }
                    conn.drain();
                });
                var client = client(server)) {
            GrpcResponse refused = client.unary("s.S", "M", PING);
            assertEquals(GrpcStatus.UNAVAILABLE, refused.status());
            assertTrue(refused.statusMessage().contains("GOAWAY"), refused.statusMessage());

            assertTrue(client.unary("s.S", "M", PING).isOk());
            assertEquals(2, accepted.get());
        }
    }

    @Test
    void a_dropped_connection_is_unavailable_and_the_next_call_reconnects() throws Exception {
        var accepted = new AtomicInteger();
        try (var server = new FakeH2Server(conn -> {
                    if (accepted.incrementAndGet() == 1) {
                        conn.request();
                        conn.socket.close();
                    } else {
                        conn.ok(conn.request().streamId(), PONG);
                        conn.drain();
                    }
                });
                var client = client(server)) {
            GrpcResponse lost = client.unary("s.S", "M", PING);
            assertEquals(GrpcStatus.UNAVAILABLE, lost.status());
            assertTrue(lost.cause().isPresent());

            assertTrue(client.unary("s.S", "M", PING).isOk());
        }
    }

    @Test
    void the_deadline_ends_a_silent_call_and_resets_its_stream() throws Exception {
        var reset = new CompletableFuture<Http2Frame>();
        try (var server = new FakeH2Server(conn -> {
                    int id = conn.request().streamId();
                    reset.complete(conn.await(Http2Frame.RstStreamFrame.class, id, 5));
                    conn.drain();
                });
                var client = client(server)) {
            long start = System.nanoTime();
            GrpcResponse response = client.unary(GrpcRequest.builder("s.S", "M")
                    .message(PING)
                    .deadline(Duration.ofMillis(300))
                    .build());
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertEquals(GrpcStatus.DEADLINE_EXCEEDED, response.status());
            assertTrue(elapsed >= 250 && elapsed < 3_000, "returned after " + elapsed + " ms");
            var frame = assertInstanceOf(Http2Frame.RstStreamFrame.class, reset.get(5, TimeUnit.SECONDS));
            assertEquals(Http2ErrorCode.CANCEL.code(), frame.errorCode());
        }
    }

    @Test
    void a_response_past_the_size_limit_is_cancelled() throws Exception {
        var reset = new CompletableFuture<Http2Frame>();
        try (var server = new FakeH2Server(conn -> {
                    int id = conn.request().streamId();
                    conn.headers(id, 200, Headers.of("content-type", "application/grpc"), false);
                    conn.data(id, GrpcResponses.frame(new byte[2_000]), false);
                    reset.complete(conn.await(Http2Frame.RstStreamFrame.class, id, 5));
                    conn.drain();
                });
                var client = GrpcClient.builder(server.uri())
                        .maxResponseMessageSize(1_000)
                        .build()) {
            GrpcResponse response = client.unary("s.S", "M", PING);

            assertEquals(GrpcStatus.RESOURCE_EXHAUSTED, response.status());
            var frame = assertInstanceOf(Http2Frame.RstStreamFrame.class, reset.get(5, TimeUnit.SECONDS));
            assertEquals(Http2ErrorCode.CANCEL.code(), frame.errorCode());
        }
    }

    @Test
    void a_large_request_waits_for_the_servers_windows() throws Exception {
        byte[] large = new byte[200_000];
        for (int i = 0; i < large.length; i++) large[i] = (byte) i;
        var tiny = new Http2Settings(4_096, false, 100, 1_000, 16_384, 8_192);
        try (var server = new FakeH2Server(
                        conn -> {
                            Http2Frame first = conn.next();
                            int id = first.streamId();
                            var body = new java.io.ByteArrayOutputStream();
                            boolean ended = false;
                            while (!ended) {
                                var data = (Http2Frame.DataFrame) conn.next();
                                byte[] bytes = new byte[data.data().remaining()];
                                data.data().get(bytes);
                                assertTrue(bytes.length <= 1_000 || body.size() > 0, "within the stream window");
                                body.writeBytes(bytes);
                                ended = data.endStream();
                                if (!ended) {
                                    conn.writer.writeWindowUpdate(0, bytes.length);
                                    conn.writer.writeWindowUpdate(id, bytes.length);
                                }
                            }
                            byte[] got = body.toByteArray();
                            conn.ok(id, java.util.Arrays.copyOfRange(got, 5, got.length));
                            conn.drain();
                        },
                        tiny);
                var client = client(server)) {
            GrpcResponse response = client.unary("s.S", "M", large);

            assertTrue(response.isOk(), response.toString());
            assertArrayEquals(large, response.message().orElseThrow());
        }
        // No failure in the server script either.
    }

    @Test
    void the_server_limit_of_concurrent_streams_queues_the_calls() throws Exception {
        var single = new Http2Settings(4_096, false, 1, 65_535, 16_384, 8_192);
        var early = new AtomicInteger();
        try (var server = new FakeH2Server(
                        conn -> {
                            for (int i = 0; i < 3; i++) {
                                int id = conn.request().streamId();
                                // While this stream is open, no other may start.
                                if (conn.poll(150) instanceof Http2Frame.HeadersFrame) early.incrementAndGet();
                                conn.ok(id, PONG);
                            }
                            conn.drain();
                        },
                        single);
                var client = client(server)) {
            var threads = new java.util.ArrayList<Thread>();
            var oks = new AtomicInteger();
            for (int i = 0; i < 3; i++) {
                threads.add(Thread.ofVirtual().start(() -> {
                    try {
                        if (client.unary("s.S", "M", PING).isOk()) oks.incrementAndGet();
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            for (Thread thread : threads) thread.join(10_000);

            assertEquals(3, oks.get());
            assertEquals(0, early.get(), "a stream opened past SETTINGS_MAX_CONCURRENT_STREAMS");
        }
    }

    @Test
    void a_server_that_does_not_speak_http2_is_unavailable_with_the_reason() throws Exception {
        try (var http1 = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try (var socket = http1.accept()) {
                    socket.getOutputStream()
                            .write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n"
                                    .getBytes(StandardCharsets.US_ASCII));
                    socket.getInputStream().readAllBytes();
                } catch (IOException _) {
                    // done
                }
            });
            try (var client = GrpcClient.builder(URI.create("http://127.0.0.1:" + http1.getLocalPort()))
                    .build()) {
                GrpcResponse response = client.unary("s.S", "M", PING);

                assertEquals(GrpcStatus.UNAVAILABLE, response.status());
                assertTrue(response.statusMessage().contains("HTTP/2"), response.statusMessage());
            }
        }
    }

    @Test
    void a_closed_port_is_unavailable() throws Exception {
        int port;
        try (var probe = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        try (var client =
                GrpcClient.builder(URI.create("http://127.0.0.1:" + port)).build()) {
            GrpcResponse response = client.unary("s.S", "M", PING);

            assertEquals(GrpcStatus.UNAVAILABLE, response.status());
            assertTrue(response.cause().isPresent());
            assertNull(response.message().orElse(null));
        }
    }

    @Test
    void a_closed_client_refuses_calls_and_ends_those_in_flight() throws Exception {
        var received = new CompletableFuture<Void>();
        try (var server = new FakeH2Server(conn -> {
            conn.request();
            received.complete(null);
            conn.drain();
        })) {
            var client = client(server);
            var outcome = new java.util.concurrent.atomic.AtomicReference<GrpcResponse>();
            Thread caller = Thread.ofVirtual().start(() -> {
                try {
                    outcome.set(client.unary("s.S", "M", PING));
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            });
            received.get(5, TimeUnit.SECONDS);
            client.close();
            caller.join(5_000);

            assertEquals(GrpcStatus.UNAVAILABLE, outcome.get().status());
            assertThrows(IllegalStateException.class, () -> client.unary("s.S", "M", PING));
        }
    }

    @Test
    void the_target_must_be_a_plain_http_or_https_authority() {
        for (String refused : new String[] {"ftp://h:1", "http://h:1/v1/traces", "http://h:1?x=1", "mailto:a@b"}) {
            URI target = URI.create(refused);
            assertThrows(IllegalArgumentException.class, () -> GrpcClient.builder(target), refused);
        }
        var builder = GrpcClient.builder(URI.create("HTTP://h:1/"));
        Duration zero = Duration.ZERO;
        Duration negative = Duration.ofSeconds(-1);
        assertThrows(IllegalArgumentException.class, () -> builder.deadline(zero));
        assertThrows(IllegalArgumentException.class, () -> builder.connectTimeout(negative));
        assertThrows(IllegalArgumentException.class, () -> builder.maxResponseMessageSize(-1));
        assertEquals(URI.create("http://h:1"), builder.build().target());
    }

    @Test
    void a_malformed_response_is_internal() throws Exception {
        try (var server = new FakeH2Server(conn -> {
                    int id = conn.request().streamId();
                    conn.data(id, new byte[] {0}, true); // DATA before any HEADERS
                    id = conn.request().streamId();
                    conn.writer.writeHeaderBlock(id, () -> conn.encoder.encodeTrailers(Headers.of("x", "y")), true);
                    conn.drain();
                });
                var client = client(server)) {
            assertEquals(GrpcStatus.INTERNAL, client.unary("s.S", "M", PING).status());
            assertEquals(GrpcStatus.INTERNAL, client.unary("s.S", "M", PING).status());
        }
    }

    @Test
    void a_push_promise_is_a_protocol_error_of_the_connection() throws Exception {
        try (var server = new FakeH2Server(conn -> {
                    int id = conn.request().streamId();
                    byte[] payload = ByteBuffer.allocate(4).putInt(2).array();
                    conn.writer.writeFrame(
                            Http2Frame.TYPE_PUSH_PROMISE, Http2Frame.FLAG_END_HEADERS, id, payload, 0, 4);
                    conn.drain();
                });
                var client = client(server)) {
            GrpcResponse response = client.unary("s.S", "M", PING);

            assertEquals(GrpcStatus.INTERNAL, response.status());
            assertTrue(response.statusMessage().contains("PUSH_PROMISE"), response.statusMessage());
        }
    }
}
