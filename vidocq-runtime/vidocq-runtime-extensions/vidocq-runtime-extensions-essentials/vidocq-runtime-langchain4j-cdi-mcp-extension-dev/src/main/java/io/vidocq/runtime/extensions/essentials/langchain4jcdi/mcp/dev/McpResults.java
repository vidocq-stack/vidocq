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

import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** What the page shows of an MCP answer (spec §3.3): a summary line, a body, and whether it is an error. */
final class McpResults {

    /** The refusal of a tool that asks the client for input, with the kinds it asks for. */
    static final String UNSUPPORTED =
            "this tool asks the client for input (%s): not supported by the dev console inspector yet";
    /** The same refusal when the server's error names no kind. */
    static final String UNSUPPORTED_UNNAMED =
            "this tool asks the client for input: not supported by the dev console inspector yet";
    /**
     * langchain4j-cdi's {@code MISSING_REQUIRED_CLIENT_CAPABILITY}: the inspector declares no client capability, so a
     * tool that asks for elicitation, sampling or roots gets this error, its {@code data.requiredCapabilities}
     * naming what it asked for.
     */
    static final int MISSING_CAPABILITY = -32021;

    private McpResults() {}

    /**
     * The result of a call that got a JSON-RPC answer. The summary is scrubbed of {@code secrets} before the
     * {@link ActionResult} cuts it, so that no prefix of a secret survives the cut; the body of an error or of an
     * input request, the server's own object, is scrubbed too. A result's body is shown as the server returned it.
     *
     * @param kind     what was called
     * @param exchange the call
     * @param details  the JSON of the exchange, as the page shows it under "Exchange"
     * @param secrets  the values of the secret arguments, see {@link Secrets}
     * @return the result
     */
    static ActionResult of(McpCatalogue.Kind kind, McpClient.Exchange exchange, String details,
            Collection<String> secrets) {
        JsonObject response = exchange.response();
        long ms = exchange.millis();
        if (response.get("error") instanceof JsonObject error) {
            String body = Secrets.scrubTree(error, secrets).toString();
            if (error.get("code") instanceof JsonNumber code && code.isIntegral()
                    && code.intValue() == MISSING_CAPABILITY) {
                return new ActionResult(refusal(capabilities(error)), ActionResult.JSON, body, true, details);
            }
            return new ActionResult(Secrets.scrub("error " + error.get("code") + ": " + error.getString("message", ""),
                    secrets), ActionResult.JSON, body, true, details);
        }
        JsonObject result = response.get("result") instanceof JsonObject object ? object
                : JsonValue.EMPTY_JSON_OBJECT;
        if ("input_required".equals(result.getString("resultType", null))) {
            JsonObject requests = result.get("inputRequests") instanceof JsonObject object ? object
                    : JsonValue.EMPTY_JSON_OBJECT;
            return new ActionResult(refusal(kinds(requests)), ActionResult.JSON,
                    Secrets.scrubTree(requests, secrets).toString(), true, details);
        }
        return switch (kind) {
            case TOOL -> tool(result, ms, details);
            case PROMPT -> {
                JsonArray messages = array(result, "messages");
                yield new ActionResult(messages.size() + " message(s) in " + ms + " ms", ActionResult.JSON,
                        messages.toString(), false, details);
            }
            case RESOURCE, TEMPLATE -> resource(result, ms, details);
        };
    }

    /**
     * The result of a call that got no JSON-RPC answer: its line, no body.
     *
     * @param summary such as {@code timed out after 55 s}
     * @param details the JSON of the request
     */
    static ActionResult transport(String summary, String details) {
        return new ActionResult(summary, null, null, true, details);
    }

    /** The JSON of {@code structuredContent} when present; else the text items joined; else the content as JSON. */
    private static ActionResult tool(JsonObject result, long ms, String details) {
        boolean isError = result.getBoolean("isError", false);
        String summary = "ok in " + ms + " ms";
        JsonValue structured = result.get("structuredContent");
        if (structured != null && structured.getValueType() != JsonValue.ValueType.NULL) {
            return new ActionResult(summary, ActionResult.JSON, structured.toString(), isError, details);
        }
        JsonArray content = array(result, "content");
        List<String> texts = new ArrayList<>();
        for (JsonValue item : content) {
            if (item instanceof JsonObject object && "text".equals(object.getString("type", null))
                    && object.get("text") instanceof JsonString text) {
                texts.add(text.getString());
            }
        }
        if (!texts.isEmpty() || content.isEmpty()) {
            return new ActionResult(summary, ActionResult.TEXT, String.join("\n", texts), isError, details);
        }
        return new ActionResult(summary, ActionResult.JSON, content.toString(), isError, details);
    }

    /** The first text content, else the contents as JSON. */
    private static ActionResult resource(JsonObject result, long ms, String details) {
        JsonArray contents = array(result, "contents");
        String summary = contents.size() + " content item(s) in " + ms + " ms";
        for (JsonValue item : contents) {
            if (item instanceof JsonObject object && object.get("text") instanceof JsonString text) {
                return new ActionResult(summary, ActionResult.TEXT, text.getString(), false, details);
            }
        }
        return new ActionResult(summary, ActionResult.JSON, contents.toString(), false, details);
    }

    /** The refusal line naming {@code kinds}, or the plain one when there is none. */
    private static String refusal(String kinds) {
        return kinds.isEmpty() ? UNSUPPORTED_UNNAMED : UNSUPPORTED.formatted(kinds);
    }

    /** {@code elicitation}: the names of the missing capabilities in the error's {@code data}, in order. */
    private static String capabilities(JsonObject error) {
        return error.get("data") instanceof JsonObject data
                && data.get("requiredCapabilities") instanceof JsonObject required
                ? String.join(", ", required.keySet()) : "";
    }

    /** {@code elicitation, sampling}: the kinds of the input requests, each once, in order. */
    private static String kinds(JsonObject requests) {
        Set<String> kinds = new LinkedHashSet<>();
        for (JsonValue request : requests.values()) {
            String method = request instanceof JsonObject object ? object.getString("method", "") : "";
            int slash = method.indexOf('/');
            kinds.add(slash > 0 ? method.substring(0, slash) : method);
        }
        return String.join(", ", kinds);
    }

    private static JsonArray array(JsonObject object, String name) {
        return object.get(name) instanceof JsonArray array ? array : JsonValue.EMPTY_JSON_ARRAY;
    }
}
