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
import io.vidocq.humboldt.sdk.trace.export.InMemorySpanExporter;
import io.vidocq.humboldt.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpanProcessorTest {

    @Test
    void simple_processor_drops_unsampled_spans() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        try (SdkTracerProvider p = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOff())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            Tracer t = p.get("x");
            for (int i = 0; i < 10; i++) {
                t.spanBuilder("dropped").startSpan().end();
            }
            assertEquals(0, exporter.getFinishedSpans().size(),
                    "AlwaysOff must prevent all export");
        }
    }

    @Test
    void batch_processor_exports_after_threshold() throws Exception {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        BatchSpanProcessor batch = BatchSpanProcessor.builder(exporter)
                .setMaxExportBatchSize(5)
                .setScheduleDelay(Duration.ofMillis(50))
                .build();
        try (SdkTracerProvider p = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(batch)
                .build()) {
            Tracer t = p.get("x");
            for (int i = 0; i < 12; i++) {
                t.spanBuilder("span-" + i).startSpan().end();
            }
            // Wait for scheduleDelay-based flush (50ms) — allow a few cycles
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline && exporter.getFinishedSpans().size() < 12) {
                Thread.sleep(20);
            }
            assertEquals(12, exporter.getFinishedSpans().size(),
                    "BatchSpanProcessor must export 12 spans within a few cycles");
        }
    }

    @Test
    void batch_processor_drains_on_shutdown() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        BatchSpanProcessor batch = BatchSpanProcessor.builder(exporter)
                .setMaxExportBatchSize(100)
                .setScheduleDelay(Duration.ofSeconds(60))   // long delay to force draining via shutdown
                .build();
        SdkTracerProvider p = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(batch)
                .build();
        Tracer t = p.get("x");
        for (int i = 0; i < 7; i++) {
            t.spanBuilder("s" + i).startSpan().end();
        }
        // At this point, no flush has happened yet (queue size = 7 < 100, delay = 60s)
        assertTrue(exporter.getFinishedSpans().size() <= 7,
                "must not have exported before shutdown : " + exporter.getFinishedSpans().size());
        p.close();
        assertEquals(7, exporter.getFinishedSpans().size(),
                "shutdown must drain the remaining queue");
    }
}
