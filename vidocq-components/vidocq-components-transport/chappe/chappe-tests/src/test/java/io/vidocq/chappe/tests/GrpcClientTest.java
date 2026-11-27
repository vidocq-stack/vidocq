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
package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;

import io.vidocq.chappe.api.GrpcHandler;
import io.vidocq.chappe.api.GrpcStatus;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.grpc.client.GrpcClient;
import io.vidocq.chappe.grpc.client.GrpcRequest;
import io.vidocq.chappe.grpc.client.GrpcResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Chappe's gRPC client (chappe-grpc-client, Vidocq/vidocq#837) against Chappe's own gRPC server, in cleartext
 * HTTP/2 with prior knowledge and over TLS with ALPN {@code h2}. The server's wire format is checked against
 * {@code grpcurl} by {@link GrpcurlConformanceTest}.
 */
// A hung call fails its test instead of the build: every call here should end within seconds.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class GrpcClientTest {

    private static final String SERVICE = "echo.EchoService";
    private static final byte[] HELLO = "hello".getBytes(StandardCharsets.UTF_8);

    private static SSLContext serverContext;
    private static SSLContext clientContext;

    private Server server;

    @BeforeAll
    static void certificates() throws Exception {
        KeyStore serverKeys = TlsFixtures.selfSigned("server", "CN=localhost", "ip:127.0.0.1");
        KeyStore trustServer = TlsFixtures.trustStore(TlsFixtures.certificate(serverKeys, "server"));
        serverContext = TlsFixtures.context(serverKeys, trustServer);
        clientContext = TlsFixtures.context(null, trustServer);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    private URI start(String method, GrpcHandler handler) {
        server = Server.builder()
                .port(0)
                .handler(Router.builder()
                        .grpc("/" + SERVICE + "/" + method, handler)
                        .build())
                .build();
        server.start();
        return URI.create("http://127.0.0.1:" + server.port());
    }

    private static void echo(io.vidocq.chappe.api.GrpcCall call) throws java.io.IOException {
        call.send(call.receive());
        call.complete(GrpcStatus.OK, "");
    }

    @Test
    void a_unary_call_echoes_its_message() throws Exception {
        URI target = start("Echo", GrpcClientTest::echo);
        try (var client =
                GrpcClient.builder(target).deadline(Duration.ofSeconds(5)).build()) {
            GrpcResponse response = client.unary(SERVICE, "Echo", HELLO);

            assertTrue(response.isOk(), response.toString());
            assertArrayEquals(HELLO, response.message().orElseThrow());
            assertEquals("0", response.trailers().firstOrNull("grpc-status"));
            assertTrue(response.headers().firstOrNull("content-type").startsWith("application/grpc"));
        }
    }

    @Test
    void metadata_reaches_the_server_and_comes_back_in_headers_and_trailers() throws Exception {
        var tenant = new AtomicReference<String>();
        URI target = start("Echo", call -> {
            tenant.set(call.metadata().firstOrNull("x-tenant"));
            call.addHeader("x-served-by", "chappe");
            byte[] message = call.receive();
            call.send(message);
            call.addTrailer("x-cost", "3");
            call.complete(GrpcStatus.OK, "");
        });
        try (var client =
                GrpcClient.builder(target).deadline(Duration.ofSeconds(10)).build()) {
            GrpcResponse response = client.unary(GrpcRequest.builder(SERVICE, "Echo")
                    .message(HELLO)
                    .metadata("x-tenant", "acme")
                    .build());

            assertTrue(response.isOk(), response.toString());
            assertEquals("acme", tenant.get());
            assertEquals("chappe", response.headers().firstOrNull("x-served-by"));
            assertEquals("3", response.trailers().firstOrNull("x-cost"));
        }
    }

    @Test
    void a_non_zero_status_and_its_message_come_from_the_trailers() throws Exception {
        URI target = start("Echo", call -> {
            call.send(call.receive());
            call.complete(GrpcStatus.FAILED_PRECONDITION, "café not open: 100%");
        });
        try (var client =
                GrpcClient.builder(target).deadline(Duration.ofSeconds(10)).build()) {
            GrpcResponse response = client.unary(SERVICE, "Echo", HELLO);

            assertEquals(GrpcStatus.FAILED_PRECONDITION, response.status());
            assertEquals("café not open: 100%", response.statusMessage());
            assertTrue(response.message().isEmpty());
        }
    }

    @Test
    void a_trailers_only_response_carries_the_status() throws Exception {
        URI target = start("Echo", call -> {
            call.receive();
            call.complete(GrpcStatus.PERMISSION_DENIED, "no");
        });
        try (var client =
                GrpcClient.builder(target).deadline(Duration.ofSeconds(10)).build()) {
            GrpcResponse response = client.unary(SERVICE, "Echo", HELLO);

            assertEquals(GrpcStatus.PERMISSION_DENIED, response.status());
            assertEquals("no", response.statusMessage());
            assertEquals(0, response.trailers().size());
            assertEquals(
                    String.valueOf(GrpcStatus.PERMISSION_DENIED),
                    response.headers().firstOrNull("grpc-status"));
        }
    }

    @Test
    void the_deadline_reaches_the_server_and_ends_a_slow_call() throws Exception {
        var serverDeadline = new AtomicReference<Optional<Duration>>();
        var release = new CountDownLatch(1);
        byte[] fast = "fast".getBytes(StandardCharsets.UTF_8);
        URI target = start("Echo", call -> {
            byte[] message = call.receive();
            if (!Arrays.equals(fast, message)) {
                serverDeadline.set(call.deadline());
                try {
                    release.await(5, TimeUnit.SECONDS); // far past the deadline of the call
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }
            call.send(message);
            call.complete(GrpcStatus.OK, "");
        });
        try (var client =
                GrpcClient.builder(target).deadline(Duration.ofSeconds(10)).build()) {
            long start = System.nanoTime();
            GrpcResponse response = client.unary(GrpcRequest.builder(SERVICE, "Echo")
                    .message(HELLO)
                    .deadline(Duration.ofMillis(300))
                    .build());
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertEquals(GrpcStatus.DEADLINE_EXCEEDED, response.status(), response.toString());
            assertTrue(elapsed < 3_000, "returned after " + elapsed + " ms");
            Duration seen = serverDeadline.get().orElseThrow();
            assertTrue(seen.compareTo(Duration.ofMillis(300)) <= 0 && !seen.isNegative(), seen.toString());

            // The connection still serves the next call.
            assertTrue(client.unary(SERVICE, "Echo", fast).isOk());
        } finally {
            release.countDown();
        }
    }

    @Test
    void an_unknown_method_is_unimplemented() throws Exception {
        URI target = start("Echo", GrpcClientTest::echo);
        try (var client =
                GrpcClient.builder(target).deadline(Duration.ofSeconds(10)).build()) {
            GrpcResponse response = client.unary(SERVICE, "Missing", HELLO);

            assertEquals(GrpcStatus.UNIMPLEMENTED, response.status(), response.toString());
        }
    }

    @Test
    void a_compressed_response_is_refused_by_name() throws Exception {
        URI target = start("Echo", call -> {
            call.useResponseEncoding("gzip");
            echo(call);
        });
        try (var client =
                GrpcClient.builder(target).deadline(Duration.ofSeconds(10)).build()) {
            GrpcResponse response = client.unary(SERVICE, "Echo", HELLO);

            assertEquals(GrpcStatus.INTERNAL, response.status());
            assertTrue(response.statusMessage().contains("compressed"), response.statusMessage());
            assertTrue(response.statusMessage().contains("gzip"), response.statusMessage());
        }
    }

    @Test
    void messages_past_the_flow_control_windows_go_both_ways() throws Exception {
        byte[] large = new byte[3 * 1024 * 1024];
        for (int i = 0; i < large.length; i++) large[i] = (byte) (i * 31);
        URI target = start("Echo", GrpcClientTest::echo);
        try (var client =
                GrpcClient.builder(target).deadline(Duration.ofSeconds(30)).build()) {
            GrpcResponse response = client.unary(SERVICE, "Echo", large);

            assertTrue(response.isOk(), response.toString());
            assertArrayEquals(large, response.message().orElseThrow());
        }
    }

    @Test
    void concurrent_calls_share_one_connection() throws Exception {
        var calls = new AtomicInteger();
        URI target = start("Echo", call -> {
            calls.incrementAndGet();
            echo(call);
        });
        try (var client =
                GrpcClient.builder(target).deadline(Duration.ofSeconds(10)).build()) {
            var threads = new ArrayList<Thread>();
            var oks = new AtomicInteger();
            for (int i = 0; i < 32; i++) {
                byte[] message = ("call-" + i).getBytes(StandardCharsets.UTF_8);
                threads.add(Thread.ofVirtual().start(() -> {
                    try {
                        GrpcResponse response = client.unary(SERVICE, "Echo", message);
                        if (response.isOk()
                                && java.util.Arrays.equals(
                                        message, response.message().orElseThrow())) {
                            oks.incrementAndGet();
                        }
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            for (Thread thread : threads) thread.join(20_000);

            assertEquals(32, oks.get());
            assertEquals(32, calls.get());
        }
    }

    @Test
    void the_client_reconnects_after_the_server_restarts() throws Exception {
        URI target = start("Echo", GrpcClientTest::echo);
        int port = server.port();
        try (var client =
                GrpcClient.builder(target).deadline(Duration.ofSeconds(5)).build()) {
            assertTrue(client.unary(SERVICE, "Echo", HELLO).isOk());
            server.stop();

            assertEquals(
                    GrpcStatus.UNAVAILABLE, client.unary(SERVICE, "Echo", HELLO).status());

            server = Server.builder()
                    .port(port)
                    .handler(Router.builder()
                            .grpc("/" + SERVICE + "/Echo", GrpcClientTest::echo)
                            .build())
                    .build();
            server.start();
            GrpcResponse again = client.unary(SERVICE, "Echo", HELLO);
            assertTrue(again.isOk(), again.toString());
        }
    }

    @Test
    void a_unary_call_over_tls_negotiates_h2_by_alpn() throws Exception {
        server = Server.builder()
                .port(0)
                .tls(serverContext)
                .handler(Router.builder()
                        .grpc("/" + SERVICE + "/Echo", GrpcClientTest::echo)
                        .build())
                .build();
        server.start();
        URI target = URI.create("https://127.0.0.1:" + server.port());
        try (var client = GrpcClient.builder(target)
                .sslContext(clientContext)
                .deadline(Duration.ofSeconds(5))
                .build()) {
            GrpcResponse response = client.unary(SERVICE, "Echo", HELLO);

            assertTrue(response.isOk(), response.toString());
            assertArrayEquals(HELLO, response.message().orElseThrow());
        }
    }

    @Test
    void a_server_certificate_that_is_not_trusted_is_unavailable() throws Exception {
        server = Server.builder()
                .port(0)
                .tls(serverContext)
                .handler(Router.builder()
                        .grpc("/" + SERVICE + "/Echo", GrpcClientTest::echo)
                        .build())
                .build();
        server.start();
        KeyStore otherKeys = TlsFixtures.selfSigned("other", "CN=other", "ip:127.0.0.1");
        SSLContext trustsOther =
                TlsFixtures.context(null, TlsFixtures.trustStore(TlsFixtures.certificate(otherKeys, "other")));
        try (var client = GrpcClient.builder(URI.create("https://127.0.0.1:" + server.port()))
                .sslContext(trustsOther)
                .deadline(Duration.ofSeconds(10))
                .build()) {
            GrpcResponse response = client.unary(SERVICE, "Echo", HELLO);

            assertEquals(GrpcStatus.UNAVAILABLE, response.status());
            assertTrue(response.cause().orElseThrow() instanceof javax.net.ssl.SSLException, response.toString());
        }
    }
}
