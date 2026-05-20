package io.vidocq.humboldt.sdk.log;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;
import io.vidocq.humboldt.sdk.log.export.LogRecordExporter;
import io.vidocq.humboldt.sdk.log.export.LogRecordProcessor;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Batch log processor — accumule les LogRecord dans une queue bornée et
 * déclenche l'export en batch sur un virtual thread dédié.
 *
 * <p>Pattern aligné sur {@code BatchSpanProcessor} : 4 déclencheurs (threshold
 * maxExportBatchSize, scheduleDelay timeout, flush(), shutdown drain).</p>
 */
public final class BatchLogRecordProcessor implements LogRecordProcessor {

    private static final Logger LOG = System.getLogger(BatchLogRecordProcessor.class.getName());

    private static final int DEFAULT_MAX_QUEUE = 2048;
    private static final int DEFAULT_MAX_BATCH = 512;
    private static final Duration DEFAULT_SCHEDULE = Duration.ofSeconds(1);

    private final LogRecordExporter exporter;
    private final int maxExportBatchSize;
    private final long scheduleDelayNanos;
    private final LinkedBlockingQueue<LogRecordData> queue;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread worker;
    private final Object flushLock = new Object();
    private volatile CompletableResultCode pendingFlush;

    public static Builder builder(LogRecordExporter exporter) {
        return new Builder(exporter);
    }

    private BatchLogRecordProcessor(Builder b) {
        this.exporter = b.exporter;
        this.maxExportBatchSize = b.maxExportBatchSize;
        this.scheduleDelayNanos = b.scheduleDelay.toNanos();
        this.queue = new LinkedBlockingQueue<>(b.maxQueueSize);
        this.worker = Thread.ofVirtual()
                .name("humboldt-batch-log-processor")
                .start(this::workerLoop);
    }

    @Override
    public void onEmit(LogRecordData record) {
        if (!running.get()) return;
        if (!queue.offer(record)) {
            LOG.log(Level.WARNING, "BatchLogRecordProcessor queue saturée — log dropé : {0}",
                    record.body());
        }
    }

    @Override
    public CompletableResultCode flush() {
        if (queue.isEmpty()) return exporter.flush();
        synchronized (flushLock) {
            CompletableResultCode rc = new CompletableResultCode();
            pendingFlush = rc;
            flushLock.notifyAll();
            return rc;
        }
    }

    @Override
    public CompletableResultCode shutdown() {
        if (!running.compareAndSet(true, false)) return CompletableResultCode.ofSuccess();
        synchronized (flushLock) {
            flushLock.notifyAll();
        }
        try {
            worker.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return exporter.shutdown();
    }

    private void workerLoop() {
        List<LogRecordData> batch = new ArrayList<>(maxExportBatchSize);
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
                exportBatch(batch);
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
                    exportBatch(batch);
                    batch.clear();
                }
                requested.succeed();
            }
        }
    }

    private void exportBatch(List<LogRecordData> batch) {
        try {
            exporter.export(List.copyOf(batch));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Échec d'export batch (" + batch.size() + " logs)", e);
        }
    }

    public static final class Builder {
        private final LogRecordExporter exporter;
        private int maxQueueSize = DEFAULT_MAX_QUEUE;
        private int maxExportBatchSize = DEFAULT_MAX_BATCH;
        private Duration scheduleDelay = DEFAULT_SCHEDULE;

        Builder(LogRecordExporter exporter) {
            if (exporter == null) throw new NullPointerException("exporter");
            this.exporter = exporter;
        }

        public Builder setMaxQueueSize(int n) {
            if (n > 0) this.maxQueueSize = n;
            return this;
        }

        public Builder setMaxExportBatchSize(int n) {
            if (n > 0) this.maxExportBatchSize = n;
            return this;
        }

        public Builder setScheduleDelay(Duration d) {
            if (d != null && !d.isNegative() && !d.isZero()) this.scheduleDelay = d;
            return this;
        }

        public BatchLogRecordProcessor build() {
            return new BatchLogRecordProcessor(this);
        }
    }
}
