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

package io.vidocq.bodoni.core.testing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A minimal HTTP/1.0 server double on {@code 127.0.0.1} (ephemeral port): every connection
 * runs on its own virtual thread, reads one request, records its path, answers the route
 * registered for that path (else {@code 404}) with {@code Connection: close}, and closes.
 */
public final class TestHttpServer implements AutoCloseable {

    private record Route(int status, Map<String, String> headers, String body) {
    }

    private static final Route NOT_FOUND = new Route(404, Map.of(), "");

    private final ServerSocket socket;
    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private final Set<String> dropped = ConcurrentHashMap.newKeySet();
    private final List<String> requestedPaths = new CopyOnWriteArrayList<>();

    private TestHttpServer(ServerSocket socket) {
        this.socket = socket;
    }

    /** Binds {@code 127.0.0.1} on an ephemeral port and starts accepting connections. */
    public static TestHttpServer start() {
        try {
            TestHttpServer server = new TestHttpServer(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
            Thread.ofVirtual().name("test-http-accept").start(server::accept);
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Answers {@code GET path} with {@code status}, the extra {@code headers} and {@code body} (UTF-8). */
    public TestHttpServer route(String path, int status, Map<String, String> headers, String body) {
        routes.put(path, new Route(status, Map.copyOf(headers), body));
        return this;
    }

    /** Closes the connection of {@code GET path} once the request is read, without any response. */
    public TestHttpServer drop(String path) {
        dropped.add(path);
        return this;
    }

    /** The bound port. */
    public int port() {
        return socket.getLocalPort();
    }

    /** The path of every request received so far, in arrival order. */
    public List<String> requestedPaths() {
        return requestedPaths;
    }

    /** Stops accepting connections. */
    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void accept() {
        while (!socket.isClosed()) {
            try {
                Socket connection = socket.accept();
                Thread.ofVirtual().start(() -> serve(connection));
            } catch (IOException _) {
                // Closed by close(): the accept loop ends.
            }
        }
    }

    private void serve(Socket connection) {
        try (connection) {
            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = in.readLine();
            String line = in.readLine();
            while (line != null && !line.isEmpty()) {
                line = in.readLine();
            }
            if (requestLine == null) {
                return;
            }
            String[] parts = requestLine.split(" ");
            String path = parts.length > 1 ? parts[1] : "";
            requestedPaths.add(path);
            if (dropped.contains(path)) {
                return;
            }
            respond(connection.getOutputStream(), routes.getOrDefault(path, NOT_FOUND));
        } catch (IOException _) {
            // The client went away: nothing to answer.
        }
    }

    private static void respond(OutputStream out, Route route) throws IOException {
        byte[] body = route.body().getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder("HTTP/1.0 ").append(route.status()).append(" Test\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append("Connection: close\r\n");
        route.headers().forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }
}
