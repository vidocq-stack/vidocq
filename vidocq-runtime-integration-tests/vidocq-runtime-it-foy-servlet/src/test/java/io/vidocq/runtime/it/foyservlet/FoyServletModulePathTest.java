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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.CookieManager;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The assembled runtime with the Servlet and REST extensions, on the module path: servlets, a
 * filter, the request and session scopes, and a REST resource share one listener; an
 * {@code IdentityStore} bean authenticates the callers of a role-constrained servlet.
 */
class FoyServletModulePathTest {

    private static VidocqBootstrap bootstrap;
    private static String base;

    @BeforeAll
    static void boot() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        System.setProperty("vidocq.chappe.listener.default.host", "127.0.0.1");
        System.setProperty("vidocq.chappe.listener.default.port", Integer.toString(port));
        base = "http://127.0.0.1:" + port;
        bootstrap = VidocqBootstrap.create().configure().start();
    }

    @AfterAll
    static void shutdown() {
        if (bootstrap != null) bootstrap.shutdown();
        System.clearProperty("vidocq.chappe.listener.default.host");
        System.clearProperty("vidocq.chappe.listener.default.port");
    }

    @Test
    void runsOnTheModulePath() {
        assertTrue(FoyServletModulePathTest.class.getModule().isNamed());
        assertTrue(ModuleLayer.boot().findModule("io.vidocq.foy.chappe").isPresent());
    }

    @Test
    void theExtensionExportsNothing() {
        Module extension = ModuleLayer.boot().findModule("io.vidocq.runtime.extensions.jakartaee.web.foy").orElseThrow();

        assertEquals(java.util.Set.of(), extension.getDescriptor().exports());
    }

    @Test
    void servletAnswersThroughTheFilter() throws Exception {
        HttpResponse<String> r = get(HttpClient.newHttpClient(), "/cart");

        assertEquals(200, r.statusCode());
        assertEquals("foy", r.headers().firstValue("X-Powered-By").orElse(null));
    }

    @Test
    void cartIsKeptPerSession() throws Exception {
        HttpClient alice = cookieClient();
        HttpClient bob = cookieClient();

        get(alice, "/cart?add=apple");
        get(bob, "/cart?add=pear");

        assertEquals("apple,banana", get(alice, "/cart?add=banana").body());
        assertEquals("pear", get(bob, "/cart").body());
    }

    @Test
    void invalidateDestroysTheSessionBean() throws Exception {
        HttpClient client = cookieClient();
        get(client, "/cart?add=apple");
        int before = Integer.parseInt(get(client, "/session-events").body());

        assertEquals("invalidated", get(client, "/cart?invalidate=1").body());

        assertEquals(before + 1, Integer.parseInt(get(client, "/session-events").body()));
        assertEquals("", get(client, "/cart").body());
    }

    @Test
    void eachConcurrentRequestHasItsOwnRequestScopedInstance() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) calls.add(() -> get(client, "/ticket").body());
            Set<String> ids = new HashSet<>();
            for (Future<String> f : pool.invokeAll(calls)) {
                String id = f.get();
                assertNotEquals("MISMATCH", id);
                ids.add(id);
            }
            assertEquals(8, ids.size());
        }
    }

    @Test
    void restAndServletShareThePort() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        assertEquals("rest", get(client, "/api/items").body());
        assertEquals(200, get(client, "/cart").statusCode());
    }

    @Test
    void aProtectedServletAsksForCredentials() throws Exception {
        HttpResponse<String> r = get(HttpClient.newHttpClient(), "/account");

        assertEquals(401, r.statusCode());
        assertTrue(r.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Basic"));
    }

    @Test
    void theIdentityStoreBeanAuthenticatesAUserWithTheRole() throws Exception {
        HttpResponse<String> r = getAs("alice", "secret", "/account");

        assertEquals(200, r.statusCode());
        assertEquals("alice", r.body());
    }

    @Test
    void aUserWithoutTheRoleIsForbidden() throws Exception {
        assertEquals(403, getAs("bob", "secret", "/account").statusCode());
    }

    @Test
    void aWrongPasswordIsRefused() throws Exception {
        assertEquals(401, getAs("alice", "nope", "/account").statusCode());
    }

    @Test
    void aProgrammaticLoginRotatesTheSessionId() throws Exception {
        String[] answer = get(cookieClient(), "/login?user=alice&password=secret").body().split(" ");

        assertEquals("alice", answer[0]);
        assertNotEquals(answer[1], answer[2]);
        assertEquals("refused", get(cookieClient(), "/login?user=alice&password=nope").body());
    }

    private static HttpResponse<String> getAs(String user, String password, String path) throws Exception {
        String credentials = Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Basic " + credentials).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpClient cookieClient() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }

    private static HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
