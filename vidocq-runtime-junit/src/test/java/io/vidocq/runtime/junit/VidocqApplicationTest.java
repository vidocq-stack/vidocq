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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Starting, waiting for and stopping an application process, with single-file Java programs in its place: what the
 * runtime does once started is the business of the examples' tests (Vidocq/vidocq#350). Each program binds port
 * {@code 0} and writes the port it got to the file its first argument names, in one move, as the {@code default}
 * Chappe listener does under {@link VidocqTest}. None logs a line: the start does not depend on the logging
 * configuration of the application.
 */
class VidocqApplicationTest {

    private static final String JAVA = ProcessHandle.current().info().command().orElseThrow();

    /** Writes {@code port} to {@code file} as the Chappe extension does: a sibling file, then one atomic move. */
    private static final String WRITE_PORT = """
            void writePort(String file, int port) throws Exception {
                var target = java.nio.file.Path.of(file);
                var tmp = java.nio.file.Files.writeString(target.resolveSibling(target.getFileName() + ".tmp"),
                        port + "\\n");
                java.nio.file.Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
            """;

    /**
     * Listens on a port the system picks, and writes it a moment later: the start polls the file, empty until then.
     * Prints a line on its output, which the application keeps.
     */
    private static final String LISTENER = WRITE_PORT + """
            void main(String[] args) throws Exception {
                IO.println("starting the fake application");
                try (var server = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
                    Thread.sleep(300);
                    writePort(args[0], server.getLocalPort());
                    while (true) {
                        server.accept().close();
                    }
                }
            }
            """;

    /** Listens, but never writes its port: an application whose {@code default} listener does not start. */
    private static final String SILENT = """
            void main(String[] args) throws Exception {
                IO.println("started, quietly");
                try (var server = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
                    while (true) {
                        server.accept().close();
                    }
                }
            }
            """;

    /** Answers {@code GET /hello} with {@code hello} and echoes a {@code POST /echo}, as a REST resource would. */
    private static final String HTTP = WRITE_PORT + """
            void main(String[] args) throws Exception {
                var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(
                        java.net.InetAddress.getLoopbackAddress(), 0), 0);
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
                writePort(args[0], server.getAddress().getPort());
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

    /**
     * The port comes from the file the {@code default} listener writes once it listens: the application bound it, so
     * nothing else could take it first (BUG-20261114-09), and no logging configuration can hide it.
     */
    @Test
    void startReturnsOnceTheApplicationWritesItsPortAndCloseStopsTheProcessAndDeletesTheFile() throws Exception {
        Recorded command = new Recorded(program("Listener", LISTENER));
        VidocqApplication stopped;
        try (VidocqApplication application = VidocqApplication.start(command, Duration.ofSeconds(60))) {
            stopped = application;
            int port = application.url().getPort();
            assertEquals(port + "\n", Files.readString(command.portFile));
            assertEquals(URI.create("http://127.0.0.1:" + port), application.url());
            assertEquals(URI.create("http://127.0.0.1:" + port + "/hello"), application.url("/hello"));
            assertEquals(URI.create("http://127.0.0.1:" + port + "/hello"), application.url("hello"));
            new Socket("127.0.0.1", port).close();
            assertTrue(application.log().contains("starting the fake application"), application.log());
        }

        assertFalse(stopped.isAlive());
        assertFalse(Files.exists(command.portFile), "the port file outlived the application");
    }

    @Test
    void aProcessThatExitsFailsTheStartWithItsLogAndLeavesNoPortFile() throws IOException {
        Recorded command = new Recorded(program("Failing", FAILING));
        Duration timeout = Duration.ofSeconds(60);

        var e = assertThrows(IllegalStateException.class, () -> VidocqApplication.start(command, timeout));

        assertTrue(e.getMessage().startsWith("The application stopped with exit code 3 before it listened."
                + " Its output:\n"), e.getMessage());
        assertTrue(e.getMessage().contains("boot failed: no such extension"), e.getMessage());
        assertFalse(Files.exists(command.portFile));
    }

    /**
     * An application whose {@code default} listener never starts fails the start, which says what it waited for.
     * The timeout leaves the child JVM, launched from a source file, the time to print its line on a busy machine:
     * with 2 s, a CI runner once stopped it before it had printed anything.
     */
    @Test
    void aProcessThatNeverWritesItsPortFailsTheStartAfterTheTimeoutAndIsStopped() throws IOException {
        Recorded command = new Recorded(program("Silent", SILENT));
        Duration timeout = Duration.ofSeconds(15);

        var e = assertThrows(IllegalStateException.class, () -> VidocqApplication.start(command, timeout));

        assertTrue(e.getMessage().startsWith("The application did not listen within 15 s: the start waits for its"
                + " default Chappe listener. Its output:\n"), e.getMessage());
        assertTrue(e.getMessage().contains("started, quietly"), e.getMessage());
        assertFalse(Files.exists(command.portFile));
    }

    /** A command that cannot be built, from a property without a value for one, starts nothing and leaves no file. */
    @Test
    void aCommandThatCannotBeBuiltLeavesNoPortFile() {
        Recorded command = new Recorded(_ -> {
            throw new IllegalArgumentException("not key=value");
        });
        Duration timeout = Duration.ofSeconds(2);

        var e = assertThrows(IllegalArgumentException.class, () -> VidocqApplication.start(command, timeout));

        assertEquals("not key=value", e.getMessage());
        assertFalse(Files.exists(command.portFile));
    }

    /** The port file lies in the shared temporary directory: only its owner reads and writes it. */
    @Test
    void thePortFileIsCreatedForItsOwnerAlone() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "POSIX permissions");
        AtomicReference<Set<PosixFilePermission>> permissions = new AtomicReference<>();
        Function<Path, List<String>> command = file -> {
            try {
                permissions.set(Files.getPosixFilePermissions(file));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            throw new IllegalStateException("read, no process to start");
        };
        Duration timeout = Duration.ofSeconds(2);

        assertThrows(IllegalStateException.class, () -> VidocqApplication.start(command, timeout));

        assertEquals(PosixFilePermissions.fromString("rw-------"), permissions.get());
    }

    /**
     * A test patched into an application module does not read {@code java.net.http}: the application answers
     * through this jar, which does.
     */
    @Test
    void theApplicationAnswersGetAndPost() throws Exception {
        try (VidocqApplication application = VidocqApplication.start(program("Http", HTTP), Duration.ofSeconds(60))) {
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
        try (VidocqApplication application = VidocqApplication.start(program("Http", HTTP), Duration.ofSeconds(60))) {
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
        VidocqApplication application = VidocqApplication.start(program("Stopped", HTTP), Duration.ofSeconds(60));
        application.close();

        var e = assertThrows(UncheckedIOException.class, () -> application.get("/hello"));

        assertEquals("GET " + application.url() + "/hello failed", e.getMessage());
    }

    /** The command of a single-file program, which takes the port file as its argument. */
    private Function<Path, List<String>> program(String name, String source) throws IOException {
        Path file = Files.writeString(dir.resolve(name + ".java"), source);
        return portFile -> List.of(JAVA, file.toString(), portFile.toString());
    }

    /** A command that records the port file the start hands it. */
    private static final class Recorded implements Function<Path, List<String>> {

        private final Function<Path, List<String>> command;
        private Path portFile;

        Recorded(Function<Path, List<String>> command) {
            this.command = command;
        }

        @Override
        public List<String> apply(Path file) {
            portFile = file;
            return command.apply(file);
        }
    }
}
