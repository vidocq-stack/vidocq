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
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.runtime.spi.devconsole.DevConsolePanel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Amendment 1 of ADR 0001 on the console's side: an action with a json argument, the larger body it may send, the
 * structured result it answers, and what the snapshot says of it. The checks of ADR 0001 themselves are
 * {@link ConsoleActionsTest}'s.
 */
class ConsoleActionResultsTest {

    private static final String HOST = "127.0.0.1:18095";
    private static final String ORIGIN = "http://127.0.0.1:18095";
    private static final String TOKEN = "5a".repeat(32);
    private static final long NOW = 1_789_740_602_114L;

    private final TestPanels.InspectorPanel panel = new TestPanels.InspectorPanel();
    private final Handler page = request -> Response.builder().status(StatusCode.OK).body("<!doctype html>").build();
    private LogRecords log;
    private Snapshot snapshot;
    private ConsoleHandler handler;

    @BeforeEach
    void aDevBoot() {
        log = new LogRecords(DevConsoleExtension.LOGGER_NAME);
        boot(panel);
    }

    @AfterEach
    void release() {
        log.close();
    }

    private void boot(DevConsolePanel shown) {
        ConsoleActions actions = new ConsoleActions(TOKEN, () -> NOW, Duration.ofSeconds(5));
        FakeReportView report = FakeReportView.of(shown);
        snapshot = new Snapshot("7f3a91c04be2d811", "0.4.0-TEST", () -> Optional.of(report), List.of(), () -> NOW,
                actions);
        handler = new ConsoleHandler(new HostGuard("127.0.0.1"), () -> 18095, snapshot, page);
    }

    private Response post(String action, String body) throws Exception {
        return handler.handle(FakeRequest.post("/api/action/acme-inspect/" + action, HOST, Map.of(
                "Content-Type", "application/json", "Origin", ORIGIN, ConsoleActions.TOKEN_HEADER, TOKEN), body));
    }

    private static String body(Response response) throws IOException {
        try (InputStream in = response.body().asInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** A request body of exactly {@code size} bytes for tool.weather: a city of 'a's. */
    private static String weatherBody(int size) {
        String head = "{\"arguments\":\"{\\\"city\\\":\\\"";
        String tail = "\\\"}\"}";
        return head + "a".repeat(size - head.length() - tail.length()) + tail;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> actionsOf(String panelId) {
        List<Map<String, Object>> panels = (List<Map<String, Object>>) Json.object(snapshot.document()).get("panels");
        Map<String, Object> ours = panels.stream().filter(p -> panelId.equals(p.get("id"))).findFirst()
                .orElseThrow();
        return (List<Map<String, Object>>) ours.get("actions");
    }

    private Map<String, Object> actionOf(String id) {
        return actionsOf("acme-inspect").stream().filter(a -> id.equals(a.get("id"))).findFirst().orElseThrow();
    }

    @Test
    void aStructuredResultAnswersEveryFieldItHasAndIsLoggedByItsSummary() throws Exception {
        Response response = post("tool.weather", "{\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\"}");

        assertEquals(200, response.status().code(), body(response));
        assertEquals("{\"result\":\"ok in 3 ms\",\"contentType\":\"application/json\",\"body\":\"{\\\"temp\\\":21}\","
                + "\"details\":\"{\\\"request\\\":{\\\"method\\\":\\\"tools/call\\\"}}\"}", body(response));
        assertEquals(List.of(Map.of("arguments", "{\"city\":\"Paris\"}")), panel.runs,
                "the json value reaches the action as its text");
        assertTrue(log.messages(Level.INFO).contains(
                "Vidocq dev console: action acme-inspect/tool.weather by 127.0.0.1: ok in 3 ms"),
                log.messages().toString());
    }

    @Test
    void anErrorOfTheTargetIsFlaggedAndStaysA200() throws Exception {
        Response response = post("tool.broken", "{\"arguments\":\"{}\"}");

        assertEquals(200, response.status().code(), body(response));
        assertEquals("{\"result\":\"error -32602: Invalid params\",\"error\":true,\"contentType\":\"application/json\","
                + "\"body\":\"{\\\"code\\\":-32602}\"}", body(response));
    }

    @Test
    void aOneLineActionStillAnswersItsResultOnly() throws Exception {
        Response response = post("note", "{\"text\":\"hello\"}");

        assertEquals(200, response.status().code(), body(response));
        assertEquals("{\"result\":\"noted\"}", body(response));
    }

    @Test
    void aJsonValueThatDoesNotParseOrIsNoObjectIsRefusedBeforeTheAction() throws Exception {
        Response truncated = post("tool.weather", "{\"arguments\":\"{\\\"city\\\":\"}");
        Response array = post("tool.weather", "{\"arguments\":\"[\\\"Paris\\\"]\"}");

        assertEquals(400, truncated.status().code());
        assertEquals("Argument arguments is not a JSON object.", body(truncated));
        assertEquals(400, array.status().code());
        assertEquals(List.of(), panel.runs);
    }

    @Test
    void aBodyWithAJsonArgumentMayReachSixtyFourKibAndAnyOtherFour() throws Exception {
        assertEquals(200, post("tool.weather", weatherBody(ConsoleActions.MAX_JSON_BODY)).status().code());
        assertEquals(413, post("tool.weather", weatherBody(ConsoleActions.MAX_JSON_BODY + 1)).status().code());
        assertEquals(413, post("note", "{\"text\":\"" + "a".repeat(ConsoleActions.MAX_BODY) + "\"}").status().code());
        assertEquals(1, panel.runs.size(), "only the body within the limit reached the action");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSnapshotCarriesTheGroupTheDescriptionAndTheSchemaAsAnObject() {
        Map<String, Object> weather = actionOf("tool.weather");
        assertEquals("Tools", weather.get("group"));
        assertEquals("The weather in a city.\nIn Celsius.", weather.get("description"), "line breaks kept");
        Map<String, Object> argument = (Map<String, Object>) ((List<?>) weather.get("arguments")).get(0);
        assertEquals("arguments", argument.get("name"));
        assertNull(argument.get("allowed"));
        assertEquals(Json.object(TestPanels.InspectorPanel.SCHEMA), argument.get("schema"));

        Map<String, Object> note = actionOf("note");
        assertFalse(note.containsKey("group"));
        assertFalse(note.containsKey("description"));
        assertFalse(((Map<?, ?>) ((List<?>) note.get("arguments")).get(0)).containsKey("schema"));
    }

    @Test
    void theLastOutcomeOfAnErrorResultSaysSo() throws Exception {
        post("tool.broken", "{\"arguments\":\"{}\"}");
        post("tool.weather", "{\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\"}");

        assertEquals(Map.of("text", "error -32602: Invalid params", "time", NOW, "ok", true, "error", true),
                actionOf("tool.broken").get("last"));
        assertEquals(Map.of("text", "ok in 3 ms", "time", NOW, "ok", true), actionOf("tool.weather").get("last"));
    }

    @Test
    void aPanelMayOfferUpToOneHundredAndTwentyEightActions() {
        boot(new TestPanels.ManyActionsPanel());

        assertEquals(128, PanelEntry.MAX_ACTIONS);
        assertEquals(PanelEntry.MAX_ACTIONS, actionsOf("acme-many").size());
    }
}
