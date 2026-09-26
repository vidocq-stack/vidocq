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

import io.vidocq.runtime.spi.devconsole.PanelAction;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The inspector's actions, called against a stub {@code /mcp} that checks each request as the server does. */
class McpInspectorTest {

    private StubMcp stub;

    @BeforeEach
    void start() throws Exception {
        stub = StubMcp.start();
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private static JsonObject object(String json) {
        return Json.createReader(new StringReader(json)).readObject();
    }

    private McpInspector inspector() {
        McpCatalogue catalogue = McpCatalogue.of(List.of(InspectorFixtures.tool("currentTime")),
                List.of(InspectorFixtures.prompt("planMeeting")), List.of(InspectorFixtures.resource("utc")),
                List.of(InspectorFixtures.template("timeInZone")));
        return McpInspector.of(catalogue, stub.uri(), Duration.ofSeconds(5));
    }

    private static PanelAction action(McpInspector inspector, String id) {
        return inspector.actions().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void eachItemIsAnActionOfItsGroupWithItsJsonArgument() {
        McpInspector inspector = inspector();

        assertEquals(List.of("tool.current-time", "prompt.plan-meeting", "res.860cd4a5", "tpl.72bba68d"),
                inspector.actions().stream().map(PanelAction::id).toList());
        PanelAction tool = action(inspector, "tool.current-time");
        assertEquals("Tools", tool.group());
        assertEquals("Current time", tool.label());
        assertEquals("The current time in a zone.", tool.description());
        assertNull(tool.confirmation(), "read-only");
        assertEquals("arguments", tool.arguments().getFirst().name());
        assertTrue(tool.arguments().getFirst().schema().contains("\"zone\""));
        assertEquals(List.of(), action(inspector, "res.860cd4a5").arguments(), "a fixed resource takes none");
        assertEquals("variables", action(inspector, "tpl.72bba68d").arguments().getFirst().name());
        assertEquals("Resources", action(inspector, "tpl.72bba68d").group());
    }

    @Test
    void aToolCallThatSucceedsShowsItsTextAndTheExchange() {
        stub.respond(request -> StubMcp.result(request,
                "{\"content\":[{\"type\":\"text\",\"text\":\"12:00 Paris\"}],\"isError\":false}"));

        PanelAction.ActionResult result = action(inspector(), "tool.current-time").call()
                .apply(Map.of("arguments", "{\"zone\":\"Europe/Paris\"}"));

        assertNull(stub.rejected(), "McpEraDetector refused the request");
        assertTrue(result.summary().matches("ok in [0-9]+ ms"), result.summary());
        assertEquals("12:00 Paris", result.body());
        assertFalse(result.error());
        JsonObject details = object(result.details());
        assertEquals("tools/call", details.getJsonObject("request").getString("method"));
        assertEquals(object("{\"zone\":\"Europe/Paris\"}"),
                details.getJsonObject("request").getJsonObject("params").getJsonObject("arguments"));
        assertEquals(200, details.getInt("status"));
        assertTrue(details.getJsonObject("response").containsKey("result"));
    }

    @Test
    void aToolWithIsErrorAJsonRpcErrorAndAnInputRequestAreErrors() {
        PanelAction tool = action(inspector(), "tool.current-time");
        Map<String, String> paris = Map.of("arguments", "{\"zone\":\"Europe/Paris\"}");

        stub.respond(request -> StubMcp.result(request, "{\"content\":[],\"isError\":true}"));
        assertTrue(tool.call().apply(paris).error());

        stub.respond(request -> StubMcp.error(request, 400, -32602, "Invalid params: zone"));
        assertEquals("error -32602: Invalid params: zone", tool.call().apply(paris).summary());

        stub.respond(request -> StubMcp.result(request, "{\"resultType\":\"input_required\",\"inputRequests\":"
                + "{\"input-1\":{\"method\":\"roots/list\",\"params\":{}}},\"requestState\":\"s\"}"));
        assertEquals("this tool asks the client for input (roots): not supported by the dev console inspector yet",
                tool.call().apply(paris).summary());
    }

    @Test
    void anUnreachableMcpIsATransportLineWithTheRequestInTheDetails() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        URI closed = URI.create("http://127.0.0.1:" + port + "/mcp");
        McpInspector nowhere = McpInspector.of(McpCatalogue.of(List.of(InspectorFixtures.tool("currentTime")),
                List.of(), List.of(), List.of()), closed, Duration.ofSeconds(5));

        PanelAction.ActionResult result = action(nowhere, "tool.current-time").call()
                .apply(Map.of("arguments", "{\"zone\":\"UTC\"}"));

        assertEquals("/mcp unreachable at " + closed, result.summary());
        assertTrue(result.error());
        assertNull(result.body());
        assertTrue(object(result.details()).containsKey("request"));
    }

    @Test
    void aBodyPastTheLimitIsTruncated() {
        stub.respond(request -> StubMcp.result(request,
                "{\"content\":[{\"type\":\"text\",\"text\":\"" + "a".repeat(300_000) + "\"}]}"));

        PanelAction.ActionResult result = action(inspector(), "tool.current-time").call()
                .apply(Map.of("arguments", "{\"zone\":\"UTC\"}"));

        assertEquals(PanelAction.ActionResult.MAX_CONTENT, result.body().length());
        assertTrue(result.body().endsWith("… truncated at 256 KiB"));
    }

    @Test
    void aPromptGetAResourceReadAndATemplateRead() {
        stub.respond(request -> switch (request.getString("method")) {
            case "prompts/get" -> StubMcp.result(request, "{\"messages\":[{\"role\":\"user\","
                    + "\"content\":{\"type\":\"text\",\"text\":\"Plan it\"}}]}");
            default -> StubMcp.result(request, "{\"contents\":[{\"uri\":\"u\",\"text\":\"UTC\"}]}");
        });
        McpInspector inspector = inspector();

        PanelAction.ActionResult prompt = action(inspector, "prompt.plan-meeting").call()
                .apply(Map.of("arguments", "{\"zones\":\"Europe/Paris\"}"));
        PanelAction.ActionResult resource = action(inspector, "res.860cd4a5").call().apply(Map.of());
        PanelAction.ActionResult template = action(inspector, "tpl.72bba68d").call()
                .apply(Map.of("variables", "{\"zone\":\"Europe/Paris\"}"));

        assertNull(stub.rejected(), "McpEraDetector refused a request");
        assertTrue(prompt.summary().matches("1 message\\(s\\) in [0-9]+ ms"), prompt.summary());
        assertTrue(resource.summary().matches("1 content item\\(s\\) in [0-9]+ ms"), resource.summary());
        assertEquals("UTC", template.body());
        List<StubMcp.Received> received = stub.received();
        assertEquals("plan_meeting", received.get(0).headers().get("Mcp-Name"));
        assertEquals("time://utc", received.get(1).headers().get("Mcp-Name"));
        assertEquals("time://zone/Europe%2FParis", received.get(2).headers().get("Mcp-Name"));
        assertEquals("time://zone/Europe%2FParis",
                object(received.get(2).body()).getJsonObject("params").getString("uri"));
    }

    @Test
    void aLoopbackUrlIsPreferredAndNoneMeansNoAddress() {
        assertEquals(Optional.of(URI.create("http://127.0.0.1:18090/mcp")),
                McpInspector.preferred(List.of("http://192.168.1.5:18090/mcp", "http://127.0.0.1:18090/mcp")));
        assertEquals(Optional.of(URI.create("http://192.168.1.5:18090/mcp")),
                McpInspector.preferred(List.of("http://192.168.1.5:18090/mcp")));
        assertEquals(Optional.empty(), McpInspector.preferred(List.of()));
    }

    /**
     * The ruling behind {@link McpClient#close()} using {@code HttpClient.shutdownNow()}: closing the inspector
     * while a call is in flight aborts it promptly, ending it as a transport line rather than blocking the caller
     * of {@link McpInspector#close()} until the call's own timeout. {@link McpLivePanelTest} covers the same
     * behavior through {@code McpLivePanel.stop()}, closer to how a dev reload triggers it.
     */
    @Test
    void closingWhileACallIsInFlightAbortsItPromptlyWithATransportLine() throws Exception {
        stub.respondWithEndlessSlowStream(20);
        McpInspector inspector = inspector();
        List<PanelAction.ActionResult> captured = new CopyOnWriteArrayList<>();
        Thread caller = new Thread(() -> captured.add(action(inspector, "tool.current-time").call()
                .apply(Map.of("arguments", "{\"zone\":\"UTC\"}"))));
        caller.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (stub.received().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(stub.received().isEmpty(), "the call never reached the stub");

        long start = System.nanoTime();
        inspector.close();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis < 5_000, "close() took " + elapsedMillis + " ms while a call was in flight");
        caller.join(Duration.ofSeconds(10).toMillis());
        assertFalse(caller.isAlive(), "the in-flight call never ended");
        PanelAction.ActionResult result = captured.get(0);
        assertTrue(result.error(), "an aborted in-flight call must end as an error");
        assertNull(result.body(), "a transport line has no body");
    }

    @Test
    void aSecretIsMaskedInTheHistoryTheDetailsAndTheSummaryTheConsoleLogs() {
        stub.respond(request -> StubMcp.error(request, 200, -32602, "Invalid key hunter22 for query"));
        McpInspector inspector = McpInspector.of(McpCatalogue.of(List.of(InspectorFixtures.tool("search")),
                List.of(), List.of(), List.of()), stub.uri(), Duration.ofSeconds(5));

        PanelAction.ActionResult result = action(inspector, "tool.search").call()
                .apply(Map.of("arguments", "{\"query\":\"cats\",\"apiKey\":\"hunter22\"}"));

        assertTrue(stub.received().getFirst().body().contains("hunter22"), "the server receives the real value");
        assertEquals("error -32602: Invalid key *** for query", result.summary(), "the console's INFO line");
        assertFalse(result.details().contains("hunter22"), result.details());
        assertTrue(result.details().contains("\"apiKey\":\"***\""), result.details());
        CallHistory.Call call = inspector.history().calls().getFirst();
        assertEquals("{\"query\":\"cats\",\"apiKey\":\"***\"}", call.arguments());
        assertEquals("tool.search {\"arguments\":{\"query\":\"cats\",\"apiKey\":\"***\"}}", call.replay());
        assertFalse(call.details().contains("hunter22"));
        assertTrue(call.error());
    }

    @Test
    void everyCallIsRecordedWithWhatReplaysIt() {
        stub.respond(request -> StubMcp.result(request, "{\"contents\":[{\"uri\":\"time://utc\",\"text\":\"UTC\"}]}"));
        McpInspector inspector = inspector();

        action(inspector, "res.860cd4a5").call().apply(Map.of());
        action(inspector, "tpl.72bba68d").call().apply(Map.of("variables", "{\"zone\":\"UTC\"}"));

        List<CallHistory.Call> calls = inspector.history().calls();
        assertEquals("tpl.72bba68d {\"variables\":{\"zone\":\"UTC\"}}", calls.get(0).replay());
        assertEquals("res.860cd4a5 {}", calls.get(1).replay());
        assertEquals("time://utc", calls.get(1).label());
        RecordingSample sample = new RecordingSample();
        inspector.sample(sample);
        assertEquals("table", sample.kind("calls"));
    }

    /**
     * Fix round 1: a secret with a quote, a backslash, a tab or a non-ASCII character is escaped by the JSON-P
     * provider when the exchange is serialized; scrubbing must happen on the decoded value, in the tree, not on the
     * already-serialized text, or the escaped form of the very same secret survives.
     */
    @Test
    void aSecretWithSpecialCharactersNeverLeaksInItsEscapedFormEither() {
        for (String secret : List.of("sk-test\"quote", "a\\b\\c-1", "tab\there", "clé-secrète")) {
            String arguments = Json.createObjectBuilder().add("query", "cats").add("apiKey", secret).build()
                    .toString();
            McpInspector inspector = McpInspector.of(McpCatalogue.of(List.of(InspectorFixtures.tool("search")),
                    List.of(), List.of(), List.of()), stub.uri(), Duration.ofSeconds(5));

            stub.respond(request -> StubMcp.error(request, 200, -32602, "Invalid key " + secret + " for query"));
            PanelAction.ActionResult errorResult = action(inspector, "tool.search").call()
                    .apply(Map.of("arguments", arguments));

            assertEquals(secret, object(stub.received().getLast().body()).getJsonObject("params")
                    .getJsonObject("arguments").getString("apiKey"), "the server receives the real value");
            assertFalse(errorResult.summary().contains(secret), secret + " in " + errorResult.summary());
            assertFalse(errorResult.details().contains(secret), secret + " in " + errorResult.details());
            CallHistory.Call errorCall = inspector.history().calls().getFirst();
            assertFalse(errorCall.details().contains(secret), secret + " in " + errorCall.details());
            assertFalse(errorCall.replay().contains(secret), secret + " in " + errorCall.replay());

            stub.respond(request -> StubMcp.result(request, Json.createObjectBuilder()
                    .add("content", Json.createArrayBuilder().add(Json.createObjectBuilder().add("type", "text")
                            .add("text", "key was " + secret)))
                    .build().toString()));
            PanelAction.ActionResult textResult = action(inspector, "tool.search").call()
                    .apply(Map.of("arguments", arguments));

            assertFalse(textResult.summary().contains(secret), secret + " in " + textResult.summary());
            assertFalse(textResult.details().contains(secret), secret + " in " + textResult.details());
            CallHistory.Call textCall = inspector.history().calls().getFirst();
            assertFalse(textCall.details().contains(secret), secret + " in " + textCall.details());
            assertFalse(textCall.replay().contains(secret), secret + " in " + textCall.replay());
        }
    }
}
