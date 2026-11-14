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
package io.vidocq.runtime.it.foywebsocket;

import io.vidocq.runtime.core.VidocqBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The assembled runtime with the Foy WebSocket extension, on the module path: a {@code ServerContainer}
 * in the servlet context, an endpoint added by a listener, and the origin policy on both the Foy
 * endpoint and a native Chappe mount, closed by default and opened by {@code vidocq.websocket.allowed-origins}.
 */
class FoyWebSocketModulePathTest {

    private static final String KEY = "vidocq.websocket.allowed-origins";
    private static final String FOY_PATH = "/ws/a/b/c/d/e";
    private static final String NATIVE_PATH = "/native";
    private static final String EVIL = "http://evil.example";
    private static final List<String> KEYS = List.of("vidocq.chappe.listener.default.host",
            "vidocq.chappe.listener.default.port", "vidocq.http.mount.echows.type",
            "vidocq.http.mount.echows.path", "vidocq.http.mount.echows.handler", KEY);

    private VidocqBootstrap bootstrap;
    private int port;

    @AfterEach
    void shutdown() {
        if (bootstrap != null) bootstrap.shutdown();
        KEYS.forEach(System::clearProperty);
    }

    @Test
    void theWebSocketModulesAreInTheBootLayer() {
        assertTrue(FoyWebSocketModulePathTest.class.getModule().isNamed());
        assertTrue(ModuleLayer.boot().findModule("jakarta.websocket").isPresent());
        assertTrue(ModuleLayer.boot().findModule("io.vidocq.foy.websocket").isPresent());
    }

    @Test
    void anEndpointAddedFromAListenerEchoes() throws Exception {
        boot(null);

        assertEquals("echo:hi", echo(FOY_PATH, "hi"));
        assertEquals("native:hi", echo(NATIVE_PATH, "hi"));
    }

    @Test
    void anotherOriginIsRefusedUntilConfigured() throws Exception {
        // Default configurator: 403 proves Foy's DefaultConfigurator was found by ServiceLoader, since
        // without it the origin check would be skipped and the handshake would answer 101.
        boot(null);
        assertEquals(403, handshakeStatus(FOY_PATH, EVIL));
        assertEquals(403, handshakeStatus(NATIVE_PATH, EVIL));
        assertEquals(101, handshakeStatus(FOY_PATH, null));
        assertEquals(101, handshakeStatus(NATIVE_PATH, null));
        bootstrap.shutdown();

        // The key reaches Foy through the ServletInitParameters ServiceLoader and Chappe directly.
        boot(EVIL);
        assertEquals(101, handshakeStatus(FOY_PATH, EVIL));
        assertEquals(101, handshakeStatus(NATIVE_PATH, EVIL));
    }

    @Test
    void aMalformedAllowedOriginStopsTheStart() {
        Throwable failure = assertThrows(Throwable.class, () -> boot("ftp://x"));

        StringBuilder chain = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            chain.append(t.getMessage()).append('\n');
        }
        assertTrue(chain.toString().contains(KEY), chain.toString());
    }

    private void boot(String allowedOrigins) throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        System.setProperty("vidocq.chappe.listener.default.host", "127.0.0.1");
        System.setProperty("vidocq.chappe.listener.default.port", Integer.toString(port));
        System.setProperty("vidocq.http.mount.echows.type", "websocket");
        System.setProperty("vidocq.http.mount.echows.path", NATIVE_PATH);
        System.setProperty("vidocq.http.mount.echows.handler", NativeEchoHandler.class.getName());
        if (allowedOrigins != null) System.setProperty(KEY, allowedOrigins);
        bootstrap = VidocqBootstrap.create().configure().start();
    }

    private String echo(String path, String message) throws Exception {
        CompletableFuture<String> received = new CompletableFuture<>();
        WebSocket client = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:" + port + path), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket ws) {
                        ws.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        received.complete(data.toString());
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);
        client.sendText(message, true).get(5, TimeUnit.SECONDS);
        String answer = received.get(5, TimeUnit.SECONDS);
        client.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        return answer;
    }

    /** A hand-written HTTP/1.1 upgrade request; the status code of the first response line. */
    private int handshakeStatus(String path, String origin) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            String key = Base64.getEncoder().encodeToString(new byte[16]);
            StringBuilder request = new StringBuilder("GET " + path + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n");
            if (origin != null) request.append("Origin: ").append(origin).append("\r\n");
            request.append("\r\n");
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            return Integer.parseInt(in.readLine().split(" ")[1]);
        }
    }
}
