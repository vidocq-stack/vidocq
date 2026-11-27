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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

import io.vidocq.chappe.api.GrpcStatus;
import io.vidocq.chappe.grpc.client.GrpcClient;
import io.vidocq.chappe.grpc.client.GrpcRequest;
import io.vidocq.chappe.grpc.client.GrpcResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Chappe's gRPC client against an independent gRPC server: grpcio, the Python binding of the gRPC C core, serving
 * raw bytes ({@code grpc/interop_server.py}), in cleartext HTTP/2 and over TLS. Skipped unless {@code python3} can
 * import {@code grpc}; {@code -Dchappe.grpc.python.path=<dir>} adds a directory holding it, for instance one filled
 * by {@code pip install --target <dir> grpcio}. Nothing of it is a dependency of the build.
 */
// A hung call fails its test instead of the build: every call here should end within seconds.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class GrpcPythonInteropTest {

    private static final String SERVICE = "interop.Echo";
    private static final String PYTHON_PATH = System.getProperty("chappe.grpc.python.path", "");

    private static Process server;
    private static Path dir;
    private static int cleartextPort;
    private static int tlsPort;
    private static SSLContext clientContext;

    @BeforeAll
    static void startServer() throws Exception {
        assumeTrue(grpcioAvailable(), "python3 with grpcio missing (skip: pip install grpcio)");
        dir = Files.createTempDirectory("chappe-grpc-interop-");
        KeyStore keys = TlsFixtures.selfSigned("server", "CN=localhost", "ip:127.0.0.1");
        var certificate = TlsFixtures.certificate(keys, "server");
        var key = keys.getKey("server", TlsFixtures.PASSWORD);
        Path cert = dir.resolve("cert.pem");
        Path keyFile = dir.resolve("key.pem");
        Files.writeString(cert, pem("CERTIFICATE", certificate.getEncoded()));
        Files.writeString(keyFile, pem("PRIVATE KEY", key.getEncoded()));
        clientContext = TlsFixtures.context(null, TlsFixtures.trustStore(certificate));

        Path script = dir.resolve("interop_server.py");
        try (InputStream in = GrpcPythonInteropTest.class.getResourceAsStream("/grpc/interop_server.py")) {
            Files.write(script, in.readAllBytes());
        }
        server = new ProcessBuilder(
                        "python3", "-I", script.toString(), cert.toString(), keyFile.toString(), PYTHON_PATH)
                .redirectErrorStream(true)
                .start();
        var reader = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
        String ready = CompletableFuture.supplyAsync(() -> {
                    try {
                        return reader.readLine();
                    } catch (java.io.IOException e) {
                        return "failed: " + e;
                    }
                })
                .get(30, TimeUnit.SECONDS);
        assertTrue(ready != null && ready.startsWith("ready "), "the interop server did not start: " + ready);
        String[] ports = ready.split(" ", -1);
        cleartextPort = Integer.parseInt(ports[1]);
        tlsPort = Integer.parseInt(ports[2]);
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) {
            server.getOutputStream().close();
            if (!server.waitFor(5, TimeUnit.SECONDS)) server.destroyForcibly();
        }
        if (dir != null) {
            try (var files = Files.list(dir)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(dir);
        }
    }

    private static boolean grpcioAvailable() {
        try {
            String probe = "import sys\nif sys.argv[1]: sys.path.insert(0, sys.argv[1])\nimport grpc";
            Process process = new ProcessBuilder("python3", "-I", "-c", probe, PYTHON_PATH)
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception _) {
            return false;
        }
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    private static GrpcClient client(boolean tls) {
        var builder = GrpcClient.builder(
                        URI.create(tls ? "https://127.0.0.1:" + tlsPort : "http://127.0.0.1:" + cleartextPort))
                .deadline(Duration.ofSeconds(20))
                .maxResponseMessageSize(8 * 1024 * 1024);
        if (tls) builder.sslContext(clientContext);
        return builder.build();
    }

    @ParameterizedTest(name = "tls={0}")
    @ValueSource(booleans = {false, true})
    void a_unary_call_with_metadata_and_a_deadline(boolean tls) throws Exception {
        try (var client = client(tls)) {
            GrpcResponse response = client.unary(GrpcRequest.builder(SERVICE, "Echo")
                    .message("hello".getBytes(StandardCharsets.UTF_8))
                    .metadata("x-tenant", "acme")
                    .build());

            assertTrue(response.isOk(), response.toString());
            assertArrayEquals(
                    "hello".getBytes(StandardCharsets.UTF_8), response.message().orElseThrow());
            assertEquals("grpcio", response.headers().firstOrNull("x-served-by"));
            assertEquals("acme", response.trailers().firstOrNull("x-tenant-seen"));
            assertEquals("yes", response.trailers().firstOrNull("x-deadline-seen"));
        }
    }

    @ParameterizedTest(name = "tls={0}")
    @ValueSource(booleans = {false, true})
    void a_message_of_five_mebibytes_each_way(boolean tls) throws Exception {
        byte[] large = new byte[5 * 1024 * 1024];
        for (int i = 0; i < large.length; i++) large[i] = (byte) (i * 7);
        try (var client = client(tls)) {
            GrpcResponse response = client.unary(SERVICE, "Echo", large);

            assertTrue(response.isOk(), response.toString());
            assertArrayEquals(large, response.message().orElseThrow());
        }
    }

    @ParameterizedTest(name = "tls={0}")
    @ValueSource(booleans = {false, true})
    void the_statuses_of_the_server(boolean tls) throws Exception {
        try (var client = client(tls)) {
            GrpcResponse failed = client.unary(SERVICE, "Fail", new byte[0]);
            assertEquals(GrpcStatus.FAILED_PRECONDITION, failed.status());
            assertEquals("café is closed: 100%", failed.statusMessage());

            assertEquals(
                    GrpcStatus.UNIMPLEMENTED,
                    client.unary(SERVICE, "Missing", new byte[0]).status());

            GrpcResponse slow = client.unary(GrpcRequest.builder(SERVICE, "Slow")
                    .deadline(Duration.ofMillis(300))
                    .build());
            assertEquals(GrpcStatus.DEADLINE_EXCEEDED, slow.status(), slow.toString());

            assertTrue(client.unary(SERVICE, "Echo", new byte[] {1}).isOk(), "the connection outlives the deadline");
        }
    }
}
