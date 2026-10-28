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
package io.vidocq.cervantes.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal HTTP/1.1 server for tests, on {@code java.base} only (the core module does not read
 * {@code jdk.httpserver}): answers every request with a fixed status and body, then closes the
 * connection. Each connection is served on a virtual thread.
 */
final class TestHttpServer implements AutoCloseable {

    private final ServerSocket socket;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status;
    private volatile byte[] body;

    TestHttpServer(int status, String body) throws IOException {
        this.status = status;
        this.body = body.getBytes(StandardCharsets.UTF_8);
        this.socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(this::acceptLoop);
    }

    String url(String path) {
        return "http://127.0.0.1:" + socket.getLocalPort() + path;
    }

    int requests() {
        return requests.get();
    }

    void respond(int newStatus, String newBody) {
        this.status = newStatus;
        this.body = newBody.getBytes(StandardCharsets.UTF_8);
    }

    private void acceptLoop() {
        while (!socket.isClosed()) {
            try {
                Socket connection = socket.accept();
                Thread.ofVirtual().start(() -> serve(connection));
            } catch (IOException _) {
                return; // closed
            }
        }
    }

    private void serve(Socket connection) {
        try (connection; InputStream in = connection.getInputStream(); OutputStream out = connection.getOutputStream()) {
            readHeaders(in);
            requests.incrementAndGet();
            byte[] payload = body;
            String head = "HTTP/1.1 " + status + " Test\r\nContent-Type: application/json\r\nContent-Length: "
                    + payload.length + "\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(payload);
            out.flush();
        } catch (IOException _) {
            // the client went away: nothing to do in a test server
        }
    }

    private static void readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream seen = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            seen.write(b);
            byte[] s = seen.toByteArray();
            int n = s.length;
            if (n >= 4 && s[n - 4] == '\r' && s[n - 3] == '\n' && s[n - 2] == '\r' && s[n - 1] == '\n') {
                return;
            }
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
