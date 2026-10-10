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
package io.vidocq.humboldt.it.openliberty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Humboldt jars, unchanged, inside a WAR on Open Liberty (vidocq-workspace#15, humboldt#23):
 * Liberty's Jakarta REST runs the Humboldt server filters, and Liberty's MicroProfile Rest Client
 * runs the client filters that Humboldt's {@code RestClientListener} registers. Liberty's
 * mpTelemetry feature is off, so every span here is Humboldt's.
 */
class OpenLibertyPortabilityIT {

    private static final String BASE = System.getProperty("humboldt.it.base");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void requestProducesAServerSpan() throws Exception {
        assertEquals("hello", get("/traced/hello"));

        List<String> spans = spans();
        assertTrue(spans.stream().anyMatch(s -> s.startsWith("SERVER|GET ") && s.contains("/traced/hello")), spans.toString());
    }

    @Test
    void restClientCallPropagatesTheTraceContext() throws Exception {
        String traceparent = get("/traced/call");

        // W3C Trace Context: 00-<trace id>-<parent span id>-<flags>
        String[] parts = traceparent.split("-");
        assertEquals(4, parts.length, "no traceparent reached the callee: " + traceparent);
        String traceId = parts[1];
        List<String> spans = spans();
        assertTrue(spans.stream().anyMatch(s -> s.startsWith("SERVER|") && s.contains("/traced/call|" + traceId + "|")), spans.toString());
        assertTrue(spans.stream().anyMatch(s -> s.startsWith("CLIENT|") && s.contains("|" + traceId + "|")), spans.toString());
    }

    @Test
    void webApplicationExceptionKeepsItsStatus() throws Exception {
        // Humboldt's ExceptionMapper<Throwable> must not turn a 404 into a 500.
        assertEquals(404, status("/traced/missing"));
    }

    @Test
    void failingResourceRecordsTheExceptionOnItsServerSpan() throws Exception {
        assertEquals(500, status("/traced/fail"));

        List<String> spans = spans();
        assertTrue(spans.stream().anyMatch(s -> s.startsWith("SERVER|") && s.contains("/traced/fail|")
                && s.contains("|ERROR|") && s.endsWith("[exception]")), spans.toString());
    }

    private static List<String> spans() throws Exception {
        return get("/traced/spans").lines().toList();
    }

    private static int status(String path) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create(BASE + path)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static String get(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(BASE + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + ": " + response.body());
        return response.body();
    }
}
