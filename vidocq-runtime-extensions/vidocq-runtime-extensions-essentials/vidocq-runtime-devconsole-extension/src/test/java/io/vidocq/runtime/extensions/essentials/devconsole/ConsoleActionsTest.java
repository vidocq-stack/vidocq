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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The actions of a dev boot (ADR 0001), behind every check a cross-site request cannot pass. Each refusal is tested
 * with the request a hostile page could send, and shown to run nothing.
 */
class ConsoleActionsTest {

    private static final String HOST = "127.0.0.1:8888";
    private static final String ORIGIN = "http://127.0.0.1:8888";
    private static final String TOKEN = "3f".repeat(32);
    private static final long NOW = 1_789_740_602_114L;

    private final TestPanels.ActionPanel panel = new TestPanels.ActionPanel();
    private final Handler page = request -> Response.builder().status(StatusCode.OK).body("<!doctype html>").build();
    private LogRecords log;
    private Snapshot snapshot;
    private ConsoleHandler handler;

    @BeforeEach
    void aDevBoot() {
        log = new LogRecords(DevConsoleExtension.LOGGER_NAME);
        dev(Duration.ofSeconds(5));
    }

    @AfterEach
    void release() {
        panel.release.countDown();
        log.close();
    }

    private void dev(Duration timeLimit) {
        ConsoleActions actions = new ConsoleActions(TOKEN, () -> NOW, timeLimit);
        FakeReportView report = FakeReportView.of(panel);
        snapshot = new Snapshot("7f3a91c04be2d810", "0.4.0-TEST", () -> Optional.of(report), List.of(), () -> NOW,
                actions);
        handler = new ConsoleHandler(new HostGuard("127.0.0.1"), () -> 8888, snapshot, page);
    }

    /** The headers the console's own page sends. */
    private static Map<String, String> fromThePage() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Origin", ORIGIN);
        headers.put(ConsoleActions.TOKEN_HEADER, TOKEN);
        return headers;
    }

    private static Map<String, String> fromThePageBut(String name, String value) {
        Map<String, String> headers = fromThePage();
        if (value == null) {
            headers.remove(name);
        } else {
            headers.put(name, value);
        }
        return headers;
    }

    private Response post(String path, Map<String, String> headers, String body) throws Exception {
        return handler.handle(FakeRequest.post(path, HOST, headers, body));
    }

    private Response post(String action, String body) throws Exception {
        return post("/api/action/acme-actions/" + action, fromThePage(), body);
    }

    private static String body(Response response) throws IOException {
        try (InputStream in = response.body().asInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void assertGuarded(Response response) {
        assertEquals("nosniff", response.headers().firstOrNull("X-Content-Type-Options"));
        assertEquals("default-src 'self'; frame-ancestors 'none'",
                response.headers().firstOrNull("Content-Security-Policy"));
        assertTrue(response.headers().all("Access-Control-Allow-Origin").isEmpty(), "no CORS");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> actionOf(String id) {
        Map<String, Object> document = Json.object(snapshot.document());
        List<Map<String, Object>> panels = (List<Map<String, Object>>) document.get("panels");
        Map<String, Object> ours = panels.stream().filter(p -> "acme-actions".equals(p.get("id"))).findFirst()
                .orElseThrow();
        return ((List<Map<String, Object>>) ours.get("actions")).stream().filter(a -> id.equals(a.get("id")))
                .findFirst().orElseThrow();
    }

    private List<String> refusals() {
        return log.messages(Level.WARNING).stream().filter(m -> m.contains(ConsoleActions.CROSS_SITE)).toList();
    }

    // ------------------------------------------------------------------------------------------------ what passes

    @Test
    void aRequestOfThePageRunsTheActionAndLogsItsResult() throws Exception {
        Response response = post("clear", "{}");

        assertEquals(200, response.status().code(), body(response));
        assertEquals("application/json; charset=utf-8", response.headers().firstOrNull("Content-Type"));
        assertEquals("{\"result\":\"0 entries\"}", body(response));
        assertGuarded(response);
        assertEquals(List.of(Map.of()), panel.runs);
        assertTrue(log.messages(Level.INFO).contains(
                "Vidocq dev console: action acme-actions/clear by 127.0.0.1: 0 entries"), log.messages().toString());
    }

    @Test
    void theArgumentsReachTheActionCheckedAndACharsetIsFine() throws Exception {
        Response response = post("/api/action/acme-actions/set-level",
                fromThePageBut("Content-Type", "Application/JSON; charset=utf-8"),
                "{\"logger\":\"com.acme.Cart\",\"level\":\"DEBUG\"}");

        assertEquals(200, response.status().code(), body(response));
        assertEquals("{\"result\":\"com.acme.Cart at DEBUG\"}", body(response));
        assertEquals(List.of(Map.of("logger", "com.acme.Cart", "level", "DEBUG")), panel.runs);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSnapshotOfADevBootCarriesTheTokenTheActionsAndTheirLastOutcome() throws Exception {
        Map<String, Object> console = (Map<String, Object>) Json.object(snapshot.document()).get("console");
        assertEquals(TOKEN, console.get("actionToken"));

        Map<String, Object> setLevel = actionOf("set-level");
        assertEquals("Set level", setLevel.get("label"));
        assertEquals("Change the level of this logger?", setLevel.get("confirmation"));
        assertEquals(2, ((List<?>) setLevel.get("arguments")).size());
        Map<?, ?> level = (Map<?, ?>) ((List<?>) setLevel.get("arguments")).get(1);
        assertEquals(List.of("INFO", "DEBUG"), level.get("allowed"));
        Map<?, ?> logger = (Map<?, ?>) ((List<?>) setLevel.get("arguments")).get(0);
        assertTrue(logger.containsKey("allowed"));
        assertNull(logger.get("allowed"), "a pattern: the page offers a text field");
        assertEquals(false, setLevel.get("running"));
        assertNull(setLevel.get("last"), "never run this boot");

        post("clear", "{}");

        Map<?, ?> last = (Map<?, ?>) actionOf("clear").get("last");
        assertEquals(Map.of("text", "0 entries", "time", NOW, "ok", true), last);
    }

    // ------------------------------------------------------------------------------------------------ the checks

    @Test
    void aForeignHostIsRefusedFirst() throws Exception {
        Response response = handler.handle(FakeRequest.post("/api/action/acme-actions/clear", "evil.example.com:8888",
                fromThePage(), "{}"));

        assertEquals(403, response.status().code());
        assertGuarded(response);
        assertEquals(List.of(), panel.runs);
    }

    @Test
    void anyMethodButPostIsNotAllowed() throws Exception {
        for (HttpMethod method : new HttpMethod[] {HttpMethod.GET, HttpMethod.HEAD, HttpMethod.PUT,
                HttpMethod.DELETE, HttpMethod.PATCH, HttpMethod.OPTIONS}) {
            Response response = handler.handle(new FakeRequest(method, "/api/action/acme-actions/clear", HOST,
                    Map.of(), fromThePage(), "{}".getBytes(StandardCharsets.UTF_8)));

            assertEquals(405, response.status().code(), method.toString());
            assertEquals("POST", response.headers().firstOrNull("Allow"));
            assertGuarded(response);
        }
        assertEquals(List.of(), panel.runs);
    }

    @Test
    void aPostElsewhereIsStillNotAllowed() throws Exception {
        for (String path : new String[] {"/api/snapshot", "/", "/api/actions", "/api/action"}) {
            Response response = post(path, fromThePage(), "{}");

            assertEquals(405, response.status().code(), path);
            assertEquals("GET, HEAD", response.headers().firstOrNull("Allow"));
        }
    }

    @Test
    void aFormOrATextBodyIsRefusedBeforeItsOriginIsLookedAt() throws Exception {
        for (String type : new String[] {"text/plain", "application/x-www-form-urlencoded", "multipart/form-data",
                "application/jsonp", "text/json", null}) {
            Response response = post("/api/action/acme-actions/clear",
                    fromThePageBut("Content-Type", type), "{}");

            assertEquals(415, response.status().code(), type);
            assertGuarded(response);
        }
        // the simple request a cross-site form sends: no preflight, a form body, a foreign origin
        Map<String, String> form = new HashMap<>();
        form.put("Content-Type", "application/x-www-form-urlencoded");
        form.put("Origin", "https://evil.example.com");
        assertEquals(415, post("/api/action/acme-actions/clear", form, "{}=").status().code());
        assertEquals(List.of(), panel.runs);
    }

    @Test
    void anOriginThatIsNotTheConsolesOwnIsRefusedAndLoggedOnce() throws Exception {
        for (String origin : new String[] {null, "null", "https://evil.example.com", "http://evil.example.com:8888",
                "http://127.0.0.1:9999", "https://127.0.0.1:8888", "http://127.0.0.1:8888/", "http://localhost:8888",
                "http://127.0.0.1"}) {
            Response response = post("/api/action/acme-actions/clear", fromThePageBut("Origin", origin), "{}");

            assertEquals(403, response.status().code(), origin);
            assertGuarded(response);
        }
        post("/api/action/acme-actions/clear", fromThePageBut("Origin", "https://evil.example.com"), "{}");

        assertEquals(List.of(), panel.runs);
        List<String> refusals = refusals();
        assertEquals(9, refusals.size(), "once per origin: " + refusals);
        assertTrue(refusals.contains("[VIDOCQ-DEVC-006] Dev console action refused, origin 'https://evil.example.com':"
                + " not the console's own origin"), refusals.toString());
        assertTrue(refusals.contains("[VIDOCQ-DEVC-006] Dev console action refused, origin (none): not the "
                + "console's own origin"), refusals.toString());
    }

    @Test
    void theOriginOfTheHostThePageWasServedFromPasses() throws Exception {
        Response response = handler.handle(FakeRequest.post("/api/action/acme-actions/clear", "localhost:8888",
                fromThePageBut("Origin", "http://localhost:8888"), "{}"));
        Response ipv6 = handler.handle(FakeRequest.post("/api/action/acme-actions/clear", "[::1]:8888",
                fromThePageBut("Origin", "http://[::1]:8888"), "{}"));

        assertEquals(200, response.status().code(), body(response));
        assertEquals(200, ipv6.status().code(), body(ipv6));
    }

    @Test
    void aMissingOrWrongTokenIsRefusedAndLogged() throws Exception {
        for (String token : new String[] {null, "", "3f", TOKEN.toUpperCase(), TOKEN + "0",
                TOKEN.substring(0, 63) + "0"}) {
            Response response = post("/api/action/acme-actions/clear",
                    fromThePageBut(ConsoleActions.TOKEN_HEADER, token), "{}");

            assertEquals(403, response.status().code(), token);
            assertGuarded(response);
        }
        assertEquals(List.of(), panel.runs);
        assertEquals(List.of("[VIDOCQ-DEVC-006] Dev console action refused, origin 'http://127.0.0.1:8888': no valid "
                + "X-Vidocq-Console-Token"), refusals());
    }

    @Test
    void anUnknownPanelOrActionIsNotFound() throws Exception {
        for (String path : new String[] {"/api/action/acme-actions/nothing", "/api/action/nothing/clear",
                "/api/action/acme-actions", "/api/action/acme-actions/", "/api/action//clear",
                "/api/action/acme-actions/clear/more", "/api/action/"}) {
            Response response = post(path, fromThePage(), "{}");

            assertEquals(404, response.status().code(), path);
        }
        assertEquals(List.of(), panel.runs);
    }

    @Test
    void argumentsThatAreNotTheDeclaredOnesAreABadRequest() throws Exception {
        for (String body : new String[] {"", "[]", "null", "{", "{}", "{\"logger\":\"com.acme\"}",
                "{\"logger\":\"com.acme\",\"level\":\"TRACE\"}", "{\"logger\":\"../etc\",\"level\":\"INFO\"}",
                "{\"logger\":\"com.acme\",\"level\":\"INFO\",\"class\":\"java.lang.Runtime\"}",
                "{\"logger\":\"com.acme\",\"level\":1}", "{\"logger\":\"com.acme\",\"level\":null}",
                "{\"logger\":\"com.acme\",\"level\":\"INFO\",\"level\":\"DEBUG\"}",
                "{\"logger\":\"com.acme\",\"level\":\"INFO\"} {}", "{\"logger\":{},\"level\":\"INFO\"}"}) {
            Response response = post("set-level", body);

            assertEquals(400, response.status().code(), body);
            assertGuarded(response);
        }
        assertEquals(400, post("clear", "{\"force\":\"true\"}").status().code(), "clear takes nothing");
        assertEquals(List.of(), panel.runs);
    }

    @Test
    void anOversizedBodyIsRefused() throws Exception {
        String big = "{\"logger\":\"" + "a".repeat(ConsoleActions.MAX_BODY) + "\",\"level\":\"INFO\"}";

        Response response = post("set-level", big);

        assertEquals(413, response.status().code());
        assertEquals(List.of(), panel.runs);
    }

    // ------------------------------------------------------------------------------------------------ running

    @Test
    void aFailureShowsItsClassNeverItsMessage() throws Exception {
        Response response = post("fail", "{}");

        assertEquals(500, response.status().code());
        String answer = body(response);
        assertEquals("{\"error\":\"IllegalStateException\"}", answer);
        assertFalse(answer.contains("hunter2"));
        assertEquals(Map.of("text", "IllegalStateException", "time", NOW, "ok", false), actionOf("fail").get("last"));
        assertFalse(snapshot.document().contains("hunter2"), "the page never gets the message");
        assertTrue(log.messages(Level.WARNING).contains(
                "Vidocq dev console: action acme-actions/fail by 127.0.0.1 failed: IllegalStateException"),
                log.messages().toString());
        for (Level level : new Level[] {Level.INFO, Level.WARNING}) {
            assertTrue(log.messages(level).stream().noneMatch(m -> m.contains("hunter2")), level.toString());
        }
    }

    @Test
    void oneActionAtATimePerPanelAndPastTheTimeLimitTheOutcomeShowsInTheSnapshot() throws Exception {
        dev(Duration.ofMillis(200));

        Response first = post("slow", "{}");
        assertEquals(202, first.status().code(), "past the time limit the request stops waiting");
        assertEquals("{\"state\":\"running\"}", body(first));
        assertEquals(true, actionOf("slow").get("running"));

        Response second = post("clear", "{}");
        assertEquals(409, second.status().code(), "another action of the same panel is running");
        assertEquals(1, panel.runs.size(), "clear never ran");

        panel.release.countDown();
        Object last = null;
        for (int attempt = 0; attempt < 100 && last == null; attempt++) {
            last = actionOf("slow").get("last");
            if (last == null) {
                Thread.sleep(20);
            }
        }
        assertEquals(Map.of("text", "finally", "time", NOW, "ok", true), last);
        assertEquals(false, actionOf("slow").get("running"));
        assertEquals(200, post("clear", "{}").status().code(), "the panel is free again");
    }

    // ------------------------------------------------------------------------------------------------ outside dev

    @Test
    void outsideADevBootThereIsNoTokenNoActionAndEveryPostIsNotAllowed() throws Exception {
        TestPanels.ActionPanel prodPanel = new TestPanels.ActionPanel();
        FakeReportView report = FakeReportView.of(prodPanel);
        Snapshot prod = new Snapshot("7f3a91c04be2d810", "0.4.0-TEST", () -> Optional.of(report), List.of(),
                () -> NOW);
        ConsoleHandler prodHandler = new ConsoleHandler(new HostGuard("127.0.0.1"), () -> 8888, prod, page);

        String document = prod.document();
        Response response = prodHandler.handle(FakeRequest.post("/api/action/acme-actions/clear", HOST,
                fromThePage(), "{}"));

        assertFalse(document.contains("actionToken"), document);
        assertFalse(document.contains("\"actions\""), document);
        assertEquals(405, response.status().code());
        assertEquals("GET, HEAD", response.headers().firstOrNull("Allow"));
        assertEquals(0, prodPanel.actionsCalls.get(), "actions() is never called outside a dev launch");
        assertEquals(List.of(), prodPanel.runs);
        assertNotNull(prod.panel("acme-actions"));
        assertEquals(List.of(), prod.panel("acme-actions").actions());
    }

    @Test
    void aTokenIsThirtyTwoRandomBytesInHexNewEachTime() {
        String one = ConsoleActions.newToken();
        String two = ConsoleActions.newToken();

        assertTrue(one.matches("[0-9a-f]{64}"), one);
        assertFalse(one.equals(two));
    }
}
