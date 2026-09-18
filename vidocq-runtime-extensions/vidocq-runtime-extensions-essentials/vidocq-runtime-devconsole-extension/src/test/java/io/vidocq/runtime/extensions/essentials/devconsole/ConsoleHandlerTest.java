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
package io.vidocq.runtime.extensions.essentials.devconsole;

import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.StatusCode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Everything the console answers on its listener: the snapshot, the page, and what it refuses, each with the headers
 * that keep another site from using it.
 */
class ConsoleHandlerTest {

    private final List<String> served = new ArrayList<>();
    private final Snapshot snapshot = new Snapshot("0123456789abcdef", "0.4.0-TEST", () -> Optional.empty(), List.of(),
            () -> 1L);
    private final Handler page = request -> {
        served.add(request.pathInfo());
        return Response.builder().status(StatusCode.OK).header("Content-Type", "text/html")
                .header("Cache-Control", "no-cache").body("<!doctype html>").build();
    };
    private final ConsoleHandler handler = new ConsoleHandler(new HostGuard("127.0.0.1"), () -> 8888, snapshot, page);

    private static String body(Response response) throws IOException {
        try (InputStream in = response.body().asInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void assertGuarded(Response response) {
        assertEquals("nosniff", response.headers().firstOrNull("X-Content-Type-Options"));
        assertEquals("no-referrer", response.headers().firstOrNull("Referrer-Policy"));
        assertEquals("default-src 'self'; frame-ancestors 'none'",
                response.headers().firstOrNull("Content-Security-Policy"));
        assertTrue(response.headers().all("Access-Control-Allow-Origin").isEmpty(), "no CORS");
    }

    @Test
    void theSnapshotIsJsonThatNoCacheKeeps() throws Exception {
        Response response = handler.handle(FakeRequest.get("/api/snapshot", "127.0.0.1:8888"));

        assertEquals(200, response.status().code());
        assertEquals("application/json; charset=utf-8", response.headers().firstOrNull("Content-Type"));
        assertEquals("no-store", response.headers().firstOrNull("Cache-Control"));
        assertGuarded(response);
        assertEquals("booting", Json.object(body(response)).get("state"));
        assertTrue(served.isEmpty());
    }

    @Test
    void headAnswersTheSnapshotsHeaders() throws Exception {
        Response response = handler.handle(new FakeRequest(HttpMethod.HEAD, "/api/snapshot", "localhost:8888"));

        assertEquals(200, response.status().code());
        assertEquals("application/json; charset=utf-8", response.headers().firstOrNull("Content-Type"));
        assertEquals("no-store", response.headers().firstOrNull("Cache-Control"));
        assertGuarded(response);
    }

    @Test
    void everyOtherPathIsThePage() throws Exception {
        Response index = handler.handle(FakeRequest.get("/", "localhost:8888"));
        Response script = handler.handle(FakeRequest.get("/console.js", "[::1]:8888"));

        assertEquals(List.of("/", "/console.js"), served);
        assertEquals(200, index.status().code());
        assertEquals("no-cache", index.headers().firstOrNull("Cache-Control"));
        assertGuarded(index);
        assertGuarded(script);
    }

    @Test
    void aForeignHostIsForbidden() throws Exception {
        for (String host : new String[] {"evil.example.com:8888", "127.0.0.1:9999", null}) {
            Response snapshotResponse = handler.handle(FakeRequest.get("/api/snapshot", host));
            Response pageResponse = handler.handle(FakeRequest.get("/", host));

            assertEquals(403, snapshotResponse.status().code(), host);
            assertEquals(403, pageResponse.status().code(), host);
            assertGuarded(snapshotResponse);
            assertTrue(!body(snapshotResponse).contains("booting"), "nothing of the console");
        }
        assertTrue(served.isEmpty());
    }

    @Test
    void theConsoleIsReadOnly() throws Exception {
        for (HttpMethod method : new HttpMethod[] {HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE,
                HttpMethod.PATCH, HttpMethod.OPTIONS}) {
            for (String path : new String[] {"/api/snapshot", "/"}) {
                Response response = handler.handle(new FakeRequest(method, path, "127.0.0.1:8888"));

                assertEquals(405, response.status().code(), method + " " + path);
                assertEquals("GET, HEAD", response.headers().firstOrNull("Allow"));
                assertGuarded(response);
            }
        }
        assertTrue(served.isEmpty());
    }
}
