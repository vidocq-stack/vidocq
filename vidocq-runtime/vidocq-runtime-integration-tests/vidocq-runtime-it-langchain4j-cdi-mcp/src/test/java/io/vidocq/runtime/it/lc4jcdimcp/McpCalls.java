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
package io.vidocq.runtime.it.lc4jcdimcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The six MCP calls of the design of Vidocq/vidocq#94 over the 2025-03-26 protocol (initialize, then a session):
 * {@code tools/list}, {@code tools/call current_time}, {@code tools/call convert_time},
 * {@code resources/templates/list}, {@code resources/read} and {@code prompts/get plan_meeting}. Two of them build a
 * {@code ToolResponse} or a {@code PromptResponse}, which fails without the {@code McpServerSPI} provider.
 */
final class McpCalls {

    private final String url;
    private String sessionId;

    McpCalls(String url) {
        this.url = url;
    }

    /** Opens a session and returns the body of the {@code initialize} response. */
    String initialize() throws IOException {
        Response response = post("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"vidocq-it\",\"version\":\"1\"}}}");
        assertEquals(200, response.status(), response.body());
        sessionId = response.sessionId();
        assertNotNull(sessionId, "initialize returned no Mcp-Session-Id");
        post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        return response.body();
    }

    /** Runs the six calls and fails on the first one that errs or misses its expected text. */
    Map<String, String> runAll() throws IOException {
        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("tools/list", check("convert_time",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"));
        bodies.put("current_time", check("Europe/Paris",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"current_time\","
                        + "\"arguments\":{\"zone\":\"Europe/Paris\"}}}"));
        bodies.put("convert_time", check("Asia/Tokyo",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"convert_time\","
                        + "\"arguments\":{\"localDateTime\":\"2026-09-16T10:30:00\",\"fromZone\":\"Europe/Paris\","
                        + "\"toZone\":\"Asia/Tokyo\"}}}"));
        bodies.put("resources/templates/list", check("time://zone",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/templates/list\"}"));
        bodies.put("resources/read", check("Europe/Paris",
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/read\",\"params\":"
                        + "{\"uri\":\"time://zone/Europe/Paris\"}}"));
        bodies.put("plan_meeting", check("messages",
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"prompts/get\",\"params\":{\"name\":\"plan_meeting\","
                        + "\"arguments\":{\"zones\":\"Europe/Paris,Asia/Tokyo\",\"durationMinutes\":\"30\"}}}"));
        return bodies;
    }

    private String check(String expected, String request) throws IOException {
        Response response = post(request);
        assertEquals(200, response.status(), request + " -> " + response.body());
        assertTrue(response.body().contains(expected), request + " -> " + response.body());
        assertFalse(response.body().contains("\"isError\":true"), request + " -> " + response.body());
        assertFalse(response.body().contains("\"error\""), request + " -> " + response.body());
        return response.body();
    }

    private record Response(int status, String sessionId, String body) {}

    private Response post(String json) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(10_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json, text/event-stream");
            if (sessionId != null) {
                connection.setRequestProperty("Mcp-Session-Id", sessionId);
            }
            try (OutputStream out = connection.getOutputStream()) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            InputStream in = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            String body = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new Response(status, connection.getHeaderField("Mcp-Session-Id"), body);
        } finally {
            connection.disconnect();
        }
    }
}
