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

import io.vidocq.runtime.spi.devconsole.DevConsolePanel;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.devconsole.Unit;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportSection;
import io.vidocq.runtime.spi.report.StartupReportView;
import io.vidocq.chappe.api.HttpMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The snapshot document of one boot: {@code booting} until the report is written, then {@code ready} with the
 * report and every panel; a panel that fails costs its own sample and nothing else.
 */
class SnapshotTest {

    private static final String BOOT = "7f3a91c04be2d810";
    private static final long NOW = 1_789_740_602_114L;

    private final AtomicReference<StartupReportView> report = new AtomicReference<>();
    private LogRecords log;

    @BeforeEach
    void captureTheConsoleLog() {
        log = new LogRecords(DevConsoleExtension.LOGGER_NAME);
    }

    @AfterEach
    void release() {
        log.close();
    }

    /** The console's own panel, shown from the first poll: a clock. */
    private static final class ClockPanel implements DevConsolePanel {

        @Override
        public String id() {
            return "clock";
        }

        @Override
        public String title() {
            return "Clock";
        }

        @Override
        public void contribute(StartupReportContext context, StartupReportSection section) {
            section.summary("ticking").row("source", "test");
        }

        @Override
        public void sample(PanelSample sample) {
            sample.counter("ticks", 42, Unit.COUNT);
        }
    }

    private Snapshot snapshot(DevConsolePanel... builtIns) {
        List<PanelEntry> entries = java.util.Arrays.stream(builtIns)
                .map(panel -> PanelEntry.builtIn(panel, LaunchMode.DEV))
                .toList();
        return new Snapshot(BOOT, "0.4.0-TEST", () -> Optional.ofNullable(report.get()), entries, () -> NOW);
    }

    /** A snapshot whose report is {@code view} from the first poll, with the live panels of {@code livePanels}. */
    private static Snapshot snapshotOf(StartupReportView view, LivePanels livePanels) {
        return new Snapshot(BOOT, "0.4.0-TEST", () -> Optional.of(view), List.of(), () -> NOW, null, livePanels,
                null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T at(Object json, Object... path) {
        Object current = json;
        for (Object step : path) {
            current = step instanceof Integer index ? ((List<Object>) current).get(index)
                    : ((Map<String, Object>) current).get(step);
        }
        return (T) current;
    }

    private static List<Object> ids(Map<String, Object> document, String list) {
        List<Map<String, Object>> items = at(document, list);
        return items.stream().map(item -> item.get("id")).toList();
    }

    /** The history of the panel {@code id} carries, as the page reads it. */
    private static List<Map<String, Object>> historyOf(Map<String, Object> document, String id) {
        List<Map<String, Object>> panels = at(document, "panels");
        return panels.stream().filter(panel -> id.equals(panel.get("id")))
                .findFirst().map(panel -> (List<Map<String, Object>>) panel.get("history")).orElseThrow();
    }

    @Test
    void theHistoryFillsOnItsOwnTicksAndNotOnThePolls() {
        Snapshot snapshot = snapshot(new ClockPanel());

        assertEquals(List.of(), historyOf(Json.object(snapshot.document()), "clock"),
                "a poll draws the history, it does not write it: nothing has ticked yet");

        snapshot.tick();
        snapshot.tick();

        List<Map<String, Object>> ticks = historyOf(Json.object(snapshot.document()), "clock");
        assertEquals(1, ticks.size());
        assertEquals("ticks", ticks.get(0).get("key"));
        assertEquals("counter", ticks.get(0).get("kind"));
        assertEquals(List.of(42L, 42L), ticks.get(0).get("v"), "two ticks, two points, however many polls there were");

        Json.object(snapshot.document());
        Json.object(snapshot.document());

        assertEquals(List.of(42L, 42L), historyOf(Json.object(snapshot.document()), "clock").get(0).get("v"),
                "three more polls and still two points: a poll reads the history, it never writes one");
    }

    @Test
    void aPollAsksForThePointsItIsMissingWithSince() {
        Snapshot snapshot = snapshot(new ClockPanel());
        snapshot.tick();
        snapshot.tick();

        assertEquals(List.of(NOW, NOW), historyOf(Json.object(snapshot.document(-1)), "clock").get(0).get("t"),
                "no since: every point kept, which is what a tab that has been away asks for");
        assertEquals(List.of(), historyOf(Json.object(snapshot.document(NOW)), "clock").get(0).get("t"),
                "a page already holding the newest point is sent none");
    }

    /** The document a request gets, the query parameters it carries applied. */
    private static Map<String, Object> answered(Snapshot snapshot, Map<String, String> query) throws IOException {
        io.vidocq.chappe.api.Response response = snapshot.handle(
                new FakeRequest(HttpMethod.GET, "/api/snapshot", "127.0.0.1:8888", query));
        try (InputStream in = response.body().asInputStream()) {
            return Json.object(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void aSinceThatIsNoNumberIsReadAsNoSinceAtAll() throws IOException {
        Snapshot snapshot = snapshot(new ClockPanel());
        snapshot.tick();

        Map<String, Object> document = answered(snapshot, Map.of("since", "yesterday"));

        assertEquals(1, ((List<?>) historyOf(document, "clock").get(0).get("t")).size(),
                "a page that cannot say what it holds is given all of it, never nothing");
    }

    @Test
    void theSinceOfARequestIsTheOneTheHistoryIsCutAt() throws IOException {
        Snapshot snapshot = snapshot(new ClockPanel());
        snapshot.tick();

        assertEquals(List.of(), historyOf(answered(snapshot, Map.of("since", String.valueOf(NOW))), "clock")
                .get(0).get("t"));
        assertEquals(List.of(NOW), historyOf(answered(snapshot, Map.of()), "clock").get(0).get("t"),
                "and no since at all is every point");
    }

    @Test
    void aPanelThatThrowsCostsItsOwnTickAndNoOther() {
        report.set(FakeReportView.of(new TestPanels.BrokenPanel(), new TestPanels.PoolPanel()));
        Snapshot snapshot = snapshot(new ClockPanel());

        snapshot.tick();

        Map<String, Object> document = Json.object(snapshot.document());
        assertEquals(List.of(), historyOf(document, "broken"),
                "a sample that threw is not half-recorded: the tick drops it whole");
        assertEquals(1, historyOf(document, "acme-pool").size(), "and the panels beside it keep ticking");
        assertEquals(1, historyOf(document, "clock").size());
    }

    @Test
    void theSnapshotIsBootingUntilTheReportIsWrittenThenReady() {
        TestPanels.PoolPanel pool = new TestPanels.PoolPanel();
        Snapshot snapshot = snapshot(new ClockPanel());
        snapshot.bound("http://127.0.0.1:8888/", 8888, 8888);

        Map<String, Object> booting = Json.object(snapshot.document());

        assertEquals("booting", booting.get("state"));
        assertTrue(booting.containsKey("startup"));
        assertNull(booting.get("startup"));
        assertEquals(Map.of("vidocq", "0.4.0-TEST", "url", "http://127.0.0.1:8888/", "boot", BOOT, "time", NOW,
                "pollMillis", 1000L, "historyTruncated", false), withoutPortTaken(at(booting, "console")));
        assertEquals(List.of("clock"), ids(booting, "panels"), "only the console's own panels");
        assertEquals(0, pool.samples.get(), "no contributed panel is sampled before the report is written");

        report.set(FakeReportView.of(pool, new TestPanels.FactsOnly()));
        Map<String, Object> ready = Json.object(snapshot.document());

        assertEquals("ready", ready.get("state"));
        assertEquals("dev", at(ready, "startup", "launchMode"));
        assertEquals("vidocq.launch.mode", at(ready, "startup", "launchReason"));
        assertEquals(Map.of("code", "VIDOCQ-CFG-003", "message", "vidocq.pool.urll is read by nothing",
                "hint", "Remove it", "source", "core"), at(ready, "startup", "anomalies", 0));
        assertEquals(List.of("launch", "layer"), ids(at(ready, "startup"), "sections"),
                "the contributed sections are panels");
        assertEquals(List.of(List.of("mode", "dev")), at(ready, "startup", "sections", 0, "lines"));
        assertEquals(java.util.Arrays.asList(null, "com.acme.app", "target/classes", "directory"),
                at(ready, "startup", "sections", 1, "lines", 0), "a table row has no key");
        assertEquals("Vidocq startup report\n  launch      dev (vidocq.launch.mode)", at(ready, "startup", "text"));

        assertEquals(List.of("acme-pool", "facts", "clock"), ids(ready, "panels"),
                "the contributed panels in report order, the console's own last");
        Map<String, Object> acme = at(ready, "panels", 0);
        assertEquals("Acme pools", acme.get("title"));
        assertEquals(true, acme.get("live"));
        assertEquals("1 pool, 8 connections max", acme.get("summary"));
        assertEquals(List.of(List.of("main", "jdbc:h2:mem:acme"), List.of("main password", "configured")),
                acme.get("lines"), "the summary is not repeated among the lines");
        assertEquals(List.of(Map.of("id", "connections", "title", "Connections", "series", List.of(
                Map.of("key", "active", "style", "area"), Map.of("key", "active", "style", "ceiling")))),
                acme.get("charts"));
        assertEquals(Map.of("name", "main", "values", List.of(Map.of("key", "active", "kind", "gauge", "value", 3L,
                "max", 8L, "unit", "count"))), at(acme, "sample", "groups", 0));
        assertEquals(false, at(acme, "sample", "slow"));
        assertEquals(false, at(acme, "sample", "truncated"));
        assertTrue(((Long) at(acme, "sample", "nanos")) >= 0);
        assertEquals(1, pool.samples.get());

        Map<String, Object> facts = at(ready, "panels", 1);
        assertEquals(false, facts.get("live"));
        assertEquals(List.of(List.of("things", "a", "b")), facts.get("lines"));
        assertEquals(List.of(), facts.get("charts"));
        assertTrue(facts.containsKey("sample"));
        assertNull(facts.get("sample"));

        Map<String, Object> clock = at(ready, "panels", 2);
        assertEquals("ticking", clock.get("summary"));
        assertEquals(List.of(List.of("source", "test")), clock.get("lines"));
        assertEquals(Long.valueOf(42), at(clock, "sample", "values", 0, "value"));
    }

    @Test
    void aThrowingPanelCostsItsOwnSampleAndNothingElse() {
        TestPanels.PoolPanel pool = new TestPanels.PoolPanel();
        report.set(FakeReportView.of(new TestPanels.BrokenPanel(), new TestPanels.LinkagePanel(), pool));
        Snapshot snapshot = snapshot(new ClockPanel());

        String first = snapshot.document();
        String second = snapshot.document();

        Map<String, Object> document = Json.object(second);
        assertEquals("ready", document.get("state"));
        assertEquals(List.of("broken", "linkage", "acme-pool", "clock"), ids(document, "panels"));
        assertEquals(Map.of("error", "IllegalStateException"), at(document, "panels", 0, "sample"),
                "the class of the failure, never its message, nor the values written before it");
        assertEquals("boot facts survive", at(document, "panels", 0, "summary"));
        assertEquals(List.of(), at(document, "panels", 0, "charts"), "charts() failed: no charts");
        assertEquals(Map.of("error", "NoClassDefFoundError"), at(document, "panels", 1, "sample"));
        assertEquals(Long.valueOf(3), at(document, "panels", 2, "sample", "groups", 0, "values", 0, "value"));
        assertEquals(2, pool.samples.get());
        assertFalse(first.contains("hunter2"), first);

        assertEquals(List.of(
                "[VIDOCQ-DEVC-005] Panel 'broken' failed to sample: IllegalStateException",
                "[VIDOCQ-DEVC-005] Panel 'linkage' failed to sample: NoClassDefFoundError"),
                log.messages(Level.WARNING), "once per panel and boot, however many polls");
        assertTrue(log.messages(Level.WARNING).stream().noneMatch(m -> m.contains("hunter2")));
        List<Throwable> traces = log.thrown(Level.FINE);
        assertTrue(traces.stream().anyMatch(t -> t instanceof IllegalStateException
                && "password=hunter2".equals(t.getMessage())), "the stack traces at DEBUG: " + traces);
        assertTrue(traces.stream().anyMatch(t -> t instanceof NoClassDefFoundError), traces.toString());
    }

    @Test
    void theConsolesOwnSectionIsShownWithTheReportNotAsAPanel() {
        DevConsoleExtension console = new DevConsoleExtension();
        report.set(FakeReportView.of(console, new TestPanels.FactsOnly()));

        Map<String, Object> document = Json.object(snapshot().document());

        assertEquals(List.of("facts"), ids(document, "panels"));
        assertEquals(List.of("launch", "layer", "devconsole"), ids(at(document, "startup"), "sections"));
        assertEquals("not started", at(document, "startup", "sections", 2, "summary"));
        assertEquals(List.of(), at(document, "startup", "sections", 2, "lines"), "the summary is not repeated");
    }

    @Test
    void aSlowSampleIsFlagged() {
        report.set(FakeReportView.of(new DevConsolePanel() {
            @Override
            public String id() {
                return "slow";
            }

            @Override
            public void contribute(StartupReportContext context, StartupReportSection section) {
                section.summary("sleeps");
            }

            @Override
            public void sample(PanelSample sample) {
                try {
                    Thread.sleep(8);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }));

        Map<String, Object> document = Json.object(snapshot().document());

        assertEquals(true, at(document, "panels", 0, "sample", "slow"));
        assertTrue((Long) at(document, "panels", 0, "sample", "nanos") >= 5_000_000L);
    }

    @Test
    void aTakenPortIsInTheSnapshotForThePageBanner() {
        Snapshot moved = snapshot();
        moved.bound("http://127.0.0.1:54213/", 8888, 54213);
        Snapshot free = snapshot();
        free.bound("http://127.0.0.1:54213/", 0, 54213);

        assertEquals(Map.of("configured", 8888L, "bound", 54213L),
                at(Json.object(moved.document()), "console", "portTaken"));
        assertNull(at(Json.object(free.document()), "console", "portTaken"), "port 0 asks for any port");
        Map<String, Object> unbound = at(Json.object(snapshot().document()), "console");
        assertTrue(unbound.containsKey("url"));
        assertNull(unbound.get("url"));
    }

    private static Map<String, Object> withoutPortTaken(Map<String, Object> console) {
        assertTrue(console.containsKey("portTaken"));
        assertNull(console.get("portTaken"));
        Map<String, Object> rest = new java.util.LinkedHashMap<>(console);
        rest.remove("portTaken");
        return rest;
    }

    @Test
    void aLineThatPointsSomewhereEndsWithItsHref() {
        Snapshot snapshot = snapshot(new ClockPanel());
        FakeReportView base = FakeReportView.of();
        java.util.List<io.vidocq.runtime.spi.report.ReportSection> sections = new java.util.ArrayList<>(base.sections());
        sections.add(new io.vidocq.runtime.spi.report.ReportSection("openapi", "OpenAPI (Grimm)", "3 operations",
                List.of(new io.vidocq.runtime.spi.report.ReportLine("Swagger UI",
                                List.of("http://127.0.0.1:18090/openapi/ui/"), "http://127.0.0.1:18090/openapi/ui/"),
                        new io.vidocq.runtime.spi.report.ReportLine("document", List.of("/openapi")))));
        report.set(new FakeReportView(base.launchMode(), base.launchReason(), base.anomalies(), sections,
                base.detailedText(), base.contributors()));

        List<?> lines = (List<?>) at(Json.object(snapshot.document()), "startup", "sections", 2, "lines");

        assertEquals(List.of("Swagger UI", "http://127.0.0.1:18090/openapi/ui/",
                Map.of("href", "http://127.0.0.1:18090/openapi/ui/")), lines.get(0));
        assertEquals(List.of("document", "/openapi"), lines.get(1), "a line that points nowhere has no object");
    }

    @Test
    void aLivePanelMakesAStaticSectionLive() {
        TestPanels.Static rest = new TestPanels.Static("rest");
        LivePanelsTest.Live live = new LivePanelsTest.Live("rest") {
            @Override
            public void sample(io.vidocq.runtime.spi.devconsole.PanelSample sample) {
                sample.counter("requests", 7, io.vidocq.runtime.spi.devconsole.Unit.COUNT);
            }
        };
        Snapshot snapshot = snapshotOf(FakeReportView.of(rest), LivePanels.of(List.of(live), w -> {}));

        PanelEntry entry = snapshot.panel("rest");

        assertEquals("rest", entry.id());
        assertEquals(live, ((LivePanelAdapter) entry.panel()).live());
        assertEquals(List.of("start"), live.calls, "started when the report is first read");
    }

    @Test
    void aLivePanelWithoutItsSectionIsNotShown() {
        List<String> warnings = new ArrayList<>();
        LivePanels panels = LivePanels.of(List.of(new LivePanelsTest.Live("absent")), warnings::add);
        Snapshot snapshot = snapshotOf(FakeReportView.of(new TestPanels.Static("rest")), panels);

        assertNull(snapshot.panel("absent"));
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("[" + LivePanels.ORPHAN + "]")), warnings.toString());
    }
}
