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

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The servlets under a context path other than the root, mounted by the extension or by a
 * declarative mount: Foy dispatches on the full request path, context path included.
 */
class FoyServletContextPathTest {

    private static final List<String> KEYS = List.of("vidocq.chappe.listener.default.host",
            "vidocq.chappe.listener.default.port", "vidocq.servlet.context-path",
            "vidocq.http.mount.web.type", "vidocq.http.mount.web.path");

    private VidocqBootstrap bootstrap;
    private String base;

    @AfterEach
    void shutdown() {
        if (bootstrap != null) bootstrap.shutdown();
        KEYS.forEach(System::clearProperty);
    }

    @Test
    void theAutoMountServesItsContextPath() throws Exception {
        boot("vidocq.servlet.context-path", "/shop");

        HttpResponse<String> r = get("/shop/cart");

        assertEquals(200, r.statusCode());
        assertEquals("foy", r.headers().firstValue("X-Powered-By").orElse(null));
        assertEquals("rest", get("/api/items").body());
    }

    @Test
    void aDeclarativeServletMountServesItsPath() throws Exception {
        System.setProperty("vidocq.http.mount.web.type", "servlet");
        boot("vidocq.http.mount.web.path", "/web");

        assertEquals(200, get("/web/cart").statusCode());
    }

    private void boot(String key, String value) throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        System.setProperty("vidocq.chappe.listener.default.host", "127.0.0.1");
        System.setProperty("vidocq.chappe.listener.default.port", Integer.toString(port));
        System.setProperty(key, value);
        base = "http://127.0.0.1:" + port;
        bootstrap = VidocqBootstrap.create().configure().start();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
