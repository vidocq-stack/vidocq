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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import io.vidocq.chappe.api.GrpcStatus;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The client over TLS: ALPN {@code h2}, the server's certificate checked, and a server that does not offer h2. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class GrpcClientTlsTest {

    private static final char[] PASSWORD = "changeit".toCharArray();
    private static final byte[] PONG = "pong".getBytes(StandardCharsets.UTF_8);

    private static SSLContext serverContext;
    private static SSLContext clientContext;

    /** A self-signed certificate for 127.0.0.1 made by the JDK's keytool: the JDK has no API to issue one. */
    @BeforeAll
    static void certificates() throws Exception {
        Path file = Files.createTempFile("chappe-grpc-client-", ".p12");
        Files.delete(file);
        try {
            Process keytool = new ProcessBuilder(
                            Path.of(System.getProperty("java.home"), "bin", "keytool")
                                    .toString(),
                            "-genkeypair",
                            "-alias",
                            "server",
                            "-keyalg",
                            "RSA",
                            "-keysize",
                            "2048",
                            "-validity",
                            "1",
                            "-dname",
                            "CN=localhost",
                            "-ext",
                            "san=ip:127.0.0.1",
                            "-storetype",
                            "PKCS12",
                            "-keystore",
                            file.toString(),
                            "-storepass",
                            "changeit",
                            "-keypass",
                            "changeit")
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                    .redirectErrorStream(true)
                    .start();
            keytool.getInputStream().readAllBytes();
            assertTrue(keytool.waitFor(60, TimeUnit.SECONDS) && keytool.exitValue() == 0, "keytool failed");
            var keys = KeyStore.getInstance("PKCS12");
            try (var in = Files.newInputStream(file)) {
                keys.load(in, PASSWORD);
            }
            var trust = KeyStore.getInstance("PKCS12");
            trust.load(null, null);
            trust.setCertificateEntry("server", keys.getCertificate("server"));

            var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keys, PASSWORD);
            var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);
            serverContext = SSLContext.getInstance("TLS");
            serverContext.init(kmf.getKeyManagers(), null, null);
            clientContext = SSLContext.getInstance("TLS");
            clientContext.init(null, tmf.getTrustManagers(), null);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static GrpcClient client(FakeH2Server server) {
        return GrpcClient.builder(server.uri())
                .sslContext(clientContext)
                .deadline(Duration.ofSeconds(10))
                .build();
    }

    @Test
    void a_call_over_tls_negotiates_h2_and_sends_the_https_scheme() throws Exception {
        var scheme = new CompletableFuture<String>();
        try (var server = FakeH2Server.tls(
                        conn -> {
                            var request = conn.request();
                            scheme.complete(request.fields().get(":scheme"));
                            conn.ok(request.streamId(), PONG);
                            conn.drain();
                        },
                        serverContext,
                        "h2");
                var client = client(server)) {
            GrpcResponse response = client.unary("s.S", "M", new byte[] {1});

            assertTrue(response.isOk(), response.toString());
            assertArrayEquals(PONG, response.message().orElseThrow());
            assertEquals("https", scheme.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void a_server_without_alpn_is_unavailable() throws Exception {
        try (var server = FakeH2Server.tls(FakeH2Server.Conn::drain, serverContext);
                var client = client(server)) {
            GrpcResponse response = client.unary("s.S", "M", new byte[] {1});

            assertEquals(GrpcStatus.UNAVAILABLE, response.status());
            assertTrue(response.statusMessage().contains("ALPN"), response.statusMessage());
        }
    }

    @Test
    void a_certificate_the_client_does_not_trust_is_unavailable() throws Exception {
        try (var server = FakeH2Server.tls(FakeH2Server.Conn::drain, serverContext, "h2");
                var client = GrpcClient.builder(server.uri())
                        .deadline(Duration.ofSeconds(10))
                        .build()) {
            GrpcResponse response = client.unary("s.S", "M", new byte[] {1});

            assertEquals(GrpcStatus.UNAVAILABLE, response.status());
            assertTrue(response.cause().isPresent());
        }
    }

    @Test
    void a_host_name_target_is_reached_with_sni_and_checked_against_the_certificate() throws Exception {
        // The certificate names CN=localhost and no DNS subjectAltName: the JDK checks the host name against the CN.
        try (var server = FakeH2Server.tls(
                        conn -> {
                            conn.ok(conn.request().streamId(), PONG);
                            conn.drain();
                        },
                        serverContext,
                        "h2");
                var client = GrpcClient.builder(
                                URI.create("https://localhost:" + server.uri().getPort()))
                        .sslContext(clientContext)
                        .deadline(Duration.ofSeconds(10))
                        .build()) {
            GrpcResponse response = client.unary("s.S", "M", new byte[] {1});

            assertTrue(response.isOk(), response.toString());
        }
    }
}
