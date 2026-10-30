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
package io.vidocq.runtime.junit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starting, waiting for and stopping an application process, with single-file Java programs in its place: what the
 * runtime does once started is the business of the examples' tests (Vidocq/vidocq#350).
 */
class VidocqApplicationTest {

    private static final String JAVA = ProcessHandle.current().info().command().orElseThrow();

    /** Listens on the port of its first argument after printing a line, until it is stopped. */
    private static final String LISTENER = """
            void main(String[] args) throws Exception {
                IO.println("starting the fake application");
                try (var server = new java.net.ServerSocket(Integer.parseInt(args[0]), 50,
                        java.net.InetAddress.getLoopbackAddress())) {
                    while (true) {
                        server.accept().close();
                    }
                }
            }
            """;

    /** Answers {@code GET /hello} with {@code hello} and echoes a {@code POST /echo}, as a REST resource would. */
    private static final String HTTP = """
            void main(String[] args) throws Exception {
                var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(
                        java.net.InetAddress.getLoopbackAddress(), Integer.parseInt(args[0])), 0);
                server.createContext("/hello", exchange -> {
                    byte[] body = "hello".getBytes();
                    exchange.getResponseHeaders().add("Content-Type", "text/plain");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
                server.createContext("/whoami", exchange -> {
                    byte[] body = (exchange.getRequestHeaders().getFirst("Authorization") + " "
                            + exchange.getRequestHeaders().getFirst("X-Tenant")).getBytes();
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
                server.createContext("/echo", exchange -> {
                    byte[] body = (exchange.getRequestMethod() + " "
                            + exchange.getRequestHeaders().getFirst("Content-Type") + " "
                            + new String(exchange.getRequestBody().readAllBytes())).getBytes();
                    exchange.sendResponseHeaders(201, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
                server.start();
            }
            """;

    /** Prints a line and exits with 3, as an application that fails to boot. */
    private static final String FAILING = """
            void main(String[] args) {
                IO.println("boot failed: no such extension");
                System.exit(3);
            }
            """;

    @TempDir
    Path dir;

    @Test
    void startReturnsOnceThePortListensAndCloseStopsTheProcess() throws Exception {
        int port = VidocqApplication.freePort();

        VidocqApplication stopped;
        try (VidocqApplication application = VidocqApplication.start(program("Listener", LISTENER, port), port,
                Duration.ofSeconds(60))) {
            stopped = application;
            assertEquals(URI.create("http://127.0.0.1:" + port), application.url());
            assertEquals(URI.create("http://127.0.0.1:" + port + "/hello"), application.url("/hello"));
            assertEquals(URI.create("http://127.0.0.1:" + port + "/hello"), application.url("hello"));
            new Socket("127.0.0.1", port).close();
            assertTrue(application.log().contains("starting the fake application"), application.log());
        }

        assertFalse(stopped.isAlive());
    }

    @Test
    void aProcessThatExitsFailsTheStartWithItsLog() throws IOException {
        int port = VidocqApplication.freePort();

        List<String> command = program("Failing", FAILING, port);
        Duration timeout = Duration.ofSeconds(60);

        var e = assertThrows(IllegalStateException.class, () -> VidocqApplication.start(command, port, timeout));

        assertTrue(e.getMessage().startsWith("The application stopped with exit code 3 before it listened on"
                + " http://127.0.0.1:" + port + ". Its output:\n"), e.getMessage());
        assertTrue(e.getMessage().contains("boot failed: no such extension"), e.getMessage());
    }

    @Test
    void aProcessThatNeverListensFailsTheStartAfterTheTimeoutAndIsStopped() throws IOException {
        int port = VidocqApplication.freePort();
        int otherPort = VidocqApplication.freePort();

        List<String> command = program("Elsewhere", LISTENER, otherPort);
        Duration timeout = Duration.ofSeconds(2);

        var e = assertThrows(IllegalStateException.class, () -> VidocqApplication.start(command, port, timeout));

        assertTrue(e.getMessage().startsWith("The application did not listen on http://127.0.0.1:" + port
                + " within 2 s. Its output:\n"), e.getMessage());
    }

    /**
     * A test patched into an application module does not read {@code java.net.http}: the application answers
     * through this jar, which does.
     */
    @Test
    void theApplicationAnswersGetAndPost() throws Exception {
        int port = VidocqApplication.freePort();

        try (VidocqApplication application = VidocqApplication.start(program("Http", HTTP, port), port,
                Duration.ofSeconds(60))) {
            VidocqResponse hello = application.get("/hello");
            assertEquals(200, hello.status());
            assertEquals("hello", hello.body());
            assertEquals("text/plain", hello.header("content-type").orElseThrow());

            VidocqResponse echoed = application.post("echo", "application/json", "{\"a\":1}");
            assertEquals(201, echoed.status());
            assertEquals("POST application/json {\"a\":1}", echoed.body());

            assertEquals(404, application.request("DELETE", "/nowhere", null, null).status());
        }
    }

    /**
     * A test sends headers, a bearer token for a {@code @RolesAllowed} endpoint for one: the test module cannot use
     * {@code java.net.http} itself (BUG-20261030-18).
     */
    @Test
    void aRequestCarriesTheHeadersOfTheTest() throws Exception {
        int port = VidocqApplication.freePort();

        try (VidocqApplication application = VidocqApplication.start(program("Http", HTTP, port), port,
                Duration.ofSeconds(60))) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer abc");
            headers.put("X-Tenant", "library");

            assertEquals("Bearer abc library", application.get("/whoami", headers).body());
            assertEquals("POST application/json {}",
                    application.request("POST", "/echo", headers, "application/json", "{}").body());
        }
    }

    /** A request the application cannot answer, as it stopped, names the request. */
    @Test
    void aRequestToAStoppedApplicationFailsWithItsUrl() throws Exception {
        int port = VidocqApplication.freePort();
        VidocqApplication application = VidocqApplication.start(program("Stopped", HTTP, port), port,
                Duration.ofSeconds(60));
        application.close();

        var e = assertThrows(UncheckedIOException.class, () -> application.get("/hello"));

        assertEquals("GET http://127.0.0.1:" + port + "/hello failed", e.getMessage());
    }

    private List<String> program(String name, String source, int port) throws IOException {
        Path file = Files.writeString(dir.resolve(name + ".java"), source);
        return List.of(JAVA, file.toString(), String.valueOf(port));
    }
}
