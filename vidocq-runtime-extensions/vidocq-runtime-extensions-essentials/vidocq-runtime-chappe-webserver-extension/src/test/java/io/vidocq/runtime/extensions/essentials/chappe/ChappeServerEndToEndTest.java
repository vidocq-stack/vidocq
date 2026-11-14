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
package io.vidocq.runtime.extensions.essentials.chappe;

import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.WebSocket;
import io.vidocq.chappe.api.WebSocketHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Starts a real Chappe Server via ChappeEngineExtension + ChappeServerBootstrap
 * and verify that it serves a mounted handler, that the hooks turn, and that it stops properly.
 *
 * <p>Every listener binds port {@code 0} and the test reads the port it got from
 * {@link ChappeServerBootstrap#boundAddresses()}. A port picked beforehand, then closed and bound again
 * by the server, could be taken in between by another process of the machine (BUG-20261114-09).
 */
class ChappeServerEndToEndTest {

    /** The default listener on a port the system picks when it binds. */
    private static final Map<String, String> ANY_PORT = Map.of("vidocq.chappe.listener.default.port", "0");

    /** The port of the default listener when nothing configures one. */
    private static final int DEFAULT_LISTENER_PORT = 8080;

    private ChappeEngineExtension engine;
    private ChappeServerBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        this.engine = new ChappeEngineExtension();
        this.bootstrap = new ChappeServerBootstrap();
        engine.configure(null);
    }

    @AfterEach
    void tearDown() {
        bootstrap.onStop();
        ChappeMountPoint.uninstall();
    }

    @Test
    void handlerMountedOnDefaultListenerIsReachable() throws Exception {
        ChappeMountPoint.instance().router(ChappeListener.DEFAULT)
                .get("/hello", _ -> Response.ok("hi"));

        start(ANY_PORT);

        HttpResponse<String> resp = get("http://127.0.0.1:" + port(ChappeListener.DEFAULT) + "/hello");
        assertEquals(200, resp.statusCode());
        assertEquals("hi", resp.body());
    }

    @Test
    void longerPrefixWinsWhateverTheRegistrationOrder() throws Exception {
        ChappeMountPoint mp = ChappeMountPoint.instance();
        mp.mount(ChappeListener.DEFAULT, "", _ -> Response.ok("servlet"));
        mp.mount(ChappeListener.DEFAULT, "/api", _ -> Response.ok("rest"));

        start(ANY_PORT);

        int port = port(ChappeListener.DEFAULT);
        assertEquals("rest", get("http://127.0.0.1:" + port + "/api/items").body());
        assertEquals("servlet", get("http://127.0.0.1:" + port + "/hello").body());
    }

    @Test
    void aPrefixWithATrailingSlashServesItsSubpaths() throws Exception {
        ChappeMountPoint.instance().mount(ChappeListener.DEFAULT, "/api/", _ -> Response.ok("rest"));

        start(ANY_PORT);

        assertEquals("rest", get("http://127.0.0.1:" + port(ChappeListener.DEFAULT) + "/api/items").body());
    }

    @Test
    void beforeAndAfterHooksExecuted() {
        AtomicBoolean before = new AtomicBoolean();
        AtomicBoolean after = new AtomicBoolean();
        ChappeMountPoint.instance().addBeforeStartHook(() -> before.set(true));
        ChappeMountPoint.instance().addAfterStopHook(() -> after.set(true));

        start(ANY_PORT);
        assertTrue(before.get(), "beforeStart hook should have run");

        bootstrap.onStop();
        assertTrue(after.get(), "afterStop hook should have run");
    }

    @Test
    void multiListenerEachServesItsOwnRouter() throws Exception {
        ChappeMountPoint mp = ChappeMountPoint.instance();
        mp.router(ChappeListener.DEFAULT).get("/ping", _ -> Response.ok("public"));
        mp.router("admin").get("/ping", _ -> Response.ok("private"));

        start(Map.of(
                "vidocq.chappe.listeners", "default,admin",
                "vidocq.chappe.listener.default.port", "0",
                "vidocq.chappe.listener.admin.host", "127.0.0.1",
                "vidocq.chappe.listener.admin.port", "0"));

        int port = port(ChappeListener.DEFAULT);
        int adminPort = port("admin");
        assertNotEquals(port, adminPort, "two listeners, two sockets");
        assertEquals("public", get("http://127.0.0.1:" + port + "/ping").body());
        assertEquals("private", get("http://127.0.0.1:" + adminPort + "/ping").body());
    }

    @Test
    void webSocketEndpointEchoesThroughBootstrap() throws Exception {
        // The WS socle: contribute a WebSocketHandler the same way ChappeMountConfigExtension does
        // for a declarative type=websocket mount, then assemble + boot the real server.
        ChappeMountPoint.instance().webSocket(ChappeListener.DEFAULT, "/echo", new WebSocketHandler() {
            @Override
            public void onText(WebSocket ws, String message) throws Exception {
                ws.sendText("echo:" + message);
            }
        });

        start(ANY_PORT);

        CompletableFuture<String> received = new CompletableFuture<>();
        java.net.http.WebSocket client = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .buildAsync(URI.create("ws://127.0.0.1:" + port(ChappeListener.DEFAULT) + "/echo"),
                        new java.net.http.WebSocket.Listener() {
                            @Override
                            public CompletionStage<?> onText(java.net.http.WebSocket ws,
                                                             CharSequence data, boolean last) {
                                received.complete(data.toString());
                                return null;
                            }
                        })
                .get(5, TimeUnit.SECONDS);

        client.sendText("hi", true);
        assertEquals("echo:hi", received.get(5, TimeUnit.SECONDS));
        client.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "bye");
    }

    /**
     * The exact report of Vidocq/chappe#7: a vidocq.properties carrying nothing but
     * {@code vidocq.http.port} used to leave the application listening on 8080, so the setting was
     * inert and the failure invisible whenever the configured value happened to be the default.
     * The socket is the assertion: with the alias at {@code 0}, only a listener that honours it binds
     * a port other than the default's.
     */
    @Test
    void httpPortAliasAloneBindsTheAdvertisedPort() throws Exception {
        ChappeMountPoint.instance().router(ChappeListener.DEFAULT)
                .get("/", _ -> Response.ok("hello from core"));

        start(Map.of("vidocq.http.port", "0"));

        int port = port(ChappeListener.DEFAULT);
        assertNotEquals(DEFAULT_LISTENER_PORT, port, "the alias, not the default port");
        HttpResponse<String> resp = get("http://127.0.0.1:" + port + "/");
        assertEquals(200, resp.statusCode());
        assertEquals("hello from core", resp.body());
    }

    @Test
    void allowedOriginsMustParse() {
        Map<String, String> config = Map.of(
                "vidocq.chappe.listener.default.port", "0",
                "vidocq.websocket.allowed-origins", "ftp://x");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> start(config));

        assertTrue(failure.getMessage().startsWith("vidocq.websocket.allowed-origins"), failure.getMessage());
    }

    @Test
    void nativeRoutesRefuseAnotherOriginByDefault() throws Exception {
        mountEchoSocket();
        start(ANY_PORT);

        assertEquals(403, handshakeStatus("http://evil.example"));
    }

    @Test
    void theRefusalNamesTheKeyWhenItIsAbsent() throws Exception {
        mountEchoSocket();
        try (LogRecords logs = new LogRecords("io.vidocq.chappe")) {
            start(ANY_PORT);

            assertEquals(403, handshakeStatus("http://evil.example"));
            assertRefusalNamesTheKey(logs);
        }
    }

    @Test
    void theRefusalNamesTheKeyWhenItDoesNotListTheOrigin() throws Exception {
        mountEchoSocket();
        try (LogRecords logs = new LogRecords("io.vidocq.chappe")) {
            start(Map.of(
                    "vidocq.chappe.listener.default.port", "0",
                    "vidocq.websocket.allowed-origins", "http://good.example"));

            assertEquals(403, handshakeStatus("http://evil.example"));
            assertRefusalNamesTheKey(logs);
        }
    }

    private static void assertRefusalNamesTheKey(LogRecords logs) {
        var warnings = logs.messages(Level.WARNING);
        assertTrue(warnings.stream().anyMatch(m -> m.contains("not allowed by vidocq.websocket.allowed-origins")),
                warnings.toString());
        assertTrue(warnings.stream().noneMatch(m -> m.contains("Router.Builder.webSocketOrigins")),
                warnings.toString());
    }

    @Test
    void nativeRoutesAcceptAnOriginTheKeyAllows() throws Exception {
        mountEchoSocket();
        start(Map.of(
                "vidocq.chappe.listener.default.port", "0",
                "vidocq.websocket.allowed-origins", "http://evil.example"));

        assertEquals(101, handshakeStatus("http://evil.example"));
    }

    private static void mountEchoSocket() {
        ChappeMountPoint.instance().webSocket(ChappeListener.DEFAULT, "/echo", new WebSocketHandler() { });
    }

    /** A hand-written HTTP/1.1 upgrade request; the status code of the first response line. */
    private int handshakeStatus(String origin) throws Exception {
        int port = port(ChappeListener.DEFAULT);
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            String key = java.util.Base64.getEncoder().encodeToString(new byte[16]);
            String request = "GET /echo HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Origin: " + origin + "\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var in = new java.io.BufferedReader(new java.io.InputStreamReader(
                    socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
            return Integer.parseInt(in.readLine().split(" ")[1]);
        }
    }

    private void start(Map<String, String> config) {
        bootstrap.onStart(new FakeExtensionContext(TestConfig.of(config)));
    }

    /** The port {@code listener} bound, which the system picked as it bound it. */
    private int port(String listener) {
        return bootstrap.boundAddresses().get(listener).getPort();
    }

    private static HttpResponse<String> get(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build();
        return client.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

}
