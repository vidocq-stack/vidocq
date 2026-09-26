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

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One stateless modern-era POST per call, as langchain4j-cdi's endpoint expects it. */
class McpClientTest {

    private StubMcp stub;
    private McpClient client;

    @BeforeEach
    void start() throws Exception {
        stub = StubMcp.start();
        client = new McpClient(stub.uri(), Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        client.close();
        stub.close();
    }

    private static JsonObject object(String json) {
        return Json.createReader(new StringReader(json)).readObject();
    }

    private McpClient.Exchange callTool(String name) throws McpTransportException {
        JsonObject request = client.request("tools/call", Json.createObjectBuilder().add("name", name)
                .add("arguments", object("{\"zone\":\"Europe/Paris\"}")).build());
        return client.send(request, name, Map.of());
    }

    @Test
    void aCallIsOneModernPostTheServerAcceptsWithoutSessionOrOrigin() throws Exception {
        stub.respond(request -> StubMcp.result(request, "{\"content\":[{\"type\":\"text\",\"text\":\"12:00\"}]}"));

        McpClient.Exchange exchange = callTool("current_time");

        assertNull(stub.rejected(), "McpEraDetector refused the request");
        StubMcp.Received received = stub.received().getFirst();
        assertEquals("2026-07-28", received.headers().get("MCP-Protocol-Version"));
        assertEquals("tools/call", received.headers().get("Mcp-Method"));
        assertEquals("current_time", received.headers().get("Mcp-Name"));
        assertEquals("application/json, text/event-stream", received.headers().get("Accept"));
        assertTrue(received.headers().get("Content-Type").startsWith("application/json"));
        assertFalse(received.headers().containsKey("Origin"), "no Origin: a client that is no browser");
        assertFalse(received.headers().containsKey("Mcp-Session-Id"), "stateless: no session");
        JsonObject meta = object(received.body()).getJsonObject("params").getJsonObject("_meta");
        assertEquals("2026-07-28", meta.getString("io.modelcontextprotocol/protocolVersion"));
        assertEquals("vidocq-dev-console",
                meta.getJsonObject("io.modelcontextprotocol/clientInfo").getString("name"));
        assertEquals(object("{\"elicitation\":{},\"sampling\":{},\"roots\":{}}"),
                meta.getJsonObject("io.modelcontextprotocol/clientCapabilities"));
        assertEquals("12:00", exchange.response().getJsonObject("result").getJsonArray("content")
                .getJsonObject(0).getString("text"));
        assertEquals(200, exchange.status());
        assertTrue(exchange.events().isEmpty());
    }

    @Test
    void aNameThatIsNotPlainAsciiTravelsInBase64() throws Exception {
        callTool("météo");

        assertNull(stub.rejected(), "McpEraDetector refused the request");
        assertEquals("=?base64?" + Base64.getEncoder().encodeToString("météo".getBytes(StandardCharsets.UTF_8))
                + "?=", stub.received().getFirst().headers().get("Mcp-Name"));
        assertEquals("plain", McpClient.headerValue("plain"));
        assertTrue(McpClient.headerValue(" padded").startsWith("=?base64?"), "HTTP would strip the space");
    }

    @Test
    void aDesignatedArgumentIsMirroredIntoItsHeader() throws Exception {
        JsonObject arguments = object("{\"tenant\":\"acme\",\"count\":3,\"flag\":true,\"nested\":{},\"none\":null}");

        Map<String, String> headers = McpClient.paramHeaders(Map.of("tenant", "Tenant-Id", "count", "Count",
                "flag", "Flag", "nested", "Nested", "none", "None", "missing", "Missing"), arguments);

        assertEquals(Map.of("Tenant-Id", "acme", "Count", "3", "Flag", "true"), headers);
        JsonObject request = client.request("tools/call", Json.createObjectBuilder().add("name", "t")
                .add("arguments", arguments).build());
        client.send(request, "t", headers);
        assertEquals("acme", stub.received().getFirst().headers().get("Mcp-Param-Tenant-Id"));
    }

    @Test
    void anSseAnswerKeepsTheFinalResponseAndTheEventsBeforeIt() throws Exception {
        stub.respond(request -> new StubMcp.Answer(200, "text/event-stream",
                "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n"
                        + "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":" + request.get("id")
                        + ",\"result\":{\"content\":[]}}\n\n", 0));

        McpClient.Exchange exchange = callTool("current_time");

        assertEquals(2, exchange.events().size());
        assertTrue(exchange.response().containsKey("result"), exchange.response().toString());
    }

    @Test
    void aJsonRpcErrorOnA400IsStillAnAnswer() throws Exception {
        stub.respond(request -> StubMcp.error(request, 400, -32602, "Invalid params: zone"));

        McpClient.Exchange exchange = callTool("current_time");

        assertEquals(400, exchange.status());
        assertEquals(-32602, exchange.response().getJsonObject("error").getInt("code"));
    }

    @Test
    void anHttpStatusWithoutAJsonRpcBodyIsATransportFailure() {
        stub.respond(request -> new StubMcp.Answer(502, "text/plain", "Bad gateway", 0));

        McpTransportException failure = assertThrows(McpTransportException.class, () -> callTool("current_time"));

        assertEquals("HTTP 502", failure.getMessage());
    }

    @Test
    void aClosedPortIsUnreachable() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        URI closed = URI.create("http://127.0.0.1:" + port + "/mcp");
        try (McpClient nowhere = new McpClient(closed, Duration.ofSeconds(5))) {
            JsonObject request = nowhere.request("tools/call", Json.createObjectBuilder().add("name", "t")
                    .add("arguments", Json.createObjectBuilder()).build());

            McpTransportException failure = assertThrows(McpTransportException.class,
                    () -> nowhere.send(request, "t", Map.of()));

            assertEquals("/mcp unreachable at " + closed, failure.getMessage());
        }
    }

    @Test
    void aSlowAnswerTimesOut() {
        client.close();
        client = new McpClient(stub.uri(), Duration.ofSeconds(1));
        stub.respond(request -> new StubMcp.Answer(200, "application/json", "{}", 2_500));

        McpTransportException failure = assertThrows(McpTransportException.class, () -> callTool("current_time"));

        assertEquals("timed out after 1 s", failure.getMessage());
    }

    @Test
    void aResponseBodyOverOneMebibyteIsATransportFailure() throws Exception {
        stub.respond(request -> new StubMcp.Answer(200, "application/json", "a".repeat(1_048_576 + 1), 0));

        McpTransportException failure = assertThrows(McpTransportException.class, () -> callTool("current_time"));

        assertEquals("/mcp answered more than 1 MiB", failure.getMessage());
    }

    @Test
    void anEndlessSlowStreamTimesOutWithoutHanging() throws Exception {
        client.close();
        client = new McpClient(stub.uri(), Duration.ofSeconds(1));
        stub.respondWithEndlessSlowStream(50);
        long start = System.nanoTime();

        McpTransportException failure = assertThrows(McpTransportException.class, () -> callTool("current_time"));

        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertEquals("timed out after 1 s", failure.getMessage());
        assertTrue(elapsedMillis < 10_000, "the call must not hang past the timeout: took " + elapsedMillis + " ms");
    }

    @Test
    void aCallAfterCloseFailsCleanly() throws Exception {
        client.close();

        McpTransportException failure = assertThrows(McpTransportException.class, () -> callTool("current_time"));

        assertEquals("/mcp unreachable at " + stub.uri(), failure.getMessage());
    }
}
