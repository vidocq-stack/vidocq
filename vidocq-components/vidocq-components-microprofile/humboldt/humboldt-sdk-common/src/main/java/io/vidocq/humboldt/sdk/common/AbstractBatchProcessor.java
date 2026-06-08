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

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Shared batch processor skeleton for trace and log signals.
 *
 * <p>Virtual-thread worker + bounded queue + 4 export triggers:</p>
 * <ul>
 *   <li>the queue reaches {@code maxExportBatchSize}</li>
 *   <li>{@code scheduleDelay} has elapsed since the last batch</li>
 *   <li>explicit call to {@link #flushBase()}</li>
 *   <li>{@link #shutdownBase()} — drain + export final</li>
 * </ul>
 *
 * <p>Subclasses call {@link #offer(Object)} from their callback
 * ({@code onEnd} for SpanProcessor, {@code onEmit} for LogRecordProcessor)
 * and provide the batch export {@link Consumer} via the constructor.</p>
 *
 * <p>The worker runs on a virtual thread ({@link Thread#ofVirtual()}) —
 * no carrier-thread pinning on pure Java blocking operations
 * (see JEP 444).</p>
 *
 * @param <T> element type (typically SpanData or LogRecordData)
 */
public abstract class AbstractBatchProcessor<T> {

    private static final Logger LOG = System.getLogger(AbstractBatchProcessor.class.getName());

    private final String workerName;
    private final int maxExportBatchSize;
    private final long scheduleDelayNanos;
    private final LinkedBlockingQueue<T> queue;
    private final Consumer<List<T>> exportBatch;
    private final Runnable shutdownExporter;
    private final java.util.function.Supplier<CompletableResultCode> flushExporter;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread worker;
    private final Object flushLock = new Object();
    private volatile CompletableResultCode pendingFlush;

    /**
     * @param workerName        virtual thread name (for example {@code "humboldt-batch-span-processor"})
     * @param maxQueueSize      maximum queue size ({@code offer} returns false beyond it)
     * @param maxExportBatchSize maximum size of a batch sent to {@code exportBatch}
     * @param scheduleDelay     maximum delay between 2 automatic batches
     * @param exportBatch       callback invoked by the worker to export a batch
     * @param flushExporter     callback invoked by {@link #flushBase()} after draining
     * @param shutdownExporter  callback invoked by {@link #shutdownBase()} to release the exporter
     */
    protected AbstractBatchProcessor(
            String workerName,
            int maxQueueSize,
            int maxExportBatchSize,
            Duration scheduleDelay,
            Consumer<List<T>> exportBatch,
            java.util.function.Supplier<CompletableResultCode> flushExporter,
            Runnable shutdownExporter) {
        this.workerName = workerName;
        this.maxExportBatchSize = maxExportBatchSize;
        this.scheduleDelayNanos = scheduleDelay.toNanos();
        this.queue = new LinkedBlockingQueue<>(maxQueueSize);
        this.exportBatch = exportBatch;
        this.flushExporter = flushExporter;
        this.shutdownExporter = shutdownExporter;
        this.worker = Thread.ofVirtual()
                .name(workerName)
                .start(this::workerLoop);
    }

    /** To be called from the subclass callback (onEnd/onEmit). */
    protected final void offer(T item) {
        if (!running.get()) return;
        if (!queue.offer(item)) {
            LOG.log(Level.WARNING, "{0} queue full — item dropped", workerName);
        }
    }

    protected final CompletableResultCode flushBase() {
        if (queue.isEmpty()) return flushExporter.get();
        synchronized (flushLock) {
            CompletableResultCode code = new CompletableResultCode();
            pendingFlush = code;
            flushLock.notifyAll();
            return code;
        }
    }

    protected final CompletableResultCode shutdownBase() {
        if (!running.compareAndSet(true, false)) return CompletableResultCode.ofSuccess();
        synchronized (flushLock) {
            flushLock.notifyAll();
        }
        try {
            worker.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        shutdownExporter.run();
        return CompletableResultCode.ofSuccess();
    }

    private void workerLoop() {
        List<T> batch = new ArrayList<>(maxExportBatchSize);
        long lastExportNanos = System.nanoTime();
        while (running.get() || !queue.isEmpty()) {
            long now = System.nanoTime();
            long waitNanos = scheduleDelayNanos - (now - lastExportNanos);
            if (waitNanos > 0 && queue.size() < maxExportBatchSize) {
                synchronized (flushLock) {
                    if (pendingFlush == null && running.get() && queue.size() < maxExportBatchSize) {
                        try {
                            TimeUnit.NANOSECONDS.timedWait(flushLock, waitNanos);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
            queue.drainTo(batch, maxExportBatchSize);
            if (!batch.isEmpty()) {
                exportBatchSafe(batch);
                batch.clear();
                lastExportNanos = System.nanoTime();
            }
            CompletableResultCode requested;
            synchronized (flushLock) {
                requested = pendingFlush;
                pendingFlush = null;
            }
            if (requested != null) {
                queue.drainTo(batch);
                if (!batch.isEmpty()) {
                    exportBatchSafe(batch);
                    batch.clear();
                }
                requested.succeed();
            }
        }
    }

    private void exportBatchSafe(List<T> batch) {
        try {
            exportBatch.accept(List.copyOf(batch));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Ébatch export failure " + workerName + " (" + batch.size() + " items)", e);
        }
    }
}
