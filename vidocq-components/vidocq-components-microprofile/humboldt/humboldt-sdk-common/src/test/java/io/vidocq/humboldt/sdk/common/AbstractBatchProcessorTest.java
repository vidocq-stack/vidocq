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
package io.vidocq.humboldt.sdk.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A flush completes once everything offered before it is exported, including the batch the worker is exporting when
 * the flush is asked for (BUG-20261029-04).
 */
class AbstractBatchProcessorTest {

    /**
     * Exports by recording each item; an export blocks until {@link #release} opens, then returns {@link #result}
     * (an exporter that sends asynchronously returns a result that completes later).
     */
    private final List<String> exported = new CopyOnWriteArrayList<>();
    private final Semaphore exporting = new Semaphore(0);
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile CompletableResultCode result = CompletableResultCode.ofSuccess();
    private volatile CompletableResultCode exporterShutdown = CompletableResultCode.ofSuccess();
    private Processor processor;

    private final class Processor extends AbstractBatchProcessor<String> {
        Processor() {
            super("test-batch-processor", 100, 100, Duration.ofHours(1),
                    batch -> {
                        exporting.release();
                        try {
                            release.await();
                        } catch (InterruptedException _) {
                            Thread.currentThread().interrupt();
                        }
                        exported.addAll(batch);
                        return result;
                    },
                    CompletableResultCode::ofSuccess,
                    () -> exporterShutdown);
        }

        void add(String item) {
            offer(item);
        }

        CompletableResultCode flush() {
            return flushBase();
        }
    }

    @AfterEach
    void shutdown() {
        release.countDown();
        processor.shutdownBase();
    }

    /** Offers {@code first}, flushes, and returns once the worker is blocked exporting it. */
    private void workerBusyExporting(String first) throws InterruptedException {
        processor = new Processor();
        processor.add(first);
        processor.flush();
        assertTrue(exporting.tryAcquire(5, TimeUnit.SECONDS), "the worker exports " + first);
    }

    @Test
    void a_flush_waits_for_the_batch_the_worker_is_exporting() throws InterruptedException {
        workerBusyExporting("a");

        CompletableResultCode flush = processor.flush();
        assertFalse(flush.isDone(), "\"a\" is not exported yet");

        release.countDown();
        assertTrue(flush.join(5, TimeUnit.SECONDS).isSuccess());
        assertEquals(List.of("a"), exported);
    }

    @Test
    void every_flush_asked_while_the_worker_exports_completes() throws InterruptedException {
        workerBusyExporting("a");
        processor.add("b");

        CompletableResultCode first = processor.flush();
        CompletableResultCode second = processor.flush();
        release.countDown();

        assertTrue(first.join(5, TimeUnit.SECONDS).isSuccess(), "the first flush is not forgotten");
        assertTrue(second.join(5, TimeUnit.SECONDS).isSuccess());
        assertEquals(List.of("a", "b"), exported);
    }

    @Test
    void a_flush_waits_for_an_export_still_in_flight() throws InterruptedException {
        CompletableResultCode sending = new CompletableResultCode();
        result = sending;
        release.countDown();
        processor = new Processor();
        processor.add("a");

        CompletableResultCode flush = processor.flush();
        assertTrue(exporting.tryAcquire(5, TimeUnit.SECONDS));
        assertFalse(flush.isDone(), "the export of \"a\" is still in flight");

        sending.succeed();
        assertTrue(flush.join(5, TimeUnit.SECONDS).isSuccess());
    }

    @Test
    void a_failed_export_in_flight_fails_the_flush() throws InterruptedException {
        CompletableResultCode sending = new CompletableResultCode();
        result = sending;
        release.countDown();
        processor = new Processor();
        processor.add("a");

        CompletableResultCode flush = processor.flush();
        assertTrue(exporting.tryAcquire(5, TimeUnit.SECONDS));
        sending.fail();

        assertFalse(flush.join(5, TimeUnit.SECONDS).isSuccess());
    }

    @Test
    void a_shutdown_completes_with_the_exporter_shutdown() {
        CompletableResultCode closing = new CompletableResultCode();
        exporterShutdown = closing;
        release.countDown();
        processor = new Processor();

        CompletableResultCode shutdown = processor.shutdownBase();
        assertFalse(shutdown.isDone(), "the exporter is still shutting down");

        closing.succeed();
        assertTrue(shutdown.join(5, TimeUnit.SECONDS).isSuccess());
        assertTrue(processor.shutdownBase().isSuccess(), "a second shutdown has nothing left to do");
        assertTrue(processor.flush().isSuccess(), "a flush after shutdown asks the exporter only");
    }
}
