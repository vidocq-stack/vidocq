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

import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.runtime.spi.devconsole.PanelLanguage;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The languages a panel offers the page's code editor (query mode spec §2): read once with the actions in a dev boot,
 * named in the snapshot by their ids, served as they are at {@code GET /api/language/<panel>/<id>}, behind the
 * snapshot's checks.
 */
class ConsoleLanguagesTest {

    private static final String HOST = "127.0.0.1:18096";
    private static final String ORIGIN = "http://127.0.0.1:18096";
    private static final long NOW = 1_789_740_602_114L;

    private final TestPanels.LanguagePanel panel = new TestPanels.LanguagePanel();
    private LogRecords log;

    @BeforeEach
    void captureTheConsoleLog() {
        log = new LogRecords(DevConsoleExtension.LOGGER_NAME);
    }

    @AfterEach
    void release() {
        log.close();
    }

    /** A boot showing {@link #panel}: a dev one, with actions, or any other. */
    private Snapshot boot(boolean dev) {
        FakeReportView report = FakeReportView.of(panel);
        return new Snapshot("7f3a91c04be2d812", "0.4.0-TEST", () -> Optional.of(report), List.of(), () -> NOW,
                dev ? new ConsoleActions("6b".repeat(32), () -> NOW, Duration.ofSeconds(5)) : null);
    }

    private static ConsoleHandler handler(Snapshot snapshot) {
        return new ConsoleHandler(new HostGuard("127.0.0.1"), () -> 18096, snapshot, request ->
                Response.builder().status(StatusCode.OK).body("<!doctype html>").build());
    }

    /** A request of {@code method} for {@code path}, with these headers besides {@code Host}. */
    private static FakeRequest request(HttpMethod method, String path, String host, Map<String, String> headers) {
        return new FakeRequest(method, path, host, Map.of(), headers, new byte[0]);
    }

    private static String body(Response response) throws IOException {
        try (InputStream in = response.body().asInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ours(Snapshot snapshot) {
        List<Map<String, Object>> panels = (List<Map<String, Object>>) Json.object(snapshot.document()).get("panels");
        return panels.stream().filter(p -> "acme-data".equals(p.get("id"))).findFirst().orElseThrow();
    }

    @Test
    void aDevBootServesALanguageAsThePanelWroteItNeverCachedStale() throws Exception {
        ConsoleHandler handler = handler(boot(true));

        Response response = handler.handle(FakeRequest.get("/api/language/acme-data/jdql", HOST));

        assertEquals(200, response.status().code(), body(response));
        assertEquals("application/json; charset=utf-8", response.headers().firstOrNull("Content-Type"));
        assertEquals("no-cache", response.headers().firstOrNull("Cache-Control"));
        assertEquals("default-src 'self'; frame-ancestors 'none'",
                response.headers().firstOrNull("Content-Security-Policy"));
        assertTrue(response.headers().all("Access-Control-Allow-Origin").isEmpty(), "no CORS");
        assertEquals(TestPanels.LanguagePanel.JDQL, body(response));
        Response head = handler.handle(request(HttpMethod.HEAD, "/api/language/acme-data/jdql", HOST, Map.of()));
        assertEquals(200, head.status().code());
        Response fromThePage = handler.handle(request(HttpMethod.GET, "/api/language/acme-data/jdql", HOST,
                Map.of("Origin", ORIGIN)));
        assertEquals(200, fromThePage.status().code(), "the console's own origin, should a browser send it");
        assertEquals(List.of(), log.messages(), "a language served is never logged");
    }

    @Test
    void anUnknownPanelOrLanguageIsNotFound() throws Exception {
        ConsoleHandler handler = handler(boot(true));

        for (String path : new String[] {"/api/language/acme-data/sql", "/api/language/nothing/jdql",
                "/api/language/acme-data", "/api/language/acme-data/", "/api/language//jdql",
                "/api/language/acme-data/jdql/more", "/api/language/", "/api/language/jvm/jdql"}) {
            Response response = handler.handle(FakeRequest.get(path, HOST));

            assertEquals(404, response.status().code(), path);
            assertFalse(body(response).contains("targets"), path);
        }
    }

    @Test
    void outsideADevBootThereIsNoLanguageAndLanguagesIsNeverCalled() throws Exception {
        Snapshot prod = boot(false);
        ConsoleHandler handler = handler(prod);

        Response response = handler.handle(FakeRequest.get("/api/language/acme-data/jdql", HOST));

        assertEquals(404, response.status().code());
        assertFalse(prod.document().contains("\"languages\""), prod.document());
        assertEquals(0, panel.languagesCalls.get(), "languages() is never called outside a dev launch");
    }

    @Test
    void aForeignOriginOrHostIsRefusedAndAnyMethodButGetOrHeadIsNotAllowed() throws Exception {
        ConsoleHandler handler = handler(boot(true));

        for (String origin : new String[] {"null", "https://evil.example.com", "http://127.0.0.1:9999",
                "http://localhost:18096"}) {
            Response response = handler.handle(request(HttpMethod.GET, "/api/language/acme-data/jdql", HOST,
                    Map.of("Origin", origin)));

            assertEquals(403, response.status().code(), origin);
            assertFalse(body(response).contains("targets"), origin);
        }
        Response foreignHost = handler.handle(FakeRequest.get("/api/language/acme-data/jdql",
                "evil.example.com:18096"));
        assertEquals(403, foreignHost.status().code());
        for (HttpMethod method : new HttpMethod[] {HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE}) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("Origin", ORIGIN);
            Response response = handler.handle(request(method, "/api/language/acme-data/jdql", HOST, headers));

            assertEquals(405, response.status().code(), method.toString());
            assertEquals("GET, HEAD", response.headers().firstOrNull("Allow"));
        }
        assertEquals(List.of(), log.messages(Level.WARNING), "a refusal is never logged either");
    }

    @Test
    void theSnapshotOfADevBootNamesTheLanguagesNeverTheirContent() {
        Snapshot dev = boot(true);

        Map<String, Object> shown = ours(dev);

        assertEquals(List.of("jdql"), shown.get("languages"));
        assertFalse(dev.document().contains("table task"), "the content is fetched, never polled");
        assertFalse(dev.toolDocument().contains("\"languages\""), "the dev MCP gets no language");
        assertEquals(1, panel.languagesCalls.get(), "read once per boot");
        dev.document();
        assertEquals(1, panel.languagesCalls.get(), "read once per boot");
    }

    @Test
    void aPanelThatNamesALanguageTwiceIsShownWithoutLanguagesAndTheConsoleSaysWhy() throws Exception {
        panel.languages = List.of(new PanelLanguage("jdql", "{}"), new PanelLanguage("sql", "{}"),
                new PanelLanguage("jdql", "{\"b\":1}"));
        Snapshot dev = boot(true);

        Map<String, Object> shown = ours(dev);

        assertFalse(shown.containsKey("languages"), shown.toString());
        assertEquals(1, ((List<?>) shown.get("actions")).size(), "the panel and its actions are kept");
        assertEquals(404, handler(dev).handle(FakeRequest.get("/api/language/acme-data/sql", HOST)).status().code());
        assertEquals(List.of("Dev console panel 'acme-data' offers the language 'jdql' twice: it is shown without "
                + "languages"), log.messages(Level.WARNING));
    }

    @Test
    void aPanelWhoseLanguagesThrowIsShownWithoutLanguages() throws Exception {
        panel.failure = new IllegalStateException("password=hunter2");
        Snapshot dev = boot(true);

        Map<String, Object> shown = ours(dev);

        assertFalse(shown.containsKey("languages"), shown.toString());
        assertEquals(1, ((List<?>) shown.get("actions")).size(), "the panel and its actions are kept");
        assertEquals(404, handler(dev).handle(FakeRequest.get("/api/language/acme-data/jdql", HOST)).status().code());
        assertTrue(log.messages(Level.WARNING).stream().noneMatch(m -> m.contains("hunter2")));
    }
}
