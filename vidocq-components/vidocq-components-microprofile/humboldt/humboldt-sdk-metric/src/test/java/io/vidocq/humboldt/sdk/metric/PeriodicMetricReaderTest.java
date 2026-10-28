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

import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.metric.data.InstrumentType;
import io.vidocq.humboldt.sdk.metric.data.MetricData;
import io.vidocq.humboldt.sdk.metric.export.MetricExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeriodicMetricReaderTest {

    private static final MetricData METRIC = new MetricData(null, null, "m", null, null,
            InstrumentType.COUNTER, null, true, null);

    /** Exporter recording each export and answering with a configurable result. */
    private static final class RecordingExporter implements MetricExporter {
        final List<Collection<MetricData>> exports = new CopyOnWriteArrayList<>();
        final AtomicInteger shutdowns = new AtomicInteger();
        volatile boolean succeed = true;

        @Override
        public CompletableResultCode export(Collection<MetricData> metrics) {
            exports.add(List.copyOf(metrics));
            return succeed ? CompletableResultCode.ofSuccess() : CompletableResultCode.ofFailure();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            shutdowns.incrementAndGet();
            return CompletableResultCode.ofSuccess();
        }
    }

    private final RecordingExporter exporter = new RecordingExporter();
    private final PeriodicMetricReader reader = PeriodicMetricReader.builder(exporter)
            .setInterval(Duration.ofMinutes(10))
            .build();

    @AfterEach
    void shutdownReader() {
        reader.shutdown();
    }

    private static CompletableResultCode await(CompletableResultCode code) {
        return code.join(5, TimeUnit.SECONDS);
    }

    @Test
    void the_builder_requires_an_exporter() {
        assertThrows(NullPointerException.class, () -> PeriodicMetricReader.builder(null));
    }

    @Test
    void flush_before_registration_is_an_immediate_success() {
        assertTrue(reader.flush().isSuccess());
        assertTrue(exporter.exports.isEmpty());
    }

    @Test
    void flush_collects_and_exports_the_registered_metrics() {
        reader.register(() -> List.of(METRIC));
        assertTrue(await(reader.flush()).isSuccess());
        assertEquals(List.of(List.of(METRIC)), exporter.exports);
    }

    @Test
    void flush_with_nothing_collected_succeeds_without_exporting() {
        reader.register(List::of);
        assertTrue(await(reader.flush()).isSuccess());
        assertTrue(exporter.exports.isEmpty());
    }

    @Test
    void flush_fails_when_the_export_fails() {
        exporter.succeed = false;
        reader.register(() -> List.of(METRIC));
        CompletableResultCode code = await(reader.flush());
        assertTrue(code.isDone());
        assertFalse(code.isSuccess());
    }

    @Test
    void flush_fails_when_the_collection_throws() {
        reader.register(() -> {
            throw new IllegalStateException("collection failure");
        });
        CompletableResultCode code = await(reader.flush());
        assertTrue(code.isDone());
        assertFalse(code.isSuccess());
    }

    @Test
    void shutdown_exports_a_final_collection_then_shuts_the_exporter_down_once() {
        reader.register(() -> List.of(METRIC));
        assertTrue(reader.shutdown().isSuccess());
        assertTrue(reader.shutdown().isSuccess(), "a second shutdown is a successful no-op");
        // Exactly one final collection (BUG-20261028-03): the wake-up of the worker by
        // shutdown() must not export on top of the final drain.
        assertEquals(List.of(List.of(METRIC)), exporter.exports);
        assertEquals(1, exporter.shutdowns.get());
    }

    @Test
    void a_short_interval_exports_without_an_explicit_flush() {
        RecordingExporter periodic = new RecordingExporter();
        PeriodicMetricReader fast = PeriodicMetricReader.builder(periodic)
                .setInterval(Duration.ofMillis(10))
                .build();
        try {
            fast.register(() -> List.of(METRIC));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (periodic.exports.size() < 2 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(periodic.exports.size() >= 2, "periodic exports expected");
        } finally {
            fast.shutdown();
        }
    }

    @Test
    void invalid_intervals_keep_the_default_of_sixty_seconds() {
        RecordingExporter idle = new RecordingExporter();
        PeriodicMetricReader slow = PeriodicMetricReader.builder(idle)
                .setInterval(null)
                .setInterval(Duration.ZERO)
                .setInterval(Duration.ofMillis(-5))
                .build();
        slow.register(() -> List.of(METRIC));
        // With the 60 s default nothing is exported before the shutdown drain.
        assertTrue(idle.exports.isEmpty());
        slow.shutdown();
        assertFalse(idle.exports.isEmpty(), "the shutdown drain exports");
        assertEquals(1, idle.shutdowns.get());
    }

    @Test
    void the_reader_drives_a_provider_end_to_end() {
        try (SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build()) {
            provider.get("scope").counterBuilder("requests").build().add(4);
            assertTrue(await(provider.flush()).isSuccess());
        }
        MetricData exported = exporter.exports.getFirst().iterator().next();
        assertEquals("requests", exported.name());
        assertEquals(1, exporter.shutdowns.get());
    }
}
