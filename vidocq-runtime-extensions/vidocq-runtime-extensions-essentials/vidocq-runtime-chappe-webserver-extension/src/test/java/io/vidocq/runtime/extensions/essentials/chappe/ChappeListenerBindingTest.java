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
import io.vidocq.chappe.api.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real servers started by {@link ChappeServerBootstrap} for the listeners of the configuration and those an
 * extension declared: the address each one bound, logged and handed back, and the fallback of a taken port.
 */
class ChappeListenerBindingTest {

    private static final String BOOTSTRAP_LOGGER = ChappeServerBootstrap.class.getName();

    private ChappeServerBootstrap bootstrap;
    private LogRecords log;

    @BeforeEach
    void setUp() {
        new ChappeEngineExtension().configure(null);
        bootstrap = new ChappeServerBootstrap();
        log = new LogRecords(BOOTSTRAP_LOGGER);
    }

    @AfterEach
    void tearDown() {
        log.close();
        bootstrap.onStop();
        ChappeMountPoint.uninstall();
    }

    /** The configuration of a test: the application's listener on a free loopback port, never 8080. */
    private static FakeExtensionContext context(String... extra) {
        Map<String, String> config = new HashMap<>(Map.of(
                "vidocq.chappe.listener.default.host", "127.0.0.1",
                "vidocq.chappe.listener.default.port", "0"));
        for (int i = 0; i < extra.length; i += 2) {
            config.put(extra[i], extra[i + 1]);
        }
        return new FakeExtensionContext(TestConfig.of(config));
    }

    @Test
    void portZeroLogsThePortItBound() throws Exception {
        ChappeMountPoint.instance().router(ChappeListener.DEFAULT).get("/hello", _ -> Response.ok("hi"));

        bootstrap.onStart(context());

        int port = bootstrap.boundAddresses().get(ChappeListener.DEFAULT).getPort();
        assertNotEquals(0, port);
        assertEquals(List.of("Chappe listener 'default' started on http://127.0.0.1:" + port + "/"),
                log.messages(Level.INFO));
        assertEquals("hi", get("http://127.0.0.1:" + port + "/hello").body());
    }

    @Test
    void theBoundAddressOutlivesTheServer() {
        bootstrap.onStart(context());
        InetSocketAddress bound = bootstrap.boundAddresses().get(ChappeListener.DEFAULT);

        Server server = bootstrap.servers().get(0);
        server.stop();

        assertEquals(0, server.port(), "Chappe answers the configured port once stopped");
        assertEquals(bound, bootstrap.boundAddresses().get(ChappeListener.DEFAULT), "read once, after start");
    }

    @Test
    void extensionListenerStartsServesAndIsHandedItsAddress() throws Exception {
        AtomicReference<InetSocketAddress> handed = new AtomicReference<>();
        AtomicReference<Thread> handedOn = new AtomicReference<>();
        ChappeMountPoint mp = ChappeMountPoint.instance();
        mp.declareListener(ChappeListener.http("dev", "127.0.0.1", 0), new ListenerOptions(false, false, null,
                address -> {
                    handed.set(address);
                    handedOn.set(Thread.currentThread());
                }));
        mp.router("dev").get("/api/ping", _ -> Response.ok("dev"));

        bootstrap.onStart(context());

        InetSocketAddress address = handed.get();
        assertNotNull(address, "onBound is called once the server listens");
        assertNotEquals(0, address.getPort());
        assertEquals(bootstrap.boundAddresses().get("dev"), address);
        assertSame(Thread.currentThread(), handedOn.get(), "on the boot thread");
        assertEquals("dev", get("http://127.0.0.1:" + address.getPort() + "/api/ping").body());
        assertEquals(List.of("dev", "default"), List.copyOf(bootstrap.boundAddresses().keySet()),
                "every declared listener starts, in declaration order");
    }

    @Test
    void quietListenerLogsItsLineAtDebug() {
        ChappeMountPoint.instance().declareListener(ChappeListener.http("dev", "127.0.0.1", 0),
                new ListenerOptions(false, true, null, null));

        bootstrap.onStart(context());

        int devPort = bootstrap.boundAddresses().get("dev").getPort();
        assertTrue(log.messages(Level.INFO).stream().noneMatch(m -> m.contains("'dev'")), log.messages().toString());
        assertEquals(List.of("Chappe listener 'dev' started on http://127.0.0.1:" + devPort + "/"),
                log.messages(Level.FINE));
    }

    @Test
    void takenPortFallsBackToAFreeOne() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            int takenPort = taken.getLocalPort();
            AtomicReference<InetSocketAddress> handed = new AtomicReference<>();
            ChappeMountPoint mp = ChappeMountPoint.instance();
            mp.declareListener(ChappeListener.http("dev", "127.0.0.1", takenPort),
                    new ListenerOptions(true, true, null, handed::set));
            mp.router("dev").get("/api/ping", _ -> Response.ok("dev"));

            bootstrap.onStart(context());

            int bound = handed.get().getPort();
            assertNotEquals(takenPort, bound);
            assertNotEquals(0, bound);
            assertEquals(List.of("Chappe listener 'dev': port " + takenPort + " is taken, listening on port "
                    + bound + " instead"), log.messages(Level.WARNING));
            assertEquals("dev", get("http://127.0.0.1:" + bound + "/api/ping").body());
        }
    }

    @Test
    void takenPortFailsTheBootWithoutTheFallback() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            ChappeMountPoint.instance().declareListener(ChappeListener.http("dev", "127.0.0.1", taken.getLocalPort()),
                    ListenerOptions.DEFAULTS);

            FakeExtensionContext context = context();
            assertThrows(RuntimeException.class, () -> bootstrap.onStart(context));
        }
    }

    @Test
    void configurationListingAnExtensionListenerFailsTheBoot() {
        ChappeMountPoint.instance().declareListener(ChappeListener.http("dev", "127.0.0.1", 0),
                ListenerOptions.DEFAULTS);

        FakeExtensionContext context = context(
                "vidocq.chappe.listeners", "default,dev",
                "vidocq.chappe.listener.dev.port", "0");
        var ex = assertThrows(IllegalStateException.class, () -> bootstrap.onStart(context));
        assertEquals("listener 'dev' is declared by an extension (ChappeListenerBindingTest); "
                + "remove it from vidocq.chappe.listeners", ex.getMessage());
        assertTrue(bootstrap.servers().isEmpty(), "nothing started");
    }

    @Test
    void failingOnBoundIsLoggedAndTheOtherListenersStillStart() {
        List<String> calls = new ArrayList<>();
        ChappeMountPoint mp = ChappeMountPoint.instance();
        mp.declareListener(ChappeListener.http("dev", "127.0.0.1", 0), new ListenerOptions(false, false, null,
                _ -> {
                    calls.add("dev");
                    throw new IllegalStateException("boom");
                }));
        mp.declareListener(ChappeListener.http("mount-attempt", "127.0.0.1", 0), new ListenerOptions(false, false, null,
                _ -> {
                    calls.add("mount-attempt");
                    ChappeMountPoint.instance().mount("dev", "/late", _ -> Response.ok("too late"));
                }));

        bootstrap.onStart(context());

        assertEquals(List.of("dev", "mount-attempt"), calls);
        assertEquals(3, bootstrap.servers().size());
        List<String> warnings = log.messages(Level.WARNING);
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("'dev'"), warnings.get(0));
        assertTrue(warnings.get(1).contains("'mount-attempt'"), warnings.get(1));
    }

    @Test
    void gracePeriodReachesTheServer() {
        ChappeMountPoint.instance().declareListener(ChappeListener.http("dev", "127.0.0.1", 0),
                new ListenerOptions(false, true, Duration.ofSeconds(1), null));

        bootstrap.onStart(context());

        List<Server> servers = bootstrap.servers();
        assertEquals(Duration.ofSeconds(1), servers.get(0).config().shutdownGracePeriod(), "dev");
        assertEquals(Duration.ofSeconds(30), servers.get(1).config().shutdownGracePeriod(),
                "a configured listener keeps Chappe's default");
    }

    private static HttpResponse<String> get(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
