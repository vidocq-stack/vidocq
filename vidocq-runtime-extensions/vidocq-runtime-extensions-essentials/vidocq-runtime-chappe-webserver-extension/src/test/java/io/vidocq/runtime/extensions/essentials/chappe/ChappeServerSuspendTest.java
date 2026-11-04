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
import io.vidocq.runtime.spi.Suspendable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The listeners are released before a process snapshot and bound again after the restore
 * ({@link Suspendable}): the accept loop of a live listener would otherwise keep its descriptor.
 */
class ChappeServerSuspendTest {

    private ChappeServerBootstrap bootstrap;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        port = freePort();
        new ChappeEngineExtension().configure(null);
        bootstrap = new ChappeServerBootstrap();
        ChappeMountPoint.instance().router(ChappeListener.DEFAULT).get("/hello", _ -> Response.ok("hi"));
        bootstrap.onStart(new FakeExtensionContext(TestConfig.of(Map.of(
                "vidocq.chappe.listener.default.port", Integer.toString(port)))));
    }

    @AfterEach
    void tearDown() {
        bootstrap.onStop();
        ChappeMountPoint.uninstall();
    }

    @Test
    void theBootstrapIsSuspendable() {
        assertInstanceOf(Suspendable.class, bootstrap);
    }

    @Test
    void suspendReleasesThePort_resumeServesAgain() throws Exception {
        Suspendable suspendable = (Suspendable) bootstrap;

        suspendable.suspend();
        try (ServerSocket probe = new ServerSocket(port)) {
            assertEquals(port, probe.getLocalPort(), "the port is free while suspended");
        }
        assertThrows(IOException.class, () -> get());

        suspendable.resume();
        assertEquals("hi", get().body());
    }

    @Test
    void suspendAndResumeAreIdempotent() throws Exception {
        Suspendable suspendable = (Suspendable) bootstrap;

        suspendable.suspend();
        suspendable.suspend();
        suspendable.resume();
        suspendable.resume();

        assertEquals("hi", get().body());
    }

    private HttpResponse<String> get() throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/hello"))
                        .timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
