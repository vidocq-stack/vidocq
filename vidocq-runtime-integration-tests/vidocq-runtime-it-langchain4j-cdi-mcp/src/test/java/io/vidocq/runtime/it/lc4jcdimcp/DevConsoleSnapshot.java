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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /**
     * Polls the console twice and keeps the second answer. The first sample of a panel runs on a cold JVM, its classes
     * loaded and its code interpreted, and can pass the 5 ms mark on a busy build machine; the second is what the
     * page sees once a second, and the one whose time says something about the panel.
     */
    static DevConsoleSnapshot readWarm(String url) throws IOException {
        read(url);
        return read(url);
    }

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

    /**
     * The console's own {@code config} panel (Vidocq/vidocq#116), as the JSON it was written in: from its id to the
     * {@code cdi} panel, which the console shows after it.
     */
    String configPanel() {
        int panel = json.indexOf("{\"id\":\"config\",\"title\":\"Configuration\"");
        assertTrue(panel >= 0, "the snapshot holds no config panel: " + json);
        int cdi = json.indexOf("{\"id\":\"cdi\"", panel);
        assertTrue(cdi > panel, "the cdi panel does not follow the config panel: " + json);
        return json.substring(panel, cdi);
    }

    /**
     * The console's own {@code cdi} panel (Vidocq/vidocq#117), as the JSON it was written in: from its id to the
     * next of the console's panels, {@code logs} in a dev launch (Vidocq/vidocq#119), {@code jvm} otherwise.
     */
    String cdiPanel() {
        int panel = json.indexOf("{\"id\":\"cdi\",\"title\":\"CDI (Vauban)\"");
        assertTrue(panel >= 0, "the snapshot holds no cdi panel: " + json);
        int logs = json.indexOf("{\"id\":\"logs\"", panel);
        int next = logs > panel ? logs : json.indexOf("{\"id\":\"jvm\"", panel);
        assertTrue(next > panel, "no console panel follows the cdi panel: " + json);
        return json.substring(panel, next);
    }

    /**
     * The console's own {@code logs} panel of a dev launch (Vidocq/vidocq#119), as the JSON it was written in: from
     * its id to the {@code jvm} panel, which the console shows after it.
     */
    String logsPanel() {
        int panel = json.indexOf("{\"id\":\"logs\",\"title\":\"Logs\"");
        assertTrue(panel >= 0, "the snapshot holds no logs panel: " + json);
        int jvm = json.indexOf("{\"id\":\"jvm\"", panel);
        assertTrue(jvm > panel, "the jvm panel does not follow the logs panel: " + json);
        return json.substring(panel, jvm);
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

    /**
     * The longest a sample may take here. The console flags a sample slow past 5 ms, a hint for
     * the developer that a shared CI runner crosses on its own; a sample that blocks or does real
     * work takes far longer, and still fails this.
     */
    private static final long SAMPLE_BUDGET_NANOS = 500_000_000L;

    /** Asserts that the panel's sample was not dropped and took no unreasonable time. */
    void assertSampled() {
        int panel = mcpPanel();
        int sample = json.indexOf("\"sample\":", panel);
        assertTrue(sample >= 0, "the mcp panel has no sample: " + json);
        String excerpt = json.substring(sample, Math.min(json.length(), sample + 200));
        assertTrue(json.startsWith("\"sample\":{", sample), "the mcp panel's sample was dropped: " + excerpt);
        Matcher nanos = Pattern.compile("\"nanos\":(\\d+)").matcher(json);
        assertTrue(nanos.find(sample), "the mcp panel's sample has no duration: " + excerpt);
        assertTrue(Long.parseLong(nanos.group(1)) < SAMPLE_BUDGET_NANOS,
                "the mcp panel's sample took longer than " + SAMPLE_BUDGET_NANOS / 1_000_000 + " ms: " + excerpt);
    }

    /**
     * The token of the boot the snapshot carries in a dev launch, {@code console.actionToken}, or {@code null} when
     * it carries none (Vidocq/vidocq#118).
     */
    String actionToken() {
        Matcher token = Pattern.compile("\"actionToken\":\"([0-9a-f]*)\"").matcher(json);
        return token.find() ? token.group(1) : null;
    }

    /**
     * Sends the console an action request as a page of {@code origin} would, with {@code token}, and returns the
     * status it answers. With {@code java.net.http}, since {@code HttpURLConnection} refuses to set an
     * {@code Origin}.
     *
     * @param snapshotUrl the URL of the console's snapshot, whose origin the request is sent to
     * @param path        the path of the action, such as {@code /api/action/mcp/nothing}
     * @param origin      the {@code Origin} header
     * @param token       the {@code X-Vidocq-Console-Token} header
     */
    static int postAction(String snapshotUrl, String path, String origin, String token) throws Exception {
        return postAction(snapshotUrl, path, origin, token, "{}").statusCode();
    }

    /**
     * Sends the console an action request with {@code body}, as {@link #postAction(String, String, String, String)}
     * does, and returns its answer.
     *
     * @param body the JSON object of the action's arguments
     */
    static HttpResponse<String> postAction(String snapshotUrl, String path, String origin, String token, String body)
            throws Exception {
        URI target = URI.create(snapshotUrl).resolve(path);
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Origin", origin)
                .header("X-Vidocq-Console-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    /**
     * Sends the console's dev MCP (Vidocq/vidocq#121) one JSON-RPC request, as an MCP client that is no browser does:
     * no {@code Origin}, no token, and returns its answer.
     *
     * @param snapshotUrl the URL of the console's snapshot, whose origin the request is sent to
     * @param body        the JSON-RPC request
     */
    static HttpResponse<String> postMcp(String snapshotUrl, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(snapshotUrl).resolve("/mcp"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
