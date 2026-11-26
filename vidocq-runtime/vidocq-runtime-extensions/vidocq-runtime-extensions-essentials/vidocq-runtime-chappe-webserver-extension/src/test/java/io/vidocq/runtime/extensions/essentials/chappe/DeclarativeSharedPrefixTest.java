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
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Router;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Two declarative static mounts on {@code /} (vidocq#802): `.order` decides which one is asked first,
 * and a file the first one lacks comes from the second.
 */
class DeclarativeSharedPrefixTest {

    @BeforeEach
    void setUp() {
        new ChappeEngineExtension().configure(null);
    }

    @AfterEach
    void tearDown() {
        ChappeMountPoint.uninstall();
    }

    private static Router mount(Map<String, String> extra) {
        Map<String, String> keys = new HashMap<>(Map.of(
                "vidocq.http.mount.a.path", "/",
                "vidocq.http.mount.a.type", "static",
                "vidocq.http.mount.a.classpath", "shared-a",
                "vidocq.http.mount.b.path", "/",
                "vidocq.http.mount.b.type", "static",
                "vidocq.http.mount.b.classpath", "shared-b"));
        keys.putAll(extra);
        new ChappeMountConfigExtension().onStart(new FakeExtensionContext(TestConfig.of(keys)));
        return ChappeMountPoint.instance().buildRouter(ChappeListener.DEFAULT);
    }

    @Test
    void theLowerOrderIsAskedFirstAndTheOtherServesWhatItLacks() throws Exception {
        Router router = mount(Map.of("vidocq.http.mount.a.order", "20", "vidocq.http.mount.b.order", "10"));

        assertEquals("b", body(router.handle(get("/both.txt"))));
        assertEquals("only-a", body(router.handle(get("/only-a.txt"))));
        assertEquals(404, router.handle(get("/none.txt")).status().code());
        assertEquals(Map.of("/", List.of("vidocq.http.mount.b", "vidocq.http.mount.a")),
                ChappeMountPoint.instance().sharedPrefixes(ChappeListener.DEFAULT));
    }

    @Test
    void anotherOrderAnotherFile() throws Exception {
        Router router = mount(Map.of("vidocq.http.mount.a.order", "10", "vidocq.http.mount.b.order", "20"));

        assertEquals("a", body(router.handle(get("/both.txt"))));
    }

    private static String body(Response r) throws Exception {
        return new String(r.body().asInputStream().readAllBytes(), StandardCharsets.UTF_8);
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
