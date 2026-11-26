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

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Router;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.runtime.extensions.essentials.chappe.spi.ChainableHandler;
import io.vidocq.runtime.extensions.essentials.chappe.spi.MountOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Several mounts on one prefix of a listener (vidocq#802): they are asked in their order, and a handler
 * that has nothing for a request passes it to the next one. The last one answers what none before it served.
 */
class SharedPrefixTest {

    private static final String LISTENER = ChappeListener.DEFAULT;

    private ChappeMountPoint mp;

    @BeforeEach
    void setUp() {
        mp = new ChappeMountPoint();
        ChappeMountPoint.install(mp);
    }

    @AfterEach
    void tearDown() {
        ChappeMountPoint.uninstall();
    }

    /** Serves the paths that start with `prefix`, answering `name` and the path it saw. */
    private static ChainableHandler serving(String name, String prefix) {
        Handler h = r -> Response.ok(name + " " + r.path());
        return ChainableHandler.of(h, r -> r.path().startsWith(prefix));
    }

    /** A handler that cannot pass a request on: it answers every path. */
    private static Handler plain(String name) {
        return r -> Response.of(StatusCode.NOT_FOUND, Body.of(name + " 404 " + r.path()));
    }

    @Test
    void eachRequestGoesToTheFirstHandlerThatServesIt() throws Exception {
        mp.mountShared(LISTENER, "/", serving("static", "/index"), true, MountOrder.STATIC, "ui");
        mp.mountShared(LISTENER, "/", serving("rest", "/items"), true, MountOrder.RESTFUL, "rest");
        mp.mount(LISTENER, "/", plain("servlet"), false, 0, "servlet", MountOrder.SERVLET);
        Router router = mp.buildRouter(LISTENER);

        assertEquals("static /index.html", body(router, "/index.html"));
        assertEquals("rest /items/42", body(router, "/items/42"));
        assertEquals("servlet 404 /nothing", body(router, "/nothing"));
        assertEquals(404, router.handle(get("/nothing")).status().code());
    }

    @Test
    void theOrderDecidesWhateverTheMountOrder() throws Exception {
        mp.mountShared(LISTENER, "/", serving("second", "/a"), true, 20, "second");
        mp.mountShared(LISTENER, "/", serving("first", "/a"), true, 10, "first");

        assertEquals("first /a", body(mp.buildRouter(LISTENER), "/a"));
    }

    @Test
    void eachHandlerSeesThePathItsOwnPrefixStrippingGivesIt() throws Exception {
        mp.mountShared(LISTENER, "/app", serving("stripped", "/x"), true, 10, "stripped");
        mp.mountShared(LISTENER, "/app", serving("full", "/app/y"), false, 20, "full");
        Router router = mp.buildRouter(LISTENER);

        assertEquals("stripped /x", body(router, "/app/x"));
        assertEquals("full /app/y", body(router, "/app/y"));
    }

    @Test
    void aLongerPrefixStillWinsOverASharedRoot() throws Exception {
        mp.mountShared(LISTENER, "/", serving("static", "/"), true, MountOrder.STATIC, "ui");
        mp.mount(LISTENER, "/", plain("servlet"), false, 0, "servlet", MountOrder.SERVLET);
        mp.mount(LISTENER, "/health", r -> Response.ok("health"));

        assertEquals("health", body(mp.buildRouter(LISTENER), "/health"));
    }

    @Test
    void twoMountsOfAPrefixWithTheSameOrderFailAndNameTheOrderKeys() {
        mp.mountShared(LISTENER, "/", serving("a", "/"), true, 10, "vidocq.http.mount.a");
        ChainableHandler b = serving("b", "/");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> mp.mountShared(LISTENER, "/", b, true, 10, "vidocq.http.mount.b"));
        assertTrue(e.getMessage().contains("by vidocq.http.mount.a and by vidocq.http.mount.b"), e.getMessage());
        assertTrue(e.getMessage().contains("vidocq.http.mount.<n>.order"), e.getMessage());
    }

    @Test
    void aHandlerThatCannotPassARequestOnMustBeLast() {
        mp.mountShared(LISTENER, "/", serving("rest", "/items"), true, 200, "rest");

        Handler legacy = plain("legacy");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> mp.mount(LISTENER, "/", legacy, true, 0, "legacy", 100));
        assertTrue(e.getMessage().contains("legacy"), e.getMessage());
        assertTrue(e.getMessage().contains("before rest"), e.getMessage());
    }

    @Test
    void aPlainHandlerAfterChainableOnesIsTheLast() throws Exception {
        mp.mountShared(LISTENER, "/", serving("rest", "/items"), true, MountOrder.RESTFUL, "rest");
        mp.mount(LISTENER, "/", plain("app"));

        assertEquals("app 404 /x", body(mp.buildRouter(LISTENER), "/x"));
    }

    @Test
    void theChainsOfTheSharedPrefixesAreListedInOrder() {
        mp.mount(LISTENER, "/", plain("servlet"), false, 0, "vidocq.servlet", MountOrder.SERVLET);
        mp.mountShared(LISTENER, "/", serving("static", "/"), true, MountOrder.STATIC, "vidocq.http.mount.ui");
        mp.mount(LISTENER, "/api", r -> Response.ok("api"));
        mp.buildRouter(LISTENER);

        assertEquals(Map.of("/", List.of("vidocq.http.mount.ui", "vidocq.servlet")), mp.sharedPrefixes(LISTENER));
    }

    private static String body(Handler router, String path) throws Exception {
        Response r = router.handle(get(path));
        try {
            return new String(r.body().asInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Request get(String path) {
        return new Request() {
            @Override public HttpMethod method() { return HttpMethod.GET; }
            @Override public URI uri() { return URI.create(path); }
            @Override public String path() { return path; }
            @Override public String query() { return null; }
            @Override public HttpVersion version() { return HttpVersion.HTTP_1_1; }
            @Override public Headers headers() { return Headers.empty(); }
            @Override public Body body() { return Body.empty(); }
            @Override public Map<String, String> pathParams() { return Map.of(); }
            @Override public Map<String, String> queryParams() { return Map.of(); }
        };
    }
}
