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
package io.vidocq.humboldt.sdk.log;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.SpanContext;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;
import io.vidocq.humboldt.sdk.log.export.LoggingLogRecordExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggingLogRecordExporterLifecycleTest {

    private static final String LOG_FILE_PROPERTY = "mptelemetry.tck.log.file.path";
    private static final String LOG_FILE = "logs.txt";

    @Test
    void writes_the_epoch_timestamp_in_utc_and_falls_back_to_the_severity_name(@TempDir Path dir)
            throws IOException {
        LogRecordData r = new LogRecordData(
                Resource.empty(), new InstrumentationScope("io.vidocq.test", null, null, Attributes.empty()),
                0L, 1_500_000_000L, SpanContext.getInvalid(),
                Severity.WARN, "", "careful", Attributes.empty());
        Path file = dir.resolve(LOG_FILE);
        LoggingLogRecordExporter exporter = LoggingLogRecordExporter.toFile(file);

        assertTrue(exporter.export(List.of(r)).isSuccess());
        assertTrue(exporter.flush().isSuccess());
        assertTrue(exporter.shutdown().isSuccess());

        assertEquals(List.of("1970-01-01 00:00:01.500 WARN careful scopeInfo:io.vidocq.test:"),
                Files.readAllLines(file));
    }

    @Test
    void appends_to_an_existing_file(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(LOG_FILE);
        Files.writeString(file, "previous" + System.lineSeparator());
        LoggingLogRecordExporter exporter = LoggingLogRecordExporter.toFile(file);
        exporter.export(List.of(sampleRecord("next")));
        exporter.shutdown();

        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size());
        assertEquals("previous", lines.getFirst());
        assertTrue(lines.get(1).contains(" INFO next scopeInfo:"), lines.get(1));
    }

    @Test
    void a_shut_down_exporter_rejects_exports_and_flushes(@TempDir Path dir) {
        LoggingLogRecordExporter exporter = LoggingLogRecordExporter.toFile(dir.resolve(LOG_FILE));
        assertTrue(exporter.shutdown().isSuccess());

        assertFalse(exporter.export(List.of(sampleRecord("late"))).isSuccess());
        assertFalse(exporter.flush().isSuccess());
        assertTrue(exporter.shutdown().isSuccess(), "a second shutdown is a successful no-op");
    }

    @Test
    void a_file_in_a_missing_directory_cannot_be_opened(@TempDir Path dir) {
        Path file = dir.resolve("missing").resolve(LOG_FILE);
        RuntimeException e = assertThrows(RuntimeException.class, () -> LoggingLogRecordExporter.toFile(file));
        assertTrue(e.getMessage().contains(file.toString()), e.getMessage());
    }

    @Test
    void create_writes_to_the_file_named_by_the_tck_system_property(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(LOG_FILE);
        String previous = System.getProperty(LOG_FILE_PROPERTY);
        System.setProperty(LOG_FILE_PROPERTY, file.toString());
        try {
            LoggingLogRecordExporter exporter = LoggingLogRecordExporter.create();
            exporter.export(List.of(sampleRecord("from create")));
            exporter.shutdown();
        } finally {
            restore(previous);
        }
        assertTrue(Files.readString(file).contains(" INFO from create scopeInfo:"));
    }

    @Test
    void create_without_the_property_writes_to_stdout_and_never_closes_it() {
        String previous = System.getProperty(LOG_FILE_PROPERTY);
        System.setProperty(LOG_FILE_PROPERTY, " ");
        PrintStream stdout = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(captured, true, StandardCharsets.UTF_8);
        System.setOut(capture);
        try {
            LoggingLogRecordExporter exporter = LoggingLogRecordExporter.create();
            assertTrue(exporter.export(List.of(sampleRecord("to stdout"))).isSuccess());
            assertTrue(exporter.flush().isSuccess());
            assertTrue(exporter.shutdown().isSuccess());
            capture.print("still open");
            capture.flush();
        } finally {
            System.setOut(stdout);
            restore(previous);
        }
        String out = captured.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains(" INFO to stdout scopeInfo:io.vidocq.test:1.0"), out);
        assertTrue(out.endsWith("still open"), out);
        assertFalse(capture.checkError());
    }

    private static void restore(String previous) {
        if (previous == null) {
            System.clearProperty(LOG_FILE_PROPERTY);
        } else {
            System.setProperty(LOG_FILE_PROPERTY, previous);
        }
    }

    private static LogRecordData sampleRecord(String body) {
        return new LogRecordData(
                Resource.empty(), new InstrumentationScope("io.vidocq.test", "1.0", null, Attributes.empty()),
                1_000L, 2_000L, SpanContext.getInvalid(),
                Severity.INFO, "INFO", body, Attributes.empty(), "");
    }
}
