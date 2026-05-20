package io.vidocq.humboldt.sdk.trace;

import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.humboldt.sdk.trace.export.CompletableResultCode;
import io.vidocq.humboldt.sdk.trace.export.SpanExporter;
import io.vidocq.humboldt.sdk.trace.export.SpanProcessor;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Batch span processor — accumule les spans dans une queue bornée et déclenche
 * l'export en batch sur un virtual thread dédié.
 *
 * <p>Quatre déclencheurs d'export :</p>
 * <ul>
 *   <li>la queue atteint {@code maxExportBatchSize} (export immédiat) ;</li>
 *   <li>le {@code scheduleDelay} écoulé depuis le dernier batch (export du restant) ;</li>
 *   <li>appel explicite à {@link #flush()} ;</li>
 *   <li>{@link #shutdown()} — drain + export final.</li>
 * </ul>
 *
 * <p>N'exporte que les spans samplés ({@code SpanContext.isSampled() == true}).</p>
 *
 * <p>Le worker s'exécute sur un virtual thread ({@code Thread.ofVirtual()}) —
 * pas de pinning de carrier thread (cf. JEP 444), pas de consommation de
 * {@code ForkJoinPool.commonPool}.</p>
 */
public final class BatchSpanProcessor implements SpanProcessor {

    private static final Logger LOG = System.getLogger(BatchSpanProcessor.class.getName());

    private static final int DEFAULT_MAX_QUEUE = 2048;
    private static final int DEFAULT_MAX_BATCH = 512;
    private static final Duration DEFAULT_SCHEDULE = Duration.ofSeconds(5);

    private final SpanExporter exporter;
    private final int maxExportBatchSize;
    private final long scheduleDelayNanos;
    private final LinkedBlockingQueue<SpanData> queue;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread worker;
    private final Object flushLock = new Object();
    private volatile CompletableResultCode pendingFlush;

    public static Builder builder(SpanExporter exporter) {
        return new Builder(exporter);
    }

    private BatchSpanProcessor(Builder b) {
        this.exporter = b.exporter;
        this.maxExportBatchSize = b.maxExportBatchSize;
        this.scheduleDelayNanos = b.scheduleDelay.toNanos();
        this.queue = new LinkedBlockingQueue<>(b.maxQueueSize);
        this.worker = Thread.ofVirtual()
                .name("humboldt-batch-span-processor")
                .start(this::workerLoop);
    }

    @Override
    public void onStart(Context parentContext, ReadableSpan span) {
        // no-op
    }

    @Override
    public void onEnd(ReadableSpan span) {
        if (!running.get()) return;
        if (!span.getSpanContext().isSampled()) return;
        if (!queue.offer(span.toSpanData())) {
            LOG.log(Level.WARNING, "BatchSpanProcessor queue saturée — span dropé : {0}", span.getName());
        }
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public CompletableResultCode flush() {
        if (queue.isEmpty()) return exporter.flush();
        synchronized (flushLock) {
            CompletableResultCode code = new CompletableResultCode();
            pendingFlush = code;
            flushLock.notifyAll();
            return code;
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
        List<SpanData> batch = new ArrayList<>(maxExportBatchSize);
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
                exporter.flush().whenComplete(() -> {
                    if (exporter.flush().isSuccess()) requested.succeed();
                    else requested.fail();
                });
                requested.succeed();
            }
        }
    }

    private void exportBatch(List<SpanData> batch) {
        try {
            exporter.export(List.copyOf(batch));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Échec d'export batch (" + batch.size() + " spans)", e);
        }
    }

    public static final class Builder {
        private final SpanExporter exporter;
        private int maxQueueSize = DEFAULT_MAX_QUEUE;
        private int maxExportBatchSize = DEFAULT_MAX_BATCH;
        private Duration scheduleDelay = DEFAULT_SCHEDULE;

        Builder(SpanExporter exporter) {
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

        public BatchSpanProcessor build() {
            return new BatchSpanProcessor(this);
        }
    }
}
