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

import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.report.LaunchMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestsPanelTest {

    private static final String FAILED = "{\"state\":\"failed\",\"trigger\":\"change\","
            + "\"startedAt\":\"2026-09-25T10:12:03Z\",\"durationMillis\":3210,"
            + "\"counts\":{\"run\":42,\"failures\":1,\"errors\":0,\"skipped\":2},"
            + "\"failures\":[{\"test\":\"com.acme.OrderServiceTest#rejectsEmptyCart\","
            + "\"type\":\"org.opentest4j.AssertionFailedError\",\"message\":\"expected: <400> but was: <200>\"}],"
            + "\"log\":\"target/vidocq-dev-tests.log\"}";
    private static final String PASSED = "{\"state\":\"passed\",\"trigger\":\"test-change\","
            + "\"startedAt\":\"2026-09-25T10:13:00Z\",\"durationMillis\":2900,"
            + "\"counts\":{\"run\":42,\"failures\":0,\"errors\":0,\"skipped\":2},\"failures\":[],"
            + "\"log\":\"target/vidocq-dev-tests.log\"}";
    private static final String RUNNING = "{\"state\":\"running\",\"trigger\":\"rerun-failed\","
            + "\"startedAt\":\"2026-09-25T10:14:00Z\",\"durationMillis\":0,"
            + "\"counts\":{\"run\":0,\"failures\":0,\"errors\":0,\"skipped\":0},\"failures\":[],"
            + "\"log\":\"target/vidocq-dev-tests.log\",\"previous\":" + FAILED + "}";

    private final List<TestsPanel> panels = new ArrayList<>();
    private long modified = 1_000_000_000_000L;

    @AfterEach
    void stopEveryPanel() {
        panels.forEach(TestsPanel::stop);
    }

    private TestsPanel panel(Path results) {
        TestsPanel panel = new TestsPanel(results);
        panels.add(panel);
        return panel;
    }

    /** Writes the results with a new modification time, as each run does. */
    private void write(Path results, String json) throws Exception {
        Files.writeString(results, json);
        modified += 2_000;
        Files.setLastModifiedTime(results, FileTime.fromMillis(modified));
    }

    private static Map<String, Object> sampled(TestsPanel panel) {
        RecordingSample sample = new RecordingSample();
        panel.sample(sample);
        JsonWriter out = new JsonWriter().beginObject();
        sample.writeTo(out);
        return Json.object(out.endObject().toString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> value(Map<String, Object> sample, String key) {
        return ((List<Map<String, Object>>) sample.getOrDefault("values", List.of())).stream()
                .filter(v -> key.equals(v.get("key"))).findFirst().orElse(null);
    }

    private static long number(Map<String, Object> sample, String key) {
        return ((Number) value(sample, key).get("value")).longValue();
    }

    @Test
    void noPropertyNoPanel() {
        assertEquals(Optional.empty(), TestsPanel.start(null));
        assertEquals(Optional.empty(), TestsPanel.start(" "));
    }

    @Test
    void itIsTheConsolesOwnPanelWithItsChartAndTwoActions(@TempDir Path dir) {
        TestsPanel panel = panel(dir.resolve("vidocq-dev-tests.json"));

        PanelEntry entry = PanelEntry.builtIn(panel, LaunchMode.DEV);
        assertEquals("tests", entry.id());
        assertEquals("Tests", panel.title());
        assertEquals(List.of("failures", "errors"),
                entry.charts().getFirst().series().stream().map(series -> series.key()).toList());
        assertEquals(List.of("run-all", "rerun-failed"), entry.actions().stream().map(PanelAction::id).toList());
        assertEquals(List.of("Run all tests", "Rerun failed tests"),
                entry.actions().stream().map(PanelAction::label).toList());
        assertTrue(entry.actions().stream().allMatch(action -> action.confirmation() == null
                && action.arguments().isEmpty()), "harmless, and no argument: the console never names a test");
    }

    @Test
    void beforeAnyRunItSaysSo(@TempDir Path dir) {
        TestsPanel panel = panel(dir.resolve("vidocq-dev-tests.json"));
        panel.refresh();

        assertEquals("no run yet", value(sampled(panel), "state").get("value"));
    }

    @Test
    void aResultShowsItsCountsAndFailures(@TempDir Path dir) throws Exception {
        Path results = dir.resolve("vidocq-dev-tests.json");
        write(results, FAILED);
        TestsPanel panel = panel(results);
        panel.refresh();

        Map<String, Object> sample = sampled(panel);
        assertEquals("failed (change)", value(sample, "state").get("value"));
        assertEquals(42, number(sample, "run"));
        assertEquals(1, number(sample, "failures"));
        assertEquals(0, number(sample, "errors"));
        assertEquals(2, number(sample, "skipped"));
        assertEquals(List.of(List.of("com.acme.OrderServiceTest#rejectsEmptyCart",
                        "org.opentest4j.AssertionFailedError", "expected: <400> but was: <200>")),
                value(sample, "failed-tests").get("rows"));
    }

    @Test
    void aNewerFileReplacesTheSnapshot(@TempDir Path dir) throws Exception {
        Path results = dir.resolve("vidocq-dev-tests.json");
        write(results, FAILED);
        TestsPanel panel = panel(results);
        panel.refresh();
        write(results, PASSED);
        panel.refresh();

        Map<String, Object> sample = sampled(panel);
        assertEquals("passed (test-change)", value(sample, "state").get("value"));
        assertEquals(0, number(sample, "failures"));
    }

    @Test
    void aRunningRunShowsThePreviousCounts(@TempDir Path dir) throws Exception {
        Path results = dir.resolve("vidocq-dev-tests.json");
        write(results, RUNNING);
        TestsPanel panel = panel(results);
        panel.refresh();

        Map<String, Object> sample = sampled(panel);
        assertEquals("running (rerun-failed)", value(sample, "state").get("value"));
        assertEquals(42, number(sample, "run"));
        assertEquals(1, number(sample, "failures"));
    }

    @Test
    void anUnreadableFileKeepsThePreviousSnapshot(@TempDir Path dir) throws Exception {
        Path results = dir.resolve("vidocq-dev-tests.json");
        write(results, FAILED);
        TestsPanel panel = panel(results);
        panel.refresh();
        write(results, "{\"state\":");
        panel.refresh();

        Map<String, Object> sample = sampled(panel);
        assertEquals(TestsPanel.UNREADABLE, value(sample, "state").get("value"));
        assertEquals(42, number(sample, "run"));
    }

    @Test
    void runAllWritesTheRequest(@TempDir Path dir) throws Exception {
        TestsPanel panel = panel(dir.resolve("vidocq-dev-tests.json"));

        assertEquals("queued", panel.actions().getFirst().run().apply(Map.of()));

        assertEquals("run-all", Files.readString(dir.resolve("vidocq-dev-tests.request")).strip());
    }

    @Test
    void rerunFailedWithoutFailureWritesNothing(@TempDir Path dir) throws Exception {
        Path results = dir.resolve("vidocq-dev-tests.json");
        write(results, PASSED);
        TestsPanel panel = panel(results);
        panel.refresh();

        assertEquals("no failed test to rerun", panel.actions().get(1).run().apply(Map.of()));
        assertFalse(Files.exists(dir.resolve("vidocq-dev-tests.request")));
    }

    @Test
    void rerunFailedWithFailuresWritesTheRequest(@TempDir Path dir) throws Exception {
        Path results = dir.resolve("vidocq-dev-tests.json");
        write(results, FAILED);
        TestsPanel panel = panel(results);
        panel.refresh();

        assertEquals("queued", panel.actions().get(1).run().apply(Map.of()));
        assertEquals("rerun-failed", Files.readString(dir.resolve("vidocq-dev-tests.request")).strip());
    }

    @Test
    void theReaderThreadStartsAndStops(@TempDir Path dir) {
        TestsPanel panel = TestsPanel.start(dir.resolve("vidocq-dev-tests.json").toString()).orElseThrow();
        panels.add(panel);
        Thread reader = panel.reader();

        assertEquals("vidocq-devconsole-tests", reader.getName());
        assertTrue(reader.isDaemon());
        panel.stop();
        assertFalse(reader.isAlive());
        assertNull(panel.reader());
    }

    /** #138: running then its outcome written within one millisecond keep the same modification time. */
    @Test
    void aRewriteWithinTheSameMillisecondIsSeenByItsSize(@TempDir Path dir) throws Exception {
        Path results = dir.resolve("vidocq-dev-tests.json");
        write(results, RUNNING);
        TestsPanel panel = panel(results);
        panel.refresh();
        FileTime same = Files.getLastModifiedTime(results);
        Files.writeString(results, PASSED);
        Files.setLastModifiedTime(results, same);

        panel.refresh();

        assertEquals("passed (test-change)", value(sampled(panel), "state").get("value"));
    }
}
