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
import io.vidocq.runtime.spi.report.ReportSection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console's own {@code logs} panel: its ring on the root logger, installed once and removed on stop, the records
 * newest first without credentials, the counters and their chart, the loggers whose level is set, and the action that
 * sets one until the next reload.
 */
class LogsPanelTest {

    private static final String JDK_FINDER = "sun.util.logging.internal.LoggingProviderImpl";
    private static final String LOGGER = "io.vidocq.test.logspanel.orders";

    /** A root logger of the tests' own: the JVM's is left alone. */
    private final Logger root = anonymousRoot();
    private final List<LogsPanel> panels = new ArrayList<>();

    private static Logger anonymousRoot() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.INFO);
        return logger;
    }

    private LogsPanel panel(String finder) {
        LogsPanel panel = new LogsPanel(() -> finder, root);
        panel.install();
        panels.add(panel);
        return panel;
    }

    @AfterEach
    void stopEveryPanel() {
        panels.forEach(LogsPanel::stop);
    }

    private long rings() {
        return Arrays.stream(root.getHandlers()).filter(handler -> handler instanceof LogRing).count();
    }

    @Test
    void itIsTheConsolesOwnPanelWithItsChartAndItsAction() {
        LogsPanel panel = panel(JDK_FINDER);

        assertEquals("logs", panel.id());
        assertEquals("Logs", panel.title());
        PanelEntry entry = PanelEntry.builtIn(panel, LaunchMode.DEV);
        assertEquals("logs", entry.id());
        assertEquals("Warnings and errors", entry.charts().getFirst().title());
        assertEquals(List.of("warnings", "errors"),
                entry.charts().getFirst().series().stream().map(series -> series.key()).toList());
        assertEquals(List.of("set-level"), entry.actions().stream().map(PanelAction::id).toList());
        assertNull(entry.actions().getFirst().confirmation(), "harmless, and undone on the next reload");
    }

    @Test
    void installingTwiceAddsOneRingAndStoppingRemovesIt() {
        LogsPanel panel = panel(JDK_FINDER);
        LogRing ring = panel.ring();
        panel.install();

        assertEquals(1, rings());
        assertSame(ring, panel.ring());

        panel.stop();
        panel.stop();
        assertEquals(0, rings());
        assertNull(panel.ring());
    }

    @Test
    void aReloadNeverStacksTwoRingsEvenWhenThePreviousBootNeverStopped() {
        panel(JDK_FINDER);
        LogsPanel reloaded = panel(JDK_FINDER);

        assertEquals(1, rings(), "the ring the previous boot left is removed");
        assertTrue(Arrays.asList(root.getHandlers()).contains(reloaded.ring()));
    }

    @Test
    void theSampleShowsTheRecordsNewestFirstAndTheCounters() {
        LogsPanel panel = panel(JDK_FINDER);
        root.info("first");
        root.log(Level.WARNING, "Order {0} late", 42);
        root.log(Level.SEVERE, "Payment failed for jdbc:postgresql://admin:s3cr3t@db/orders",
                new IllegalStateException("gateway down\n\tat com.acme.Pay.run"));
        root.fine("below the root level: never published");

        Map<String, Object> sample = sampled(panel);

        assertEquals(1, counter(value(sample, "warnings")));
        assertEquals(1, counter(value(sample, "errors")));
        Map<String, Object> records = value(sample, "records");
        assertEquals(List.of("time", "level", "logger", "thread", "message"), records.get("columns"));
        List<List<String>> rows = rows(records);
        assertEquals(List.of("SEVERE", "WARNING", "INFO"), rows.stream().map(row -> row.get(1)).toList(),
                "newest first");
        assertEquals("Payment failed for jdbc:postgresql://***@db/orders - java.lang.IllegalStateException: "
                + "gateway down", rows.getFirst().get(4));
        assertEquals("Order 42 late", rows.get(1).get(4));
        assertFalse(sample.toString().contains("s3cr3t"), sample.toString());
        assertFalse(sample.toString().contains("com.acme.Pay"), "never the stack: " + sample);
    }

    @Test
    void theRecordsTableShowsAHundredRowsAtMost() {
        LogsPanel panel = panel(JDK_FINDER);
        for (int i = 0; i < 300; i++) {
            root.info("record " + i);
        }

        List<List<String>> rows = rows(value(sampled(panel), "records"));
        assertEquals(LogsPanel.MAX_ROWS, rows.size());
        assertEquals("record 299", rows.getFirst().get(4));
    }

    @Test
    void whenAnotherBackendTakesTheLogsThePanelSaysItCannotSeeThem() {
        LogsPanel panel = panel("org.apache.logging.log4j.jpl.Log4jSystemLoggerFinder");
        root.warning("seen by JUL, but System.Logger goes elsewhere");

        Map<String, Object> sample = sampled(panel);

        String reason = "logs go to Log4jSystemLoggerFinder, not java.util.logging";
        assertEquals(Map.of("key", "records", "kind", "absent", "reason", reason), value(sample, "records"));
        assertEquals(Map.of("key", "warnings", "kind", "absent", "reason", reason), value(sample, "warnings"),
                "never a zero that would read as a quiet application");
        assertEquals(reason, contributed(panel).summary());
        assertTrue(value(sample, "levels") != null, "the levels of java.util.logging are still shown");
    }

    @Test
    void theFindersOfTheJdkSendTheLogsToJul() {
        for (String finder : LogsPanel.JDK_FINDERS) {
            assertEquals("counter", value(sampled(panel(finder)), "warnings").get("kind"), finder);
        }
        assertEquals("counter", value(sampled(panel(null)), "warnings").get("kind"), "an unknown finder");
    }

    @Test
    void theBootFactsSayWhereTheLogsGoAndHowManyAreKept() {
        ReportSection facts = contributed(panel(JDK_FINDER));

        assertEquals("the last 500 records, root logger at INFO", facts.summary());
        assertEquals(List.of("java.util.logging"), line(facts, "backend"));
        assertEquals(List.of("500 records, in memory, for this boot"), line(facts, "kept"));
        assertEquals(List.of("INFO"), line(facts, "root level"));
    }

    @Test
    void theLevelsTableListsTheRootThenTheLoggersWhoseLevelIsSet() {
        LogsPanel panel = panel(JDK_FINDER);
        Logger orders = Logger.getLogger(LOGGER);
        orders.setLevel(Level.CONFIG);
        Logger unset = Logger.getLogger(LOGGER + ".unset");
        try {
            List<List<String>> rows = rows(value(sampled(panel), "levels"));

            assertEquals(List.of("(root)", "INFO", ""), rows.getFirst());
            assertTrue(rows.contains(List.of(LOGGER, "CONFIG", "")), rows.toString());
            assertFalse(rows.stream().anyMatch(row -> row.getFirst().equals(unset.getName())),
                    "a logger without a level of its own is not listed");
        } finally {
            orders.setLevel(null);
        }
    }

    @Test
    void theActionSetsALevelUntilTheNextReloadAndTheStopPutsItBack() {
        LogsPanel panel = panel(JDK_FINDER);
        Logger orders = Logger.getLogger(LOGGER);
        orders.setLevel(Level.WARNING);
        try {
            String result = panel.setLevel(Map.of("logger", LOGGER, "level", "FINE"));
            panel.setLevel(Map.of("logger", LOGGER, "level", "FINEST"));

            assertEquals(LOGGER + " at FINE until the next reload", result);
            assertEquals(Level.FINEST, Logger.getLogger(LOGGER).getLevel());
            assertTrue(rows(value(sampled(panel), "levels"))
                    .contains(List.of(LOGGER, "FINEST", "until the next reload")));

            panel.stop();
            assertEquals(Level.WARNING, orders.getLevel(), "the level it had before the first change");
        } finally {
            orders.setLevel(null);
        }
    }

    @Test
    void aLoggerWhoseLevelTheActionSetIsHeldUntilTheStop() throws Exception {
        LogsPanel panel = panel(JDK_FINDER);
        String name = LOGGER + ".weak";
        panel.setLevel(Map.of("logger", name, "level", "FINE"));
        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(10);
        }

        assertEquals(Level.FINE, Logger.getLogger(name).getLevel(), "java.util.logging holds its loggers weakly");

        panel.stop();
        assertNull(Logger.getLogger(name).getLevel(), "no level before, none after");
    }

    @Test
    void theEmptyLoggerIsTheRootOne() {
        LogsPanel panel = panel(JDK_FINDER);

        assertEquals("the root logger at FINE until the next reload",
                panel.setLevel(Map.of("logger", "", "level", "FINE")));
        assertEquals(Level.FINE, root.getLevel());
        assertEquals(List.of("(root)", "FINE", "until the next reload"),
                rows(value(sampled(panel), "levels")).getFirst());

        panel.stop();
        assertEquals(Level.INFO, root.getLevel());
    }

    @Test
    void theArgumentsAcceptALoggerNameOrNothingAndALevelOfJul() {
        PanelAction action = panel(JDK_FINDER).actions().getFirst();
        PanelAction.Argument logger = action.arguments().get(0);
        PanelAction.Argument level = action.arguments().get(1);

        for (String accepted : List.of("", "io.vidocq.cassini", "Orders", "com.acme.Cart$Line", "a".repeat(200))) {
            assertTrue(logger.accepts(accepted), accepted);
        }
        for (String refused : List.of(" ", "io vidocq", "../etc", "1abc", "a;b", "a".repeat(201))) {
            assertFalse(logger.accepts(refused), refused);
        }
        assertEquals(LogsPanel.LEVELS, level.allowedValues());
        assertFalse(level.accepts("DEBUG"));
    }

    @Test
    void noLevelIsSetOnceThePanelStopped() {
        LogsPanel panel = panel(JDK_FINDER);
        panel.stop();

        assertThrows(IllegalStateException.class, () -> panel.setLevel(Map.of("logger", LOGGER, "level", "FINE")));
        assertNull(Logger.getLogger(LOGGER).getLevel());
    }

    @Test
    void aStoppedPanelWritesNothing() {
        LogsPanel panel = panel(JDK_FINDER);
        panel.stop();

        assertEquals(List.of(), sampled(panel).getOrDefault("values", List.of()));
    }

    private static long counter(Map<String, Object> value) {
        assertEquals("counter", value.get("kind"), value.toString());
        assertEquals("count", value.get("unit"), value.toString());
        return ((Number) value.get("value")).longValue();
    }

    private static List<String> line(ReportSection section, String key) {
        return section.lines().stream().filter(line -> line.key().equals(key)).findFirst().orElseThrow().values();
    }

    private static ReportSection contributed(LogsPanel panel) {
        RecordingSection section = new RecordingSection(LogsPanel.ID, panel.title());
        panel.contribute(new ConsoleReportContext(LaunchMode.DEV), section);
        return section.toSection();
    }

    private static Map<String, Object> sampled(LogsPanel panel) {
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

    @SuppressWarnings("unchecked")
    private static List<List<String>> rows(Map<String, Object> table) {
        return (List<List<String>>) table.get("rows");
    }
}
