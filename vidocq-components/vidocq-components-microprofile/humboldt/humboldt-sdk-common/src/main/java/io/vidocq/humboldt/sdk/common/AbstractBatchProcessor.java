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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

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
 * and provide the batch export {@link Function} via the constructor.</p>
 *
 * <p>A flush is served by the worker, after the batch it may be exporting, and completes once every export started
 * before it has completed and the exporter has flushed: a successful flush means that everything offered before it
 * was exported (BUG-20261029-04).</p>
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
    private final Function<List<T>, CompletableResultCode> exportBatch;
    private final java.util.function.Supplier<CompletableResultCode> shutdownExporter;
    private final java.util.function.Supplier<CompletableResultCode> flushExporter;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread worker;
    private final Object flushLock = new Object();
    /** The flushes asked for and not served yet; guarded by {@link #flushLock}. */
    private final List<CompletableResultCode> flushRequests = new ArrayList<>();
    /** The exports that returned before their result completed (an exporter sending asynchronously). */
    private final Set<CompletableResultCode> inFlight = ConcurrentHashMap.newKeySet();
    /** Whether an export failed since the last flush was served: that flush reports the failure. */
    private final AtomicBoolean exportFailed = new AtomicBoolean();

    /**
     * @param workerName        virtual thread name (for example {@code "humboldt-batch-span-processor"})
     * @param maxQueueSize      maximum queue size ({@code offer} returns false beyond it)
     * @param maxExportBatchSize maximum size of a batch sent to {@code exportBatch}
     * @param scheduleDelay     maximum delay between 2 automatic batches
     * @param exportBatch       callback invoked by the worker to export a batch; its result may complete later
     * @param flushExporter     callback invoked by {@link #flushBase()} after draining
     * @param shutdownExporter  callback invoked by {@link #shutdownBase()} to release the exporter; the shutdown
     *                          completes with its result, once the exports it waits for have completed
     */
    protected AbstractBatchProcessor(
            String workerName,
            int maxQueueSize,
            int maxExportBatchSize,
            Duration scheduleDelay,
            Function<List<T>, CompletableResultCode> exportBatch,
            java.util.function.Supplier<CompletableResultCode> flushExporter,
            java.util.function.Supplier<CompletableResultCode> shutdownExporter) {
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

    /**
     * Exports everything offered so far. Even with an empty queue the request goes through the worker: the worker may
     * hold a batch it took from the queue and has not exported yet.
     */
    protected final CompletableResultCode flushBase() {
        synchronized (flushLock) {
            if (running.get()) {
                CompletableResultCode code = new CompletableResultCode();
                flushRequests.add(code);
                flushLock.notifyAll();
                return code;
            }
        }
        return flushExporter.get();
    }

    protected final CompletableResultCode shutdownBase() {
        synchronized (flushLock) {
            if (!running.compareAndSet(true, false)) return CompletableResultCode.ofSuccess();
            flushLock.notifyAll();
        }
        try {
            worker.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        return shutdownExporter.get();
    }

    private void workerLoop() {
        List<T> batch = new ArrayList<>(maxExportBatchSize);
        long lastExportNanos = System.nanoTime();
        while (running.get() || !queue.isEmpty() || hasFlushRequests()) {
            long now = System.nanoTime();
            long waitNanos = scheduleDelayNanos - (now - lastExportNanos);
            if (waitNanos > 0 && queue.size() < maxExportBatchSize) {
                awaitBatchOrFlush(waitNanos);
            }
            queue.drainTo(batch, maxExportBatchSize);
            if (!batch.isEmpty()) {
                exportBatchSafe(batch);
                batch.clear();
                lastExportNanos = System.nanoTime();
            }
            serveFlushRequests(batch);
        }
    }

    private boolean hasFlushRequests() {
        synchronized (flushLock) {
            return !flushRequests.isEmpty();
        }
    }

    /**
     * Waits up to {@code waitNanos} for a flush request, a shutdown or a full batch, unless one is already there.
     */
    private void awaitBatchOrFlush(long waitNanos) {
        synchronized (flushLock) {
            if (flushRequests.isEmpty() && running.get() && queue.size() < maxExportBatchSize) {
                try {
                    TimeUnit.NANOSECONDS.timedWait(flushLock, waitNanos);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * If flushes were requested, exports everything queued (using the empty {@code batch} buffer), then completes them
     * once the exports still in flight and the exporter's own flush have completed.
     */
    private void serveFlushRequests(List<T> batch) {
        List<CompletableResultCode> requested;
        synchronized (flushLock) {
            if (flushRequests.isEmpty()) return;
            requested = List.copyOf(flushRequests);
            flushRequests.clear();
        }
        queue.drainTo(batch);
        if (!batch.isEmpty()) {
            exportBatchSafe(batch);
            batch.clear();
        }
        List<CompletableResultCode> pending = new ArrayList<>(inFlight);
        pending.add(flushExporter.get());
        CompletableResultCode done = CompletableResultCode.ofAll(pending);
        done.whenComplete(() -> {
            boolean success = done.isSuccess() & !exportFailed.getAndSet(false);
            for (CompletableResultCode r : requested) {
                if (success) r.succeed();
                else r.fail();
            }
        });
    }

    private void exportBatchSafe(List<T> batch) {
        try {
            CompletableResultCode result = exportBatch.apply(List.copyOf(batch));
            if (result == null) return;
            if (!result.isDone()) inFlight.add(result);
            result.whenComplete(() -> {
                if (!result.isSuccess()) exportFailed.set(true);
                inFlight.remove(result);
            });
        } catch (RuntimeException e) {
            exportFailed.set(true);
            LOG.log(Level.WARNING, "Batch export failure " + workerName + " (" + batch.size() + " items)", e);
        }
    }
}
