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
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One {@code GET /api/snapshot} of the dev console, the JSON its page polls, read as the page reads it: the values
 * of a panel are flat objects in its {@code sample}, such as
 * {@code {"key":"sessions","kind":"absent","reason":"no request served yet"}} or
 * {@code {"key":"sessions","kind":"gauge","value":1.0,"unit":"count"}}.
 *
 * <p>Read with the JDK only: this module has no JSON parser on its test class path, and the shapes it looks for are
 * small enough to be found by their key.
 *
 * @param json the body of the snapshot
 */
record DevConsoleSnapshot(String json) {

    /** Polls the console once. */
    static DevConsoleSnapshot read(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            InputStream in = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            String body = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(200, status, url + " -> " + body);
            return new DevConsoleSnapshot(body);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Asserts that the {@code rest} panel is there, lists the {@code /mcp} route among its boot facts and is live, and
     * returns the {@code requests} counter of its {@code vidocq.rest} mount (Vidocq/vidocq#103).
     */
    double restRequests() {
        int panel = json.indexOf("{\"id\":\"rest\",\"title\":\"REST (Cassini)\"");
        assertTrue(panel >= 0, "the snapshot holds no rest panel: " + json);
        // A panel ends where the next one's "live" flag starts: charts carry ids and titles too.
        int live = json.indexOf("\"live\":", panel);
        int nextLive = json.indexOf("\"live\":", live + 1);
        String excerpt = json.substring(panel, nextLive < 0 ? json.length() : nextLive);
        assertTrue(excerpt.contains("McpEndpoint#handlePost"), "the rest panel lists no /mcp route: " + excerpt);
        assertTrue(json.startsWith("\"live\":true", live), "the rest panel is not live: " + excerpt);
        java.util.regex.Matcher requests = java.util.regex.Pattern
                .compile("\\{\"key\":\"requests\",\"kind\":\"counter\",\"value\":([0-9.]+)")
                .matcher(excerpt);
        assertTrue(requests.find(), "the rest panel wrote no requests counter: " + excerpt);
        return Double.parseDouble(requests.group(1));
    }

    /** Asserts that the {@code mcp} panel is there and shown live, and returns where it starts. */
    private int mcpPanel() {
        int panel = json.indexOf("{\"id\":\"mcp\",\"title\":\"MCP server (langchain4j-cdi)\"");
        assertTrue(panel >= 0, "the snapshot holds no mcp panel: " + json);
        assertTrue(json.startsWith("\"live\":true", json.indexOf("\"live\":", panel)),
                "the mcp panel is not live: " + json.substring(panel, Math.min(json.length(), panel + 400)));
        return panel;
    }

    /**
     * The value object the {@code mcp} panel wrote for {@code key}, as the JSON it was written in. Looked up after
     * the panel's {@code sample}, since its {@code charts} name the same keys as the series they plot.
     *
     * @param key the key of the value, such as {@code sessions}
     */
    String value(String key) {
        int panel = json.indexOf("\"sample\":", mcpPanel());
        assertTrue(panel >= 0, "the mcp panel has no sample: " + json);
        int at = json.indexOf("{\"key\":\"" + key + "\"", panel);
        assertTrue(at >= 0, "the mcp panel wrote no '" + key + "': " + json);
        int end = json.indexOf('}', at);
        assertTrue(end > at, "the value of '" + key + "' is not closed: " + json);
        return json.substring(at, end + 1);
    }

    /** The number of a gauge or a counter the {@code mcp} panel wrote. */
    double number(String key) {
        String value = value(key);
        assertTrue(value.contains("\"kind\":\"gauge\"") || value.contains("\"kind\":\"counter\""),
                key + " is not a number: " + value);
        int at = value.indexOf("\"value\":");
        assertTrue(at >= 0, key + " has no value: " + value);
        String rest = value.substring(at + "\"value\":".length());
        int end = rest.indexOf(',');
        return Double.parseDouble(end < 0 ? rest.substring(0, rest.length() - 1) : rest.substring(0, end));
    }

    /** Asserts that {@code key} is absent, and returns the reason the panel gave. */
    String absent(String key) {
        String value = value(key);
        assertTrue(value.contains("\"kind\":\"absent\""), key + " is not absent: " + value);
        int at = value.indexOf("\"reason\":\"");
        assertTrue(at >= 0, key + " is absent without saying why: " + value);
        String rest = value.substring(at + "\"reason\":\"".length());
        return rest.substring(0, rest.indexOf('"'));
    }

    /** Asserts that the panel's sample was neither dropped nor flagged slow. */
    void assertSampled() {
        int panel = mcpPanel();
        int sample = json.indexOf("\"sample\":", panel);
        assertTrue(sample >= 0, "the mcp panel has no sample: " + json);
        assertTrue(json.startsWith("\"sample\":{", sample), "the mcp panel's sample was dropped: "
                + json.substring(sample, Math.min(json.length(), sample + 200)));
        int slow = json.indexOf("\"slow\":", sample);
        assertTrue(json.startsWith("\"slow\":false", slow),
                "the mcp panel's sample was flagged slow: " + json.substring(sample,
                        Math.min(json.length(), sample + 200)));
    }
}
