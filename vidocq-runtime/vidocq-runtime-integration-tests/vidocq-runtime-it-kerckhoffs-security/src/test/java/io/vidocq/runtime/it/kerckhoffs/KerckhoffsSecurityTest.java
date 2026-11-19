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
package io.vidocq.runtime.it.kerckhoffs;

import io.vidocq.runtime.core.VidocqBootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jakarta Security 4.0 on the Vidocq runtime: the BASIC mechanism the application declares authenticates the requests
 * Foy serves, against the application's identity store, and the injected security context answers for the caller.
 */
class KerckhoffsSecurityTest {

    private static VidocqBootstrap bootstrap;
    private static String base;

    @BeforeAll
    static void boot() {
        System.setProperty("vidocq.chappe.listener.default.host", "127.0.0.1");
        System.setProperty("vidocq.chappe.listener.default.port", "0");
        try (BoundPort port = BoundPort.watch()) {
            bootstrap = VidocqBootstrap.create().configure().start();
            base = "http://127.0.0.1:" + port.port();
        }
    }

    @AfterAll
    static void shutdown() {
        if (bootstrap != null) bootstrap.shutdown();
        System.clearProperty("vidocq.chappe.listener.default.host");
        System.clearProperty("vidocq.chappe.listener.default.port");
    }

    private static HttpResponse<String> get(String path, String credentials) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (credentials != null) {
            request.header("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aProtectedResourceChallengesARequestWithoutCredentials() throws Exception {
        HttpResponse<String> response = get("/secured", null);

        assertEquals(401, response.statusCode());
        assertTrue(response.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Basic"),
                response.headers().toString());
    }

    @Test
    void aWrongPasswordIsRefused() throws Exception {
        assertEquals(401, get("/secured", "alice:nope").statusCode());
    }

    @Test
    void theCallerOfAValidRequestIsKnownToTheSecurityContext() throws Exception {
        HttpResponse<String> response = get("/secured", "alice:wonderland");

        assertEquals(200, response.statusCode());
        assertEquals("alice|true|alice|true|BASIC", response.body());
    }

    @Test
    void anUnprotectedResourceSeesNoCallerWithoutCredentials() throws Exception {
        HttpResponse<String> response = get("/public", null);

        assertEquals(200, response.statusCode());
        assertEquals("anonymous", response.body());
    }
}
