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
package io.vidocq.runtime.core.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleLogFormatterTest {

    private static final String NL = System.lineSeparator();
    private static final Instant INSTANT = Instant.parse("2026-09-17T17:39:19.902Z");

    private static LogRecord record(Level level, String message) {
        LogRecord record = new LogRecord(level, message);
        record.setInstant(INSTANT);
        record.setLoggerName("io.vidocq.runtime.core.VidocqBootstrap");
        record.setSourceClassName("io.vidocq.runtime.core.VidocqBootstrap");
        record.setSourceMethodName("configure");
        return record;
    }

    private static String formatOnThread(ConsoleLogFormatter formatter, LogRecord record, Thread.Builder builder)
            throws InterruptedException {
        AtomicReference<String> line = new AtomicReference<>();
        builder.start(() -> line.set(formatter.format(record))).join();
        return line.get();
    }

    private static String threadColumn(String line) {
        // [LEVEL][timestamp][thread][source] : message
        int start = line.indexOf("][", line.indexOf("][") + 2) + 2;
        return line.substring(start, line.indexOf(']', start));
    }

    @Test
    void formatsOneAlignedLine() throws InterruptedException {
        ConsoleLogFormatter formatter = new ConsoleLogFormatter(false, ZoneOffset.UTC);

        String line = formatOnThread(formatter, record(Level.INFO, "Vidocq - Configuration phase"),
                Thread.ofPlatform().name("main"));

        assertEquals("[INFO ][2026-09-17 17:39:19.902][main" + " ".repeat(11) + "]"
                + "[" + pad("i.v.r.c.VidocqBootstrap#configure") + "] : Vidocq - Configuration phase" + NL, line);
        // [LEVEL] + [timestamp] + [thread] + [source] + " : " — the message always starts in column 100
        assertEquals(7 + 25 + 17 + 47 + 3, line.indexOf("Vidocq - Configuration phase"));
    }

    @ParameterizedTest
    @CsvSource({
            "SEVERE, ERROR", "WARNING, WARN", "INFO, INFO", "CONFIG, CONF",
            "FINE, DEBUG", "FINER, TRACE", "FINEST, TRACE", "OFF, ERROR", "ALL, TRACE"})
    void namesLevelsLikeSystemLoggerAndPadsThemToFiveColumns(String jul, String expected) {
        String line = new ConsoleLogFormatter(false, ZoneOffset.UTC).format(record(Level.parse(jul), "m"));

        assertTrue(line.startsWith("[" + expected + " ".repeat(5 - expected.length()) + "]["), line);
    }

    @Test
    void levelNamesAreNeverLocalized() {
        // A custom level between INFO and WARNING, with a localized-looking name, maps by value.
        Level custom = new Level("INFOS", 850) {};

        assertEquals("INFO", ConsoleLogFormatter.levelName(custom));
    }

    @ParameterizedTest
    @CsvSource({
            "SEVERE, '[1;31m'", "WARNING, '[33m'", "INFO, '[32m'",
            "CONFIG, '[36m'", "FINE, '[2m'", "FINEST, '[2m'"})
    void coloursOnlyTheLevel(String jul, String color) {
        Level level = Level.parse(jul);
        String name = ConsoleLogFormatter.levelName(level);

        String line = new ConsoleLogFormatter(true, ZoneOffset.UTC).format(record(level, "message"));

        assertTrue(line.startsWith("[" + color + name + "[0m" + " ".repeat(5 - name.length()) + "]["), line);
        assertEquals(2, line.split("", -1).length - 1, "only the level carries escapes: " + line);
    }

    @Test
    void noEscapeWithoutColours() {
        String line = new ConsoleLogFormatter(false, ZoneOffset.UTC).format(record(Level.SEVERE, "message"));

        assertFalse(line.contains(""), line);
    }

    @Test
    void timestampIsLocalDateTimeWithMilliseconds() {
        String line = new ConsoleLogFormatter(false).format(record(Level.INFO, "m"));

        assertTrue(Pattern.compile("^\\[INFO \\]\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}]\\[")
                .matcher(line).find(), line);
    }

    @Test
    void padsTheThreadNameToFifteenColumns() throws InterruptedException {
        String line = formatOnThread(new ConsoleLogFormatter(false, ZoneOffset.UTC), record(Level.INFO, "m"),
                Thread.ofPlatform().name("worker-1"));

        assertEquals("worker-1       ", threadColumn(line));
    }

    @Test
    void aLongerThreadNameKeepsItsEnd() throws InterruptedException {
        String line = formatOnThread(new ConsoleLogFormatter(false, ZoneOffset.UTC), record(Level.INFO, "m"),
                Thread.ofPlatform().name("chappe-listener-default-42"));

        assertEquals("~ner-default-42", threadColumn(line));
    }

    @Test
    void anUnnamedVirtualThreadShowsItsId() throws InterruptedException {
        AtomicReference<Long> id = new AtomicReference<>();
        AtomicReference<String> line = new AtomicReference<>();
        ConsoleLogFormatter formatter = new ConsoleLogFormatter(false, ZoneOffset.UTC);
        Thread.ofVirtual().start(() -> {
            id.set(Thread.currentThread().threadId());
            line.set(formatter.format(record(Level.INFO, "m")));
        }).join();

        String expected = "virtual-" + id.get();
        assertEquals(expected + " ".repeat(15 - expected.length()), threadColumn(line.get()));
    }

    @Test
    void sourceAbbreviatesPackagesToTheirFirstLetter() {
        assertEquals(pad("i.v.r.c.VidocqBootstrap#configure"),
                ConsoleLogFormatter.source("io.vidocq.runtime.core.VidocqBootstrap", "configure", "ignored"));
        assertEquals(pad("d.l.c.m.s.r.McpToolDiscovery#registerMethod"),
                ConsoleLogFormatter.source("dev.langchain4j.cdi.mcp.server.runtime.McpToolDiscovery",
                        "registerMethod", null));
    }

    @Test
    void sourceWithoutPackageStaysAsIs() {
        assertEquals(pad("ProtoMain#main"), ConsoleLogFormatter.source("ProtoMain", "main", null));
    }

    @Test
    void sourceDropsThePackagesWhenStillTooWide() {
        // i.v.r.e.c.CassiniScopeExtension#addProviderDefaultScope is 55 columns
        assertEquals("CassiniScopeExtension#addProviderDefaultScope",
                ConsoleLogFormatter.source("io.vidocq.runtime.extensions.cassini.CassiniScopeExtension",
                        "addProviderDefaultScope", null));
    }

    @Test
    void sourceCutsTheMethodAtItsEnd() {
        assertEquals("McpInvokerBuildCompatibleExtension#registerI~",
                ConsoleLogFormatter.source("dev.langchain4j.cdi.mcp.McpInvokerBuildCompatibleExtension",
                        "registerInvokers", null));
    }

    @Test
    void sourceShowsTheClassAloneWhenNoRoomIsLeftForTheMethod() {
        String type = "a.b." + "C".repeat(44);

        assertEquals("C".repeat(44) + " ", ConsoleLogFormatter.source(type, "method", null));
    }

    @Test
    void aClassNameThatDoesNotFitKeepsItsEnd() {
        String simple = "Abcdefghij".repeat(5) + "End";          // 53 columns

        String source = ConsoleLogFormatter.source("x.y." + simple, "method", null);

        assertEquals(45, source.length());
        assertEquals("~" + simple.substring(simple.length() - 44), source);
    }

    @Test
    void sourceFallsBackToTheLoggerNameWhenTheClassIsUnknown() {
        assertEquals(pad("o.h.SQL"), ConsoleLogFormatter.source(null, "ignored", "org.hibernate.SQL"));
        assertEquals(pad(""), ConsoleLogFormatter.source(null, null, null));
    }

    @Test
    void recordWithoutSourceClassUsesTheLoggerName() {
        LogRecord record = record(Level.INFO, "m");
        record.setSourceClassName(null);
        record.setSourceMethodName(null);
        record.setLoggerName("chappe.access");

        assertTrue(new ConsoleLogFormatter(false).format(record).contains("][" + pad("c.access") + "] : m"));
    }

    @Test
    void sourceNeverFailsOnOddNames() {
        assertEquals(pad(".X#m"), ConsoleLogFormatter.source(".X", "m", null));
        assertEquals(pad("a..b#m"), ConsoleLogFormatter.source("a..b", "m", null));
        assertEquals(pad("a.b.#m"), ConsoleLogFormatter.source("a.b.", "m", null));
    }

    @Test
    void stackTraceFollowsTheLine() {
        LogRecord record = record(Level.WARNING, "A warning with its stack trace");
        IllegalStateException boom = new IllegalStateException("No McpServerSPI implementation found.");
        record.setThrown(boom);

        String output = new ConsoleLogFormatter(false, ZoneOffset.UTC).format(record);

        String[] lines = output.split(Pattern.quote(NL), -1);
        assertTrue(lines[0].endsWith("] : A warning with its stack trace"), lines[0]);
        assertEquals("java.lang.IllegalStateException: No McpServerSPI implementation found.", lines[1]);
        assertTrue(lines[2].startsWith("\tat ") && lines[2].contains("ConsoleLogFormatterTest.stackTraceFollowsTheLine"),
                lines[2]);
        assertTrue(output.endsWith(NL));
    }

    @Test
    void extraMessageLinesFollowUnchanged() {
        String output = new ConsoleLogFormatter(false, ZoneOffset.UTC)
                .format(record(Level.INFO, "Multi-line message:\n  second line"));

        assertTrue(output.endsWith("] : Multi-line message:\n  second line" + NL), output);
    }

    @Test
    void messageParametersAreFormatted() {
        LogRecord record = record(Level.INFO, "Starting extension: {0} ({1})");
        record.setParameters(new Object[] {"chappe", 2});

        assertTrue(new ConsoleLogFormatter(false).format(record).endsWith("] : Starting extension: chappe (2)" + NL));
    }

    private static String pad(String value) {
        return value + " ".repeat(45 - value.length());
    }
}
