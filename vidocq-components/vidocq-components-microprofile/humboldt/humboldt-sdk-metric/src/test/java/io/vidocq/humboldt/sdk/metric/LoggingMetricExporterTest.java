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
package io.vidocq.humboldt.sdk.metric;

import io.opentelemetry.api.common.Attributes;
import io.vidocq.humboldt.sdk.metric.data.AggregationTemporality;
import io.vidocq.humboldt.sdk.metric.data.DoublePointData;
import io.vidocq.humboldt.sdk.metric.data.HistogramPointData;
import io.vidocq.humboldt.sdk.metric.data.InstrumentType;
import io.vidocq.humboldt.sdk.metric.data.LongPointData;
import io.vidocq.humboldt.sdk.metric.data.MetricData;
import io.vidocq.humboldt.sdk.metric.data.PointData;
import io.vidocq.humboldt.sdk.metric.export.LoggingMetricExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggingMetricExporterTest {

    private static final String LOG_FILE_PROPERTY = "mptelemetry.tck.log.file.path";
    private static final String METRICS_FILE = "metrics.txt";

    private static MetricData metric(String name, InstrumentType type, List<? extends PointData> points) {
        return new MetricData(null, null, name, "desc of " + name, "ms", type,
                AggregationTemporality.CUMULATIVE, false, points);
    }

    private static LongPointData longPoint() {
        return new LongPointData(0L, 1L, Attributes.empty(), 1L);
    }

    private static DoublePointData doublePoint() {
        return new DoublePointData(0L, 1L, Attributes.empty(), 1.0);
    }

    @Test
    void writes_one_line_per_metric_with_the_otel_metric_data_type(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("nested").resolve(METRICS_FILE);
        LoggingMetricExporter exporter = LoggingMetricExporter.toFile(file);
        HistogramPointData histogram = new HistogramPointData(0L, 1L, null, 1.0, 1L, 1.0, 1.0,
                List.of(5.0), List.of(1L, 0L));

        assertTrue(exporter.export(List.of(
                metric("c.long", InstrumentType.COUNTER, List.of(longPoint())),
                metric("c.double", InstrumentType.UP_DOWN_COUNTER, List.of(doublePoint())),
                metric("o.long", InstrumentType.OBSERVABLE_COUNTER, List.of()),
                metric("o.double", InstrumentType.OBSERVABLE_UP_DOWN_COUNTER, List.of(doublePoint())),
                metric("h", InstrumentType.HISTOGRAM, List.of(histogram)),
                metric("g.long", InstrumentType.GAUGE, List.of(longPoint())),
                metric("g.double", InstrumentType.OBSERVABLE_GAUGE, List.of(doublePoint())))).isSuccess());
        assertTrue(exporter.flush().isSuccess());
        assertTrue(exporter.shutdown().isSuccess());

        assertEquals(List.of(
                "name=c.long, description=desc of c.long, unit=ms, type=LONG_SUM",
                "name=c.double, description=desc of c.double, unit=ms, type=DOUBLE_SUM",
                "name=o.long, description=desc of o.long, unit=ms, type=LONG_SUM",
                "name=o.double, description=desc of o.double, unit=ms, type=DOUBLE_SUM",
                "name=h, description=desc of h, unit=ms, type=HISTOGRAM",
                "name=g.long, description=desc of g.long, unit=ms, type=LONG_GAUGE",
                "name=g.double, description=desc of g.double, unit=ms, type=DOUBLE_GAUGE"),
                Files.readAllLines(file));
    }

    @Test
    void a_shut_down_exporter_rejects_exports_and_flushes(@TempDir Path dir) {
        LoggingMetricExporter exporter = LoggingMetricExporter.toFile(dir.resolve(METRICS_FILE));
        assertTrue(exporter.shutdown().isSuccess());

        assertFalse(exporter.export(List.of(metric("late", InstrumentType.COUNTER, List.of()))).isSuccess());
        assertFalse(exporter.flush().isSuccess());
        assertTrue(exporter.shutdown().isSuccess(), "a second shutdown is a successful no-op");
    }

    @Test
    void appends_to_an_existing_file(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(METRICS_FILE);
        Files.writeString(file, "previous\n");
        LoggingMetricExporter exporter = LoggingMetricExporter.toFile(file);
        exporter.export(List.of(metric("next", InstrumentType.GAUGE, List.of())));
        exporter.shutdown();

        assertEquals(List.of("previous", "name=next, description=desc of next, unit=ms, type=LONG_GAUGE"),
                Files.readAllLines(file));
    }

    @Test
    void a_file_whose_parent_is_a_regular_file_cannot_be_opened(@TempDir Path dir) throws IOException {
        Path notADirectory = Files.writeString(dir.resolve("plain"), "x");
        Path file = notADirectory.resolve(METRICS_FILE);
        assertThrows(UncheckedIOException.class, () -> LoggingMetricExporter.toFile(file));
    }

    @Test
    void create_writes_to_the_file_named_by_the_tck_system_property(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(METRICS_FILE);
        String previous = System.getProperty(LOG_FILE_PROPERTY);
        System.setProperty(LOG_FILE_PROPERTY, file.toString());
        try {
            LoggingMetricExporter exporter = LoggingMetricExporter.create();
            exporter.export(List.of(metric("from.create", InstrumentType.HISTOGRAM, List.of())));
            exporter.shutdown();
        } finally {
            restore(previous);
        }
        assertEquals(List.of("name=from.create, description=desc of from.create, unit=ms, type=HISTOGRAM"),
                Files.readAllLines(file));
    }

    @Test
    void create_without_the_property_writes_to_stdout_and_never_closes_it() {
        String previous = System.getProperty(LOG_FILE_PROPERTY);
        System.setProperty(LOG_FILE_PROPERTY, "");
        PrintStream stdout = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(captured, true, StandardCharsets.UTF_8);
        System.setOut(capture);
        try {
            LoggingMetricExporter exporter = LoggingMetricExporter.create();
            assertTrue(exporter.export(List.of(metric("to.stdout", InstrumentType.COUNTER, List.of()))).isSuccess());
            assertTrue(exporter.shutdown().isSuccess());
            capture.print("still open");
            capture.flush();
        } finally {
            System.setOut(stdout);
            restore(previous);
        }
        String out = captured.toString(StandardCharsets.UTF_8);
        assertEquals("name=to.stdout, description=desc of to.stdout, unit=ms, type=LONG_SUM\nstill open", out);
        assertFalse(capture.checkError());
    }

    private static void restore(String previous) {
        if (previous == null) {
            System.clearProperty(LOG_FILE_PROPERTY);
        } else {
            System.setProperty(LOG_FILE_PROPERTY, previous);
        }
    }
}
