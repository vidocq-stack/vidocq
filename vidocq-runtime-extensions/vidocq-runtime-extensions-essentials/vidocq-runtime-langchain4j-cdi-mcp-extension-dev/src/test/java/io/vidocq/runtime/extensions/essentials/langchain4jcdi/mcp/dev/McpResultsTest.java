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
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec §3.3, one outcome per test. */
class McpResultsTest {

    private static final JsonObject REQUEST = object("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"}");

    private static JsonObject object(String json) {
        return Json.createReader(new StringReader(json)).readObject();
    }

    private static McpClient.Exchange answered(String response) {
        return new McpClient.Exchange(REQUEST, object(response), List.of(), 200, 12);
    }

    private static ActionResult of(McpCatalogue.Kind kind, String result) {
        return McpResults.of(kind, answered("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + result + "}"), "{}");
    }

    @Test
    void aToolResultShowsItsTextItemsJoined() {
        ActionResult result = of(McpCatalogue.Kind.TOOL, "{\"content\":[{\"type\":\"text\",\"text\":\"a\"},"
                + "{\"type\":\"image\",\"data\":\"x\"},{\"type\":\"text\",\"text\":\"b\"}],\"isError\":false}");

        assertEquals("ok in 12 ms", result.summary());
        assertEquals("text/plain", result.contentType());
        assertEquals("a\nb", result.body());
        assertFalse(result.error());
        assertEquals("{}", result.details());
    }

    @Test
    void aToolResultWithStructuredContentShowsItsJson() {
        ActionResult result = of(McpCatalogue.Kind.TOOL,
                "{\"content\":[{\"type\":\"text\",\"text\":\"21\"}],\"structuredContent\":{\"temp\":21}}");

        assertEquals("application/json", result.contentType());
        assertEquals("{\"temp\":21}", result.body());
    }

    @Test
    void aToolResultWithOnlyNonTextContentShowsTheContentAsJson() {
        ActionResult result = of(McpCatalogue.Kind.TOOL, "{\"content\":[{\"type\":\"image\",\"data\":\"x\"}]}");

        assertEquals("application/json", result.contentType());
        assertEquals("[{\"type\":\"image\",\"data\":\"x\"}]", result.body());
    }

    @Test
    void isErrorFlagsTheResult() {
        assertTrue(of(McpCatalogue.Kind.TOOL, "{\"content\":[],\"isError\":true}").error());
    }

    @Test
    void aPromptShowsItsMessages() {
        ActionResult result = of(McpCatalogue.Kind.PROMPT,
                "{\"messages\":[{\"role\":\"user\",\"content\":{\"type\":\"text\",\"text\":\"hi\"}}]}");

        assertEquals("1 message(s) in 12 ms", result.summary());
        assertEquals("application/json", result.contentType());
        assertTrue(result.body().startsWith("[{\"role\":\"user\""), result.body());
    }

    @Test
    void aResourceShowsItsFirstTextElseItsContents() {
        ActionResult text = of(McpCatalogue.Kind.RESOURCE,
                "{\"contents\":[{\"uri\":\"time://utc\",\"text\":\"UTC\"},{\"uri\":\"b\",\"text\":\"B\"}]}");
        ActionResult blob = of(McpCatalogue.Kind.TEMPLATE, "{\"contents\":[{\"uri\":\"x\",\"blob\":\"AA==\"}]}");

        assertEquals("2 content item(s) in 12 ms", text.summary());
        assertEquals("UTC", text.body());
        assertEquals("application/json", blob.contentType());
        assertEquals("[{\"uri\":\"x\",\"blob\":\"AA==\"}]", blob.body());
    }

    @Test
    void aJsonRpcErrorShowsItsCodeAndMessage() {
        ActionResult result = McpResults.of(McpCatalogue.Kind.TOOL,
                answered("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602,\"message\":\"Invalid params\"}}"),
                "{}");

        assertEquals("error -32602: Invalid params", result.summary());
        assertTrue(result.error());
        assertEquals("{\"code\":-32602,\"message\":\"Invalid params\"}", result.body());
    }

    @Test
    void anInputRequestIsRefusedWithItsKinds() {
        ActionResult result = of(McpCatalogue.Kind.TOOL, "{\"resultType\":\"input_required\",\"inputRequests\":{"
                + "\"input-1\":{\"method\":\"elicitation/create\",\"params\":{}},"
                + "\"input-2\":{\"method\":\"sampling/createMessage\",\"params\":{}},"
                + "\"input-3\":{\"method\":\"elicitation/create\",\"params\":{}}},\"requestState\":\"s\"}");

        assertEquals("this tool asks the client for input (elicitation, sampling): not supported by the dev console "
                + "inspector yet", result.summary());
        assertTrue(result.error());
        assertTrue(result.body().contains("\"input-2\""), result.body());
    }

    @Test
    void aTransportFailureHasNoBody() {
        ActionResult result = McpResults.transport("timed out after 55 s", "{\"request\":{}}");

        assertEquals("timed out after 55 s", result.summary());
        assertTrue(result.error());
        assertNull(result.body());
        assertEquals("{\"request\":{}}", result.details());
    }
}
