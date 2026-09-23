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

import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.runtime.spi.report.ReportLine;
import io.vidocq.runtime.spi.report.ReportSection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dev MCP of a dev boot: the protocol subset it speaks, each of its five tools, what it refuses, and that no
 * tool ever answers the token of the boot or runs an action.
 */
class DevMcpTest {

    private static final String HOST = "127.0.0.1:8888";
    private static final String TOKEN = "3f".repeat(32);
    private static final long NOW = 1_789_740_602_114L;

    private final TestPanels.ActionPanel actionPanel = new TestPanels.ActionPanel();
    private final TestPanels.PoolPanel poolPanel = new TestPanels.PoolPanel();
    private final List<String> served = new ArrayList<>();
    private final Handler page = request -> {
        served.add(request.pathInfo());
        return Response.builder().status(StatusCode.OK).body("<!doctype html>").build();
    };
    private LogRecords log;
    private ConsoleHandler handler;

    @BeforeEach
    void aDevBoot() {
        log = new LogRecords(DevConsoleExtension.LOGGER_NAME);
        FakeReportView written = FakeReportView.of(poolPanel, actionPanel);
        List<ReportSection> sections = new ArrayList<>(written.sections());
        sections.add(new ReportSection("rest", "REST (Cassini)", "1 resource class, 2 routes", List.of(
                new ReportLine(null, List.of("1 resource class, 2 routes")),
                new ReportLine(null, List.of("POST", "http://127.0.0.1:18090/mcp", "McpEndpoint#handlePost"), null),
                new ReportLine(null, List.of("GET", "http://127.0.0.1:18090/time", "TimeResource#now"),
                        "http://127.0.0.1:18090/time"),
                new ReportLine("resources", List.of("com.acme.TimeResource")))));
        FakeReportView report = new FakeReportView(written.launchMode(), written.launchReason(), written.anomalies(),
                List.copyOf(sections), written.detailedText(), written.contributors());
        ConsoleActions actions = new ConsoleActions(TOKEN, () -> NOW, Duration.ofSeconds(5));
        Snapshot snapshot = new Snapshot("7f3a91c04be2d810", "0.4.0-TEST", () -> Optional.of(report), List.of(),
                () -> NOW, actions);
        handler = new ConsoleHandler(new HostGuard("127.0.0.1"), () -> 8888, snapshot, page,
                new DevMcp(snapshot, "0.4.0-TEST"));
    }

    @AfterEach
    void release() {
        actionPanel.release.countDown();
        log.close();
    }

    /** The headers an MCP client that is no browser sends: no {@code Origin}. */
    private static Map<String, String> fromAClient() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json, text/event-stream");
        return headers;
    }

    private static Map<String, String> fromAClientBut(String name, String value) {
        Map<String, String> headers = fromAClient();
        headers.put(name, value);
        return headers;
    }

    private Response post(Map<String, String> headers, String body) throws Exception {
        return handler.handle(FakeRequest.post("/mcp", HOST, headers, body));
    }

    private Response rpc(String method, String params) throws Exception {
        return post(fromAClient(), "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\""
                + (params == null ? "" : ",\"params\":" + params) + "}");
    }

    private static String body(Response response) throws IOException {
        try (InputStream in = response.body().asInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The {@code result} of a successful answer, checked to be JSON-RPC 2.0 for id 7. */
    private static Map<?, ?> result(Response response) throws IOException {
        String text = body(response);
        assertEquals(200, response.status().code(), text);
        assertEquals("application/json; charset=utf-8", response.headers().firstOrNull("Content-Type"));
        Map<String, Object> answer = Json.object(text);
        assertEquals("2.0", answer.get("jsonrpc"));
        assertEquals(7L, answer.get("id"));
        assertNull(answer.get("error"), text);
        return (Map<?, ?>) answer.get("result");
    }

    /** The {@code error} of an answer, with its HTTP status. */
    private static Map<?, ?> error(Response response, int status) throws IOException {
        String text = body(response);
        assertEquals(status, response.status().code(), text);
        Map<?, ?> error = (Map<?, ?>) Json.object(text).get("error");
        assertTrue(error != null, text);
        return error;
    }

    /** Calls {@code tool} and returns its structured content, after checking its text content says the same. */
    private Map<?, ?> call(String tool, String arguments) throws Exception {
        Map<?, ?> result = result(rpc("tools/call", "{\"name\":\"" + tool + "\""
                + (arguments == null ? "" : ",\"arguments\":" + arguments) + "}"));
        assertEquals(false, result.get("isError"));
        Map<?, ?> structured = (Map<?, ?>) result.get("structuredContent");
        List<?> content = (List<?>) result.get("content");
        assertEquals(1, content.size());
        Map<?, ?> text = (Map<?, ?>) content.get(0);
        assertEquals("text", text.get("type"));
        assertEquals(structured, Json.object((String) text.get("text")), "the text is the same object, indented");
        assertTrue(((String) text.get("text")).contains("\n  \""), "indented for a reader");
        return structured;
    }

    @Test
    void initializeAnswersTheVersionAskedWhenItSpeaksIt() throws Exception {
        for (String version : List.of("2025-03-26", "2025-06-18", "2025-11-25")) {
            Map<?, ?> result = result(rpc("initialize", "{\"protocolVersion\":\"" + version + "\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"inspector\",\"version\":\"1\"}}"));

            assertEquals(version, result.get("protocolVersion"));
            assertEquals(Map.of("tools", Map.of("listChanged", false)), result.get("capabilities"));
            assertEquals(Map.of("name", "vidocq-dev-console", "version", "0.4.0-TEST"), result.get("serverInfo"));
        }
    }

    @Test
    void initializeAnswersItsNewestVersionForOneItDoesNotSpeak() throws Exception {
        Map<?, ?> result = result(rpc("initialize", "{\"protocolVersion\":\"2031-01-01\"}"));

        assertEquals(DevMcp.PROTOCOL_VERSIONS.get(0), result.get("protocolVersion"));
    }

    @Test
    void theAnswersOpenNoSessionAndNoStream() throws Exception {
        Response response = rpc("initialize", "{\"protocolVersion\":\"2025-06-18\"}");

        assertNull(response.headers().firstOrNull("Mcp-Session-Id"), "stateless: no session");
        assertTrue(response.headers().firstOrNull("Content-Type").startsWith("application/json"), "never SSE");
        assertEquals("no-store", response.headers().firstOrNull("Cache-Control"));
        assertEquals("nosniff", response.headers().firstOrNull("X-Content-Type-Options"));
        assertTrue(response.headers().all("Access-Control-Allow-Origin").isEmpty(), "no CORS");
    }

    @Test
    void aNotificationOrAResponseIsAcceptedWithoutABody() throws Exception {
        Response initialized = post(fromAClient(), "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        Response cancelled = post(fromAClient(), "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\","
                + "\"params\":{\"requestId\":3}}");
        Response answer = post(fromAClient(), "{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{}}");

        for (Response response : List.of(initialized, cancelled, answer)) {
            assertEquals(202, response.status().code());
            assertEquals("", body(response));
        }
    }

    @Test
    void pingAnswersAnEmptyObject() throws Exception {
        assertEquals(Map.of(), result(rpc("ping", null)));
    }

    @Test
    void toolsListHasTheFiveReadOnlyTools() throws Exception {
        List<?> tools = (List<?>) result(rpc("tools/list", "{}")).get("tools");

        assertEquals(List.of("vidocq_report", "vidocq_panels", "vidocq_panel", "vidocq_anomalies", "vidocq_routes"),
                tools.stream().map(tool -> ((Map<?, ?>) tool).get("name")).toList());
        for (Object each : tools) {
            Map<?, ?> tool = (Map<?, ?>) each;
            assertTrue(((String) tool.get("description")).contains("Read-only"), tool.toString());
            assertEquals(true, ((Map<?, ?>) tool.get("annotations")).get("readOnlyHint"));
            assertEquals("object", ((Map<?, ?>) tool.get("inputSchema")).get("type"));
        }
        Map<?, ?> panel = (Map<?, ?>) ((Map<?, ?>) tools.get(2)).get("inputSchema");
        assertEquals(List.of("id"), panel.get("required"));
        assertEquals(List.of("acme-pool", "acme-actions"),
                ((Map<?, ?>) ((Map<?, ?>) panel.get("properties")).get("id")).get("enum"),
                "the ids of the panels the console shows now");
    }

    @Test
    void theReportIsTheStartupReportThePageShows() throws Exception {
        Map<?, ?> report = call("vidocq_report", null);

        assertEquals("ready", report.get("state"));
        Map<?, ?> console = (Map<?, ?>) report.get("console");
        assertEquals("0.4.0-TEST", console.get("vidocq"));
        assertFalse(console.containsKey("actionToken"), "never the token");
        Map<?, ?> startup = (Map<?, ?>) report.get("startup");
        assertEquals("dev", startup.get("launchMode"));
        assertEquals(List.of("launch", "layer", "rest"),
                ((List<?>) startup.get("sections")).stream().map(s -> ((Map<?, ?>) s).get("id")).toList(),
                "the sections that are no panel");
        assertTrue(((String) startup.get("text")).startsWith("Vidocq startup report"));
    }

    @Test
    void thePanelsAreListedWithTheirSummary() throws Exception {
        List<?> panels = (List<?>) call("vidocq_panels", "{}").get("panels");

        assertEquals(List.of(
                Map.of("id", "acme-pool", "title", "Acme pools", "live", true, "summary", "1 pool, 8 connections max"),
                Map.of("id", "acme-actions", "title", "Acme actions", "live", true, "summary", "things to do")),
                panels);
    }

    @Test
    void aPanelIsItsBootFactsAndItsLatestSampleWithoutActionsOrHistory() throws Exception {
        Map<?, ?> panel = (Map<?, ?>) call("vidocq_panel", "{\"id\":\"acme-pool\"}").get("panel");

        assertEquals("acme-pool", panel.get("id"));
        assertEquals(List.of(List.of("main", "jdbc:h2:mem:acme"), List.of("main password", "configured")),
                panel.get("lines"), "a secret is masked, as on the page");
        Map<?, ?> sample = (Map<?, ?>) panel.get("sample");
        assertTrue(sample.toString().contains("active"), sample.toString());
        assertFalse(panel.containsKey("history"));
        assertFalse(panel.containsKey("actions"));

        Map<?, ?> withActions = (Map<?, ?>) call("vidocq_panel", "{\"id\":\"acme-actions\"}").get("panel");
        assertFalse(withActions.containsKey("actions"), "the tools never offer an action");
        assertEquals(List.of(), actionPanel.runs, "and never run one");
    }

    @Test
    void anUnknownPanelIsAToolErrorThatNamesThePanels() throws Exception {
        Map<?, ?> result = result(rpc("tools/call", "{\"name\":\"vidocq_panel\",\"arguments\":{\"id\":\"nope\"}}"));

        assertEquals(true, result.get("isError"));
        String text = (String) ((Map<?, ?>) ((List<?>) result.get("content")).get(0)).get("text");
        assertEquals("No panel 'nope'. The panels are [acme-pool, acme-actions].", text);
    }

    @Test
    void theAnomaliesAreTheReportsAnomalies() throws Exception {
        List<?> anomalies = (List<?>) call("vidocq_anomalies", null).get("anomalies");

        assertEquals(1, anomalies.size());
        assertEquals("VIDOCQ-CFG-003", ((Map<?, ?>) anomalies.get(0)).get("code"));
    }

    @Test
    void theRoutesAreTheRestRoutesWithAbsoluteUrls() throws Exception {
        List<?> routes = (List<?>) call("vidocq_routes", null).get("routes");

        assertEquals(List.of(
                Map.of("method", "POST", "url", "http://127.0.0.1:18090/mcp", "handler", "McpEndpoint#handlePost"),
                Map.of("method", "GET", "url", "http://127.0.0.1:18090/time", "handler", "TimeResource#now")),
                routes);
    }

    @Test
    void whileTheBootRunsTheToolsSayBooting() throws Exception {
        Snapshot booting = new Snapshot("7f3a91c04be2d810", "0.4.0-TEST", Optional::empty, List.of(), () -> NOW,
                new ConsoleActions(TOKEN, () -> NOW, Duration.ofSeconds(5)));
        handler = new ConsoleHandler(new HostGuard("127.0.0.1"), () -> 8888, booting, page,
                new DevMcp(booting, "0.4.0-TEST"));

        Map<?, ?> routes = call("vidocq_routes", null);
        assertEquals("booting", routes.get("state"));
        assertEquals(List.of(), routes.get("routes"));
        assertEquals(List.of(), call("vidocq_anomalies", null).get("anomalies"));
    }

    @Test
    void noAnswerEverHoldsTheToken() throws Exception {
        StringBuilder all = new StringBuilder();
        all.append(body(rpc("initialize", "{\"protocolVersion\":\"2025-06-18\"}")));
        all.append(body(rpc("tools/list", null)));
        for (String tool : List.of("vidocq_report", "vidocq_panels", "vidocq_anomalies", "vidocq_routes")) {
            all.append(body(rpc("tools/call", "{\"name\":\"" + tool + "\"}")));
        }
        all.append(body(rpc("tools/call", "{\"name\":\"vidocq_panel\",\"arguments\":{\"id\":\"acme-actions\"}}")));

        assertFalse(all.toString().contains(TOKEN), "the token of the boot is the page's only");
        assertFalse(all.toString().contains("actionToken"));
        assertFalse(all.toString().contains("set-level"), "no action is described either");
    }

    @Test
    void anUnknownMethodOrToolAndBadParamsAreJsonRpcErrors() throws Exception {
        assertEquals(-32601L, error(rpc("resources/list", null), 200).get("code"));
        assertEquals(-32602L, error(rpc("tools/call", "{\"name\":\"vidocq_shutdown\"}"), 200).get("code"));
        assertEquals(-32602L, error(rpc("tools/call", "{}"), 200).get("code"));
        assertEquals(-32602L, error(rpc("tools/call", "[1]"), 200).get("code"));
        assertEquals(-32602L, error(rpc("tools/call", "{\"name\":\"vidocq_panel\"}"), 200).get("code"));
        assertEquals(-32602L, error(rpc("tools/call", "{\"name\":\"vidocq_panel\",\"arguments\":[]}"), 200)
                .get("code"));
        assertEquals(-32602L, error(rpc("initialize", "{}"), 200).get("code"));
    }

    @Test
    void aBodyThatIsNotOneRequestIsRefused() throws Exception {
        assertEquals(-32700L, error(post(fromAClient(), "{\"jsonrpc\":"), 400).get("code"));
        assertEquals(-32700L, error(post(fromAClient(), "[".repeat(40) + "]".repeat(40)), 400).get("code"),
                "nested past the limit");
        assertEquals(-32600L, error(post(fromAClient(), "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]"), 400)
                .get("code"), "no batch");
        assertEquals(-32600L, error(post(fromAClient(), "{\"id\":1,\"method\":\"ping\"}"), 400).get("code"));
        assertEquals(-32600L, error(post(fromAClient(), "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}"),
                400).get("code"));
        assertEquals(-32600L, error(post(fromAClient(), "\"ping\""), 400).get("code"));
    }

    @Test
    void aBodyPastTheLimitIs413() throws Exception {
        Response response = post(fromAClient(), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{\"x\":\""
                + "a".repeat(DevMcp.MAX_BODY) + "\"}}");

        assertEquals(413, response.status().code());
    }

    @Test
    void aForeignOriginIsRefusedAndLogged() throws Exception {
        Response response = post(fromAClientBut("Origin", "https://evil.example.com"),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");

        assertEquals(403, response.status().code());
        assertFalse(body(response).contains("vidocq_"), "nothing of the tools");
        assertEquals(List.of("[VIDOCQ-DEVC-006] Dev console MCP request refused, origin 'https://evil.example.com': "
                + "not the console's own origin"), log.messages(Level.WARNING));
    }

    @Test
    void theConsolesOwnOriginIsLetIn() throws Exception {
        Response response = post(fromAClientBut("Origin", "http://127.0.0.1:8888"),
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}");

        assertEquals(Map.of(), result(response));
    }

    @Test
    void aForeignHostIsRefusedBeforeAnything() throws Exception {
        Response response = handler.handle(FakeRequest.post("/mcp", "evil.example.com:8888", fromAClient(),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"));

        assertEquals(403, response.status().code());
    }

    @Test
    void aBodyThatIsNotJsonIs415AndAnAcceptWithoutJsonIs406() throws Exception {
        String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        Map<String, String> noType = fromAClient();
        noType.remove("Content-Type");

        assertEquals(415, post(noType, ping).status().code());
        assertEquals(415, post(fromAClientBut("Content-Type", "application/x-www-form-urlencoded"), ping).status()
                .code(), "a cross-site form");
        assertEquals(415, post(fromAClientBut("Content-Type", "text/plain"), ping).status().code());
        assertEquals(406, post(fromAClientBut("Accept", "text/event-stream"), ping).status().code());
        Map<String, String> noAccept = fromAClient();
        noAccept.remove("Accept");
        assertEquals(406, post(noAccept, ping).status().code());
        assertEquals(200, post(fromAClientBut("Accept", "*/*"), ping).status().code());
    }

    @Test
    void getAndEveryOtherMethodAre405() throws Exception {
        for (HttpMethod method : new HttpMethod[] {HttpMethod.GET, HttpMethod.HEAD, HttpMethod.PUT,
                HttpMethod.DELETE}) {
            Response response = handler.handle(new FakeRequest(method, "/mcp", HOST));

            assertEquals(405, response.status().code(), method.toString());
            assertEquals("POST", response.headers().firstOrNull("Allow"));
        }
        assertTrue(served.isEmpty(), "never the page");
    }

    @Test
    void outsideADevLaunchThereIsNoDevMcp() throws Exception {
        Snapshot prod = new Snapshot("7f3a91c04be2d810", "0.4.0-TEST", Optional::empty, List.of(), () -> NOW);
        ConsoleHandler readOnly = new ConsoleHandler(new HostGuard("127.0.0.1"), () -> 8888, prod, page);

        Response response = readOnly.handle(FakeRequest.post("/mcp", HOST, fromAClient(),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"));

        assertEquals(405, response.status().code());
        assertEquals("GET, HEAD", response.headers().firstOrNull("Allow"), "a POST like any other");
    }
}
