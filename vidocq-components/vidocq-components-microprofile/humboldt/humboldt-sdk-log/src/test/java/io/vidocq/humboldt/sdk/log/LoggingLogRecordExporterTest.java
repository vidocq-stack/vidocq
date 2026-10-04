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
import io.opentelemetry.api.common.KeyValue;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.SpanContext;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;
import io.vidocq.humboldt.sdk.log.export.LoggingLogRecordExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggingLogRecordExporterTest {

    @Test
    void prints_the_event_name_before_the_scope(@TempDir Path dir) throws Exception {
        List<String> lines = export(dir, record("checkout.completed"));

        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().endsWith(" INFO done eventName:checkout.completed scopeInfo:io.vidocq.test:1.0"),
                lines.getFirst());
        // The MicroProfile Telemetry Logs TCK (JulTest) matches lines with this pattern.
        assertTrue(lines.getFirst().matches(".*INFO.*done.*scopeInfo:.*"), lines.getFirst());
    }

    @Test
    void keeps_the_line_unchanged_without_an_event_name(@TempDir Path dir) throws Exception {
        List<String> lines = export(dir, record(""));

        assertTrue(lines.getFirst().endsWith(" INFO done scopeInfo:io.vidocq.test:1.0"), lines.getFirst());
    }

    @Test
    void prints_a_structured_body_in_its_json_string_form(@TempDir Path dir) throws Exception {
        Value<?> body = Value.of(
                KeyValue.of("user", Value.of("alice")),
                KeyValue.of("count", Value.of(3L)));
        LogRecordData structured = new LogRecordData(
                Resource.empty(), new InstrumentationScope("io.vidocq.test", "1.0", null, Attributes.empty()),
                1_000L, 2_000L, SpanContext.getInvalid(),
                Severity.INFO, "INFO", "", Attributes.empty(), "", body);

        List<String> lines = export(dir, structured);

        assertTrue(lines.getFirst().endsWith(" INFO {\"user\":\"alice\",\"count\":3} scopeInfo:io.vidocq.test:1.0"),
                lines.getFirst());
        assertTrue(lines.getFirst().matches(".*INFO.*alice.*scopeInfo:.*"), lines.getFirst());
    }

    @Test
    void a_string_body_record_exposes_the_body_as_a_string_value() {
        LogRecordData r = record("");

        assertEquals("done", r.body());
        assertEquals(Value.of("done"), r.bodyValue());
    }

    private static List<String> export(Path dir, LogRecordData record) throws Exception {
        Path file = dir.resolve("logs.txt");
        LoggingLogRecordExporter exporter = LoggingLogRecordExporter.toFile(file);
        assertTrue(exporter.export(List.of(record)).isSuccess());
        exporter.shutdown();
        return Files.readAllLines(file);
    }

    private static LogRecordData record(String eventName) {
        return new LogRecordData(
                Resource.empty(), new InstrumentationScope("io.vidocq.test", "1.0", null, Attributes.empty()),
                1_000L, 2_000L, SpanContext.getInvalid(),
                Severity.INFO, "INFO", "done", Attributes.empty(), eventName);
    }
}
