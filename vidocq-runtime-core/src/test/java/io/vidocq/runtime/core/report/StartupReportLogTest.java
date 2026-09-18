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
package io.vidocq.runtime.core.report;

import io.vidocq.runtime.core.console.ConsoleLogFormatter;
import io.vidocq.runtime.core.report.Section.Items;
import io.vidocq.runtime.core.report.Section.Row;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.Verbosity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The report as it reaches the console: one record whose first line takes the formatter's prefix and whose
 * other lines are printed as they are, through Vidocq's console formatter and through the JDK's.
 */
class StartupReportLogTest {

    private static final StartupReport REPORT = new StartupReport(LaunchMode.DEV, "auto: IntelliJ agent",
            Verbosity.DETAILED, List.of(new Phase("build", 71_000_000)),
            List.of(new Section("mcp", "MCP server", "1 resource template", List.of(
                    new Items("resource templates", List.of("time://zone/{zone}")),
                    new Row("format", "{0} and '{1}' are not parameters")), -1)),
            List.of(), null, null);

    @Test
    void vidocqConsoleFormatterKeepsTheLinesOfTheReportInColumnZero() {
        String text = StartupReportRenderer.render(REPORT);

        List<String> lines = new ConsoleLogFormatter(false).format(record(text)).lines().toList();

        assertTrue(lines.getFirst().startsWith("[INFO "), lines.getFirst());
        assertTrue(lines.getFirst().endsWith(" : " + StartupReportRenderer.TITLE), lines.getFirst());
        assertEquals(text.lines().skip(1).toList(), lines.subList(1, lines.size()));
    }

    @Test
    void theJdkFormatterKeepsThemToo() {
        String text = StartupReportRenderer.render(REPORT);

        String formatted = new SimpleFormatter().format(record(text));

        assertTrue(formatted.lines().anyMatch(line -> line.endsWith(": " + StartupReportRenderer.TITLE)), formatted);
        assertTrue(formatted.contains(text.substring(text.indexOf('\n'))), formatted);
        assertTrue(formatted.contains("time://zone/{zone}") && formatted.contains("{0} and '{1}' are not parameters"),
                "a report has no parameters: " + formatted);
    }

    private static LogRecord record(String text) {
        LogRecord record = new LogRecord(Level.INFO, text);
        record.setLoggerName(StartupRecorder.LOGGER_NAME);
        return record;
    }
}
