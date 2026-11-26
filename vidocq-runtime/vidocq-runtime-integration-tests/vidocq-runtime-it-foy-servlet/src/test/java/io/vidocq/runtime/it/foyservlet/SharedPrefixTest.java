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
package io.vidocq.runtime.it.foyservlet;

import io.vidocq.runtime.core.VidocqBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Static files, REST resources and servlets on one prefix (vidocq#802), on the module path: each request
 * goes to the first mount that has something for it, static files first, then REST, then the servlets,
 * and `vidocq.<...>.order` changes that order.
 */
class SharedPrefixTest {

    private static final List<String> KEYS = List.of("vidocq.chappe.listener.default.host",
            "vidocq.chappe.listener.default.port", "vidocq.servlet.context-path", "vidocq.rest.order",
            "vidocq.http.mount.ui.path", "vidocq.http.mount.ui.type", "vidocq.http.mount.ui.classpath");

    private VidocqBootstrap bootstrap;
    private String base;

    @AfterEach
    void shutdown() {
        if (bootstrap != null) bootstrap.shutdown();
        KEYS.forEach(System::clearProperty);
    }

    @Test
    void theFilesTheResourcesAndTheServletsShareTheirPrefix() throws Exception {
        boot(Map.of());

        assertEquals("static hello", get("/api/hello.html").body());
        assertEquals("static items", get("/api/items").body());
        HttpResponse<String> cart = get("/api/cart");
        assertEquals(200, cart.statusCode());
        assertEquals("foy", cart.headers().firstValue("X-Powered-By").orElse(null));
        assertEquals(404, get("/api/nothing").statusCode());
    }

    @Test
    void anOrderSetInTheConfigurationAsksTheRestResourcesFirst() throws Exception {
        boot(Map.of("vidocq.rest.order", "50"));

        assertEquals("rest", get("/api/items").body());
        assertEquals("static hello", get("/api/hello.html").body());
    }

    private void boot(Map<String, String> extra) {
        System.setProperty("vidocq.chappe.listener.default.host", "127.0.0.1");
        System.setProperty("vidocq.chappe.listener.default.port", "0");
        // The REST resources are on /api (@ApplicationPath): the servlets and the files join them there.
        System.setProperty("vidocq.servlet.context-path", "/api");
        System.setProperty("vidocq.http.mount.ui.path", "/api");
        System.setProperty("vidocq.http.mount.ui.type", "static");
        System.setProperty("vidocq.http.mount.ui.classpath", "static");
        extra.forEach(System::setProperty);
        try (BoundPort port = BoundPort.watch()) {
            bootstrap = VidocqBootstrap.create().configure().start();
            base = "http://127.0.0.1:" + port.port();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
