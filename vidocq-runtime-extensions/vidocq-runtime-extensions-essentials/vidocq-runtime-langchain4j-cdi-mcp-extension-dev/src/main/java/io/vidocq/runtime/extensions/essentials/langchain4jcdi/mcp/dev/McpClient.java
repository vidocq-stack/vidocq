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
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import dev.langchain4j.cdi.mcp.server.protocol.McpHttpHeaders;
import dev.langchain4j.cdi.mcp.server.protocol.McpMetaKeys;
import dev.langchain4j.cdi.mcp.server.protocol.McpProtocolVersions;
import dev.langchain4j.cdi.mcp.server.transport.McpParamHeaderValidator;
import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The MCP inspector's client (spec §3.2): one stateless {@code POST} per call to the application's own {@code /mcp},
 * in protocol {@value #PROTOCOL}. No {@code initialize} and no session, so that {@code McpSessionManager} and its
 * cleanup thread are never created; no {@code Origin}, which langchain4j-cdi's origin check lets through as a client
 * that is no browser.
 *
 * <p>Each request carries what {@code McpEraDetector} requires of the modern era: the protocol version in the
 * {@code MCP-Protocol-Version} header and in {@code _meta}, the method in {@code Mcp-Method}, the name or URI in
 * {@code Mcp-Name}, Base64-wrapped when it is not plain ASCII, the client's capabilities, and for a tool the
 * {@code Mcp-Param-*} headers of its designated arguments. It declares elicitation, sampling and roots, so that a
 * tool that needs them answers {@code input_required}, which the inspector reports, rather than a missing
 * capability. It accepts JSON and SSE; from a stream it keeps the final response and the events before it.
 */
final class McpClient {

    /** The stateless era of MCP. */
    static final String PROTOCOL = McpProtocolVersions.MODERN_2026_07_28;
    /** What the inspector calls itself in {@code clientInfo}. */
    static final String CLIENT_NAME = "vidocq-dev-console";

    private static final String BASE64_PREFIX = "=?base64?";
    private static final String BASE64_SUFFIX = "?=";

    /**
     * One call.
     *
     * @param request  the JSON-RPC request sent
     * @param response the final JSON-RPC response, with {@code result} or {@code error}
     * @param events   the events of an SSE answer, the response included; empty for a JSON answer
     * @param status   the HTTP status
     * @param millis   how long the call took
     */
    record Exchange(JsonObject request, JsonObject response, List<JsonObject> events, int status, long millis) {}

    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient http;
    private final AtomicLong ids = new AtomicLong();

    /**
     * @param endpoint the absolute URL of {@code /mcp}
     * @param timeout  how long a call may take, under the console's 60 s action limit
     */
    McpClient(URI endpoint, Duration timeout) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** The URL of {@code /mcp} this client calls. */
    URI endpoint() {
        return endpoint;
    }

    /** A JSON-RPC request of {@code method} with a new id, and {@code params} with the {@code _meta} of the era. */
    JsonObject request(String method, JsonObject params) {
        return Json.createObjectBuilder()
                .add("jsonrpc", "2.0")
                .add("id", ids.incrementAndGet())
                .add("method", method)
                .add("params", Json.createObjectBuilder(params).add(McpMetaKeys.META, meta()))
                .build();
    }

    /**
     * Sends {@code request} and waits for its answer.
     *
     * @param request      a request of {@link #request}
     * @param name         the tool or prompt name, or the resource URI, for {@code Mcp-Name}
     * @param paramHeaders the {@code Mcp-Param-*} headers, by designation, from {@link #paramHeaders}
     * @return the exchange
     * @throws McpTransportException when no JSON-RPC answer came back
     */
    Exchange send(JsonObject request, String name, Map<String, String> paramHeaders) throws McpTransportException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header(McpHttpHeaders.PROTOCOL_VERSION, PROTOCOL)
                .header(McpHttpHeaders.METHOD, request.getString("method"))
                .header(McpHttpHeaders.NAME, headerValue(name))
                .POST(HttpRequest.BodyPublishers.ofString(request.toString(), StandardCharsets.UTF_8));
        paramHeaders.forEach((designation, value) ->
                builder.header(McpParamHeaderValidator.HEADER_PREFIX + designation, headerValue(value)));
        long start = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException unreachable) {
            throw new McpTransportException("/mcp unreachable at " + endpoint);
        } catch (HttpTimeoutException slow) {
            throw new McpTransportException("timed out after " + timeout.toSeconds() + " s");
        } catch (IOException unreachable) {
            throw new McpTransportException("/mcp unreachable at " + endpoint);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new McpTransportException("interrupted while waiting for /mcp");
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        String type = response.headers().firstValue("Content-Type").orElse("");
        List<JsonObject> events = List.of();
        JsonObject answer;
        if (type.regionMatches(true, 0, "text/event-stream", 0, "text/event-stream".length())) {
            events = events(response.body());
            answer = finalResponse(events, request.get("id"));
        } else {
            answer = object(response.body());
        }
        if (answer == null || !(answer.containsKey("result") || answer.containsKey("error"))) {
            throw new McpTransportException("HTTP " + response.statusCode());
        }
        return new Exchange(request, answer, events, response.statusCode(), millis);
    }

    /**
     * The {@code Mcp-Param-*} headers a tool's designated arguments need (SEP-2243): a string as itself, an integer
     * as its decimal text, a boolean as {@code true} or {@code false}; a {@code null}, absent or non-primitive value
     * is left out, as langchain4j-cdi expects.
     *
     * @param designations the tool's designations, argument name to header suffix
     * @param arguments    the call's arguments
     * @return the header values by suffix
     */
    static Map<String, String> paramHeaders(Map<String, String> designations, JsonObject arguments) {
        Map<String, String> headers = new LinkedHashMap<>();
        designations.forEach((argument, designation) -> {
            JsonValue value = arguments.get(argument);
            String mirrored = null;
            if (value instanceof JsonString string) {
                mirrored = string.getString();
            } else if (value instanceof JsonNumber number) {
                mirrored = number.isIntegral() ? number.bigIntegerValue().toString() : number.toString();
            } else if (value != null && value.getValueType() == JsonValue.ValueType.TRUE) {
                mirrored = "true";
            } else if (value != null && value.getValueType() == JsonValue.ValueType.FALSE) {
                mirrored = "false";
            }
            if (mirrored != null) {
                headers.put(designation, mirrored);
            }
        });
        return headers;
    }

    /**
     * {@code value} as a header carries it: as it is when it is printable ASCII without a space at either end,
     * {@code =?base64?…?=} otherwise, which langchain4j-cdi decodes.
     */
    static String headerValue(String value) {
        boolean plain = !value.startsWith(BASE64_PREFIX) && value.equals(value.strip());
        for (int i = 0; plain && i < value.length(); i++) {
            char c = value.charAt(i);
            plain = c >= 0x20 && c <= 0x7e;
        }
        return plain ? value : BASE64_PREFIX
                + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + BASE64_SUFFIX;
    }

    /** The JSON objects the {@code data} lines of an SSE body carry, one per event, in order. */
    static List<JsonObject> events(String body) {
        List<JsonObject> events = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\r?\n", -1)) {
            if (line.isEmpty()) {
                flush(data, events);
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(line.startsWith("data: ") ? line.substring(6) : line.substring(5));
            }
        }
        flush(data, events);
        return List.copyOf(events);
    }

    private static void flush(StringBuilder data, List<JsonObject> events) {
        if (data.isEmpty()) {
            return;
        }
        JsonObject event = object(data.toString());
        if (event != null) {
            events.add(event);
        }
        data.setLength(0);
    }

    /** The last event that answers {@code id}: the final response; notifications before it are left out. */
    private static JsonObject finalResponse(List<JsonObject> events, JsonValue id) {
        JsonObject last = null;
        for (JsonObject event : events) {
            if (id.equals(event.get("id")) && (event.containsKey("result") || event.containsKey("error"))) {
                last = event;
            }
        }
        return last;
    }

    /** {@code text} as a JSON object, or {@code null} when it is none. */
    private static JsonObject object(String text) {
        try (JsonReader reader = Json.createReader(new StringReader(text))) {
            return reader.readObject();
        } catch (JsonException | IllegalStateException notAnObject) {
            return null;
        }
    }

    private static JsonObject meta() {
        return Json.createObjectBuilder()
                .add(McpMetaKeys.PROTOCOL_VERSION, PROTOCOL)
                .add(McpMetaKeys.CLIENT_INFO, Json.createObjectBuilder().add("name", CLIENT_NAME).add("version", "1"))
                .add(McpMetaKeys.CLIENT_CAPABILITIES, Json.createObjectBuilder()
                        .add("elicitation", JsonValue.EMPTY_JSON_OBJECT)
                        .add("sampling", JsonValue.EMPTY_JSON_OBJECT)
                        .add("roots", JsonValue.EMPTY_JSON_OBJECT))
                .build();
    }
}
