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

import io.vidocq.runtime.spi.devconsole.Chart;
import io.vidocq.runtime.spi.devconsole.Series;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.ReportLine;
import io.vidocq.runtime.spi.report.ReportSection;
import org.junit.jupiter.api.Test;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console's own {@code jvm} panel: the boot facts and the live values the platform MXBeans give, the figures a
 * JVM may not publish written absent, and charts that plot only what the sample writes.
 */
class JvmPanelTest {

    /** A panel whose operating system figures are known, so that every chart has something to plot. */
    private static JvmPanel withLoad() {
        return new JvmPanel(() -> 0.25, () -> 0.5, () -> 1.75);
    }

    /** What {@code panel} writes on one poll, as the snapshot carries it. */
    private static Map<String, Object> sampled(JvmPanel panel) {
        RecordingSample sample = new RecordingSample();
        panel.sample(sample);
        JsonWriter out = new JsonWriter().beginObject();
        sample.writeTo(out);
        return Json.object(out.endObject().toString());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> object, String member) {
        return (List<Map<String, Object>>) object.get(member);
    }

    private static Map<String, Object> value(Map<String, Object> scope, String key) {
        return list(scope, "values").stream().filter(v -> key.equals(v.get("key"))).findFirst().orElse(null);
    }

    private static List<Object> keys(Map<String, Object> scope) {
        return list(scope, "values").stream().map(v -> v.get("key")).toList();
    }

    @Test
    void itIsTheConsolesOwnPanel() {
        JvmPanel panel = new JvmPanel();

        assertEquals("jvm", panel.id());
        assertEquals("JVM", panel.title());
        PanelEntry entry = PanelEntry.builtIn(panel, LaunchMode.DEV);
        assertEquals("jvm", entry.id());
        assertEquals("JVM", entry.title());
        assertEquals(panel, entry.panel(), "live");
        assertEquals(List.of("memory", "nonheap", "threads", "cpu", "gc"),
                entry.charts().stream().map(Chart::id).toList());
    }

    @Test
    void theBootFactsNameTheJvmItsCollectorsAndItsSource() {
        RecordingSection section = new RecordingSection("jvm", "JVM");

        new JvmPanel().contribute(new ConsoleReportContext(LaunchMode.DEV), section);

        ReportSection facts = section.toSection();
        assertEquals(List.of("java", "vm", "gc", "heap max", "processors", "pid", "source"),
                facts.lines().stream().map(ReportLine::key).toList());
        Map<String, List<String>> byKey = new java.util.LinkedHashMap<>();
        facts.lines().forEach(line -> byKey.put(line.key(), line.values()));
        String vendor = ManagementFactory.getRuntimeMXBean().getVmVendor();
        assertEquals(List.of(Runtime.version() + ", " + vendor), byKey.get("java"));
        assertEquals(List.of(ManagementFactory.getRuntimeMXBean().getVmName() + " "
                + ManagementFactory.getRuntimeMXBean().getVmVersion()), byKey.get("vm"));
        assertEquals(ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName).toList(), byKey.get("gc"));
        assertEquals(List.of(String.valueOf(Runtime.getRuntime().availableProcessors())), byKey.get("processors"));
        assertEquals(List.of(String.valueOf(ProcessHandle.current().pid())), byKey.get("pid"));
        assertEquals(List.of("JMX"), byKey.get("source"));
        assertTrue(facts.summary().startsWith("Java " + Runtime.version() + ", "), facts.summary());
        assertTrue(facts.summary().contains(Runtime.getRuntime().availableProcessors() + " processors"),
                facts.summary());
        assertEquals(List.of(), section.anomalies());
    }

    @Test
    void theSampleHasTheLiveValuesOfTheJvm() {
        Map<String, Object> sample = sampled(withLoad());

        assertEquals(List.of("heap.used", "heap.committed", "nonheap.used", "threads", "threads.daemon",
                "threads.peak", "classes", "uptime", "cpu.process", "cpu.system", "load"), keys(sample));
        Map<String, Object> heap = value(sample, "heap.used");
        assertEquals("gauge", heap.get("kind"));
        assertEquals("bytes", heap.get("unit"));
        assertTrue(((Number) heap.get("value")).doubleValue() > 0, heap.toString());
        assertTrue(((Number) heap.get("max")).doubleValue() >= ((Number) heap.get("value")).doubleValue(),
                "the heap max, or what is committed when the JVM has no max: " + heap);
        assertEquals("count", value(sample, "threads").get("unit"));
        assertTrue(((Number) value(sample, "threads").get("value")).longValue() >= 1);
        assertEquals("duration", value(sample, "uptime").get("kind"));
        assertEquals(Map.of("key", "cpu.process", "kind", "gauge", "value", 0.25, "max", 1L, "unit", "ratio"),
                value(sample, "cpu.process"));
        assertEquals(Map.of("key", "cpu.system", "kind", "gauge", "value", 0.5, "max", 1L, "unit", "ratio"),
                value(sample, "cpu.system"));
        assertEquals(Map.of("key", "load", "kind", "gauge", "value", 1.75, "unit", "count"), value(sample, "load"));

        List<Map<String, Object>> groups = list(sample, "groups");
        assertEquals(ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName).toList(), groups.stream().map(g -> g.get("name")).toList(),
                "one group per collector");
        for (Map<String, Object> collector : groups) {
            assertEquals(List.of("collections", "time"), keys(collector));
            Map<String, Object> collections = value(collector, "collections");
            Map<String, Object> time = value(collector, "time");
            if ("counter".equals(collections.get("kind"))) {
                assertEquals("count", collections.get("unit"));
                assertEquals("counter", time.get("kind"));
                assertEquals("nanos", time.get("unit"), "the pause time, in nanoseconds");
                assertEquals(0, ((Long) time.get("value")) % 1_000_000, "milliseconds, as the JVM counts them");
            } else {
                assertEquals("absent", collections.get("kind"), collections.toString());
            }
        }
    }

    @Test
    void aFigureTheJvmDoesNotPublishIsAbsentNeverZero() {
        JvmPanel panel = new JvmPanel(() -> -1, () -> Double.NaN, () -> {
            throw new UnsupportedOperationException("no load average here");
        });

        Map<String, Object> sample = sampled(panel);

        assertEquals(Map.of("key", "cpu.process", "kind", "absent", "reason", "not available"),
                value(sample, "cpu.process"));
        assertEquals(Map.of("key", "cpu.system", "kind", "absent", "reason", "not available"),
                value(sample, "cpu.system"));
        assertEquals(Map.of("key", "load", "kind", "absent", "reason", "not available"), value(sample, "load"));
        assertEquals("gauge", value(sample, "heap.used").get("kind"), "the other values are still there");
    }

    @Test
    void thisJvmsOwnCpuLoadIsAGaugeOrAbsent() {
        Map<String, Object> sample = sampled(new JvmPanel());

        for (String key : List.of("cpu.process", "cpu.system", "load")) {
            Map<String, Object> value = value(sample, key);
            assertNotNull(value, key);
            if ("gauge".equals(value.get("kind"))) {
                assertTrue(((Number) value.get("value")).doubleValue() >= 0, value.toString());
            } else {
                assertEquals(Map.of("key", key, "kind", "absent", "reason", "not available"), value);
            }
        }
    }

    @Test
    void everyChartPlotsValuesTheSampleWrites() {
        JvmPanel panel = withLoad();
        Map<String, Object> sample = sampled(panel);
        List<Map<String, Object>> scopes = new ArrayList<>();
        scopes.add(sample);
        scopes.addAll(list(sample, "groups"));

        for (Chart chart : panel.charts()) {
            for (Series series : chart.series()) {
                List<Map<String, Object>> held = scopes.stream().map(scope -> value(scope, series.key()))
                        .filter(v -> v != null && !"absent".equals(v.get("kind"))).toList();
                assertFalse(held.isEmpty(), chart.id() + " plots " + series.key() + ", which no scope holds");
                for (Map<String, Object> value : held) {
                    String expected = series.style() == Series.Style.RATE ? "counter" : "gauge";
                    assertEquals(expected, value.get("kind"), chart.id() + ": " + series);
                    if (series.style() == Series.Style.CEILING) {
                        assertTrue(value.containsKey("max"), chart.id() + ": a ceiling needs a max, " + value);
                    }
                }
            }
        }
    }

    @Test
    void theHeapCeilingIsItsMaxOrWhatIsCommitted() {
        assertEquals(1024, JvmPanel.ceiling(new MemoryUsage(0, 100, 512, 1024)));
        assertEquals(512, JvmPanel.ceiling(new MemoryUsage(0, 100, 512, -1)), "no max: what is committed");
    }

    @Test
    void sizesReadAsTheReportWritesThem() {
        assertEquals("512 B", JvmPanel.size(512));
        assertEquals("64 KB", JvmPanel.size(64 * 1024));
        assertEquals("1024 MB", JvmPanel.size(1024L * 1024 * 1024));
        assertEquals("30.0 GB", JvmPanel.size(30L * 1024 * 1024 * 1024));
        assertEquals("not set", JvmPanel.size(-1));
    }
}
