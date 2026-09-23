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

import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.StatusCode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The dev MCP: the dev console as read-only tools a coding agent calls over the Model Context Protocol, at
 * {@code POST /mcp} of the console's own listener, in a {@code dev} launch only. It answers what the page shows, and
 * nothing else: every tool reads {@link Snapshot#toolDocument()}, the page's own snapshot without the token of the
 * boot, the actions and the history.
 *
 * <p>The transport is the part of MCP's Streamable HTTP that {@code tools/list} and {@code tools/call} need: one
 * JSON-RPC 2.0 request a {@code POST}, answered {@code application/json}, never an event stream, and no session
 * ({@code Mcp-Session-Id} is neither given nor read). {@link ConsoleHandler} checks the {@code Host}, the method, the
 * content type, the {@code Origin} and the {@code Accept} header first; this class then reads the body, at most
 * {@value #MAX_BODY} bytes ({@code 413}), and answers:
 * <ul>
 *   <li>{@code initialize}, with the protocol version the client asked for when it is one of
 *       {@link #PROTOCOL_VERSIONS}, the newest of them otherwise; {@code ping}; {@code tools/list};
 *       {@code tools/call};</li>
 *   <li>a notification, such as {@code notifications/initialized}, or a response: {@code 202}, no body;</li>
 *   <li>an unknown method: {@code -32601}; parameters it cannot use: {@code -32602}; a body that is not JSON, or
 *       nested deeper than {@value #MAX_DEPTH}: {@code -32700}; anything but one request object, a batch included:
 *       {@code -32600}. These last two with {@code 400}, every other answer with {@code 200}.</li>
 * </ul>
 *
 * <p>No tool changes anything: none runs a panel's action. They are {@code vidocq_report}, {@code vidocq_panels},
 * {@code vidocq_panel}, {@code vidocq_anomalies} and {@code vidocq_routes}; each answers its object as
 * {@code structuredContent} and, for a client that reads text only, as indented JSON in one {@code text} content.
 */
final class DevMcp {

    /** Where the console serves it. */
    static final String PATH = "/mcp";
    /** The largest body of a request, in bytes. */
    static final int MAX_BODY = 64 * 1024;
    /** How deep the values of a request may nest. */
    static final int MAX_DEPTH = 32;
    /** The versions of MCP it speaks, the newest first: what it needs of each is the same. */
    static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26");
    /** Its name in {@code serverInfo}. */
    static final String SERVER_NAME = "vidocq-dev-console";

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;

    /** How deep the snapshot's own values nest, well past what it writes. */
    private static final int SNAPSHOT_DEPTH = 64;
    private static final String READ_ONLY = " Read-only: it changes nothing in the application.";

    private final Snapshot snapshot;
    private final String version;

    /**
     * @param snapshot what the page is sent, which the tools read
     * @param version  the Vidocq version, or {@code null}
     */
    DevMcp(Snapshot snapshot, String version) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.version = version;
    }

    /**
     * Answers a request {@link ConsoleHandler} let through.
     *
     * @param request the request, its body not read yet
     * @return the answer
     */
    Response handle(Request request) {
        String body;
        try {
            body = ConsoleActions.body(request, MAX_BODY);
        } catch (ConsoleActions.TooLarge tooLarge) {
            return ConsoleActions.text(StatusCode.PAYLOAD_TOO_LARGE, "The body is larger than " + MAX_BODY
                    + " bytes.");
        } catch (IOException | RuntimeException unreadable) {
            return answer(StatusCode.BAD_REQUEST, error(null, PARSE_ERROR, "The body is not UTF-8 text."));
        }
        return answer(body);
    }

    /**
     * Answers one JSON-RPC text.
     *
     * @param body the body of the request
     * @return the answer
     */
    Response answer(String body) {
        Object message;
        try {
            message = JsonValues.parse(body, MAX_DEPTH);
        } catch (IllegalArgumentException notJson) {
            return answer(StatusCode.BAD_REQUEST, error(null, PARSE_ERROR, "Parse error: " + notJson.getMessage()));
        }
        if (message instanceof List<?>) {
            return answer(StatusCode.BAD_REQUEST, error(null, INVALID_REQUEST, "A batch is not supported: send one "
                    + "request per POST."));
        }
        if (!(message instanceof Map<?, ?> request) || !"2.0".equals(request.get("jsonrpc"))) {
            return answer(StatusCode.BAD_REQUEST, error(null, INVALID_REQUEST, "Not a JSON-RPC 2.0 request."));
        }
        Object id = request.get("id");
        boolean hasId = request.containsKey("id");
        if (!request.containsKey("method")) {
            // a response from the client, which this server never asked for anything: nothing to answer
            return hasId && (request.containsKey("result") || request.containsKey("error"))
                    ? accepted()
                    : answer(StatusCode.BAD_REQUEST, error(null, INVALID_REQUEST, "Not a JSON-RPC 2.0 request."));
        }
        if (!(request.get("method") instanceof String method)) {
            return answer(StatusCode.BAD_REQUEST, error(null, INVALID_REQUEST, "The method is not a string."));
        }
        if (!hasId) {
            return accepted();
        }
        if (!(id instanceof String) && !(id instanceof Number)) {
            return answer(StatusCode.BAD_REQUEST, error(null, INVALID_REQUEST, "The id is not a string or a "
                    + "number."));
        }
        Object params = request.get("params");
        if (params != null && !(params instanceof Map<?, ?>)) {
            return answer(StatusCode.OK, error(id, INVALID_PARAMS, "The params are not an object."));
        }
        Map<?, ?> named = params == null ? Map.of() : (Map<?, ?>) params;
        try {
            Object result = switch (method) {
                case "initialize" -> initialize(named);
                case "ping" -> Map.of();
                case "tools/list" -> Map.of("tools", tools());
                case "tools/call" -> call(named);
                default -> throw new Refused(METHOD_NOT_FOUND, "Method not found: " + Texts.clean(method));
            };
            return answer(StatusCode.OK, result(id, result));
        } catch (Refused refused) {
            return answer(StatusCode.OK, error(id, refused.code, refused.getMessage()));
        }
    }

    private Map<String, Object> initialize(Map<?, ?> params) {
        if (!(params.get("protocolVersion") instanceof String asked)) {
            throw new Refused(INVALID_PARAMS, "initialize takes a protocolVersion.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSIONS.contains(asked) ? asked : PROTOCOL_VERSIONS.get(0));
        result.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("name", SERVER_NAME);
        server.put("version", version == null ? "unknown" : version);
        result.put("serverInfo", server);
        result.put("instructions", "The dev console of a running Vidocq application, read-only: its startup report, "
                + "its panels with their latest values, its anomalies and the absolute URLs of its REST routes.");
        return result;
    }

    /** The tools, {@code vidocq_panel}'s ids those of the panels the console shows now. */
    private List<Object> tools() {
        List<Object> ids = new ArrayList<>();
        for (Object panel : list(document().get("panels"))) {
            ids.add(map(panel).get("id"));
        }
        Map<String, Object> id = new LinkedHashMap<>();
        id.put("type", "string");
        id.put("description", "The id of the panel, as vidocq_panels lists it.");
        id.put("enum", ids);
        return List.of(
                tool("vidocq_report", "Startup report", "The startup report of the running application, as the dev "
                        + "console shows it: the launch mode, the anomalies, every section that is not a panel, and "
                        + "the whole report as text." + READ_ONLY, Map.of()),
                tool("vidocq_panels", "Dev console panels", "The panels of the dev console: the id, the title, "
                        + "whether it has live values, and the summary of each." + READ_ONLY, Map.of()),
                tool("vidocq_panel", "One dev console panel", "One panel of the dev console, by id: its boot facts "
                        + "and its latest sample of live values. Secrets are masked, as on the page." + READ_ONLY,
                        Map.of("id", id)),
                tool("vidocq_anomalies", "Anomalies", "The anomalies of the startup report: what is wrong without "
                        + "stopping the boot, each with its code, message, hint and source." + READ_ONLY, Map.of()),
                tool("vidocq_routes", "REST routes", "The REST routes the application serves, each with its HTTP "
                        + "method, its absolute URL and the method that handles it." + READ_ONLY, Map.of()));
    }

    private static Map<String, Object> tool(String name, String title, String description,
                                            Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!properties.isEmpty()) {
            schema.put("required", List.copyOf(properties.keySet()));
        }
        schema.put("additionalProperties", false);
        Map<String, Object> annotations = new LinkedHashMap<>();
        annotations.put("title", title);
        annotations.put("readOnlyHint", true);
        annotations.put("openWorldHint", false);
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("title", title);
        tool.put("description", description);
        tool.put("inputSchema", schema);
        tool.put("annotations", annotations);
        return tool;
    }

    private Map<String, Object> call(Map<?, ?> params) {
        if (!(params.get("name") instanceof String name)) {
            throw new Refused(INVALID_PARAMS, "tools/call takes the name of a tool.");
        }
        Object arguments = params.get("arguments");
        if (arguments != null && !(arguments instanceof Map<?, ?>)) {
            throw new Refused(INVALID_PARAMS, "The arguments are not an object.");
        }
        Map<?, ?> named = arguments == null ? Map.of() : (Map<?, ?>) arguments;
        Map<String, Object> document = document();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", document.get("state"));
        Map<?, ?> startup = document.get("startup") == null ? null : map(document.get("startup"));
        switch (name) {
            case "vidocq_report" -> {
                out.put("console", document.get("console"));
                out.put("startup", startup);
            }
            case "vidocq_panels" -> {
                List<Object> panels = new ArrayList<>();
                for (Object each : list(document.get("panels"))) {
                    Map<?, ?> panel = map(each);
                    Map<String, Object> shown = new LinkedHashMap<>();
                    for (String key : List.of("id", "title", "live", "summary")) {
                        shown.put(key, panel.get(key));
                    }
                    panels.add(shown);
                }
                out.put("panels", panels);
            }
            case "vidocq_panel" -> {
                if (!(named.get("id") instanceof String id)) {
                    throw new Refused(INVALID_PARAMS, "vidocq_panel takes the id of a panel.");
                }
                Object found = null;
                List<Object> ids = new ArrayList<>();
                for (Object each : list(document.get("panels"))) {
                    ids.add(map(each).get("id"));
                    if (id.equals(map(each).get("id"))) {
                        found = each;
                    }
                }
                if (found == null) {
                    return toolError("No panel '" + Texts.clean(id) + "'. The panels are " + ids + ".");
                }
                out.put("panel", found);
            }
            case "vidocq_anomalies" -> {
                out.put("anomalies", startup == null ? List.of() : startup.get("anomalies"));
                if (startup != null && Boolean.TRUE.equals(startup.get("truncated"))) {
                    out.put("truncated", true);
                }
            }
            case "vidocq_routes" -> out.put("routes", routes(document, startup));
            default -> throw new Refused(INVALID_PARAMS, "Unknown tool: " + Texts.clean(name));
        }
        return toolResult(out, false);
    }

    /**
     * The routes of the {@code rest} section, a panel or not: every line with no key and three values, the method,
     * the URL and the handler, as the report writes a route.
     */
    private static List<Object> routes(Map<String, Object> document, Map<?, ?> startup) {
        List<Object> sections = new ArrayList<>(list(document.get("panels")));
        if (startup != null) {
            sections.addAll(list(startup.get("sections")));
        }
        List<Object> routes = new ArrayList<>();
        for (Object each : sections) {
            Map<?, ?> section = map(each);
            if (!"rest".equals(section.get("id"))) {
                continue;
            }
            for (Object line : list(section.get("lines"))) {
                List<?> cells = list(line);
                int values = cells.size() > 0 && cells.get(cells.size() - 1) instanceof Map<?, ?>
                        ? cells.size() - 2 : cells.size() - 1;
                if (cells.isEmpty() || cells.get(0) != null || values != 3) {
                    continue;
                }
                Map<String, Object> route = new LinkedHashMap<>();
                route.put("method", cells.get(1));
                route.put("url", cells.get(2));
                route.put("handler", cells.get(3));
                routes.add(route);
            }
        }
        return routes;
    }

    private Map<String, Object> document() {
        return map(JsonValues.parse(snapshot.toolDocument(), SNAPSHOT_DEPTH));
    }

    private static Map<String, Object> toolResult(Map<String, Object> structured, boolean error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(text(JsonValues.pretty(structured))));
        result.put("structuredContent", structured);
        result.put("isError", error);
        return result;
    }

    /** A tool that ran and could not answer: said to the model, which may correct its call. */
    private static Map<String, Object> toolError(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(text(message)));
        result.put("isError", true);
        return result;
    }

    private static Map<String, Object> text(String text) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", text);
        return content;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String result(Object id, Object result) {
        JsonWriter out = new JsonWriter().beginObject().name("jsonrpc").value("2.0").name("id");
        JsonValues.write(out, id);
        out.name("result");
        JsonValues.write(out, result);
        return out.endObject().toString();
    }

    private static String error(Object id, int code, String message) {
        JsonWriter out = new JsonWriter().beginObject().name("jsonrpc").value("2.0").name("id");
        JsonValues.write(out, id);
        return out.name("error").beginObject().name("code").value(code).name("message").value(message).endObject()
                .endObject().toString();
    }

    private static Response answer(StatusCode status, String json) {
        return Response.builder()
                .status(status)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Cache-Control", "no-store")
                .body(json)
                .build();
    }

    private static Response accepted() {
        return Response.builder().status(StatusCode.ACCEPTED).header("Cache-Control", "no-store").build();
    }

    /** A request this server does not answer, with its JSON-RPC error code. */
    private static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int code;

        Refused(int code, String message) {
            super(message, null, false, false);
            this.code = code;
        }
    }
}
