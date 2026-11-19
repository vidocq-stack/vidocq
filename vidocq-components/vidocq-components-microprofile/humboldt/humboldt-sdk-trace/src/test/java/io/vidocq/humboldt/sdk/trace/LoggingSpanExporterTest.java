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
package io.vidocq.humboldt.sdk.trace;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.humboldt.sdk.trace.export.InMemorySpanExporter;
import io.vidocq.humboldt.sdk.trace.export.LoggingSpanExporter;
import io.vidocq.humboldt.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggingSpanExporterTest {

    private final Logger julLogger = Logger.getLogger(LoggingSpanExporter.class.getName());
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
            records.add(logRecord);
        }

        @Override
        public void flush() {
            // nothing buffered
        }

        @Override
        public void close() {
            // nothing to release
        }
    };

    @BeforeEach
    void attach() {
        julLogger.addHandler(capture);
    }

    @AfterEach
    void detach() {
        julLogger.removeHandler(capture);
    }

    private static List<SpanData> parentAndChild() {
        InMemorySpanExporter memory = InMemorySpanExporter.create();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(memory))
                .build()) {
            Tracer tracer = provider.get("logging");
            Span parent = tracer.spanBuilder("parent").startSpan();
            tracer.spanBuilder("child")
                    .setParent(io.opentelemetry.context.Context.root().with(parent))
                    .startSpan()
                    .end();
            parent.end();
            return memory.getFinishedSpans();
        }
    }

    @Test
    void logs_one_info_record_per_span() {
        List<SpanData> spans = parentAndChild();
        LoggingSpanExporter exporter = LoggingSpanExporter.create();

        assertTrue(exporter.export(spans).isSuccess());

        assertEquals(2, records.size());
        LogRecord child = records.get(0);
        assertEquals(Level.INFO, child.getLevel());
        assertEquals("child", child.getParameters()[0]);
        assertEquals(spans.get(1).spanContext().getSpanId(), child.getParameters()[4]);
        assertEquals("(root)", records.get(1).getParameters()[4]);
        assertTrue(records.get(1).getParameters()[5] instanceof Duration);
    }

    @Test
    void refuses_to_export_after_shutdown() {
        LoggingSpanExporter exporter = LoggingSpanExporter.create();
        assertTrue(exporter.flush().isSuccess());
        assertTrue(exporter.shutdown().isSuccess());

        assertFalse(exporter.export(parentAndChild()).isSuccess());
        assertTrue(records.isEmpty());
    }
}
